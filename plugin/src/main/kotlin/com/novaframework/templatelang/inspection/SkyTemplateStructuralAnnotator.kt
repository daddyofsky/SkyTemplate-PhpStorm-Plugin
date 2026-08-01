package com.novaframework.templatelang.inspection

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateFoldingScanner
import com.novaframework.templatelang.sky.SkyTemplateLanguage
import com.novaframework.templatelang.sky.SkyTemplateRangeCache

/**
 * Reports the same structural diagnostics as the M7 inspections but for
 * HTML / XML host files where SkyTemplate directives are embedded as
 * plain text. We use an [Annotator] (not a [com.intellij.codeInspection.LocalInspectionTool])
 * because the platform's per-language inspection pipeline declines to
 * dispatch our LocalInspection class to HTML / XML host files even when
 * registered with `language="HTML"` — the annotator pipeline picks
 * everything up reliably.
 *
 * Diagnostics produced (mirroring the SkyTemplate-only inspections):
 *   - **Unclosed block** — `{loop …}`, `{if …}`, `{?:expr}` (elvis), … without
 *     a matching `{/}` / `{end}` before end-of-file. Includes an indent
 *     hint pointing at where the closer most naturally fits.
 *   - **Orphan branch** — `{else}` / `{elseif …}` / `{:}` / `{:expr}`
 *     outside any open block.
 *   - Orphan close (`{/}` with no opener) is intentionally NOT reported —
 *     partial-template fragments where the opener lives in another
 *     included file are legitimate.
 *   - **Missing loop name** — `{loop}` / `{each}` / `{@}` / `{%}` with no
 *     argument. The compiler throws `RuntimeException('Loop tag name is
 *     missing')` for this exact shape (`SkyTemplateCompiler::tagLoop`,
 *     `!$arg`) — `foreach` / `for` / `while` do NOT have this restriction
 *     (missing arg there just produces a PHP-level warning/error, not a
 *     compiler-level throw), so only the `loop`-family keyword/prefix forms
 *     are checked here.
 *
 * Honours the master `Enable SkyTemplate support` toggle.
 *
 * Annotation model: the annotator is invoked on every PSI element, so we
 * gate to fire only once per file (when [PsiElement] is the file root).
 */
class SkyTemplateStructuralAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        // `*.sky` files are multi-tree (SkyTemplate base + HTML data root).
        // The SkyTemplate-language LocalInspection equivalents already cover
        // the base tree; without this guard this HTML-registered annotator
        // fires a SECOND time on the data root, duplicating every diagnostic.
        // Genuine `*.html` / `*.xml` hosts have no SkyTemplate base tree, so
        // baseLanguage there is HTML/XML and this guard is a no-op for them.
        if (element.viewProvider.baseLanguage === SkyTemplateLanguage && element.language === HTMLLanguage.INSTANCE) return
        if (!TemplateLangFileFilter.shouldProcess(element)) return

        val text = element.viewProvider.contents
        if (text.isEmpty() || '{' !in text) return

        val result = SkyTemplateRangeCache.getBlockPairing(text)
        for (open in result.unpairedOpens) {
            val message = buildUnclosedMessage(open.openText, open.openLine, open.suggestedClose)
            holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(open.range)
                .create()
        }
        for (orphan in result.orphanBranches) {
            val message = "`{${orphan.keyword}}` outside `{if}` / `{loop}` block"
            holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(orphan.range)
                .create()
        }
        for (range in findMissingLoopNameRanges(text)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "Loop tag name is missing")
                .range(range)
                .create()
        }
    }

    private fun buildUnclosedMessage(openText: String, openLine: Int, suggestedClose: Int): String {
        val trimmed = openText.replace(Regex("\\s+"), " ").trim()
        val display = if (trimmed.length > 60) trimmed.take(57) + "…" else trimmed
        val base = "Unclosed `$display` block — missing `{/}` or `{end}`"
        return if (suggestedClose > openLine) {
            "$base (likely close near line $suggestedClose, based on indent)"
        } else {
            base
        }
    }

    private val LOOP_KEYWORDS = setOf("loop", "each")

    /**
     * Scan every `{ … }` tag for the `loop`-family shapes
     * (`{loop}` / `{each}` / `{@}` / `{%}`) with a blank argument — the
     * compiler throws for exactly this case. Mirrors
     * `SkyTemplateRanges.classifyBlockKind`'s prefix/keyword recognition
     * (leading-whitespace / word-boundary rules) but only for the subset
     * that actually maps to `tagLoop`.
     */
    private fun findMissingLoopNameRanges(text: CharSequence): List<TextRange> {
        val out = ArrayList<TextRange>()
        for (range in SkyTemplateRangeCache.get(text)) {
            val open = range.startOffset
            val close = range.endOffset
            var tagOpen = -1
            for (i in open until close) {
                if (text[i] == '{') { tagOpen = i; break }
            }
            var tagClose = -1
            for (i in close - 1 downTo open) {
                if (text[i] == '}') { tagClose = i; break }
            }
            if (tagOpen < 0 || tagClose < 0 || tagOpen >= tagClose) continue
            val bodyStart = tagOpen + 1
            val bodyEnd = tagClose
            if (bodyEnd <= bodyStart) continue
            var i = bodyStart
            while (i < bodyEnd && (text[i] == ' ' || text[i] == '\t')) i++
            if (i >= bodyEnd) continue
            val first = text[i]
            val hasLeadingWs = i > bodyStart
            val argStart: Int
            if (first == '@' || first == '%') {
                argStart = i + 1
            } else if (!hasLeadingWs && (first.isLetter() || first == '_')) {
                var j = i + 1
                while (j < bodyEnd && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.subSequence(i, j).toString().lowercase()
                val followedByBoundary = j >= bodyEnd || !(text[j].isLetterOrDigit() || text[j] == '_')
                if (!followedByBoundary || word !in LOOP_KEYWORDS) continue
                argStart = j
            } else {
                continue
            }
            var s = argStart
            while (s < bodyEnd && text[s].isWhitespace()) s++
            var e = bodyEnd
            while (e > s && text[e - 1].isWhitespace()) e--
            if (s >= e) {
                out += TextRange(tagOpen, tagClose + 1)
            }
        }
        return out
    }
}
