package com.novaframework.templatelang.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic coverage for [SkyTemplateBlockActionsLogic.computeJoinEdit]
 * and [SkyTemplateBlockActionsLogic.computeSplitEdit]. The action
 * classes themselves are thin shims that call into this logic and
 * apply the resulting [SkyTemplateBlockActionsLogic.Edit] via
 * `WriteCommandAction`.
 *
 * The convention mirrors the other logic tests — the source string
 * carries a `<caret>` marker, which is dropped to obtain the literal
 * text plus caret offset.
 */
class SkyTemplateBlockActionsLogicTest {

    private fun applyJoin(input: String): String? {
        val caret = input.indexOf("<caret>")
        require(caret >= 0)
        val text = input.removeRange(caret, caret + "<caret>".length)
        val edit = SkyTemplateBlockActionsLogic.computeJoinEdit(text, caret) ?: return null
        return text.substring(0, edit.from) + edit.replacement + text.substring(edit.to)
    }

    private fun applySplit(input: String, indentStep: String = "    "): String? {
        val caret = input.indexOf("<caret>")
        require(caret >= 0)
        val text = input.removeRange(caret, caret + "<caret>".length)
        val edit = SkyTemplateBlockActionsLogic.computeSplitEdit(text, caret, indentStep) ?: return null
        return text.substring(0, edit.from) + edit.replacement + text.substring(edit.to)
    }

    // ── Join ──────────────────────────────────────────────────────────────

    @Test fun join_simpleIfBlock_collapsesToOneLine() {
        val out = applyJoin("{?cond}\n    body<caret>\n{/}")
        assertEquals("{?cond}body{/}", out)
    }

    @Test fun join_loopBlock_collapses() {
        val out = applyJoin("{loop xs as x}\n    <li>{=x}</li><caret>\n{/}")
        assertEquals("{loop xs as x}<li>{=x}</li>{/}", out)
    }

    @Test fun join_blockWithBranches_collapsesAllSegments() {
        val out = applyJoin("{?cond}\n    yes<caret>\n{:}\n    no\n{/}")
        assertEquals("{?cond}yes{:}no{/}", out)
    }

    @Test fun join_emptyBody_leavesEmptyButStillCollapses() {
        val out = applyJoin("{?cond}\n    <caret>\n{/}")
        assertEquals("{?cond}{/}", out)
    }

    @Test fun join_caretOnOpenerLine_findsBlock() {
        val out = applyJoin("{?cond}<caret>\n    body\n{/}")
        assertEquals("{?cond}body{/}", out)
    }

    @Test fun join_caretOnCloserLine_findsBlock() {
        val out = applyJoin("{?cond}\n    body\n{/}<caret>")
        assertEquals("{?cond}body{/}", out)
    }

    @Test fun join_alreadyOneLine_returnsNull() {
        // Multi-line check fails — Join is a no-op.
        assertNull(applyJoin("{?cond}body{/}<caret>"))
    }

    @Test fun join_outsideAnyBlock_returnsNull() {
        assertNull(applyJoin("plain text<caret>"))
    }

    @Test fun join_nestedBlocks_targetsInnermost() {
        // Caret inside the inner block — Join collapses ONLY the inner.
        val out = applyJoin("{loop xs}\n    {?cond}\n        body<caret>\n    {/}\n{/}")
        assertEquals("{loop xs}\n    {?cond}body{/}\n{/}", out)
    }

    @Test fun join_preservesInternalSpaces() {
        // The collapse rule trims newlines + their surrounding ws but
        // keeps spaces inside the body content (so `hello world` stays
        // `hello world`, not `helloworld`).
        val out = applyJoin("{?cond}\n    hello world<caret>\n{/}")
        assertEquals("{?cond}hello world{/}", out)
    }

    @Test fun join_wordBoundaryAcrossNewline_insertsSingleSpace() {
        // A newline run bordered by word characters on both sides must
        // become a single space, not disappear — `hello\nworld` should
        // never collapse to `helloworld`.
        val out = applyJoin("{?cond}\n    hello<caret>\n    world\n{/}")
        assertEquals("{?cond}hello world{/}", out)
    }

    @Test fun join_tagAdjacentNewline_staysAdjacent() {
        // A newline run bordered by tag punctuation (non-word) on either
        // side collapses to nothing, so the block stays tag-adjacent.
        val out = applyJoin("{loop xs as x}\n    <li>{=x}</li><caret>\n{/}")
        assertEquals("{loop xs as x}<li>{=x}</li>{/}", out)
    }

    // ── Split ─────────────────────────────────────────────────────────────

    @Test fun split_simpleIfBlock_expandsToThreeLines() {
        val out = applySplit("{?cond}body{/}<caret>")
        assertEquals("{?cond}\n    body\n{/}", out)
    }

    @Test fun split_loopBlock_expands() {
        val out = applySplit("{loop xs as x}<li>{=x}</li>{/}<caret>")
        assertEquals("{loop xs as x}\n    <li>{=x}</li>\n{/}", out)
    }

    @Test fun split_blockWithBranches_eachOnOwnLine() {
        val out = applySplit("{?cond}yes{:}no{/}<caret>")
        assertEquals("{?cond}\n    yes\n{:}\n    no\n{/}", out)
    }

    @Test fun split_indentedOpener_bodyMatchesOpenerLevel() {
        val out = applySplit("    {?cond}body{/}<caret>")
        assertEquals("    {?cond}\n        body\n    {/}", out)
    }

    @Test fun split_emptyBody_returnsNull() {
        // No body text → no transformation needed.
        assertNull(applySplit("{?cond}{/}<caret>"))
    }

    @Test fun split_alreadyMultiLine_returnsNull() {
        assertNull(applySplit("{?cond}\n    body\n{/}<caret>"))
    }

    @Test fun split_outsideAnyBlock_returnsNull() {
        assertNull(applySplit("plain text<caret>"))
    }

    @Test fun split_tabIndentStep_usesTabs() {
        val out = applySplit("{?cond}body{/}<caret>", indentStep = "\t")
        assertEquals("{?cond}\n\tbody\n{/}", out)
    }

    @Test fun split_nestedSingleLineBlock_targetsInnermost() {
        val out = applySplit("{loop xs}\n    {?cond}body{/}<caret>\n{/}")
        assertEquals("{loop xs}\n    {?cond}\n        body\n    {/}\n{/}", out)
    }

    @Test fun findEnclosing_closerWithTrailingText_notTreatedAsCloser() {
        // P-BUG-06: `{/foo}` is not a closer (only `{/}` / `{/ // comment}`
        // are) — the block's real closer is the `{/}` below it, so the
        // enclosing block must span through to that `{/}`, not stop early
        // at `{/foo}`.
        val text = "{?cond}\n    {/foo}\n    body\n{/}"
        val span = SkyTemplateBlockActionsLogic.findEnclosingBlock(text, text.indexOf("body"))
        assertEquals(0, span?.openerStart)
        assertEquals(text.length, span?.closerEnd)
    }

    // ── findEnclosingBlock ────────────────────────────────────────────────

    @Test fun findEnclosing_simpleBlock() {
        val text = "{?cond}\n    body\n{/}"
        val span = SkyTemplateBlockActionsLogic.findEnclosingBlock(text, 12)        // mid-body
        assertEquals(0, span?.openerStart)
        assertEquals(text.length, span?.closerEnd)
    }

    @Test fun findEnclosing_caretOnOpener_includesBlock() {
        val text = "{?cond}\n    body\n{/}"
        val span = SkyTemplateBlockActionsLogic.findEnclosingBlock(text, 7)         // right after `}` of opener
        assertEquals(0, span?.openerStart)
    }

    @Test fun findEnclosing_caretOutsideAnyBlock_isNull() {
        assertNull(SkyTemplateBlockActionsLogic.findEnclosingBlock("plain text", 5))
    }

    @Test fun findEnclosing_orphanCloser_isNull() {
        assertNull(SkyTemplateBlockActionsLogic.findEnclosingBlock("{/}", 1))
    }

    @Test fun findEnclosing_wrappedOpener_pairsWithPlainCloser() {
        // P2-3: a wrapped `<!--{loop x}-->` opener must classify as OPEN so
        // the pairing walk here matches it with the plain `{/}` below —
        // mirrors FoldingScanner's `innerBraceBounds`-based pairing.
        val text = "<!--{loop x}-->\n    body\n{/}"
        val span = SkyTemplateBlockActionsLogic.findEnclosingBlock(text, text.indexOf("body"))
        assertEquals(0, span?.openerStart)
        assertEquals(text.length, span?.closerEnd)
    }

    @Test fun join_wrappedOpener_collapsesWithPlainCloser() {
        val out = applyJoin("<!--{loop x}-->\n    body<caret>\n{/}")
        assertEquals("<!--{loop x}-->body{/}", out)
    }
}
