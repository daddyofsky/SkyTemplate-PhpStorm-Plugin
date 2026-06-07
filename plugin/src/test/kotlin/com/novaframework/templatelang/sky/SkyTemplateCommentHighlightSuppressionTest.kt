package com.novaframework.templatelang.sky

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Verifies [SkyTemplateHtmlErrorFilter]'s suppression of low-severity,
 * description-less highlights inside `{*…*}` comments in `*.html` host files.
 *
 * Rainbow Brackets colours the `< >` of HTML tags that the platform still
 * parses inside a comment (HTML stays the primary PSI for `*.html`). Those
 * highlights are INFORMATION-severity with a null description, sitting as a
 * PROPER SUBSET of the comment range — exactly the shape this filter drops.
 * Our own file-level grey overlay spans the WHOLE comment range and must
 * survive.
 */
class SkyTemplateCommentHighlightSuppressionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply { enabled = true }
        )
    }

    private val filter = SkyTemplateHtmlErrorFilter()

    fun testRainbowStyleTagBracketInsideCommentIsSuppressed() {
        myFixture.configureByText("page.html", "<div>\n{*\n<ul><li>x</li></ul>\n*}\n</div>")
        val file = myFixture.file
        val comment = SkyTemplateRanges.computeCommentRanges(file.text).single()

        // A description-less INFORMATION highlight on the `<` of `<ul>` —
        // a proper subset of the comment range (Rainbow Brackets shape).
        val ltOffset = file.text.indexOf("<ul")
        val info = info(ltOffset, ltOffset + 1, description = null)

        assertFalse(
            "tag-bracket colour inside a comment must be dropped (range ${info.startOffset}..${info.endOffset} ⊂ $comment)",
            filter.accept(info, file),
        )
    }

    fun testFullRangeCommentOverlayIsPreserved() {
        myFixture.configureByText("page.html", "<div>\n{*\n<ul><li>x</li></ul>\n*}\n</div>")
        val file = myFixture.file
        val comment = SkyTemplateRanges.computeCommentRanges(file.text).single()

        // Our own overlay: description-less INFORMATION spanning the WHOLE
        // comment range. Must be let through (it is the grey paint).
        val info = info(comment.startOffset, comment.endOffset, description = null)

        assertTrue(
            "the file-level comment overlay (range == comment range) must survive",
            filter.accept(info, file),
        )
    }

    fun testHighlightInPlainHtmlWithoutCommentIsUntouched() {
        myFixture.configureByText("page.html", "<div><ul><li>x</li></ul></div>")
        val file = myFixture.file
        val ltOffset = file.text.indexOf("<ul")
        val info = info(ltOffset, ltOffset + 1, description = null)

        assertTrue(
            "a tag bracket outside any comment must not be suppressed",
            filter.accept(info, file),
        )
    }

    private fun info(start: Int, end: Int, description: String?): HighlightInfo {
        val builder = HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
            .range(TextRange(start, end))
        if (description != null) builder.descriptionAndTooltip(description)
        return builder.create()!!
    }
}
