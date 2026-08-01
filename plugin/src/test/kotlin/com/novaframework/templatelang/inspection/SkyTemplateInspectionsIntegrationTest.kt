package com.novaframework.templatelang.inspection

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * IDE-level checks for the M7 inspections. Drives `myFixture.checkHighlighting`
 * with `<error>…</error>` markers in the source so we exercise the visitor +
 * registration plumbing end-to-end.
 *
 * Pure pairing logic is covered by [com.novaframework.templatelang.sky.SkyTemplateBlockPairingTest];
 * this file focuses on file-type filtering and Settings gating.
 */
class SkyTemplateInspectionsIntegrationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The .sky / .skyhtml diagnostics go through LocalInspection
        // and need explicit enablement in the test profile. The HTML /
        // XML diagnostics are produced by SkyTemplateStructuralAnnotator
        // and run unconditionally — no enable needed for those.
        myFixture.enableInspections(
            SkyTemplateUnclosedBlockInspection(),
            SkyTemplateOrphanElseInspection(),
        )
    }

    private fun ensureEnabled() {
        TemplateLangSettings.getInstance(project).state.enabled = true
    }

    // ── Unclosed block ────────────────────────────────────────────────────────

    fun testUnclosedLoopReportedInSkyFile() {
        ensureEnabled()
        myFixture.configureByText(
            "a.sky",
            "<error descr=\"Unclosed `{loop xs as x}` block — missing `{/}` or `{end}` (likely close near line 3, based on indent)\">{loop xs as x}</error>\n  body\n",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testPairedLoopNotReported() {
        ensureEnabled()
        myFixture.configureByText("a.sky", "{loop xs as x}\n  body\n{/}")
        myFixture.checkHighlighting(true, false, true)
    }

    fun testUnclosedReportedInHtmlFile() {
        // The inspection now also runs in HTML host files. Same behaviour
        // as in `*.sky` — no special-case suppression.
        ensureEnabled()
        myFixture.configureByText(
            "a.html",
            "<body>\n<error descr=\"Unclosed `{loop xs as x}` block — missing `{/}` or `{end}` (likely close near line 4, based on indent)\">{loop xs as x}</error>\n  body\n</body>",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testUnclosedReportedInHtmlHostFile() {
        // Annotator path — the unclosed-block diagnostic surfaces in plain
        // HTML host files via SkyTemplateStructuralAnnotator. (`.skyhtml`
        // was dropped from SkyTemplateFileType in 0.5.26 — `.html` plus
        // the SkyTemplate annotator overlay covers the same use case.)
        ensureEnabled()
        myFixture.configureByText(
            "a.html",
            "<html><body>\n<error descr=\"Unclosed `{loop items as it}` block — missing `{/}` or `{end}` (likely close near line 4, based on indent)\">{loop items as it}</error>\n  <li>{it}</li>\n</body></html>",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testUnclosedNotReportedWhenSettingsDisabled() {
        TemplateLangSettings.getInstance(project).state.enabled = false
        try {
            myFixture.configureByText("a.sky", "{loop xs as x}\n  body\n")
            myFixture.checkHighlighting(true, false, true)
        } finally {
            TemplateLangSettings.getInstance(project).state.enabled = true
        }
    }

    // ── Orphan branch ─────────────────────────────────────────────────────────

    fun testOrphanElseReportedInSkyFile() {
        ensureEnabled()
        myFixture.configureByText(
            "a.sky",
            "<error descr=\"`{else}` outside `{if}` / `{loop}` block\">{else}</error>\nfallback\n{/}",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testElseInsideIfNotReported() {
        ensureEnabled()
        myFixture.configureByText(
            "a.sky",
            "{if cond}\n  a\n{else}\n  b\n{/}",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testOrphanElseReportedInHtmlFile() {
        // The inspection now runs in HTML host files too.
        ensureEnabled()
        myFixture.configureByText(
            "a.html",
            "<body><error descr=\"`{else}` outside `{if}` / `{loop}` block\">{else}</error>fallback{/}</body>",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    // ── Elvis (regression for 0.5.16) ─────────────────────────────────────────

    fun testElvisStandaloneReportedAsUnclosedBlock() {
        // `{?:expr}` is a block opener (compiler pushes 'if' onto arrBlock),
        // so a standalone form is unclosed — same diagnostic as `{if expr}`
        // with no `{/}`.
        ensureEnabled()
        myFixture.configureByText(
            "a.sky",
            "<error descr=\"Unclosed `{?:fallback}` block — missing `{/}` or `{end}`\">{?:fallback}</error>",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testElvisProperlyClosedNotReported() {
        ensureEnabled()
        myFixture.configureByText("a.sky", "{?:val}fallback{/}")
        myFixture.checkHighlighting(true, false, true)
    }

    // ── P3-11: missing loop tag name (compiler throws) ──────────────────────

    fun testLoopWithNoNameReportedInHtmlFile() {
        // `SkyTemplateCompiler::tagLoop` throws 'Loop tag name is missing'
        // when `$arg` is empty. `{loop}` (no argument at all) hits it.
        ensureEnabled()
        myFixture.configureByText(
            "a.html",
            "<body><error descr=\"Loop tag name is missing\">{loop}</error>body{/}</body>",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testLoopWithOnlyWhitespaceNameReportedInHtmlFile() {
        // `{loop  }` — whitespace-only argument, same as no argument once trimmed.
        ensureEnabled()
        myFixture.configureByText(
            "a.html",
            "<body><error descr=\"Loop tag name is missing\">{loop  }</error>body{/}</body>",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testLoopAtPrefixWithNoNameReportedInHtmlFile() {
        // `{@}` aliases to `loop` in the compiler's tagAlias table — same throw.
        ensureEnabled()
        myFixture.configureByText(
            "a.html",
            "<body><error descr=\"Loop tag name is missing\">{@}</error>body{/}</body>",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testLoopPercentPrefixWithNoNameReportedInHtmlFile() {
        // `{%}` also aliases to `loop`.
        ensureEnabled()
        myFixture.configureByText(
            "a.html",
            "<body><error descr=\"Loop tag name is missing\">{%}</error>body{/}</body>",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testEachWithNoNameReportedInHtmlFile() {
        // `each` also aliases to `loop` in the compiler's tagAlias table.
        ensureEnabled()
        myFixture.configureByText(
            "a.html",
            "<body><error descr=\"Loop tag name is missing\">{each}</error>body{/}</body>",
        )
        myFixture.checkHighlighting(true, false, true)
    }

    fun testLoopWithNameNotReported() {
        ensureEnabled()
        myFixture.configureByText("a.html", "<body>{loop xs as x}body{/}</body>")
        myFixture.checkHighlighting(true, false, true)
    }

    fun testLoopAtPrefixWithNameNotReported() {
        ensureEnabled()
        myFixture.configureByText("a.html", "<body>{@xs}body{/}</body>")
        myFixture.checkHighlighting(true, false, true)
    }

    fun testForeachWithNoArgNotReported() {
        // `foreach` / `for` / `while` do NOT throw on a missing argument in
        // the compiler (only `tagLoop` does) — must stay in scope.
        ensureEnabled()
        myFixture.configureByText("a.html", "<body>{foreach}body{/}</body>")
        myFixture.checkHighlighting(true, false, true)
    }

}
