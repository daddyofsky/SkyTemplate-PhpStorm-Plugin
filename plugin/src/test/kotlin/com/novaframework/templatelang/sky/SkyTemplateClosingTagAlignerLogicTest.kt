package com.novaframework.templatelang.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic coverage for [SkyTemplateClosingTagAlignerLogic.computeAlignment].
 *
 * Each test sets up a `<caret>` marker in the source string, drops the
 * marker to obtain the literal text plus caret offset, and asserts the
 * computed re-indent edit (or its absence). The convention mirrors the
 * Enter handler logic test — pure-text input, no IDE fixture.
 */
class SkyTemplateClosingTagAlignerLogicTest {

    private fun computeFromCaret(input: String): SkyTemplateClosingTagAlignerLogic.Edit? {
        val caret = input.indexOf("<caret>")
        require(caret >= 0) { "Test source must include `<caret>` marker." }
        val text = input.removeRange(caret, caret + "<caret>".length)
        return SkyTemplateClosingTagAlignerLogic.computeAlignment(text, caret)
    }

    private fun applied(input: String): String {
        val caret = input.indexOf("<caret>")
        val text = input.removeRange(caret, caret + "<caret>".length)
        val edit = SkyTemplateClosingTagAlignerLogic.computeAlignment(text, caret) ?: return text
        return text.substring(0, edit.from) + edit.replacement + text.substring(edit.to)
    }

    // —— Branch alignment ——

    @Test fun branch_inIfBlock_alignsToOpenerIndent() {
        val out = applied("{?cond}\n    body\n    {:}<caret>")
        assertEquals("{?cond}\n    body\n{:}", out)
    }

    @Test fun branch_inAtBlock_alignsToOpenerIndent() {
        val out = applied("{@items}\n    body\n    {:}<caret>")
        assertEquals("{@items}\n    body\n{:}", out)
    }

    @Test fun branch_elseKeyword_aligns() {
        val out = applied("{if x}\n    body\n        {else}<caret>")
        assertEquals("{if x}\n    body\n{else}", out)
    }

    @Test fun branch_elseifKeyword_aligns() {
        val out = applied("{if x}\n    body\n    {elseif y}<caret>")
        assertEquals("{if x}\n    body\n{elseif y}", out)
    }

    @Test fun branch_caseExpr_aligns() {
        val out = applied("{?val:case}\n        body\n        {:case red}<caret>")
        assertEquals("{?val:case}\n        body\n{:case red}", out)
    }

    // —— Closer alignment ——

    @Test fun closer_slashOnly_alignsToInnermostOpener() {
        val out = applied("{?cond}\n    body\n    {/}<caret>")
        assertEquals("{?cond}\n    body\n{/}", out)
    }

    @Test fun closer_endKeyword_aligns() {
        val out = applied("{loop xs}\n    body\n        {end}<caret>")
        assertEquals("{loop xs}\n    body\n{end}", out)
    }

    @Test fun closer_inNestedBlocks_alignsToInnermostByDefault() {
        // User typed `{/}` at col 8, deepest enclosing is `{?inner}` at col 4 →
        // align to col 4 (innermost LIFO match).
        val out = applied("{loop xs}\n    {?inner}\n        body\n        {/}<caret>")
        assertEquals("{loop xs}\n    {?inner}\n        body\n    {/}", out)
    }

    @Test fun closer_atColZero_unwindsToOutermostOpener() {
        // User typed `{/}` at col 0 inside nested blocks — indent-unwinding
        // takes us back past the inner block to the outermost opener.
        val out = applied("{loop xs}\n    {?inner}\n        body\n{/}<caret>")
        assertEquals("{loop xs}\n    {?inner}\n        body\n{/}", out)
    }

    // —— No-op cases ——

    @Test fun openerTag_noAlignmentTriggered() {
        // `{?cond}` is an opener — never realigned, regardless of indent.
        assertNull(computeFromCaret("{?cond}<caret>"))
    }

    @Test fun nonBlockTag_noAlignmentTriggered() {
        assertNull(computeFromCaret("    {=foo()}<caret>"))
    }

    @Test fun inlineCloser_noAlignment() {
        // `{/}` is not at line start (preceded by content) → not realigned.
        assertNull(computeFromCaret("{?cond}body{/}<caret>"))
    }

    @Test fun closerWithTrailingContent_noAlignment() {
        // `{/}` is at line start but has content after → not realigned.
        assertNull(computeFromCaret("{?cond}\n  body\n  {/}<caret>x"))
    }

    @Test fun closerTagWithTrailingTextInBody_notClassifiedAsCloser_noAlignment() {
        // P-BUG-06: `{/foo}` has trailing text INSIDE the tag body — only
        // `{/}` / `{/ // comment}` are closers. `{/foo}` must not be
        // realigned as if it were the block's closer.
        assertNull(computeFromCaret("{?cond}\n  body\n  {/foo}<caret>"))
    }

    @Test fun orphanCloser_noAlignment() {
        // No matching opener → leave as the user typed.
        assertNull(computeFromCaret("    {/}<caret>"))
    }

    @Test fun orphanBranch_noAlignment() {
        assertNull(computeFromCaret("    {:}<caret>"))
    }

    @Test fun alreadyAligned_noEdit() {
        // Branch already at opener indent → no edit needed.
        assertNull(computeFromCaret("{?cond}\n    body\n{:}<caret>"))
    }

    @Test fun caretNotAfterBrace_noEdit() {
        // Caret follows other text → handler should NOT have fired in
        // practice, but the pure logic guards against it anyway.
        assertNull(computeFromCaret("{?cond}\n    body\n    {/}x<caret>"))
    }

    @Test fun closer_matchesWrappedOpener_alignsToWrappedOpenerIndent() {
        // P2-3: the opener-search walk must classify a wrapped
        // `<!--{loop x}-->` opener the same as its plain form so a plain
        // `{/}` typed below it still finds its match and aligns.
        val out = applied("<!--{loop x}-->\n    {/}<caret>")
        assertEquals("<!--{loop x}-->\n{/}", out)
    }
}
