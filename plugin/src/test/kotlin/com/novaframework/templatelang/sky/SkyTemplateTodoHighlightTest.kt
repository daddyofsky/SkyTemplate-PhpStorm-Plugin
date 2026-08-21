package com.novaframework.templatelang.sky

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.search.TodoAttributesUtil
import com.intellij.psi.search.TodoPattern
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * TODO / FIXME matches inside a `{*…*}` comment keep the colours configured
 * in *Settings → Editor → TODO* instead of the grey comment overlay.
 */
class SkyTemplateTodoHighlightTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TemplateLangSettings.getInstance(project).loadState(
            TemplateLangSettings.State().apply { enabled = true }
        )
    }

    fun testTodoInsideSkyCommentIsPaintedWithTodoAttributes() {
        myFixture.configureByText("page.html", "<div>x</div>\n{* TODO: fix me *}\n")
        val text = myFixture.file.text
        val todoStart = text.indexOf("TODO")
        val todoEnd = text.indexOf(" *}") + 1

        val todo = paintedSpan(todoStart, todoEnd)
        assertNotNull("the TODO text must carry its own highlight", todo)
        assertEquals(
            TodoAttributesUtil.getDefaultColorSchemeTextAttributes(),
            todo!!.forcedTextAttributes,
        )
    }

    fun testCommentOverlayStopsAtTheTodoSpan() {
        myFixture.configureByText("page.html", "<div>x</div>\n{* TODO: fix me *}\n")
        val text = myFixture.file.text
        val comment = SkyTemplateRanges.computeCommentRanges(text).single()
        val todoStart = text.indexOf("TODO")

        assertNull(
            "the grey overlay must not span the whole comment any more",
            paintedSpan(comment.startOffset, comment.endOffset),
        )
        assertNotNull(
            "the `{* ` head keeps the comment colour",
            paintedSpan(comment.startOffset, todoStart),
        )
    }

    fun testTodoInsideWrappedSkyCommentIsPainted() {
        myFixture.configureByText("page.html", "<div>x</div>\n<!--{* TODO: fix me *}-->\n")
        val text = myFixture.file.text
        val todoStart = text.indexOf("TODO")
        val todoEnd = text.indexOf(" *}") + 1

        assertNotNull(
            "the wrapped `<!--{* … *}-->` shape must be covered too",
            paintedSpan(todoStart, todoEnd),
        )
    }

    fun testCustomPatternColoursAreUsed() {
        val configuration = com.intellij.ide.todo.TodoConfiguration.getInstance()
        val original = configuration.todoPatterns
        val attributes = TodoAttributesUtil.createDefault().apply {
            setUseCustomTodoColor(true, textAttributes.clone().apply { fontType = 1 })
        }
        configuration.todoPatterns = arrayOf(TodoPattern("\\bREVIEW\\b.*", attributes, true))
        try {
            myFixture.configureByText("page.html", "{* REVIEW this *}\n")
            val text = myFixture.file.text
            val start = text.indexOf("REVIEW")
            val info = paintedSpan(start, text.indexOf(" *}") + 1)
            assertNotNull("a user-defined pattern must be honoured", info)
            assertEquals(attributes.textAttributes, info!!.forcedTextAttributes)
        } finally {
            configuration.todoPatterns = original
        }
    }

    fun testCommentWithoutTodoKeepsOneFullRangeOverlay() {
        myFixture.configureByText("page.html", "<div>x</div>\n{* plain note *}\n")
        val comment = SkyTemplateRanges.computeCommentRanges(myFixture.file.text).single()

        val overlays = myFixture.doHighlighting().filter {
            it.description == null && it.forcedTextAttributes != null
        }
        assertEquals(1, overlays.size)
        assertEquals(TextRange(comment.startOffset, comment.endOffset), TextRange(overlays[0].startOffset, overlays[0].endOffset))
    }

    fun testSkyFileStillGetsThePlatformTodoHighlight() {
        myFixture.configureByText("partial.sky", "<div>x</div>\n{* TODO: fix me *}\n")

        val todo = myFixture.doHighlighting().singleOrNull { it.type == HighlightInfoType.TODO }
        assertNotNull("`*.sky` TODO items come from the platform pass", todo)
    }

    fun testEveryTodoInAMultiLineCommentIsPainted() {
        myFixture.configureByText(
            "page.html",
            "{*\n  TODO: one\n  <li>x</li>\n  FIXME: two\n*}\n",
        )
        val text = myFixture.file.text

        assertNotNull(paintedSpan(text.indexOf("TODO"), text.indexOf("\n  <li>")))
        assertNotNull(paintedSpan(text.indexOf("FIXME"), text.indexOf("\n*}")))
    }

    fun testForeignHighlightInsideACommentWithATodoIsStillSuppressed() {
        myFixture.configureByText("page.html", "{*\n  TODO: one\n  <ul><li>x</li></ul>\n*}\n")
        val file = myFixture.file
        val ltOffset = file.text.indexOf("<ul")

        // Rainbow-Brackets shape: description-less INFORMATION on the `<`.
        val info = HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
            .range(TextRange(ltOffset, ltOffset + 1))
            .create()!!

        assertFalse(
            "splitting the overlay around the TODO must not un-suppress host noise",
            SkyTemplateHtmlErrorFilter().accept(info, file),
        )
    }

    private fun paintedSpan(start: Int, end: Int): HighlightInfo? =
        myFixture.doHighlighting().firstOrNull {
            it.startOffset == start && it.endOffset == end && it.forcedTextAttributes != null
        }
}
