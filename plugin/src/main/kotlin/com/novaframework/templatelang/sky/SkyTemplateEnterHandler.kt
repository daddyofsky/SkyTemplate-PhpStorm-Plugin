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
import com.novaframework.templatelang.settings.TemplateLangFileFilter

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
        if (!TemplateLangFileFilter.shouldProcess(file)) return EnterHandlerDelegate.Result.Continue

        val document = editor.document
        val text = document.charsSequence
        val offset = caretOffset.get() ?: return EnterHandlerDelegate.Result.Continue
        if (offset <= 0 || offset > text.length) return EnterHandlerDelegate.Result.Continue

        // Resolve indent step from the project's code style. Falls back to
        // 4 spaces if the SkyTemplate language has no explicit indent options
        // (which it doesn't until we add a Code Style page in M9).
        val indentStep = resolveIndentStep(file)

        // Smart-split path — runs BEFORE the after-opener path so that
        // `{?cond}<caret>{/}` (and any "caret immediately before a
        // closer / branch" position) opens up to a three-line PHP / JS
        // `{<enter>}` shape: indented blank line for the caret, with
        // the closer / branch on its own line at the matching opener's
        // depth.
        val split = SkyTemplateEnterHandlerLogic.checkSmartSplit(text, offset, indentStep)
        if (split != null) {
            applySmartSplit(file, editor, document, offset, split)
            return EnterHandlerDelegate.Result.Stop
        }

        val analysis = SkyTemplateEnterHandlerLogic.analyzeBefore(text, offset)
        if (analysis == null) {
            // Plain Enter inside an embedded `<script>` / `<style>` that carries
            // SkyTemplate tags: own it. The host JS / CSS Enter can't see
            // `{?var}` / `{/}` block structure and re-indents the new line to
            // its own (Sky-blind) depth — and for JS it does so even after our
            // post-Enter correction. Inserting the newline ourselves (and
            // returning Stop) keeps the host Enter from running at all, so the
            // combined HTML + Sky + brace indent we compute survives.
            if (ownEmbeddedPlainEnter(file, editor, document, offset, indentStep)) {
                return EnterHandlerDelegate.Result.Stop
            }
            return EnterHandlerDelegate.Result.Continue
        }

        // Relative lift: when the opener is the first non-whitespace on its
        // line, compute the indent it should carry RELATIVE to the nearest
        // enclosing opener above — HTML (`<div>`) or template (`{loop}`)
        // alike, at that parent's ACTUAL indent + one step — and lift the
        // opener line when it sits shallower. Inline forms
        // (`<div>{?cond}`) are not lifted: the opener is not a structural
        // first-child there and rewriting the line would over-indent it.
        val openerLineStart = analysis.openerOffset - analysis.indent.length
        val openerIsFirstOnLine = openerLineStart == 0 || text[openerLineStart - 1] == '\n'
        val relativeExpected = if (openerIsFirstOnLine) {
            SkyTemplatePostFormatLogic.computeIndentForLine(text, openerLineStart, indentStep)
        } else null
        val effectiveIndent = if (relativeExpected != null && relativeExpected.length > analysis.indent.length) {
            relativeExpected
        } else {
            analysis.indent
        }
        val liftDelta = effectiveIndent.length - analysis.indent.length

        // If we are lifting, rewrite the opener's leading-whitespace prefix
        // BEFORE inserting the rest. Doing the lift first keeps the
        // post-insert offset arithmetic straightforward (caret math below
        // is computed on the post-lift `offset`).
        val adjustedOffset = if (liftDelta > 0) {
            document.replaceString(openerLineStart, openerLineStart + analysis.indent.length, effectiveIndent)
            SkyTemplateRangeCache.invalidate()
            offset + liftDelta
        } else {
            offset
        }

        // Trailing content on the opener's line (`{loop x}<caret>text`) is
        // the block's body, not decoration after the auto-inserted `{/}` —
        // cut it here and re-append it to the body line below instead of
        // leaving it stuck after the closer.
        var trailingEnd = adjustedOffset
        while (trailingEnd < text.length && text[trailingEnd] != '\n') trailingEnd++
        val trailing = text.subSequence(adjustedOffset, trailingEnd).toString()
        val trailingContent = if (analysis.needsAutoClose) trailing.trimEnd() else ""
        if (trailingContent.isNotEmpty()) {
            document.deleteString(adjustedOffset, adjustedOffset + trailingContent.length)
            SkyTemplateRangeCache.invalidate()
        }

        val insertion = buildString {
            append('\n')
            append(effectiveIndent)
            append(indentStep)
            append(trailingContent)
            if (analysis.needsAutoClose) {
                // Caret will be placed at the end of this prefix; the closer
                // line follows on the next document line.
                append('\n')
                append(effectiveIndent)
                append("{/}")
            }
        }

        // Compute caret position BEFORE the insertion so we can place it at
        // the end of the indented blank line (just after the indent step,
        // before the closer if there is one) — i.e. right before any
        // trailing content that got moved down.
        val caretAfter = adjustedOffset + 1 /* '\n' */ + effectiveIndent.length + indentStep.length

        document.insertString(adjustedOffset, insertion)
        SkyTemplateRangeCache.invalidate()
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        editor.caretModel.moveToOffset(caretAfter)

        // Stop — we own the entire Enter behaviour for this case. Returning
        // Continue would let the platform also insert its own newline and
        // re-indent, which would conflict with what we just wrote.
        return EnterHandlerDelegate.Result.Stop
    }

    /**
     * Post-Enter Sky-aware re-indent. Runs AFTER the platform's own
     * Enter handling and fixes two cases the host (HTML / XML) Enter
     * doesn't get right:
     *
     *   1. **Enter inside a Sky block uses HTML-only indent.** The
     *      host handler doesn't see `{?cond}` / `{@items}` as a block
     *      opener, so the new line lands at the host's HTML-derived
     *      depth instead of the SkyTemplate body depth. We replay the
     *      unified HTML+Sky stack walk for the caret line and lift it
     *      to the proper depth (one-sided — a deeper user / host
     *      indent is preserved).
     *   2. **Enter before a `{/}` / `{:}` line indents the closer.**
     *      The closer / branch line gets the host's auto-indent
     *      regardless of the matching opener. Re-indenting the line
     *      below with the same Sky-aware walk pulls the closer back
     *      to its opener's level (two-sided — closer / branch indent
     *      is fully determined by the matching opener).
     *
     * Both fixes leverage [SkyTemplatePostFormatLogic.computeIndentForLine]
     * and [SkyTemplatePostFormatLogic.reindent] so the rules stay
     * consistent with Reformat Code — the user's own suggested
     * direction ("엔터 클릭시 해당 라인을 reformat code 로직을 태우는").
     */
    override fun postProcessEnter(
        file: PsiFile,
        editor: Editor,
        dataContext: DataContext,
    ): EnterHandlerDelegate.Result {
        val lang = file.language
        if (lang !== SkyTemplateLanguage &&
            lang !== com.intellij.lang.html.HTMLLanguage.INSTANCE &&
            lang !== com.intellij.lang.xml.XMLLanguage.INSTANCE
        ) return EnterHandlerDelegate.Result.Continue
        if (!TemplateLangFileFilter.shouldProcess(file)) return EnterHandlerDelegate.Result.Continue

        val document = editor.document
        val caretOffset = editor.caretModel.offset
        if (caretOffset < 0 || caretOffset > document.textLength) {
            return EnterHandlerDelegate.Result.Continue
        }

        // Inside a SkyTemplate-bearing `<script>` / `<style>` the host JS / CSS
        // Enter delegate runs AFTER us and re-indents the caret line to its
        // own (Sky-blind) depth, clobbering our correction. We detect that
        // context here so we can both (a) feed the embedded brace depth into
        // the indent and (b) claim the final word with `Stop` below.
        val inEmbedded = SkyTemplateRangeCache.getProtectedEmbeddedRanges(document.charsSequence)
            .any { it.startOffset <= caretOffset && caretOffset <= it.endOffset }

        val indentStep = resolveIndentStep(file)

        // (1) Caret line — fix indent on the freshly-inserted side of
        // the Enter break. We compute the expected indent and apply if
        // the platform's auto-indent landed shallower (one-sided), or
        // if the line's first content is a closer / branch (two-sided).
        applyCaretLineIndent(editor, document, caretOffset, indentStep)

        // (2) Line below — covers the "Enter before `{/}` / `{:}`"
        // case where the platform may have left the closer / branch at
        // the same indent as the inserted blank line. The reindent pass
        // is range-scoped so only the affected line is touched.
        val newCaretOffset = editor.caretModel.offset
        val belowStart = lineEndOf(document.charsSequence, newCaretOffset)
            .let { if (it < document.textLength) it + 1 else -1 }
        if (belowStart >= 0) {
            val updatedText = document.charsSequence
            var belowEnd = belowStart
            while (belowEnd < updatedText.length && updatedText[belowEnd] != '\n') belowEnd++
            SkyTemplatePostFormatLogic.reindent(
                text = updatedText,
                range = com.intellij.openapi.util.TextRange(belowStart, belowEnd),
                indentStep = indentStep,
            ) { from, to, replacement ->
                document.replaceString(from, to, replacement)
                SkyTemplateRangeCache.invalidate()
            }
        }

        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        // In embedded `<script>` / `<style>` the host delegate would otherwise
        // re-indent the caret line after us; Stop keeps our combined depth.
        return if (inEmbedded) EnterHandlerDelegate.Result.Stop
        else EnterHandlerDelegate.Result.Continue
    }

    /**
     * Own a plain Enter inside a SkyTemplate-bearing `<script>` / `<style>`
     * body: insert the newline and the combined HTML + Sky + JS/CSS-brace
     * indent ourselves, place the caret, and let the caller return Stop so
     * the host Enter never runs. Returns false (caller falls through to the
     * platform) when [offset] is not inside such a body.
     *
     * The indent = [SkyTemplatePostFormatLogic.computeIndentForLine] (HTML +
     * Sky block depth) plus [SkyTemplateIndentContext.embeddedBraceDepth]
     * steps (the host language's own `{` nesting the Sky walk can't see).
     */
    private fun ownEmbeddedPlainEnter(
        file: PsiFile,
        editor: Editor,
        document: com.intellij.openapi.editor.Document,
        offset: Int,
        indentStep: String,
    ): Boolean {
        val inEmbedded = SkyTemplateRangeCache
            .getProtectedEmbeddedRanges(document.charsSequence)
            .any { it.startOffset <= offset && offset <= it.endOffset }
        if (!inEmbedded) return false

        document.insertString(offset, "\n")
        SkyTemplateRangeCache.invalidate()
        val text = document.charsSequence
        val newLineStart = offset + 1
        val base = SkyTemplatePostFormatLogic.computeIndentForLine(text, newLineStart, indentStep) ?: ""
        val rawBraceDepth = SkyTemplateIndentContext.embeddedBraceDepth(text, newLineStart)
        val braceDepth = if (SkyTemplateIndentContext.startsWithCloseBrace(text, newLineStart)) {
            (rawBraceDepth - 1).coerceAtLeast(0)
        } else {
            rawBraceDepth
        }
        val indent = if (braceDepth > 0) base + indentStep.repeat(braceDepth) else base
        if (indent.isNotEmpty()) {
            document.insertString(newLineStart, indent)
            SkyTemplateRangeCache.invalidate()
        }

        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        editor.caretModel.moveToOffset(newLineStart + indent.length)
        return true
    }

    /**
     * Replace the caret line's leading whitespace with the
     * Sky-aware desired indent (computed by
     * [SkyTemplatePostFormatLogic.computeIndentForLine]). Caret is
     * adjusted so that:
     *   - if it was inside the leading whitespace, it lands at the end
     *     of the new indent (typical Enter outcome — caret on a freshly
     *     inserted blank line),
     *   - if it was past the whitespace (mid-line content), it shifts
     *     by the indent-length delta.
     */
    private fun applyCaretLineIndent(
        editor: Editor,
        document: com.intellij.openapi.editor.Document,
        caretOffset: Int,
        indentStep: String,
    ) {
        val text = document.charsSequence
        var lineStart = caretOffset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = caretOffset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++

        val base = SkyTemplatePostFormatLogic
            .computeIndentForLine(text, lineStart, indentStep)
            ?: return
        // Add the embedded JS / CSS brace nesting the Sky/HTML walk can't see
        // (a `function () {` body inside `{?var}` sits one level deeper than
        // the Sky body depth). Zero outside `<script>` / `<style>`. A line
        // that itself closes with `}` is counted by embeddedBraceDepth as
        // still inside the brace it closes — compensate by one level so
        // the closer aligns with the opener instead of its body.
        val rawBraceDepth = SkyTemplateIndentContext.embeddedBraceDepth(text, lineStart)
        val braceDepth = if (SkyTemplateIndentContext.startsWithCloseBrace(text, lineStart)) {
            (rawBraceDepth - 1).coerceAtLeast(0)
        } else {
            rawBraceDepth
        }
        val desired = if (braceDepth > 0) base + indentStep.repeat(braceDepth) else base

        var firstNonWs = lineStart
        while (firstNonWs < lineEnd && (text[firstNonWs] == ' ' || text[firstNonWs] == '\t')) firstNonWs++
        val current = text.subSequence(lineStart, firstNonWs).toString()
        if (current == desired) return

        // Apply rule:
        //   - blank line (firstNonWs == lineEnd) → set indent to desired
        //     (caret will sit at end of new indent).
        //   - line starts with a Sky / HTML closer or branch → two-sided
        //     (set indent to desired so the closer aligns with opener).
        //   - other content → one-sided (lift only when shallower).
        val isBlank = firstNonWs == lineEnd
        val firstIsCloserOrBranch = !isBlank && isCloserOrBranchLineStart(text, firstNonWs, lineEnd)
        val shouldEdit = when {
            isBlank -> current != desired
            firstIsCloserOrBranch -> current != desired
            else -> visualWidth(current, indentStep) < visualWidth(desired, indentStep)
        }
        if (!shouldEdit) return

        document.replaceString(lineStart, firstNonWs, desired)
        SkyTemplateRangeCache.invalidate()

        val delta = desired.length - current.length
        val newCaret = when {
            caretOffset <= lineStart -> caretOffset
            caretOffset <= firstNonWs -> lineStart + desired.length
            else -> caretOffset + delta
        }
        editor.caretModel.moveToOffset(newCaret)
    }

    /**
     * Apply a smart-split edit at [offset]. Builds the insertion based on
     * what's BEFORE the caret on its line — see
     * [SkyTemplateEnterHandlerLogic.SmartSplitInfo] for the rule details.
     *
     * Insertion shapes:
     *   - **Pre-caret is content** (Case B): `\n + bodyIndent + \n +
     *     closerIndent`. Caret moves to end of `bodyIndent` on the new
     *     blank indented line.
     *   - **Pre-caret is whitespace, shallower than bodyIndent** (Case
     *     A with lift): missing `bodyIndent` chars + `\n + closerIndent`.
     *     The pre-caret whitespace already on the line + the missing
     *     chars together form `bodyIndent`. Caret moves to end of new
     *     indent on the same line.
     *   - **Pre-caret is whitespace, ≥ bodyIndent** (Case A no lift):
     *     `\n + closerIndent`. The pre-caret whitespace IS the body
     *     indent (or deeper, user's choice). Caret stays where it was
     *     (end of pre-caret whitespace), now end-of-line just before
     *     the inserted newline.
     */
    private fun applySmartSplit(
        file: PsiFile,
        editor: Editor,
        document: com.intellij.openapi.editor.Document,
        offset: Int,
        split: SkyTemplateEnterHandlerLogic.SmartSplitInfo,
    ) {
        val (insertion, caretShift) = if (split.preCaretIsAllWs) {
            if (split.preCaretLength < split.bodyIndent.length) {
                val missing = split.bodyIndent.substring(split.preCaretLength)
                Pair(missing + "\n" + split.closerIndent, missing.length)
            } else {
                Pair("\n" + split.closerIndent, 0)
            }
        } else {
            Pair(
                "\n" + split.bodyIndent + "\n" + split.closerIndent,
                1 + split.bodyIndent.length,
            )
        }

        document.insertString(offset, insertion)
        SkyTemplateRangeCache.invalidate()
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        editor.caretModel.moveToOffset(offset + caretShift)
    }

    /**
     * Cheap check: starting at [firstNonWs], does the rest of the line
     * begin with a SkyTemplate closer / branch (`{/`, `{end`, `{:`,
     * `{else`, `{elseif`) or HTML closer (`</`)? Used to decide whether
     * to apply two-sided indenting on the caret line.
     */
    private fun isCloserOrBranchLineStart(text: CharSequence, firstNonWs: Int, lineEnd: Int): Boolean {
        if (firstNonWs >= lineEnd) return false
        val c = text[firstNonWs]
        if (c == '{' && firstNonWs + 1 < lineEnd) {
            val n = text[firstNonWs + 1]
            if (n == '/' || n == ':') return true
            // Keyword forms — match `{end…}`, `{else…}`, `{elseif…}` ignoring case.
            val rest = text.subSequence(firstNonWs + 1, lineEnd.coerceAtMost(firstNonWs + 12)).toString().lowercase()
            if (rest.startsWith("end") || rest.startsWith("else")) return true
        }
        if (c == '<' && firstNonWs + 1 < lineEnd && text[firstNonWs + 1] == '/') return true
        return false
    }

    /**
     * Visual width of an indent prefix: a tab counts as [indentStep]'s
     * length (the project's indent size) rather than 1, so comparisons
     * against a space-based indent are accurate under
     * `USE_TAB_CHARACTER` projects — a single `\t` and `"    "` compare
     * as equal width instead of `\t` looking 4x shallower.
     */
    private fun visualWidth(s: String, indentStep: String): Int {
        val tabWidth = indentStep.length.coerceAtLeast(1)
        var width = 0
        for (c in s) width += if (c == '\t') tabWidth else 1
        return width
    }

    private fun lineEndOf(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return i
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
        /** Document offset of the just-typed opener's `{`. */
        val openerOffset: Int,
    )

    /**
     * Smart-split decision for the case where [caretOffset] sits IMMEDIATELY
     * before a SkyTemplate closer (`{/}`, `{end}`) or branch (`{:}`,
     * `{:expr}`, `{else}`, `{elseif x}`). Mirrors the PHP / JS editor's
     * `{<enter>}` behaviour: an indented blank line is inserted and the
     * closer / branch moves to the next line at the matching opener's
     * depth.
     *
     * The struct describes WHAT to insert; the handler performs the edit
     * and the caret repositioning.
     *
     * @property bodyIndent       indent string for the inserted blank line
     *   (where the caret will end up). Equals matching-opener indent +
     *   one indent step.
     * @property closerIndent     indent string for the closer / branch
     *   line. Equals matching-opener indent.
     * @property preCaretIsAllWs  true when everything before the caret on
     *   the current line is whitespace (or empty). Drives whether we
     *   insert just `\n + closerIndent` (the caret line's existing
     *   whitespace already serves as body indent) or
     *   `\n + bodyIndent + \n + closerIndent` (the caret line has body
     *   content, so we add a fresh blank indented line in between).
     * @property preCaretLength   length of the pre-caret whitespace —
     *   used to decide whether to lift it up to [bodyIndent] (only
     *   relevant when [preCaretIsAllWs]).
     */
    data class SmartSplitInfo(
        val bodyIndent: String,
        val closerIndent: String,
        val preCaretIsAllWs: Boolean,
        val preCaretLength: Int,
    )

    /**
     * Returns smart-split info when [caretOffset] sits immediately before
     * a closer / branch tag whose rest-of-line is only whitespace, and a
     * matching opener exists. Otherwise null — the caller falls through
     * to [analyzeBefore] (the after-opener path) and ultimately to the
     * platform's Enter.
     */
    fun checkSmartSplit(text: CharSequence, caretOffset: Int, indentStep: String): SmartSplitInfo? {
        if (caretOffset < 0 || caretOffset >= text.length) return null
        if (text[caretOffset] != '{') return null

        // Locate the matching `}` for the `{` at the caret on the same line.
        val closeOffset = findClosingBraceOnLine(text, caretOffset) ?: return null

        // The tag must be a closer or branch.
        val kind = classifyTagKind(text, caretOffset, closeOffset + 1)
        if (kind != TagKind.CLOSE && kind != TagKind.BRANCH) return null

        // Everything after the closing `}` on the line must be whitespace.
        // A trailing comment or content disqualifies the smart split — the
        // user's hand-typed structure shouldn't be rearranged in that case.
        var i = closeOffset + 1
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i < text.length && text[i] != '\n') return null

        // Locate the matching opener (LIFO + indent-aware unwinding —
        // same rule the closing-tag aligner and the unclosed-block
        // inspection use). No matching opener → leave Enter alone.
        val openerOffset = findMatchingOpenerForSplit(text, caretOffset) ?: return null
        val openerIndent = lineIndentStringAtOffset(text, openerOffset)

        // Inspect what sits BEFORE the caret on the current line.
        val lineStart = lineStartOfOffset(text, caretOffset)
        val preCaretText = text.subSequence(lineStart, caretOffset).toString()
        val preCaretIsAllWs = preCaretText.all { it == ' ' || it == '\t' }

        return SmartSplitInfo(
            bodyIndent = openerIndent + indentStep,
            closerIndent = openerIndent,
            preCaretIsAllWs = preCaretIsAllWs,
            preCaretLength = preCaretText.length,
        )
    }

    /** Internal tag kind for [findMatchingOpenerForSplit] / [classifyTagKind]. */
    private enum class TagKind { OPEN, CLOSE, BRANCH, OTHER }

    /**
     * Same classifier the post-format / typed-handler aligner uses. Kept
     * private to this object; the duplicated copies across the handler
     * code lets each handler stay independently auditable while sharing
     * the same recognition rules.
     */
    private fun classifyTagKind(text: CharSequence, openOffset: Int, closeEndOffset: Int): TagKind {
        val bodyStart = openOffset + 1
        val bodyEnd = closeEndOffset - 1
        if (bodyEnd <= bodyStart) return TagKind.OTHER
        var i = bodyStart
        while (i < bodyEnd && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= bodyEnd) return TagKind.OTHER
        val first = text[i]
        val hasLeadingWs = i > bodyStart
        if (first == '/') {
            // `{/}` / `{/  }` — closer with optional trailing whitespace.
            // `{/  // comment}` — closer + line comment. Anything else
            // (`{/foo}`) is not a closer — mirrors FoldingBuilder's rule.
            var j = i + 1
            while (j < bodyEnd && (text[j] == ' ' || text[j] == '\t')) j++
            if (j >= bodyEnd) return TagKind.CLOSE
            if (j + 1 < bodyEnd && text[j] == '/' && text[j + 1] == '/') return TagKind.CLOSE
            return TagKind.OTHER
        }
        if (first == ':') return TagKind.BRANCH
        if (first == '?' || first == '@' || first == '%') return TagKind.OPEN
        if (hasLeadingWs) return TagKind.OTHER
        if (!(first.isLetter() || first == '_')) return TagKind.OTHER
        var j = i + 1
        while (j < bodyEnd && (text[j].isLetterOrDigit() || text[j] == '_')) j++
        val word = text.subSequence(i, j).toString().lowercase()
        val followedByBoundary = j >= bodyEnd || !(text[j].isLetterOrDigit() || text[j] == '_')
        if (!followedByBoundary) return TagKind.OTHER
        return when (word) {
            "loop", "foreach", "for", "while", "if", "each" -> TagKind.OPEN
            "else", "elseif" -> TagKind.BRANCH
            "end" -> TagKind.CLOSE
            else -> TagKind.OTHER
        }
    }

    /**
     * Locate the `}` that closes the `{` at [openOffset], staying within
     * the same line. Returns null on a stray `{` or a multi-line tag.
     * `${` is treated as not-an-open (JS template-literal syntax).
     */
    private fun findClosingBraceOnLine(text: CharSequence, openOffset: Int): Int? {
        var depth = 1
        var i = openOffset + 1
        while (i < text.length) {
            when (text[i]) {
                '\n' -> return null
                '{' -> if (i > 0 && text[i - 1] == '$') { /* skip */ } else depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /**
     * LIFO + indent-aware match for the closer / branch starting at
     * [ourOpenOffset]. Walks every Sky tag before our position, keeping
     * a depth stack of opener offsets and their line-indent widths;
     * closers seen along the way pop the stack with indent-unwinding so
     * a closer at outer indent skips over openers at deeper indent. Our
     * own indent then triggers a final unwind so the caret's column
     * decides which level we're closing.
     */
    private fun findMatchingOpenerForSplit(text: CharSequence, ourOpenOffset: Int): Int? {
        val ranges = SkyTemplateRangeCache.get(text)
        val stack = ArrayDeque<Pair<Int, Int>>()                       // (openerOffset, indentWidth)
        for (range in ranges) {
            if (range.startOffset >= ourOpenOffset) break
            when (classifyTagKind(text, range.startOffset, range.endOffset)) {
                TagKind.OPEN -> {
                    stack.addLast(range.startOffset to lineIndentWidthAtOffset(text, range.startOffset))
                }
                TagKind.CLOSE -> {
                    if (stack.isNotEmpty()) {
                        val closerIndent = lineIndentWidthAtOffset(text, range.startOffset)
                        while (stack.isNotEmpty() && stack.last().second > closerIndent) {
                            stack.removeLast()
                        }
                        if (stack.isNotEmpty()) stack.removeLast()
                    }
                }
                else -> {}
            }
        }
        val ourIndent = lineIndentWidthAtOffset(text, ourOpenOffset)
        while (stack.isNotEmpty() && stack.last().second > ourIndent) {
            stack.removeLast()
        }
        return stack.lastOrNull()?.first
    }

    private fun lineStartOfOffset(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineIndentStringAtOffset(text: CharSequence, offset: Int): String {
        val start = lineStartOfOffset(text, offset)
        var i = start
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.subSequence(start, i).toString()
    }

    private fun lineIndentWidthAtOffset(text: CharSequence, offset: Int): Int {
        val start = lineStartOfOffset(text, offset)
        var i = start
        var w = 0
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
            w++
            i++
        }
        return w
    }

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
        val commentRanges = SkyTemplateRangeCache.getCommentRanges(text)
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
        //   - BLOCK_OPEN: insert `{/}` when the just-typed opener has no
        //     matching closer under the SAME indent-aware pairing rules
        //     used by inspections / folding (LIFO with indent-unwinding —
        //     a closer at outer indent skips over openers at deeper
        //     indent, leaving them unpaired). This catches both the
        //     "fresh top-level opener" case and the "matching `{/}`
        //     exists but at a wrong indent for this opener" case.
        //   - BRANCH (`{:}`, `{else}`, `{elseif}`): NEVER insert `{/}`.
        //     Branches always sit inside an existing block; the surrounding
        //     opener was almost certainly auto-closed when the user typed
        //     it (e.g. `{?cond}` Enter already inserted `{/}`), so adding
        //     another `{/}` just duplicates the closer. The user still
        //     gets the indented blank line for the branch body.
        val needsAutoClose = when (kind) {
            OpeningKind.BLOCK_OPEN -> openerIsUnpaired(text, openOffset)
            OpeningKind.BRANCH -> false
        }

        return EnterAnalysis(
            indent = indent,
            needsAutoClose = needsAutoClose,
            openerOffset = openOffset,
        )
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
     * Indent-aware pairing check: returns true when the opener at
     * [openOffset] would be left unpaired by the same algorithm that
     * `SkyTemplateFoldingScanner.analyze` runs for inspections — LIFO
     * with indent-unwinding (a closer at outer indent skips over openers
     * at deeper indent, leaving them unpaired).
     *
     * This handles three cases simple opens-vs-closes balance gets wrong:
     *   - "matching `{/}` exists but at outer indent than the new
     *     opener" → unpaired → still insert (the existing closer logically
     *     matches an enclosing opener, not ours).
     *   - "nested edit inside an already-closed block" → 2 opens,
     *     1 close. Indent-unwinding pops the inner opener as unpaired
     *     and pairs the outer with the existing `{/}`, so the new opener
     *     is unpaired → insert.
     *   - "balanced file but new opener typed below an unrelated
     *     `{/}`/branch fragment" → the upstream `{/}` already paired
     *     (LIFO), so the new opener is unpaired → insert.
     *
     * Branches (`{:}`, `{else}`, `{elseif}`) participate in pairing
     * elsewhere but are not openers, so they neither block nor satisfy
     * a closer — handled by the scanner the same way pairing logic
     * does in inspection/folding code.
     */
    private fun openerIsUnpaired(text: CharSequence, openOffset: Int): Boolean {
        val analysis = SkyTemplateRangeCache.getBlockPairing(text)
        return analysis.unpairedOpens.any { it.range.startOffset == openOffset }
    }

}
