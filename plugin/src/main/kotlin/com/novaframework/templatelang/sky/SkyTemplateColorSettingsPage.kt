package com.novaframework.templatelang.sky

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class SkyTemplateColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon? = null
    override fun getHighlighter(): SyntaxHighlighter = SkyTemplateSyntaxHighlighter()

    override fun getDemoText(): String = """
        {* Tag-prefix shortcut form (single-char prefix after `{`) *}
        {?user.isAdmin}admin{:}guest{/}            {* if / else / end *}
        {@products}<li>{.name}</li>{/}             {* loop alias *}
        {=rawHtml}                                 {* raw output *}
        {#"common/header.html"}                    {* include *}
        {+"plugin.php"}                            {* execute *}
        {]"raw_dump.txt"}                          {* dump *}
        {&childBlock}                              {* refer *}
        {?:fallbackValue}                          {* elvis *}
        {;x = 1}                                   {* php assign *}

        {* Keyword form *}
        {if user.isAdmin}
          <p>Welcome, {user.displayName|trim}.</p>
        {else}
          <p>Login required.</p>
        {/}
        {foreach items as item}{item.name}{/}

        {* Variables, scopes & filters *}
        <h1>Hello, {name}!</h1>
        {loop products}
          <li class="item-{_index}">
            {.name} — {.price#5} won               {* current loop var + zerofill *}
            {..parentName}                         {* parent loop scope *}
            {.detail@2}                            {* grand-parent via @N modifier *}
            <a href="{c.BASE_URL}/item/{.id}">link</a>
          </li>
        {/}
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Comment", SkyTemplateColors.COMMENT),
        AttributesDescriptor("Braces", SkyTemplateColors.BRACES),
        AttributesDescriptor("Tag prefix (? : / @ = # +)", SkyTemplateColors.TAG_PREFIX),
        AttributesDescriptor("Tag keyword (loop, if, else…)", SkyTemplateColors.TAG_KEYWORD),
        AttributesDescriptor("Reserved scope (_index, _GET…)", SkyTemplateColors.SCOPE_RESERVED),
        AttributesDescriptor("Loop scope (.var, ..parent)", SkyTemplateColors.SCOPE_LOOP),
        AttributesDescriptor("Constant scope (c.NAME)", SkyTemplateColors.SCOPE_CONST),
        AttributesDescriptor("Identifier", SkyTemplateColors.IDENTIFIER),
        AttributesDescriptor("Operator", SkyTemplateColors.OPERATOR),
        AttributesDescriptor("Pipe", SkyTemplateColors.PIPE),
        AttributesDescriptor("String", SkyTemplateColors.STRING),
        AttributesDescriptor("Number", SkyTemplateColors.NUMBER),
        AttributesDescriptor("Dot", SkyTemplateColors.DOT),
        AttributesDescriptor("Comma", SkyTemplateColors.COMMA),
        AttributesDescriptor("Parentheses", SkyTemplateColors.PARENS),
        AttributesDescriptor("Brackets", SkyTemplateColors.BRACKETS),
        AttributesDescriptor("Bad character", SkyTemplateColors.BAD_CHARACTER),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "SkyTemplate"
}
