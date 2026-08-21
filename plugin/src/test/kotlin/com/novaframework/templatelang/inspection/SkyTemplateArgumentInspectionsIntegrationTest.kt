package com.novaframework.templatelang.inspection

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Integration coverage for the Phase 3 argument-validation inspections.
 * Drives `myFixture.doHighlighting()` against fixture sources containing
 * paren / static-method / pipe-form calls and verifies each rule fires (or
 * stays silent) per the spec.
 *
 * Both inspections are enabled together because the analyzer is shared —
 * keeping their assertions in one place mirrors the SkyTemplate test
 * convention of one *IntegrationTest per feature.
 */
class SkyTemplateArgumentInspectionsIntegrationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
            }
        )
        myFixture.enableInspections(
            SkyTemplateArgumentCountInspection(),
            SkyTemplateNamedArgumentInspection(),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun ourDiagnostics(highlights: List<HighlightInfo>): List<HighlightInfo> {
        return highlights.filter { hi ->
            hi.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal && (
                hi.description?.startsWith("Missing required argument") == true ||
                hi.description?.startsWith("Too many arguments") == true ||
                hi.description?.startsWith("Unknown parameter") == true ||
                hi.description?.startsWith("Duplicate named argument") == true ||
                hi.description?.startsWith("Cannot use positional argument after named argument") == true
            )
        }
    }

    private fun assertOurDiagnostic(
        highlights: List<HighlightInfo>,
        prefix: String,
        severity: HighlightSeverity,
    ) {
        val ours = ourDiagnostics(highlights)
        val match = ours.firstOrNull {
            it.severity == severity && it.description?.startsWith(prefix) == true
        }
        assertNotNull(
            "expected $severity diagnostic starting with `$prefix`. Got: " +
                ours.joinToString { "[${it.severity}] ${it.description}" },
            match,
        )
    }

    private fun assertNoOurDiagnostics(highlights: List<HighlightInfo>) {
        val ours = ourDiagnostics(highlights)
        assertTrue(
            "expected no Phase-3 argument diagnostics. Got: " +
                ours.joinToString { "[${it.severity}] ${it.description}" },
            ours.isEmpty(),
        )
    }

    // ── rule a: required missing ──────────────────────────────────────────

    fun testRuleA_paren_required_missing() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function helper(string ${'$'}a) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=helper()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Missing required argument(s) for `helper(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    fun testRuleA_staticMethod_required_missing() {
        myFixture.addFileToProject(
            "Util.php",
            """
            <?php
            class Util {
                public static function f(int ${'$'}x) {}
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=Util::f()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Missing required argument(s) for `Util::f(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    fun testRuleA_pipe_required_missing() {
        // pipe input fills PHP arg 0 (auto-prepend), `$tpl` still required.
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fmt(string ${'$'}tpl, ${'$'}val) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{x|fmt=}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Missing required argument(s) for `fmt(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    // ── P3-4: pipe `Cls::method` static-method calls are validated ─────────

    fun testRuleA_pipeStaticMethod_required_missing() {
        myFixture.addFileToProject(
            "Util.php",
            """
            <?php
            class Util {
                public static function fmt(string ${'$'}tpl, ${'$'}val) { return ${'$'}tpl; }
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{x|Util::fmt=}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Missing required argument(s) for `Util::fmt(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    fun testRuleB_pipeStaticMethod_tooMany() {
        myFixture.addFileToProject(
            "Util2.php",
            """
            <?php
            class Util2 {
                public static function fmt(${'$'}a) { return ${'$'}a; }
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{x|Util2::fmt=##, b, c}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Too many arguments for `Util2::fmt(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    fun testRuleA_negative_allRequiredFilled() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function helper(string ${'$'}a, int ${'$'}b = 0) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=helper('x')}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    fun testRuleA_negative_polyVariantTolerantWhenAnyAccepts() {
        // Two functions share a simple name. helper(a) requires 1; helperShim()
        // requires 0. Simple-name fallback returns both — most-permissive
        // wins, no diagnostic for the empty call.
        myFixture.addFileToProject(
            "ns_a.php",
            """
            <?php
            namespace App\A;
            function shareName(string ${'$'}a) {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "ns_b.php",
            """
            <?php
            namespace App\B;
            function shareName() {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=shareName()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    /**
     * Regression (P2-2, v1.2.4): a call site written as an example inside an
     * HTML-wrapped comment (`<!--{* … *}-->`) must not be validated — the
     * comment guard only recognised the plain `{*…*}` shape and let the
     * shell-expanded wrapped form slip through, flagging example code.
     */
    fun testRuleA_negative_wrappedCommentIsNotValidated() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function helper(string ${'$'}a) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p><!--{* 예: {=helper()} *}--></p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    fun testRuleA_wrappedNonCommentDirective_isStillValidated() {
        // A wrapped directive that is NOT a comment (no `<!--{*` shape) must
        // still be validated normally.
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function helper(string ${'$'}a) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p><!--{=helper()}--></p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Missing required argument(s) for `helper(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    // ── rule b: too many ──────────────────────────────────────────────────

    fun testRuleB_paren_tooMany() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function f(${'$'}a) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=f(1, 2, 3)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Too many arguments for `f(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    fun testRuleB_staticMethod_tooMany() {
        myFixture.addFileToProject(
            "Util.php",
            """
            <?php
            class Util {
                public static function sum(${'$'}a, ${'$'}b) { return 0; }
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=Util::sum(1, 2, 3)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Too many arguments for `Util::sum(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    fun testRuleB_pipe_explicitHash_tooMany() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fmt(${'$'}a, ${'$'}b) {}
            """.trimIndent()
        )
        // tokens: `##, b, c` — explicit hash → phpArgCount = 3, sig has 2.
        myFixture.configureByText("page.html", "<p>{x|fmt=##, b, c}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Too many arguments for `fmt(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    // ── P3-5: blank pipe-arg buckets count toward phpArgCount ───────────────

    fun testRuleB_pipe_blankBucketsCountAsArguments() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fmt(${'$'}a, ${'$'}b) {}
            """.trimIndent()
        )
        // tokens: ##(auto), a, '', b → 4 PHP args (compiler keeps the blank
        // CSV token as a positional `''`), sig has 2 → too many.
        myFixture.configureByText("page.html", "<p>{x|fmt=a,,b}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Too many arguments for `fmt(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    fun testRuleB_negative_variadic() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function many(...${'$'}xs) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=many(1, 2, 3, 4, 5)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    fun testRuleB_negative_fixedPlusVariadic() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function f(${'$'}a, ...${'$'}rest) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=f(1, 2, 3)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    // ── rule c: unknown named ─────────────────────────────────────────────

    fun testRuleC_paren_unknownName() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function helper(string ${'$'}name) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=helper(typo: 1)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Unknown parameter `typo` for `helper(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    fun testRuleC_staticMethod_unknownName() {
        myFixture.addFileToProject(
            "Util.php",
            """
            <?php
            class Util {
                public static function f(int ${'$'}value) {}
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=Util::f(valuee: 1)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Unknown parameter `valuee` for `Util::f(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    fun testRuleC_pipe_unknownName() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fn1(${'$'}val, ${'$'}mode) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{x|fn1=moded=1}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Unknown parameter `moded` for `fn1(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    fun testRuleC_negative_correctName() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function helper(string ${'$'}name) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=helper(name: 'x')}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    fun testRuleC_negative_variadicAcceptsAnyName() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function dyn(...${'$'}kw) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=dyn(any: 1, other: 2)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    // ── rule d: duplicate named (ERROR) ───────────────────────────────────

    fun testRuleD_paren_duplicate() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function helper(${'$'}a, ${'$'}b = 0) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=helper(a: 1, a: 2)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Duplicate named argument `a` for `helper(...)`",
            severity = HighlightSeverity.ERROR,
        )
    }

    fun testRuleD_pipe_duplicate() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fn2(${'$'}val, ${'$'}mode) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{x|fn2=mode=1, mode=2}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Duplicate named argument `mode` for `fn2(...)`",
            severity = HighlightSeverity.ERROR,
        )
    }

    // ── rule e: positional after named (ERROR) ────────────────────────────

    fun testRuleE_paren_positionalAfterNamed() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function helper(${'$'}name, ${'$'}extra = 0) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=helper(name: 1, 2)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Cannot use positional argument after named argument in `helper(...)`",
            severity = HighlightSeverity.ERROR,
        )
    }

    /**
     * Regression (P1-3): rule e must NOT apply to the pipe form. The
     * compiler's `parseFunction()` (SkyTemplateCompiler.php:874-887)
     * reorders pipe args as `array_merge($positional, $named)` before
     * emitting the call, so a positional written after a named arg still
     * compiles fine — flagging it was a false positive.
     */
    fun testRuleE_pipe_positionalAfterNamed_isNotFlagged() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fn3(${'$'}val, ${'$'}mode, ${'$'}extra = 0) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{x|fn3=mode=1, plain}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    fun testRuleE_pipe_strPadStylePositionalAfterNamed_isNotFlagged() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function str_pad(${'$'}str, ${'$'}pad_type, ${'$'}len, ${'$'}pad = ' ') {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{var|str_pad=pad_type=1,10}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    fun testRuleE_negative_pipeHashIsNotPositionalForRuleE() {
        // `##` placeholder must not be treated as a positional that triggers
        // rule e when it follows a named arg.
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fn4(${'$'}val, ${'$'}mode) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{x|fn4=mode=1, ##}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    fun testRuleE_negative_allNamed() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function helper(${'$'}a, ${'$'}b) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=helper(a: 1, b: 2)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    // ── out-of-scope shapes ───────────────────────────────────────────────

    fun testObjectMethodCall_notValidated() {
        // `{=user.name(1, 2, 3)}` is object property/method access — out of
        // scope (variable type inference required). No diagnostic regardless
        // of the value of `name`.
        myFixture.configureByText("page.html", "<p>{=user.name(1, 2, 3)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    fun testUnresolvedCallee_skipsArgumentChecks() {
        // FQN doesn't resolve → undefined-symbol inspection covers it. Argument
        // checks must stay silent to avoid double-flagging.
        myFixture.configureByText("page.html", "<p>{=getKakaoLogginUrl(1, 2, 3)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    fun testRuns_inSkyFile() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function only_in_sky(string ${'$'}a) {}
            """.trimIndent()
        )
        myFixture.configureByText("partial.sky", "{=only_in_sky()}")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Missing required argument(s) for `only_in_sky(...)`",
            severity = HighlightSeverity.WARNING,
        )
    }

    // ── extra regressions (tester pass) ──────────────────────────────────

    fun testRuleC_polyVariant_oneCandidateDefinesName() {
        // Two functions share the simple name. One declares `name`, the other
        // declares `value`. A call using `name:` resolves under the lenient
        // policy (any candidate accepts ⇒ pass).
        myFixture.addFileToProject(
            "ns_a.php",
            """
            <?php
            namespace App\Va;
            function pickOne(string ${'$'}name) {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "ns_b.php",
            """
            <?php
            namespace App\Vb;
            function pickOne(string ${'$'}value) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=pickOne(name: 'x')}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    fun testRuleD_staticMethod_duplicate() {
        // Same shape as the paren / pipe tests; pinned to make sure the
        // analyzer's per-rule path treats the calleeClass-prefixed callee
        // identically to the free-function case.
        myFixture.addFileToProject(
            "Util.php",
            """
            <?php
            class Util {
                public static function sum(${'$'}a, ${'$'}b = 0) { return 0; }
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=Util::sum(a: 1, a: 2)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Duplicate named argument `a` for `Util::sum(...)`",
            severity = HighlightSeverity.ERROR,
        )
    }

    fun testRuleA_highlightRangeIsTheParenSlot() {
        // The diagnostic for an empty arg list points at the `(` slot (one
        // byte before argListStart) so Alt+Enter / hover lands on the call
        // opener, not the whole identifier.
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function pinHelper(string ${'$'}a) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=pinHelper()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val highlights = myFixture.doHighlighting()
        val match = highlights.firstOrNull {
            it.severity == HighlightSeverity.WARNING &&
                it.description?.startsWith("Missing required argument(s) for `pinHelper(...)`") == true
        }
        assertNotNull("expected pin diagnostic", match)
        val text = myFixture.file.text
        val openParen = text.indexOf("(", text.indexOf("pinHelper"))
        // Range is `[openParen, openParen + 1)`.
        assertEquals("highlight start = `(`", openParen, match!!.startOffset)
        assertEquals("highlight end = `(` + 1", openParen + 1, match.endOffset)
    }

    fun testCallShapeRegression_acrossPrefixes() {
        // Phase-3 must fire identically across every expression-context tag
        // prefix that surfaces a function call in the SkyTemplate language.
        // Each shape passes one positional to a 2-required signature ⇒ rule
        // a fires once per tag, totalling four diagnostics.
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function shapeFn(${'$'}a, ${'$'}b) {}
            """.trimIndent()
        )
        myFixture.configureByText(
            "page.html",
            // `{? …}` (if condition), `{:foo()}` (echo-no-escape),
            // `{?:foo()}` (echo-empty-fallback), `{;foo()}` (php-side),
            // and the canonical `{=foo()}` already covered above. We add
            // two more shapes here to lock the regression in.
            """<p>{?shapeFn(1)}</p>
               <p>{:shapeFn(1)}</p>
               <p>{?:shapeFn(1)}</p>
               <p>{;shapeFn(1)}</p>""".trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val ours = ourDiagnostics(myFixture.doHighlighting())
            .filter { it.description?.startsWith("Missing required argument(s) for `shapeFn(...)`") == true }
        assertEquals(
            "expected one diagnostic per tag shape (4 total). Got: " +
                ours.joinToString { "[${it.severity}] @(${it.startOffset},${it.endOffset})" },
            4, ours.size,
        )
    }

    fun testMasterToggleOff_silencesAll() {
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply { enabled = false }
        )
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function disabledHelper(string ${'$'}a) {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=disabledHelper()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }

    // ── cache-invalidation regression ───────────────────────────────────────
    // [SkyTemplateCallArguments.analyze] is now shared across this file's
    // two inspection passes via a file-level `CachedValuesManager` entry
    // keyed on `PsiModificationTracker.MODIFICATION_COUNT`. Verifies a real
    // editor edit invalidates that cache instead of leaving the pre-edit
    // diagnostic baked in for the next highlighting pass.

    fun testStaleCacheClearsAfterEditingArgumentList() {
        myFixture.addFileToProject(
            "lib4.php",
            """
            <?php
            function helper4(string ${'$'}a) {}
            """.trimIndent()
        )
        myFixture.configureByText("page4.html", "<p>{=helper4(<caret>)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertOurDiagnostic(
            myFixture.doHighlighting(),
            prefix = "Missing required argument(s) for `helper4(...)`",
            severity = HighlightSeverity.WARNING,
        )

        // Type the missing argument via the real editor action — this must
        // bump the modification tracker and force a fresh `analyze()`, not
        // replay the pre-edit cached diagnostic list.
        myFixture.type("'x'")
        assertNoOurDiagnostics(myFixture.doHighlighting())
    }
}
