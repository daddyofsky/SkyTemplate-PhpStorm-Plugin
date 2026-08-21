package com.novaframework.templatelang.sky

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.datatransfer.StringSelection

/**
 * End-to-end checks that paste and Move Statement Up/Down re-indent
 * SkyTemplate block bodies. Drives the real editor actions so the
 * `copyPastePostProcessor` / `statementUpDownMover` extension chain is
 * exercised exactly as in the IDE.
 */
class SkyTemplatePasteMoveIntegrationTest : BasePlatformTestCase() {

    private fun paste(
        filename: String,
        clipboard: String,
        source: String,
        reformatOnPaste: Int = CodeInsightSettings.INDENT_EACH_LINE,
    ): String {
        val settings = CodeInsightSettings.getInstance()
        val saved = settings.REFORMAT_ON_PASTE
        settings.REFORMAT_ON_PASTE = reformatOnPaste
        try {
            CopyPasteManager.getInstance().setContents(StringSelection(clipboard))
            myFixture.configureByText(filename, source)
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)
            return myFixture.editor.document.text
        } finally {
            settings.REFORMAT_ON_PASTE = saved
        }
    }

    fun testPasteLoopBlockIndentsBody() {
        // Paste a loop block whose body sits at column 0 into an empty HTML
        // host. The body should be lifted one indent step under {loop}.
        val result = paste("a.html", "{loop xs as x}\nbody\n{/}\n", "<caret>")
        assertEquals("{loop xs as x}\n    body\n{/}", result.trimEnd())
    }

    /**
     * The platform's post-paste indent runs AFTER our processor. Verify our
     * `indented = true` claim survives the indent "When pasting" settings —
     * the default is INDENT_EACH_LINE, which on a 2026.1 HTML host flattens
     * `{loop}` bodies to column 0 (the tag is opaque to HTML).
     *
     * REFORMAT_BLOCK ("Reformat") is intentionally excluded: it reformats the
     * pasted range through the host (HTML) formatter via `reformatRange`,
     * which does not re-run SkyTemplate's post-format re-indent — a known
     * limitation of the "Reformat" paste mode for Sky blocks in HTML hosts.
     */
    fun testPasteLoopBlockIndentsBodyAcrossIndentSettings() {
        for (mode in intArrayOf(
            CodeInsightSettings.NO_REFORMAT,
            CodeInsightSettings.INDENT_BLOCK,
            CodeInsightSettings.INDENT_EACH_LINE,
        )) {
            val result = paste("a.html", "{loop xs as x}\nbody\n{/}\n", "<caret>", mode)
            assertEquals(
                "mode=$mode should indent the loop body",
                "{loop xs as x}\n    body\n{/}",
                result.trimEnd(),
            )
        }
    }

    /**
     * Regression for the real-world report: pasting an ALREADY correctly
     * indented Sky block must keep its indent. Our re-indent makes no edit
     * here, so the fix is that we still claim `indented` — otherwise the
     * platform's INDENT_EACH_LINE pass flattens the body to column 0.
     */
    fun testPasteAlreadyIndentedBlockIsNotFlattened() {
        val result = paste(
            "a.html",
            "{loop xs as x}\n    body\n{/}\n",
            "<caret>",
            CodeInsightSettings.INDENT_EACH_LINE,
        )
        assertEquals("{loop xs as x}\n    body\n{/}", result.trimEnd())
    }

    fun testPasteNestedBlockIndents() {
        val result = paste(
            "a.html",
            "{?cond}\n<div>\nx\n</div>\n{/}\n",
            "<caret>",
        )
        assertEquals(
            "{?cond}\n    <div>\n        x\n    </div>\n{/}",
            result.trimEnd(),
        )
    }

    fun testPasteIntoPlainHtmlFileWithoutSkyIsUntouchedByUs() {
        // No Sky structure ⇒ our processor makes no edits and does not claim
        // `indented`, so the platform's normal paste indent applies. We only
        // assert the pasted text survives (no corruption from our processor).
        val result = paste("a.html", "<span>hi</span>", "<div><caret></div>")
        assertTrue(result.contains("<span>hi</span>"))
    }

    fun testMoveStatementDownReindentsMovedLine() {
        // Move the `<p>a</p>` line down past `<p>b</p>`. Both stay at body
        // depth under {loop}; the moved line must not collapse to column 0.
        myFixture.configureByText(
            "a.html",
            "{loop xs as x}\n    <caret><p>a</p>\n    <p>b</p>\n{/}",
        )
        myFixture.performEditorAction(IdeActions.ACTION_MOVE_STATEMENT_DOWN_ACTION)
        val text = myFixture.editor.document.text
        assertEquals(
            "{loop xs as x}\n    <p>b</p>\n    <p>a</p>\n{/}",
            text.trimEnd(),
        )
    }

    fun testMoveStatementDownOutOfBlockOutdents() {
        // P2-13b: the last body line of `{loop}` (indent 8, nested under
        // `<div>`) moves down past the block's `{/}` closer. The one-sided
        // (lift-only) re-indent used everywhere else can't shrink 8 back to
        // the correct post-move depth (4, one step under `<div>`) — this is
        // the one path where the mover must set the moved line's indent
        // exactly rather than only ever lifting it.
        myFixture.configureByText(
            "a.html",
            "<div>\n    {loop xs as x}\n        <caret><p>a</p>\n    {/}\n</div>",
        )
        myFixture.performEditorAction(IdeActions.ACTION_MOVE_STATEMENT_DOWN_ACTION)
        val text = myFixture.editor.document.text
        assertEquals(
            "<div>\n    {loop xs as x}\n    {/}\n    <p>a</p>\n</div>",
            text.trimEnd(),
        )
    }

    fun testMoveStatementUpOutOfBlockOutdents() {
        // Mirror of the above for Move Up: the FIRST body line of `{loop}`
        // moves up past the `{loop …}` opener, ending up above the block
        // (outside it, directly under `<div>`).
        myFixture.configureByText(
            "a.html",
            "<div>\n    {loop xs as x}\n        <caret><p>a</p>\n    {/}\n</div>",
        )
        myFixture.performEditorAction(IdeActions.ACTION_MOVE_STATEMENT_UP_ACTION)
        val text = myFixture.editor.document.text
        assertEquals(
            "<div>\n    <p>a</p>\n    {loop xs as x}\n    {/}\n</div>",
            text.trimEnd(),
        )
    }

    fun testMoveStatementDownWithinBlockKeepsIndent() {
        // Existing in-block sibling swap must stay unaffected by the new
        // exact-indent handling — both lines stay at the same body depth.
        myFixture.configureByText(
            "a.html",
            "{loop xs as x}\n    <caret><p>a</p>\n    <p>b</p>\n{/}",
        )
        myFixture.performEditorAction(IdeActions.ACTION_MOVE_STATEMENT_DOWN_ACTION)
        val text = myFixture.editor.document.text
        assertEquals(
            "{loop xs as x}\n    <p>b</p>\n    <p>a</p>\n{/}",
            text.trimEnd(),
        )
    }

    fun testMoveStatementDownAtLastLineDoesNotThrow() {
        // P-BUG-12: moving the file's LAST physical line down has no swap
        // target below it, so the platform's MoveInfo.toMove2 stays null.
        // afterMove must not NPE dereferencing it — the action should
        // simply no-op / leave the document intact instead of crashing.
        myFixture.configureByText(
            "a.html",
            "{loop xs as x}\n    <p>a</p>\n<caret>{/}",
        )
        myFixture.performEditorAction(IdeActions.ACTION_MOVE_STATEMENT_DOWN_ACTION)
        val text = myFixture.editor.document.text
        assertEquals(
            "{loop xs as x}\n    <p>a</p>\n{/}",
            text.trimEnd(),
        )
    }
}
