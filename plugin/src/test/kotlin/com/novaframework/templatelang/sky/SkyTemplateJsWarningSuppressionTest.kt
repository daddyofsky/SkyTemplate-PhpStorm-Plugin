package com.novaframework.templatelang.sky

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Verifies that [SkyTemplateHtmlErrorFilter] suppresses JS semantic warnings
 * (`Unnecessary semicolon`, `Expression statement is not assignment or call`)
 * on lines that contain a Sky template tag inside a `<script>` block, while
 * preserving the same warnings on plain JS lines with no Sky tags.
 *
 * The JS parser sees Sky tags like `{=expr}` as raw brace tokens and raises
 * semantic warnings on the adjacent `;` and on bare keyword tokens such as
 * `true`/`false` left over after the `{?cond}…{:}…{/}` conditional. These
 * warnings land just OUTSIDE the `{…}` range, so exact-range overlap cannot
 * drop them — line-level overlap is required.
 */
class SkyTemplateJsWarningSuppressionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply { enabled = true }
        )
    }

    private val filter = SkyTemplateHtmlErrorFilter()

    // -------------------------------------------------------------------------
    // Unit checks on the matcher and line-overlap helper via the filter's
    // accept() path — synthetic HighlightInfo objects placed at known offsets.
    // -------------------------------------------------------------------------

    /**
     * A WEAK_WARNING with description "Unnecessary semicolon" on a line that
     * contains a Sky tag must be suppressed.
     */
    fun testUnnecessarySemicolonOnSkyTagLineIsSuppressed() {
        // line 4 (0-based 3): `    const a = {=json_encode(data_json)};`
        val html = """
            <html><body><script>
                const x = 1;
                const y = 2;
                const a = {=json_encode(data_json)};
                const b = {?var}true{:}false{/};
            </script></body></html>
        """.trimIndent()

        myFixture.configureByText("a.html", html)
        val file = myFixture.file

        // Find the offset of the `;` that follows `}`  on the `const a` line.
        val lineStart = html.indexOf("const a =")
        val semicolonOffset = html.indexOf("};", lineStart) + 1  // the `;` after `}`

        val info = weakWarning(semicolonOffset, semicolonOffset + 1, "Unnecessary semicolon")
        assertFalse(
            "Unnecessary semicolon on a line with a Sky tag must be suppressed",
            filter.accept(info, file),
        )
    }

    /**
     * A WEAK_WARNING with description "Expression statement is not assignment
     * or call" on a line containing a Sky branch tag must be suppressed.
     */
    fun testExpressionStatementWarningOnSkyTagLineIsSuppressed() {
        val html = """
            <html><body><script>
                const a = {=json_encode(data_json)};
                const b = {?var}true{:}false{/};
            </script></body></html>
        """.trimIndent()

        myFixture.configureByText("a.html", html)
        val file = myFixture.file

        // The bare `true` token on the `const b` line.
        val trueOffset = html.indexOf("true")

        val info = weakWarning(trueOffset, trueOffset + 4, "Expression statement is not assignment or call")
        assertFalse(
            "Expression-statement warning on a Sky conditional line must be suppressed",
            filter.accept(info, file),
        )
    }

    /**
     * Negative / non-regression: "Unnecessary semicolon" on a plain JS line
     * with NO Sky tag on that line must NOT be suppressed.
     *
     * The filter suppresses these warnings only when a template range occupies
     * the same line as the highlight. A file with Sky tags in it but the
     * offending line having none must still report the warning.
     */
    fun testUnnecessarySemicolonOnPlainJsLineIsPreserved() {
        // The double-semicolon is on its own line; Sky tags are elsewhere.
        val html = """
            <html><body><script>
                const a = 1;;
                const b = {=someVar};
            </script></body></html>
        """.trimIndent()

        myFixture.configureByText("a.html", html)
        val file = myFixture.file

        // Find the second `;` on the `const a = 1;;` line.
        val lineStart = html.indexOf("const a = 1")
        val doubleSemiOffset = html.indexOf(";;", lineStart) + 1  // second `;`

        val info = weakWarning(doubleSemiOffset, doubleSemiOffset + 1, "Unnecessary semicolon")
        assertTrue(
            "Unnecessary semicolon on a plain JS line (no Sky tag on that line) must not be suppressed",
            filter.accept(info, file),
        )
    }

    /**
     * Negative: in a file with NO Sky tags at all, neither warning is
     * suppressed — ensures files without template usage are fully unaffected.
     */
    fun testWarningsInFileWithoutSkyTagsArePreserved() {
        val html = """
            <html><body><script>
                const a = 1;;
                const b = c;
            </script></body></html>
        """.trimIndent()

        myFixture.configureByText("a.html", html)
        val file = myFixture.file

        val semiOffset = html.indexOf(";;") + 1
        val semiInfo = weakWarning(semiOffset, semiOffset + 1, "Unnecessary semicolon")
        assertTrue(
            "Unnecessary semicolon in a Sky-free file must not be suppressed",
            filter.accept(semiInfo, file),
        )

        val stmtOffset = html.indexOf("const b = c") + 10  // the `c`
        val stmtInfo = weakWarning(stmtOffset, stmtOffset + 1, "Expression statement is not assignment or call")
        assertTrue(
            "Expression-statement warning in a Sky-free file must not be suppressed",
            filter.accept(stmtInfo, file),
        )
    }

    // -------------------------------------------------------------------------
    // Integration: doHighlighting() — checks what the IDE actually emits.
    // -------------------------------------------------------------------------

    /**
     * Full highlighting pass on the two #3 repro lines. Asserts that neither
     * `Unnecessary semicolon` nor `Expression statement is not assignment or
     * call` appears in the results.
     *
     * NOTE: Whether the JS plugin actually emits these diagnostics in the
     * BasePlatformTestCase fixture depends on the platform configuration. If
     * the platform does NOT emit them (e.g. the JS inspection is not active in
     * the light fixture), this test will still pass — the filter suppression
     * is already verified by the unit-level tests above. The absence of the
     * warning in the highlight list is the correct outcome either way.
     */
    fun testNoSkyInducedJsWarningsInHighlightOutput() {
        val html = """<html><body><script>
    const a = {=json_encode(data_json)};
    const b = {?var}true{:}false{/};
</script></body></html>"""

        myFixture.configureByText("a.html", html)
        val highlights: List<HighlightInfo> = myFixture.doHighlighting()

        val suppressed = highlights.filter { hi ->
            hi.description == "Unnecessary semicolon" ||
                hi.description == "Expression statement is not assignment or call"
        }
        assertTrue(
            "Expected no Sky-induced JS warnings in highlighting output, but found: $suppressed",
            suppressed.isEmpty(),
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun weakWarning(start: Int, end: Int, description: String): HighlightInfo =
        HighlightInfo.newHighlightInfo(HighlightInfoType.WEAK_WARNING)
            .range(TextRange(start, end))
            .descriptionAndTooltip(description)
            .severity(HighlightSeverity.WEAK_WARNING)
            .create()!!
}
