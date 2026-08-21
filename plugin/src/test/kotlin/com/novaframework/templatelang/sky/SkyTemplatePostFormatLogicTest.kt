package com.novaframework.templatelang.sky

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [SkyTemplatePostFormatLogic.reindent]. The
 * IntelliJ-side processor is a thin shim that funnels the document
 * snapshot here.
 */
class SkyTemplatePostFormatLogicTest {

    /** Run the re-indent algorithm against [src] and return the patched text. */
    private fun reindent(src: String, indentStep: String = "    "): String {
        val sb = StringBuilder(src)
        SkyTemplatePostFormatLogic.reindent(
            text = src,
            range = TextRange(0, src.length),
            indentStep = indentStep,
        ) { from, to, replacement ->
            sb.replace(from, to, replacement)
        }
        return sb.toString()
    }

    @Test fun bodyLineMissingIndent_isRestored() {
        val before = "{loop xs as x}\n<p>body</p>\n{/}"
        val after = reindent(before)
        assertEquals("{loop xs as x}\n    <p>body</p>\n{/}", after)
    }

    @Test fun bodyLineWithDeeperIndent_isPreserved() {
        // Already indented further than opener+step (e.g. nested inside HTML
        // structure the host formatter understands). Must not reduce.
        val before = "{loop xs as x}\n        <p>body</p>\n{/}"
        val after = reindent(before)
        assertEquals(before, after)
    }

    @Test fun bodyLineWithExactDesiredIndent_isUnchanged() {
        val before = "{loop xs as x}\n    <p>body</p>\n{/}"
        val after = reindent(before)
        assertEquals(before, after)
    }

    @Test fun nestedBlocks_innerOpenerCloserAndBodyAreReindented() {
        // Both the outer and inner openers are at column 0 (host formatter
        // having stripped indent). The processor now re-indents nested tag
        // lines too: the inner `{if x.active}` and its matching `{/}` move
        // to column 4 (= outer.effectiveIndent + step), and the body line
        // `<li>x</li>` settles at column 8. The outer `{loop xs as x}`
        // and outer `{/}` stay at column 0 \u2014 top-level pair anchored
        // to the host formatter's placement.
        val before = "{loop xs as x}\n{if x.active}\n<li>x</li>\n{/}\n{/}"
        val after = reindent(before)
        assertEquals(
            "{loop xs as x}\n    {if x.active}\n        <li>x</li>\n    {/}\n{/}",
            after,
        )
    }

    @Test fun commentInterior_skyBlockIndentsBodyUnderOpener() {
        // Inside a `{*…*}` comment, template tags drive indent on par with
        // HTML: the `{loop}` body lifts one step, `{/}` returns to the
        // opener's level, and the comment markers sit at the comment's
        // own (HTML-derived) depth.
        val before = "<div>\n{*\n{loop xs as x}\n<li>x</li>\n{/}\n*}\n</div>"
        val after = reindent(before)
        assertEquals(
            "<div>\n    {*\n    {loop xs as x}\n        <li>x</li>\n    {/}\n    *}\n</div>",
            after,
        )
    }

    @Test fun commentInterior_unbalancedOpenerDoesNotLeakBelow() {
        // A `{loop}` with no `{/}` inside the comment must NOT indent the
        // real `<p>` that follows the comment — comment-scoped containment
        // restores the depth at `*}`.
        val before = "<div>\n{*\n{loop xs}\n*}\n<p>x</p>\n</div>"
        val after = reindent(before)
        assertEquals(
            "<div>\n    {*\n    {loop xs}\n    *}\n    <p>x</p>\n</div>",
            after,
        )
    }

    @Test fun computeIndentForLine_blankLineInsideCommentUsesSkyFrame() {
        // Blank caret line between `{loop}` and `{/}` inside a comment →
        // one step under the `{loop}` opener's ACTUAL indent (col 0 here;
        // the enclosing `<div>` depth does not compound — the result is
        // relative to the nearest opener as it stands in the document).
        val text = "<div>\n{*\n{loop xs as x}\n\n{/}\n*}\n</div>"
        val lineStart = text.indexOf("{loop xs as x}\n") + "{loop xs as x}\n".length
        val indent = SkyTemplatePostFormatLogic.computeIndentForLine(text, lineStart, "    ")
        assertEquals("    ", indent)
    }

    @Test fun computeIndentForLine_isRelativeToNearestOpenersActualIndent() {
        // Unindented ancestor chain (`<html>` / `<body>` / `<div>` all at
        // col 0): the blank body line inside `{?cond}` indents relative to
        // `{?cond}`'s ACTUAL indent (col 0) → one step, NOT the whole-file
        // depth (which would be 4 levels deep).
        val text = "<html>\n<body>\n<div>\n{?cond}\n\n{/}\n</div>\n</body>\n</html>"
        val lineStart = text.indexOf("{?cond}\n") + "{?cond}\n".length
        val indent = SkyTemplatePostFormatLogic.computeIndentForLine(text, lineStart, "    ")
        assertEquals("    ", indent)
    }

    @Test fun computeIndentForLine_usesIndentedParentAsAnchor() {
        // Parent opener carries a real indent (col 4) — the body line sits
        // one step under it (col 8) even though grandparents are at col 0.
        val text = "<div>\n    {?cond}\n\n    {/}\n</div>"
        val lineStart = text.indexOf("{?cond}\n") + "{?cond}\n".length
        val indent = SkyTemplatePostFormatLogic.computeIndentForLine(text, lineStart, "    ")
        assertEquals("        ", indent)
    }

    @Test fun reindent_partialRange_isRelativeToActualParentIndent() {
        // Reindent ONLY the `{loop}` block (paste / Enter-below scope). The
        // context above — `<html>` / `<body>` / `<div>` at col 0 — anchors
        // at its ACTUAL indent, so the block lifts relative to `<div>`
        // (opener col 4, body col 8), not to a whole-file re-derived depth.
        val src = "<html>\n<body>\n<div>\n{loop xs}\nbody\n{/}\n</div>\n</body>\n</html>"
        val rangeStart = src.indexOf("{loop xs}")
        val rangeEnd = src.indexOf("{/}") + "{/}".length
        val sb = StringBuilder(src)
        SkyTemplatePostFormatLogic.reindent(
            text = src,
            range = TextRange(rangeStart, rangeEnd),
            indentStep = "    ",
        ) { from, to, replacement ->
            sb.replace(from, to, replacement)
        }
        assertEquals(
            "<html>\n<body>\n<div>\n    {loop xs}\n        body\n    {/}\n</div>\n</body>\n</html>",
            sb.toString(),
        )
    }

    @Test fun computeIndentForLine_lineRightAfterCommentIsNotLeaked() {
        // The `<p>` line right after a comment containing an unbalanced
        // `{loop}` must resolve to the enclosing `<div>` depth, not the
        // leaked loop depth.
        val text = "<div>\n{*\n{loop xs}\n*}\n<p>x</p>\n</div>"
        val lineStart = text.indexOf("<p>x</p>")
        val indent = SkyTemplatePostFormatLogic.computeIndentForLine(text, lineStart, "    ")
        assertEquals("    ", indent)
    }

    @Test fun closerAtLowerIndentThanOpener_isPulledUpToOpenerLevel() {
        // Closer was at col 2, but its matching opener sits at col 4. The
        // one-sided "increase only" rule still fires because col 2 < col 4.
        // This is exactly the Reformat-Code-strips-closer scenario the
        // post-processor exists to fix.
        val before = "    {loop xs}\n<p>body</p>\n  {/}"
        val after = reindent(before)
        assertEquals(
            "    {loop xs}\n        <p>body</p>\n    {/}",
            after,
        )
    }

    @Test fun branchInsideBlock_branchLineUnchangedBodyReindented() {
        val before = "{?cond}\n<p>yes</p>\n{:}\n<p>no</p>\n{/}"
        val after = reindent(before)
        assertEquals(
            "{?cond}\n    <p>yes</p>\n{:}\n    <p>no</p>\n{/}",
            after,
        )
    }

    @Test fun blankBodyLine_isLeftAlone() {
        // Pure-whitespace lines are untouched \u2014 indenting blanks just
        // creates trailing-whitespace noise the host formatter strips on
        // the next pass.
        val before = "{loop xs}\n\n{/}"
        val after = reindent(before)
        assertEquals(before, after)
    }

    @Test fun outsideAnyBlock_lineUnchanged() {
        val before = "<p>plain</p>\n<p>more</p>"
        val after = reindent(before)
        assertEquals(before, after)
    }

    @Test fun unbalancedFile_doesNotCrashAndOnlyTouchesPairedBlocks() {
        // Stray `{/}` with no opener \u2014 partial-template fragment. Nothing
        // on the pair stack, so nothing to re-indent.
        val before = "<p>before</p>\n{/}\n<p>after</p>"
        val after = reindent(before)
        assertEquals(before, after)
    }

    @Test fun tabIndentStep_usesTabs() {
        val before = "{loop xs}\n<p>body</p>\n{/}"
        val after = reindent(before, indentStep = "\t")
        assertEquals("{loop xs}\n\t<p>body</p>\n{/}", after)
    }

    @Test fun mixedTabInExistingIndent_isNotTreatedAsShallowerThanSpaceDesired() {
        // P-BUG-11: the project's indent step is 4 spaces, but this line
        // (leftover from a tab-indented paste, or a `USE_TAB_CHARACTER`
        // file edited under mixed settings) already carries ONE literal
        // tab as its indent — visually equal to one 4-space step, not
        // "1 column" as naive `String.length` would count it. Comparing
        // raw lengths would see `\t` (length 1) as shallower than the
        // desired `"    "` (length 4) and wrongly rewrite it; the
        // tab-aware width must recognise both as the same depth and
        // leave the line alone.
        val before = "{loop xs}\n\t<p>body</p>\n{/}"
        val after = reindent(before, indentStep = "    ")
        assertEquals(before, after)
    }

    @Test fun nonBlockTagsIgnored_rawOutputDoesNotOpen() {
        // `{=foo()}` is not a block opener; following line stays put.
        val before = "{=foo()}\n<p>body</p>"
        val after = reindent(before)
        assertEquals(before, after)
    }

    @Test fun elvisTag_classifiedAsOpen_bodyAndCloserIndentCorrectly() {
        // P-BUG-05: `{?:expr}` (elvis) is a block opener, same pairing
        // semantics as `{if expr}` — the compiler emits its own `{/}`
        // matching. Body must indent one step; `{/}` returns to the
        // opener's level.
        val before = "{?:expr}\nbody\n{/}"
        val after = reindent(before)
        assertEquals("{?:expr}\n    body\n{/}", after)
    }

    @Test fun forgottenInnerClose_outerCloserUnwindsPastUnclosedInnerBlock() {
        // P-BUG-07: user forgot to close the inner `{if b}` — only one
        // `{/}` follows, written at the OUTER opener's indent (col 0),
        // while `{if b}` sits deeper (col 4). Indent-aware unwinding must
        // pop the unclosed inner frame first so the `{/}` pairs with
        // `{if a}` (matching Enter / ClosingTagAligner / FoldingBuilder's
        // unwind rule) and the stack is left EMPTY afterward — `after`
        // must NOT be treated as still inside `{if a}` / `{if b}`.
        val before = "{if a}\n    {if b}\nbody\n{/}\nafter"
        val after = reindent(before)
        assertEquals(
            "{if a}\n    {if b}\n        body\n{/}\nafter",
            after,
        )
    }

    @Test fun closerWithTrailingText_notTreatedAsClose_bodyUnaffected() {
        // P-BUG-06: `{/foo}` is NOT a closer (only `{/}` / `{/ // comment}`
        // are), so it must not pop the `{if}` frame — the real `{/}`
        // below is what closes the block, and the line between stays at
        // body depth (not treated as a two-sided-aligned closer line).
        val before = "{if x}\n{/foo}\nbody\n{/}"
        val after = reindent(before)
        assertEquals("{if x}\n    {/foo}\n    body\n{/}", after)
    }

    @Test fun htmlChildLift_topLevelOpenerInsideDiv_isLifted() {
        // `{?cond}` sits as the first child of `<div>`. The HTML formatter
        // would normally strip it to col 0; the post-format processor lifts
        // back to `<div>.indent + step` so the user's "first child of HTML"
        // intent survives Reformat.
        val before = "<div>\n{?cond}\nbody\n{/}"
        val after = reindent(before)
        assertEquals(
            "<div>\n    {?cond}\n        body\n    {/}",
            after,
        )
    }

    @Test fun htmlChildLift_doesNotApplyToInlineSiblings() {
        // No HTML opening tag immediately above — the line above ends with
        // `</div>`, which is a closing tag. Lift must not fire; the opener
        // stays at its raw col 0.
        val before = "</div>\n{?cond}\nbody\n{/}"
        val after = reindent(before)
        assertEquals(
            "</div>\n{?cond}\n    body\n{/}",
            after,
        )
    }

    @Test fun htmlChildLift_indentDeeperThanLiftIsPreserved() {
        // User typed the opener at col 8 even though `<div>` is at col 0
        // (only suggesting col 4). The one-sided rule keeps the deeper
        // indent — body / closer follow at col 12 / col 8 accordingly.
        val before = "<div>\n        {?cond}\nbody\n        {/}"
        val after = reindent(before)
        assertEquals(
            "<div>\n        {?cond}\n            body\n        {/}",
            after,
        )
    }

    @Test fun htmlAndSkyDeeplyInterleaved_eachLevelContributesOneStep() {
        // Mirrors a real `<table> > <tr> > {@products} > <td> > {?.name}`
        // layout. The Sky-only stack lost the `<td>` level and left
        // `{?.name}` at the same indent as `<td>` (the user-reported
        // bug). The unified stack pushes both HTML and Sky frames, so
        // every level — `<table>`, `<tr>`, `{@products}`, `<td>`,
        // `{?.name}`, `{?.xxx}` — adds one step of indent.
        val before =
            "<table>\n" +
                "<tr>\n" +
                "{@products}\n" +
                "<td>\n" +
                "{?.name}\n" +
                "{.name}\n" +
                "{?.xxx}\n" +
                "sdfsd\n" +
                "{:}\n" +
                "{/}\n" +
                "{/}\n" +
                "</td>\n" +
                "{/}\n" +
                "</tr>\n" +
                "</table>"
        val after = reindent(before)
        assertEquals(
            "<table>\n" +
                "    <tr>\n" +
                "        {@products}\n" +
                "            <td>\n" +
                "                {?.name}\n" +
                "                    {.name}\n" +
                "                    {?.xxx}\n" +
                "                        sdfsd\n" +
                "                    {:}\n" +
                "                    {/}\n" +
                "                {/}\n" +
                "            </td>\n" +
                "        {/}\n" +
                "    </tr>\n" +
                "</table>",
            after,
        )
    }

    @Test fun htmlVoidElementDoesNotPushStack() {
        // `<br>` / `<img>` / `<input>` are HTML5 void — no closer is
        // expected, so the formatter must not push a stack frame for
        // them. A SkyTemplate body following a void HTML element keeps
        // its enclosing-block depth, not depth + 1.
        val before = "{loop xs}\n<br>\nbody\n{/}"
        val after = reindent(before)
        assertEquals(
            "{loop xs}\n    <br>\n    body\n{/}",
            after,
        )
    }

    @Test fun htmlSelfClosingTagDoesNotPushStack() {
        val before = "{loop xs}\n<x/>\nbody\n{/}"
        val after = reindent(before)
        assertEquals(
            "{loop xs}\n    <x/>\n    body\n{/}",
            after,
        )
    }

    // ── JS / CSS context (Sky tags inside <script> / <style>) ─────────────
    //
    // The unified stack walker is content-agnostic — it sees structural
    // tags (HTML / Sky) regardless of whether the surrounding text is
    // HTML body, JS, or CSS. Lines whose FIRST non-whitespace is not a
    // recognised tag stay BODY, so JS object literals like
    // `{ key: "v" }` and CSS rule braces (`.foo { … }`) are treated as
    // body content rather than as Sky / HTML opens.

    @Test fun skyBlockInsideScriptTag_indentsThroughHtmlAndSky() {
        // `{?logged}` sits inside `<script>` (HTML opener at col 0). Its
        // body line `var inside = 1;` indents one step further than the
        // Sky opener — same depth contribution rule as inside `<div>`.
        val before = "<script>\nvar greeting = 1;\n{?logged}\nvar inside = 1;\n{/}\n</script>"
        val after = reindent(before)
        assertEquals(
            "<script>\n" +
                "    var greeting = 1;\n" +
                "    {?logged}\n" +
                "        var inside = 1;\n" +
                "    {/}\n" +
                "</script>",
            after,
        )
    }

    @Test fun skyBlockInsideStyleTag_indentsThroughHtmlAndSky() {
        // CSS `.container { … }` on multiple lines: the opening `{` is
        // at end-of-line (not first non-ws), so the CSS rule's brace
        // does NOT push a stack frame. The HTML formatter is expected
        // to handle CSS rule indentation natively; our pass only adds
        // SkyTemplate awareness on top.
        val before = "<style>\n.container {\n{?dark}\nbackground: black;\n{/}\n}\n</style>"
        val after = reindent(before)
        // `<style>` contributes a step (col 4 for its body lines).
        // `.container {` is body of `<style>` at col 4.
        // `{?dark}` is SKY_OPEN, lifted to col 4 (= style.eff + step).
        // Its body line lands at col 8. The closing `{/}` returns to
        // col 4. The CSS-closing `}` is BODY at col 4.
        assertEquals(
            "<style>\n" +
                "    .container {\n" +
                "    {?dark}\n" +
                "        background: black;\n" +
                "    {/}\n" +
                "    }\n" +
                "</style>",
            after,
        )
    }

    @Test fun jsObjectLiteralOnOwnLine_isTreatedAsBody() {
        // `{ key: "value" }` shaped lines have no Sky markers, so
        // `SkyTemplateRanges` excludes them from the Sky range list.
        // The unified walker then classifies the line as BODY (first
        // non-ws is `{` but not in skyByStart). Stack must NOT push a
        // frame for this line.
        val before = "<script>\nvar obj = { key: \"value\" };\n</script>"
        val after = reindent(before)
        assertEquals(
            "<script>\n    var obj = { key: \"value\" };\n</script>",
            after,
        )
    }

    @Test fun cssRuleBraces_doNotConfuseStack() {
        // CSS rule on a single line — no Sky tags involved. Body inside
        // `<style>` at col 4 (one step deeper than `<style>`).
        val before = "<style>\n.foo { color: red; }\n</style>"
        val after = reindent(before)
        assertEquals(
            "<style>\n    .foo { color: red; }\n</style>",
            after,
        )
    }

    @Test fun jsBlockBracesOnOwnLines_doNotPushStack() {
        // JS function-body braces (`{`, `}`) on their own lines are JS
        // structure, not Sky. Sky range computation excludes them (no
        // Sky markers in the body), so the unified walker treats them
        // as BODY content. The body line inside the JS block keeps the
        // host formatter's deeper indent under the one-sided rule.
        val before = "<script>\nfunction f() {\n      return 1;\n}\n</script>"
        val after = reindent(before)
        assertEquals(
            "<script>\n" +
                "    function f() {\n" +
                "      return 1;\n" +
                "    }\n" +
                "</script>",
            after,
        )
    }

    // ── P2-3: wrapped `<!--{…}-->` directives must classify the same as
    //    their plain `{…}` counterparts (bug was `bodyStart = openOffset + 1`
    //    reading into the `<!--` shell instead of the inner `{…}` body). ──

    @Test fun wrappedCloser_mixedWithPlainOpener_popsFrameAndDoesNotOverIndentFollowingLines() {
        // Before the fix, `<!--{/}-->` classified as OTHER/BODY (first body
        // char read as `!`), so the `{loop}` frame never popped and
        // `<p>after</p>` was pushed one level deeper than it should be.
        val before = "{loop items}\n<li>x</li>\n<!--{/}-->\n<p>after</p>"
        val after = reindent(before)
        assertEquals(
            "{loop items}\n    <li>x</li>\n<!--{/}-->\n<p>after</p>",
            after,
        )
    }

    @Test fun wrappedOnlyBlock_bodyIndentsOneStepUnderOpener() {
        val before = "<!--{loop x}-->\n<li>y</li>\n<!--{/}-->"
        val after = reindent(before)
        assertEquals(
            "<!--{loop x}-->\n    <li>y</li>\n<!--{/}-->",
            after,
        )
    }

    @Test fun wrappedBranch_alignsWithWrappedOpenerLine() {
        val before = "<!--{? c}-->\na\n<!--{:}-->\nb\n<!--{/}-->"
        val after = reindent(before)
        assertEquals(
            "<!--{? c}-->\n    a\n<!--{:}-->\n    b\n<!--{/}-->",
            after,
        )
    }
}
