package com.novaframework.templatelang.sky

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Reformat-Code regression for SkyTemplate tags inside embedded `<script>`
 * JS in `*.html` host files. The JS formatter reads `{=json_encode(data)}`
 * as an object-literal / block and splits it across lines; the
 * pre/post-format protect+restore pair ([SkyTemplatePreFormatProcessor] /
 * [SkyTemplatePostFormatProcessor]) must put each tag back on one piece.
 */
class SkyTemplateScriptReformatPreserveTest : BasePlatformTestCase() {

    private fun reformat(filename: String, source: String): String {
        myFixture.configureByText(filename, source)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project)
                .reformatText(myFixture.file, 0, myFixture.file.textLength)
        }
        return myFixture.editor.document.text
    }

    /** Body strictly between the first `<tag …>` and its `</tag>`. */
    private fun bodyOf(text: String, tag: String): String {
        val open = text.indexOf("<$tag")
        val gt = text.indexOf('>', open)
        val close = text.indexOf("</$tag>", gt)
        return text.substring(gt + 1, close)
    }

    fun testRawOutputTagSurvivesScriptReformat() {
        val result = reformat(
            "a.html",
            "<html>\n<body>\n<script>\n    const a = {=json_encode(data)};\n</script>\n</body>\n</html>",
        )
        assertFalse(
            "tag must not be split into `{ =`; got:\n$result",
            result.contains("{ ="),
        )
        assertTrue(
            "tag `{=json_encode(data)}` must survive intact on one line; got:\n$result",
            result.contains("{=json_encode(data)}"),
        )
    }

    fun testTwoTagsOnSeparateLinesBothSurvive() {
        val result = reformat(
            "a.html",
            "<html>\n<body>\n<script>\n    const a = {=json_encode(data)};\n    const b = {=foo(bar)};\n</script>\n</body>\n</html>",
        )
        assertFalse("no tag split; got:\n$result", result.contains("{ ="))
        assertTrue(
            "first tag intact; got:\n$result",
            result.contains("{=json_encode(data)}"),
        )
        assertTrue(
            "second tag intact; got:\n$result",
            result.contains("{=foo(bar)}"),
        )
    }

    fun testPlainScriptObjectLiteralReformatsNormally() {
        // No Sky tag — the object literal is genuine JS and may be reformatted;
        // we only require no crash and that the keys survive (we do not restore
        // non-Sky braces).
        val result = reformat(
            "a.html",
            "<html>\n<body>\n<script>\n    const x = {a: 1, b: 2};\n</script>\n</body>\n</html>",
        )
        assertTrue("key `a` survives; got:\n$result", result.contains("a"))
        assertTrue("key `b` survives; got:\n$result", result.contains("b"))
        assertTrue("values survive; got:\n$result", result.contains("1") && result.contains("2"))
    }

    fun testInlineConditionalTagsSurvive() {
        val result = reformat(
            "a.html",
            "<html>\n<body>\n<script>\n    const c = {?var}true{:}false{/};\n</script>\n</body>\n</html>",
        )
        assertTrue("`{?var}` intact; got:\n$result", result.contains("{?var}"))
        assertTrue("`{:}` intact; got:\n$result", result.contains("{:}"))
        assertTrue("`{/}` intact; got:\n$result", result.contains("{/}"))
    }

    // ── whole-body verbatim protection (the dup.html complaints) ──────────────
    // The JS / CSS formatter mangles the whitespace BETWEEN tags too: it adds
    // blank lines around block tags, pushes the `;` of a one-line statement
    // onto its own line, breaks an inline `{?var}…{/}` over several lines, and
    // mis-indents. A SkyTemplate-bearing `<script>` / `<style>` body must come
    // back exactly as written.

    // Indented to the fixture's default code style (4 spaces / level). The
    // user's test/dup.html is tab-indented; the structure (not the indent
    // character) is what the fix preserves, so the body is written here in
    // the project's own indent. Without the region snapshot the formatter
    // would split `const a`/`const b` and wedge blank lines around the block
    // tags, so an exact-body match is a real regression guard.
    private val scriptBody =
        "\n" +
            "    {?var}\n" +
            "        function test() {\n" +
            "        }\n" +
            "    {:}\n" +
            "        function test() {\n" +
            "        }\n" +
            "    {/}\n" +
            "\n" +
            "    test();\n" +
            "\n" +
            "    const a = {=json_encode(data_json)};\n" +
            "    const b = {?var}true{:}false{/};\n" +
            "\n" +
            "    console.log(a, b);\n"

    fun testBlockStructuredScriptBodyPreservedVerbatim() {
        // Mirrors the FIRST <script> in test/dup.html. Asserts the whole body
        // is byte-identical after Reformat: no blank lines added, single-line
        // statements intact, indentation unchanged.
        val result = reformat("a.html", "<script type=\"text/javascript\">$scriptBody</script>\n")
        assertEquals(
            "script body must survive Reformat verbatim",
            scriptBody,
            bodyOf(result, "script"),
        )
    }

    fun testSingleLineStatementsNotSplit() {
        val result = reformat("a.html", "<script type=\"text/javascript\">$scriptBody</script>\n")
        assertTrue(
            "`const a` must stay one line; got:\n$result",
            result.contains("const a = {=json_encode(data_json)};"),
        )
        assertTrue(
            "inline `const b` conditional must stay one line; got:\n$result",
            result.contains("const b = {?var}true{:}false{/};"),
        )
    }

    fun testNoBlankLinesInsertedAroundBlockTags() {
        val result = reformat("a.html", "<script type=\"text/javascript\">$scriptBody</script>\n")
        val body = bodyOf(result, "script")
        // The formatter used to wedge a blank line after `{?var}`, around
        // `{:}`, and before `{/}`. The opener must be immediately followed by
        // its body line, never a blank line.
        assertTrue("`{?var}` opener must be followed by the function, got:\n$body",
            body.contains("    {?var}\n        function test() {"))
        assertTrue("`{:}` must sit between the two function bodies, got:\n$body",
            body.contains("        }\n    {:}\n        function test() {"))
        assertTrue("`{/}` must immediately follow the second function body, got:\n$body",
            body.contains("        }\n    {/}\n"))
    }

    fun testStyleBodyPreservedVerbatim() {
        val styleBody = "\n    {?var}\n        #id {}\n    {:}\n        #id {}\n    {/}\n"
        val result = reformat("a.html", "<style>$styleBody</style>\n")
        assertEquals(
            "style body must survive Reformat verbatim",
            styleBody,
            bodyOf(result, "style"),
        )
    }

    fun testReformatWithNoOpReindentDoesNotThrowOnShrunkScript() {
        // P-BUG-08: restoreMangledTags can shrink the document (a snapshot
        // restores a mangled, formatter-lengthened script body back to its
        // shorter original) in the SAME processText call where the reindent
        // walk makes no edits of its own. originalLength must be captured
        // BEFORE restore so that "no-op reindent" doesn't fall back to a
        // stale pre-restore rangeToReformat with out-of-bounds offsets —
        // reformatText must complete without a range/index exception.
        val source = "<html>\n<body>\n<script>\n    const a = {=json_encode(data)};\n</script>\n</body>\n</html>"
        val result = reformat("a.html", source)
        assertTrue(
            "tag must survive intact; got:\n$result",
            result.contains("{=json_encode(data)}"),
        )
    }
}
