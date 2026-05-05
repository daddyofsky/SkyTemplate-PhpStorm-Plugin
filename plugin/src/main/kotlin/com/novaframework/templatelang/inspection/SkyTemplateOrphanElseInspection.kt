package com.novaframework.templatelang.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateFileType
import com.novaframework.templatelang.sky.SkyTemplateFoldingScanner

/**
 * Reports SkyTemplate branch tags — `{else}`, `{elseif …}`, `{:}`,
 * `{:expr}` — that appear outside any enclosing block. The SkyTemplate
 * compiler cannot place such a branch and would fail.
 *
 * **Scope**: registered for the SkyTemplate, HTML, and XML languages so
 * the inspection runs in `*.sky` / `*.skyhtml` files as well as in HTML
 * and XML hosts where SkyTemplate directives are embedded.
 *
 * Severity: ERROR.
 *
 * Honours `TemplateLangSettings.isEnabled`.
 */
class SkyTemplateOrphanElseInspection : LocalInspectionTool() {

    override fun getShortName(): String = SHORT_NAME

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (!isApplicable(file)) return null
        val text = file.viewProvider.contents
        val result = SkyTemplateFoldingScanner.analyze(text)
        if (result.orphanBranches.isEmpty()) return null
        return result.orphanBranches.map { orphan ->
            val message = "`{${orphan.keyword}}` outside `{if}` / `{loop}` block"
            manager.createProblemDescriptor(
                file,
                orphan.range,
                message,
                ProblemHighlightType.GENERIC_ERROR,
                isOnTheFly,
            )
        }.toTypedArray()
    }

    private fun isApplicable(file: PsiFile): Boolean {
        if (!TemplateLangFileFilter.shouldProcess(file)) return false
        if (file.fileType === SkyTemplateFileType) return true
        val lang = file.language
        return lang === com.intellij.lang.html.HTMLLanguage.INSTANCE ||
            lang === com.intellij.lang.xml.XMLLanguage.INSTANCE
    }

    companion object {
        const val SHORT_NAME = "SkyTemplateOrphanElse"
    }
}
