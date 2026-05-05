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
 * Reports SkyTemplate block tags (`{loop …}`, `{if …}`, `{foreach …}`,
 * `{?expr}`, `{?:expr}` (elvis), `{@…}`, `{%…}`, `{each …}`, `{for …}`,
 * `{while …}`) that are opened but never closed — i.e. no matching `{/}`
 * or `{end}` is seen before end-of-file.
 *
 * **Scope**: registered for the SkyTemplate, HTML, and XML languages so
 * the inspection runs in `*.sky` / `*.skyhtml` files, in plain HTML / XML
 * host files where SkyTemplate directives are embedded, and in any
 * multi-tree file that mixes them. Orphan close tags (`{/}` / `{end}`
 * without an opener) remain intentionally silent — partial-template
 * fragments where the opener lives in another included file are
 * legitimate and should not be flagged.
 *
 * Severity: ERROR (configurable via the standard inspection profile UI).
 *
 * Honours `TemplateLangSettings.isEnabled` — disabling the master switch
 * also silences this inspection. The pairing logic is shared with
 * [SkyTemplateFoldingScanner.analyze] so fold rendering and the inspection
 * stay in lockstep.
 */
class SkyTemplateUnclosedBlockInspection : LocalInspectionTool() {

    override fun getShortName(): String = SHORT_NAME

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor>? {
        if (!isApplicable(file)) return null
        val text = file.viewProvider.contents
        val result = SkyTemplateFoldingScanner.analyze(text)
        if (result.unpairedOpens.isEmpty()) return null
        return result.unpairedOpens.map { open ->
            val message = buildMessage(open.openText, open.openLine, open.suggestedClose)
            manager.createProblemDescriptor(
                file,
                open.range,
                message,
                ProblemHighlightType.GENERIC_ERROR,
                isOnTheFly,
            )
        }.toTypedArray()
    }

    private fun isApplicable(file: PsiFile): Boolean {
        if (!TemplateLangFileFilter.shouldProcess(file)) return false
        // Run for any file whose VFS file type is SkyTemplate (covers
        // `*.sky` / `*.skyhtml`) or whose primary language is HTML / XML
        // (covers host files where SkyTemplate constructs are embedded).
        if (file.fileType === SkyTemplateFileType) return true
        val lang = file.language
        return lang === com.intellij.lang.html.HTMLLanguage.INSTANCE ||
            lang === com.intellij.lang.xml.XMLLanguage.INSTANCE
    }

    private fun buildMessage(openText: String, openLine: Int, suggestedClose: Int): String {
        val trimmed = openText.replace(Regex("\\s+"), " ").trim()
        val display = if (trimmed.length > 60) trimmed.take(57) + "…" else trimmed
        val base = "Unclosed `$display` block — missing `{/}` or `{end}`"
        return if (suggestedClose > openLine) {
            "$base (likely close near line $suggestedClose, based on indent)"
        } else {
            base
        }
    }

    companion object {
        const val SHORT_NAME = "SkyTemplateUnclosedBlock"
    }
}
