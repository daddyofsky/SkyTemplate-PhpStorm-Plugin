package com.novaframework.templatelang.sky

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.util.LayerDescriptor
import com.intellij.openapi.editor.ex.util.LayeredLexerEditorHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.templateLanguages.TemplateDataLanguageMappings
import com.intellij.lang.html.HTMLLanguage

/**
 * Layered editor highlighter:
 *   - top layer: SkyTemplate token highlighting (tags, vars, comments, …)
 *   - layer for OUTER_CONTENT tokens: data language (HTML by default)
 *
 * The data layer is what makes plain HTML tags / CSS class attribute names /
 * JavaScript inside `<script>` look right when the file does NOT contain a
 * `<html>` element.
 */
class SkyTemplateEditorHighlighter(
    project: Project?,
    virtualFile: VirtualFile?,
    colors: EditorColorsScheme,
) : LayeredLexerEditorHighlighter(SkyTemplateSyntaxHighlighter(), colors) {

    init {
        val dataLanguage = (project?.let {
            virtualFile?.let { vf -> TemplateDataLanguageMappings.getInstance(it).getMapping(vf) }
        }) ?: HTMLLanguage.INSTANCE

        val dataHighlighter = SyntaxHighlighterFactory
            .getSyntaxHighlighter(dataLanguage, project, virtualFile)
            ?: PlainSyntaxHighlighter()

        registerLayer(SkyTemplateTokenTypes.OUTER_CONTENT, LayerDescriptor(dataHighlighter, ""))
    }
}
