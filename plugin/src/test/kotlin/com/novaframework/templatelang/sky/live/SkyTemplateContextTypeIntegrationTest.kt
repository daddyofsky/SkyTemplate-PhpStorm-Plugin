package com.novaframework.templatelang.sky.live

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * IDE-level checks for [SkyTemplateContextType.isInContext]. The PSI-walking
 * checks (XmlTag ancestor for `<script>` / `<style>`) need a real PSI tree,
 * so we drive `myFixture` to set up files and ask the context type whether
 * a snippet would expand at the caret position.
 *
 * The pure comment-detection logic is covered separately by
 * [SkyTemplateLiveContextLogicTest].
 */
class SkyTemplateContextTypeIntegrationTest : BasePlatformTestCase() {

    private val ctxType = SkyTemplateContextType()

    private fun isActive(filename: String, source: String): Boolean {
        myFixture.configureByText(filename, source)
        val file = myFixture.file
        val editor = myFixture.editor
        val ctx = TemplateActionContext.expanding(file, editor)
        return ctxType.isInContext(ctx)
    }

    // ── *.sky files ───────────────────────────────────────────────────────────

    fun testActiveInPlainSkyFile() {
        assertTrue(isActive("a.sky", "<p>hello <caret></p>"))
    }

    fun testActiveAtTopLevelOfSkyFile() {
        assertTrue(isActive("a.sky", "<caret>"))
    }

    fun testInactiveInsideTemplateCommentInSkyFile() {
        assertFalse(isActive("a.sky", "{* hello <caret> *}"))
    }

    fun testInactiveAtCommentOpenBoundaryInSkyFile() {
        // Caret is right before `{` — i.e. NOT inside the comment yet → active.
        assertTrue(isActive("a.sky", "<caret>{* x *}"))
    }

    fun testInactiveJustInsideCommentOpenBoundaryInSkyFile() {
        // Caret right after `{` (at offset of `*`) — inside the comment.
        assertFalse(isActive("a.sky", "{<caret>* x *}"))
    }

    // ── *.html files ──────────────────────────────────────────────────────────

    fun testActiveInHtmlBody() {
        assertTrue(isActive("a.html", "<html><body><caret></body></html>"))
    }

    fun testInactiveInsideScriptTag() {
        assertFalse(
            isActive(
                "a.html",
                "<html><body><script>let x = <caret>1;</script></body></html>",
            )
        )
    }

    fun testInactiveInsideStyleTag() {
        assertFalse(
            isActive(
                "a.html",
                "<html><head><style>.x { col<caret>or: red; }</style></head></html>",
            )
        )
    }

    fun testInactiveInsideTemplateCommentInHtml() {
        assertFalse(
            isActive(
                "a.html",
                "<html><body>{* hello <caret> *}</body></html>",
            )
        )
    }

    fun testActiveInsideHtmlCommentNotTemplate() {
        // `<!-- … -->` is an HTML comment, not a SkyTemplate `{*…*}`.
        // Users may want to drop a SkyTemplate snippet inside, so we stay
        // active. (Suppressing here would surprise users who comment-out a
        // block and want to add a directive.)
        assertTrue(
            isActive(
                "a.html",
                "<html><body><!-- old <caret> stuff --></body></html>",
            )
        )
    }

    // ── unrelated languages ───────────────────────────────────────────────────

    fun testInactiveInPhpFile() {
        // PHP files are not in scope for SkyTemplate snippets.
        assertFalse(isActive("a.php", "<?php echo <caret>1; ?>"))
    }

    // ── settings gate ─────────────────────────────────────────────────────────

    fun testInactiveWhenPluginIsDisabled() {
        // Mirror the annotator / reference / completion contributors: when the
        // master toggle is off the plugin must be silent. Snippets included.
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply { enabled = false }
        )
        assertFalse("snippet must not expand when plugin is disabled (sky)",
            isActive("a.sky", "<p>hello <caret></p>"))
        assertFalse("snippet must not expand when plugin is disabled (html body)",
            isActive("a.html", "<html><body><caret></body></html>"))
    }

    fun testActiveAgainAfterReEnabling() {
        // After flipping the toggle back on, snippets work as before — verifies
        // the gate reads the current state on every call (no stale caching).
        val settings = TemplateLangSettings.getInstance(project)
        settings.loadState(TemplateLangSettings.State().apply { enabled = false })
        settings.loadState(TemplateLangSettings.State().apply { enabled = true })
        assertTrue(isActive("a.sky", "<p>hello <caret></p>"))
    }
}
