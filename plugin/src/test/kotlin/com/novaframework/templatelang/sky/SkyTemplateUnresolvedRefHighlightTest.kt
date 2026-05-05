package com.novaframework.templatelang.sky

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.novaframework.templatelang.inspection.SkyTemplateUndefinedSymbolInspection
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Verifies the 0.5.34 [SkyTemplateUndefinedSymbolInspection]: an unresolved
 * SkyTemplate PHP-symbol reference (typo or genuinely missing function /
 * class / etc.) surfaces as a daemon-level WARNING. The diagnostic is
 * needed because the platform's built-in "Cannot resolve symbol" highlight
 * does NOT fire for arbitrary `PsiReference`s in HTML host files — we
 * confirmed by disabling [SkyTemplateHtmlErrorFilter] and observing no
 * `WRONG_REF` highlight even with `soft = false`. The platform simply
 * doesn't run an unresolved-reference check in HTML, so we run our own.
 */
class SkyTemplateUnresolvedRefHighlightTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply {
                enabled = true
                namespace = "\\"
            }
        )
        myFixture.enableInspections(SkyTemplateUndefinedSymbolInspection())
    }

    fun testUnresolvedFunctionEmitsWarningInHtmlText() {
        myFixture.configureByText(
            "page.html",
            "<p>{=definitelyMissing()}</p>",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertWarningOnSymbol(
            myFixture.doHighlighting(),
            "definitelyMissing",
            expectedMessagePrefix = "Cannot resolve function",
        )
    }

    fun testUnresolvedFunctionEmitsWarningInsideHrefAttributeValue() {
        // Regression: 0.5.32~0.5.33 fixed Find Usages / Go to Definition
        // for href attribute values. The inspection must also reach there.
        myFixture.configureByText(
            "page.html",
            """<a href="{=definitelyMissingInHref()}">x</a>""",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertWarningOnSymbol(
            myFixture.doHighlighting(),
            "definitelyMissingInHref",
            expectedMessagePrefix = "Cannot resolve function",
        )
    }

    fun testUnresolvedStaticMethodEmitsKindSpecificMessage() {
        myFixture.configureByText(
            "page.html",
            "<p>{=Util::nonExistentMethod()}</p>",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        // METHOD message form: "Cannot resolve method `<class>::<method>()`"
        val highlights = myFixture.doHighlighting()
        val match = highlights.firstOrNull { hi ->
            hi.severity == HighlightSeverity.WARNING &&
                hi.description?.startsWith("Cannot resolve method `Util::nonExistentMethod()") == true
        }
        assertNotNull(
            "expected METHOD-kind diagnostic on `Util::nonExistentMethod`. Got: " +
                highlights.filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }.map { it.description },
            match,
        )
    }

    fun testResolvedFunctionDoesNotEmitWarning() {
        myFixture.addFileToProject(
            "lib.php",
            """
            <?php
            function definitelyDefined(): void {}
            """.trimIndent()
        )
        myFixture.configureByText(
            "page.html",
            "<p>{=definitelyDefined()}</p>",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val highlights = myFixture.doHighlighting()
        val unresolvedHits = highlights.filter { hi ->
            hi.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal &&
                hi.description?.startsWith("Cannot resolve") == true
        }
        assertTrue(
            "no `Cannot resolve` diagnostic expected for a resolvable function. Got: " +
                unresolvedHits.map { it.description },
            unresolvedHits.isEmpty(),
        )
    }

    fun testPluginDisabledSuppressesInspection() {
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply { enabled = false }
        )
        myFixture.configureByText(
            "page.html",
            "<p>{=disabledFn()}</p>",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val highlights = myFixture.doHighlighting()
        val ourHits = highlights.filter { hi ->
            hi.description?.startsWith("Cannot resolve") == true
        }
        assertTrue(
            "with the master toggle off the inspection must not fire. Got: " +
                ourHits.map { it.description },
            ourHits.isEmpty(),
        )
    }

    fun testInspectionSurvivesSkyTemplateHtmlErrorFilter() {
        // Critical: the filter drops every WEAK_WARNING+ highlight whose
        // range overlaps a SkyTemplate construct. Our own diagnostic sits
        // inside the construct by design, so the filter must whitelist it
        // (mirrors the 0.5.26 fix for the structural / scope annotators).
        myFixture.configureByText(
            "page.html",
            "<p>{=needsToSurviveFilter()}</p>",
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        assertWarningOnSymbol(
            myFixture.doHighlighting(),
            "needsToSurviveFilter",
            expectedMessagePrefix = "Cannot resolve function",
        )
    }

    private fun assertWarningOnSymbol(
        highlights: List<HighlightInfo>,
        symbolName: String,
        expectedMessagePrefix: String,
    ) {
        val symbolOffset = myFixture.file.text.indexOf(symbolName)
        val match = highlights.firstOrNull { hi ->
            hi.severity == HighlightSeverity.WARNING &&
                hi.description?.startsWith(expectedMessagePrefix) == true &&
                hi.startOffset <= symbolOffset && hi.endOffset >= symbolOffset + symbolName.length
        }
        assertNotNull(
            "expected WARNING `$expectedMessagePrefix...` covering `$symbolName`. Got: " +
                highlights
                    .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
                    .joinToString { "[${it.severity}] ${it.description} @(${it.startOffset},${it.endOffset})" },
            match,
        )
    }
}
