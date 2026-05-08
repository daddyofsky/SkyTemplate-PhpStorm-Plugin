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

    fun testSameLineCloserOpensUpToThreeLines() {
        // Smart split: caret sits between an opener and an existing closer
        // on the same line. Mirrors the PHP / JS `{<enter>}` editor habit
        // — open up to a three-line shape with the closer on its own
        // line at the matching opener's depth.
        val result = typeEnter("a.sky", "{loop xs as x}<caret>{/}")
        assertEquals(
            "{loop xs as x}\n    \n{/}",
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

    fun testExistingDownstreamCloserSuppressesAutoClose() {
        // User typed `{?foo}` in front of a block whose `{/}` already
        // exists below — Enter must not duplicate the closer.
        val result = typeEnter(
            "a.sky",
            "{?foo}<caret>\n  body\n{/}",
        )
        assertEquals(
            "{?foo}\n    \n  body\n{/}",
            result.trimEnd(),
        )
    }

    fun testIndentInherited() {
        val result = typeEnter("a.sky", "        {if x}<caret>")
        assertEquals(
            "        {if x}\n            \n        {/}",
            result.trimEnd()
        )
    }

    fun testHtmlChildLiftAlignsWithReformatOutput() {
        // User typed `{?cond}` at col 0 immediately under `<div>`. Without
        // the HTML-aware lift, Enter would place body at col 4 / closer at
        // col 0 while Reformat (post-format) re-indents to col 8 / col 4
        // — Enter and Reformat would diverge. The lift makes Enter match
        // what Reformat would settle on: opener moves to col 4, body to
        // col 8, closer to col 4.
        val result = typeEnter("a.html", "<div>\n{?cond}<caret>")
        assertEquals(
            "<div>\n    {?cond}\n        \n    {/}",
            result.trimEnd(),
        )
    }

    fun testEnterInsideSkyBlockUsesSkyDepth() {
        // Issue 1: caret somewhere mid-body INSIDE a `{?cond}` block
        // in an HTML host. Without Sky awareness, the platform Enter
        // would copy whatever indent body1 had (col 4 here, flush with
        // `{?cond}` and `<div>` content). The post-Enter pass lifts
        // the new blank line to the SKY-aware body depth — `{?cond}`
        // sits at effective col 4, so its body level is col 8, and
        // that's where the caret lands so the next character the user
        // types is correctly nested inside `{?cond}`.
        val result = typeEnter("a.html", "<div>\n    {?cond}\n    body1<caret>\n    {/}\n</div>")
        assertEquals(
            "<div>\n    {?cond}\n    body1\n        \n    {/}\n</div>",
            result.trimEnd(),
        )
    }

    fun testEnterBeforeCloserKeepsCloserAlignedWithOpener() {
        // Issue 2: caret right before `{/}` on its own line. The
        // platform's Enter inserts the newline and may auto-indent the
        // closer line; the post-Enter pass pulls the closer back to
        // its opener's level (two-sided rule for closer / branch).
        val result = typeEnter("a.sky", "{?cond}\n    body\n    <caret>{/}")
        // After Enter: body / blank line / `{/}` with `{/}` aligned to
        // the opener (col 0).
        assertEquals(
            "{?cond}\n    body\n    \n{/}",
            result.trimEnd(),
        )
    }

    fun testEnterBeforeBranchKeepsBranchAlignedWithOpener() {
        val result = typeEnter("a.sky", "{?cond}\n    body\n    <caret>{:}")
        assertEquals(
            "{?cond}\n    body\n    \n{:}",
            result.trimEnd(),
        )
    }

    fun testSmartSplitWithBodyContentBeforeCaret() {
        // Caret sits between content (`stmt`) and an existing `{/}`. The
        // smart split inserts an indented blank line and pushes `{/}` to
        // the next line at the opener's depth.
        val result = typeEnter("a.sky", "{?cond}\n    stmt<caret>{/}")
        assertEquals(
            "{?cond}\n    stmt\n    \n{/}",
            result.trimEnd(),
        )
    }

    fun testSmartSplitLiftsShallowPreCaretIndent() {
        // User's caret line is indented at col 0 (shallower than the
        // body level for `{?cond}` at col 0, which is col 4). The smart
        // split LIFTS the caret line indent up to body level so the
        // caret lands at the proper depth for typing the body.
        val result = typeEnter("a.sky", "{?cond}\n<caret>{/}")
        assertEquals(
            "{?cond}\n    \n{/}",
            result.trimEnd(),
        )
    }

    fun testSmartSplitRespectsDeeperUserIndent() {
        // User typed extra-deep indent (col 8) on the caret line; the
        // smart split must not REDUCE it. Body indent stays at col 8.
        // Closer goes to opener level (col 0).
        val result = typeEnter("a.sky", "{?cond}\n        <caret>{/}")
        assertEquals(
            "{?cond}\n        \n{/}",
            result.trimEnd(),
        )
    }
}
