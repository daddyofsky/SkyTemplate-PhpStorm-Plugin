package com.novaframework.templatelang

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests guarding the **external-facing surface** of the plugin.
 *
 * Policy (T5 / 0.5.9): the public surface — plugin description shown on the
 * marketplace, change notes shown in the plugin manager, settings UI labels /
 * comments / placeholders — must not reference unofficial engine support
 * (e.g. `Template_`, `xtac.net`). The same code path still handles compatible
 * directives, but those internal facts belong in KDoc / `PLAN.md` / tests, not
 * in user-visible strings.
 *
 * Tests are pure JUnit (no IDE fixture). They read source files directly so
 * the assertions reflect what ships in the build artefact.
 */
class ExternalSurfaceTextTest {

    @Test
    fun `plugin xml description has no Template_ reference`() {
        val text = readSourceFile("plugin/src/main/resources/META-INF/plugin.xml")

        // Extract the <description> CDATA so we don't accidentally validate the
        // whole file (vendor URL, extension class names, etc. are out of scope).
        val match = Regex(
            "<description>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</description>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(text)
        assertNotNull("plugin.xml must contain a <description><![CDATA[…]]></description> block", match)
        val description = match!!.groupValues[1]

        assertFalse(
            "plugin.xml <description> must not mention 'Template_' (unofficial engine):\n$description",
            description.contains("Template_"),
        )
        assertFalse(
            "plugin.xml <description> must not mention 'xtac':\n$description",
            description.contains("xtac"),
        )
    }

    @Test
    fun `build gradle changeNotes has no Template_ reference`() {
        val text = readSourceFile("plugin/build.gradle.kts")

        // Pull just the changeNotes.set(""" … """) block.
        val match = Regex(
            "changeNotes\\.set\\(\\s*\"\"\"(.*?)\"\"\"",
            RegexOption.DOT_MATCHES_ALL,
        ).find(text)
        assertNotNull("build.gradle.kts must contain a changeNotes.set(\"\"\"…\"\"\") block", match)
        val notes = match!!.groupValues[1]

        assertFalse(
            "changeNotes must not mention 'Template_' (unofficial engine).\nFound at:\n" +
                excerpt(notes, "Template_"),
            notes.contains("Template_"),
        )
        assertFalse(
            "changeNotes must not mention 'xtac'.\nFound at:\n" + excerpt(notes, "xtac"),
            notes.contains("xtac"),
        )
    }

    @Test
    fun `configurable user-visible strings have no Template_ reference`() {
        val text = readSourceFile(
            "plugin/src/main/kotlin/com/novaframework/templatelang/settings/TemplateLangConfigurable.kt"
        )

        // Strip KDoc (/** … */) and `//` line comments. KDoc / line comments
        // are developer-facing surface and may reference Template_ for context.
        // Whatever remains is code + user-visible string literals.
        val stripped = text
            .replace(Regex("/\\*\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)//.*$"), "")

        assertFalse(
            "TemplateLangConfigurable user-visible strings must not mention 'Template_'.\n" +
                "Hint: KDoc /** … */ and // line comments are stripped before this check; " +
                "any remaining 'Template_' is in a string literal shown in the settings UI.",
            stripped.contains("Template_"),
        )
        assertFalse(
            "TemplateLangConfigurable user-visible strings must not mention 'xtac'.",
            stripped.contains("xtac"),
        )
    }

    @Test
    fun `plugin xml file as a whole has no xtac reference`() {
        // Belt-and-braces: even outside <description>, the marketplace metadata
        // (vendor URL is allowed; xtac.net is not the vendor) must stay clean.
        val text = readSourceFile("plugin/src/main/resources/META-INF/plugin.xml")
        assertFalse("plugin.xml must not contain 'xtac' anywhere", text.contains("xtac"))
        assertFalse("plugin.xml must not contain 'Template_' anywhere", text.contains("Template_"))
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Resolve a project-relative path. Gradle runs tests with the `plugin/`
     * sub-project as the working directory, but IDE runners sometimes pick
     * the repository root. Try a few likely anchors.
     */
    private fun readSourceFile(relPath: String): String {
        val cwd = File(".").absoluteFile
        // walk up to 4 levels looking for the file
        var dir: File? = cwd
        repeat(5) {
            dir?.let {
                val direct = File(it, relPath)
                if (direct.isFile) return direct.readText()
                // try with the leading 'plugin/' segment removed (when cwd is already plugin/)
                if (relPath.startsWith("plugin/")) {
                    val short = File(it, relPath.removePrefix("plugin/"))
                    if (short.isFile) return short.readText()
                }
            }
            dir = dir?.parentFile
        }
        error("Could not locate $relPath starting from $cwd")
    }

    /**
     * Extract a short context window around the first match, for nicer
     * failure messages.
     */
    private fun excerpt(haystack: String, needle: String, window: Int = 80): String {
        val idx = haystack.indexOf(needle)
        if (idx < 0) return "(no match — but assertion failed?)"
        val start = (idx - window).coerceAtLeast(0)
        val end = (idx + needle.length + window).coerceAtMost(haystack.length)
        return "…" + haystack.substring(start, end).replace("\n", " ").trim() + "…"
    }
}
