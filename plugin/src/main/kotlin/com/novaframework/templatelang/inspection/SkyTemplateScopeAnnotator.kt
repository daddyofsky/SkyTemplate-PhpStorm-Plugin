package com.novaframework.templatelang.inspection

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.inspection.SkyTemplateScopeAnalyzer.Code
import com.novaframework.templatelang.inspection.SkyTemplateScopeAnalyzer.Severity
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * HTML / XML host coverage for the four scope/var diagnostics that the
 * three M7+ inspections cover in `*.sky` / `*.skyhtml` files
 * ([SkyTemplateLoopScopeInspection], [SkyTemplateRedundantAtInspection],
 * [SkyTemplateDuplicateElseInspection]).
 *
 * We use an annotator instead of LocalInspection in HTML / XML host context
 * for the same reason as [SkyTemplateStructuralAnnotator] — the platform's
 * inspection-dispatch path doesn't reliably fire LocalInspection on the
 * host file when SkyTemplate constructs are embedded in plain HTML / XML
 * source. Annotators run unconditionally on every PSI element, so we gate
 * to a single pass per file via `element is PsiFile`.
 */
class SkyTemplateScopeAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        if (!TemplateLangFileFilter.shouldProcess(element)) return

        val text = element.viewProvider.contents
        if (text.isEmpty() || '{' !in text) return

        val issues = SkyTemplateScopeAnalyzer.analyze(text)
        if (issues.isEmpty()) return

        for (issue in issues) {
            holder.newAnnotation(toHighlightSeverity(issue.severity), issue.message)
                .range(issue.range)
                .create()
        }
    }

    private fun toHighlightSeverity(s: Severity): HighlightSeverity = when (s) {
        Severity.ERROR -> HighlightSeverity.ERROR
        Severity.WARNING -> HighlightSeverity.WARNING
        Severity.WEAK_WARNING -> HighlightSeverity.WEAK_WARNING
    }
}
