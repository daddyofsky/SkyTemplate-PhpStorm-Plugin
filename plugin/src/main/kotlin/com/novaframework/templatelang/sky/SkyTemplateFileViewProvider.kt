package com.novaframework.templatelang.sky

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.MultiplePsiFilesPerDocumentFileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.templateLanguages.ConfigurableTemplateLanguageFileViewProvider
import com.intellij.psi.templateLanguages.TemplateDataLanguageMappings

/**
 * Multi-tree view provider for SkyTemplate files.
 *
 * Two PSI trees backed by the same document:
 *   1. SkyTemplate base tree — produced by SkyTemplate parser/lexer.
 *   2. Data tree — HTML by default, overridable per-file via
 *      Settings → Languages & Frameworks → Template Data Languages.
 *
 * The data tree's content element type is [SkyTemplateElementTypes.OUTER_ELEMENT_TYPE],
 * which uses the SkyTemplate lexer to slice the source so only OUTER content is
 * fed to the HTML parser; template ranges become opaque [SKY_FRAGMENT] leaves.
 */
class SkyTemplateFileViewProvider(
    manager: PsiManager,
    file: VirtualFile,
    eventSystemEnabled: Boolean,
) : MultiplePsiFilesPerDocumentFileViewProvider(manager, file, eventSystemEnabled),
    ConfigurableTemplateLanguageFileViewProvider {

    override fun getBaseLanguage(): Language = SkyTemplateLanguage

    override fun getTemplateDataLanguage(): Language =
        TemplateDataLanguageMappings.getInstance(manager.project).getMapping(virtualFile)
            ?: HTMLLanguage.INSTANCE

    override fun getLanguages(): Set<Language> =
        setOf(SkyTemplateLanguage, templateDataLanguage)

    override fun createFile(lang: Language): PsiFile? {
        val parserDef = LanguageParserDefinitions.INSTANCE.forLanguage(lang) ?: return null
        return when {
            lang === SkyTemplateLanguage -> parserDef.createFile(this)
            lang === templateDataLanguage -> {
                val psi = parserDef.createFile(this)
                if (psi is PsiFileImpl) {
                    psi.contentElementType = SkyTemplateElementTypes.OUTER_ELEMENT_TYPE
                }
                psi
            }
            else -> null
        }
    }

    override fun cloneInner(file: VirtualFile): MultiplePsiFilesPerDocumentFileViewProvider =
        SkyTemplateFileViewProvider(manager, file, false)

    override fun supportsIncrementalReparse(rootLanguage: Language): Boolean = false
}
