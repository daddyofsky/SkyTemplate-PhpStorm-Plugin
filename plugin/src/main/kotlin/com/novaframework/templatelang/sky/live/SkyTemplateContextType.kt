package com.novaframework.templatelang.sky.live

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlTag
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateFileType

/**
 * Live-template context for SkyTemplate snippets (`loop`, `if`, `foreach`,
 * `incl`, `cmt`, …).
 *
 * **Active when**:
 *   - the file is a SkyTemplate file (`*.sky` / `*.skyhtml`), and the caret
 *     is NOT inside a `{* … *}` comment, OR
 *   - the file is HTML / XHTML / XML and the caret is in the document body —
 *     specifically NOT inside `<script>`, NOT inside `<style>`, and NOT
 *     inside a `{* … *}` template comment.
 *
 * Inside `<script>` / `<style>` injections the user wants JS / CSS snippets,
 * not template snippets, so we step out of the way. Inside `{*…*}` they're
 * typing a comment and a snippet expansion would be a surprise.
 *
 * The `contextId` (`SKY_TEMPLATE`) is referenced from
 * `liveTemplates/SkyTemplate.xml` via `<context><option name="SKY_TEMPLATE" value="true"/></context>`.
 */
class SkyTemplateContextType : TemplateContextType("SkyTemplate") {

    override fun isInContext(context: TemplateActionContext): Boolean {
        val file = context.file
        val offset = context.startOffset

        // 0. Master settings + file-extension whitelist gate — when SkyTemplate
        //    support is disabled or the file's extension is not whitelisted,
        //    no plugin-provided snippets should appear (consistency with
        //    annotator / reference / completion contributors).
        if (!TemplateLangFileFilter.shouldProcess(file)) return false

        // 1. SkyTemplate file type wins — caret can be anywhere except inside
        //    a `{*…*}` comment.
        if (file.fileType === SkyTemplateFileType) {
            return !isInsideTemplateComment(file, offset)
        }

        // 2. HTML / XML host. Only allow in the document body.
        val langId = file.language.id
        if (langId != "HTML" && langId != "XHTML" && langId != "XML") return false
        if (isInsideScriptOrStyle(file, offset)) return false
        if (isInsideTemplateComment(file, offset)) return false
        return true
    }

    private fun isInsideTemplateComment(file: PsiFile, offset: Int): Boolean =
        SkyTemplateLiveContextLogic.isInsideTemplateComment(file.viewProvider.contents, offset)

    /**
     * Walk PSI ancestors from the element at [offset]. If we hit an XmlTag
     * named `script` / `style`, the caret is inside an injected JS / CSS
     * region (or its raw text) — disable the SkyTemplate snippets so the
     * user keeps the JS / CSS toolset.
     */
    private fun isInsideScriptOrStyle(file: PsiFile, offset: Int): Boolean {
        val element: PsiElement = file.findElementAt(offset)
            ?: file.findElementAt((offset - 1).coerceAtLeast(0))
            ?: return false
        var n: PsiElement? = element
        while (n != null) {
            if (n is XmlTag) {
                val tagName = n.name.lowercase()
                if (tagName == "script" || tagName == "style") return true
            }
            n = n.parent
        }
        return false
    }
}
