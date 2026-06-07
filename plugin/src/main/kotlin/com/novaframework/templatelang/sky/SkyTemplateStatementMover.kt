package com.novaframework.templatelang.sky

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.moveUpDown.LineMover
import com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Re-applies SkyTemplate block indentation after a *Move Statement Up/Down*
 * (`Cmd/Ctrl+Shift+↑/↓`).
 *
 * **Why this exists.** The platform's move-statement action relocates lines
 * and re-indents them with `adjustLineIndent`, which — like paste — has no
 * notion of `{loop …} … {/}` / `{?…}` block structure, so a moved Sky tag or
 * body line lands at the host indent level. [SkyTemplatePostFormatProcessor]
 * never runs on this path.
 *
 * We extend [LineMover] so the actual move computation (which line range
 * moves, swap target) is reused verbatim; we only add an [afterMove] pass
 * that re-indents the affected line region with the same unified HTML+Sky
 * walk the formatter uses.
 *
 * Scope is deliberately narrow: [checkAvailable] engages only for files that
 * pass [TemplateLangFileFilter] **and** actually contain SkyTemplate
 * constructs. Pure HTML files (no `{ … }`) fall through to the platform's
 * default movers untouched, so we never change move behaviour where there is
 * no Sky structure to indent.
 */
class SkyTemplateStatementMover : LineMover() {

    override fun checkAvailable(
        editor: Editor,
        file: PsiFile,
        info: StatementUpDownMover.MoveInfo,
        down: Boolean,
    ): Boolean {
        if (!TemplateLangFileFilter.shouldProcess(file)) return false
        if (SkyTemplateRanges.computeTemplateRanges(file.viewProvider.contents).isEmpty()) return false
        val available = super.checkAvailable(editor, file, info, down)
        if (available) {
            // MoverWrapper runs the platform's `adjustLineIndent` AFTER
            // afterMove (swap → afterMove → indentTarget adjust). That host
            // indent is Sky-unaware and would re-flatten what afterMove sets,
            // so we take ownership of indentation: skip the platform pass and
            // let afterMove's unified HTML+Sky re-indent be authoritative.
            info.indentTarget = false
        }
        return available
    }

    override fun afterMove(
        editor: Editor,
        file: PsiFile,
        info: StatementUpDownMover.MoveInfo,
        down: Boolean,
    ) {
        super.afterMove(editor, file, info, down)
        val document = editor.document

        // Affected region: the union of the moved block and its swap target.
        // After the move the two have swapped, but the line index span they
        // occupy is unchanged, so re-indenting that span covers both.
        val startLine = minOf(info.toMove.startLine, info.toMove2.startLine)
            .coerceIn(0, document.lineCount - 1)
        val endLineExclusive = maxOf(info.toMove.endLine, info.toMove2.endLine)
            .coerceIn(0, document.lineCount)
        if (endLineExclusive <= startLine) return

        val from = document.getLineStartOffset(startLine)
        val to = document.getLineEndOffset((endLineExclusive - 1).coerceIn(0, document.lineCount - 1))

        val indentStep = resolveIndentStep(file)
        // afterMove is not guaranteed to run inside a write action, so wrap
        // the re-indent edits explicitly (reentrant when one is already open).
        ApplicationManager.getApplication().runWriteAction {
            SkyTemplatePostFormatLogic.reindent(
                text = document.charsSequence,
                range = TextRange(from, to),
                indentStep = indentStep,
            ) { f, t, replacement ->
                document.replaceString(f, t, replacement)
            }
            PsiDocumentManager.getInstance(file.project).commitDocument(document)
        }
    }

    private fun resolveIndentStep(file: PsiFile): String {
        return try {
            val opts = CodeStyle.getIndentOptions(file)
            if (opts.USE_TAB_CHARACTER) "\t" else " ".repeat(opts.INDENT_SIZE.coerceAtLeast(1))
        } catch (_: Throwable) {
            "    "
        }
    }
}
