package com.novaframework.templatelang.sky

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Code-folding for SkyTemplate files (`*.sky`).
 *
 * Folds:
 *   - Block tags: `{loop …} … {/}`, `{foreach …} … {/}`, `{for …} … {/}`,
 *     `{while …} … {/}`, `{if …} … {/}`, `{?expr} … {/}`, `{@…} … {/}`,
 *     `{%…} … {/}`, `{each …} … {/}`. The closer can be either `{/}` or
 *     the keyword `{end}`. `{else}` / `{elseif}` / `{:…}` branches DO NOT
 *     reset depth — they sit inside an already-open block.
 *   - Multi-line `{*…*}` comments (single-line ones offer no folding value).
 *   - Multi-line `<!--{…}-->` wrapped directives (best-effort: any wrapper
 *     that spans more than one line collapses).
 *
 * Placeholder text (collapsed view):
 *   - Block tag — header line through its closing `}` + ` … {/}`. So
 *     `{loop users as u}\n  …\n{/}` collapses to `{loop users as u} … {/}`.
 *   - `{*…*}` — `{*…*}`.
 *   - `<!--{…}-->` — `<!--{…}-->`.
 *
 * No region is collapsed by default; users decide what to fold.
 *
 * Implementation note: SkyTemplate's PSI is currently a flat tree of leaf
 * tokens (M2 deferred structural parsing). We don't have first-class
 * `Tag` / `Block` PSI nodes to attach descriptors to. Instead, every
 * descriptor is rooted at the file's PSI element — the platform allows
 * multiple descriptors on the same node — and ranges are computed by a
 * lexer-driven scan over the file text. This is the same strategy the
 * annotator uses (`SkyTemplateAnnotator`) and side-steps the missing PSI
 * structure cleanly.
 */
class SkyTemplateFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ): Array<FoldingDescriptor> {
        val file = root.containingFile ?: return emptyArray()
        if (!TemplateLangFileFilter.shouldProcess(file)) return emptyArray()
        val viewProvider = file.viewProvider

        // Three host categories the plugin supports:
        //   1. `*.sky` — multi-tree, base language is SkyTemplate.
        //      The platform fires this builder twice (once for the SkyTemplate
        //      root, once for the HTML data root). Run only when invoked on
        //      the SkyTemplate root to avoid duplicate descriptors.
        //   2. Pure SkyTemplate file (no data tree) — run.
        //   3. `*.html` / `*.htm` / `*.xml` — primary language is HTML/XML and
        //      there's no SkyTemplate base tree. Run, scan the document text.
        val rootLang = root.language
        val baseLang = viewProvider.baseLanguage
        val isSkyMultiTree = baseLang === SkyTemplateLanguage
        val accept = when {
            isSkyMultiTree -> rootLang === SkyTemplateLanguage
            rootLang === SkyTemplateLanguage -> true
            rootLang === HTMLLanguage.INSTANCE || rootLang === XMLLanguage.INSTANCE -> true
            else -> false
        }
        if (!accept) return emptyArray()

        val text = viewProvider.contents
        if (text.isEmpty()) return emptyArray()
        if ('{' !in text) return emptyArray()

        // Routed through SkyTemplateRangeCache (identity-keyed on this
        // viewProvider.contents instance) instead of calling
        // SkyTemplateFoldingScanner.scan(text) directly, so a folding pass
        // that follows (or precedes) an inspection / annotator pass over the
        // same document reuses that pass's block-pairing result instead of
        // re-lexing the whole file.
        val regions = SkyTemplateRangeCache.getBlockPairing(text).foldRegions
        if (regions.isEmpty()) return emptyArray()

        val node = root.node ?: file.node ?: return emptyArray()
        // No FoldingGroup — each block fold should collapse independently
        // (a shared group would force every region to fold/unfold together,
        // which kills nested-block UX where outer `{loop}` and inner `{if}`
        // are independent fold targets).
        return regions.map { region ->
            FoldingDescriptor(node, region.range, /* group = */ null, region.placeholder)
        }.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String? {
        // Each FoldingDescriptor was built with an explicit placeholder, so
        // the platform consults the descriptor directly. This fallback
        // covers the rare path where it asks the builder by node — keep a
        // sensible default.
        return DEFAULT_PLACEHOLDER
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private companion object {
        const val DEFAULT_PLACEHOLDER = "..."
    }
}

/**
 * Pure scanner — walks the file text and produces fold regions. UI-free, so
 * it can be unit-tested without spinning up the IntelliJ test fixture.
 */
object SkyTemplateFoldingScanner {

    data class FoldRegion(val range: TextRange, val placeholder: String)

    /**
     * Block-pairing diagnostic — describes both the matched fold regions and
     * the structural mistakes (unpaired opens / orphan branches) found while
     * walking the text. Inspections (`SkyTemplateUnclosedBlockInspection`,
     * `SkyTemplateOrphanElseInspection`) consume the latter two fields.
     */
    data class BlockPairingResult(
        val foldRegions: List<FoldRegion>,
        val unpairedOpens: List<UnpairedOpen>,
        val orphanBranches: List<OrphanBranch>,
    )

    /**
     * Open block tag (e.g. `{loop xs as x}`) that has no matching closer.
     *
     * @property range          range of the opener tag itself.
     * @property openText       the opener text, e.g. `{loop xs as x}`.
     * @property openLine       1-based line number where the opener sits.
     * @property suggestedClose 1-based line number where the closer would
     *   most naturally fit, derived from indent: the line after the LAST
     *   line whose indent is greater than the opener's indent. Falls back
     *   to the last non-empty line of the file when the heuristic finds
     *   no clearly-indented body. Inspections surface this as a hint in
     *   the error message.
     */
    data class UnpairedOpen(
        val range: TextRange,
        val openText: String,
        val openLine: Int,
        val suggestedClose: Int,
    )

    /**
     * `{else}` / `{elseif …}` / `{:}` / `{:expr}` that appears outside any
     * enclosing block opener. Note: orphan close tags (`{/}` with no opener)
     * are NOT reported — common in partial-template files where the opener
     * lives in another included fragment.
     */
    data class OrphanBranch(val range: TextRange, val keyword: String)

    /**
     * Convenience: compute fold regions only. Equivalent to
     * `analyze(text).foldRegions` and kept stable for [SkyTemplateFoldingBuilder].
     */
    fun scan(text: CharSequence): List<FoldRegion> = analyze(text).foldRegions

    /**
     * Walk [text] and produce both fold regions and structural diagnostics in
     * a single pass.
     */
    fun analyze(text: CharSequence): BlockPairingResult {
        if (text.isEmpty() || '{' !in text) {
            return BlockPairingResult(emptyList(), emptyList(), emptyList())
        }
        val phpRanges = SkyTemplateRanges.computePhpRanges(text)
        val commentRanges = SkyTemplateRanges.computeCommentRanges(text, phpRanges)
        val wrappedMatches = SkyTemplateRanges.computeWrappedMatches(text)
        val templateRanges = SkyTemplateRanges.computeTemplateRanges(text, commentRanges, phpRanges, wrappedMatches)
        val (regions, unpaired, orphans) = scanInternal(text, commentRanges, wrappedMatches, templateRanges)
        return BlockPairingResult(regions, unpaired, orphans)
    }

    /**
     * Overload for callers that already hold [commentRanges] / [templateRanges]
     * from a shared per-document computation (e.g. [SkyTemplateRangeCache]'s
     * entry, which computes both as part of its own lazy fields) — skips
     * recomputing them so the whole-file comment-lex / PHP-region /
     * brace-pairing scans run once per document instead of once per
     * consumer. [SkyTemplateRanges.computeWrappedMatches] is still run once
     * here since it isn't part of the shared entry.
     */
    internal fun analyze(
        text: CharSequence,
        commentRanges: List<TextRange>,
        templateRanges: List<TextRange>,
    ): BlockPairingResult {
        if (text.isEmpty() || '{' !in text) {
            return BlockPairingResult(emptyList(), emptyList(), emptyList())
        }
        val wrappedMatches = SkyTemplateRanges.computeWrappedMatches(text)
        val (regions, unpaired, orphans) = scanInternal(text, commentRanges, wrappedMatches, templateRanges)
        return BlockPairingResult(regions, unpaired, orphans)
    }

    /**
     * Single-pass walker. Returns fold regions + diagnostics. Takes the
     * intermediate scans as parameters (rather than recomputing them) so
     * both [analyze] overloads share this one assembly step.
     */
    private fun scanInternal(
        text: CharSequence,
        commentRanges: List<TextRange>,
        wrappedMatches: List<TextRange>,
        templateRanges: List<TextRange>,
    ): Triple<List<FoldRegion>, List<UnpairedOpen>, List<OrphanBranch>> {
        val regions = ArrayList<FoldRegion>()

        // 1. Multi-line `{*…*}` comments and wrapped `<!--{*…*}-->` forms.
        for (range in commentRanges) {
            if (spansMultipleLines(text, range)) {
                val placeholder = if (looksWrapped(text, range)) "<!--{*…*}-->" else "{*…*}"
                regions += FoldRegion(range, placeholder)
            }
        }

        // 2. Multi-line wrapped non-comment directives (`<!--{loop x}-->` …
        //    `<!--{/}-->`). The wrapper-by-wrapper handling for matched
        //    blocks would be useful, but the M9 spec only asks for the
        //    plain `{ … }` block form to be primary. Wrapped multi-line
        //    spans of a SINGLE wrapper still benefit from a fold (e.g. a
        //    `<!--{ very long expression }-->` literal that wraps lines),
        //    so we add those as standalone folds.
        for (range in wrappedMatches) {
            if (!spansMultipleLines(text, range)) continue
            // A match overlapping a comment is either the comment itself
            // (already folded above), a directive inside `{*…*}` (inert), or
            // the lazy regex mis-stopping at an inner `}-->` of a wrapped
            // comment — skip all three.
            if (SkyTemplateRanges.anyOverlap(commentRanges, range.startOffset, range.endOffset)) continue
            regions += FoldRegion(range, "<!--{…}-->")
        }

        // 3. Block tags: pair openers with closers using a depth stack over
        //    the already-computed [templateRanges]. The same pass produces
        //    diagnostics — leftover open tags (`unpaired`) and branch tags
        //    that appeared outside any open block (`orphans`).
        val (blockRegions, unpaired, orphans) = scanBlockTagsWithDiagnostics(text, templateRanges)
        regions += blockRegions

        // Order by start so the platform receives them sensibly (the API
        // doesn't strictly require this, but keeping output deterministic
        // helps tests).
        regions.sortBy { it.range.startOffset }
        return Triple(regions, unpaired, orphans)
    }

    /**
     * Lexer-driven block matcher with diagnostics. Walks every `{ … }` /
     * `<!--{ … }-->` span and classifies it; produces fold regions plus the
     * diagnostic lists used by inspections.
     *
     * Diagnostics policy:
     *   - `BLOCK_OPEN` left on the stack at end-of-text → reported as
     *     `UnpairedOpen` (the inspection turns this into "Unclosed block").
     *   - `BRANCH` (`{else}`, `{elseif}`, `{:}`, `{:expr}`) encountered while
     *     the open stack is empty → reported as `OrphanBranch` (the
     *     inspection turns this into "Orphan `{else}` outside block").
     *   - `BLOCK_CLOSE` (`{/}`, `{end}`) with an empty stack is intentionally
     *     **not** reported. Partial-template files often contain trailing
     *     closers whose opener lives in an included fragment; reporting
     *     would be a false positive in those legitimate workflows.
     */
    private data class StackEntry(
        val openOffset: Int,
        val openEnd: Int,
        val openText: String,
        val indent: Int,
    )

    private fun scanBlockTagsWithDiagnostics(
        text: CharSequence,
        pairs: List<TextRange>,
    ): Triple<List<FoldRegion>, List<UnpairedOpen>, List<OrphanBranch>> {
        // [pairs] is every `{ … }` span that looks like a template tag (this
        // already includes wrapped `<!--{ … }-->` ranges from
        // computeTemplateRanges, so wrapped openers / closers participate
        // in pairing alongside their plain counterparts).
        if (pairs.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())

        val openStack = ArrayDeque<StackEntry>()
        val regions = ArrayList<FoldRegion>()
        val orphans = ArrayList<OrphanBranch>()
        // Openers discovered as "skipped over" while indent-unwinding a
        // closer — the closer sits at outer indent than the opener on
        // top of the stack, so the opener cannot logically own this
        // closer. The opener was forgotten; report it as unclosed.
        val unpairedFromUnwind = ArrayList<UnpairedOpen>()

        for (range in pairs) {
            val open = range.startOffset
            val close = range.endOffset
            when (classify(text, open, close)) {
                TagKind.BLOCK_OPEN -> {
                    val openText = text.subSequence(open, close).toString()
                    val indent = lineIndentWidth(text, open)
                    openStack.addLast(StackEntry(open, close, openText, indent))
                }
                TagKind.BLOCK_CLOSE -> {
                    val closerIndent = lineIndentWidth(text, open)
                    // Indent-aware unwinding: any opener still on the
                    // stack whose indent is STRICTLY GREATER than the
                    // closer's must have been missed — the closer can't
                    // logically own an opener nested deeper than itself.
                    // Reports the deepest forgotten opener instead of
                    // (or in addition to) any outer opener that ends up
                    // unpaired at EOF.
                    while (openStack.isNotEmpty() &&
                        openStack.last().indent > closerIndent
                    ) {
                        unpairedFromUnwind += toUnpairedOpen(text, openStack.removeLast())
                    }
                    if (openStack.isNotEmpty()) {
                        val matched = openStack.removeLast()
                        val span = TextRange(matched.openOffset, close)
                        if (spansMultipleLines(text, span)) {
                            val placeholder = buildBlockPlaceholder(
                                text, matched.openOffset, matched.openEnd, open, close,
                            )
                            regions += FoldRegion(span, placeholder)
                        }
                    }
                    // Orphan close: intentionally not reported (see KDoc).
                }
                TagKind.BRANCH -> {
                    // Branch (`{else}` / `{elseif}` / `{:}` / `{:expr}`)
                    // outside any open block is an "orphan branch" —
                    // SkyTemplate cannot compile it.
                    if (openStack.isEmpty()) {
                        val keyword = extractBranchKeyword(text, open, close)
                        orphans += OrphanBranch(TextRange(open, close), keyword)
                    }
                    // Inside a block — no stack effect.
                }
                TagKind.OTHER -> {
                    // No stack effect.
                }
            }
        }

        // Anything left in the open stack at end-of-text is unclosed.
        val unpairedAtEof = openStack.map { toUnpairedOpen(text, it) }
        return Triple(regions, unpairedFromUnwind + unpairedAtEof, orphans)
    }

    private fun toUnpairedOpen(text: CharSequence, entry: StackEntry): UnpairedOpen {
        val openLine = lineNumber(text, entry.openOffset)
        return UnpairedOpen(
            range = TextRange(entry.openOffset, entry.openEnd),
            openText = entry.openText,
            openLine = openLine,
            suggestedClose = suggestCloseLine(text, entry.openOffset, openLine),
        )
    }

    /**
     * Heuristic: where would the user most naturally have placed `{/}`
     * for the unclosed opener at [openOffset] (1-based [openLine])?
     *
     * Walk forward from the opener line to end-of-text, tracking the
     * deepest body line — the LAST line whose indent is strictly greater
     * than the opener's indent. The natural close fits at one line below
     * that. Returns [openLine] (i.e. "no useful hint") when the body is
     * empty or no line is indented further than the opener — in those
     * cases the opener already tells the user everything, and a hint
     * pointing at the line right after the opener would be noise.
     */
    private fun suggestCloseLine(text: CharSequence, openOffset: Int, openLine: Int): Int {
        val openIndent = lineIndentWidth(text, openOffset)

        var idx = nextLineStart(text, openOffset)
        var line = openLine
        var deepestBodyLine = -1

        while (idx < text.length) {
            line++
            val lineEnd = lineEnd(text, idx)
            if (lineEnd > idx) {
                val indent = lineIndentWidth(text, idx)
                val nonEmpty = lineHasNonWhitespace(text, idx, lineEnd)
                if (nonEmpty && indent > openIndent) deepestBodyLine = line
            }
            idx = lineEnd + if (lineEnd < text.length) 1 else 0
        }

        return if (deepestBodyLine > 0) deepestBodyLine + 1 else openLine
    }

    /** 1-based line number for [offset]. */
    private fun lineNumber(text: CharSequence, offset: Int): Int {
        var n = 1
        for (i in 0 until offset.coerceAtMost(text.length)) {
            if (text[i] == '\n') n++
        }
        return n
    }

    /** Indent width (spaces=1, tab=1) of the line containing [offset]. */
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

    /** Offset of the start of the line FOLLOWING the line at [offset]. */
    private fun nextLineStart(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return if (i < text.length) i + 1 else text.length
    }

    /** Offset of the `\n` (or text.length) ending the line starting at [start]. */
    private fun lineEnd(text: CharSequence, start: Int): Int {
        var i = start
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun lineHasNonWhitespace(text: CharSequence, start: Int, end: Int): Boolean {
        for (i in start until end) {
            val c = text[i]
            if (c != ' ' && c != '\t' && c != '\r') return true
        }
        return false
    }

    /**
     * Pull the user-facing keyword from a branch tag for the inspection
     * message. Returns one of `else` / `elseif` / `:` / `?:` / `:<expr>` —
     * for the prefix forms we keep the leading char so the message is
     * recognisable to the user (they wrote `{:}`, not `{else}`).
     */
    private fun extractBranchKeyword(
        text: CharSequence,
        openOffset: Int,
        closeEndOffset: Int,
    ): String {
        val (innerOpen, innerCloseEnd) = SkyTemplateRanges.innerBraceBounds(text, openOffset, closeEndOffset)
            ?: return "else"
        var i = innerOpen + 1
        val end = innerCloseEnd - 1
        while (i < end && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= end) return "else"

        // Prefix forms.
        if (text[i] == ':') return ":"
        if (text[i] == '?' && i + 1 < end && text[i + 1] == ':') return "?:"

        // Keyword forms.
        var j = i
        while (j < end && (text[j].isLetterOrDigit() || text[j] == '_')) j++
        if (j > i) return text.subSequence(i, j).toString()
        return "else"
    }

    /** Classification of a single `{ … }` span. */
    private enum class TagKind { BLOCK_OPEN, BLOCK_CLOSE, BRANCH, OTHER }

    private fun classify(text: CharSequence, openOffset: Int, closeEndOffset: Int): TagKind {
        val (innerOpen, innerCloseEnd) = SkyTemplateRanges.innerBraceBounds(text, openOffset, closeEndOffset)
            ?: return TagKind.OTHER
        val bodyStart = innerOpen + 1
        val bodyEnd = innerCloseEnd - 1
        if (bodyEnd <= bodyStart) return TagKind.OTHER

        // Skip horizontal whitespace.
        var i = bodyStart
        while (i < bodyEnd && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= bodyEnd) return TagKind.OTHER
        val first = text[i]
        val hasLeadingWs = i > bodyStart

        // Closing forms.
        if (first == '/') {
            // `{/}` / `{/  }` — closer with optional trailing whitespace.
            // `{/  // comment}` / `{/// comment}` — closer + line comment per
            // SkyTemplate's `\h*//[^}\n]*` suffix on PATTERN_VAR. Anything
            // else (`{/foo}`) is treated as OTHER to stay conservative.
            var j = i + 1
            while (j < bodyEnd && (text[j] == ' ' || text[j] == '\t')) j++
            if (j >= bodyEnd) return TagKind.BLOCK_CLOSE
            if (j + 1 < bodyEnd && text[j] == '/' && text[j + 1] == '/') return TagKind.BLOCK_CLOSE
            return TagKind.OTHER
        }

        // `?:` prefix — SkyTemplate "elvis" / fallback. Compiler emits
        // `if ($e=expr) { echo $e; } else {` and pushes `'if'` onto its
        // arrBlock stack (SkyTemplateCompiler::tagElvis), so `{?:expr}`
        // opens a block that MUST be closed by `{/}` or `{end}` — same
        // pairing semantics as `{if expr}`. Treat as BLOCK_OPEN. Must be
        // checked before the bare `?` rule so we don't double-classify.
        if (first == '?' && i + 1 < bodyEnd && text[i + 1] == ':') {
            return TagKind.BLOCK_OPEN
        }

        // Branch prefix `:` — `{:}` plain `else`, `{:case}` / `{:expr}`
        // are branches inside an already-open block. Don't pop.
        if (first == ':') {
            return TagKind.BRANCH
        }

        // Block opens via prefix.
        if (first == '?' || first == '@' || first == '%') return TagKind.BLOCK_OPEN

        // Keyword form: must be immediately at body start, like the lexer's
        // atTagStart rule.
        if (hasLeadingWs) return TagKind.OTHER
        if (!(first.isLetter() || first == '_')) return TagKind.OTHER

        var j = i + 1
        while (j < bodyEnd && (text[j].isLetterOrDigit() || text[j] == '_')) j++
        val word = text.subSequence(i, j).toString().lowercase()
        val followedByBoundary = j >= bodyEnd || !(text[j].isLetterOrDigit() || text[j] == '_')
        if (!followedByBoundary) return TagKind.OTHER

        return when (word) {
            "loop", "foreach", "for", "while", "if", "each" -> TagKind.BLOCK_OPEN
            "end" -> TagKind.BLOCK_CLOSE
            "else", "elseif" -> TagKind.BRANCH
            else -> TagKind.OTHER
        }
    }

    /**
     * Build a placeholder for a block — the opener and closer rendered
     * verbatim with ` … ` between. Wrapped openers / closers carry their
     * `<!--…-->` shells through so the collapsed view matches the source
     * shape (`<!--{@ data}--> … <!--{/}-->`). Both ends are clipped if
     * they exceed [MAX_HEADER] to keep the gutter tidy.
     */
    private fun buildBlockPlaceholder(
        text: CharSequence,
        openStart: Int,
        openEnd: Int,
        closerStart: Int,
        closerEnd: Int,
    ): String {
        val opener = clipTagText(text.subSequence(openStart, openEnd).toString())
        val closer = clipTagText(text.subSequence(closerStart, closerEnd).toString())
        return "$opener … $closer"
    }

    private fun clipTagText(raw: String): String {
        // Collapse internal whitespace runs to single spaces, drop newlines.
        val normalised = raw.replace(WHITESPACE_RUN, " ").trim()
        return if (normalised.length > MAX_HEADER) {
            normalised.substring(0, MAX_HEADER - 1) + "…"
        } else {
            normalised
        }
    }

    private fun spansMultipleLines(text: CharSequence, range: TextRange): Boolean {
        val start = range.startOffset
        val end = range.endOffset
        if (start < 0 || end > text.length || start >= end) return false
        for (i in start until end) {
            if (text[i] == '\n') return true
        }
        return false
    }

    private fun looksWrapped(text: CharSequence, range: TextRange): Boolean {
        val start = range.startOffset
        if (start + 4 > text.length) return false
        // `<!--` — at least four chars at the start.
        return text[start] == '<' && text[start + 1] == '!' &&
            text[start + 2] == '-' && text[start + 3] == '-'
    }

    private val WHITESPACE_RUN = Regex("""\s+""")
    private const val MAX_HEADER = 60
}
