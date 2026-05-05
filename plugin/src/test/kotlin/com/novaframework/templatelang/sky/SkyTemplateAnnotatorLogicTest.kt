package com.novaframework.templatelang.sky

import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

/**
 * Exercises the lexer-driven token classification the annotator performs on
 * XmlText / XmlAttributeValue text. The annotator itself plugs into the IDE,
 * but the underlying decision (which ranges to colour) is testable in
 * isolation here.
 */
class SkyTemplateAnnotatorLogicTest {

    private fun nonOuterTokens(text: String): List<Pair<IElementType, String>> {
        val lexer = SkyTemplateLexer()
        lexer.start(text, 0, text.length, 0)
        val out = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            if (type !== T.OUTER_CONTENT) {
                out += type to text.substring(lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return out
    }

    @Test fun xmlTextWithVariable() {
        // Simulates an XmlText node whose text is `Hello {name}!`
        val toks = nonOuterTokens("Hello {name}!")
        assertEquals(listOf(T.LBRACE, T.IDENTIFIER, T.RBRACE), toks.map { it.first })
        assertEquals("name", toks[1].second)
    }

    @Test fun xmlTextWithLoopAndComment() {
        // Simulates the inside of a `<ul>…</ul>` block.
        val toks = nonOuterTokens("{* show items *}\n{loop products}{/}")
        val types = toks.map { it.first }
        assertTrue(T.COMMENT_OPEN in types)
        assertTrue(T.COMMENT_CLOSE in types)
        assertTrue(T.TAG_KEYWORD in types)
        // Two LBRACEs (loop + end)
        assertEquals(2, types.count { it == T.LBRACE })
    }

    @Test fun xmlAttributeValueWithVariable() {
        // Simulates `class="item-{_index}"` — full attribute value text.
        val toks = nonOuterTokens("\"item-{_index}\"")
        val types = toks.map { it.first }
        // { _index }
        assertTrue(T.LBRACE in types)
        assertTrue(T.SCOPE_RESERVED in types)
        assertTrue(T.RBRACE in types)
    }

    @Test fun pureHtmlTextProducesNoTemplateTokens() {
        val toks = nonOuterTokens("<p>nothing template-y here</p>")
        // No `{`, no template tokens.
        assertTrue(toks.isEmpty())
    }

    @Test fun jsTemplateLiteralIgnored() {
        // `${name}` is JS, the lexer must not treat it as a SkyTemplate tag.
        val toks = nonOuterTokens("const x = `\${name}`;")
        assertTrue(toks.isEmpty())
    }

    @Test fun pipeFunctionInAttribute() {
        // `value="{name|trim}"`
        val toks = nonOuterTokens("\"{name|trim}\"")
        val types = toks.map { it.first }
        assertTrue(T.PIPE in types)
        assertTrue(T.IDENTIFIER in types)
    }

    @Test fun emptyTextIsSafe() {
        assertTrue(nonOuterTokens("").isEmpty())
    }

    @Test fun textWithoutBraceShortCircuits() {
        // The annotator early-exits on `'{' !in text` — verify the lexer agrees
        // there's no template content.
        assertTrue(nonOuterTokens("plain html content with no braces").isEmpty())
    }

    @Test fun templateInsideQuotedAttributeStillParses() {
        // `tpl-checked="{enabled}"` attribute value (with quotes intact).
        val toks = nonOuterTokens("\"{enabled}\"")
        assertEquals(listOf(T.LBRACE, T.IDENTIFIER, T.RBRACE), toks.map { it.first })
    }

    @Test fun annotatorDoesNotMisreadDollarBraceEvenInHtmlText() {
        // Edge case: literal `${foo}` written in HTML body (rare but possible).
        val toks = nonOuterTokens("price: \${total}")
        assertFalse(T.LBRACE in toks.map { it.first })
    }

    @Test fun constantScopeReceivesItsOwnTokenForColouring() {
        // T3 / 0.5.10: `{c.NAME}` inside HTML body (e.g. an XmlText node).
        // The leading `c` must be classified SCOPE_CONST so the annotator can
        // pick up the dedicated colour configured in the Color Settings page.
        val toks = nonOuterTokens("<a href=\"{c.BASE_URL}/page\">link</a>")
        val types = toks.map { it.first }
        assertTrue("expected SCOPE_CONST in $types", T.SCOPE_CONST in types)
        // The constant name itself stays as IDENTIFIER.
        assertTrue(T.IDENTIFIER in types)
    }
}
