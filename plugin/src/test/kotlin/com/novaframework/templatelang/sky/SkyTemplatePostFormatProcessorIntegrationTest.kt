package com.novaframework.templatelang.sky

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * IDE-level coverage for [SkyTemplatePostFormatProcessor.processText] driven
 * through the real `CodeStyleManager.reformatText` entry point (Reformat
 * Code), rather than the pure [SkyTemplatePostFormatLogic.reindent] unit.
 */
class SkyTemplatePostFormatProcessorIntegrationTest : BasePlatformTestCase() {

    fun testReformatNoOpCaseProducesNoException() {
        // P-BUG-08: when reindent makes no edits (the file is already
        // correctly indented), processText used to capture originalLength
        // AFTER restoreMangledTags shrank the document, then hand back the
        // stale pre-restore rangeToReformat — an endOffset past the
        // (now shorter) document. Reformat Code on an already-correct file
        // must complete without throwing and leave the text untouched.
        val source = "{loop xs as x}\n    <p>body</p>\n{/}"
        myFixture.configureByText("a.html", source)
        val file = myFixture.file

        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(file, 0, file.textLength)
        }

        assertEquals(source, myFixture.editor.document.text)
    }

    fun testReformatWithScriptBlockRestoresWithoutRangeException() {
        // Exercises restoreMangledTags on a <script> body carrying Sky
        // tags — the path that changes document length before the fix
        // moved originalLength capture earlier. Must complete without a
        // range/index exception regardless of what the host JS formatter
        // does to the body.
        val source = "<script>\n{?var}\nvar.a=1;\n{/}\n</script>"
        myFixture.configureByText("a.html", source)
        val file = myFixture.file

        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(file, 0, file.textLength)
        }

        // No crash is the primary assertion; the Sky tags must still be
        // present (restoreMangledTags did not corrupt the script body).
        val text = myFixture.editor.document.text
        assertTrue(text.contains("{?var}"))
        assertTrue(text.contains("{/}"))
    }
}
