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
 * HTML / XML host coverage for [SkyTemplateUndefinedSymbolAnalyzer]. Mirrors
 * the M7 pattern: a single LocalInspection for SkyTemplate-language files
 * gives users profile-level control, while host-language coverage rides on
 * an Annotator so it doesn't appear as a duplicate entry in Settings →
 * Editor → Inspections.
 *
 * The analyzer is file-level, so the annotator runs the analysis only when
 * visiting the [PsiFile] root and skips every other element. This avoids
 * O(elements × refs) duplication — the analyzer's reference scan is itself
 * `CachedValuesManager`-cached, so a second hit is cheap, but invoking it
 * thousands of times per highlight pass would still be wasteful.
 */
class SkyTemplateUndefinedSymbolAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        // `*.sky` multi-tree guard — see SkyTemplateStructuralAnnotator. The
        // SkyTemplate-language LocalInspection already covers the base tree;
        // without this, this HTML-registered annotator double-fires on the
        // `*.sky` file's HTML data root.
        if (element.viewProvider.baseLanguage === SkyTemplateLanguage && element.language === HTMLLanguage.INSTANCE) return
        if (!TemplateLangFileFilter.shouldProcess(element)) return

        for (diag in SkyTemplateUndefinedSymbolAnalyzer.analyze(element)) {
            holder.newAnnotation(HighlightSeverity.WARNING, diag.message)
                .range(diag.range)
                .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
                .create()
        }
    }
}
