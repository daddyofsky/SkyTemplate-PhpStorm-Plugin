package com.novaframework.templatelang.sky

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Post-format step that restores SkyTemplate block indentation after
 * the host language's formatter (HTML / XML / SkyTemplate's own) has
 * run.
 *
 * **Why this exists.** SkyTemplate has no `lang.formatter` of its own;
 * the platform's HTML / XML formatters drive Reformat Code on `*.html`
 * hosts (and on the HTML view of multi-tree `*.sky` files). Those
 * formatters do not understand `{loop …} … {/}` block structure, so
 * they collapse the indentation that the [SkyTemplateEnterHandler]
 * carefully laid down when the user was typing — leaving body lines
 * at the host indent level and breaking the visual block structure
 * the user authored.
 *
 * **What it does.** After the host formatter completes, we walk every
 * line and maintain a UNIFIED stack of structural opens — both
 * SkyTemplate (`{loop …}`, `{?…}`, `{@…}`, …) and HTML (`<div>`,
 * `<table>`, `<td>`, …). Each level — Sky or HTML — contributes one
 * indent step. A SkyTemplate opener that sits as the immediate child of
 * an HTML element therefore gets `<parent>.indent + step`, and the
 * opener's body indents to `<parent>.indent + step + step`, so deeply
 * interleaved layouts like `<table> > <tr> > {@products} > <td> >
 * {?.name} > {.name}` indent correctly through every level (the
 * earlier Sky-only stack lost the HTML levels and stranded inner Sky
 * tags at outer-Sky depth).
 *
 * The re-indent is one-sided — indent is only ever **increased** — so:
 *   - lines the host formatter put at column 0 because it didn't know
 *     they sat inside a `{loop}` / `<table>` get pushed back to the
 *     correct combined depth;
 *   - lines the host formatter already indented MORE than the
 *     SkyTemplate-derived minimum (e.g. an HTML attribute-aligned
 *     continuation) keep their richer indent intact.
 *
 * **Tag lines are re-indented too.** A line whose entire content is a
 * SkyTemplate opener / closer / branch or an HTML opener / closer /
 * void tag is repositioned to the depth derived from the unified stack;
 * branches (`{:}`, `{else}`, `{elseif}`) sit at their enclosing
 * opener's level; void HTML (`<br>`, `<img>`, `<x/>`, …) does not
 * change the stack. Pure-whitespace lines are skipped, and lines mixing
 * structural tags with body text (e.g. `<p>x</p>`) are treated as body.
 *
 * Scope: runs only when the file passes [TemplateLangFileFilter] —
 * project setting "Enable SkyTemplate support" and the configured
 * file-extension whitelist. Files outside that gate (vanilla HTML in
 * an unrelated module, partial fragments excluded by the user, …)
 * are formatted by the host alone, exactly as before.
 */
class SkyTemplatePostFormatProcessor : PostFormatProcessor {

    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement = source

    override fun processText(
        source: PsiFile,
        rangeToReformat: TextRange,
        settings: CodeStyleSettings,
    ): TextRange {
        if (!TemplateLangFileFilter.shouldProcess(source)) return rangeToReformat

        val document = source.viewProvider.document ?: return rangeToReformat
        // Undo what the host (JS / CSS) formatter did to protected regions and
        // tags BEFORE re-indenting, so the existing pass then runs on restored
        // text (a `<script>` body comes back verbatim; a stray HTML-context tag
        // is rejoined).
        restoreMangledTags(source, document)
        val indentStep = resolveIndentStep(source)
        val originalLength = document.textLength
        val newRange = SkyTemplatePostFormatLogic.reindent(
            text = document.charsSequence,
            range = rangeToReformat,
            indentStep = indentStep,
        ) { from, to, replacement ->
            document.replaceString(from, to, replacement)
        }
        // The range may have grown if we inserted characters. Clamp to the
        // current document length defensively.
        val end = newRange.endOffset.coerceAtMost(document.textLength)
        val start = newRange.startOffset.coerceAtMost(end)
        return if (originalLength == document.textLength) rangeToReformat
        else TextRange(start, end)
    }

    /**
     * Replace each snapshot the host formatter mangled with the original text
     * captured by [SkyTemplatePreFormatProcessor] — both whole protected
     * `<script>` / `<style>` bodies and individual HTML-context tags. Every
     * marker expanded with the whitespace the formatter inserted, so it still
     * spans its (now mangled) region; restoring removes that whitespace,
     * rejoining a split tag and collapsing a mangled body back to what the
     * user wrote. No-ops when there is no snapshot (file outside the formatted
     * range, or the pre pass did not run).
     */
    private fun restoreMangledTags(source: PsiFile, document: Document) {
        val snapshot = document.getUserData(SkyTemplatePreFormatProcessor.SNAPSHOT_KEY) ?: return
        document.putUserData(SkyTemplatePreFormatProcessor.SNAPSHOT_KEY, null)

        var changed = false
        // Descending start offset so earlier edits don't shift later markers;
        // markers track the document anyway, but ordering keeps the edits
        // independent and matches the rest of the document-edit code here.
        for ((marker, original) in snapshot.sortedByDescending { it.first.startOffset }) {
            if (marker.isValid && document.getText(marker.textRange) != original) {
                document.replaceString(marker.startOffset, marker.endOffset, original)
                changed = true
            }
            marker.dispose()
        }
        if (changed) PsiDocumentManager.getInstance(source.project).commitDocument(document)
    }

    private fun resolveIndentStep(file: PsiFile): String {
        return try {
            val opts = CodeStyle.getIndentOptions(file)
            if (opts.USE_TAB_CHARACTER) "\t" else " ".repeat(opts.INDENT_SIZE.coerceAtLeast(1))
        } catch (_: Throwable) {
            "    "
        }
    }
}

/**
 * Pure logic for the post-format processor. Kept UI-free so it can be
 * unit-tested without the IntelliJ test fixture.
 *
 * Algorithm: walk the file line by line maintaining a UNIFIED stack of
 * structural opens — both SkyTemplate (`{loop …}`, `{?…}`, `{@…}`, …)
 * and HTML (`<div>`, `<ul>`, …). Whenever a line's first non-whitespace
 * is a structural tag, push or pop accordingly; for every other line the
 * desired indent is derived from the current stack top.
 *
 *   - OPEN line (Sky or HTML): desired indent =
 *     `max(rawLineIndent, parent.effective + step)`. The `max` is
 *     one-sided: a user-typed deeper indent wins, but a host-stripped
 *     col-0 line gets lifted back to its proper depth. The computed
 *     value becomes the new top's `effective` and is what children use
 *     for their `+ step`.
 *   - CLOSE line (Sky or HTML): desired indent = `top.effective` (the
 *     closer aligns with its opener); then pop.
 *   - BRANCH line (`{:}`, `{else}`, `{elseif}`): desired indent =
 *     `top.effective` (sits at the enclosing opener's level), no stack
 *     change.
 *   - VOID HTML (`<br>`, `<img>`, `<x/>`, …): treated as body — no
 *     stack change.
 *   - Body / inline / mixed lines (anything else): desired indent =
 *     `top.effective + step` if a parent exists; otherwise no
 *     constraint (top-level body anchored by the host formatter).
 *
 * The unified stack is what makes deeply interleaved structures like
 * `<table> > <tr> > {@products} > <td> > {?.name} > {.name}` indent
 * correctly — every level (HTML or Sky) contributes one step. The old
 * Sky-only stack lost the HTML levels, leaving inner Sky tags stranded
 * at outer-Sky depth.
 *
 * Edits are one-sided (only ever increase indent) so the host
 * formatter's deeper placements (e.g. an HTML attribute-aligned
 * continuation line) survive untouched.
 */
object SkyTemplatePostFormatLogic {

    /**
     * Per-line classification used by the unified-stack walker. Lines
     * with no recognisable structural tag at their start fall into
     * [BODY] (or [OTHER] for inline-tag lines like `{=foo()}` that
     * don't change the stack but still want body-level indent).
     */
    private enum class LineKind {
        SKY_OPEN, SKY_CLOSE, SKY_BRANCH,
        HTML_OPEN, HTML_CLOSE, HTML_VOID,
        BODY,
    }

    /**
     * Stack frame for an open structural element (HTML or Sky).
     *
     * @property effective indent column where THIS element's opener and
     *   matching closer should sit.
     * @property childStep step to add to [effective] for any line nested
     *   inside this frame. Always equals the project indent step for
     *   HTML opens; for SkyTemplate opens, depends on the
     *   `indentBlockBody` setting — `indentStep` when on (children sit
     *   one level deeper, the conventional layout), empty string when
     *   off (children sit at the SAME depth as the opener, useful for
     *   long blocks that wrap a section without adding a visual level).
     *   Recording the step per-frame lets the same stack handle mixed
     *   HTML+Sky nesting where only Sky frames are "transparent".
     */
    private data class StackEntry(
        val effective: String,
        val openerLine: Int,
        val kind: LineKind,
        val childStep: String,
    )

    /** First-non-whitespace classification for a single line. */
    private data class LineInfo(val firstNonWs: Int, val kind: LineKind)

    /**
     * Keeps block frames opened by template tags INSIDE a `{*…*}` comment
     * from leaking past the comment. [computeIndentRanges] exposes those
     * inner tags to the line walker so the comment body indents under them;
     * this scope snapshots the stack depth when a comment opens and restores
     * it once the walk moves past the comment's end. Unbalanced inner tags
     * (e.g. a `{loop}` with no `{/}`) therefore never indent real code below.
     *
     * [onLine] must be called once per line, BEFORE that line is classified.
     * Comment ranges are non-overlapping and sorted by start offset.
     */
    private class CommentScope(private val comments: List<com.intellij.openapi.util.TextRange>) {
        private var idx = 0
        private var activeEnd = -1
        private var savedDepth = 0

        fun onLine(lineStart: Int, nextLineStart: Int, stack: ArrayDeque<*>) {
            // Exit: this line reaches or passes the active comment's end
            // (`*}` line included) → restore depth. Inner lines already
            // consumed the frames; the closing `*}` and everything after sit
            // back at the pre-comment level.
            if (activeEnd in 0..nextLineStart) {
                while (stack.size > savedDepth) stack.removeLast()
                activeEnd = -1
            }
            // Drop comments fully behind the cursor.
            while (idx < comments.size && comments[idx].endOffset <= lineStart) idx++
            // Enter: a comment whose `{*` opener lands on this line. The
            // opener line classifies as BODY, so the depth captured here is
            // the true pre-comment depth.
            if (activeEnd < 0 && idx < comments.size) {
                val c = comments[idx]
                if (c.startOffset in lineStart until nextLineStart) {
                    activeEnd = c.endOffset
                    savedDepth = stack.size
                }
            }
        }
    }

    /**
     * Re-indent every line in [range] according to the unified
     * HTML + SkyTemplate depth stack. [applyEdit] is invoked once per
     * line that needs adjustment with `(startOffset, endOffset,
     * replacement)`; edits are dispatched in DESCENDING start order so
     * the caller can apply them straight to a mutable document without
     * tracking shifted offsets. Returns the (possibly grown) range.
     */
    fun reindent(
        text: CharSequence,
        range: TextRange,
        indentStep: String,
        applyEdit: (start: Int, end: Int, replacement: String) -> Unit,
    ): TextRange {
        if (text.isEmpty() || indentStep.isEmpty()) return range

        val lineCount = countLines(text)
        val lineStarts = IntArray(lineCount + 2)
        run {
            var line = 1
            lineStarts[1] = 0
            for (i in text.indices) {
                if (text[i] == '\n') {
                    line++
                    if (line < lineStarts.size) lineStarts[line] = i + 1
                }
            }
            lineStarts[lineCount + 1] = text.length
        }

        // Sky tags index keyed by opening offset — the unified classifier
        // consults this to decide whether the `{` at the line start is
        // structural (paid into the stack) or just a `{=foo()}` body.
        // [computeIndentRanges] also surfaces block tags inside `{*…*}`
        // comments so they drive comment-body indent; the comment-scope
        // containment below stops those inner frames from leaking out.
        val skyByStart = HashMap<Int, com.intellij.openapi.util.TextRange>()
        for (r in SkyTemplateRanges.computeIndentRanges(text)) {
            skyByStart.putIfAbsent(r.startOffset, r)
        }
        val commentScope = CommentScope(SkyTemplateRanges.computeCommentRanges(text))

        data class Edit(val from: Int, val to: Int, val text: String)
        val edits = ArrayList<Edit>()

        val firstLine = lineAt(text, range.startOffset.coerceIn(0, text.length))
        val lastLine = lineAt(text, range.endOffset.coerceIn(0, text.length))
        val stack = ArrayDeque<StackEntry>()

        // We walk EVERY line from line 1 (not just from firstLine) — the
        // stack has to accumulate from the top of the file or the depth
        // would be wrong for partial-range Reformat invocations. Edits
        // are still gated to the [firstLine, lastLine] window.
        for (line in 1..lineCount) {
            val lineStart = lineStarts[line]
            val rawLineEnd = lineStarts.getOrElse(line + 1) { text.length }
            // Contain comment-internal block frames: restore depth on exit,
            // snapshot depth on entry (runs BEFORE this line is classified).
            commentScope.onLine(lineStart, rawLineEnd, stack)
            val lineEnd = if (rawLineEnd > lineStart && text.getOrNull(rawLineEnd - 1) == '\n') {
                rawLineEnd - 1
            } else rawLineEnd

            val firstNonWs = firstNonWhitespace(text, lineStart, lineEnd)
            if (firstNonWs == lineEnd) continue                        // pure whitespace

            val info = classifyLine(text, lineStart, lineEnd, firstNonWs, skyByStart)

            val parent = stack.lastOrNull()
            val parentEffective = parent?.effective
            val parentChildStep = parent?.childStep ?: ""
            val rawIndent = text.subSequence(lineStart, firstNonWs).toString()

            // Compute `desired` for the current line. For OPEN / BODY /
            // VOID the contribution is `parent.effective + parent.childStep`
            // — `childStep` honours the `indentBlockBody` policy on the
            // parent frame (HTML always contributes a step; Sky frames
            // contribute a step only when the toggle is on). For CLOSE /
            // BRANCH the answer is the parent frame itself, regardless of
            // step policy.
            val desired: String? = when (info.kind) {
                LineKind.SKY_OPEN, LineKind.HTML_OPEN -> {
                    val baseline = if (parentEffective != null) parentEffective + parentChildStep else ""
                    if (visualWidth(baseline) > visualWidth(rawIndent)) baseline else rawIndent
                }
                LineKind.SKY_CLOSE, LineKind.HTML_CLOSE -> parentEffective
                LineKind.SKY_BRANCH -> parentEffective
                LineKind.HTML_VOID, LineKind.BODY -> {
                    if (parentEffective != null) parentEffective + parentChildStep else null
                }
            }

            // Application policy:
            //   - Closer / branch lines (Sky `{/}` `{end}` `{:}` `{else}`
            //     `{elseif}`, HTML `</tag>`) are TWO-SIDED: their indent
            //     is fully determined by the matching opener, so we set
            //     it exactly. This is what fixes "Enter before `{/}`
            //     leaves the closer indented" — the closer line gets
            //     pulled back to its opener's level even when the
            //     platform's auto-indent pushed it deeper.
            //   - Open / body / void lines stay ONE-SIDED (lift only):
            //     a deeper user-typed indent expresses intent (visual
            //     emphasis, host formatter's nested HTML rules, …) and
            //     overriding it would be more hostile than helpful.
            if (line in firstLine..lastLine && desired != null) {
                val twoSided = info.kind == LineKind.SKY_CLOSE ||
                    info.kind == LineKind.HTML_CLOSE ||
                    info.kind == LineKind.SKY_BRANCH
                val mustEdit = if (twoSided) {
                    rawIndent != desired
                } else {
                    visualWidth(rawIndent) < visualWidth(desired)
                }
                if (mustEdit) edits += Edit(lineStart, firstNonWs, desired)
            }

            when (info.kind) {
                LineKind.SKY_OPEN, LineKind.HTML_OPEN -> {
                    // Effective for the new frame is what we'd APPLY to
                    // this line; if we don't lift, that's the rawIndent
                    // (the user's deeper choice). Children walk off this.
                    // Both Sky and HTML opens contribute one indent step
                    // — the conventional depth + 1 layout, fixed policy.
                    val effective = desired ?: rawIndent
                    stack.addLast(StackEntry(effective, line, info.kind, indentStep))
                }
                LineKind.SKY_CLOSE, LineKind.HTML_CLOSE -> {
                    // Indent-aware close pairing — pop only when it makes
                    // structural sense. Mismatched HTML / Sky closer at
                    // the top of the stack is a parse error from the
                    // formatter's perspective; pop only the matching kind
                    // so the stack stays consistent.
                    val top = stack.lastOrNull()
                    if (top != null && (
                            (info.kind == LineKind.SKY_CLOSE && top.kind == LineKind.SKY_OPEN) ||
                                (info.kind == LineKind.HTML_CLOSE && top.kind == LineKind.HTML_OPEN)
                            )
                    ) {
                        stack.removeLast()
                    } else if (top != null) {
                        // Tolerant fallback: pop the top regardless rather
                        // than leaving a permanent imbalance for the rest
                        // of the file. Mismatch is a user error our indent
                        // pass shouldn't make worse.
                        stack.removeLast()
                    }
                }
                else -> {}
            }
        }

        if (edits.isEmpty()) return range

        var rangeEnd = range.endOffset
        var rangeDelta = 0
        for (e in edits.sortedByDescending { it.from }) {
            val newLen = e.text.length
            val oldLen = e.to - e.from
            applyEdit(e.from, e.to, e.text)
            if (e.from <= rangeEnd) rangeDelta += (newLen - oldLen)
        }
        return TextRange(range.startOffset, rangeEnd + rangeDelta)
    }

    /**
     * Compute the SkyTemplate-aware desired indent for the line whose
     * first character lies at [lineStart]. Replays the same unified
     * HTML+Sky stack walk that [reindent] uses, but for a single line —
     * intended for the post-Enter handler that needs to know what
     * indent a freshly inserted (possibly blank) line should carry.
     *
     * Returns null when the line is at top level outside any block —
     * the caller should keep whatever indent the host formatter chose.
     *
     * The line itself is treated as either a tag line (Sky / HTML
     * opener / closer / branch / void) or a body line. For tag lines,
     * the rule mirrors [reindent]'s desired-indent computation. For
     * blank lines (no first non-whitespace), the result is body-level
     * `top.effective + step`, so the caret on a freshly-inserted Enter
     * line gets the right indent.
     */
    fun computeIndentForLine(
        text: CharSequence,
        lineStart: Int,
        indentStep: String,
    ): String? {
        if (text.isEmpty() || indentStep.isEmpty()) return null
        if (lineStart < 0 || lineStart > text.length) return null

        val skyByStart = HashMap<Int, com.intellij.openapi.util.TextRange>()
        for (r in SkyTemplateRanges.computeIndentRanges(text)) {
            skyByStart.putIfAbsent(r.startOffset, r)
        }
        val commentScope = CommentScope(SkyTemplateRanges.computeCommentRanges(text))

        val stack = ArrayDeque<StackEntry>()

        // Walk every preceding line, updating the stack — same rules as
        // [reindent]'s walker.
        var pos = 0
        var lineNo = 1
        while (pos < lineStart) {
            val curLineStart = pos
            var curLineEndIdx = pos
            while (curLineEndIdx < text.length && text[curLineEndIdx] != '\n') curLineEndIdx++
            val curLineEnd = curLineEndIdx
            val nextLineStart = if (curLineEnd < text.length) curLineEnd + 1 else text.length
            commentScope.onLine(curLineStart, nextLineStart, stack)
            val firstNonWs = firstNonWhitespace(text, curLineStart, curLineEnd)
            if (firstNonWs < curLineEnd) {
                val info = classifyLine(text, curLineStart, curLineEnd, firstNonWs, skyByStart)
                val parent = stack.lastOrNull()
                val parentEffective = parent?.effective
                val parentChildStep = parent?.childStep ?: ""
                val rawIndent = text.subSequence(curLineStart, firstNonWs).toString()
                when (info.kind) {
                    LineKind.SKY_OPEN, LineKind.HTML_OPEN -> {
                        val baseline = if (parentEffective != null) parentEffective + parentChildStep else ""
                        val effective = if (visualWidth(baseline) > visualWidth(rawIndent)) baseline else rawIndent
                        stack.addLast(StackEntry(effective, lineNo, info.kind, indentStep))
                    }
                    LineKind.SKY_CLOSE, LineKind.HTML_CLOSE -> {
                        if (stack.isNotEmpty()) stack.removeLast()
                    }
                    else -> {}
                }
            }
            pos = if (curLineEnd < text.length) curLineEnd + 1 else text.length
            lineNo++
        }

        // Compute indent for the target line.
        var targetEnd = lineStart
        while (targetEnd < text.length && text[targetEnd] != '\n') targetEnd++
        val targetFirstNonWs = firstNonWhitespace(text, lineStart, targetEnd)
        // Containment for the target line: if it sits past the comment we
        // were inside, restore depth so a line right below `*}` is not
        // indented by an unbalanced inner block tag.
        val targetNextLineStart = if (targetEnd < text.length) targetEnd + 1 else text.length
        commentScope.onLine(lineStart, targetNextLineStart, stack)
        val parent = stack.lastOrNull()
        val parentEffective = parent?.effective
        val parentChildStep = parent?.childStep ?: ""

        if (targetFirstNonWs == targetEnd) {
            return if (parentEffective != null) parentEffective + parentChildStep else null
        }

        val info = classifyLine(text, lineStart, targetEnd, targetFirstNonWs, skyByStart)
        val rawIndent = text.subSequence(lineStart, targetFirstNonWs).toString()
        return when (info.kind) {
            LineKind.SKY_OPEN, LineKind.HTML_OPEN -> {
                val baseline = if (parentEffective != null) parentEffective + parentChildStep else ""
                if (visualWidth(baseline) > visualWidth(rawIndent)) baseline else rawIndent
            }
            LineKind.SKY_CLOSE, LineKind.HTML_CLOSE -> parentEffective
            LineKind.SKY_BRANCH -> parentEffective
            LineKind.HTML_VOID, LineKind.BODY -> {
                if (parentEffective != null) parentEffective + parentChildStep else null
            }
        }
    }

    /**
     * Classify the line `[lineStart, lineEnd)` whose first non-whitespace
     * is at [firstNonWs]. Considers a line a structural tag line ONLY
     * when the tag spans the entire content of the line (any trailing
     * non-whitespace makes it BODY — mixed lines like `<p>x</p>` are
     * body, not opens).
     */
    private fun classifyLine(
        text: CharSequence,
        lineStart: Int,
        lineEnd: Int,
        firstNonWs: Int,
        skyByStart: Map<Int, com.intellij.openapi.util.TextRange>,
    ): LineInfo {
        val first = text[firstNonWs]

        // SkyTemplate tag (bare `{…}` or wrapped `<!--{…}-->`). The Sky
        // ranges index already accounts for both forms.
        skyByStart[firstNonWs]?.let { range ->
            if (range.endOffset > lineEnd) return LineInfo(firstNonWs, LineKind.BODY)
            if (!isWhitespaceTo(text, range.endOffset, lineEnd)) {
                return LineInfo(firstNonWs, LineKind.BODY)
            }
            return LineInfo(
                firstNonWs,
                when (classifyKind(text, range.startOffset, range.endOffset)) {
                    Kind.OPEN -> LineKind.SKY_OPEN
                    Kind.CLOSE -> LineKind.SKY_CLOSE
                    Kind.BRANCH -> LineKind.SKY_BRANCH
                    Kind.OTHER -> LineKind.BODY
                }
            )
        }

        // HTML tag at line start. We require a single tag occupying the
        // line's content (mixed-tag lines like `<p>x</p>` or `<div><span>`
        // are body / unrecognised).
        if (first == '<' && firstNonWs + 1 < lineEnd) {
            val next = text[firstNonWs + 1]
            // Skip declarations / processing instructions / comments.
            if (next == '!' || next == '?') return LineInfo(firstNonWs, LineKind.BODY)
            val tagEnd = scanHtmlTagClose(text, firstNonWs, lineEnd)
                ?: return LineInfo(firstNonWs, LineKind.BODY)
            if (!isWhitespaceTo(text, tagEnd + 1, lineEnd)) {
                return LineInfo(firstNonWs, LineKind.BODY)
            }
            val isClose = next == '/'
            val isSelfClose = tagEnd > firstNonWs && text[tagEnd - 1] == '/'
            // Tag name follows `<` (open) or `</` (close).
            val nameStart = firstNonWs + (if (isClose) 2 else 1)
            var nameEnd = nameStart
            while (nameEnd < tagEnd
                && (text[nameEnd].isLetterOrDigit() || text[nameEnd] == '-' || text[nameEnd] == '_')
            ) nameEnd++
            if (nameEnd == nameStart) return LineInfo(firstNonWs, LineKind.BODY)
            val name = text.subSequence(nameStart, nameEnd).toString().lowercase()
            return LineInfo(
                firstNonWs,
                when {
                    isClose -> LineKind.HTML_CLOSE
                    isSelfClose || name in HTML_VOID_ELEMENTS -> LineKind.HTML_VOID
                    else -> LineKind.HTML_OPEN
                }
            )
        }

        return LineInfo(firstNonWs, LineKind.BODY)
    }

    /**
     * Find the offset of the `>` that closes the HTML tag opening at
     * [openOffset]. Respects single- and double-quoted attribute values
     * so `<a title=">>>">` matches the right `>`. Returns null if the
     * tag does not close before [lineEnd] (multi-line attribute lists
     * — uncommon, treated as body for indent purposes).
     */
    private fun scanHtmlTagClose(text: CharSequence, openOffset: Int, lineEnd: Int): Int? {
        var i = openOffset + 1
        var quote: Char = ' '
        while (i < lineEnd) {
            val c = text[i]
            if (quote != ' ') {
                if (c == quote) quote = ' '
            } else when (c) {
                '"', '\'' -> quote = c
                '>' -> return i
            }
            i++
        }
        return null
    }

    /** True if `text[start, end)` is empty or contains only space / tab characters. */
    private fun isWhitespaceTo(text: CharSequence, start: Int, end: Int): Boolean {
        for (i in start until end) {
            val c = text[i]
            if (c != ' ' && c != '\t') return false
        }
        return true
    }

    /**
     * HTML5 void elements — the formatter never expects a closer for
     * these, so the opening tag form `<br>` / `<input>` doesn't push a
     * stack frame. (`<br/>` self-closing form is handled separately.)
     */
    private val HTML_VOID_ELEMENTS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    private enum class Kind { OPEN, CLOSE, BRANCH, OTHER }

    private fun classifyKind(text: CharSequence, openOffset: Int, closeEndOffset: Int): Kind {
        val bodyStart = openOffset + 1
        val bodyEnd = closeEndOffset - 1
        if (bodyEnd <= bodyStart) return Kind.OTHER

        var i = bodyStart
        while (i < bodyEnd && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= bodyEnd) return Kind.OTHER
        val first = text[i]
        val hasLeadingWs = i > bodyStart

        if (first == '/') return Kind.CLOSE
        if (first == ':') return Kind.BRANCH
        if (first == '?' && i + 1 < bodyEnd && text[i + 1] == ':') return Kind.OTHER
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

    private fun firstNonWhitespace(text: CharSequence, start: Int, end: Int): Int {
        var i = start
        while (i < end && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    /** Visual width: each tab counts as one character, same as the leading-ws comparison in the Enter handler. */
    private fun visualWidth(s: String): Int = s.length

    private fun lineAt(text: CharSequence, offset: Int): Int {
        var n = 1
        val capped = offset.coerceAtMost(text.length)
        for (i in 0 until capped) if (text[i] == '\n') n++
        return n
    }

    private fun countLines(text: CharSequence): Int {
        if (text.isEmpty()) return 1
        var n = 1
        for (i in text.indices) if (text[i] == '\n') n++
        return n
    }
}
