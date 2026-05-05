package com.novaframework.templatelang.inspection

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateFoldingScanner

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
 *
 * Honours the master `Enable SkyTemplate support` toggle.
 *
 * Annotation model: the annotator is invoked on every PSI element, so we
 * gate to fire only once per file (when [PsiElement] is the file root).
 */
class SkyTemplateStructuralAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        if (!TemplateLangFileFilter.shouldProcess(element)) return

        val text = element.viewProvider.contents
        if (text.isEmpty() || '{' !in text) return

        val result = SkyTemplateFoldingScanner.analyze(text)
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
}
