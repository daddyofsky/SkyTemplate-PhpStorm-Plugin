package com.novaframework.templatelang.sky

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * "Join Sky Block" — collapses the multi-line SkyTemplate block under
 * the caret into a single line. Mirrors `Edit > Join Lines` in spirit
 * but scoped to a SkyTemplate `{?…}…{/}` / `{@…}…{/}` / `{loop …}…{/}`
 * pair (and any branches in between).
 *
 * Effect: `{?cond}\n    body\n{/}` → `{?cond}body{/}`. Branches collapse
 * inline too: `{?cond}\n    a\n{:}\n    b\n{/}` → `{?cond}a{:}b{/}`.
 *
 * Caret can sit anywhere inside or directly on the block; the action
 * finds the innermost enclosing block under it.
 */
class SkyTemplateJoinBlockAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val ctx = blockContext(e)
        e.presentation.isEnabledAndVisible = ctx != null && ctx.canJoin
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ctx = blockContext(e) ?: return
        if (!ctx.canJoin) return
        val project = e.project ?: return
        val edit = SkyTemplateBlockActionsLogic.computeJoinEdit(
            text = ctx.editor.document.charsSequence,
            caretOffset = ctx.editor.caretModel.offset,
        ) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Join Sky Block", null, {
            ctx.editor.document.replaceString(edit.from, edit.to, edit.replacement)
            PsiDocumentManager.getInstance(project).commitDocument(ctx.editor.document)
            // Place caret at the closer's start so the user can see the
            // collapsed result immediately.
            ctx.editor.caretModel.moveToOffset(edit.from + edit.caretOffsetInReplacement)
        })
    }

    private fun blockContext(e: AnActionEvent): BlockActionContext? = blockActionContext(e)
}

/**
 * "Split Sky Block" — expands a single-line SkyTemplate block under the
 * caret into a multi-line shape, with the body / branches indented one
 * step deeper than the matching opener.
 *
 * Effect: `{?cond}body{/}` → `{?cond}\n    body\n{/}`. Branches split
 * onto their own lines too: `{?cond}a{:}b{/}` →
 * `{?cond}\n    a\n{:}\n    b\n{/}`.
 *
 * Caret can sit anywhere inside or directly on the block.
 */
class SkyTemplateSplitBlockAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val ctx = blockContext(e)
        e.presentation.isEnabledAndVisible = ctx != null && ctx.canSplit
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ctx = blockContext(e) ?: return
        if (!ctx.canSplit) return
        val project = e.project ?: return
        val indentStep = resolveIndentStep(ctx.psi)
        val edit = SkyTemplateBlockActionsLogic.computeSplitEdit(
            text = ctx.editor.document.charsSequence,
            caretOffset = ctx.editor.caretModel.offset,
            indentStep = indentStep,
        ) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Split Sky Block", null, {
            ctx.editor.document.replaceString(edit.from, edit.to, edit.replacement)
            PsiDocumentManager.getInstance(project).commitDocument(ctx.editor.document)
            ctx.editor.caretModel.moveToOffset(edit.from + edit.caretOffsetInReplacement)
        })
    }

    private fun blockContext(e: AnActionEvent): BlockActionContext? = blockActionContext(e)

    private fun resolveIndentStep(file: PsiFile): String {
        return try {
            val opts = com.intellij.application.options.CodeStyle.getIndentOptions(file)
            if (opts.USE_TAB_CHARACTER) "\t" else " ".repeat(opts.INDENT_SIZE.coerceAtLeast(1))
        } catch (_: Throwable) {
            "    "
        }
    }
}

/** Minimal state passed from update() to actionPerformed(). */
private data class BlockActionContext(
    val editor: Editor,
    val psi: PsiFile,
    val canJoin: Boolean,
    val canSplit: Boolean,
)

private fun blockActionContext(e: AnActionEvent): BlockActionContext? {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val psi = e.getData(CommonDataKeys.PSI_FILE) ?: return null
    if (!TemplateLangFileFilter.shouldProcess(psi)) return null
    val text = editor.document.charsSequence
    val caretOffset = editor.caretModel.offset
    val pair = SkyTemplateBlockActionsLogic.findEnclosingBlock(text, caretOffset) ?: return null
    val multiLine = SkyTemplateBlockActionsLogic.spansMultipleLines(text, pair)
    return BlockActionContext(
        editor = editor,
        psi = psi,
        canJoin = multiLine,
        canSplit = !multiLine,
    )
}

/**
 * Pure logic for [SkyTemplateJoinBlockAction] / [SkyTemplateSplitBlockAction].
 * Kept UI-free so the join/split transformation rules are unit-testable
 * without an IntelliJ test fixture.
 */
object SkyTemplateBlockActionsLogic {

    /**
     * Found block under the caret. Offsets cover the entire span from
     * the opener's `{` to the closer's `}` (inclusive of `{` and `}`).
     */
    data class BlockSpan(val openerStart: Int, val closerEnd: Int)

    /**
     * Result of a Join / Split computation. `replacement` is what the
     * span `[from, to)` is replaced with; `caretOffsetInReplacement` is
     * the offset within `replacement` where the caret should land
     * (typically just after the new opener for predictability).
     */
    data class Edit(
        val from: Int,
        val to: Int,
        val replacement: String,
        val caretOffsetInReplacement: Int,
    )

    /**
     * Find the innermost SkyTemplate block whose span CONTAINS the
     * caret (or whose span begins at the caret — typing position right
     * after `{?cond}` counts as inside). Walks the existing Sky range
     * list so the same recognition rules used by inspections / folding
     * apply.
     *
     * Returns null when no block encloses the caret, when the caret
     * sits inside a comment, or when the file has no Sky structure.
     */
    fun findEnclosingBlock(text: CharSequence, caretOffset: Int): BlockSpan? {
        if (text.isEmpty()) return null
        val ranges = SkyTemplateRanges.computeTemplateRanges(text)
        if (ranges.isEmpty()) return null

        // Build a stack of opener offsets walking the document start to
        // end. For each closer encountered, pair it with the most recent
        // opener (LIFO with indent-aware unwinding to mirror the rest
        // of the plugin). When the resulting pair encloses our caret,
        // record it; the deepest such pair is the answer.
        val stack = ArrayDeque<Triple<Int, Int, Int>>()                // (openerStart, openerEnd, openerIndent)
        var bestSpan: BlockSpan? = null

        for (range in ranges) {
            when (classifySplit(text, range.startOffset, range.endOffset)) {
                SplitKind.OPEN -> {
                    stack.addLast(
                        Triple(range.startOffset, range.endOffset, lineIndentWidth(text, range.startOffset))
                    )
                }
                SplitKind.CLOSE -> {
                    if (stack.isNotEmpty()) {
                        val closerIndent = lineIndentWidth(text, range.startOffset)
                        while (stack.isNotEmpty() && stack.last().third > closerIndent) {
                            stack.removeLast()
                        }
                        if (stack.isNotEmpty()) {
                            val (openerStart, _, _) = stack.removeLast()
                            // The pair encloses the caret if the caret is
                            // anywhere in [openerStart, closerEnd]. We
                            // accept ON the boundaries too — caret right
                            // after `{?cond}` (= openerEnd) or right before
                            // `{/}` (= closerStart) both count as inside.
                            if (openerStart <= caretOffset && caretOffset <= range.endOffset) {
                                val candidate = BlockSpan(openerStart, range.endOffset)
                                // Innermost wins — accept any candidate that
                                // tightens the span we already have.
                                if (bestSpan == null ||
                                    (candidate.openerStart >= bestSpan!!.openerStart &&
                                        candidate.closerEnd <= bestSpan!!.closerEnd)
                                ) {
                                    bestSpan = candidate
                                }
                            }
                        }
                    }
                }
                SplitKind.BRANCH, SplitKind.OTHER -> {}
            }
        }
        return bestSpan
    }

    /** True when [block] contains at least one `\n` between its braces. */
    fun spansMultipleLines(text: CharSequence, block: BlockSpan): Boolean {
        for (i in block.openerStart until block.closerEnd) {
            if (text[i] == '\n') return true
        }
        return false
    }

    /**
     * Build the Join edit for the block enclosing [caretOffset]: replace
     * everything between the opener's `}` and the closer's `{` with the
     * inline-collapsed body. Branches stay inline too.
     *
     * Algorithm: enumerate every `{…}` template tag in the block (the
     * opener itself, internal branches, and the closer) and rewrite
     * them in source order. For each text segment between tags, keep
     * its non-whitespace tokens and collapse whitespace runs.
     *
     * Returns null when there is no enclosing block under the caret,
     * the block is already a single line, or the body has no content.
     */
    fun computeJoinEdit(text: CharSequence, caretOffset: Int): Edit? {
        val block = findEnclosingBlock(text, caretOffset) ?: return null
        if (!spansMultipleLines(text, block)) return null
        val ranges = SkyTemplateRanges.computeTemplateRanges(text)

        // Tags that fall WITHIN this block (excluding the opener itself
        // — we keep that boundary fixed): branches and the matching
        // closer. We rebuild the block by iterating the ranges that lie
        // strictly between [openerStart, closerEnd] and joining them
        // with the trimmed text in between.
        val inner = ranges.filter { it.startOffset > block.openerStart && it.endOffset <= block.closerEnd }
        val sb = StringBuilder()
        var cursor = block.openerStart
        // Append the opener verbatim.
        sb.append(text, block.openerStart, ranges.first { it.startOffset == block.openerStart }.endOffset)
        cursor = ranges.first { it.startOffset == block.openerStart }.endOffset

        for (r in inner) {
            // Append the text BETWEEN the previous tag end and this
            // tag's start, with newlines / leading-whitespace runs
            // collapsed to nothing. Preserves any non-whitespace
            // content (e.g. `{?cond}\n    body\n{/}` becomes
            // `{?cond}body{/}`; the `body` text is kept).
            val between = text.subSequence(cursor, r.startOffset).toString()
            sb.append(collapseWhitespace(between))
            // Append the tag verbatim.
            sb.append(text, r.startOffset, r.endOffset)
            cursor = r.endOffset
        }

        // Caret lands right after the opener — predictable position.
        val openerEnd = ranges.first { it.startOffset == block.openerStart }.endOffset
        val caretInReplacement = openerEnd - block.openerStart

        return Edit(
            from = block.openerStart,
            to = block.closerEnd,
            replacement = sb.toString(),
            caretOffsetInReplacement = caretInReplacement,
        )
    }

    /**
     * Build the Split edit for the single-line block enclosing
     * [caretOffset]: replace it with a multi-line shape where each tag
     * (opener / branches / closer) sits on its own line, body text is
     * indented one [indentStep] deeper than the opener, and tags align
     * with the opener's existing indent.
     *
     * Returns null when no enclosing block exists, when the block is
     * already multi-line, or when the body is empty (no need to split).
     */
    fun computeSplitEdit(text: CharSequence, caretOffset: Int, indentStep: String): Edit? {
        val block = findEnclosingBlock(text, caretOffset) ?: return null
        if (spansMultipleLines(text, block)) return null
        val ranges = SkyTemplateRanges.computeTemplateRanges(text)

        val openerEnd = ranges.first { it.startOffset == block.openerStart }.endOffset
        val openerIndent = lineIndentString(text, block.openerStart)
        val bodyIndent = openerIndent + indentStep

        // Structural tags AT OUR LEVEL only — branches that belong to
        // us and the matching closer. Nested blocks inside our body
        // (their openers / closers) and inline non-structural tags
        // (`{=x}`, `{var}`, `{c.NAME}`) are not split points; they
        // stay inside the body text untouched.
        val structural = collectStructuralAtOurLevel(text, ranges, block, openerEnd)

        val sb = StringBuilder()
        sb.append(text, block.openerStart, openerEnd)
        var cursor = openerEnd
        var anyBodyContent = false

        for (r in structural) {
            val between = text.subSequence(cursor, r.startOffset).toString().trim()
            if (between.isNotEmpty()) {
                sb.append('\n').append(bodyIndent).append(between)
                anyBodyContent = true
            }
            // Branch / closer line: at opener indent.
            sb.append('\n').append(openerIndent)
            sb.append(text, r.startOffset, r.endOffset)
            cursor = r.endOffset
        }

        // No body anywhere (e.g. `{?cond}{/}` or `{?cond}{:}{/}` with
        // empty branches) — Split has nothing meaningful to do.
        if (!anyBodyContent) return null

        val replacement = sb.toString()
        val caretInReplacement = openerEnd - block.openerStart + 1 + bodyIndent.length

        return Edit(
            from = block.openerStart,
            to = block.closerEnd,
            replacement = replacement,
            caretOffsetInReplacement = caretInReplacement.coerceAtMost(replacement.length),
        )
    }

    /**
     * From [ranges], extract the tags that are STRUCTURAL at the level
     * of [block] — i.e. branches that belong to this block and the
     * matching closer. Walks the ranges with a depth counter starting
     * at 0 (we are inside [block] right after the opener); nested
     * opens push depth, nested closes pop it, and only tags seen at
     * depth 0 belong to us. The matching closer arrives when a CLOSE
     * brings depth from 0 to -1 — that's our terminator and the walk
     * stops.
     */
    private fun collectStructuralAtOurLevel(
        text: CharSequence,
        ranges: List<com.intellij.openapi.util.TextRange>,
        block: BlockSpan,
        openerEnd: Int,
    ): List<com.intellij.openapi.util.TextRange> {
        val out = ArrayList<com.intellij.openapi.util.TextRange>()
        var depth = 0
        for (r in ranges) {
            if (r.startOffset < openerEnd) continue                    // skip opener and earlier
            if (r.startOffset >= block.closerEnd) break
            when (classifySplit(text, r.startOffset, r.endOffset)) {
                SplitKind.OPEN -> depth++
                SplitKind.CLOSE -> {
                    if (depth == 0) {
                        // Matching closer for [block].
                        out += r
                        break
                    }
                    depth--
                }
                SplitKind.BRANCH -> if (depth == 0) out += r
                SplitKind.OTHER -> {}                                  // `{=x}` / variables / etc. — body content
            }
        }
        return out
    }

    // ── tag classification + indent helpers ───────────────────────────────

    private enum class SplitKind { OPEN, CLOSE, BRANCH, OTHER }

    private fun classifySplit(text: CharSequence, openOffset: Int, closeEndOffset: Int): SplitKind {
        val bodyStart = openOffset + 1
        val bodyEnd = closeEndOffset - 1
        if (bodyEnd <= bodyStart) return SplitKind.OTHER
        var i = bodyStart
        while (i < bodyEnd && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= bodyEnd) return SplitKind.OTHER
        val first = text[i]
        val hasLeadingWs = i > bodyStart
        if (first == '/') return SplitKind.CLOSE
        if (first == ':') return SplitKind.BRANCH
        if (first == '?' && i + 1 < bodyEnd && text[i + 1] == ':') return SplitKind.OTHER
        if (first == '?' || first == '@' || first == '%') return SplitKind.OPEN
        if (hasLeadingWs) return SplitKind.OTHER
        if (!(first.isLetter() || first == '_')) return SplitKind.OTHER
        var j = i + 1
        while (j < bodyEnd && (text[j].isLetterOrDigit() || text[j] == '_')) j++
        val word = text.subSequence(i, j).toString().lowercase()
        val followedByBoundary = j >= bodyEnd || !(text[j].isLetterOrDigit() || text[j] == '_')
        if (!followedByBoundary) return SplitKind.OTHER
        return when (word) {
            "loop", "foreach", "for", "while", "if", "each" -> SplitKind.OPEN
            "else", "elseif" -> SplitKind.BRANCH
            "end" -> SplitKind.CLOSE
            else -> SplitKind.OTHER
        }
    }

    private fun lineIndentString(text: CharSequence, offset: Int): String {
        var start = offset
        while (start > 0 && text[start - 1] != '\n') start--
        var i = start
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.subSequence(start, i).toString()
    }

    private fun lineIndentWidth(text: CharSequence, offset: Int): Int {
        var start = offset
        while (start > 0 && text[start - 1] != '\n') start--
        var w = 0
        while (start + w < text.length) {
            val c = text[start + w]
            if (c != ' ' && c != '\t') break
            w++
        }
        return w
    }

    /**
     * Collapse the segment `[between]` for the Join transformation:
     * trims leading / trailing whitespace AND replaces internal newline
     * runs with empty string (to keep the line-collapse tight). Spaces
     * within text content are preserved — we only kill structural
     * whitespace, not author-meaningful spaces.
     */
    private fun collapseWhitespace(between: String): String {
        // Strip leading + trailing whitespace.
        val trimmed = between.trim()
        if (trimmed.isEmpty()) return ""
        // Replace internal newline (with optional surrounding tabs /
        // spaces) runs with a single space — preserves multi-line
        // bodies that have meaningful word boundaries between lines.
        return trimmed.replace(Regex("\\s*\n\\s*"), "")
    }
}
