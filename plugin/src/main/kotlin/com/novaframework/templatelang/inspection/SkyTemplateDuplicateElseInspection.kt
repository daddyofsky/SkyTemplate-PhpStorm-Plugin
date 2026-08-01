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
 * Flags any branch tag (`{:}`, `{:expr}`, `{else}`, `{elseif …}`) that
 * sits AFTER a bare `{:}` / `{else}` in the same `{?…}` / `{if …}`
 * block — including a second bare else or an `elseif` after else.
 *
 * The SkyTemplate compiler keeps emitting code for the second branch
 * regardless of order, so the resulting PHP looks like
 * `} else { … } else { … }` which is a PHP syntax error. The runtime
 * blow-up only surfaces when the file is actually rendered, so flagging
 * at the template level catches the mistake earlier.
 *
 * The same rule applies after a `{:}` on a `{loop}` / `{foreach}` /
 * `{for}` / `{while}` block (the compiler's `tagElse` branches into the
 * loop's empty case, which can only happen once).
 */
class SkyTemplateDuplicateElseInspection : LocalInspectionTool() {

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
            .filter { it.code == Code.DUPLICATE_ELSE }
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
        const val SHORT_NAME = "SkyTemplateDuplicateElse"
    }
}
