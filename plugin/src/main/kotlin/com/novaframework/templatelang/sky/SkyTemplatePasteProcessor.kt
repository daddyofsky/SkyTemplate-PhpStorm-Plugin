package com.novaframework.templatelang.sky

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/**
 * Re-applies SkyTemplate block indentation to text pasted into a
 * SkyTemplate-enabled file.
 *
 * **Why this exists.** [SkyTemplatePostFormatProcessor] only runs on the
 * explicit *Reformat Code* path (`CodeStyleManager.reformatText`). The
 * *Indent on Paste* setting (the default) drives the platform's `PasteHandler`,
 * which re-indents pasted lines with `adjustLineIndent` and never invokes a
 * `PostFormatProcessor`. The host (HTML / XML) line-indent has no notion of
 * `{loop …} … {/}` / `{?…}` block structure, so pasted Sky blocks land at the
 * host indent level. We hook the post-paste path and run the same unified
 * HTML+Sky re-indent the formatter uses.
 *
 * **Two `PasteHandler` quirks shape this implementation:**
 *
 *  1. `PasteHandler.ProcessorAndData.create()` *skips* a processor whose
 *     [extractTransferableData] returns an empty list — `processTransferableData`
 *     is then never called. To fire on **every** paste (including text from
 *     outside the IDE, which carries no flavour of ours), [extractTransferableData]
 *     returns a single inert marker. The marker's content is irrelevant: the
 *     re-indent works off the pasted [bounds] in the target document, not the
 *     clipboard payload.
 *  2. The platform's own indent / reformat-on-paste runs *after* the
 *     processors. If we re-indented and did nothing else, that later pass
 *     would re-flatten our Sky indent back to the host level. Setting
 *     `indented = true` tells `PasteHandler` indentation is already handled, so
 *     it skips its own pass — but only when we actually changed something, so a
 *     paste with no Sky structure still gets the platform's normal indent.
 *
 * Scope mirrors the rest of the plugin: gated on [TemplateLangFileFilter]
 * (master enable switch + extension whitelist).
 */
class SkyTemplatePasteProcessor : CopyPastePostProcessor<TextBlockTransferableData>() {

    override fun collectTransferableData(
        file: PsiFile,
        editor: Editor,
        startOffsets: IntArray,
        endOffsets: IntArray,
    ): List<TextBlockTransferableData> = emptyList()

    // Always non-empty so PasteHandler keeps us in the processor list and
    // calls processTransferableData (see quirk 1 in the class KDoc). The
    // marker is inert — we ignore `values` entirely.
    override fun extractTransferableData(content: Transferable): List<TextBlockTransferableData> =
        MARKER

    override fun processTransferableData(
        project: Project,
        editor: Editor,
        bounds: RangeMarker,
        caretOffset: Int,
        indented: Ref<in Boolean>,
        values: List<TextBlockTransferableData>,
    ) {
        if (!bounds.isValid) return
        val document = editor.document
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        if (!TemplateLangFileFilter.shouldProcess(psi)) return

        val indentStep = resolveIndentStep(psi)
        var changed = false
        // PasteHandler invokes processors on the EDT but NOT inside a
        // write action, so document edits must be wrapped explicitly — the
        // active paste command captures them into a single undo.
        ApplicationManager.getApplication().runWriteAction {
            SkyTemplatePostFormatLogic.reindent(
                text = document.charsSequence,
                range = TextRange(bounds.startOffset, bounds.endOffset),
                indentStep = indentStep,
            ) { from, to, replacement ->
                document.replaceString(from, to, replacement)
                changed = true
            }
            if (changed) PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        // Always claim indentation for a Sky-enabled file — NOT only when we
        // changed something (see quirk 2 in the class KDoc). The platform's
        // post-paste indent (`INDENT_EACH_LINE` is the default) is Sky-unaware:
        // it sees `{loop}` / `{?…}` as opaque and would re-flatten the body to
        // the host depth. That clobbers BOTH our just-applied re-indent AND the
        // case where the pasted text was already correctly indented (our walk
        // makes no edit, yet the platform would still flatten it). Claiming
        // `indented` makes PasteHandler skip that pass for the indent settings.
        indented.set(true)
    }

    private fun resolveIndentStep(file: PsiFile): String {
        return try {
            val opts = CodeStyle.getIndentOptions(file)
            if (opts.USE_TAB_CHARACTER) "\t" else " ".repeat(opts.INDENT_SIZE.coerceAtLeast(1))
        } catch (_: Throwable) {
            "    "
        }
    }

    private companion object {
        /** Inert single-element payload; see quirk 1 in the class KDoc. */
        val MARKER: List<TextBlockTransferableData> = listOf(
            object : TextBlockTransferableData {
                private val flavor = DataFlavor(
                    SkyTemplatePasteProcessor::class.java,
                    "SkyTemplate paste marker",
                )

                override fun getFlavor(): DataFlavor = flavor
            },
        )
    }
}
