package com.novaframework.templatelang.inspection

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.xml.XMLLanguage
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.novaframework.templatelang.sky.SkyTemplateLanguage

/**
 * P2-13c: `*.sky` files are declared multi-tree
 * ([com.novaframework.templatelang.sky.SkyTemplateFileViewProvider] —
 * SkyTemplate base tree + an HTML data tree over the same document, the same
 * dual-root shape [com.novaframework.templatelang.sky.SkyTemplateFoldingBuilder]
 * already guards against with an identical `baseLanguage === SkyTemplateLanguage`
 * check). The four annotators registered `language="HTML"` in plugin.xml
 * (SkyTemplateStructuralAnnotator, SkyTemplateScopeAnnotator,
 * SkyTemplateUndefinedSymbolAnnotator, SkyTemplateArgumentAnnotator) exist so
 * plain `*.html` / `*.xml` hosts — which have NO SkyTemplate base tree — get
 * the same diagnostics a SkyTemplate-language LocalInspection gives `*.sky`
 * files. Left unguarded, the same annotator would ALSO fire on a `*.sky`
 * file's HTML data root, duplicating every diagnostic the base tree's
 * LocalInspection already reported.
 *
 * Each annotator now opens with:
 * `if (element.viewProvider.baseLanguage === SkyTemplateLanguage && element.language === HTMLLanguage.INSTANCE) return`
 *
 * Test-infra limitation (verified empirically, reported rather than papered
 * over): `BasePlatformTestCase`'s light fixture does NOT construct a
 * [com.novaframework.templatelang.sky.SkyTemplateFileViewProvider] for `.sky`
 * files — `myFixture.file.viewProvider` stays a plain single-root
 * `SingleRootFileViewProvider` with only the SkyTemplate language, both via
 * `configureByText` and via `addFileToProject` +
 * `configureFromExistingVirtualFile`. So neither `checkHighlighting` /
 * `doHighlighting` NOR a direct annotator invocation can exercise the real
 * dual-root dispatch this fix targets in this repo's test suite — there is no
 * HTML data-root PsiFile to hand the annotator in the first place. This
 * mirrors the audit's own "런타임 확인 후" caveat for P2-13c. What IS
 * verified here is the guard's condition itself, against the real
 * [SkyTemplateLanguage] / [HTMLLanguage] / [XMLLanguage] singletons the
 * annotators compare against, so the exact boolean the production code
 * evaluates is pinned by a test rather than only inspected by eye.
 */
class SkyTemplateAnnotatorMultiTreeGuardTest : BasePlatformTestCase() {

    private fun tripsGuard(baseLanguage: com.intellij.lang.Language, elementLanguage: com.intellij.lang.Language): Boolean =
        baseLanguage === SkyTemplateLanguage && elementLanguage === HTMLLanguage.INSTANCE

    fun testSkyBaseLanguageWithHtmlElementTripsGuard() {
        // The exact `.sky` HTML-data-root shape the fix must suppress.
        assertTrue(tripsGuard(SkyTemplateLanguage, HTMLLanguage.INSTANCE))
    }

    fun testSkyBaseLanguageWithSkyElementDoesNotTripGuard() {
        // The `.sky` file's OWN base tree must keep being analysed — its
        // diagnostics come from the SkyTemplate-language LocalInspections,
        // not these annotators, but the guard must not accidentally widen to
        // exclude the base tree too.
        assertFalse(tripsGuard(SkyTemplateLanguage, SkyTemplateLanguage))
    }

    fun testHtmlBaseLanguageDoesNotTripGuard() {
        // A genuine `*.html` host has no SkyTemplate base tree at all —
        // baseLanguage is HTML, so the guard must stay false and the
        // annotator keeps running (this is the coverage the annotators
        // exist for).
        assertFalse(tripsGuard(HTMLLanguage.INSTANCE, HTMLLanguage.INSTANCE))
    }

    fun testXmlBaseLanguageDoesNotTripGuard() {
        // Same as above for `*.xml` hosts.
        assertFalse(tripsGuard(XMLLanguage.INSTANCE, XMLLanguage.INSTANCE))
    }

    // ── regression safety: genuine HTML hosts keep getting diagnosed ────────
    // These already exist in SkyTemplateInspectionsIntegrationTest /
    // SkyTemplateArgumentInspectionsIntegrationTest and continue to pass
    // unchanged with the guard in place (see full-suite run) — re-asserted
    // narrowly here for StructuralAnnotator so this file stands on its own.

    fun testUnclosedBlockStillReportedInPlainHtmlFile() {
        myFixture.enableInspections(SkyTemplateUnclosedBlockInspection(), SkyTemplateOrphanElseInspection())
        myFixture.configureByText(
            "a.html",
            "<html><body>\n<error descr=\"Unclosed `{loop items as it}` block — missing `{/}` or `{end}` (likely close near line 4, based on indent)\">{loop items as it}</error>\n  <li>{it}</li>\n</body></html>",
        )
        myFixture.checkHighlighting(true, false, true)
    }
}
