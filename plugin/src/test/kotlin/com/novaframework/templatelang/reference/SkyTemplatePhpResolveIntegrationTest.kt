package com.novaframework.templatelang.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ProcessingContext
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.novaframework.templatelang.settings.TemplateLangSettings
import com.jetbrains.php.lang.psi.elements.Function as PhpFunction

/**
 * End-to-end resolution tests — exercises the full path:
 *
 *   template host PSI → SkyTemplateReferenceProvider → SkyTemplatePhpReference
 *   → PhpIndex lookup → resolved PSI element from a real `.php` file.
 *
 * Earlier integration tests stopped one step before resolution because a vanilla
 * `BasePlatformTestCase` setup didn't have PhpIndex populated for fixture files.
 * The fix: after `myFixture.addFileToProject(...)` add a PHP file, then call
 * [IndexingTestUtil.waitUntilIndexesAreReady] before invoking `resolve()`.
 *
 * To stay independent of `plugin.xml` extension auto-loading (which is flaky in
 * light fixtures — see `SkyTemplateReferenceIntegrationTest` class doc), we
 * invoke our [SkyTemplateReferenceProvider] directly to obtain the reference,
 * then call `resolve()` / `multiResolve()` on it. This is sufficient to verify
 * that the FQN we hand to PhpIndex resolves to the right PSI element.
 */
class SkyTemplatePhpResolveIntegrationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Root namespace so the simple identifiers in fixture PHP map cleanly
        // to the FQNs our reference layer queries.
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
            }
        )
    }

    // ── functions ─────────────────────────────────────────────────────────────

    fun testFunctionResolvesToPhpFunction() {
        myFixture.addFileToProject(
            "defs.php",
            """
            <?php
            function myTplFunc(string ${'$'}s): string { return ${'$'}s; }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val ref = firstSkyRef("<p>{=myTplFunc(\"hi\")}</p>", "myTplFunc")
        val resolved = ref.resolve()
        assertNotNull("PhpIndex should resolve myTplFunc; got null", resolved)
        val fn = resolved as? PhpFunction
            ?: fail("expected PhpFunction, got ${resolved!!::class.qualifiedName}")
                .let { return }
        assertEquals("myTplFunc", fn.name)
    }

    fun testNamespacedFunctionResolvesAfterApplyingSettingNamespace() {
        myFixture.addFileToProject(
            "defs_ns.php",
            """
            <?php
            namespace App;
            function nsFunc(): void {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "App"
            }
        )
        val ref = firstSkyRef("<p>{=nsFunc()}</p>", "nsFunc")
        val resolved = ref.resolve() as? PhpFunction
        assertNotNull("expected resolution under namespace App\\nsFunc", resolved)
        assertEquals("nsFunc", resolved!!.name)
        assertEquals("\\App\\nsFunc", resolved.fqn)
    }

    fun testAbsoluteFqnFunctionBypassesSettingNamespace() {
        myFixture.addFileToProject(
            "defs_abs.php",
            """
            <?php
            namespace Other;
            function plainFunc(): void {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                // Setting namespace would normally prepend `\App\`, but the
                // template uses an absolute FQN (`\Other\…`) which must bypass
                // the prefix.
                namespace = "App"
            }
        )
        val ref = firstSkyRef("<p>{=\\Other\\plainFunc()}</p>", "plainFunc")
        val resolved = ref.resolve() as? PhpFunction
        assertNotNull("absolute-FQN should resolve regardless of namespace setting", resolved)
        assertEquals("\\Other\\plainFunc", resolved!!.fqn)
    }

    fun testUnknownFunctionResolvesToNull() {
        // No PHP file added — the reference still attaches but resolve()
        // returns null because nothing matches.
        myFixture.addFileToProject(
            "empty.php",
            "<?php"
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val ref = firstSkyRef("<p>{=ghostFunction()}</p>", "ghostFunction")
        assertNull("unknown function should not resolve", ref.resolve())
    }

    // ── classes / methods ─────────────────────────────────────────────────────

    fun testClassRefResolvesToPhpClass() {
        myFixture.addFileToProject(
            "Util.php",
            """
            <?php
            class Util {
                public static function build(string ${'$'}s): string { return ${'$'}s; }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val refs = providerRefs("<p>{=Util::build(\"hi\")}</p>")
        val classRef = refs.firstSky(SkyTemplateRefDetector.Kind.CLASS, "Util")
        val resolvedClass = classRef.resolve() as? PhpClass
        assertNotNull("Util should resolve", resolvedClass)
        assertEquals("Util", resolvedClass!!.name)

        val methodRef = refs.firstSky(SkyTemplateRefDetector.Kind.METHOD, "build")
        val resolvedMethod = methodRef.resolve() as? Method
        assertNotNull("Util::build should resolve to a method", resolvedMethod)
        assertEquals("build", resolvedMethod!!.name)
    }

    fun testNamespacedStaticMethodResolves() {
        myFixture.addFileToProject(
            "Enums.php",
            """
            <?php
            namespace App\Enums;
            class UserLevel {
                public static function getArray(int ${'$'}id): array { return []; }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "App"
            }
        )

        val refs = providerRefs("<p>{=Enums\\UserLevel::getArray(\"a\")}</p>")
        // The detector emits the CLASS ref's range over the LAST identifier
        // token (`UserLevel`), with `nameInSource` carrying the full
        // qualified-as-written form (`Enums\UserLevel`). The range-text
        // assertion targets the last token; resolution still uses the FQN.
        val classRef = refs.firstSky(SkyTemplateRefDetector.Kind.CLASS, "UserLevel")
        val resolvedClass = classRef.resolve() as? PhpClass
        assertNotNull("App\\Enums\\UserLevel should resolve", resolvedClass)
        assertEquals("UserLevel", resolvedClass!!.name)

        val methodRef = refs.firstSky(SkyTemplateRefDetector.Kind.METHOD, "getArray")
        val resolvedMethod = methodRef.resolve() as? Method
        assertNotNull("UserLevel::getArray should resolve", resolvedMethod)
    }

    fun testPipeStaticMethodResolves() {
        myFixture.addFileToProject(
            "Format.php",
            """
            <?php
            class Format {
                public static function trimAll(string ${'$'}s): string { return trim(${'$'}s); }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val refs = providerRefs("<p>{name|Format::trimAll}</p>")
        val classRef = refs.firstSky(SkyTemplateRefDetector.Kind.CLASS, "Format")
        assertNotNull("Format should resolve via pipe form too", classRef.resolve() as? PhpClass)

        val methodRef = refs.firstSky(SkyTemplateRefDetector.Kind.METHOD, "trimAll")
        assertNotNull("Format::trimAll should resolve via pipe form", methodRef.resolve() as? Method)
    }

    // ── constants ─────────────────────────────────────────────────────────────

    fun testGlobalConstantResolves() {
        myFixture.addFileToProject(
            "consts.php",
            """
            <?php
            const MY_GLOBAL_CONST = 42;
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val ref = firstSkyRef("<p>{c.MY_GLOBAL_CONST}</p>", "MY_GLOBAL_CONST")
        val resolved = ref.resolve()
        assertNotNull("global const should resolve via PhpIndex", resolved)
    }

    fun testClassConstantResolves() {
        myFixture.addFileToProject(
            "Status.php",
            """
            <?php
            class Status {
                const ACTIVE = 'active';
                const INACTIVE = 'inactive';
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val refs = providerRefs("<p>{c.Status::ACTIVE}</p>")
        val classRef = refs.firstSky(SkyTemplateRefDetector.Kind.CLASS, "Status")
        assertNotNull("Status class should resolve under c.", classRef.resolve() as? PhpClass)

        val ccRef = refs.firstSky(SkyTemplateRefDetector.Kind.CLASS_CONSTANT, "ACTIVE")
        val resolvedConst = ccRef.resolve() as? Field
        assertNotNull("Status::ACTIVE should resolve to a class constant Field", resolvedConst)
        assertEquals("ACTIVE", resolvedConst!!.name)
        assertTrue("expected class-constant Field, got non-constant", resolvedConst.isConstant)
    }

    // ── attribute-value + various namespace configurations (0.5.31) ───────────

    /**
     * Regression for the user's `<a href="{=getKakaoLoginUrl()}">` pattern.
     * Function defined in global namespace; namespace setting is `\` (root).
     */
    fun testFunctionInAttributeValueResolvesWithRootNamespace() {
        myFixture.addFileToProject(
            "kakao.php",
            """
            <?php
            function getKakaoLoginUrl(): string { return ''; }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"  // root
            }
        )

        val ref = firstSkyRef(
            """<a href="{=getKakaoLoginUrl()}">login</a>""",
            "getKakaoLoginUrl",
        )
        val resolved = ref.resolve() as? PhpFunction
        assertNotNull(
            "function in global namespace must resolve when namespace setting is `\\`",
            resolved,
        )
        assertEquals("\\getKakaoLoginUrl", resolved!!.fqn)
    }

    /**
     * Same test but with namespace setting EMPTY string — should be equivalent
     * to root, but if blank-string handling diverges from `\` handling, this
     * catches it.
     */
    fun testFunctionResolvesWithEmptyNamespaceSetting() {
        myFixture.addFileToProject(
            "globalFn.php",
            """
            <?php
            function emptyNsFn(): void {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = ""
            }
        )

        val ref = firstSkyRef("<p>{=emptyNsFn()}</p>", "emptyNsFn")
        val resolved = ref.resolve() as? PhpFunction
        assertNotNull("global function must resolve with empty namespace setting", resolved)
        assertEquals("\\emptyNsFn", resolved!!.fqn)
    }

    /**
     * Critical case: function in a NON-global namespace, plugin configured
     * with root namespace (`\`). Without the simple-name fallback, this
     * silently fails — Find Usages from the function definition won't see
     * the template hit, and Ctrl+Click in the template won't navigate.
     *
     * For users who have set namespace = `\` (or left it empty) and have
     * any namespaced functions, this is the bulk of the "일부는 동작 안 함"
     * symptom.
     */
    fun testNamespacedFunctionResolvesEvenWithRootNamespaceSetting() {
        myFixture.addFileToProject(
            "namespacedFn.php",
            """
            <?php
            namespace App\Helpers;
            function urlFor(): string { return ''; }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
            }
        )

        val ref = firstSkyRef("<p>{=urlFor()}</p>", "urlFor")
        val resolved = ref.resolve() as? PhpFunction
        assertNotNull(
            "function in App\\Helpers namespace must still resolve when plugin namespace " +
                "is root — fall back to PhpIndex.getFunctionsByName(simpleName) so users " +
                "don't have to configure a namespace per project",
            resolved,
        )
        assertEquals("\\App\\Helpers\\urlFor", resolved!!.fqn)
    }

    // ── namespace fallback (0.5.28) ───────────────────────────────────────────

    /**
     * The user's project has `namespace = App` configured for templates, but
     * common helper functions live in the global namespace. Without the
     * global-namespace fallback, every `{=helper()}` call to those functions
     * silently fails to resolve — Find Usages on the helper misses template
     * hits and Ctrl+Click does nothing.
     */
    fun testGlobalFunctionResolvesWhenConfiguredNamespaceIsNonRoot() {
        myFixture.addFileToProject(
            "globals.php",
            """
            <?php
            function globalHelper(): string { return ''; }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "App"
            }
        )

        val ref = firstSkyRef("<p>{=globalHelper()}</p>", "globalHelper")
        val resolved = ref.resolve() as? PhpFunction
        assertNotNull("global helper should resolve via global-namespace fallback", resolved)
        assertEquals("\\globalHelper", resolved!!.fqn)
    }

    fun testGlobalClassResolvesWhenConfiguredNamespaceIsNonRoot() {
        myFixture.addFileToProject(
            "GlobalUtil.php",
            """
            <?php
            class GlobalUtil {
                public static function tag(string ${'$'}s): string { return ${'$'}s; }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "App"
            }
        )

        val refs = providerRefs("<p>{=GlobalUtil::tag(\"hi\")}</p>")
        val classRef = refs.firstSky(SkyTemplateRefDetector.Kind.CLASS, "GlobalUtil")
        val resolvedClass = classRef.resolve() as? PhpClass
        assertNotNull("global class should resolve via global-namespace fallback", resolvedClass)
        assertEquals("\\GlobalUtil", resolvedClass!!.fqn)

        val methodRef = refs.firstSky(SkyTemplateRefDetector.Kind.METHOD, "tag")
        assertNotNull(
            "GlobalUtil::tag should resolve once GlobalUtil resolved via fallback",
            methodRef.resolve() as? Method,
        )
    }

    fun testGlobalConstantResolvesWhenConfiguredNamespaceIsNonRoot() {
        myFixture.addFileToProject(
            "consts.php",
            """
            <?php
            const ROOT_CONST = 1;
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "App"
            }
        )

        val ref = firstSkyRef("<p>{c.ROOT_CONST}</p>", "ROOT_CONST")
        assertNotNull("global constant should resolve via fallback", ref.resolve())
    }

    /**
     * When the same simple name is defined in both the configured namespace
     * AND globally, multiResolve should report both — Find Usages then
     * picks up template hits regardless of which definition is the user's
     * actual target.
     */
    fun testNamespacedAndGlobalShareSimpleName_bothResolved() {
        myFixture.addFileToProject(
            "ns_dual.php",
            """
            <?php
            namespace App;
            function dualName(): void {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "global_dual.php",
            """
            <?php
            function dualName(): void {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "App"
            }
        )

        val ref = firstSkyRef("<p>{=dualName()}</p>", "dualName") as SkyTemplatePhpReference
        val results = ref.multiResolve(false)
        val fqns = results.mapNotNull { (it.element as? PhpFunction)?.fqn }.toSet()
        assertTrue(
            "expected both \\App\\dualName and \\dualName to resolve; got $fqns",
            fqns.contains("\\App\\dualName") && fqns.contains("\\dualName"),
        )
    }

    // ── useClass alias expansion (0.5.28) ─────────────────────────────────────

    fun testUseClassAliasExpandsForExpressionContext() {
        myFixture.addFileToProject(
            "AliasedClass.php",
            """
            <?php
            namespace App\Helpers;
            class Foo {
                public static function tag(): string { return ''; }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
                // useClass entry with explicit alias `Bar`
                useClass = mutableListOf("App\\Helpers\\Foo as Bar")
            }
        )

        val refs = providerRefs("<p>{=Bar::tag()}</p>")
        val classRef = refs.firstSky(SkyTemplateRefDetector.Kind.CLASS, "Bar")
        val resolvedClass = classRef.resolve() as? PhpClass
        assertNotNull("alias `Bar` should expand to App\\Helpers\\Foo", resolvedClass)
        assertEquals("\\App\\Helpers\\Foo", resolvedClass!!.fqn)

        val methodRef = refs.firstSky(SkyTemplateRefDetector.Kind.METHOD, "tag")
        assertNotNull("Bar::tag should resolve via alias", methodRef.resolve() as? Method)
    }

    fun testUseClassWithoutExplicitAliasUsesBasename() {
        myFixture.addFileToProject(
            "NoAliasClass.php",
            """
            <?php
            namespace App\Helpers;
            class Widget {
                public static function render(): string { return ''; }
            }
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
                // useClass entry without explicit alias — basename `Widget` is the alias
                useClass = mutableListOf("App\\Helpers\\Widget")
            }
        )

        val refs = providerRefs("<p>{=Widget::render()}</p>")
        val classRef = refs.firstSky(SkyTemplateRefDetector.Kind.CLASS, "Widget")
        val resolvedClass = classRef.resolve() as? PhpClass
        assertNotNull("basename alias `Widget` should expand to App\\Helpers\\Widget", resolvedClass)
        assertEquals("\\App\\Helpers\\Widget", resolvedClass!!.fqn)
    }

    // ── multiResolve poly-variant ─────────────────────────────────────────────

    fun testFunctionWithDuplicateDeclarations_multiResolveReturnsAll() {
        // Two identically-named functions in different namespaces map to two
        // FQNs. The reference's `multiResolve` should report BOTH so PhpStorm
        // shows a candidate picker rather than silently picking one.
        myFixture.addFileToProject(
            "dup_a.php",
            """
            <?php
            namespace AppA;
            function shared(): void {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "dup_b.php",
            """
            <?php
            namespace AppB;
            function shared(): void {}
            """.trimIndent()
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        // Without a namespace setting, only the root-namespace lookup runs and
        // would miss both. Configure namespace to AppA so AppA\shared resolves;
        // AppB\shared should NOT resolve here (different namespace), so a single
        // multi-resolve hit is expected. The point of the test is that the
        // poly-variant API is invoked and yields a non-empty result array.
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "AppA"
            }
        )
        val ref = firstSkyRef("<p>{=shared()}</p>", "shared") as SkyTemplatePhpReference
        val results = ref.multiResolve(false)
        assertTrue(
            "multiResolve should yield at least one result for AppA\\shared; got 0",
            results.isNotEmpty(),
        )
        assertTrue(
            "all multiResolve elements should be PhpFunction; got ${results.map { it.element?.javaClass?.simpleName }}",
            results.all { it.element is PhpFunction },
        )
    }

    // ── Find Usages flow (0.5.33) ─────────────────────────────────────────────

    /**
     * The user reports Go to Definition (template → PHP) works for
     * `<a href="{=getFacebookLoginUrl()}">` after 0.5.32, but Find Usages
     * (PHP → template) still doesn't include the template hit. Diagnoses
     * by running the platform `ReferencesSearch.search(phpFunction)` and
     * asserting the template-side reference appears in the result.
     */
    fun testFindUsagesFromPhpFunctionFindsTemplateHitInsideHref() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function getFacebookLoginUrl(): string { return ''; }
            """.trimIndent()
        )
        myFixture.configureByText(
            "page.html",
            """<a href="{=getFacebookLoginUrl()}">link</a>""",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val fn = PhpIndex.getInstance(project)
            .getFunctionsByFQN("\\getFacebookLoginUrl")
            .firstOrNull() ?: error("PHP function not indexed")

        val results = com.intellij.psi.search.searches.ReferencesSearch
            .search(fn, com.intellij.psi.search.GlobalSearchScope.allScope(project))
            .findAll()
        val skyHits = results.filterIsInstance<SkyTemplatePhpReference>()
        assertTrue(
            "ReferencesSearch on `\\getFacebookLoginUrl` must include the template hit. " +
                "Got ${results.size} total refs (${results.map { it::class.simpleName }}), of " +
                "which ${skyHits.size} are SkyTemplatePhpReference. If 0 sky-hits: the search " +
                "is leaf-only and the leaf inside an XmlAttributeValue returns no refs — fix " +
                "must surface our refs at the leaf level.",
            skyHits.isNotEmpty(),
        )
    }

    /** Same test, but template construct is in plain XmlText (not attribute value). */
    /**
     * Same scenario but using `getUseScope()` (the scope PhpStorm's default
     * Find Usages dialog applies). If allScope works but useScope doesn't,
     * the gap is in the search-scope filter — UseScopeEnlarger isn't kicking
     * in for this declaration.
     */
    fun testFindUsagesWithUseScope() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function useScopeFn(): void {}
            """.trimIndent()
        )
        myFixture.configureByText(
            "page.html",
            """<a href="{=useScopeFn()}">x</a>""",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val fn = PhpIndex.getInstance(project)
            .getFunctionsByFQN("\\useScopeFn")
            .firstOrNull() ?: error("PHP function not indexed")

        // Inspect the use-scope to see if .html is included
        val useScope = fn.useScope
        val htmlVF = myFixture.file.virtualFile
        val isInScope = useScope is com.intellij.psi.search.GlobalSearchScope &&
            (useScope as com.intellij.psi.search.GlobalSearchScope).contains(htmlVF)
        println("useScope class=${useScope::class.simpleName} contains(.html)=$isInScope")

        val results = com.intellij.psi.search.searches.ReferencesSearch
            .search(fn)  // use the function's default scope
            .findAll()
        val skyHits = results.filterIsInstance<SkyTemplatePhpReference>()
        println("ReferencesSearch via default scope found ${results.size} total, ${skyHits.size} sky")
        assertTrue(
            "Find Usages with default (use-scope) must find template hit. ${skyHits.size}/${results.size} sky.",
            skyHits.isNotEmpty(),
        )
    }

    fun testFindUsagesFromPhpFunctionFindsTemplateHitInPlainText() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function plainTextFn(): void {}
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=plainTextFn()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val fn = PhpIndex.getInstance(project)
            .getFunctionsByFQN("\\plainTextFn")
            .firstOrNull() ?: error("PHP function not indexed")

        val results = com.intellij.psi.search.searches.ReferencesSearch
            .search(fn, com.intellij.psi.search.GlobalSearchScope.allScope(project))
            .findAll()
        val skyHits = results.filterIsInstance<SkyTemplatePhpReference>()
        assertTrue(
            "ReferencesSearch in plain XmlText must also find template hit. " +
                "Got ${results.size} total, ${skyHits.size} sky.",
            skyHits.isNotEmpty(),
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun providerRefs(html: String): Array<PsiReference> {
        myFixture.configureByText("page.html", html)
        val file = myFixture.file
        return SkyTemplateReferenceProvider().getReferencesByElement(file, ProcessingContext())
    }

    private fun firstSkyRef(html: String, expectedRangeText: String): PsiReference {
        return providerRefs(html).firstSkyRefByText(expectedRangeText)
    }

    private fun Array<PsiReference>.firstSky(
        kind: SkyTemplateRefDetector.Kind,
        expectedRangeText: String,
    ): SkyTemplatePhpReference {
        val skyRefs = filterIsInstance<SkyTemplatePhpReference>()
        return skyRefs.firstOrNull { ref ->
            val range = ref.rangeInElement
            ref.element.text.substring(range.startOffset, range.endOffset) == expectedRangeText &&
                ref.toString().contains(kind.name)
        } ?: error(
            "no $kind ref covering '$expectedRangeText' in: ${skyRefs.toList()}"
        )
    }

    private fun Array<PsiReference>.firstSkyRefByText(expectedRangeText: String): PsiReference {
        val skyRefs = filterIsInstance<SkyTemplatePhpReference>()
        return skyRefs.firstOrNull { ref ->
            val range = ref.rangeInElement
            ref.element.text.substring(range.startOffset, range.endOffset) == expectedRangeText
        } ?: error("no ref covering '$expectedRangeText' in: ${skyRefs.toList()}")
    }

    /** Tiny shim so test code reads naturally. */
    private fun fail(message: String): Nothing = error(message)

    @Suppress("UNUSED_PARAMETER")
    private fun assertResolved(label: String, element: PsiElement?) {
        assertNotNull("$label: PhpIndex returned null — fixture probably not indexed", element)
    }
}
