package com.novaframework.templatelang.sky

import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Smart-indent provider: answers the platform's "what indent should this
 * line carry?" query (`CodeStyleManager.getLineIndent(editor, …)`) with
 * the same relative HTML+Sky computation the Enter handler and the
 * post-format processor use.
 *
 * **Why this exists.** The Enter / paste handlers own their specific
 * entry points, but the platform computes line indent through this EP on
 * several other paths — the host EnterHandler's own auto-indent, Tab at
 * line start, and editor smart-indent actions. Those consult the host
 * (HTML / XML) formatter, which is Sky-blind, so a line inside
 * `{loop …} … {/}` falls back to the host depth. This provider answers
 * first with the SkyTemplate-aware indent — relative to the nearest
 * enclosing HTML or template opener at its actual indent — and returns
 * null outside Sky context so the host behaviour is untouched.
 *
 * Scope: SkyTemplate / HTML / XML languages, gated by
 * [TemplateLangFileFilter] (master enable switch + extension whitelist).
 */
class SkyTemplateLineIndentProvider : LineIndentProvider {

    override fun getLineIndent(
        project: Project,
        editor: Editor,
        language: Language?,
        offset: Int,
    ): String? {
        val document = editor.document
        if (offset < 0 || offset > document.textLength) return null
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
        if (!TemplateLangFileFilter.shouldProcess(psi)) return null

        val text = document.charsSequence
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--

        val indentStep = resolveIndentStep(psi)
        // Null (top level, no enclosing opener) falls through to the
        // platform's formatter-based indent — same contract as the rest
        // of the EP chain.
        val base = SkyTemplatePostFormatLogic.computeIndentForLine(text, lineStart, indentStep)
            ?: return null
        // A line that itself closes with `}` is counted by embeddedBraceDepth
        // as still inside the brace it closes — compensate by one level so
        // the closer aligns with the opener instead of its body.
        val rawDepth = SkyTemplateIndentContext.embeddedBraceDepth(text, lineStart)
        val braceDepth = if (SkyTemplateIndentContext.startsWithCloseBrace(text, lineStart)) {
            (rawDepth - 1).coerceAtLeast(0)
        } else {
            rawDepth
        }
        return if (braceDepth > 0) base + indentStep.repeat(braceDepth) else base
    }

    override fun isSuitableFor(language: Language?): Boolean =
        language === SkyTemplateLanguage ||
            language === com.intellij.lang.html.HTMLLanguage.INSTANCE ||
            language === com.intellij.lang.xml.XMLLanguage.INSTANCE

    private fun resolveIndentStep(file: com.intellij.psi.PsiFile): String {
        return try {
            val opts = CodeStyle.getIndentOptions(file)
            if (opts.USE_TAB_CHARACTER) "\t" else " ".repeat(opts.INDENT_SIZE.coerceAtLeast(1))
        } catch (_: Throwable) {
            "    "
        }
    }
}
