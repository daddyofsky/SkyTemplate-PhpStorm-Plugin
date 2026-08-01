package com.novaframework.templatelang.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.inspection.SkyTemplateScopeAnalyzer.Code
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateFileType

/**
 * Flags ineffective uses of the `@N` parent-loop modifier:
 *
 *   - **`@` on a non-loop, non-reserved variable** ([Code.REDUNDANT_AT_ON_NON_LOOP]):
 *     `{var@}` / `{var@5}`. The compiler's `parseNormalVar` path does not
 *     consult `var_up`, so the modifier is silently dropped. Almost always
 *     a typo of `{.var@N}` (loop scope).
 *
 *   - **`@0` on a loop or reserved variable** ([Code.REDUNDANT_AT_ZERO]):
 *     `{.var@0}` / `{_index@0}`. Both `parseLoopVar` and `parseReservedVar`
 *     compute `up = max(int(N), 1)`, so `@0` is treated identically to
 *     `@1` (one level up). Spelling it `@0` reads as "no offset" — the
 *     surface and the runtime semantics disagree.
 *
 * Note: bare `@` on a loop-scope variable (`{.var@}`) is intentional
 * shorthand for `@1` and is NOT flagged here. The user explicitly requested
 * this distinction.
 */
class SkyTemplateRedundantAtInspection : LocalInspectionTool() {

    override fun getShortName(): String = SHORT_NAME

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (file.fileType !== SkyTemplateFileType) return null
        if (!TemplateLangFileFilter.shouldProcess(file)) return null

        val text = file.viewProvider.contents
        val issues = SkyTemplateScopeAnalysisCache.get(text)
            .filter { it.code == Code.REDUNDANT_AT_ON_NON_LOOP || it.code == Code.REDUNDANT_AT_ZERO }
        if (issues.isEmpty()) return null

        return issues.map { issue ->
            val highlight = when (issue.code) {
                Code.REDUNDANT_AT_ZERO -> ProblemHighlightType.WEAK_WARNING
                else -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
            }
            manager.createProblemDescriptor(
                file,
                issue.range,
                issue.message,
                highlight,
                isOnTheFly,
            )
        }.toTypedArray()
    }

    companion object {
        const val SHORT_NAME = "SkyTemplateRedundantAt"
    }
}
