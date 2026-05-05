package com.novaframework.templatelang.sky

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.application.options.CodeStyle

/**
 * Enter handler that auto-closes SkyTemplate block tags.
 *
 * When the caret sits **immediately after** the closing `}` of an opening
 * block tag and the same line does not already contain a closing `{/}` /
 * `{end}`, pressing Enter:
 *
 *   1. Inserts a newline plus the surrounding indent + one indent step
 *      (the spot where the user will type the body),
 *   2. Appends another newline + the surrounding indent + a literal `{/}`
 *      to auto-close the block,
 *   3. Leaves the caret on the indented blank line in between.
 *
 * The same UX as `{` Enter `}` in JetBrains IDEs / VS Code, scoped to
 * SkyTemplate's directive surface.
 *
 * Recognised opening forms:
 *
 *   - **Keyword form** (no leading whitespace allowed inside the tag —
 *     mirrors the lexer's `atTagStart` rule): `{loop …}`, `{foreach …}`,
 *     `{for …}`, `{while …}`, `{if …}`, `{else}`, `{each …}`.
 *   - **Prefix form**: `{?…}` (if), `{:…}` (else / elseif), `{@…}`, `{%…}`
 *     (loop aliases). Horizontal whitespace between `{` and the prefix is
 *     tolerated to match the compiler's permissive lead.
 *
 * Closing forms `{/}` and `{end}` are NOT triggers — the user is closing
 * a block, not opening one.
 *
 * Comment ranges (`{*…*}`, `<!--{*…*}-->`) suppress the trigger so that an
 * opening keyword that *appears* inside a comment doesn't fire.
 *
 * Host scope: fires in any file whose primary language is SkyTemplate,
 * HTML, or XML. The internal classifier (looksLikeTemplateBody +
 * isOpeningBlockTag) keeps the handler quiet on non-SkyTemplate `{ … }`
 * shapes (e.g. JS object literals), so plain HTML editing is unaffected
 * unless the user actually types a SkyTemplate opener.
 */
class SkyTemplateEnterHandler : EnterHandlerDelegateAdapter() {

    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        // Run for SkyTemplate, HTML, and XML hosts. The classifier inside
        // analyzeBefore only triggers on actual SkyTemplate openers so
        // pure HTML / JS / CSS editing is unaffected.
        val lang = file.language
        if (lang !== SkyTemplateLanguage &&
            lang !== com.intellij.lang.html.HTMLLanguage.INSTANCE &&
            lang !== com.intellij.lang.xml.XMLLanguage.INSTANCE
        ) {
            return EnterHandlerDelegate.Result.Continue
        }

        val document = editor.document
        val text = document.charsSequence
        val offset = caretOffset.get() ?: return EnterHandlerDelegate.Result.Continue
        if (offset <= 0 || offset > text.length) return EnterHandlerDelegate.Result.Continue

        val analysis = SkyTemplateEnterHandlerLogic.analyzeBefore(text, offset)
            ?: return EnterHandlerDelegate.Result.Continue

        // Resolve indent step from the project's code style. Falls back to
        // 4 spaces if the SkyTemplate language has no explicit indent options
        // (which it doesn't until we add a Code Style page in M9).
        val indentStep = resolveIndentStep(file)

        val insertion = buildString {
            append('\n')
            append(analysis.indent)
            append(indentStep)
            if (analysis.needsAutoClose) {
                // Caret will be placed at the end of this prefix; the closer
                // line follows on the next document line.
                append('\n')
                append(analysis.indent)
                append("{/}")
            }
        }

        // Compute caret position BEFORE the insertion so we can place it at
        // the end of the indented blank line (just after the indent step,
        // before the closer if there is one).
        val caretAfter = offset + 1 /* '\n' */ + analysis.indent.length + indentStep.length

        document.insertString(offset, insertion)
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        editor.caretModel.moveToOffset(caretAfter)

        // Stop — we own the entire Enter behaviour for this case. Returning
        // Continue would let the platform also insert its own newline and
        // re-indent, which would conflict with what we just wrote.
        return EnterHandlerDelegate.Result.Stop
    }

    /**
     * Resolve the indent step from the project's code style. SkyTemplate
     * does not register its own `LanguageCodeStyleSettingsProvider` yet
     * (M9's Code Style page is a separate item), so we ask for the
     * SkyTemplate file type's options — which falls back to the platform
     * default (4 spaces).
     */
    private fun resolveIndentStep(file: PsiFile): String {
        return try {
            val indentOptions = CodeStyle.getIndentOptions(file)
            if (indentOptions.USE_TAB_CHARACTER) {
                "\t"
            } else {
                " ".repeat(indentOptions.INDENT_SIZE.coerceAtLeast(1))
            }
        } catch (_: Throwable) {
            // Defensive: the IDE's settings call paths can fail in odd test
            // setups. Fall back to a sensible default rather than break Enter.
            "    "
        }
    }
}

/**
 * Pure logic for the Enter handler — kept UI-free so it can be exercised by
 * standalone unit tests. All decisions are made on the immutable text +
 * caret offset.
 */
object SkyTemplateEnterHandlerLogic {

    /** Result of analysing the position immediately preceding the caret. */
    data class EnterAnalysis(
        /** Indent (horizontal whitespace prefix) of the line the caret is on. */
        val indent: String,
        /** True if the handler should auto-insert a `{/}` line below. */
        val needsAutoClose: Boolean,
    )

    /**
     * Inspect [text] just before [caretOffset] and decide whether this Enter
     * press follows an opening SkyTemplate block tag.
     *
     * Returns null if the caret is not in trigger position. The caller should
     * fall through to the default Enter handler.
     */
    fun analyzeBefore(text: CharSequence, caretOffset: Int): EnterAnalysis? {
        if (caretOffset <= 0 || caretOffset > text.length) return null

        // The character immediately before the caret must be the closing `}`
        // of a tag — caret in the middle of a tag (`{loop x|`) or after
        // arbitrary content does not trigger.
        if (text[caretOffset - 1] != '}') return null

        // Walk back to the matching `{` for that `}`, respecting nested
        // braces (string literals like `'a}b'` and arbitrary CSS / JS in the
        // body of a template-language file are uncommon, but we still want
        // depth tracking so we don't confuse `{?cond}{loop x}` either way).
        val openOffset = findMatchingOpen(text, caretOffset - 1) ?: return null

        // Reject if the entire `{ … }` span lies inside a `{*…*}` (or
        // `<!--{*…*}-->`) comment — those should never trigger.
        val commentRanges = SkyTemplateRanges.computeCommentRanges(text)
        if (commentRanges.any { it.startOffset <= openOffset && caretOffset <= it.endOffset }) {
            return null
        }

        // Body must look like a SkyTemplate tag — gates out CSS / JS
        // shapes that share the brace form. Critical for HTML / XML host
        // files where plain `{ }` content is common (object literals,
        // CSS rules, etc.).
        if (!SkyTemplateRanges.looksLikeTemplateBody(text, openOffset, caretOffset)) {
            return null
        }

        // Classify: is the body an opening block tag, a branch, or neither?
        val kind = classifyOpening(text, openOffset, caretOffset) ?: return null

        // Indent of the line containing the caret (whitespace-only prefix).
        val indent = lineIndentBefore(text, openOffset)

        // Auto-`{/}` policy:
        //   - BLOCK_OPEN: insert `{/}` unless the same line already has one.
        //   - BRANCH (`{:}`, `{else}`, `{elseif}`): NEVER insert `{/}`.
        //     Branches always sit inside an existing block; the surrounding
        //     opener was almost certainly auto-closed when the user typed
        //     it (e.g. `{?cond}` Enter already inserted `{/}`), so adding
        //     another `{/}` just duplicates the closer. The user still
        //     gets the indented blank line for the branch body.
        val needsAutoClose = when (kind) {
            OpeningKind.BLOCK_OPEN -> !lineAfterContainsCloser(text, caretOffset)
            OpeningKind.BRANCH -> false
        }

        return EnterAnalysis(indent = indent, needsAutoClose = needsAutoClose)
    }

    /**
     * Walk back from a `}` at [closeOffset] to its matching `{`, tracking
     * nested brace depth. Returns null if no match is found before the
     * line start the `}` belongs to (a sanity bound — block tags don't span
     * line breaks in normal SkyTemplate code, and bounding the search keeps
     * pathological inputs cheap).
     */
    private fun findMatchingOpen(text: CharSequence, closeOffset: Int): Int? {
        var depth = 1
        var i = closeOffset - 1
        // Track if the `}` is inside a string within the brace span — we
        // only enter "string mode" while scanning content from the matched
        // `{` forwards, so a backwards walk just respects whatever character
        // it sees. Acceptable for the trigger-detection use case: a `}`
        // inside `'…'` is unusual in SkyTemplate tags, and a false negative
        // simply falls through to the default handler.
        while (i >= 0) {
            when (text[i]) {
                '}' -> depth++
                '{' -> {
                    // Skip `${` (JS / shell template literal) — it is not a
                    // SkyTemplate open. Treat it as not-an-open so depth
                    // tracking still resolves the real outer `{`.
                    if (i > 0 && text[i - 1] == '$') {
                        // do nothing — keep looking back
                    } else {
                        depth--
                        if (depth == 0) return i
                    }
                }
                '\n' -> {
                    // Block-tag opens stay on a single line. Bail if we
                    // cross a newline before resolving — the `}` was likely
                    // a stray.
                    return null
                }
            }
            i--
        }
        return null
    }

    /**
     * What kind of opening-related tag is this `{ … }` span?
     *   - [OpeningKind.BLOCK_OPEN] — the tag opens a fresh block that the
     *     handler should auto-close with `{/}`. Examples:
     *     `{loop xs}`, `{?cond}`, `{@items}`, `{if x}`.
     *   - [OpeningKind.BRANCH] — the tag continues an already-open block.
     *     The handler still produces the indented blank line for the
     *     branch body but does NOT add another `{/}`. Examples: `{:}`,
     *     `{:case}`, `{else}`, `{elseif x}`.
     *   - `null` — closing form (`{/}`, `{end}`), elvis (`{?:expr}`), or
     *     non-block tag (`{=foo()}`, `{var}`, …). The handler does
     *     nothing.
     */
    private enum class OpeningKind { BLOCK_OPEN, BRANCH }

    private fun classifyOpening(text: CharSequence, openOffset: Int, closeEndOffset: Int): OpeningKind? {
        // Skip the outer braces.
        val bodyStart = openOffset + 1
        val bodyEnd = closeEndOffset - 1
        if (bodyEnd <= bodyStart) return null

        // Skip leading horizontal whitespace (Template_ permissive).
        var i = bodyStart
        while (i < bodyEnd && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= bodyEnd) return null

        val first = text[i]
        val hasLeadingWs = i > bodyStart

        // Closing tag `{/}` — `/` is a tag prefix char, body is just `/`.
        if (first == '/') return null

        // Branch prefix `:` — `{:}`, `{:case}`, `{:expr}`. Not a fresh
        // block; sits inside an already-open block.
        if (first == ':') return OpeningKind.BRANCH

        // Reject `{?:expr}` (elvis) — it IS a block opener per the
        // pairing logic, but the SkyTemplate compiler emits its closing
        // brace as part of `{/}` matching, and the handler here is
        // shape-based: leave elvis Enter handling to the user (a
        // standalone `{?:val}` would become an unclosed block under
        // current semantics, but auto-closing it would mask the
        // diagnostic). Treat as null.
        if (first == '?' && i + 1 < bodyEnd && text[i + 1] == ':') return null

        // Prefix form openers: `?`, `@`, `%`. The other prefix chars
        // (`=`, `;`, `#`, `+`, `]`, `&`, `\`) are non-block.
        if (first == '?' || first == '@' || first == '%') return OpeningKind.BLOCK_OPEN

        // Keyword form: must be IMMEDIATELY at body start (mirrors
        // SkyTemplateLexer.atTagStart's strictness for keywords).
        if (hasLeadingWs) return null
        if (!(first.isLetter() || first == '_')) return null

        var j = i + 1
        while (j < bodyEnd && (text[j].isLetterOrDigit() || text[j] == '_')) j++
        val word = text.subSequence(i, j).toString().lowercase()
        val followedByBoundary = j >= bodyEnd || !(text[j].isLetterOrDigit() || text[j] == '_')
        if (!followedByBoundary) return null

        return when (word) {
            in OPENING_KEYWORDS -> OpeningKind.BLOCK_OPEN
            in BRANCH_KEYWORDS -> OpeningKind.BRANCH
            else -> null
        }
    }

    /** Keywords that open a fresh block. */
    private val OPENING_KEYWORDS = setOf(
        "loop", "foreach", "for", "while", "if", "each",
    )

    /** Keywords that re-enter an already-open block (no auto-`{/}`). */
    private val BRANCH_KEYWORDS = setOf(
        "else", "elseif",
    )

    /**
     * Extract the leading whitespace (spaces / tabs only) of the line that
     * contains [offset]. The result is the exact byte sequence — preserves
     * tabs vs. spaces as the user wrote them.
     */
    private fun lineIndentBefore(text: CharSequence, offset: Int): String {
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var i = lineStart
        while (i < offset && (text[i] == ' ' || text[i] == '\t')) i++
        return text.subSequence(lineStart, i).toString()
    }

    /**
     * Look ahead from [offset] until end-of-line and return true if the
     * remainder of the current line contains a `{/}` or `{end}` closer.
     * Used to suppress auto-close insertion when the user has already typed
     * (or pasted) the closer on the same line.
     */
    private fun lineAfterContainsCloser(text: CharSequence, offset: Int): Boolean {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        val tail = text.subSequence(offset, i).toString()
        // Cheap substring scan — these are short tails, and false positives
        // (e.g. `{/}` inside a string literal on the same line) just suppress
        // the auto-close, which degrades to a no-op rather than a bug.
        return tail.contains("{/}") || endTagPattern.containsMatchIn(tail)
    }

    /** `{end}` with optional internal whitespace — `{ end }` should also count. */
    private val endTagPattern = Regex("""\{\s*end\s*}""", RegexOption.IGNORE_CASE)
}
