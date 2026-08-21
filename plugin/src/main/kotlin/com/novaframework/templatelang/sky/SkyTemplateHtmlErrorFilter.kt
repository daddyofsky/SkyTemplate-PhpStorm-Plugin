package com.novaframework.templatelang.sky

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Drops HTML / XML / injected-fragment highlights whose range overlaps a
 * SkyTemplate construct.
 *
 * Coverage:
 *   - WEAK_WARNING and stronger from any host or injected language whose
 *     host file is HTML / XML, when the range overlaps a `{ … }`, `{* … *}`,
 *     or wrapped `<!--{ … }-->` template span.
 *   - Low-severity highlights inside comment ranges — both subtle inspection
 *     hints (with a description) and description-less colour highlights such
 *     as Rainbow Brackets' per-tag `< >` colouring of the HTML PSI the
 *     platform still builds inside a `{*…*}` comment in `*.html` host files.
 *     The user's original example
 *       `<!--{*
 *           <ul><li>{title}</li><!--<li>{hit}</li>--></ul>
 *        *}-->`
 *     produces all kinds of HTML/PHP noise inside the wrap that we want
 *     completely silent.
 *
 * Our own file-level comment overlay ([SkyTemplateAnnotator] phase 1) is an
 * INFORMATION highlight with a null description whose range spans the WHOLE
 * comment; it is preserved because [suppressInsideComment] only drops
 * description-less highlights that are a PROPER SUBSET of a comment range and
 * never one of the plugin's own paint spans ([SkyTemplateCommentPaint]).
 */
class SkyTemplateHtmlErrorFilter : HighlightInfoFilter {

    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        // Gate: fall through with `return true` (=let highlight through),
        // never `return false` — that would suppress every highlight in the
        // file, breaking unrelated languages (PHP, JS, …) entirely.
        if (!TemplateLangFileFilter.shouldProcess(file)) return true
        // file is non-null past the gate.
        @Suppress("NAME_SHADOWING") val file = file!!
        val project = file.project

        // Translate offsets into the host file when the highlight comes from an
        // injected language fragment (PHP, JS, CSS, …).
        val injectionManager = InjectedLanguageManager.getInstance(project)
        val hostFile: PsiFile
        val hostStart: Int
        val hostEnd: Int

        if (injectionManager.isInjectedFragment(file)) {
            val host = injectionManager.getInjectionHost(file) ?: return true
            hostFile = host.containingFile ?: return true
            val fragmentRange = TextRange(highlightInfo.startOffset, highlightInfo.endOffset)
            val translated = injectionManager.injectedToHost(file, fragmentRange)
            hostStart = translated.startOffset
            hostEnd = translated.endOffset
        } else {
            hostFile = file
            hostStart = highlightInfo.startOffset
            hostEnd = highlightInfo.endOffset
        }

        if (!isHtmlLikeFile(hostFile)) return true

        val severity = highlightInfo.severity
        val isWeakOrStronger = severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal

        // Low-severity highlights (INFORMATION hints, plus semantic / Rainbow
        // Brackets colour highlights that carry no description) are inert text
        // when they fall inside a `{*…*}` comment. See [suppressInsideComment].
        if (!isWeakOrStronger) {
            return !suppressInsideComment(highlightInfo, hostFile, hostStart, hostEnd)
        }

        if (isWeakOrStronger) {
            // Whitelist: SkyTemplate's own structural-annotator output lives
            // ON SkyTemplate ranges by design (it flags unclosed openers,
            // orphan branches, loop-scope mismatches, redundant `@`,
            // duplicate else, etc.). Letting these through is the whole
            // point — every other host-language error overlapping a
            // template range still gets dropped below.
            val description = highlightInfo.description
            if (description != null && isOwnDiagnosticMessage(description)) {
                return true
            }

            val templateRanges = templateRangesCached(hostFile)
            if (templateRanges.isNotEmpty() &&
                SkyTemplateRanges.anyOverlap(templateRanges, hostStart, hostEnd)
            ) {
                return false
            }

            // Duplicate-id / duplicate-declaration false positives. The HTML /
            // JS parser flattens every `{?…}{:}{/}` branch and every loop
            // iteration into one scope, so identical ids / declarations across
            // mutually-exclusive branches (or inside a repeating loop body)
            // look like duplicates. Drop these when the highlight sits inside a
            // loop block or a branched if/switch. A plain branch-less `{?cond}`
            // is NOT covered, so genuine collisions against outside content
            // still surface.
            if (description != null &&
                templateRanges.isNotEmpty() &&
                isDuplicateDiagnostic(description)
            ) {
                val blocks = duplicateSuppressionRangesCached(hostFile)
                if (blocks.isNotEmpty() &&
                    blocks.any { it.startOffset <= hostStart && hostEnd <= it.endOffset }
                ) {
                    return false
                }
            }

            // Cross-language structural errors. Constructs like `{/}` (an
            // empty `if` close) or `{:}` (a branch) inside `<script>` /
            // `<style>` are SkyTemplate tags but the embedded JS / CSS
            // parser sees them as raw `{`, `/`, `:`, `}` chars — JS's
            // regex-literal lookahead even consumes the closing brace of
            // `{/}` as regex content, leaving the surrounding function's
            // `{` apparently unclosed. The parser's "Missing }" error then
            // surfaces on the function's real close brace, which sits
            // OUTSIDE every template range, so the per-range overlap check
            // above can't drop it.
            //
            // Suppress these structural parse errors whenever the host
            // file contains ANY SkyTemplate construct. Files with no
            // template usage retain full JS / CSS error reporting.
            if (description != null &&
                templateRanges.isNotEmpty() &&
                isLikelyTemplateInducedSyntaxError(description)
            ) {
                return false
            }

            // JS semantic warnings raised by the embedded-JS parser on tokens
            // that sit just outside a Sky tag's `{…}` bounds (e.g. the `;`
            // after `{=expr}` or the bare `true`/`false` tokens in
            // `{?var}true{:}false{/}`). Exact-range overlap misses them, so
            // we widen the check to line granularity: drop when any template
            // range shares a line with the offending highlight.
            if (description != null &&
                templateRanges.isNotEmpty() &&
                isLikelyTemplateInducedSemanticWarning(description) &&
                lineOverlapsTemplateRange(hostFile, hostStart, hostEnd, templateRanges)
            ) {
                return false
            }
        }

        return true
    }

    /**
     * True when a low-severity highlight at [hostStart, hostEnd) should be
     * dropped because it sits inside a `{*…*}` comment, where every byte is
     * inert text.
     *
     * Two shapes are handled:
     *   - **description != null** — a subtle inspection / daemon hint: dropped
     *     on any overlap with a comment range (the original behaviour).
     *   - **description == null** — an annotator / semantic colour, including
     *     Rainbow Brackets' per-tag `< >` colouring of the HTML PSI that the
     *     platform still builds inside a comment in `*.html` host files. These
     *     are dropped only when the highlight is a PROPER SUBSET of a comment
     *     range, and never when its range is one of our own paint spans
     *     ([SkyTemplateCommentPaint]) — the grey overlay, which normally spans
     *     the whole comment, plus the TODO spans it is split around.
     */
    private fun suppressInsideComment(
        highlightInfo: HighlightInfo,
        hostFile: PsiFile,
        hostStart: Int,
        hostEnd: Int,
    ): Boolean {
        val commentRanges = commentRangesCached(hostFile)
        if (commentRanges.isEmpty()) return false
        if (highlightInfo.description != null) {
            return SkyTemplateRanges.anyOverlap(commentRanges, hostStart, hostEnd)
        }
        val insideComment = commentRanges.any { r ->
            r.startOffset <= hostStart && hostEnd <= r.endOffset &&
                !(r.startOffset == hostStart && r.endOffset == hostEnd)
        }
        if (!insideComment) return false
        // Cheapest last: only a highlight already destined for suppression
        // pays for the TODO-pattern scan over the comment bodies.
        return !SkyTemplateCommentPaint.isPaintedSpan(
            hostFile.text, commentRanges, hostStart, hostEnd,
        )
    }

    private fun isHtmlLikeFile(file: PsiFile): Boolean {
        val lang = file.language
        return lang.isKindOf(HTMLLanguage.INSTANCE) || lang.isKindOf(XMLLanguage.INSTANCE)
    }

    /**
     * Description-prefix check identifying highlight info that we ourselves
     * produced via [com.novaframework.templatelang.inspection.SkyTemplateStructuralAnnotator]
     * or [com.novaframework.templatelang.inspection.SkyTemplateScopeAnnotator].
     * These annotations sit on SkyTemplate ranges by design — without this
     * whitelist the per-range overlap drop further down would silently
     * suppress them in HTML / XML host files.
     */
    private fun isOwnDiagnosticMessage(description: String): Boolean {
        // M7: unclosed block + orphan branch
        if (description.startsWith("Unclosed `")) return true
        if (description.endsWith("outside `{if}` / `{loop}` block")) return true
        // P3-11: SkyTemplateStructuralAnnotator's missing-loop-name diagnostic
        // (`{loop}` / `{each}` / `{@}` / `{%}` with no argument) sits ON the
        // template range by definition — without this whitelist entry it was
        // silently dropped by the per-range overlap suppression below.
        if (description == "Loop tag name is missing") return true
        // 0.5.25 scope/var diagnostics — kept in lockstep with the message
        // strings produced by SkyTemplateScopeAnalyzer.
        if (description.startsWith("Loop-scope reference `")) return true
        if (description.startsWith("Reserved name `")) return true
        if (description.startsWith("`@` modifier has no effect")) return true
        if (description.startsWith("`@0` is treated as")) return true
        if (description.startsWith("Duplicate `{else}`")) return true
        // 0.5.34 SkyTemplateUndefinedSymbolInspection — sits ON SkyTemplate
        // ranges by definition (the unresolved identifier is inside `{ … }`).
        if (description.startsWith("Cannot resolve function `") ||
            description.startsWith("Cannot resolve method `") ||
            description.startsWith("Cannot resolve class `") ||
            description.startsWith("Cannot resolve constant `") ||
            description.startsWith("Cannot resolve class constant `")
        ) return true
        // Phase 3 SkyTemplateArgumentAnnotator — the six argument-validation
        // diagnostics also sit on SkyTemplate ranges (the offending arg /
        // call opener is inside `{ … }`).
        if (description.startsWith("Missing required argument(s) for `") ||
            description.startsWith("Too many arguments for `") ||
            description.startsWith("Unknown parameter `") ||
            description.startsWith("Duplicate named argument `") ||
            description.startsWith("Cannot use positional argument after named argument in `")
        ) return true
        return false
    }

    /**
     * True if [description] looks like a brace / paren / statement-mismatch
     * parser error from JS / CSS / HTML embedded in the host file. The
     * patterns cover the most common phrasings we've seen surfaced from
     * the JavaScript and CSS plugins when SkyTemplate constructs appear
     * inside `<script>` / `<style>` blocks.
     */
    /**
     * True for the host-language "duplicate" diagnostics that branch /
     * loop flattening turns into false positives:
     *   - HTML `Duplicate id reference` (platform XML duplicate-id check).
     *   - JS `Duplicate declaration` (function / variable redeclaration).
     * See [SkyTemplateRanges.computeDuplicateSuppressionRanges] for the
     * containment rule that gates the actual drop.
     */
    private fun isDuplicateDiagnostic(description: String): Boolean =
        description.startsWith("Duplicate id reference") ||
            description.startsWith("Duplicate declaration")

    private fun isLikelyTemplateInducedSyntaxError(description: String): Boolean {
        // "Missing }", "Missing {", "Missing )", "Missing (",
        // "Missing ;", "Missing ,"
        if (description.startsWith("Missing }") ||
            description.startsWith("Missing {") ||
            description.startsWith("Missing )") ||
            description.startsWith("Missing (") ||
            description.startsWith("Missing ]") ||
            description.startsWith("Missing [") ||
            description.startsWith("Missing ;") ||
            description.startsWith("Missing ,")
        ) return true
        // "Unexpected token X", "Unexpected end of file"
        if (description.startsWith("Unexpected ")) return true
        // "Statement expected, found …", "Expression expected", "; expected"
        if (description.endsWith("expected") || description.contains(" expected")) return true
        // "Closing tag matches nothing", "Element … is not closed"
        if (description.startsWith("Closing tag ") || description.contains("is not closed")) return true
        // Generic catch-alls
        if (description.contains("unbalanced", ignoreCase = true)) return true
        return false
    }

    /**
     * JS semantic warnings the embedded-JS parser emits around Sky tags:
     *   - `"Unnecessary semicolon"` — the `;` after `{=expr};` belongs to the
     *     host JS statement; the JS parser sees the tag body as a parse error
     *     fragment and flags the following `;` as redundant.
     *   - `"Expression statement is not assignment or call"` — bare identifiers
     *     like `true` / `false` in `{?var}true{:}false{/}` become standalone
     *     expression statements when the JS parser strips the surrounding tags.
     */
    private fun isLikelyTemplateInducedSemanticWarning(description: String): Boolean =
        description.startsWith("Unnecessary semicolon") ||
            description.startsWith("Expression statement is not assignment or call")

    /**
     * True when the line(s) containing `[hostStart, hostEnd)` overlap any range
     * in [templateRanges]. Line boundaries are derived by scanning [hostFile]'s
     * raw text: line start = char after the preceding `\n` (or 0); line end =
     * position of the next `\n` (or end of text). This widens the overlap check
     * from the exact highlight range to the enclosing line so that tokens
     * adjacent to (but outside) a `{…}` tag are still caught.
     */
    private fun lineOverlapsTemplateRange(
        hostFile: PsiFile,
        hostStart: Int,
        hostEnd: Int,
        templateRanges: List<TextRange>,
    ): Boolean {
        val text = hostFile.text
        val n = text.length
        val clampedStart = hostStart.coerceIn(0, n)
        val clampedEnd = hostEnd.coerceIn(0, n)

        // Scan back to find the start of the line containing hostStart.
        var lineStart = clampedStart
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--

        // Scan forward to find the end of the line containing hostEnd.
        var lineEnd = clampedEnd
        while (lineEnd < n && text[lineEnd] != '\n') lineEnd++

        return SkyTemplateRanges.anyOverlap(templateRanges, lineStart, lineEnd)
    }

    private fun templateRangesCached(file: PsiFile): List<TextRange> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                SkyTemplateRanges.computeTemplateRanges(file.text),
                file,
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    private fun duplicateSuppressionRangesCached(file: PsiFile): List<TextRange> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                SkyTemplateRanges.computeDuplicateSuppressionRanges(file.text),
                file,
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

    private fun commentRangesCached(file: PsiFile): List<TextRange> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                SkyTemplateRanges.computeCommentRanges(file.text),
                file,
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }
}
