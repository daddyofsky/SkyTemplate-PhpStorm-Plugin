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
 * Reports loop-scope variables (`{.var}`, `{..var}`, `{.var@N}`,
 * `{_index}`, `{_index@N}`, etc.) whose required parent-loop depth
 * exceeds the actual loop nesting at that position.
 *
 * Mirrors the SkyTemplate compiler's `parseLoopVar` / `parseReservedVar`
 * formula: an `up` of `max(@N, leading_dots − 1)` requires at least
 * `up + 1` enclosing loop frames. The compiler clamps to 0 on shortfall,
 * which yields `$v0[..]` / `$i0` reads — undefined data at runtime.
 *
 * Bundles two related codes ([Code.LOOP_DEPTH_TOO_DEEP] and
 * [Code.RESERVED_OUTSIDE_LOOP]) so the user can disable both together
 * (they share the same root cause: variable assumes a deeper loop than
 * actually exists).
 *
 * Activates in `*.sky` / `*.skyhtml` files. The HTML / XML host coverage
 * is handled by [SkyTemplateScopeAnnotator].
 */
class SkyTemplateLoopScopeInspection : LocalInspectionTool() {

    override fun getShortName(): String = SHORT_NAME

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (file.fileType !== SkyTemplateFileType) return null
        if (!TemplateLangFileFilter.shouldProcess(file)) return null

        val text = file.viewProvider.contents
        val issues = SkyTemplateScopeAnalyzer.analyze(text)
            .filter { it.code == Code.LOOP_DEPTH_TOO_DEEP || it.code == Code.RESERVED_OUTSIDE_LOOP }
        if (issues.isEmpty()) return null

        return issues.map { issue ->
            manager.createProblemDescriptor(
                file,
                issue.range,
                issue.message,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly,
            )
        }.toTypedArray()
    }

    companion object {
        const val SHORT_NAME = "SkyTemplateLoopScopeMismatch"
    }
}
