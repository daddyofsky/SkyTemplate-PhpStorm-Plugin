package com.novaframework.templatelang.sky

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class SkyTemplateTokenType(debugName: String) : IElementType(debugName, SkyTemplateLanguage)

object SkyTemplateTokenTypes {
    // Outer (non-template) content. Will be delegated to HTML data layer later.
    @JvmField val OUTER_CONTENT = SkyTemplateTokenType("OUTER_CONTENT")

    // Comments: {* ... *}
    @JvmField val COMMENT_OPEN = SkyTemplateTokenType("COMMENT_OPEN")     // {*
    @JvmField val COMMENT_CLOSE = SkyTemplateTokenType("COMMENT_CLOSE")   // *}
    @JvmField val COMMENT_CONTENT = SkyTemplateTokenType("COMMENT_CONTENT")

    // Tag delimiters
    @JvmField val LBRACE = SkyTemplateTokenType("LBRACE") // {
    @JvmField val RBRACE = SkyTemplateTokenType("RBRACE") // }

    // Tag prefix (single chars in tagAlias)
    @JvmField val TAG_PREFIX = SkyTemplateTokenType("TAG_PREFIX")

    // Tag keyword (loop/if/else/end/foreach/for/while/include/...)
    @JvmField val TAG_KEYWORD = SkyTemplateTokenType("TAG_KEYWORD")

    // Reserved scope marker for variables: _var
    @JvmField val SCOPE_RESERVED = SkyTemplateTokenType("SCOPE_RESERVED") // leading _
    @JvmField val SCOPE_LOOP = SkyTemplateTokenType("SCOPE_LOOP")         // leading dots .. ...
    @JvmField val SCOPE_CONST = SkyTemplateTokenType("SCOPE_CONST")       // leading c.

    @JvmField val IDENTIFIER = SkyTemplateTokenType("IDENTIFIER")

    // Operators / punctuation inside expressions
    @JvmField val DOT = SkyTemplateTokenType("DOT")           // .
    @JvmField val ARROW = SkyTemplateTokenType("ARROW")       // -> ?->
    @JvmField val DBL_COLON = SkyTemplateTokenType("DBL_COLON") // ::
    /**
     * Single `:` — emitted ONLY when not part of `::` (DBL_COLON wins via
     * peek). Distinct from OPERATOR so the SkyTemplate ref-detector can
     * recognise PHP-8 named-argument syntax (`foo(name: $x)`) without
     * scanning raw characters. Visually still painted as an operator.
     */
    @JvmField val COLON = SkyTemplateTokenType("COLON")       // : (single, not ::)
    @JvmField val PIPE = SkyTemplateTokenType("PIPE")         // |
    @JvmField val AT = SkyTemplateTokenType("AT")             // @ (var_up)
    @JvmField val HASH = SkyTemplateTokenType("HASH")         // # (zerofill)
    @JvmField val EQ = SkyTemplateTokenType("EQ")             // = (param sep in pipe func)
    @JvmField val LPAREN = SkyTemplateTokenType("LPAREN")
    @JvmField val RPAREN = SkyTemplateTokenType("RPAREN")
    @JvmField val LBRACKET = SkyTemplateTokenType("LBRACKET")
    @JvmField val RBRACKET = SkyTemplateTokenType("RBRACKET")
    @JvmField val COMMA = SkyTemplateTokenType("COMMA")
    @JvmField val NS_SEP = SkyTemplateTokenType("NS_SEP")     // \
    @JvmField val OPERATOR = SkyTemplateTokenType("OPERATOR") // + - * / % == != etc.

    @JvmField val STRING = SkyTemplateTokenType("STRING")
    @JvmField val NUMBER = SkyTemplateTokenType("NUMBER")
    @JvmField val LINE_COMMENT = SkyTemplateTokenType("LINE_COMMENT") // //... inside expression

    @JvmField val WHITE_SPACE_INSIDE = SkyTemplateTokenType("WHITE_SPACE_INSIDE")
    @JvmField val BAD_CHARACTER = SkyTemplateTokenType("BAD_CHARACTER")

    /**
     * Standalone escape literal — `{\` (no closing `}` on the line) or
     * `\}` (no opening `{` on the line). Per SkyTemplate compiler the
     * `\` prefix is the "escape" alias that emits a literal brace; the
     * paired `{\}` form is already lexed as LBRACE+TAG_PREFIX+RBRACE
     * via STATE_IN_TAG. This token covers the standalone variants so
     * they get the tag-prefix accent colour without entering IN_TAG.
     */
    @JvmField val ESCAPE_LITERAL = SkyTemplateTokenType("ESCAPE_LITERAL")

    val COMMENTS = TokenSet.create(COMMENT_OPEN, COMMENT_CLOSE, COMMENT_CONTENT, LINE_COMMENT)
    val STRINGS = TokenSet.create(STRING)
    val WHITESPACES = TokenSet.create(WHITE_SPACE_INSIDE)

    // Tag keywords (full-word form). Single-char alias forms are tokenised as TAG_PREFIX.
    val KEYWORDS = setOf(
        "loop", "each", "if", "else", "elseif", "end", "foreach", "for", "while",
        "refer", "include", "execute", "dump", "escape"
    )

    // Tag-prefix single chars: & ? : / @ % = ; # + ] \
    fun isTagPrefixChar(c: Char): Boolean = c in "&?:/@%=;#+]\\"
}
