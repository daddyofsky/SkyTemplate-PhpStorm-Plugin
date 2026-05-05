package com.novaframework.templatelang.sky

import com.intellij.psi.templateLanguages.TemplateDataElementType

object SkyTemplateElementTypes {
    /**
     * Marker emitted into the data-language (HTML) PSI tree for ranges that
     * were occupied by SkyTemplate tokens. The HTML parser sees these as
     * opaque text and doesn't choke on `{` `}` etc.
     */
    @JvmField val SKY_FRAGMENT = SkyTemplateTokenType("SKY_FRAGMENT")

    /**
     * Lazy-parseable element type used as the `contentElementType` of the
     * data-language PsiFile. When the platform parses its contents, this
     * type runs the SkyTemplate lexer over the source and:
     *   - keeps OUTER_CONTENT ranges as data-language input
     *   - replaces every other range with a SKY_FRAGMENT leaf so the data
     *     parser only sees what it understands.
     */
    @JvmField val OUTER_ELEMENT_TYPE = TemplateDataElementType(
        "SKY_TEMPLATE_DATA",
        SkyTemplateLanguage,
        SkyTemplateTokenTypes.OUTER_CONTENT,
        SKY_FRAGMENT,
    )
}
