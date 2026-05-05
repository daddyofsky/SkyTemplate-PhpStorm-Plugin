package com.novaframework.templatelang.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Reports SkyTemplate call sites that pass too few or too many positional
 * arguments to a resolved PHP function / method.
 *
 *   - rule a: required parameter missing  (`foo()` ← `foo(string $a)`)
 *   - rule b: too many arguments          (`foo(1,2,3)` ← `foo($a,$b)`)
 *
 * Both rules are WARNING severity — PHP still raises a fatal at runtime, but
 * runtime-registered functions / autoloaders can lift the static signature so
 * we prefer warning over error to mirror [SkyTemplateUndefinedSymbolInspection]
 * tone.
 *
 * Variadic functions (`...$args`) are exempt from rule b. Default-value
 * parameters are exempt from rule a's required count.
 *
 * Type checks are intentionally **out of scope** — only count is validated.
 *
 * Registered for `language="SkyTemplate"` only. HTML host coverage flows
 * through the same analyzer because [SkyTemplateCallArguments.analyze] also
 * accepts `XmlFile`. If profile-based HTML host coverage gaps surface, add an
 * annotator companion mirroring [SkyTemplateUndefinedSymbolAnnotator].
 */
class SkyTemplateArgumentCountInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!TemplateLangFileFilter.shouldProcess(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                for (diag in SkyTemplateCallArguments.analyze(file)) {
                    if (diag.rule != SkyTemplateCallArguments.Rule.A_REQUIRED_MISSING &&
                        diag.rule != SkyTemplateCallArguments.Rule.B_TOO_MANY) continue
                    holder.registerProblem(
                        file,
                        diag.message,
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        diag.range,
                    )
                }
            }
        }
    }
}
