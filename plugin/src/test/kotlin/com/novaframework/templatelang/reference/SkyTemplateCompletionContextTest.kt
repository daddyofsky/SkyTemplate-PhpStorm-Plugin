package com.novaframework.templatelang.reference

import com.novaframework.templatelang.reference.SkyTemplateCompletionContext.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for the completion context analyzer. Each test sets up a
 * `text` string with a `|` marker indicating the caret position (the `|` is
 * stripped before the call), then asserts what context the analyzer infers.
 *
 * The analyzer is the brain behind [SkyTemplateCompletionContributor]; getting
 * the context right is what makes the difference between offering functions,
 * constants, or class members at typing time.
 */
class SkyTemplateCompletionContextTest {

    /** Strips a `‸` caret marker and returns (textWithoutMarker, caretOffset). */
    private fun atCaret(s: String): Pair<String, Int> {
        val idx = s.indexOf('‸')
        require(idx >= 0) { "expected `‸` caret marker in: $s" }
        return s.removeRange(idx, idx + 1) to idx
    }

    private fun infer(s: String): Result? {
        val (text, caret) = atCaret(s)
        return SkyTemplateCompletionContext.infer(text, caret)
    }

    // ── Function (with parens) ──────────────────────────────────────────────

    @Test fun expressionPrefix_emit_offersFunctionWithParens() {
        assertEquals(Result.Function(withParens = true), infer("{=‸"))
        assertEquals(Result.Function(withParens = true), infer("{=fo‸"))
    }

    @Test fun expressionPrefix_if_offersFunctionWithParens() {
        assertEquals(Result.Function(withParens = true), infer("{?‸"))
        assertEquals(Result.Function(withParens = true), infer("{?cond‸"))
    }

    @Test fun expressionPrefix_else_elvis_php_offersFunctionWithParens() {
        assertEquals(Result.Function(withParens = true), infer("{:‸"))
        assertEquals(Result.Function(withParens = true), infer("{?:‸"))
        assertEquals(Result.Function(withParens = true), infer("{;‸"))
    }

    @Test fun keywordExpression_offersFunctionWithParens() {
        assertEquals(Result.Function(withParens = true), infer("{if ‸"))
        assertEquals(Result.Function(withParens = true), infer("{foreach ‸"))
        assertEquals(Result.Function(withParens = true), infer("{while ‸"))
        assertEquals(Result.Function(withParens = true), infer("{loop ‸"))
        assertEquals(Result.Function(withParens = true), infer("{each ‸"))
    }

    @Test fun keywordExpression_isCaseInsensitive() {
        assertEquals(Result.Function(withParens = true), infer("{IF ‸"))
        assertEquals(Result.Function(withParens = true), infer("{Foreach ‸"))
    }

    // ── Function (no parens — pipe form) ────────────────────────────────────

    @Test fun pipeFilter_offersFunctionWithoutParens() {
        assertEquals(Result.Function(withParens = false), infer("{var|‸"))
        assertEquals(Result.Function(withParens = false), infer("{var|tri‸"))
    }

    @Test fun pipeChain_stillOffersFunctionWithoutParens() {
        assertEquals(Result.Function(withParens = false), infer("{var|trim|‸"))
        assertEquals(Result.Function(withParens = false), infer("{var|trim|nl‸"))
    }

    // ── Constant ────────────────────────────────────────────────────────────

    @Test fun cScope_offersConstants() {
        assertEquals(Result.Constant, infer("{c.‸"))
        assertEquals(Result.Constant, infer("{c.MY‸"))
    }

    // ── Class member (`Cls::`) ──────────────────────────────────────────────

    @Test fun classMember_inExpressionContext_offersWithParens() {
        val r = infer("{=Cls::‸") as Result.ClassMember
        assertEquals("Cls", r.classNameInSource)
        assertEquals(true, r.withMethodParens)
        assertEquals(false, r.constantsOnly)
    }

    @Test fun classMember_partiallyTypedMember_keepsClassQualifier() {
        val r = infer("{=Cls::met‸") as Result.ClassMember
        assertEquals("Cls", r.classNameInSource)
        assertEquals(true, r.withMethodParens)
    }

    @Test fun classMember_namespaceQualified_preservesBackslashes() {
        val r = infer("{=Ns\\Cls::‸") as Result.ClassMember
        assertEquals("Ns\\Cls", r.classNameInSource)
    }

    @Test fun classMember_absoluteFqn_keepsLeadingBackslash() {
        val r = infer("{=\\Ns\\Cls::‸") as Result.ClassMember
        assertEquals("\\Ns\\Cls", r.classNameInSource)
    }

    @Test fun classMember_inPipeContext_omitsParens() {
        val r = infer("{var|Cls::‸") as Result.ClassMember
        assertEquals("Cls", r.classNameInSource)
        assertEquals(false, r.withMethodParens)
        assertEquals(false, r.constantsOnly)
    }

    @Test fun classMember_inPipeContext_namespacedQualifier() {
        val r = infer("{var|Enums\\Test::‸") as Result.ClassMember
        assertEquals("Enums\\Test", r.classNameInSource)
        assertEquals(false, r.withMethodParens)
    }

    @Test fun classMember_under_cScope_offersOnlyConstants() {
        val r = infer("{c.Cls::‸") as Result.ClassMember
        assertEquals("Cls", r.classNameInSource)
        assertEquals(true, r.constantsOnly)
        // withMethodParens is irrelevant under constantsOnly but should still
        // reflect "expression-like" context (no pipe).
        assertEquals(true, r.withMethodParens)
    }

    @Test fun classMember_under_cScope_namespacedClass() {
        val r = infer("{c.App\\Enums::TYPE_‸") as Result.ClassMember
        assertEquals("App\\Enums", r.classNameInSource)
        assertEquals(true, r.constantsOnly)
    }

    @Test fun classMember_insideArgList_stillTriggers() {
        // `{=foo(Cls::, x)}` — caret right after `Cls::` inside an arg list.
        val r = infer("{=foo(Cls::‸") as Result.ClassMember
        assertEquals("Cls", r.classNameInSource)
        assertEquals(true, r.withMethodParens)
    }

    // ── No suggestion ───────────────────────────────────────────────────────

    @Test fun outsideTemplateTag_returnsNull() {
        assertNull(infer("plain html ‸"))
        assertNull(infer("<p>just text‸</p>"))
    }

    @Test fun afterClosingBrace_returnsNull() {
        // Caret after a closed `{=foo()}` — back-walk hits `}` and aborts.
        assertNull(infer("{=foo()} ‸"))
    }

    @Test fun jsTemplateLiteral_returnsNull() {
        // `${foo}` is JS, not a SkyTemplate tag — `$` before `{` blocks.
        assertNull(infer("`\${name‸"))
    }

    @Test fun caretInsideLineBreak_returnsNull() {
        // Back-walk stops at newline.
        assertNull(infer("{=foo\n‸"))
    }

    @Test fun emptyBody_returnsNull() {
        assertNull(infer("{‸"))
    }

    @Test fun unrecognisedFirstChar_returnsNull() {
        assertNull(infer("{<‸"))
    }

    @Test fun bareIdentifier_returnsNull() {
        // `{nam‸` — variable form, no PHP symbol completion offered.
        assertNull(infer("{nam‸"))
    }

    // ── Edge cases on `::` parsing ──────────────────────────────────────────

    @Test fun standaloneDoubleColon_withNoQualifier_returnsNull() {
        // `{::‸` has no identifier before the `::` — fall through.
        assertNull(infer("{::‸"))
    }

    @Test fun whitespaceBetweenQualifierAndDoubleColon_isTolerated() {
        val r = infer("{=Cls ::‸") as Result.ClassMember
        assertEquals("Cls", r.classNameInSource)
    }
}
