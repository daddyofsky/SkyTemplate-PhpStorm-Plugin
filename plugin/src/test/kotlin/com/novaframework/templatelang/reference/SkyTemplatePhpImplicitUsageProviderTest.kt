package com.novaframework.templatelang.reference

import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.PhpIndex
import com.novaframework.templatelang.settings.TemplateLangSettings
import com.jetbrains.php.lang.psi.elements.Function as PhpFunction

/**
 * Integration tests for [SkyTemplatePhpImplicitUsageProvider]. The provider
 * reports a PHP declaration as "implicitly used" when its name appears in
 * any project `.sky` / `.html` file via the IntelliJ platform's word
 * index. Uses [IndexingTestUtil.waitUntilIndexesAreReady] to ensure the
 * fixture's word index includes the template-side hits before the
 * provider is queried.
 */
class SkyTemplatePhpImplicitUsageProviderTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
            }
        )
    }

    fun testFunctionUsedInHtmlTemplateMarkedImplicit() {
        myFixture.addFileToProject(
            "defs.php",
            """
            <?php
            function templateOnlyFn(): void {}
            """.trimIndent()
        )
        myFixture.addFileToProject("page.html", "<p>{=templateOnlyFn()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val fn = phpFunction("templateOnlyFn") ?: error("templateOnlyFn not in PhpIndex")
        assertTrue(
            "function used only from .html template should be implicit-used",
            SkyTemplatePhpImplicitUsageProvider().isImplicitUsage(fn),
        )
    }

    fun testFunctionUsedInSkyPartialMarkedImplicit() {
        myFixture.addFileToProject(
            "defs.php",
            """
            <?php
            function partialUsed(): void {}
            """.trimIndent()
        )
        myFixture.addFileToProject("snippet.sky", "{=partialUsed()}")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val fn = phpFunction("partialUsed") ?: error("partialUsed not in PhpIndex")
        assertTrue(
            "function used only from .sky partial should be implicit-used (relies on " +
                "SkyTemplateFindUsagesProvider's WordsScanner registration)",
            SkyTemplatePhpImplicitUsageProvider().isImplicitUsage(fn),
        )
    }

    fun testFunctionNotReferencedAnywhereNotImplicit() {
        myFixture.addFileToProject(
            "defs.php",
            """
            <?php
            function neverCalled(): void {}
            """.trimIndent()
        )
        myFixture.addFileToProject("page.html", "<p>nothing relevant here</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val fn = phpFunction("neverCalled") ?: error("neverCalled not in PhpIndex")
        assertFalse(
            "function not appearing in any template should not be implicit-used",
            SkyTemplatePhpImplicitUsageProvider().isImplicitUsage(fn),
        )
    }

    fun testFunctionMentionedOnlyInProseInTemplateStillCountsAsUsed() {
        // Documented trade-off: the word-index check is name-only and does
        // NOT verify the hit is structurally inside a `{ … }` construct.
        // For dead-code analysis, false-positive "used" is the safe direction.
        myFixture.addFileToProject(
            "defs.php",
            """
            <?php
            function proseMentionFn(): void {}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "page.html",
            "<p>To format prices, use proseMentionFn for full output.</p>",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val fn = phpFunction("proseMentionFn") ?: error("proseMentionFn not in PhpIndex")
        assertTrue(
            "name-mention in plain prose should still mark as implicit-used (deliberate " +
                "false-positive bias for dead-code safety)",
            SkyTemplatePhpImplicitUsageProvider().isImplicitUsage(fn),
        )
    }

    fun testPluginDisabledNotImplicit() {
        myFixture.addFileToProject(
            "defs.php",
            """
            <?php
            function disabledCheckFn(): void {}
            """.trimIndent()
        )
        myFixture.addFileToProject("page.html", "<p>{=disabledCheckFn()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply { enabled = false }
        )

        val fn = phpFunction("disabledCheckFn") ?: error("disabledCheckFn not in PhpIndex")
        assertFalse(
            "with plugin disabled the provider must short-circuit to false",
            SkyTemplatePhpImplicitUsageProvider().isImplicitUsage(fn),
        )
    }

    fun testNonPhpDeclarationNotConsidered() {
        myFixture.addFileToProject("page.html", "<p>{=anything()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val htmlFile = myFixture.configureByText("other.html", "<p>x</p>")
        assertFalse(
            "non-PHP elements (here: an HTML file) must not be classified",
            SkyTemplatePhpImplicitUsageProvider().isImplicitUsage(htmlFile),
        )
    }

    private fun phpFunction(name: String): PhpFunction? =
        PhpIndex.getInstance(project)
            .getFunctionsByFQN("\\$name")
            .firstOrNull()
}
