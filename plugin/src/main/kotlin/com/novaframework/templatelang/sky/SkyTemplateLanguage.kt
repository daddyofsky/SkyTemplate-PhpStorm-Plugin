package com.novaframework.templatelang.sky

import com.intellij.lang.Language
import com.intellij.psi.templateLanguages.TemplateLanguage

/**
 * SkyTemplate is a [TemplateLanguage]: its files are layered on top of a data
 * language (HTML by default) so HTML/JS/CSS support stays intact in the
 * non-template parts of the file.
 */
object SkyTemplateLanguage : Language("SkyTemplate"), TemplateLanguage {
    override fun getDisplayName(): String = "SkyTemplate"
    override fun isCaseSensitive(): Boolean = true
    private fun readResolve(): Any = SkyTemplateLanguage
}
