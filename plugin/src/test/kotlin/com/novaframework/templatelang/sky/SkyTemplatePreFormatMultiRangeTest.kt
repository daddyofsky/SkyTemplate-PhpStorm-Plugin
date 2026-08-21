package com.novaframework.templatelang.sky

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * P2-13a regression: a single Reformat can invoke
 * [SkyTemplatePreFormatProcessor.process] more than once on the SAME
 * document — e.g. "Reformat only VCS changed lines" / any
 * `CodeStyleManager.reformatText(file, ranges)` call with several disjoint
 * ranges drives one `process()` per range. The old implementation disposed
 * the ENTIRE snapshot at the top of every `process()` call, so an earlier
 * range's `<script>` snapshot was gone before
 * [SkyTemplatePostFormatProcessor] ever got to restore it — only the LAST
 * range's tags survived Reformat intact.
 */
class SkyTemplatePreFormatMultiRangeTest : BasePlatformTestCase() {

    fun testBothScriptBlocksSurviveMultiRangeReformat() {
        // Two independent `<script>` bodies, each carrying a Sky tag the JS
        // formatter would otherwise split across lines. Reformat is invoked
        // with each script's own range as a SEPARATE FormatTextRange, mirroring
        // "Reformat only VCS changed lines" splitting one Reformat into several
        // process() calls on the same document.
        val source = "<script>\n    const a = {=foo()};\n</script>\n" +
            "<div>unrelated</div>\n" +
            "<script>\n    const b = {=bar()};\n</script>\n"
        myFixture.configureByText("a.html", source)
        val file = myFixture.file
        val document = myFixture.editor.document

        val firstScriptRange = TextRange(source.indexOf("<script>"), source.indexOf("</script>") + "</script>".length)
        val secondScriptStart = source.indexOf("<script>", firstScriptRange.endOffset)
        val secondScriptRange = TextRange(secondScriptStart, source.indexOf("</script>", secondScriptStart) + "</script>".length)

        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project)
                .reformatText(file, listOf(firstScriptRange, secondScriptRange))
        }

        val result = document.text
        assertTrue(
            "first script's tag must survive intact; got:\n$result",
            result.contains("{=foo()}"),
        )
        assertTrue(
            "second script's tag must survive intact; got:\n$result",
            result.contains("{=bar()}"),
        )
        assertFalse("no tag must be split into `{ =`; got:\n$result", result.contains("{ ="))
    }

    fun testSingleRangeReformatStillWorks() {
        // Existing single-FormatTextRange behaviour must be unchanged by the
        // merge logic.
        val source = "<script>\n    const a = {=foo()};\n</script>\n"
        myFixture.configureByText("a.html", source)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project)
                .reformatText(myFixture.file, 0, myFixture.file.textLength)
        }
        val result = myFixture.editor.document.text
        assertTrue("tag survives intact; got:\n$result", result.contains("{=foo()}"))
    }
}
