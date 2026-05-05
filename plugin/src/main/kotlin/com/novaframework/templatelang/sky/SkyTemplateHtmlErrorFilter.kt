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
 *   - INFORMATION-level inspection results too, but **only** inside comment
 *     ranges — the user's original example
 *       `<!--{*
 *           <ul><li>{title}</li><!--<li>{hit}</li>--></ul>
 *        *}-->`
 *     produces all kinds of HTML/PHP noise inside the wrap that we want
 *     completely silent.
 *
 * Our own annotator output uses INFORMATION level too, but it is paired with
 * a TextAttributesKey instead of a description / inspection id; we leave any
 * INFORMATION whose `description` is null untouched so the file-level comment
 * overlay is preserved.
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

        // Information-level highlights are usually subtle hints. We drop them
        // only inside *comment* ranges (text-of-comment semantics) and never
        // touch the no-description ones — those are typically annotator output.
        val isSuppressibleInformation = !isWeakOrStronger &&
            severity.myVal >= HighlightSeverity.INFORMATION.myVal &&
            highlightInfo.description != null

        if (!isWeakOrStronger && !isSuppressibleInformation) return true

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
        }

        if (isSuppressibleInformation) {
            val commentRanges = commentRangesCached(hostFile)
            if (commentRanges.isNotEmpty() &&
                SkyTemplateRanges.anyOverlap(commentRanges, hostStart, hostEnd)
            ) {
                return false
            }
        }

        return true
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

    private fun templateRangesCached(file: PsiFile): List<TextRange> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                SkyTemplateRanges.computeTemplateRanges(file.text),
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
