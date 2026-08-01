package com.novaframework.templatelang.reference

import com.novaframework.templatelang.reference.SkyTemplateRefDetector.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 named-argument coverage for [SkyTemplateRefDetector]. Distinct
 * file from [SkyTemplateRefDetectorTest] so the named-arg suite is
 * easy to invoke in isolation while iterating.
 */
class SkyTemplateRefDetectorNamedArgsTest {

    private fun detect(text: String) = SkyTemplateRefDetector.detect(text)

    // ── paren named arg ────────────────────────────────────────────────────

    @Test fun parenFunction_namedArg_isReportedAsParameterName() {
        val refs = detect("{=foo(name: \$x)}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals("expected one PARAMETER_NAME ref, got $refs", 1, params.size)
        val p = params[0]
        assertEquals("name", p.nameInSource)
        assertEquals("foo", p.callTargetName)
        assertNull("free function should have null callTargetClass", p.callTargetClass)
    }

    @Test fun parenFunction_namedArg_isNOTReportedAsFunction() {
        val refs = detect("{=foo(name: \$x)}")
        val funcs = refs.filter { it.kind == Kind.FUNCTION }
        // `foo` itself stays a FUNCTION; `name` must NOT also become one.
        assertEquals("foo", funcs.single().nameInSource)
    }

    @Test fun parenStaticMethod_namedArg_carriesCallTargetClass() {
        val refs = detect("{=Cls::format(value: \$x, width: 5)}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals(2, params.size)
        assertEquals(setOf("value", "width"), params.map { it.nameInSource }.toSet())
        assertTrue("each param should carry the static-class hint",
            params.all { it.callTargetName == "format" && it.callTargetClass == "Cls" })
    }

    @Test fun parenStaticMethod_namespaced_classCarriesFqnAsWritten() {
        val refs = detect("{=App\\Util::go(label: \$x)}")
        val p = refs.first { it.kind == Kind.PARAMETER_NAME }
        assertEquals("label", p.nameInSource)
        assertEquals("go", p.callTargetName)
        assertEquals("App\\Util", p.callTargetClass)
    }

    @Test fun nestedCall_innerNamedArg_outerStaysFunction() {
        // foo(bar(x: 1)) — `foo`/`bar` are FUNCTION, only `x` is PARAMETER_NAME.
        val refs = detect("{=foo(bar(x: 1))}")
        val funcs = refs.filter { it.kind == Kind.FUNCTION }
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals(setOf("foo", "bar"), funcs.map { it.nameInSource }.toSet())
        assertEquals(1, params.size)
        assertEquals("x", params[0].nameInSource)
        assertEquals("bar", params[0].callTargetName)
    }

    // ── pipe `=` named arg ─────────────────────────────────────────────────

    @Test fun pipeFilter_namedArg_isReportedAsParameterName() {
        val refs = detect("{\$amount|number_format=decimals=2, ##, decimal_separator=.}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals(setOf("decimals", "decimal_separator"), params.map { it.nameInSource }.toSet())
        assertTrue("filter named args use callTargetName=filter, callTargetClass=null",
            params.all { it.callTargetName == "number_format" && it.callTargetClass == null })
    }

    @Test fun pipeFilter_positionalToken_doesNotTriggerParameterName() {
        // Regression: `|sprintf=%05d, ##` is a legacy positional filter.
        // No PARAMETER_NAME refs may be emitted.
        val refs = detect("{\$x|sprintf=%05d, ##}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertTrue("positional filter must not emit PARAMETER_NAME, got $params",
            params.isEmpty())
        // The FUNCTION ref for `sprintf` itself should still be present.
        val funcs = refs.filter { it.kind == Kind.FUNCTION }
        assertEquals(1, funcs.size)
        assertEquals("sprintf", funcs[0].nameInSource)
    }

    @Test fun pipeFilter_chainedFilters_namedArgInOnlyOneStage() {
        // `|trim|nl2br=mode=1` — first stage positional, second stage has named.
        val refs = detect("{\$x|trim|nl2br=mode=1}")
        val funcs = refs.filter { it.kind == Kind.FUNCTION }
        assertEquals(setOf("trim", "nl2br"), funcs.map { it.nameInSource }.toSet())
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals(1, params.size)
        assertEquals("mode", params[0].nameInSource)
        assertEquals("nl2br", params[0].callTargetName)
    }

    // ── regression: ternary / DBL_COLON / property access ──────────────────

    @Test fun ternary_doesNotEmitParameterName() {
        // `{?$cond ? $a : $b}` — `$cond`/`$a`/`$b` are all variables; the
        // colon between `$a` and `$b` is the ternary `:`, not a named-arg.
        val refs = detect("{?\$cond ? \$a : \$b}")
        assertTrue("ternary must not emit PARAMETER_NAME, got $refs",
            refs.none { it.kind == Kind.PARAMETER_NAME })
    }

    @Test fun classConstant_dblColon_unaffected() {
        val refs = detect("{=Cls::CONST}")
        assertTrue("`::` must keep emitting CLASS + CLASS_CONSTANT only",
            refs.any { it.kind == Kind.CLASS } &&
                refs.any { it.kind == Kind.CLASS_CONSTANT } &&
                refs.none { it.kind == Kind.PARAMETER_NAME })
    }

    @Test fun pipeStaticMethod_namedArgPhase1_NOT_supported() {
        // Phase 1 spec D-3: `|Cls::method=name=value` is out-of-scope.
        // The CLASS + METHOD refs still emit, but no PARAMETER_NAME.
        val refs = detect("{var|Cls::method=name=value}")
        assertNotNull(refs.firstOrNull { it.kind == Kind.CLASS })
        assertNotNull(refs.firstOrNull { it.kind == Kind.METHOD })
        assertTrue("pipe Cls::method named arg must NOT emit PARAMETER_NAME",
            refs.none { it.kind == Kind.PARAMETER_NAME })
    }

    @Test fun parenCall_propertyAccess_doesNotConfuseNamedArg() {
        // `{=foo(obj.prop, name: $x)}` — `prop` is a property, only `name`
        // is a PARAMETER_NAME.
        val refs = detect("{=foo(obj.prop, name: \$x)}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals(1, params.size)
        assertEquals("name", params[0].nameInSource)
    }

    // ── PM-requested coverage (2026-05-05) ─────────────────────────────────

    /**
     * Explicit `##` placeholder followed by a named arg in a pipe filter.
     * Compiler reorders to `positional, named` so the placeholder must NOT be
     * lost and `label` must still surface as PARAMETER_NAME on the filter.
     */
    @Test fun pipeFilter_explicitPlaceholderThenNamedArg() {
        val refs = detect("{\$x|filter=##, label=hi}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals(1, params.size)
        assertEquals("label", params[0].nameInSource)
        assertEquals("filter", params[0].callTargetName)
    }

    /**
     * Paren call where a named-arg value contains a nested brace expression.
     * `foo(label: {var})` — IN_TAG context, lexer emits balanced LBRACE/RBRACE.
     * `label` must still be tagged once and the inner `var` must NOT be
     * mistaken for a second PARAMETER_NAME.
     */
    @Test fun parenCall_namedArgValueContainsNestedBrace() {
        val refs = detect("{=foo(label: {var})}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals("only `label` should be PARAMETER_NAME, got $params",
            listOf("label"), params.map { it.nameInSource })
    }

    /**
     * Constant scope `{c.NAME}` must never trigger PARAMETER_NAME even when
     * a colon-shaped tail follows it (defensive — `c.NAME:default` is not
     * valid SkyTemplate syntax today, but the guard prevents future
     * regressions if scope parsing ever broadens).
     */
    @Test fun constantScope_colonTail_doesNotTriggerParameterName() {
        val refs = detect("{c.NAME}")
        assertTrue("`{c.NAME}` should never emit PARAMETER_NAME, got $refs",
            refs.none { it.kind == Kind.PARAMETER_NAME })
        // Sanity: the constant ref itself is still emitted.
        assertNotNull(refs.firstOrNull { it.kind == Kind.CONSTANT })
    }

    /**
     * Ternary inside an argument value — `foo(x: $a ? $b : $c)`. The ternary
     * `:` must not be picked up as a named-arg colon for `$b`. Already
     * indirectly covered by `ternary_doesNotEmitParameterName`, but this case
     * exercises the inside-argument-list path of `scanArgumentList` which is
     * a separate code path from the top-level walker.
     */
    @Test fun parenCall_ternaryInsideValue_doesNotEmitExtraParameterName() {
        val refs = detect("{=foo(x: \$a ? \$b : \$c)}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals("ternary `:` inside value must not double-emit, got $params",
            1, params.size)
        assertEquals("x", params[0].nameInSource)
    }

    /**
     * Ternary with bare identifiers — `foo(x: cond ? a : b)`. Previously the
     * IDENT-COLON branch in `scanArgumentList` lacked the spec-D-1 prev guard
     * and would emit `b` as a PARAMETER_NAME because its next token is `:`.
     * Adding `lastSemanticBefore` rejects `b` since its previous semantic
     * token is the OPERATOR `?` (not `(` / `,`).
     */
    @Test fun parenCall_ternaryBareIdent_doesNotEmitExtraParameterName() {
        val refs = detect("{=foo(x: cond ? a : b)}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals("ternary bare-ident must not double-emit, got $params",
            1, params.size)
        assertEquals("x", params[0].nameInSource)
    }

    /**
     * Mixed: a ternary-valued named arg followed by another named arg. The
     * trailing `y:` must still be detected after the ternary fully closes
     * (its previous semantic token is the COMMA, which the prev guard
     * accepts).
     */
    @Test fun parenCall_namedArgAfterTernaryValuedNamedArg_isDetected() {
        val refs = detect("{=foo(x: \$cond ? \$a : \$b, y: 2)}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
            .map { it.nameInSource }
            .sorted()
        assertEquals("expected x and y only, got $params",
            listOf("x", "y"), params)
    }

    /**
     * Nested ternary inside a named-arg value — only the outer `x:` is a
     * named-arg. The inner `(\$b ? c : d)` must not surface any
     * PARAMETER_NAME refs because every IDENT-COLON inside it is preceded
     * by a non-boundary token.
     */
    @Test fun parenCall_nestedTernaryInValue_doesNotEmitExtraParameterName() {
        val refs = detect("{=foo(x: \$a ? (\$b ? c : d) : e)}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals("nested ternary must not double-emit, got $params",
            1, params.size)
        assertEquals("x", params[0].nameInSource)
    }

    // ── L-003 (Phase 2): pipe filter `==` comparison vs. named-arg ─────────

    /**
     * Pipe filter bucket whose first token is a `==` comparison expression.
     * The leading IDENT-EQ-EQ shape must be recognised as a comparison,
     * NOT a named argument — so no PARAMETER_NAME ref is emitted.
     */
    @Test fun pipeFilter_doubleEqualsComparison_doesNotEmitParameterName() {
        val refs = detect("{\$x|filter=count==2}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertTrue("`count==2` is a comparison, not a named arg; got $params",
            params.isEmpty())
    }

    /** Triple-equals (`a===b`) — same comparison-not-named-arg classification. */
    @Test fun pipeFilter_tripleEqualsComparison_doesNotEmitParameterName() {
        val refs = detect("{\$x|filter=a===b}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertTrue("`a===b` is a comparison, not a named arg; got $params",
            params.isEmpty())
    }

    /**
     * `>=`, `<=`, `!=` style comparisons — the leading-IDENT pattern won't
     * match because the operator's prefix char (`>` / `<` / `!`) breaks the
     * IDENT consumption. This test pins the behaviour against accidental
     * regressions if the scanner ever broadens the IDENT shape.
     */
    @Test fun pipeFilter_geLeNeComparisons_doNotEmitParameterName() {
        for (input in listOf(
            "{\$x|filter=val>=10}",
            "{\$x|filter=val<=10}",
            "{\$x|filter=val!=10}",
            "{\$x|filter=val<>10}",
            "{\$x|filter=a!==b}",
        )) {
            val refs = detect(input)
            val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
            assertTrue("`$input` must NOT emit PARAMETER_NAME, got $params",
                params.isEmpty())
        }
    }

    /** Single `=` named arg still works after L-003 tightening. */
    @Test fun pipeFilter_singleEqualsNamedArg_stillEmitsParameterName() {
        val refs = detect("{\$amount|fmt=decimals=2}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals("expected one PARAMETER_NAME `decimals`, got $params",
            1, params.size)
        assertEquals("decimals", params[0].nameInSource)
        assertEquals("fmt", params[0].callTargetName)
    }

    /**
     * Mixed bucket: a comparison bucket and a regular named-arg bucket.
     * Only the named arg `mode` should surface — the `count==2` bucket is
     * a positional comparison value.
     */
    @Test fun pipeFilter_comparisonAndNamedArg_onlyNamedArgEmits() {
        val refs = detect("{\$x|filter=count==2, mode=strict}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals("expected only `mode`, got $params",
            listOf("mode"), params.map { it.nameInSource })
    }

    // ── P3-7: scanPipeFilterArgs depth tracking ─────────────────────────────

    /**
     * `{x|fmt=foo(a=1)}` — `a=1` is a named arg of the NESTED call `foo(...)`,
     * not of the outer pipe filter `fmt`. Before depth-tracking, the scanner
     * saw `a` immediately followed by `=` regardless of the enclosing `(`
     * and misreported it as a PARAMETER_NAME of `fmt`.
     */
    @Test fun pipeFilter_namedArgInsideNestedCallArgs_notMisreadAsFilterParam() {
        val refs = detect("{\$x|fmt=foo(a=1)}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertTrue("`a=1` belongs to nested `foo(...)`, not `fmt`; got $params",
            params.isEmpty())
    }

    /** A genuine top-level named arg AFTER a nested-call bucket must still surface. */
    @Test fun pipeFilter_namedArgAfterNestedCallBucket_stillDetected() {
        val refs = detect("{\$x|fmt=foo(a=1), label=hi}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertEquals("expected only the top-level `label`, got $params",
            listOf("label"), params.map { it.nameInSource })
        assertEquals("fmt", params[0].callTargetName)
    }

    /** Bracket nesting must also gate depth (array-literal value). */
    @Test fun pipeFilter_bracketNesting_doesNotLeakInnerAssignmentAsNamedArg() {
        val refs = detect("{\$x|fmt=[a=1, b=2]}")
        val params = refs.filter { it.kind == Kind.PARAMETER_NAME }
        assertTrue("bucket inside `[...]` must not surface a PARAMETER_NAME, got $params",
            params.isEmpty())
    }
}
