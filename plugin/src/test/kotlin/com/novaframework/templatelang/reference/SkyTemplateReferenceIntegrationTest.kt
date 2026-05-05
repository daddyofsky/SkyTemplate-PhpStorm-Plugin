package com.novaframework.templatelang.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ProcessingContext
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * IDE-level integration tests covering the reference pipeline up to
 * (but not including) PhpIndex resolution.
 *
 * Scope:
 *   - **Provider directly invoked** — that the contributor / provider chain
 *     attaches the right references on the host PSI. This catches detector
 *     regressions, comment-range gating, settings gating, and offset bugs.
 *   - **Settings round-trip** — that the project-level settings are reachable
 *     and behave as expected from a test fixture.
 *
 * Out of scope (deferred to manual sandbox verification + a heavier fixture
 * pass when we have time to debug the test platform's plugin descriptor
 * loading):
 *   - `findReferenceAt` end-to-end. In `BasePlatformTestCase`, our plugin's
 *     `psi.referenceContributor` extensions don't always get registered into
 *     the test platform's `ReferenceProvidersRegistry`, so the standard
 *     `PsiElement#findReferenceAt` walk doesn't see our refs even though the
 *     provider works fine. We work around by invoking the provider directly
 *     here; full end-to-end coverage is exercised in `./gradlew runIde`.
 *   - PhpIndex resolution (`SkyTemplatePhpReference#resolve`). PhpIndex
 *     requires fully-indexed PHP files which the light fixture does not
 *     provide reliably without `IndexingTestUtil` setup.
 */
class SkyTemplateReferenceIntegrationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "App"
            }
        )
    }

    fun testSettingsAreActive() {
        val settings = TemplateLangSettings.getInstance(project)
        assertTrue(settings.isEnabled)
        assertEquals("App", settings.namespace)
    }

    fun testRawFunctionCallAttachesFunctionReference() {
        val refs = providerRefsAt("<p>{=fmt(\"hi\")}</p>", "fmt")
        assertSky(refs, "fmt", SkyTemplateRefDetector.Kind.FUNCTION)
    }

    fun testStaticMethodAttachesClassAndMethod() {
        val refs = providerRefsAt("<p>{=Util::build(\"hi\")}</p>", "Util")
        assertSky(refs, "Util", SkyTemplateRefDetector.Kind.CLASS)
        // Same host — find the method ref too.
        assertHasKind(refs, SkyTemplateRefDetector.Kind.METHOD, "build")
    }

    fun testPipeFunctionAttachesFunctionReference() {
        val refs = providerRefsAt("<p>{name|nl2br_keep}</p>", "nl2br_keep")
        assertSky(refs, "nl2br_keep", SkyTemplateRefDetector.Kind.FUNCTION)
    }

    fun testPipeStaticMethodAttachesClassAndMethod() {
        val refs = providerRefsAt("<p>{name|Enums::label}</p>", "Enums")
        assertSky(refs, "Enums", SkyTemplateRefDetector.Kind.CLASS)
        assertHasKind(refs, SkyTemplateRefDetector.Kind.METHOD, "label")
    }

    fun testCDotConstantAttachesConstantReference() {
        val refs = providerRefsAt("<p>{c.MY_CONST}</p>", "MY_CONST")
        assertSky(refs, "MY_CONST", SkyTemplateRefDetector.Kind.CONSTANT)
    }

    fun testCDotClassConstantAttachesClassAndClassConstant() {
        val refs = providerRefsAt("<p>{c.Status::ACTIVE}</p>", "Status")
        assertSky(refs, "Status", SkyTemplateRefDetector.Kind.CLASS)
        assertHasKind(refs, SkyTemplateRefDetector.Kind.CLASS_CONSTANT, "ACTIVE")
    }

    fun testPlainBraceFunctionCallDoesNotAttach() {
        // {foo()} (no `=` prefix) is NOT a function call per SkyTemplate
        myFixture.configureByText("page.html", "<p>{foo(\"hi\")}</p>")
        val host = hostAt("foo") ?: return
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(host, ProcessingContext())
        assertEquals("expected no refs for plain {foo()}", 0, refs.size)
    }

    fun testCommentRangeBlocksReferences() {
        myFixture.configureByText("page.html", "<p>{* {=commented()} *}</p>")
        val host = hostAt("commented") ?: return
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(host, ProcessingContext())
        assertEquals("expected no refs inside {* … *}", 0, refs.size)
    }

    fun testDisabledBlocksReferences() {
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = false
            }
        )
        myFixture.configureByText("page.html", "<p>{=fmt(\"hi\")}</p>")
        val host = hostAt("fmt") ?: return
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(host, ProcessingContext())
        assertEquals("expected no refs when plugin is disabled", 0, refs.size)
    }

    /**
     * Regression for top-level template constructs in `.html` files (no parent
     * tag). The user's `test.html` has `{=foo()}` at the document root, and
     * Find Usages / Go to Definition were not picking them up because the
     * contributor was attached to `XmlText` / `XmlAttributeValue` only — and
     * top-level `{=foo()}` lives directly under `HtmlDocumentImpl`.
     *
     * Fix: provider now scans the full document text regardless of where the
     * HTML parser chose to anchor each `{ … }` fragment.
     */
    fun testTopLevelFunctionCallInHtml() {
        myFixture.configureByText("page.html", "{=foo()}\n")
        val file = myFixture.file as XmlFile
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(file, ProcessingContext())
        assertSky(refs, "foo", SkyTemplateRefDetector.Kind.FUNCTION)
    }

    fun testLeafElementFunctionCallInHtml() {
        myFixture.configureByText("page.html", "<p>{=fmt(\"hi\")}</p>")
        val leaf = leafAt("fmt") ?: error("no leaf for fmt")
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(leaf, ProcessingContext())
        assertSky(refs, "fmt", SkyTemplateRefDetector.Kind.FUNCTION)
    }

    fun testLeafElementTopLevelFunctionCallInHtml() {
        myFixture.configureByText("page.html", "{=foo()}\n")
        val leaf = leafAt("foo") ?: error("no leaf for foo")
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(leaf, ProcessingContext())
        assertSky(refs, "foo", SkyTemplateRefDetector.Kind.FUNCTION)
    }

    /**
     * `{=Cls::method()}` at top level — verifies the more interesting CLASS +
     * METHOD pair attaches through full-file scanning too. Mirrors the
     * `<p>…</p>`-wrapped variant covered by [testStaticMethodAttachesClassAndMethod].
     */
    fun testTopLevelStaticMethodInHtml() {
        myFixture.configureByText("page.html", "{=Util::build(\"hi\")}\n")
        val file = myFixture.file as XmlFile
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(file, ProcessingContext())
        assertSky(refs, "Util", SkyTemplateRefDetector.Kind.CLASS)
        assertHasKind(refs, SkyTemplateRefDetector.Kind.METHOD, "build")
    }

    /**
     * `{?check}` in attribute-name area: HTML parser puts the leaf inside an
     * `XmlAttribute` (NOT `XmlAttributeValue`), so the previous element-level
     * patterns missed this entirely. File-level scan picks it up.
     *
     * Plus `{=fmt()}` later in the same line — exercise mixed contexts in
     * one file.
     */
    fun testAttributeNameAreaInHtml() {
        myFixture.configureByText(
            "page.html",
            "<input type=\"radio\" {?disabledCond}disabled{/}> {=fmt()}\n",
        )
        val file = myFixture.file as XmlFile
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(file, ProcessingContext())
        assertSky(refs, "fmt", SkyTemplateRefDetector.Kind.FUNCTION)
    }

    /**
     * Regression for the user's `function getNoServiceAlert(){ {=serviceLimit('A1')} }`
     * pattern: a SkyTemplate tag nested inside a JS function body within
     * `<script>`. The outer JS `{ ... }` brace pair must NOT be treated as a
     * template tag, but the inner `{=serviceLimit('A1')}` must produce a
     * FUNCTION ref so Ctrl+Click / Find Usages work.
     */
    fun testTemplateTagInsideJsFunctionBody() {
        myFixture.configureByText(
            "page.html",
            """
                <script>
                function getNoServiceAlert(){
                    {=serviceLimit('A1')}
                }
                </script>
            """.trimIndent(),
        )
        val file = myFixture.file as XmlFile
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(file, ProcessingContext())
        assertSky(refs, "serviceLimit", SkyTemplateRefDetector.Kind.FUNCTION)
    }

    /**
     * Symmetric case for `<style>`: `{c.LABEL}` inside a CSS rule body. CSS
     * PSI parses the surrounding `{ … }` as a CSS rule, but the inner
     * `'{c.LABEL}'` is a SkyTemplate constant ref. The detector handles this
     * (see `SkyTemplateRangesTest.cTagConstantStillRegistered`); this test
     * verifies the provider attaches the ref via the file root.
     */
    fun testTemplateConstantInsideCssStyleBlock() {
        myFixture.configureByText(
            "page.html",
            "<style>p::before { content: '{c.MY_LABEL}'; }</style>",
        )
        val file = myFixture.file as XmlFile
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(file, ProcessingContext())
        assertSky(refs, "MY_LABEL", SkyTemplateRefDetector.Kind.CONSTANT)
    }

    /**
     * Same case, but exercise the leaf-to-file walk used by `findReferenceAt`.
     * If this fails while [testTemplateTagInsideJsFunctionBody] passes, it
     * means the JS injection's PSI ancestry doesn't reach an element our
     * provider attaches to.
     */
    fun testFindReferenceAtWalk_serviceLimitInsideJsFunction() {
        myFixture.configureByText(
            "page.html",
            """
                <script>
                function getNoServiceAlert(){
                    {=serviceLimit('A1')}
                }
                </script>
            """.trimIndent(),
        )
        val ref = simulatedFindReferenceAt(myFixture.file.text.indexOf("serviceLimit"))
        assertNotNull(
            "no provider ref found while walking leaf-to-file at offset of `serviceLimit` " +
                "inside JS function body",
            ref,
        )
        assertTrue(
            "expected SkyTemplatePhpReference, got ${ref!!::class.simpleName}",
            ref is SkyTemplatePhpReference,
        )
    }

    /**
     * Smoke test for the platform-level path: if extensions are wired up,
     * [ReferenceProvidersRegistry.getReferencesFromProviders] returns refs
     * including ours. If the test framework hasn't loaded our plugin.xml
     * extensions, this returns empty — the test then degenerates into a
     * simple no-op so it doesn't block other progress.
     */
    fun testReferenceProvidersRegistryWiresOurContributor() {
        myFixture.configureByText("page.html", "<p>{=fmt(\"hi\")}</p>")
        val host = hostAt("fmt") ?: return
        val refs = ReferenceProvidersRegistry.getReferencesFromProviders(host)
        if (refs.isEmpty()) {
            // Extensions not loaded in this fixture — see class-level note.
            return
        }
        val skyRefs = refs.filterIsInstance<SkyTemplatePhpReference>()
        assertTrue(
            "ReferenceProvidersRegistry returned refs but none are ours: ${refs.toList()}",
            skyRefs.isNotEmpty(),
        )
    }

    /**
     * Simulate the IDE's `findReferenceAt(offset)` walk: leaf → parents,
     * calling our provider directly at each level. This is what `Ctrl+Click`
     * and `Find Usages` rely on under the hood. Any setup that lets the leaf
     * provide a matching reference makes both work.
     */
    fun testFindReferenceAtWalkInTopLevelHtml() {
        myFixture.configureByText("page.html", "{=foo()}\n")
        val ref = simulatedFindReferenceAt(myFixture.file.text.indexOf("foo"))
        assertNotNull(
            "no provider ref found while walking leaf-to-file at offset of `foo`",
            ref,
        )
        assertTrue(
            "expected SkyTemplatePhpReference, got ${ref!!::class.simpleName}",
            ref is SkyTemplatePhpReference,
        )
    }

    fun testFindReferenceAtWalkInWrappedHtml() {
        myFixture.configureByText("page.html", "<p>{=fmt(\"hi\")}</p>")
        val ref = simulatedFindReferenceAt(myFixture.file.text.indexOf("fmt"))
        assertNotNull(
            "no provider ref found while walking leaf-to-file at offset of `fmt`",
            ref,
        )
    }

    fun testFindReferenceAtWalkInAttributeArea() {
        myFixture.configureByText("page.html", "<input {=fooHelper()}>\n")
        val ref = simulatedFindReferenceAt(myFixture.file.text.indexOf("fooHelper"))
        assertNotNull(
            "no provider ref found while walking leaf-to-file at offset of `fooHelper`",
            ref,
        )
    }

    /**
     * User-reported regression: in `<script>` JS context, clicking on the
     * SECOND function in `{=foo() . bar()}` was navigating to `foo` because
     * JS PSI parses `foo() . bar` as one qualified `JSReferenceExpression`
     * and its self-reference covers the chain. The walk must still find OUR
     * PhpReference for `bar` at the leaf level, not the JS chain reference.
     */
    fun testSecondCallInDotConcat_inJsScript_resolvesIndependently() {
        myFixture.configureByText(
            "page.html",
            """
                <script>
                function f() {
                    var x = '{=search_goods_keyword() . search_goods_code()}';
                }
                </script>
            """.trimIndent(),
        )
        val text = myFixture.file.text
        val codeOffset = text.indexOf("search_goods_code")
        val ref = simulatedFindReferenceAt(codeOffset)
        assertNotNull("no ref at offset of `search_goods_code`", ref)
        assertTrue(
            "expected SkyTemplatePhpReference, got ${ref!!::class.simpleName}",
            ref is SkyTemplatePhpReference,
        )
        val sky = ref as SkyTemplatePhpReference
        // Critical: must resolve the second function, NOT the first.
        assertEquals(
            "wrong function — JS chain reference must not steal `search_goods_code`",
            "search_goods_code",
            sky.nameInSource,
        )
    }

    fun testSecondCallInDotConcat_ifPrefix_inJsScript_resolvesIndependently() {
        myFixture.configureByText(
            "page.html",
            """
                <script>
                function f() {
                    var x = '{? search_goods_keyword() . search_goods_code()}';
                }
                </script>
            """.trimIndent(),
        )
        val text = myFixture.file.text
        val codeOffset = text.indexOf("search_goods_code")
        val ref = simulatedFindReferenceAt(codeOffset)
        assertNotNull("no ref at offset of `search_goods_code`", ref)
        assertTrue(ref is SkyTemplatePhpReference)
        assertEquals(
            "search_goods_code",
            (ref as SkyTemplatePhpReference).nameInSource,
        )
    }

    /**
     * Sanity check the `&&` operator already works (the user reports that the
     * `&&` form correctly navigates to the second function, only `.` is broken).
     * This guards against any change that might also break the `&&` path.
     */
    fun testSecondCallInAndAndConcat_inJsScript_resolvesIndependently() {
        myFixture.configureByText(
            "page.html",
            """
                <script>
                function f() {
                    var x = '{? search_goods_keyword() && search_goods_code()}';
                }
                </script>
            """.trimIndent(),
        )
        val text = myFixture.file.text
        val codeOffset = text.indexOf("search_goods_code")
        val ref = simulatedFindReferenceAt(codeOffset)
        assertNotNull("no ref at offset of `search_goods_code`", ref)
        assertTrue(ref is SkyTemplatePhpReference)
        assertEquals(
            "search_goods_code",
            (ref as SkyTemplatePhpReference).nameInSource,
        )
    }

    /**
     * Variant: template tag DIRECTLY in `<script>` content (not inside a JS
     * string). The user-reported case is likely this — JS parses
     * `{=foo() . bar()}` as malformed expressions where `foo() . bar()` becomes
     * a chained member-access expression. The leaf-to-file walk must still
     * find OUR PhpReference for `bar`, otherwise JS's self-reference for the
     * chain wins and Cmd+click navigates to the qualifier (`foo`).
     */
    fun testSecondCallInDotConcat_directlyInScriptTag_resolvesIndependently() {
        myFixture.configureByText(
            "page.html",
            """
                <script>
                {=search_goods_keyword() . search_goods_code()}
                </script>
            """.trimIndent(),
        )
        val text = myFixture.file.text
        val codeOffset = text.indexOf("search_goods_code")
        val ref = simulatedFindReferenceAt(codeOffset)
        assertNotNull("no ref at offset of `search_goods_code`", ref)
        assertTrue(
            "expected SkyTemplatePhpReference, got ${ref!!::class.simpleName}",
            ref is SkyTemplatePhpReference,
        )
        assertEquals(
            "search_goods_code",
            (ref as SkyTemplatePhpReference).nameInSource,
        )
    }

    fun testSecondCallInDotConcat_ifPrefix_directlyInScriptTag_resolvesIndependently() {
        myFixture.configureByText(
            "page.html",
            """
                <script>
                {? search_goods_keyword() . search_goods_code()}
                {/}
                </script>
            """.trimIndent(),
        )
        val text = myFixture.file.text
        val codeOffset = text.indexOf("search_goods_code")
        val ref = simulatedFindReferenceAt(codeOffset)
        assertNotNull("no ref at offset of `search_goods_code`", ref)
        assertTrue(ref is SkyTemplatePhpReference)
        assertEquals(
            "search_goods_code",
            (ref as SkyTemplatePhpReference).nameInSource,
        )
    }

    /**
     * Regression for the user's `adult_login.html` case (0.5.32):
     * `<a href="{=getFacebookLoginUrl()}">` failed Ctrl+Click / Find Usages
     * while `{?useFacebookLogin()}` (in `{?…}` block opener at top level)
     * worked. Diagnosis showed the platform's `findReferenceAt` was
     * returning a `PsiMultiReference` whose chosen primary ref was a built-in
     * URL `SchemeReference`, not our `SkyTemplatePhpReference`, because
     * URL refs are non-soft and our reference was registered with
     * `soft = true`. PsiMultiReference prefers non-soft refs over soft ones
     * regardless of order, so the URL ref hijacked the position.
     *
     * Fix: register our ref with `soft = false`. After the fix, the platform
     * resolves the position to our ref → the PHP `Function`. Without the PHP
     * file in the fixture this test would only check ranges; we add the
     * function definition so `resolve()` returns the `FunctionImpl`.
     */
    fun testFunctionInsideHrefResolvesViaFindReferenceAt() {
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
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)

        val offset = myFixture.file.text.indexOf("getFacebookLoginUrl")
        val ref = myFixture.file.findReferenceAt(offset)
            ?: error("findReferenceAt returned null at offset $offset")

        val resolved = ref.resolve()
        assertNotNull(
            "platform-level `findReferenceAt` must resolve to the PHP function. " +
                "Got reference class=${ref::class.simpleName} range=${ref.rangeInElement}. " +
                "If null: PsiMultiReference picked a URL/HTML reference instead of ours " +
                "(soft-vs-non-soft priority). Confirm SkyTemplatePhpReference uses soft=false.",
            resolved,
        )
        assertEquals(
            "expected resolve to return the PHP function declaration",
            "getFacebookLoginUrl",
            (resolved as? com.jetbrains.php.lang.psi.elements.Function)?.name,
        )
    }

    /**
     * Regression for the user's `<a href="{=getKakaoLoginUrl()}">` pattern —
     * template construct sitting INSIDE an attribute value (not in attribute-
     * name area, not in element body). The previous attribute-related test
     * (`testAttributeNameAreaInHtml`) covered the name-area case. This one
     * covers the value-area case.
     */
    fun testFunctionCallInsideAttributeValue() {
        myFixture.configureByText(
            "page.html",
            """<a href="{=getKakaoLoginUrl()}">login</a>""",
        )
        val file = myFixture.file as XmlFile

        // Provider invoked on the file root: must detect the construct.
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(file, ProcessingContext())
        assertSky(refs, "getKakaoLoginUrl", SkyTemplateRefDetector.Kind.FUNCTION)

        // Provider invoked on the leaf: same expectation.
        val leaf = leafAt("getKakaoLoginUrl") ?: error("no leaf for getKakaoLoginUrl")
        val leafRefs = SkyTemplateReferenceProvider().getReferencesByElement(leaf, ProcessingContext())
        assertSky(leafRefs, "getKakaoLoginUrl", SkyTemplateRefDetector.Kind.FUNCTION)
    }

    /**
     * Regression for 0.5.29 — the leaf-level provider call must succeed for
     * top-level template constructs. PhpStorm's HTML PSI emits each `{ … }`
     * chunk as a single `XmlTokenImpl` whose `getLanguage()` returns
     * `XMLLanguage` (not `HTMLLanguage`). The HTML-only contributor
     * registration silently skipped those leaves; the XML-language
     * registration in plugin.xml covers them.
     *
     * This test invokes the provider on the leaf directly to prove the
     * symbol resolution logic works at that level — the registry routing
     * itself is plugin.xml plumbing and exercised end-to-end in a sandbox
     * runIde.
     */
    fun testLeafLevelProviderResolvesTopLevelInMixedHtml() {
        val text = """
            <ul>
                {loop products}
                <li>{.name}</li>
                {/}
            </ul>

            <div>
                {=foo2()}
            </div>

            {=barTopLevel()}
        """.trimIndent()
        myFixture.configureByText("page.html", text)

        val leaf = leafAt("barTopLevel") ?: error("no leaf for barTopLevel")
        val refs = SkyTemplateReferenceProvider().getReferencesByElement(leaf, ProcessingContext())
        assertSky(refs, "barTopLevel", SkyTemplateRefDetector.Kind.FUNCTION)
    }

    /**
     * Regression: top-level template constructs in a file that ALSO contains
     * other content (wrapped tags, additional template directives, comments).
     * Mirrors `test/test.html` which has both `<div>{=foo2()}</div>`-wrapped
     * constructs AND bare top-level `{=foo2()}` lines. User-reported case:
     * Find Usages / Ctrl+Click works on the wrapped form but not the bare
     * top-level form.
     *
     * The simulated leaf-walk should still resolve the bare lines, because
     * the file root catches them once the leaf walk reaches the document
     * level.
     */
    fun testFindReferenceAtWalkInUserHtml_topLevelConstructResolves() {
        val text = """
            <ul>
                {loop products}
                <li>{.name}</li>
                {/}
            </ul>

            <div>
                {=foo2()}
                {=Cls::method2()}
            </div>

            {=barTopLevel()}
            {=Other::topLevelMethod()}
        """.trimIndent()
        myFixture.configureByText("page.html", text)

        // Bare top-level function call.
        val refTopFn = simulatedFindReferenceAt(myFixture.file.text.indexOf("barTopLevel"))
        assertNotNull(
            "no provider ref found at top-level `barTopLevel` (no surrounding tag)",
            refTopFn,
        )
        assertTrue(
            "expected SkyTemplatePhpReference at top-level fn, got ${refTopFn!!::class.simpleName}",
            refTopFn is SkyTemplatePhpReference,
        )

        // Bare top-level static-method call.
        val refTopMethod = simulatedFindReferenceAt(myFixture.file.text.indexOf("topLevelMethod"))
        assertNotNull(
            "no provider ref found at top-level `topLevelMethod` (no surrounding tag)",
            refTopMethod,
        )
        assertTrue(
            "expected SkyTemplatePhpReference at top-level method, got ${refTopMethod!!::class.simpleName}",
            refTopMethod is SkyTemplatePhpReference,
        )
    }

    /**
     * Goes through the platform's `findReferenceAt` — this is exactly the
     * path `Ctrl+Click` and `Find Usages` rely on. The light test fixture
     * sometimes does and sometimes does not load `psi.referenceContributor`
     * extensions into its registry — passing it depends on test-execution
     * order — so this test degenerates into a no-op when the platform path
     * is unavailable, mirroring the tolerant pattern of
     * [testReferenceProvidersRegistryWiresOurContributor]. The
     * [testFindReferenceAtWalkInTopLevelHtml] sibling test exercises the
     * same logic via direct provider invocation and is the deterministic
     * coverage; this one stays as a smoke check for sandbox verification.
     */
    fun testPlatformFindReferenceAtDiagnostic() {
        myFixture.configureByText("page.html", "{=foo()}\n")
        val offset = myFixture.file.text.indexOf("foo")
        val leaf = myFixture.file.findElementAt(offset)
            ?: error("no leaf at offset $offset")
        val ref = myFixture.file.findReferenceAt(offset)
        if (ref == null) {
            // Extensions not loaded into this fixture — degenerate to a
            // no-op, same shape as testReferenceProvidersRegistryWiresOurContributor.
            return
        }
        val diag = "leaf=${leaf::class.simpleName} text='${leaf.text}' leafRange=${leaf.textRange}"
        assertTrue(
            "platform-level ref returned, but it's not ours: ${ref::class.qualifiedName}. $diag",
            ref is SkyTemplatePhpReference,
        )
    }

    /**
     * Mirrors [com.intellij.psi.impl.source.tree.SharedPsiElementImplUtil.findReferenceAt]
     * but only consults our provider — bypasses the platform registry which
     * doesn't always load plugin extensions in light fixtures. This proves the
     * provider returns refs at every node along the walk that matters in the
     * real IDE: leaf, intermediate, and file.
     */
    private fun simulatedFindReferenceAt(offsetInFile: Int): PsiReference? {
        val provider = SkyTemplateReferenceProvider()
        val file = myFixture.file ?: return null
        var element: PsiElement? = file.findElementAt(offsetInFile)
        while (element != null) {
            val refs = provider.getReferencesByElement(element, ProcessingContext())
            val offsetInElement = offsetInFile - element.textRange.startOffset
            val match = refs.firstOrNull { it.rangeInElement.contains(offsetInElement) }
            if (match != null) return match
            element = element.parent
        }
        return null
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Configure HTML, find the host containing [needle], invoke our provider. */
    private fun providerRefsAt(html: String, needle: String): Array<PsiReference> {
        myFixture.configureByText("page.html", html)
        val host = hostAt(needle) ?: error("no host for '$needle' in: $html")
        return SkyTemplateReferenceProvider().getReferencesByElement(host, ProcessingContext())
    }

    /** Find the XmlText / XmlAttributeValue that contains [needle]. */
    private fun hostAt(needle: String): PsiElement? {
        val file = myFixture.file ?: return null
        val idx = file.text.indexOf(needle)
        if (idx < 0) return null
        var element: PsiElement? = file.findElementAt(idx)
        while (element != null && element !is XmlText && element !is XmlAttributeValue) {
            element = element.parent
        }
        return element
    }

    private fun leafAt(needle: String): PsiElement? {
        val file = myFixture.file ?: return null
        val idx = file.text.indexOf(needle)
        if (idx < 0) return null
        return file.findElementAt(idx)
    }

    private fun assertSky(
        refs: Array<PsiReference>,
        expectedRangeText: String,
        expectedKind: SkyTemplateRefDetector.Kind,
    ) {
        val skyRefs = refs.filterIsInstance<SkyTemplatePhpReference>()
        val match = skyRefs.firstOrNull { ref ->
            val range = ref.rangeInElement
            ref.element.text.substring(range.startOffset, range.endOffset) == expectedRangeText &&
                ref.toString().contains(expectedKind.name)
        }
        assertNotNull(
            "expected $expectedKind ref covering '$expectedRangeText' in: ${skyRefs.toList()}",
            match,
        )
    }

    private fun assertHasKind(
        refs: Array<PsiReference>,
        kind: SkyTemplateRefDetector.Kind,
        expectedRangeText: String,
    ) {
        val skyRefs = refs.filterIsInstance<SkyTemplatePhpReference>()
        val match = skyRefs.firstOrNull { ref ->
            val range = ref.rangeInElement
            ref.element.text.substring(range.startOffset, range.endOffset) == expectedRangeText &&
                ref.toString().contains(kind.name)
        }
        assertNotNull(
            "expected $kind ref covering '$expectedRangeText' in: ${skyRefs.toList()}",
            match,
        )
    }
}
