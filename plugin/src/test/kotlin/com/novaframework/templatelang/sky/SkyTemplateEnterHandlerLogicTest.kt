package com.novaframework.templatelang.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [SkyTemplateEnterHandlerLogic]. The IntelliJ-side
 * handler is a thin shim — every interesting decision lives here.
 */
class SkyTemplateEnterHandlerLogicTest {

    /** Helper: caret marker `<caret>` in [src] is replaced; we get analysis at the position. */
    private fun analyse(src: String): SkyTemplateEnterHandlerLogic.EnterAnalysis? {
        val caret = src.indexOf("<caret>")
        require(caret >= 0) { "test source needs a <caret> marker" }
        val text = src.replace("<caret>", "")
        return SkyTemplateEnterHandlerLogic.analyzeBefore(text, caret)
    }

    @Test fun afterOpenLoop_triggersWithAutoClose() {
        val a = analyse("{loop users as u}<caret>")
        assertNotNull(a)
        assertEquals("", a!!.indent)
        assertTrue(a.needsAutoClose)
    }

    @Test fun afterOpenIf_triggersWithAutoClose() {
        val a = analyse("{if cond}<caret>")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }

    @Test fun afterOpenForeach_triggersWithAutoClose() {
        val a = analyse("{foreach items as it}<caret>")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }

    @Test fun afterPrefixIf_triggersWithAutoClose() {
        val a = analyse("{?cond}<caret>")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }

    @Test fun afterElsePrefix_triggersWithoutAutoClose() {
        // 0.5.18: branches (`{:}`, `{:case}`, `{else}`, `{elseif}`) sit
        // inside an existing block whose `{/}` was already auto-completed
        // when the user typed the original opener. Adding another `{/}`
        // here just duplicates the closer. We still produce the indented
        // blank line for the branch body — only `needsAutoClose` flips.
        val a = analyse("{:}<caret>")
        assertNotNull(a)
        assertFalse(a!!.needsAutoClose)
    }

    @Test fun afterElseKeyword_triggersWithoutAutoClose() {
        val a = analyse("{else}<caret>")
        assertNotNull(a)
        assertFalse(a!!.needsAutoClose)
    }

    @Test fun afterElseifKeyword_triggersWithoutAutoClose() {
        val a = analyse("{elseif x > 0}<caret>")
        assertNotNull(a)
        assertFalse(a!!.needsAutoClose)
    }

    @Test fun afterColonCase_triggersWithoutAutoClose() {
        // `{:case1}` (switch case branch).
        val a = analyse("{:case1}<caret>")
        assertNotNull(a)
        assertFalse(a!!.needsAutoClose)
    }

    @Test fun afterPrefixLoopAlias_triggers() {
        val a = analyse("{@id}<caret>")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }

    @Test fun afterCloseSlashTag_doesNotTrigger() {
        // `{/}` is a closing tag — never an opener.
        assertNull(analyse("{loop x}\n  body\n{/}<caret>"))
    }

    @Test fun afterEndKeyword_doesNotTrigger() {
        assertNull(analyse("{loop x}\n  body\n{end}<caret>"))
    }

    @Test fun afterRawOutput_doesNotTrigger() {
        assertNull(analyse("{=foo()}<caret>"))
    }

    @Test fun afterBareVariable_doesNotTrigger() {
        assertNull(analyse("{name}<caret>"))
    }

    @Test fun afterConstantReference_doesNotTrigger() {
        assertNull(analyse("{c.NAME}<caret>"))
    }

    @Test fun caretInsideTag_doesNotTrigger() {
        // Caret not after `}`.
        assertNull(analyse("{loop x<caret>"))
    }

    @Test fun caretAfterPlainText_doesNotTrigger() {
        assertNull(analyse("hello<caret>"))
    }

    @Test fun sameLineHasSlashCloser_suppressesAutoClose() {
        val a = analyse("{loop x}<caret>{/}")
        assertNotNull(a)
        assertFalse(a!!.needsAutoClose)
    }

    @Test fun sameLineHasEndKeyword_suppressesAutoClose() {
        val a = analyse("{loop x}<caret>{end}")
        assertNotNull(a)
        assertFalse(a!!.needsAutoClose)
    }

    @Test fun indentPreservedSpaces() {
        val a = analyse("    {if x}<caret>")
        assertNotNull(a)
        assertEquals("    ", a!!.indent)
    }

    @Test fun indentPreservedTab() {
        val a = analyse("\t{loop x}<caret>")
        assertNotNull(a)
        assertEquals("\t", a!!.indent)
    }

    @Test fun indentRelativeToOpenerLine_notCaretLine() {
        // The caret sits at the end of the opener line — there's only one line.
        // But the indent is computed from the opener line's start, which is
        // where `{` is — so leading whitespace before `{` is what we capture.
        val a = analyse("  \t{loop x}<caret>")
        assertNotNull(a)
        assertEquals("  \t", a!!.indent)
    }

    @Test fun openerInsideComment_doesNotTrigger() {
        // `{if x}` appears inside a `{*…*}` comment — never a trigger.
        assertNull(analyse("{* {if x} *}<caret>"))
    }

    @Test fun openerWithLeadingSpaceInsideTag_keywordRejected() {
        // SkyTemplate requires no whitespace between `{` and a keyword. The
        // lexer / range filter both enforce that, so `{ if x }` is treated
        // as a variable form and should NOT trigger an auto-close.
        assertNull(analyse("{ if x }<caret>"))
    }

    @Test fun openerWithLeadingSpacePrefix_isAccepted() {
        // Prefix forms allow leading horizontal whitespace (Template_ permissive).
        val a = analyse("{ ?cond}<caret>")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }

    @Test fun standaloneSwitchCase_doesNotTrigger() {
        // `{?:case}` (Template_ switch case) is a branch inside an open
        // block, not a fresh opener.
        assertNull(analyse("{?:case}<caret>"))
    }

    @Test fun multipleTagsOnLine_lastOpenerWins() {
        // `{if a}{loop x}` on one line — caret right after the loop's `}` —
        // should treat `{loop x}` as the relevant opener.
        val a = analyse("{if a}{loop x}<caret>")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }

    @Test fun caretBeyondTextLength_returnsNull() {
        assertNull(SkyTemplateEnterHandlerLogic.analyzeBefore("{loop x}", 999))
    }

    @Test fun caretAtZero_returnsNull() {
        assertNull(SkyTemplateEnterHandlerLogic.analyzeBefore("{loop x}", 0))
    }

    // —— Indent-aware pairing suppression ——
    //
    // Auto-close fires when the just-typed opener has no matching closer
    // under LIFO + indent-unwinding pairing (the same rule inspections /
    // folding use). A `{/}` that exists in the file but pairs with some
    // OTHER opener — or is at outer indent than the new opener — still
    // leaves the new opener unpaired, so a fresh `{/}` is inserted.

    @Test fun existingDownstreamCloser_suppressesAutoClose_prefixIf() {
        // `{?foo}` pairs with the downstream `{/}` → suppress.
        val a = analyse("{?foo}<caret>\n  body\n{/}")
        assertNotNull(a)
        assertFalse(a!!.needsAutoClose)
    }

    @Test fun existingDownstreamCloser_suppressesAutoClose_prefixAt() {
        val a = analyse("{@items}<caret>\n  body\n{/}")
        assertNotNull(a)
        assertFalse(a!!.needsAutoClose)
    }

    @Test fun existingDownstreamEndKeyword_suppressesAutoClose() {
        val a = analyse("{loop xs}<caret>\n  body\n{end}")
        assertNotNull(a)
        assertFalse(a!!.needsAutoClose)
    }

    @Test fun upstreamCloserDoesNotMatch_addsCloser() {
        // The `{/}` above the caret has nothing to pair with at file
        // level — it is an orphan close of a parent fragment. The new
        // `{?cond}` typed below it has no matching closer, so insert.
        val a = analyse("{:case red}\n  red\n{/}\n{?cond}<caret>")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }

    @Test fun balancedUpstreamBlockPlusNewOpener_addsCloser() {
        // The upstream `{loop xs}…{/}` pair is already balanced; the
        // new `{?foo}` below has no closer → insert.
        val a = analyse("{loop xs}\n  body\n{/}\n{?foo}<caret>")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }

    @Test fun nestedOpenerInsideAlreadyClosedBlock_addsCloser() {
        // Indent-unwinding: the outer `{/}` (col 0) pops the deeper inner
        // `{?foo}` (col 2) as unpaired before pairing with `{loop xs}`,
        // so the freshly typed `{?foo}` needs its own closer.
        val a = analyse("{loop xs}\n  {?foo}<caret>\n  body\n{/}")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }

    @Test fun openerBeforeDifferentBlock_addsCloser() {
        // `{?foo}` is unpaired (the downstream `{/}` matches the inner
        // `{loop xs}`), so insert.
        val a = analyse("{?foo}<caret>\nplain\n{loop xs}\n  body\n{/}")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }

    @Test fun closerAtOuterIndentThanOpener_addsCloser() {
        // `{?foo}` is at col 4 but the only downstream `{/}` is at col 0,
        // so it cannot logically own the new opener. Indent-unwinding
        // marks `{?foo}` unpaired → insert.
        val a = analyse("    {?foo}<caret>\n  body\n{/}")
        assertNotNull(a)
        assertTrue(a!!.needsAutoClose)
    }
}
