package com.novaframework.templatelang.sky

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * IDE-level integration check for [SkyTemplateEnterHandler]. Drives the
 * actual editor action (`myFixture.type('\n')`) so that the Enter handler
 * delegate chain is exercised end-to-end.
 *
 * `*.html` host scope is verified separately — pressing Enter inside an
 * HTML file must NOT produce SkyTemplate-style auto-closes (that would
 * disrupt Emmet / HTML tag-pair completion).
 */
class SkyTemplateEnterHandlerIntegrationTest : BasePlatformTestCase() {

    /** Helper: configure source with `<caret>` marker, type Enter, return result. */
    private fun typeEnter(filename: String, source: String): String {
        myFixture.configureByText(filename, source)
        myFixture.type('\n')
        return myFixture.editor.document.text
    }

    fun testLoopOpenerInsertsAutoClose() {
        val result = typeEnter("a.sky", "{loop xs as x}<caret>")
        // Default indent step in the test fixture is 4 spaces.
        assertEquals(
            "{loop xs as x}\n    \n{/}",
            result.trimEnd()
        )
    }

    fun testIfOpenerInsertsAutoClose() {
        val result = typeEnter("a.sky", "{if cond}<caret>")
        assertEquals(
            "{if cond}\n    \n{/}",
            result.trimEnd()
        )
    }

    fun testForeachOpenerInsertsAutoClose() {
        val result = typeEnter("a.sky", "{foreach items as it}<caret>")
        assertEquals(
            "{foreach items as it}\n    \n{/}",
            result.trimEnd()
        )
    }

    fun testPrefixIfInsertsAutoClose() {
        val result = typeEnter("a.sky", "{?cond}<caret>")
        assertEquals(
            "{?cond}\n    \n{/}",
            result.trimEnd()
        )
    }

    fun testSameLineCloserSuppressesAutoClose() {
        val result = typeEnter("a.sky", "{loop xs as x}<caret>{/}")
        // Same line already has `{/}` — handler injects only an indented
        // blank position; the existing `{/}` stays put on the next line.
        assertEquals(
            "{loop xs as x}\n    {/}",
            result.trimEnd()
        )
    }

    fun testNonBlockTagFallsThroughToDefault() {
        // `{=foo()}` is not a block — Enter should produce the platform's
        // default behaviour (a plain newline).
        val result = typeEnter("a.sky", "{=foo()}<caret>")
        assertEquals("{=foo()}\n", result)
    }

    fun testCaretInsideTagBodyFallsThrough() {
        val result = typeEnter("a.sky", "{loop x<caret>")
        assertEquals("{loop x\n", result)
    }

    fun testHtmlFileAutoCloses() {
        // 0.5.16: scope was lifted from `*.sky` only to also cover HTML
        // host files. A SkyTemplate opener in `*.html` now auto-closes
        // on Enter just like it does in `*.sky`.
        myFixture.configureByText("a.html", "<p>{loop x}<caret></p>")
        myFixture.type('\n')
        val text = myFixture.editor.document.text
        assertTrue(
            "SkyTemplate auto-close should fire in *.html files; got: $text",
            text.contains("{/}")
        )
    }

    fun testSkyFileIsAffected() {
        // 0.5.26: `.skyhtml` was dropped from SkyTemplateFileType;
        // verifying the `.sky` path still triggers auto-close.
        val result = typeEnter("a.sky", "{if x}<caret>")
        assertEquals(
            "{if x}\n    \n{/}",
            result.trimEnd()
        )
    }

    fun testIndentInherited() {
        val result = typeEnter("a.sky", "        {if x}<caret>")
        assertEquals(
            "        {if x}\n            \n        {/}",
            result.trimEnd()
        )
    }
}
