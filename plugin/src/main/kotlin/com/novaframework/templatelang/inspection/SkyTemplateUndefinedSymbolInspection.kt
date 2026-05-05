package com.novaframework.templatelang.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Reports SkyTemplate constructs whose PHP-symbol reference fails to
 * resolve. Wraps [SkyTemplateUndefinedSymbolAnalyzer] for the inspection
 * profile so users can disable / re-enable the check per profile.
 *
 * Registered for `language="SkyTemplate"` only — the HTML host coverage
 * runs through [com.novaframework.templatelang.inspection.SkyTemplateUndefinedSymbolAnnotator]
 * instead, mirroring the M7 split between SkyTemplate-language inspections
 * (profile entry) and HTML annotators (no profile entry, master toggle
 * gates).
 */
class SkyTemplateUndefinedSymbolInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!TemplateLangFileFilter.shouldProcess(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                for (diag in SkyTemplateUndefinedSymbolAnalyzer.analyze(file)) {
                    holder.registerProblem(
                        file,
                        diag.message,
                        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
                        diag.range,
                    )
                }
            }
        }
    }
}
