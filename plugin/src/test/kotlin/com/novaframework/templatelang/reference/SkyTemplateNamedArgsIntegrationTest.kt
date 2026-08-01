package com.novaframework.templatelang.reference

import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.psi.PsiReference
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.Parameter as PhpParameter
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Integration coverage for the Phase 1 named-args feature:
 *
 *   - Resolve (`SkyTemplatePhpReference` PARAMETER_NAME branch) returns the
 *     PHP `Parameter` PSI for paren calls and pipe `=` filter args.
 *   - Inlay parameter-hint provider emits hints for positional args and
 *     skips already-named ones.
 *
 * ParameterInfo end-to-end is NOT exercised here — the IntelliJ
 * platform's `ShowParameterInfoHandler` requires a populated
 * `Editor.caretModel` and is awkward to drive from a light fixture.
 * The handler logic is covered indirectly via the same callee
 * resolution path used by the inlay provider.
 */
class SkyTemplateNamedArgsIntegrationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
            }
        )
    }

    // ── PARAMETER_NAME → PHP Parameter resolution ─────────────────────────

    fun testParenNamedArg_resolvesToPhpParameter() {
        myFixture.addFileToProject(
            "helper.php",
            """
            <?php
            function helper(int ${'$'}count, string ${'$'}label = '') {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val refs = providerRefs("<p>{=helper(count: 3, label: 'x')}</p>")
        val countRef = refs.skyParam("count")
        val resolved = countRef.resolve() as? PhpParameter
        assertNotNull("`count` named-arg must resolve to a PHP Parameter", resolved)
        assertEquals("count", resolved!!.name)

        val labelRef = refs.skyParam("label")
        assertNotNull("`label` named-arg must also resolve",
            labelRef.resolve() as? PhpParameter)
    }

    fun testStaticMethodNamedArg_resolvesViaClassMember() {
        myFixture.addFileToProject(
            "Util.php",
            """
            <?php
            class Util {
                public static function format(int ${'$'}value, int ${'$'}width = 0): string {
                    return '';
                }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val refs = providerRefs("<p>{=Util::format(value: 1, width: 5)}</p>")
        val valueRef = refs.skyParam("value")
        val resolved = valueRef.resolve() as? PhpParameter
        assertNotNull("static-method named-arg must resolve to its parameter", resolved)
        assertEquals("value", resolved!!.name)
    }

    fun testPipeFilterNamedArg_resolvesToFreeFunctionParameter() {
        myFixture.addFileToProject(
            "fmt.php",
            """
            <?php
            function fmt(string ${'$'}value, int ${'$'}decimals = 0, string ${'$'}sep = '.') {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val refs = providerRefs("<p>{\$amount|fmt=decimals=2, ##}</p>")
        val decimalsRef = refs.skyParam("decimals")
        val resolved = decimalsRef.resolve() as? PhpParameter
        assertNotNull("pipe-filter named-arg must resolve via free-function lookup",
            resolved)
        assertEquals("decimals", resolved!!.name)
    }

    fun testUnresolvedNamedArg_isSoftAndReturnsEmpty() {
        // Callee unknown (no PHP fixture). Reference must still be created
        // and treated as soft so the analyzer doesn't surface a warning.
        myFixture.addFileToProject("empty.php", "<?php")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val refs = providerRefs("<p>{=ghostFn(name: 1)}</p>")
        val nameRef = refs.skyParam("name") as SkyTemplatePhpReference
        assertEquals(SkyTemplateRefDetector.Kind.PARAMETER_NAME, nameRef.kind)
        assertTrue("PARAMETER_NAME ref must be soft", nameRef.isSoft)
        assertTrue("unresolved named-arg returns empty multiResolve",
            nameRef.multiResolve(false).isEmpty())
    }

    // ── inlay parameter hints ─────────────────────────────────────────────

    fun testInlayHints_positionalArgs_getNameChips() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function inlayFn(string ${'$'}first, int ${'$'}second) {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{=inlayFn('a', 2)}</p>")
        val labels = hints.map { it.text }
        assertTrue("expected `first:` hint, got $labels", labels.contains("first:"))
        assertTrue("expected `second:` hint, got $labels", labels.contains("second:"))
    }

    fun testInlayHints_alreadyNamed_areSkipped() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function inlayFn2(string ${'$'}first, int ${'$'}second) {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{=inlayFn2(first: 'a', second: 2)}</p>")
        assertTrue("already-named args must NOT receive duplicate hints; got $hints",
            hints.isEmpty())
    }

    fun testInlayHints_pipeFilter_namedArgSkipsHint() {
        // Already-named filter args must NOT receive a hint (would be a
        // duplicate). The piped value (`{name|fn=…}` form) is implicit and
        // not part of the visible argument list.
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function pipeFn(string ${'$'}value, int ${'$'}width) {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{name|pipeFn=width=4}</p>")
        // Bucket `width=4` is named — no hint expected for it.
        val labels = hints.map { it.text }
        assertTrue("named pipe-filter arg must NOT be decorated, got $labels",
            labels.none { it == "width:" })
    }

    // ── L-002 (Phase 2): explicit `##` placement in pipe filters ────────────

    /**
     * Auto-prepend (no `##` written) — visible tokens map to parameters
     * starting at index 1 because the compiler places the pipe-input value
     * at slot 0. Pre-existing behaviour, pinned here against L-002 regressions.
     */
    fun testInlayHints_pipe_autoPrepend_chipsStartAtParam1() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function pipeFn3(string ${'$'}piped, string ${'$'}a, string ${'$'}b) {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{name|pipeFn3=foo, bar}</p>")
        val labels = hints.map { it.text }
        assertTrue("expected `a:` chip on `foo`, got $labels", labels.contains("a:"))
        assertTrue("expected `b:` chip on `bar`, got $labels", labels.contains("b:"))
        assertTrue("`piped` must NOT be decorated (it's the pipe-input), got $labels",
            labels.none { it == "piped:" })
    }

    /**
     * Explicit `##` in the *middle* of the bucket list. The compiler
     * preserves user token order and substitutes the pipe-input expression
     * at the `##` slot — so `arg1, ##, arg3` compiles to
     * `fn(arg1, $piped, arg3)` and the chips align as
     *   token[0]=`arg1` → parameter[0]=`piped:`
     *   token[1]=`##`   → skipped (placeholder)
     *   token[2]=`arg3` → parameter[2]=`b:`
     * The PHP function fixture parameter at index 0 is named `piped` so
     * the chip on `arg1` carries that label — confirming the user's
     * `##`-anchored token directly maps to slot 0.
     */
    fun testInlayHints_pipe_middleHash_chipsAreCorrectlyOffset() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function pipeFn4(string ${'$'}piped, string ${'$'}a, string ${'$'}b) {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{name|pipeFn4=arg1, ##, arg3}</p>")
        val labels = hints.map { it.text }
        assertTrue("`arg1` → parameter[0] = `piped:`, got $labels",
            labels.contains("piped:"))
        assertTrue("`arg3` → parameter[2] = `b:`, got $labels",
            labels.contains("b:"))
        assertTrue("`##` placeholder must NOT receive a chip; `arg2` (`a:`) is not in this input, got $labels",
            labels.none { it == "a:" })
    }

    /**
     * Trailing `##` — same token-order semantics:
     *   token[0]=`arg1` → parameter[0]=`piped:`
     *   token[1]=`arg2` → parameter[1]=`a:`
     *   token[2]=`##`   → skipped
     */
    fun testInlayHints_pipe_trailingHash_chipsAreCorrectlyOffset() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function pipeFn5(string ${'$'}piped, string ${'$'}a, string ${'$'}b) {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{name|pipeFn5=arg1, arg2, ##}</p>")
        val labels = hints.map { it.text }
        assertTrue("`arg1` → `piped:`, got $labels", labels.contains("piped:"))
        assertTrue("`arg2` → `a:`, got $labels", labels.contains("a:"))
        assertTrue("`##` placeholder must NOT receive a chip; `b:` is not used, got $labels",
            labels.none { it == "b:" })
    }

    /**
     * Leading `##` — equivalent to auto-prepend in compiler output, so the
     * visible tokens map to parameter[1+]:
     *   token[0]=`##`   → skipped
     *   token[1]=`arg1` → parameter[1]=`a:`
     *   token[2]=`arg2` → parameter[2]=`b:`
     */
    fun testInlayHints_pipe_leadingHash_chipsAreCorrectlyOffset() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function pipeFn6(string ${'$'}piped, string ${'$'}a, string ${'$'}b) {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{name|pipeFn6=##, arg1, arg2}</p>")
        val labels = hints.map { it.text }
        assertTrue("`arg1` → `a:`, got $labels", labels.contains("a:"))
        assertTrue("`arg2` → `b:`, got $labels", labels.contains("b:"))
        assertTrue("`piped` must NOT be decorated (chip on `##` slot), got $labels",
            labels.none { it == "piped:" })
    }

    // ── P2-14 / P3-2: named-arg reorder must not shift positional chips ────

    /**
     * `{name|pipeFn8=name=v, a}` — the compiler moves ALL named args after
     * ALL positional ones (`array_merge($positional, $named)`), so `name=v`
     * consumes NO positional slot even though it's written first. `a` is
     * therefore PHP arg 1 (right after the auto-prepended pipe value at
     * slot 0), not arg 2.
     */
    fun testInlayHints_pipe_namedArgBeforePositional_doesNotShiftPositionalChip() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function pipeFn8(string ${'$'}piped, string ${'$'}a, string ${'$'}b) {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{name|pipeFn8=label=v, a}</p>")
        val labels = hints.map { it.text }
        assertTrue("`a` must map to param[1] = `a:`, got $labels", labels.contains("a:"))
        assertTrue("must NOT shift to param[2] = `b:`, got $labels", labels.none { it == "b:" })
    }

    // ── P3-4: pipe `Cls::method` static calls get inlay hints too ──────────

    fun testInlayHints_pipeStaticMethod_positionalArgsGetChips() {
        myFixture.addFileToProject(
            "Fmt.php",
            """
            <?php
            class Fmt {
                public static function pad(string ${'$'}piped, int ${'$'}width) { return ${'$'}piped; }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{name|Fmt::pad=4}</p>")
        val labels = hints.map { it.text }
        assertTrue("expected `width:` chip on pipe static-method call, got $labels",
            labels.contains("width:"))
    }

    // ── 1.1.0: InlayProvider static-method off-by-one regression ───────────

    /**
     * Static-method paren call: positional arguments must receive parameter
     * chips. The 1.0.0 InlayProvider had a one-byte short-trim in the
     * `Cls::` class-name extractor, dropping the last character (so `Util`
     * looked up as `Uti` and resolved to nothing → no chips emitted at
     * all). 1.1.0 delegates to `SkyTemplateCallArguments` which has the
     * corrected math; this test pins the regression.
     */
    fun testInlayHints_staticMethod_positionalArgs_getNameChips() {
        myFixture.addFileToProject(
            "Format.php",
            """
            <?php
            class Format {
                public static function pad(int ${'$'}value, int ${'$'}width) {
                    return str_pad((string)${'$'}value, ${'$'}width);
                }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{=Format::pad(7, 4)}</p>")
        val labels = hints.map { it.text }
        assertTrue("expected `value:` chip on static-method positional, got $labels",
            labels.contains("value:"))
        assertTrue("expected `width:` chip on static-method positional, got $labels",
            labels.contains("width:"))
    }

    /**
     * Static-method paren call with all-named arguments — no chips at all,
     * because every slot already carries the parameter name. Pre-1.1.0 the
     * provider emitted **zero** chips here too, but only because the
     * class-name extractor failed and the lookup returned no parameters
     * (i.e. correct outcome for the wrong reason). With the fix the
     * lookup succeeds AND the named-arg recognition still suppresses the
     * chips, so the assertion is identical but its provenance is sound.
     */
    fun testInlayHints_staticMethod_paren_allNamedArgs_noChips() {
        myFixture.addFileToProject(
            "Format2.php",
            """
            <?php
            class Format2 {
                public static function pad(int ${'$'}value, int ${'$'}width) {
                    return str_pad((string)${'$'}value, ${'$'}width);
                }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{=Format2::pad(value: 7, width: 4)}</p>")
        val labels = hints.map { it.text }
        assertTrue("all named — no parameter chips expected, got $labels",
            labels.isEmpty())
    }

    // ── L-003 (Phase 2): pipe filter `==` comparison ───────────────────────

    /**
     * `count==2` is a comparison, not a named arg. The bucket should be
     * decorated as a positional value (chip = parameter[1] name).
     */
    fun testInlayHints_pipe_doubleEqualsComparison_isPositional() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function pipeFn7(string ${'$'}piped, string ${'$'}predicate) {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val hints = collectHints("<p>{x|pipeFn7=count==2}</p>")
        val labels = hints.map { it.text }
        assertTrue("comparison bucket should receive `predicate:` chip, got $labels",
            labels.contains("predicate:"))
        assertTrue("MUST NOT mistake `count` for a named arg, got $labels",
            labels.none { it == "count:" })
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun providerRefs(html: String): Array<PsiReference> {
        myFixture.configureByText("page.html", html)
        val file = myFixture.file
        return SkyTemplateReferenceProvider().getReferencesByElement(file, ProcessingContext())
    }

    private fun Array<PsiReference>.skyParam(expectedName: String): PsiReference {
        val skyRefs = filterIsInstance<SkyTemplatePhpReference>()
        return skyRefs.firstOrNull { ref ->
            ref.kind == SkyTemplateRefDetector.Kind.PARAMETER_NAME &&
                ref.nameInSource == expectedName
        } ?: error("no PARAMETER_NAME ref for `$expectedName` in ${skyRefs.toList()}")
    }

    private fun collectHints(html: String): List<InlayInfo> {
        myFixture.configureByText("page.html", html)
        val provider = SkyTemplateInlayParameterHintsProvider()
        return provider.getParameterHints(myFixture.file)
    }
}
