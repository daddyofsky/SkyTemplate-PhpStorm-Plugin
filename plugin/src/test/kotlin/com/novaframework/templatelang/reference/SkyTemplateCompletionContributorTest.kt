package com.novaframework.templatelang.reference

import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.PhpIndex
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * P3-8 coverage: `SkyTemplateCompletionContributor.lookupClasses` must share
 * [SkyTemplatePhpReference]'s candidate-FQN chain (useClass alias →
 * configured namespace → global namespace → simple-name fallback) so
 * completion and Ctrl+Click/Find-Usages resolve the SAME class.
 *
 * Driven directly against the (internal, test-visible) resolution helper
 * rather than through `myFixture.completeBasic()` — this project's light
 * test fixture doesn't reliably register extension-point contributors (see
 * `SkyTemplateReferenceIntegrationTest`'s class doc), so exercising the full
 * completion UI pipeline here would be flaky for reasons unrelated to this
 * logic.
 */
class SkyTemplateCompletionContributorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
            }
        )
    }

    fun testUseClassAlias_resolvesClassForMemberCompletion() {
        myFixture.addFileToProject(
            "AliasedClass.php",
            """
            <?php
            namespace App\Helpers;
            class Foo {
                public static function tag(): string { return ''; }
                const KIND = 1;
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=Bar::tag()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
                useClass = mutableListOf("App\\Helpers\\Foo as Bar")
            }
        )

        val contributor = SkyTemplateCompletionContributor()
        val settings = TemplateLangSettings.getInstance(project)
        val classes = contributor.lookupClasses(PhpIndex.getInstance(project), settings, "Bar")
        assertTrue(
            "useClass alias `Bar` must resolve to App\\Helpers\\Foo for member completion, got $classes",
            classes.any { it.fqn == "\\App\\Helpers\\Foo" },
        )
    }

    fun testUseClassAliasWithoutExplicitAlias_usesBasename() {
        myFixture.addFileToProject(
            "Widget.php",
            """
            <?php
            namespace App\Helpers;
            class Widget {
                public static function render(): string { return ''; }
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=Widget::render()}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
                useClass = mutableListOf("App\\Helpers\\Widget")
            }
        )

        val contributor = SkyTemplateCompletionContributor()
        val settings = TemplateLangSettings.getInstance(project)
        val classes = contributor.lookupClasses(PhpIndex.getInstance(project), settings, "Widget")
        assertTrue(
            "basename alias `Widget` must resolve to App\\Helpers\\Widget, got $classes",
            classes.any { it.fqn == "\\App\\Helpers\\Widget" },
        )
    }

    fun testNoAlias_fallsBackToConfiguredNamespace() {
        myFixture.addFileToProject(
            "Ns.php",
            """
            <?php
            namespace App;
            class Direct {
                const X = 1;
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=Direct::X}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "App"
            }
        )

        val contributor = SkyTemplateCompletionContributor()
        val settings = TemplateLangSettings.getInstance(project)
        val classes = contributor.lookupClasses(PhpIndex.getInstance(project), settings, "Direct")
        assertTrue(
            "configured namespace must still resolve `Direct`, got $classes",
            classes.any { it.fqn == "\\App\\Direct" },
        )
    }

    fun testNoMatch_fallsBackToSimpleNameAcrossNamespaces() {
        myFixture.addFileToProject(
            "Deep.php",
            """
            <?php
            namespace Some\Deep\Ns;
            class Elsewhere {
                const Y = 1;
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=Elsewhere::Y}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        // namespace = "\" (root) — the FQN chain (\Elsewhere) misses; the
        // simple-name fallback must still find it under Some\Deep\Ns.

        val contributor = SkyTemplateCompletionContributor()
        val settings = TemplateLangSettings.getInstance(project)
        val classes = contributor.lookupClasses(PhpIndex.getInstance(project), settings, "Elsewhere")
        assertTrue(
            "simple-name fallback must find Elsewhere across namespaces, got $classes",
            classes.any { it.fqn == "\\Some\\Deep\\Ns\\Elsewhere" },
        )
    }
}
