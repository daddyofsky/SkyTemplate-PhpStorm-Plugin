package com.novaframework.templatelang.sky

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Smart re-indent on closer / branch typing — the SkyTemplate analogue
 * of the HTML editor's "type `</div>` and the line jumps to align with
 * `<div>`" behaviour.
 *
 * Trigger conditions (all must hold):
 *   1. The typed character is `}` and the line up to the caret ends
 *      with a complete `{ … }` tag.
 *   2. The tag's `{` is the first non-whitespace on its line, and only
 *      whitespace follows the closing `}` to end-of-line — i.e. the tag
 *      occupies the line on its own.
 *   3. The tag classifies as a CLOSE (`{/}`, `{end}`) or BRANCH
 *      (`{:}`, `{:expr}`, `{else}`, `{elseif x}`).
 *   4. There is a matching opener somewhere above (LIFO + indent-aware
 *      unwinding, same algorithm folding / inspections use). Orphan
 *      branches and orphan closers leave the line untouched — the
 *      indent is whatever the user typed and they probably want it
 *      that way until the opener arrives.
 *
 * When all four hold, the line's leading whitespace is rewritten to
 * match the opener line's leading whitespace. Caret follows the edit
 * automatically — the platform repositions it relative to the document
 * change.
 *
 * Host scope: SkyTemplate, HTML, and XML files (same as the Enter
 * handler). Pure HTML / JS / CSS editing is unaffected because the
 * trigger is gated by the SkyTemplate-tag classifier.
 */
class SkyTemplateClosingTagAligner : TypedHandlerDelegate() {

    override fun charTyped(
        c: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
    ): Result {
        if (c != '}') return Result.CONTINUE

        val lang = file.language
        if (lang !== SkyTemplateLanguage &&
            lang !== com.intellij.lang.html.HTMLLanguage.INSTANCE &&
            lang !== com.intellij.lang.xml.XMLLanguage.INSTANCE
        ) return Result.CONTINUE
        if (!TemplateLangFileFilter.shouldProcess(file)) return Result.CONTINUE

        val document = editor.document
        val caretOffset = editor.caretModel.offset
        val edit = SkyTemplateClosingTagAlignerLogic
            .computeAlignment(document.charsSequence, caretOffset)
            ?: return Result.CONTINUE

        document.replaceString(edit.from, edit.to, edit.replacement)
        SkyTemplateRangeCache.invalidate()
        PsiDocumentManager.getInstance(project).commitDocument(document)
        return Result.CONTINUE
    }
}

/**
 * Pure logic for [SkyTemplateClosingTagAligner] — kept UI-free so it
 * can be unit-tested without a fixture.
 */
object SkyTemplateClosingTagAlignerLogic {

    /** A single text edit: `text[from, to)` is replaced with [replacement]. */
    data class Edit(val from: Int, val to: Int, val replacement: String)

    /**
     * Decide whether the line containing the just-typed `}` at
     * [caretOffset] should be re-indented, and return the edit if so.
     * Returns null when any trigger condition fails.
     */
    fun computeAlignment(text: CharSequence, caretOffset: Int): Edit? {
        if (caretOffset <= 0 || caretOffset > text.length) return null
        if (text[caretOffset - 1] != '}') return null

        // Locate the matching `{` on the same line.
        val openOffset = findMatchingOpenOnLine(text, caretOffset - 1) ?: return null

        // The tag must be the only content of the line — preceded by
        // pure whitespace, and followed (after the `}`) by whitespace
        // up to end-of-line.
        val lineStart = lineStartOf(text, openOffset)
        for (i in lineStart until openOffset) {
            if (text[i] != ' ' && text[i] != '\t') return null
        }
        var afterClose = caretOffset
        while (afterClose < text.length && (text[afterClose] == ' ' || text[afterClose] == '\t')) afterClose++
        if (afterClose < text.length && text[afterClose] != '\n' && text[afterClose] != '\r') return null

        val kind = classifyKind(text, openOffset, caretOffset)
        if (kind != Kind.CLOSE && kind != Kind.BRANCH) return null

        // Find the matching opener via LIFO + indent-unwinding pairing
        // walked over every tag BEFORE the just-typed one.
        val openerOffset = findMatchingOpener(text, openOffset, kind) ?: return null

        // Compute the desired vs. current indent.
        val desiredIndent = lineIndentString(text, openerOffset)
        val currentIndent = text.subSequence(lineStart, openOffset).toString()
        if (currentIndent == desiredIndent) return null

        return Edit(lineStart, openOffset, desiredIndent)
    }

    /**
     * Walk the document up to [ourOpenOffset] and return the offset of
     * the opener that the just-typed CLOSE / BRANCH tag pairs with under
     * LIFO + indent-unwinding rules. Returns null when no opener pairs
     * (orphan closer / orphan branch).
     *
     * The indent-unwinding step uses the typed tag's CURRENT indent
     * (which may be wrong — the user typed it that way) so the matching
     * opener follows the user's intent: typing `{/}` at col 0 inside
     * a deeply-nested file unwinds back to the outermost still-open
     * block; typing at col 8 stays at the inner block.
     */
    private fun findMatchingOpener(
        text: CharSequence,
        ourOpenOffset: Int,
        ourKind: Kind,
    ): Int? {
        val ranges = SkyTemplateRangeCache.get(text)
        // Stack of opener offsets — paired with their line indent so the
        // unwinding step can compare against the closer's indent without
        // recomputing.
        val stack = ArrayDeque<Pair<Int, Int>>()                       // (openerOffset, openerIndentWidth)

        for (range in ranges) {
            if (range.startOffset >= ourOpenOffset) break
            when (classifyKind(text, range.startOffset, range.endOffset)) {
                Kind.OPEN -> {
                    stack.addLast(range.startOffset to lineIndentWidth(text, range.startOffset))
                }
                Kind.CLOSE -> {
                    if (stack.isNotEmpty()) {
                        val closerIndent = lineIndentWidth(text, range.startOffset)
                        while (stack.isNotEmpty() && stack.last().second > closerIndent) {
                            stack.removeLast()
                        }
                        if (stack.isNotEmpty()) stack.removeLast()
                    }
                }
                else -> {}
            }
        }

        // Now apply indent-unwinding for the just-typed tag, using its
        // CURRENT (possibly-wrong) indent. After this, the stack top is
        // our matching opener regardless of whether kind is CLOSE or
        // BRANCH — the only difference is BRANCH does not pop afterward
        // (we just need the offset, so pop / not-pop is moot here).
        val ourIndent = lineIndentWidth(text, ourOpenOffset)
        while (stack.isNotEmpty() && stack.last().second > ourIndent) {
            stack.removeLast()
        }
        return stack.lastOrNull()?.first
    }

    // ── Tag classification ──────────────────────────────────────────────

    enum class Kind { OPEN, CLOSE, BRANCH, OTHER }

    /**
     * Classify the SkyTemplate tag whose `{` sits at [openOffset] and
     * whose closing `}` is at [closeEndOffset] - 1. Same logic as the
     * post-format / Enter handler classifiers — kept local rather than
     * shared because the surface here is small and the duplication
     * keeps each handler independently auditable.
     */
    private fun classifyKind(text: CharSequence, openOffset: Int, closeEndOffset: Int): Kind {
        val (innerOpen, innerCloseEnd) = SkyTemplateRanges.innerBraceBounds(text, openOffset, closeEndOffset)
            ?: return Kind.OTHER
        val bodyStart = innerOpen + 1
        val bodyEnd = innerCloseEnd - 1
        if (bodyEnd <= bodyStart) return Kind.OTHER

        var i = bodyStart
        while (i < bodyEnd && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= bodyEnd) return Kind.OTHER
        val first = text[i]
        val hasLeadingWs = i > bodyStart

        if (first == '/') {
            // `{/}` / `{/  }` — closer with optional trailing whitespace.
            // `{/  // comment}` — closer + line comment. Anything else
            // (`{/foo}`) is not a closer — mirrors FoldingBuilder's rule.
            var j = i + 1
            while (j < bodyEnd && (text[j] == ' ' || text[j] == '\t')) j++
            if (j >= bodyEnd) return Kind.CLOSE
            if (j + 1 < bodyEnd && text[j] == '/' && text[j + 1] == '/') return Kind.CLOSE
            return Kind.OTHER
        }
        if (first == ':') return Kind.BRANCH
        if (first == '?' || first == '@' || first == '%') return Kind.OPEN

        if (hasLeadingWs) return Kind.OTHER
        if (!(first.isLetter() || first == '_')) return Kind.OTHER

        var j = i + 1
        while (j < bodyEnd && (text[j].isLetterOrDigit() || text[j] == '_')) j++
        val word = text.subSequence(i, j).toString().lowercase()
        val followedByBoundary = j >= bodyEnd || !(text[j].isLetterOrDigit() || text[j] == '_')
        if (!followedByBoundary) return Kind.OTHER

        return when (word) {
            "loop", "foreach", "for", "while", "if", "each" -> Kind.OPEN
            "else", "elseif" -> Kind.BRANCH
            "end" -> Kind.CLOSE
            else -> Kind.OTHER
        }
    }

    // ── Text helpers ────────────────────────────────────────────────────

    /**
     * Walk back from a `}` at [closeOffset] to its matching `{` on the
     * SAME LINE, tracking nested brace depth. Returns null when no
     * match is found before the line start (a stray `}` typed mid-line
     * is not our concern).
     */
    private fun findMatchingOpenOnLine(text: CharSequence, closeOffset: Int): Int? {
        var depth = 1
        var i = closeOffset - 1
        while (i >= 0) {
            when (text[i]) {
                '\n' -> return null
                '}' -> depth++
                '{' -> {
                    if (i > 0 && text[i - 1] == '$') {
                        // `${` is JS template-literal syntax — not a SkyTemplate open.
                    } else {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i--
        }
        return null
    }

    private fun lineStartOf(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineIndentString(text: CharSequence, offset: Int): String {
        val start = lineStartOf(text, offset)
        var i = start
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.subSequence(start, i).toString()
    }

    private fun lineIndentWidth(text: CharSequence, offset: Int): Int {
        val start = lineStartOf(text, offset)
        var i = start
        var w = 0
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
            w++
            i++
        }
        return w
    }
}
