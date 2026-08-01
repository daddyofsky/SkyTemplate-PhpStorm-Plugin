package com.novaframework.templatelang.inspection

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.html.HTMLLanguage
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateLanguage

/**
 * HTML / XML host coverage for [SkyTemplateCallArguments]. Mirrors the M7
 * pattern (see [SkyTemplateUndefinedSymbolAnnotator]): a single annotator
 * surfaces every Phase 3 diagnostic in `*.html` host files at the right
 * severity, while the two LocalInspections cover `*.sky` and provide
 * profile-level toggles in *Settings → Editor → Inspections → SkyTemplate*.
 *
 * Severity mapping (matches the inspection wrappers):
 *   - rule a, b, c → WARNING
 *   - rule d, e    → ERROR
 *
 * The analyzer itself owns the master-toggle / dumb-mode / file-type guards,
 * so the annotator is a thin shim that runs once per file (skipping every
 * other element).
 */
class SkyTemplateArgumentAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        // `*.sky` multi-tree guard — see SkyTemplateStructuralAnnotator. The
        // two LocalInspections already cover the base tree; without this,
        // this HTML-registered annotator double-fires on the `*.sky` file's
        // HTML data root.
        if (element.viewProvider.baseLanguage === SkyTemplateLanguage && element.language === HTMLLanguage.INSTANCE) return
        if (!TemplateLangFileFilter.shouldProcess(element)) return

        for (diag in SkyTemplateCallArguments.analyze(element)) {
            val (severity, highlight) = when (diag.rule) {
                SkyTemplateCallArguments.Rule.A_REQUIRED_MISSING,
                SkyTemplateCallArguments.Rule.B_TOO_MANY,
                SkyTemplateCallArguments.Rule.C_UNKNOWN_NAMED ->
                    HighlightSeverity.WARNING to ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                SkyTemplateCallArguments.Rule.D_DUPLICATE_NAMED,
                SkyTemplateCallArguments.Rule.E_POSITIONAL_AFTER_NAMED ->
                    HighlightSeverity.ERROR to ProblemHighlightType.GENERIC_ERROR
            }
            holder.newAnnotation(severity, diag.message)
                .range(diag.range)
                .highlightType(highlight)
                .create()
        }
    }
}
