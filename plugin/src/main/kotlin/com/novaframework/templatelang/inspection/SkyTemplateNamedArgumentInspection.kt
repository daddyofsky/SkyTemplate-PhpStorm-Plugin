package com.novaframework.templatelang.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Reports SkyTemplate call sites whose **named arguments** violate one of:
 *
 *   - rule c: name not declared by the callee   (WARNING)
 *   - rule d: same name passed twice            (ERROR)
 *   - rule e: positional argument after a named (ERROR)
 *
 * Rule d / e are runtime-fatal in PHP 8 (`Error: Cannot use positional argument
 * after named argument`, `Error: Named parameter $x overwrites previous argument`),
 * so they are reported as ERROR even though the localInspection's profile
 * default level is WARNING — the visitor pins the highlight type per rule.
 * The profile default lets users dial the severity down in
 * `Settings → Editor → Inspections → SkyTemplate` if their workflow needs it.
 *
 * Rule c stays WARNING because (a) a callee resolved through a poly-variant
 * candidate set may legitimately accept names known to one variant only, and
 * (b) PHP variadic (`...$rest`) functions accept any name at runtime — the
 * analyzer already passes those, but a mis-stubbed signature shouldn't go
 * red.
 *
 * Pipe-form named arguments (`{var|fn=name=value}`) are recognised the same
 * way paren-form (`{=foo(name: 1)}`) is.
 *
 * Registered for `language="SkyTemplate"` only. HTML host coverage flows
 * through the same analyzer because [SkyTemplateCallArguments.analyze] also
 * accepts `XmlFile`.
 */
class SkyTemplateNamedArgumentInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!TemplateLangFileFilter.shouldProcess(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                for (diag in SkyTemplateCallArguments.analyze(file)) {
                    val highlightType = when (diag.rule) {
                        SkyTemplateCallArguments.Rule.C_UNKNOWN_NAMED ->
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                        SkyTemplateCallArguments.Rule.D_DUPLICATE_NAMED,
                        SkyTemplateCallArguments.Rule.E_POSITIONAL_AFTER_NAMED ->
                            ProblemHighlightType.GENERIC_ERROR
                        else -> continue   // rules a / b owned by the count inspection
                    }
                    holder.registerProblem(
                        file,
                        diag.message,
                        highlightType,
                        diag.range,
                    )
                }
            }
        }
    }
}
