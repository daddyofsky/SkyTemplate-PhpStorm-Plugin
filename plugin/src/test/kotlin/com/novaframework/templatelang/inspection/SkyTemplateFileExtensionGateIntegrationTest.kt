package com.novaframework.templatelang.inspection

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Phase 5 — file-extension whitelist gate: end-to-end checks that the
 * inspection / annotator pipeline stays silent in non-whitelisted files.
 *
 * Phase 5 motivation: SkyTemplate token sequences (`{?cond}{/}`,
 * `{=foo()}`) can appear by coincidence in `.md` / `.py` / `.txt` files
 * that have nothing to do with templates. Before the whitelist gate, every
 * such occurrence produced false-positive `Cannot resolve …` warnings.
 *
 * These tests configure the same SkyTemplate-shaped content in different
 * file extensions and assert that:
 *   1. `.md` → no diagnostics (gate blocks)
 *   2. `.html` → diagnostics flow as before (default whitelist allows)
 *   3. `.sky` → diagnostics flow regardless of whitelist (own file type)
 *   4. Mutating the whitelist toggles the behaviour live.
 *
 * `myFixture.checkHighlighting(true, false, true)` is the same call used by
 * the existing inspection integration tests; it asserts the file produces
 * exactly the highlights marked inline. Files with NO inline markers must
 * therefore produce NO highlights — the documented Phase 5 contract.
 */
class SkyTemplateFileExtensionGateIntegrationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Reset settings to documented defaults — other test classes mutate
        // the same project-level service through .state and may leave it
        // in a non-default shape.
        TemplateLangSettings.getInstance(project).loadState(TemplateLangSettings.State())
        myFixture.enableInspections(
            SkyTemplateUnclosedBlockInspection(),
            SkyTemplateOrphanElseInspection(),
            SkyTemplateUndefinedSymbolInspection(),
        )
    }

    override fun tearDown() {
        try {
            TemplateLangSettings.getInstance(project).loadState(TemplateLangSettings.State())
        } finally {
            super.tearDown()
        }
    }

    // ── Phase 5 core: false-positive elimination ─────────────────────────────

    fun testMdFileWithSkyTemplatePatterns_NoAnnotationsOrInspections() {
        // Plain Markdown that happens to contain `{? … }{/}` and `{=foo()}`
        // sequences. Pre-Phase-5 these would surface "Cannot resolve" and
        // structural diagnostics; the gate must keep the file silent.
        myFixture.configureByText(
            "notes.md",
            """
            # Heading
            Sample sky-shaped text below — must not be processed.
            {?cond}body{/}
            {=foo()}
            {else}
            """.trimIndent()
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testPyFileSilent() {
        myFixture.configureByText(
            "script.py",
            "x = '{?cond}body{/}'\ny = '{=foo()}'\n"
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testTxtFileSilent() {
        myFixture.configureByText(
            "doc.txt",
            "{?cond}body{/}\n{=foo()}\n"
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testJsonFileSilent() {
        // JSON sometimes carries `{...}` that looks like a template tag —
        // the gate must silence it under the default whitelist.
        myFixture.configureByText(
            "config.json",
            "{ \"k\": \"{=foo()}\" }\n"
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testYamlFileSilent() {
        myFixture.configureByText(
            "data.yaml",
            "key: '{=foo()}'\nother: '{?cond}body{/}'\n"
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testRstFileSilent() {
        myFixture.configureByText(
            "doc.rst",
            "Heading\n=======\n{?cond}body{/}\n"
        )
        myFixture.checkHighlighting(true, false, true)
    }

    // ── Whitelist mutations ──────────────────────────────────────────────────

    fun testHtmlSilentAfterRemovedFromWhitelist() {
        // Strip `html` from the whitelist — `.html` files should now be
        // ignored by the gate even though the content carries SkyTemplate
        // patterns.
        TemplateLangSettings.getInstance(project).state.fileExtensions =
            mutableListOf("sky")
        myFixture.configureByText(
            "page.html",
            "<body>\n{loop xs as x}\nbody\n</body>"
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testHtmlReportedWhenInWhitelist() {
        // Sanity: with the default whitelist the same content surfaces the
        // unclosed-block diagnostic (regression for the gate not being
        // overzealous in default config). The exact phrasing comes from
        // SkyTemplateUnclosedBlockInspection and is held verbatim.
        myFixture.configureByText(
            "page.html",
            "<body>\n<error descr=\"Unclosed `{loop xs as x}` block — missing `{/}` or `{end}`\">{loop xs as x}</error>\nbody\n</body>"
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testSkyAlwaysReportedEvenIfWhitelistEmpty() {
        // Own file type bypasses the whitelist by design.
        TemplateLangSettings.getInstance(project).state.fileExtensions =
            mutableListOf()
        myFixture.configureByText(
            "page.sky",
            "<error descr=\"Unclosed `{loop xs as x}` block — missing `{/}` or `{end}` (likely close near line 3, based on indent)\">{loop xs as x}</error>\n  body\n"
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testSkySilentWhenMasterSwitchOff() {
        // Master switch overrides everything — even own file type goes silent.
        TemplateLangSettings.getInstance(project).state.enabled = false
        try {
            myFixture.configureByText(
                "page.sky",
                "{loop xs as x}\nbody\n"
            )
            myFixture.checkHighlighting(true, false, true)
        } finally {
            TemplateLangSettings.getInstance(project).state.enabled = true
        }
    }

    // ── Add an extension to whitelist ────────────────────────────────────────

    fun testXmlSilentByDefault() {
        // `.xml` is NOT in the default whitelist (htm / xml / skyhtml are
        // opt-in). Same content that fires in `.html` must stay silent.
        myFixture.configureByText(
            "data.xml",
            "<root>\n{loop xs as x}\nbody\n</root>"
        )
        myFixture.checkHighlighting(true, false, true)
    }
}
