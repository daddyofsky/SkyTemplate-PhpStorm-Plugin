package com.novaframework.templatelang.sky

import com.intellij.lang.html.HTMLLanguage
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Fixture-level checks for [SkyTemplateLineIndentProvider] — the smart
 * indent EP that answers the platform's line-indent query with the
 * relative HTML+Sky depth.
 */
class SkyTemplateLineIndentProviderTest : BasePlatformTestCase() {

    private val provider = SkyTemplateLineIndentProvider()

    private fun lineIndent(filename: String, source: String): String? {
        myFixture.configureByText(filename, source)
        return provider.getLineIndent(
            project,
            myFixture.editor,
            myFixture.file.language,
            myFixture.caretOffset,
        )
    }

    fun testBodyLineInsideSkyBlockIsOneStepUnderOpener() {
        val indent = lineIndent(
            "a.html",
            "<div>\n    {?cond}\n<caret>\n    {/}\n</div>",
        )
        assertEquals("        ", indent)
    }

    fun testIndentIsRelativeToNearestParentNotWholeFile() {
        // Unindented ancestors — the answer is relative to `{?cond}`'s
        // actual indent (col 0), one step.
        val indent = lineIndent(
            "a.html",
            "<html>\n<body>\n<div>\n{?cond}\n<caret>\n{/}\n</div>\n</body>\n</html>",
        )
        assertEquals("    ", indent)
    }

    fun testTopLevelLineFallsBackToHost() {
        // No enclosing opener → null, so the host formatter's indent wins.
        val indent = lineIndent("a.html", "<p>x</p>\n<caret>")
        assertNull(indent)
    }

    fun testSuitableForHtmlAndSkyButNotOthers() {
        assertTrue(provider.isSuitableFor(HTMLLanguage.INSTANCE))
        assertTrue(provider.isSuitableFor(SkyTemplateLanguage))
        assertFalse(provider.isSuitableFor(com.intellij.lang.Language.ANY))
        assertFalse(provider.isSuitableFor(null))
    }
}
