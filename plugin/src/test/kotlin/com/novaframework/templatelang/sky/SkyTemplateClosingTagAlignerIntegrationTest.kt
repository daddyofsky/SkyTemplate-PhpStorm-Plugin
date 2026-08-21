package com.novaframework.templatelang.sky

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * IDE-level integration check for [SkyTemplateClosingTagAligner].
 * Drives `myFixture.type('}')` to exercise the typed-handler delegate
 * chain end-to-end (including the pre-`}` document state matching what
 * the user would have on screen mid-typing).
 */
class SkyTemplateClosingTagAlignerIntegrationTest : BasePlatformTestCase() {

    /**
     * Configure [source] (which contains a `<caret>` marker positioned
     * AFTER the user-typed `{tag` but BEFORE the trailing `}`), then
     * type `}` to complete the tag and trigger the delegate.
     */
    private fun typeClose(filename: String, source: String): String {
        myFixture.configureByText(filename, source)
        myFixture.type('}')
        return myFixture.editor.document.text
    }

    fun testBranchSlashAlignsInsideIfBlock() {
        val out = typeClose("a.sky", "{?cond}\n    body\n    {:<caret>")
        assertEquals("{?cond}\n    body\n{:}", out.trimEnd())
    }

    fun testCloserSlashAlignsInsideIfBlock() {
        val out = typeClose("a.sky", "{?cond}\n    body\n    {/<caret>")
        assertEquals("{?cond}\n    body\n{/}", out.trimEnd())
    }

    fun testElseKeywordAligns() {
        val out = typeClose("a.sky", "{if x}\n    body\n        {else<caret>")
        assertEquals("{if x}\n    body\n{else}", out.trimEnd())
    }

    fun testEndKeywordAligns() {
        val out = typeClose("a.sky", "{loop xs}\n    body\n        {end<caret>")
        assertEquals("{loop xs}\n    body\n{end}", out.trimEnd())
    }

    fun testCloserInsideHtmlAlsoAligns() {
        // Verifies the typed handler fires in `*.html` host files too.
        val out = typeClose(
            "a.html",
            "<div>\n    {@items}\n        body\n            {/<caret>",
        )
        assertEquals(
            "<div>\n    {@items}\n        body\n    {/}",
            out.trimEnd(),
        )
    }

    fun testInlineCloserNotRealigned() {
        // `{/}` not at line start — must not be touched.
        val out = typeClose("a.sky", "{?cond}body{/<caret>")
        assertEquals("{?cond}body{/}", out.trimEnd())
    }
}
