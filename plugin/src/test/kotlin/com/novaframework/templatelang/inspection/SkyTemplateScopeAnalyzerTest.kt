package com.novaframework.templatelang.inspection

import com.novaframework.templatelang.inspection.SkyTemplateScopeAnalyzer.Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [SkyTemplateScopeAnalyzer]. No PSI fixture needed —
 * the analyser walks raw text via the SkyTemplate lexer.
 *
 * Each test names the issue code(s) expected. The check `assertCodes`
 * asserts on a multi-set of codes regardless of order — many of these
 * inputs intentionally produce multiple issues at once (e.g. `{var@}` is
 * one issue; `{.var@0}` produces one).
 */
class SkyTemplateScopeAnalyzerTest {

    private fun codes(input: String): List<Code> =
        SkyTemplateScopeAnalyzer.analyze(input).map { it.code }

    private fun assertCodes(expected: List<Code>, input: String) {
        val actual = codes(input)
        assertEquals("input was: $input — actual: $actual", expected.sortedBy { it.name }, actual.sortedBy { it.name })
    }

    // ── LOOP_DEPTH_TOO_DEEP ──────────────────────────────────────────────────

    @Test fun singleDotOutsideLoopReportsTooDeep() {
        // `{.name}` outside any loop — required depth 1, current 0.
        assertCodes(listOf(Code.LOOP_DEPTH_TOO_DEEP), "{.name}")
    }

    @Test fun singleDotInsideOneLoopIsValid() {
        // `{.name}` inside a single loop — required 1, current 1. OK.
        assertTrue(codes("{loop xs}{.name}{/}").isEmpty())
    }

    @Test fun doubleDotInsideOneLoopReportsTooDeep() {
        // `{..name}` needs depth 2, only 1 loop is open.
        assertCodes(listOf(Code.LOOP_DEPTH_TOO_DEEP), "{loop xs}{..name}{/}")
    }

    @Test fun doubleDotInsideTwoLoopsIsValid() {
        // `{..name}` needs depth 2, two loops open.
        assertTrue(codes("{loop xs}{loop ys}{..name}{/}{/}").isEmpty())
    }

    @Test fun atTwoInsideOneLoopReportsTooDeep() {
        // `{.name@2}` needs depth 3 (up=2, +1 = 3).
        assertCodes(listOf(Code.LOOP_DEPTH_TOO_DEEP), "{loop xs}{.name@2}{/}")
    }

    @Test fun atOneOnLoopVarInsideTwoLoopsIsValid() {
        // `{.name@1}` needs depth 2.
        assertTrue(codes("{loop xs}{loop ys}{.name@1}{/}{/}").isEmpty())
    }

    @Test fun loopAliasAtIncrementsDepth() {
        // `{@products}` is the SkyTemplate `loop` alias — must increment depth.
        assertTrue(codes("{@products}{.name}{/}").isEmpty())
    }

    // ── RESERVED_OUTSIDE_LOOP ────────────────────────────────────────────────

    @Test fun indexOutsideLoopReportsReservedOutsideLoop() {
        assertCodes(listOf(Code.RESERVED_OUTSIDE_LOOP), "{_index}")
    }

    @Test fun indexInsideLoopIsValid() {
        assertTrue(codes("{loop xs}{_index}{/}").isEmpty())
    }

    @Test fun indexAt2InsideOneLoopReportsTooDeep() {
        // `{_index@2}` needs depth 3.
        assertCodes(listOf(Code.LOOP_DEPTH_TOO_DEEP), "{loop xs}{_index@2}{/}")
    }

    @Test fun indexAt1InsideTwoLoopsIsValid() {
        assertTrue(codes("{loop xs}{loop ys}{_index@1}{/}{/}").isEmpty())
    }

    @Test fun phpSuperglobalsOutsideLoopAreValid() {
        // `_GET`, `_SERVER`, etc. compile through `parseGlobalVar` → `$_GET`,
        // `$_SERVER`. They are not loop-scoped reserved names and must NOT
        // require an enclosing loop, even though the lexer paints them with
        // the reserved-scope colour for highlighting consistency.
        for (name in listOf("_GET", "_POST", "_REQUEST", "_COOKIE", "_SESSION", "_SERVER", "_ENV", "_FILES")) {
            assertTrue("expected no issues for {$name}, got: ${codes("{$name}")}", codes("{$name}").isEmpty())
        }
    }

    @Test fun phpSuperglobalWithPropertyAccessIsValid() {
        // `{? _SERVER.QUERY_STRING}` — superglobal in an `{?}` condition with
        // a sub-key. Both the leading `_SERVER` and the trailing `QUERY_STRING`
        // identifier should pass without warnings.
        assertTrue(codes("{? _SERVER.QUERY_STRING}body{/}").isEmpty())
    }

    @Test fun customUnderscoreGlobalOutsideLoopIsValid() {
        // Arbitrary `_NAME` that isn't a PHP superglobal still falls through
        // to `parseGlobalVar` (`$_D['NAME']`) and requires no loop frame.
        assertTrue(codes("{_FOO}").isEmpty())
    }

    @Test fun atOnSuperglobalReportsRedundant() {
        // `parseGlobalVar` ignores `var_up`, so `{_SERVER@1}` is dead syntax —
        // mirror the existing redundant-@-on-non-loop warning.
        assertCodes(listOf(Code.REDUNDANT_AT_ON_NON_LOOP), "{_SERVER@1}")
    }

    // ── REDUNDANT_AT_ON_NON_LOOP ─────────────────────────────────────────────

    @Test fun atOnNonLoopVarOutsideLoopReportsRedundant() {
        // `{var@}` — `var` has no leading dot and isn't reserved; @ is ignored.
        assertCodes(listOf(Code.REDUNDANT_AT_ON_NON_LOOP), "{var@}")
    }

    @Test fun atDigitOnNonLoopVarReportsRedundant() {
        assertCodes(listOf(Code.REDUNDANT_AT_ON_NON_LOOP), "{var@5}")
    }

    @Test fun atOnLoopVarIsAllowed() {
        // `{.var@}` — bare @ on loop-scope variable. Equivalent to `@1`.
        // No "redundant" warning, but does need depth 2 (up=1) — only 1 loop open.
        assertCodes(listOf(Code.LOOP_DEPTH_TOO_DEEP), "{loop xs}{.var@}{/}")
    }

    @Test fun atOnLoopVarInsideTwoLoopsIsValid() {
        assertTrue(codes("{loop xs}{loop ys}{.var@}{/}{/}").isEmpty())
    }

    @Test fun atOnReservedVarIsAllowed() {
        // `{_index@}` — @ on reserved is fine syntactically. depth check applies.
        // Inside one loop: up=1, depth=1, need >= 2 → too deep.
        assertCodes(listOf(Code.LOOP_DEPTH_TOO_DEEP), "{loop xs}{_index@}{/}")
    }

    // ── REDUNDANT_AT_ZERO ────────────────────────────────────────────────────

    @Test fun atZeroOnLoopVarReportsRedundantZero() {
        // `{.var@0}` — @0 = @1 by compiler. Inside two loops the depth check passes,
        // so only the "redundant zero" warning fires.
        assertCodes(listOf(Code.REDUNDANT_AT_ZERO), "{loop xs}{loop ys}{.var@0}{/}{/}")
    }

    @Test fun atZeroOnReservedReportsRedundantZero() {
        // `{_index@0}` inside two loops — up=1, depth=2, OK. Only the @0 warning.
        assertCodes(listOf(Code.REDUNDANT_AT_ZERO), "{loop xs}{loop ys}{_index@0}{/}{/}")
    }

    // ── DUPLICATE_ELSE ───────────────────────────────────────────────────────

    @Test fun secondElseInIfBlockIsDuplicate() {
        // `{?cond}{:}{:}{/}` — second `{:}` is unreachable.
        assertCodes(listOf(Code.DUPLICATE_ELSE), "{?cond}a{:}b{:}c{/}")
    }

    @Test fun elseifAfterElseIsDuplicate() {
        // `{?cond}{:}{:other}{/}` — `:other` after bare else is also broken.
        assertCodes(listOf(Code.DUPLICATE_ELSE), "{?cond}a{:}b{:other}c{/}")
    }

    @Test fun elseWithOnlyTrailingCommentIsBareElseDuplicate() {
        // `{: // note}` — the compiler strips `\h*//.*$` from the arg BEFORE
        // checking `if ($arg)`, so this is a bare else (empty arg after
        // strip), not a conditional branch. A bare `{:}` already consumed
        // the final-branch slot, so this second one is a duplicate.
        assertCodes(listOf(Code.DUPLICATE_ELSE), "{?a}x{:}y{: // note}z{/}")
    }

    @Test fun multipleElseifAreFine() {
        // `{?a}{:b}{:c}{/}` — chain of elseif, no bare else in between. OK.
        assertTrue(codes("{?a}x{:b}y{:c}z{/}").isEmpty())
    }

    @Test fun singleElseAtEndIsFine() {
        assertTrue(codes("{?a}x{:b}y{:}z{/}").isEmpty())
    }

    @Test fun keywordFormDuplicateElse() {
        assertCodes(listOf(Code.DUPLICATE_ELSE), "{if cond}a{else}b{else}c{/}")
    }

    // ── Combined / regression ────────────────────────────────────────────────

    @Test fun userExampleMultiVarExpressionNoFalsePositive() {
        // `{? !.category.name || !.category.code}` — both `.category` are
        // loop-scope at depth 1. Inside one loop, valid.
        assertTrue(codes("{loop xs}{? !.category.name || !.category.code}body{/}{/}").isEmpty())
    }

    @Test fun bareDotOutsideLoopButAfterAtFlagsBoth() {
        // `{var@}` outside any loop → only the redundant-@ warning (no
        // depth check on plain vars).
        assertCodes(listOf(Code.REDUNDANT_AT_ON_NON_LOOP), "{var@}")
    }

    @Test fun nestedLoopWithMixedAccess() {
        // Two loops, inside check several variables at once.
        val tpl = """
            {loop xs}
              {.x}
              {loop ys}
                {.y}
                {..x}
                {.y@1}
                {.y@2}
              {/}
            {/}
        """.trimIndent()
        // `{.y@2}` requires depth 3, only 2 are open → flagged.
        assertCodes(listOf(Code.LOOP_DEPTH_TOO_DEEP), tpl)
    }

    // ── `{&block}` refer is block-stack-neutral (P2-4) ────────────────────────
    // Compiler's `tagRefer` (SkyTemplateCompiler.php:388-409) never touches
    // `arrBlock` — it's a one-shot include, not an opener. The analyser must
    // not push an IF frame for it, or a following `{/}` pops the WRONG frame.

    @Test fun referBetweenLoopAndItsCloseDoesNotShiftCloseTarget() {
        // `{/}` must close the LOOP opened by `{@rows}`, not a phantom frame
        // pushed for `{&cell}` — so `{_index}` after `{/}` is outside any loop.
        assertCodes(listOf(Code.RESERVED_OUTSIDE_LOOP), "{@rows}{&cell}{/}{_index}")
    }

    @Test fun referBetweenDuplicateElsesStillDetectsDuplicate() {
        // A `{&b}` sitting between the first bare `{:}` and a second one must
        // not swallow the duplicate-else detection by pushing its own frame.
        assertCodes(listOf(Code.DUPLICATE_ELSE), "{?a}x{:}y{&b}{:}z{/}")
    }

    @Test fun referAloneProducesNoIssues() {
        assertTrue(codes("{&block}").isEmpty())
    }

    // ── DOT / ARROW / DBL_COLON trailing identifiers (P2-5) ───────────────────
    // `row._value` / `obj->_key()` compile to a single var_array / method-call
    // expression (`$v0['row']['_value']`, valid at any depth) — the trailing
    // identifier is a property/member name, not an independent variable read,
    // and must not be checked against the loop-scope / reserved-name rules.

    @Test fun dotTrailingReservedNameOutsideLoopIsValid() {
        assertTrue(codes("{row._value}").isEmpty())
    }

    @Test fun arrowTrailingReservedNameOutsideLoopIsValid() {
        assertTrue(codes("{=obj->_key()}").isEmpty())
    }

    @Test fun bareReservedNameOutsideLoopStillReportsReservedOutsideLoop() {
        // Control case — `_index` written standalone (no preceding DOT/ARROW)
        // must still be flagged; the fix only exempts member-access targets.
        assertCodes(listOf(Code.RESERVED_OUTSIDE_LOOP), "{_index}")
    }

    @Test fun dotTrailingIdentifierWithAtOutsideLoopIsValid() {
        // `{user.roles@2}` — `roles` is a property-chain segment; the `@2`
        // suffix on it must not trigger the redundant-`@`-on-non-loop warning.
        assertTrue(codes("{user.roles@2}").isEmpty())
    }

    // ── Escape tag (`{\ … }`) neutralisation ──────────────────────────────────
    // The compiler's `escape` dispatch arm never runs the tag body through
    // `parseVarCommon` — it's emitted as literal `{…}` text. Anything that
    // looks like a loop-scope / reserved variable inside it must not be
    // validated as a real read.

    @Test fun escapeTagBodyOutsideLoopProducesNoIssues() {
        // `{\.price@2}` looks like a loop-scope var with a redundant `@0`-ish
        // depth reference, but it's literal output — no LOOP_DEPTH_TOO_DEEP.
        assertTrue(codes("{\\.price@2}").isEmpty())
    }

    @Test fun escapeTagBodyWithReservedNameProducesNoIssues() {
        assertTrue(codes("{\\_index}").isEmpty())
    }
}
