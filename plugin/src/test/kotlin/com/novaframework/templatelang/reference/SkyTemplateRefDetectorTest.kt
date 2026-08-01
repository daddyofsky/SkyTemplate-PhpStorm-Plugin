package com.novaframework.templatelang.reference

import com.novaframework.templatelang.reference.SkyTemplateRefDetector.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyTemplateRefDetectorTest {

    private fun detect(text: String) = SkyTemplateRefDetector.detect(text)

    // ── functions in expression-context tags ─────────────────────────────────

    @Test fun functionCall_rawPrefix() {
        // {=foo()} — raw output of function call (the canonical SkyTemplate form)
        val refs = detect("{=foo()}")
        assertEquals(1, refs.size)
        assertEquals(Kind.FUNCTION, refs[0].kind)
        assertEquals("foo", refs[0].nameInSource)
        // Range covers just `foo` (after `{=`, before `(`)
        assertEquals(2, refs[0].rangeInHost.startOffset)
        assertEquals(5, refs[0].rangeInHost.endOffset)
    }

    @Test fun functionCall_ifPrefix() {
        // {?foo()} — if condition
        val refs = detect("{?foo()}")
        assertEquals(1, refs.size)
        assertEquals(Kind.FUNCTION, refs[0].kind)
        assertEquals("foo", refs[0].nameInSource)
    }

    @Test fun functionCall_phpPrefix() {
        // {;foo()} — php expression
        val refs = detect("{;foo()}")
        assertEquals(1, refs.size)
        assertEquals(Kind.FUNCTION, refs[0].kind)
    }

    @Test fun functionCall_inIfKeyword() {
        // {if foo()}
        val refs = detect("{if foo()}")
        assertEquals(1, refs.size)
        assertEquals(Kind.FUNCTION, refs[0].kind)
        assertEquals("foo", refs[0].nameInSource)
    }

    @Test fun functionCall_absoluteFqn_inRawPrefix() {
        // {=\App\Util\fmt()}
        val refs = detect("{=\\App\\Util\\fmt()}")
        assertEquals(1, refs.size)
        assertEquals(Kind.FUNCTION, refs[0].kind)
        assertEquals("\\App\\Util\\fmt", refs[0].nameInSource)
    }

    @Test fun functionCall_absoluteFqn_NOT_detectedWithoutExpressionPrefix() {
        // {\App\Util\fmt()} — leading `\` is the escape directive in
        // SkyTemplate, NOT an expression context. Authors must write
        // `{=\App\Util\fmt()}` (or `{?…}` etc.) to invoke a namespaced
        // function.
        assertTrue(detect("{\\App\\Util\\fmt()}").isEmpty())
    }

    @Test fun escapeDirective_keywordForm_doesNotEmitRefs() {
        // {escape}foo(){/} — keyword form of the escape directive. The body
        // is opaque (not an expression context), so no PHP refs are emitted.
        // T4 / 0.5.13 regression.
        assertTrue(detect("{escape}foo(){/}").isEmpty())
    }

    @Test fun escapeLiteralBare_emitsNoRefs() {
        // {\} alone — the escape literal. No identifiers in the body, so
        // trivially zero refs. T4 / 0.5.13 regression.
        assertTrue(detect("{\\}").isEmpty())
    }

    @Test fun functionCall_nestedInsideArgs() {
        // {=outer(inner())}
        val refs = detect("{=outer(inner())}")
        assertEquals(2, refs.size)
        assertEquals(setOf("outer", "inner"), refs.map { it.nameInSource }.toSet())
        assertTrue(refs.all { it.kind == Kind.FUNCTION })
    }

    @Test fun functionCall_concatBetweenTwoCalls_rawPrefix() {
        // `{=foo() . bar()}` — string concat between two call results. Both
        // sides are independent function calls; both must resolve. The lexer's
        // whitespace-adjacency rule emits `.` as OPERATOR (not DOT) so `bar`
        // is NOT skipped as a property/method on `foo()` result.
        val refs = detect("{=foo() . bar()}")
        assertEquals(2, refs.size)
        assertEquals(setOf("foo", "bar"), refs.map { it.nameInSource }.toSet())
        assertTrue(refs.all { it.kind == Kind.FUNCTION })
    }

    @Test fun functionCall_concatBetweenTwoCalls_ifPrefix() {
        // `{? foo() . bar()}` — same in if-condition expression context.
        val refs = detect("{? foo() . bar()}")
        assertEquals(2, refs.size)
        assertEquals(setOf("foo", "bar"), refs.map { it.nameInSource }.toSet())
        assertTrue(refs.all { it.kind == Kind.FUNCTION })
    }

    @Test fun propertyAccess_onCallResult_tightDotKeepsSingleRef() {
        // `{=fn().foo}` — tight dot is property access on call result.
        // Only `fn` is a function ref; `foo` is a runtime property and
        // must NOT be reported as a separate function (regression guard
        // for the dot-classification rule).
        val refs = detect("{=fn().foo}")
        assertEquals(1, refs.size)
        assertEquals("fn", refs[0].nameInSource)
        assertEquals(Kind.FUNCTION, refs[0].kind)
    }

    @Test fun plainBraceFunctionCall_NOT_detected() {
        // {foo()} — plain, no tag prefix. SkyTemplate compiler doesn't treat
        // this as a function call; we shouldn't either.
        val refs = detect("{foo()}")
        assertTrue(refs.isEmpty())
    }

    // ── classes / methods / class constants in expression context ────────────

    @Test fun staticMethod_inRawPrefix() {
        val refs = detect("{=Cls::method()}")
        assertEquals(2, refs.size)
        val cls = refs.first { it.kind == Kind.CLASS }
        val method = refs.first { it.kind == Kind.METHOD }
        assertEquals("Cls", cls.nameInSource)
        assertEquals("method", method.nameInSource)
        assertEquals("Cls", method.classNameInSource)
    }

    @Test fun staticMethod_namespaced_inRawPrefix() {
        val refs = detect("{=\\App\\Cls::method()}")
        val cls = refs.first { it.kind == Kind.CLASS }
        val method = refs.first { it.kind == Kind.METHOD }
        assertEquals("\\App\\Cls", cls.nameInSource)
        assertEquals("\\App\\Cls", method.classNameInSource)
        assertEquals("method", method.nameInSource)
    }

    @Test fun classConstant_inRawPrefix() {
        val refs = detect("{=Cls::CONST}")
        assertEquals(2, refs.size)
        val constant = refs.first { it.kind == Kind.CLASS_CONSTANT }
        assertEquals("CONST", constant.nameInSource)
        assertEquals("Cls", constant.classNameInSource)
    }

    @Test fun plainBraceStaticMethod_NOT_detected() {
        assertTrue(detect("{Cls::method()}").isEmpty())
    }

    // ── pipe filters ─────────────────────────────────────────────────────────

    @Test fun pipeFunction_simple() {
        val refs = detect("{var|trim}")
        assertEquals(1, refs.size)
        assertEquals(Kind.FUNCTION, refs[0].kind)
        assertEquals("trim", refs[0].nameInSource)
    }

    @Test fun pipeFunction_withEqualsArgs() {
        val refs = detect("{var|sprintf=%05d, ##}")
        val funcs = refs.filter { it.kind == Kind.FUNCTION }
        assertEquals(1, funcs.size)
        assertEquals("sprintf", funcs[0].nameInSource)
    }

    @Test fun pipeFunction_chained() {
        val refs = detect("{var|trim|nl2br}")
        val funcs = refs.filter { it.kind == Kind.FUNCTION }
        assertEquals(2, funcs.size)
        assertEquals(setOf("trim", "nl2br"), funcs.map { it.nameInSource }.toSet())
    }

    @Test fun pipeFunction_classMethod() {
        // {var|Cls::method} — pipe through static method, no parens.
        val refs = detect("{var|Cls::method}")
        assertEquals(2, refs.size)
        val cls = refs.first { it.kind == Kind.CLASS }
        val method = refs.first { it.kind == Kind.METHOD }
        assertEquals("Cls", cls.nameInSource)
        assertEquals("method", method.nameInSource)
        assertEquals("Cls", method.classNameInSource)
    }

    @Test fun pipeFunction_namespacedClassMethod() {
        // {var|Enums\Test::getName}
        val refs = detect("{var|Enums\\Test::getName}")
        assertEquals(2, refs.size)
        val cls = refs.first { it.kind == Kind.CLASS }
        val method = refs.first { it.kind == Kind.METHOD }
        assertEquals("Enums\\Test", cls.nameInSource)
        assertEquals("getName", method.nameInSource)
        assertEquals("Enums\\Test", method.classNameInSource)
    }

    @Test fun pipeFunction_absoluteFqnClassMethod_failsCompilerGate() {
        // Compiler gate (SkyTemplateCompiler::parseFunction) tests the WHOLE
        // `$func` string against `/^[a-z][\w\\:]*$/i` — a LEADING backslash
        // (absolute FQN) fails the `[a-z]` first-char requirement, so
        // `{var|\App\Util::fmt}` is a no-op filter at runtime (skipped via
        // `continue`), not a call to `Util::fmt`. No refs must be emitted —
        // emitting CLASS/METHOD here would be a ghost navigation target.
        val refs = detect("{var|\\App\\Util::fmt}")
        assertTrue("gate-failing absolute-FQN pipe filter must emit no refs, got $refs",
            refs.isEmpty())
    }

    @Test fun pipeFunction_flaggedAsPipeFilter() {
        // Pipe filter names dispatch formatter-method-first in the compiler
        // (`method_exists($formatter, $func)` → `_F::func(...)`), so the ref
        // must carry the pipe marker for the resolver.
        val refs = detect("{var|trim}")
        assertEquals(1, refs.size)
        assertTrue(refs[0].isPipeFilter)
    }

    @Test fun pipeFunction_chained_bothFlaggedAsPipeFilter() {
        val funcs = detect("{var|trim|nl2br}").filter { it.kind == Kind.FUNCTION }
        assertEquals(2, funcs.size)
        assertTrue(funcs.all { it.isPipeFilter })
    }

    @Test fun pipeNamedArg_flaggedAsPipeFilter() {
        // {var|fmt=digits=2} — the PARAMETER_NAME ref belongs to the pipe
        // callee, which may be a formatter method.
        val refs = detect("{var|fmt=digits=2}")
        val param = refs.first { it.kind == Kind.PARAMETER_NAME }
        assertTrue(param.isPipeFilter)
        assertEquals("fmt", param.callTargetName)
    }

    // ── P2-14 / P3-6: compiler filter-name gate ────────────────────────────

    @Test fun pipeFunction_leadingUnderscore_failsCompilerGate() {
        // Compiler gate (`/^[a-z][\w\\:]*$/i`) requires the first char to be
        // a letter — `_foo` fails it, so the compiler `continue`s past the
        // whole filter (no call emitted). Must not surface a FUNCTION ref.
        val refs = detect("{var|_foo}")
        assertTrue("gate-failing filter name must emit no refs, got $refs", refs.isEmpty())
    }

    @Test fun pipeFunction_leadingUnderscore_argsNotScannedEither() {
        // Since the compiler skips the whole filter, named-args in its
        // arg list must not surface as PARAMETER_NAME either.
        val refs = detect("{var|_foo=name=1}")
        assertTrue("gate-failing filter must not scan its args, got $refs", refs.isEmpty())
    }

    @Test fun pipeFunction_validName_stillPassesGate() {
        // Sanity: a normal filter name must be unaffected by the gate.
        val refs = detect("{var|trim}")
        assertEquals(1, refs.size)
        assertEquals(Kind.FUNCTION, refs[0].kind)
        assertEquals("trim", refs[0].nameInSource)
    }

    @Test fun pipeFunction_leadingDigit_failsCompilerGate() {
        val refs = detect("{var|2foo}")
        assertTrue("digit-led filter name must fail the gate, got $refs", refs.isEmpty())
    }

    @Test fun expressionFunctionCall_NOT_flaggedAsPipeFilter() {
        // {=foo()} — expression context never consults the formatter class.
        val refs = detect("{=foo()}")
        assertEquals(1, refs.size)
        assertTrue(!refs[0].isPipeFilter)
    }

    // ── constants via c. ────────────────────────────────────────────────────

    @Test fun constant_cDot_simple() {
        val refs = detect("{c.NAME}")
        assertEquals(1, refs.size)
        assertEquals(Kind.CONSTANT, refs[0].kind)
        assertEquals("NAME", refs[0].nameInSource)
    }

    @Test fun constant_cDot_absoluteFqn() {
        val refs = detect("{c.\\App\\CONST_NAME}")
        assertEquals(1, refs.size)
        assertEquals(Kind.CONSTANT, refs[0].kind)
        assertEquals("\\App\\CONST_NAME", refs[0].nameInSource)
    }

    @Test fun classConstant_viaCDot() {
        // {c.Enums::CONST} — class constant via the c. scope
        val refs = detect("{c.Enums::CONST}")
        assertEquals(2, refs.size)
        val cls = refs.first { it.kind == Kind.CLASS }
        val constant = refs.first { it.kind == Kind.CLASS_CONSTANT }
        assertEquals("Enums", cls.nameInSource)
        assertEquals("CONST", constant.nameInSource)
        assertEquals("Enums", constant.classNameInSource)
    }

    @Test fun classConstant_viaCDot_namespaced() {
        val refs = detect("{c.App\\Enums::TYPE_A}")
        val cls = refs.first { it.kind == Kind.CLASS }
        val constant = refs.first { it.kind == Kind.CLASS_CONSTANT }
        assertEquals("App\\Enums", cls.nameInSource)
        assertEquals("TYPE_A", constant.nameInSource)
        assertEquals("App\\Enums", constant.classNameInSource)
    }

    // ── tags / variables (no refs) ───────────────────────────────────────────

    @Test fun loopTag_noRefs() {
        // {loop products} — `products` is a variable (no parens, no `::`),
        // so even though `loop` enters expression context the body produces
        // no PHP-symbol refs.
        assertTrue(detect("{loop products}").isEmpty())
    }

    // ── expression-context prefixes for loop directives (@/%) ────────────────

    @Test fun atPrefix_staticMethod_isDetected() {
        // User's reported case: `{@:Cls::method()}` — the colon after `@` is
        // an operator (not a tag prefix at this position), and `@` itself
        // enters expression context.
        val refs = detect("{@:Enums\\UserLevel::getArray()}")
        assertEquals(2, refs.size)
        val cls = refs.first { it.kind == Kind.CLASS }
        val method = refs.first { it.kind == Kind.METHOD }
        assertEquals("Enums\\UserLevel", cls.nameInSource)
        assertEquals("getArray", method.nameInSource)
        assertEquals("Enums\\UserLevel", method.classNameInSource)
    }

    @Test fun atPrefix_staticMethodWithCDotArg_bothDetected() {
        // The exact user case — outer static method + inner `c.Cls::CONST`.
        val refs = detect("{@:Enums\\UserLevel::getArray(c.Enums\\UserLevel::GUEST)}")
        // CLASS for Enums\UserLevel (twice — outer call and inner c.Class),
        // METHOD getArray, CLASS_CONSTANT GUEST.
        val classRefs = refs.filter { it.kind == Kind.CLASS }
        val methodRefs = refs.filter { it.kind == Kind.METHOD }
        val classConstRefs = refs.filter { it.kind == Kind.CLASS_CONSTANT }
        assertEquals(2, classRefs.size)
        assertEquals(1, methodRefs.size)
        assertEquals("getArray", methodRefs[0].nameInSource)
        assertEquals(1, classConstRefs.size)
        assertEquals("GUEST", classConstRefs[0].nameInSource)
    }

    @Test fun atPrefix_simpleVariable_noRefs() {
        // `{@list}` — `list` is a variable, no refs even in expression context.
        assertTrue(detect("{@list}").isEmpty())
    }

    @Test fun percentPrefix_staticMethod_isDetected() {
        val refs = detect("{%Cls::build()}")
        assertEquals(2, refs.size)
        assertTrue(refs.any { it.kind == Kind.CLASS && it.nameInSource == "Cls" })
        assertTrue(refs.any { it.kind == Kind.METHOD && it.nameInSource == "build" })
    }

    @Test fun foreachTag_noRefs_forVariables() {
        // foreach IS expression keyword, but `items`, `as`, `item` are not
        // function calls (no parens, no `::`) so still no refs.
        assertTrue(detect("{foreach items as item}").isEmpty())
    }

    @Test fun ifTag_dotProperty_noRefs() {
        // user.isAdmin — `user` is variable, `isAdmin` is property (after dot)
        assertTrue(detect("{if user.isAdmin}").isEmpty())
    }

    @Test fun bareVariable_noRefs() {
        assertTrue(detect("{userName}").isEmpty())
    }

    @Test fun loopScopeVariable_noRefs() {
        assertTrue(detect("{.title}").isEmpty())
        assertTrue(detect("{..parent}").isEmpty())
    }

    @Test fun objectMethodCall_NOT_detected_asFunction() {
        // {=user.method()} — `method` belongs to `user` (a template variable),
        // not a top-level PHP function. Must NOT emit a FUNCTION ref for it.
        // (M5 will resolve `user` via assign() and offer reference for `method`.)
        val refs = detect("{=user.method()}")
        assertTrue("expected no refs but got $refs", refs.isEmpty())
    }

    @Test fun objectMethodInsideFunctionArg_onlyOuterFunctionEmitted() {
        // {=foo(arg.method())} — only `foo` is detected; `arg.method` skipped.
        val refs = detect("{=foo(arg.method())}")
        assertEquals(1, refs.size)
        assertEquals("foo", refs[0].nameInSource)
        assertEquals(Kind.FUNCTION, refs[0].kind)
    }

    // ── empty / no-op cases ──────────────────────────────────────────────────

    @Test fun emptyText() { assertTrue(detect("").isEmpty()) }
    @Test fun plainText() { assertTrue(detect("hello world").isEmpty()) }
    @Test fun textWithoutBrace() { assertTrue(detect("no template here").isEmpty()) }

    @Test fun unterminatedTag() {
        assertTrue(detect("{=foo(").isEmpty())
    }

    // ── multiple constructs in one element ───────────────────────────────────

    @Test fun multipleTagsInSameText() {
        // {=foo()}     → 1 FUNCTION
        // {=Bar::baz()} → 1 CLASS + 1 METHOD
        // {var|nl2br}  → 1 FUNCTION (pipe)
        // {c.X}        → 1 CONSTANT
        val refs = detect("hi {=foo()} mid {=Bar::baz()} end {var|nl2br} foot {c.X}")
        val byKind = refs.groupBy { it.kind }
        assertEquals(2, byKind[Kind.FUNCTION]?.size)
        assertEquals(1, byKind[Kind.CLASS]?.size)
        assertEquals(1, byKind[Kind.METHOD]?.size)
        assertEquals(1, byKind[Kind.CONSTANT]?.size)
    }

    // ── CSS / JS guard ──────────────────────────────────────────────────────

    @Test fun cssRule_doesNotEmitRefs() {
        // CSS rule body contains identifiers that look function-shaped — `red`,
        // `padding`, etc. — but the body is `prop: val` form, NOT a template tag.
        val refs = detect("<style>.foo { color: red; padding: 4px; }</style>")
        assertTrue("CSS rule should produce no refs, got $refs", refs.isEmpty())
    }

    @Test fun jsObjectLiteral_doesNotEmitRefs() {
        val refs = detect("<script>let x = {foo: bar};</script>")
        assertTrue(refs.isEmpty())
    }

    @Test fun jsBlock_doesNotEmitRefs() {
        val refs = detect("<script>if (x) { somefunc(); }</script>")
        assertTrue(refs.isEmpty())
    }

    @Test fun cTagInsideStyleStillWorks() {
        // Genuine `{c.LABEL}` in CSS context — must still emit a CONSTANT ref.
        val refs = detect("<style>p::before { content: '{c.LABEL}'; }</style>")
        assertEquals(1, refs.size)
        assertEquals(Kind.CONSTANT, refs[0].kind)
        assertEquals("LABEL", refs[0].nameInSource)
    }

    @Test fun pipeFilterInsideScriptStillWorks() {
        val refs = detect("<script>let s = '{name|upper}';</script>")
        assertEquals(1, refs.size)
        assertEquals(Kind.FUNCTION, refs[0].kind)
        assertEquals("upper", refs[0].nameInSource)
    }
}
