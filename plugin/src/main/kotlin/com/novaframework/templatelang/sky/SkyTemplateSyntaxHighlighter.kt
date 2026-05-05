package com.novaframework.templatelang.sky

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

/**
 * SkyTemplate's user-facing colour keys. Each key has a fallback to a
 * standard IntelliJ scheme attribute so themes that don't customise
 * SkyTemplate-specific keys still produce sensible visuals.
 *
 * Fallback choices (default — what the user sees out of the box):
 *   - **TAG_PREFIX / TAG_KEYWORD** — both fall back to `KEYWORD` so all
 *     SkyTemplate control markers (`?`, `@`, `#`, `=`, `:`, `/`, …) and
 *     keyword forms (`loop`, `if`, `else`, …) share the keyword colour.
 *   - **SCOPE_RESERVED** — `INSTANCE_FIELD` (visible across themes).
 *   - **SCOPE_LOOP** — `METADATA` (yellow/olive accent for `.var`,
 *     `..parent`, …).
 *   - **SCOPE_CONST** — `CONSTANT` (purple accent for `c.NAME`).
 *   - **PIPE** — `KEYWORD` (the `|` filter is a control marker — same
 *     family as tag prefixes).
 *   - **OPERATOR / DOT / COMMA / PARENS / BRACKETS / NUMBER / STRING /
 *     IDENTIFIER / BRACES** — standard IntelliJ defaults.
 */
object SkyTemplateColors {
    val COMMENT = TextAttributesKey.createTextAttributesKey(
        "SKY_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
    val BRACES = TextAttributesKey.createTextAttributesKey(
        "SKY_BRACES", DefaultLanguageHighlighterColors.BRACES)
    // Tag-start prefix chars (`?`, `:`, `@`, `%`, `=`, `/`, `;`, `#`, `+`,
    // `]`, `&`, `\`) and 2-char `?:`. Mid-tag `@N` / `#N` (var modifiers)
    // also paint with this key. Falling back to KEYWORD ensures these
    // markers share the same accent colour as the keyword forms.
    val TAG_PREFIX = TextAttributesKey.createTextAttributesKey(
        "SKY_TAG_PREFIX", DefaultLanguageHighlighterColors.KEYWORD)
    val TAG_KEYWORD = TextAttributesKey.createTextAttributesKey(
        "SKY_TAG_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val SCOPE_RESERVED = TextAttributesKey.createTextAttributesKey(
        "SKY_SCOPE_RESERVED", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val SCOPE_LOOP = TextAttributesKey.createTextAttributesKey(
        "SKY_SCOPE_LOOP", DefaultLanguageHighlighterColors.METADATA)
    val SCOPE_CONST = TextAttributesKey.createTextAttributesKey(
        "SKY_SCOPE_CONST", DefaultLanguageHighlighterColors.CONSTANT)
    val IDENTIFIER = TextAttributesKey.createTextAttributesKey(
        "SKY_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
    val OPERATOR = TextAttributesKey.createTextAttributesKey(
        "SKY_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    // Filter pipe `|` in `{var|trim|date}`. Same family as tag prefix —
    // a structural control marker, not an arithmetic operator.
    val PIPE = TextAttributesKey.createTextAttributesKey(
        "SKY_PIPE", DefaultLanguageHighlighterColors.KEYWORD)
    val STRING = TextAttributesKey.createTextAttributesKey(
        "SKY_STRING", DefaultLanguageHighlighterColors.STRING)
    val NUMBER = TextAttributesKey.createTextAttributesKey(
        "SKY_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val DOT = TextAttributesKey.createTextAttributesKey(
        "SKY_DOT", DefaultLanguageHighlighterColors.DOT)
    val COMMA = TextAttributesKey.createTextAttributesKey(
        "SKY_COMMA", DefaultLanguageHighlighterColors.COMMA)
    val PARENS = TextAttributesKey.createTextAttributesKey(
        "SKY_PARENS", DefaultLanguageHighlighterColors.PARENTHESES)
    val BRACKETS = TextAttributesKey.createTextAttributesKey(
        "SKY_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    val BAD_CHARACTER = TextAttributesKey.createTextAttributesKey(
        "SKY_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)
}

class SkyTemplateSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = SkyTemplateLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        val key: TextAttributesKey? = when (tokenType) {
            T.COMMENT_OPEN, T.COMMENT_CLOSE, T.COMMENT_CONTENT, T.LINE_COMMENT -> SkyTemplateColors.COMMENT
            T.LBRACE, T.RBRACE -> SkyTemplateColors.BRACES
            T.TAG_PREFIX, T.ESCAPE_LITERAL -> SkyTemplateColors.TAG_PREFIX
            T.TAG_KEYWORD -> SkyTemplateColors.TAG_KEYWORD
            T.SCOPE_RESERVED -> SkyTemplateColors.SCOPE_RESERVED
            T.SCOPE_LOOP -> SkyTemplateColors.SCOPE_LOOP
            T.SCOPE_CONST -> SkyTemplateColors.SCOPE_CONST
            T.IDENTIFIER -> SkyTemplateColors.IDENTIFIER
            // AT / HASH are mid-tag variable modifiers (`{var@2}` parent-loop
            // depth, `{var#5}` zerofill width). Visually they read as
            // SkyTemplate accents — paint them with the same colour as the
            // tag-start prefix forms (`{?…}`, `{@…}`, `{#…}`, `{=…}`) for
            // consistency. The OPERATION_SIGN colour was too dim to stand out.
            T.AT, T.HASH -> SkyTemplateColors.TAG_PREFIX
            T.OPERATOR, T.ARROW, T.DBL_COLON, T.COLON, T.EQ, T.NS_SEP -> SkyTemplateColors.OPERATOR
            T.PIPE -> SkyTemplateColors.PIPE
            T.STRING -> SkyTemplateColors.STRING
            T.NUMBER -> SkyTemplateColors.NUMBER
            T.DOT -> SkyTemplateColors.DOT
            T.COMMA -> SkyTemplateColors.COMMA
            T.LPAREN, T.RPAREN -> SkyTemplateColors.PARENS
            T.LBRACKET, T.RBRACKET -> SkyTemplateColors.BRACKETS
            T.BAD_CHARACTER -> SkyTemplateColors.BAD_CHARACTER
            else -> null
        }
        return if (key == null) emptyArray() else arrayOf(key)
    }
}

class SkyTemplateSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        SkyTemplateSyntaxHighlighter()
}
