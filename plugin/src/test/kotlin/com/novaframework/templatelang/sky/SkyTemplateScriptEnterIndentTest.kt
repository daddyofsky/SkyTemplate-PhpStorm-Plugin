package com.novaframework.templatelang.sky

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Enter indentation inside an embedded `<script>` / `<style>` body, where
 * SkyTemplate block tags interleave with JS / CSS braces. Mirrors the cases
 * the user reported against test/dup.html. Fixture default indent = 4 spaces,
 * so one level = 4 spaces (the user's file uses tabs; the structure, not the
 * indent character, is what matters).
 *
 * The host JS / CSS Enter delegate can't see `{?var}` / `{:}` / `{/}` block
 * structure, so without the handler's embedded-aware path the new line lands
 * at the host's Sky-blind depth.
 */
class SkyTemplateScriptEnterIndentTest : BasePlatformTestCase() {

    /** Type Enter at the `<caret>` in [source]; return the new caret line's indent. */
    private fun enterIndent(filename: String, source: String): String {
        myFixture.configureByText(filename, source)
        myFixture.type('\n')
        val doc = myFixture.editor.document.charsSequence
        val off = myFixture.editor.caretModel.offset
        var ls = off
        while (ls > 0 && doc[ls - 1] != '\n') ls--
        var i = ls
        while (i < doc.length && (doc[i] == ' ' || doc[i] == '\t')) i++
        return doc.subSequence(ls, i).toString()
    }

    private val SCRIPT_OPEN = "<script type=\"text/javascript\">\n"

    fun testEnterAfterJsOpenBraceInsideSkyBlock() {
        // `        function test() {` is at level 2 (8 sp); the JS `{` pushes
        // the new line to level 3 (12 sp).
        val indent = enterIndent(
            "a.html",
            SCRIPT_OPEN +
                "    {?var}\n" +
                "        function test() {<caret>\n" +
                "        }\n" +
                "    {/}\n" +
                "</script>\n",
        )
        assertEquals("level 3 (8 + JS brace)", "            ", indent)
    }

    fun testEnterAfterCloserReturnsToScriptBody() {
        // After `{/}` (closes `{?var}`), the next line is script body = level 1.
        val indent = enterIndent(
            "a.html",
            SCRIPT_OPEN +
                "    {?var}\n" +
                "        function test() {\n" +
                "        }\n" +
                "    {/}<caret>\n" +
                "    test();\n" +
                "</script>\n",
        )
        assertEquals("level 1 (script body, {?var} closed)", "    ", indent)
    }

    fun testEnterOnBlankLineInScriptBody() {
        // Blank line between `{/}` and `test();`; Enter keeps script body level 1.
        val indent = enterIndent(
            "a.html",
            SCRIPT_OPEN +
                "    {?var}\n" +
                "        x();\n" +
                "    {/}\n" +
                "<caret>\n" +
                "    test();\n" +
                "</script>\n",
        )
        assertEquals("level 1 (blank line in script body)", "    ", indent)
    }

    fun testEnterAfterStatementInScriptBody() {
        // After `const a = {=...};` (level 1) — the new line stays level 1.
        val indent = enterIndent(
            "a.html",
            SCRIPT_OPEN +
                "    {?var}\n" +
                "        x();\n" +
                "    {/}\n" +
                "    const a = {=json_encode(data_json)};<caret>\n" +
                "</script>\n",
        )
        assertEquals("level 1 (after statement)", "    ", indent)
    }

    fun testEnterInsideSecondBranchBody() {
        // Caret after a statement inside the `{:}` branch body — level 2.
        val indent = enterIndent(
            "a.html",
            SCRIPT_OPEN +
                "    {?var}\n" +
                "        a();\n" +
                "    {:}\n" +
                "        b();<caret>\n" +
                "    {/}\n" +
                "</script>\n",
        )
        assertEquals("level 2 (inside {:} branch)", "        ", indent)
    }

    fun testEnterInStyleAfterCloser() {
        // `<style>` parallel: after `{/}` returns to style body level 1.
        val indent = enterIndent(
            "a.html",
            "<style>\n" +
                "    {?var}\n" +
                "        #id {}\n" +
                "    {/}<caret>\n" +
                "</style>\n",
        )
        assertEquals("level 1 (style body, {?var} closed)", "    ", indent)
    }
}
