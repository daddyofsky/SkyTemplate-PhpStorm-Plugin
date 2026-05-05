package com.novaframework.templatelang.sky

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object SkyTemplateFileType : LanguageFileType(SkyTemplateLanguage) {
    override fun getName(): String = "SkyTemplate"
    override fun getDescription(): String = "SkyTemplate template file"
    override fun getDefaultExtension(): String = "sky"
    override fun getIcon(): Icon? = null
}
