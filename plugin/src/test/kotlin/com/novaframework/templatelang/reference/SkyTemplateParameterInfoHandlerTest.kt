package com.novaframework.templatelang.reference

import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import com.jetbrains.php.lang.psi.elements.Method
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Regression coverage for P1-2: `Cls::method(` class-name extraction in
 * [SkyTemplateParameterInfoHandler] dropped the class name's last character
 * (`substring(ks, ke - 1)` instead of `substring(ks, ke)`), so Ctrl+P never
 * resolved any static-method call.
 */
class SkyTemplateParameterInfoHandlerTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
            }
        )
    }

    fun testStaticMethodCallShowsParameterInfo() {
        myFixture.addFileToProject(
            "Widget.php",
            """
            <?php
            class Widget {
                public static function render(string ${'$'}text, int ${'$'}size = 1): string { return ${'$'}text; }
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=Widget::render(<caret>\"hi\")}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = SkyTemplateParameterInfoHandler()
        val context = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val host = handler.findElementForParameterInfo(context)

        assertNotNull("Ctrl+P must locate a call context for Widget::render(...)", host)
        val items = context.itemsToShow
        assertNotNull("resolved targets must be populated", items)
        assertTrue(
            "expected Widget::render method among items to show",
            items!!.any { it is Method && it.name == "render" },
        )
    }

    /**
     * Single-character class names hit the same off-by-one at its most
     * extreme — the extracted name became an empty string, which the
     * handler then treated as "no class" and misresolved the call as a
     * global function lookup instead.
     */
    fun testSingleCharClassNameShowsParameterInfo() {
        myFixture.addFileToProject(
            "A.php",
            """
            <?php
            class A {
                public static function run(int ${'$'}x): int { return ${'$'}x; }
            }
            """.trimIndent()
        )
        myFixture.configureByText("page.html", "<p>{=A::run(<caret>1)}</p>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val handler = SkyTemplateParameterInfoHandler()
        val context = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val host = handler.findElementForParameterInfo(context)

        assertNotNull("Ctrl+P must locate a call context for A::run(...)", host)
        val items = context.itemsToShow
        assertNotNull("resolved targets must be populated", items)
        assertTrue(
            "expected A::run method among items to show",
            items!!.any { it is Method && it.name == "run" },
        )
    }

    // ── P2-14: pipe filter arg-index highlighting ───────────────────────────

    private fun currentPipeArgIndex(html: String): Int {
        myFixture.configureByText("page.html", html)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val handler = SkyTemplateParameterInfoHandler()
        val updateContext = MockUpdateParameterInfoContext(myFixture.editor, myFixture.file)
        val owner = handler.findElementForUpdatingParameterInfo(updateContext)
        assertNotNull("must locate a pipe call context", owner)
        handler.updateParameterInfo(owner!!, updateContext)
        return updateContext.currentParameter
    }

    /**
     * `##` not written — the compiler auto-prepends it at PHP arg slot 0,
     * so the first visible token (`a`) is PHP arg 1, not arg 0.
     */
    fun testPipeFilter_autoPrependedHash_shiftsArgIndexByOne() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fn9(string ${'$'}piped, string ${'$'}a, string ${'$'}b) {}
            """.trimIndent()
        )
        assertEquals(1, currentPipeArgIndex("<p>{x|fn9=<caret>a, b}</p>"))
        assertEquals(2, currentPipeArgIndex("<p>{x|fn9=a, <caret>b}</p>"))
    }

    /** Explicit `##` — no shift; the placeholder itself occupies slot 0. */
    fun testPipeFilter_explicitHash_noShift() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fn10(string ${'$'}piped, string ${'$'}a, string ${'$'}b) {}
            """.trimIndent()
        )
        assertEquals(1, currentPipeArgIndex("<p>{x|fn10=##, <caret>a, b}</p>"))
    }

    /** A comma inside a quoted string argument must not be counted as a separator. */
    fun testPipeFilter_quotedComma_notCountedAsSeparator() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fn11(string ${'$'}piped, string ${'$'}a, string ${'$'}b) {}
            """.trimIndent()
        )
        // tokens (no ##): "a,b" (one quoted positional), then `c` — `c` is
        // PHP arg 2, not arg 3 (which it would be if the comma inside the
        // quotes were counted as a separator).
        assertEquals(2, currentPipeArgIndex("<p>{x|fn11='a,b', <caret>c}</p>"))
    }

    /**
     * A named arg written BEFORE a positional one must not shift the
     * positional's index — the compiler reorders named args after all
     * positional ones (`array_merge($positional, $named)`).
     */
    fun testPipeFilter_namedArgBeforePositional_doesNotShiftIndex() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function fn12(string ${'$'}piped, string ${'$'}a, string ${'$'}b) {}
            """.trimIndent()
        )
        assertEquals(1, currentPipeArgIndex("<p>{x|fn12=label=v, <caret>a}</p>"))
    }
}
