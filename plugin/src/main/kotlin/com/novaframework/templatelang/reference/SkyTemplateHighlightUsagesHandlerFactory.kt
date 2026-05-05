package com.novaframework.templatelang.reference

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.util.Consumer
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateFile
import com.novaframework.templatelang.sky.SkyTemplateRanges

/**
 * Fixes the "highlight identifier under caret" visual indicator for SkyTemplate
 * identifiers inside `<script>` blocks.
 *
 * **Why this exists.** When the cursor sits on `bar` inside a template tag like
 * `{=foo() . bar()}` in HTML `<script>` content, JS PSI parses the body as a
 * qualified `JSReferenceExpression`. The platform's [IdentifierHighlighterPass]
 * uses `findReferenceAt(offset).resolve()` to determine what to highlight; that
 * runs through `PsiMultiReference.chooseReference()`, whose comparator (verified
 * by reading the bundled `PsiMultiReference.class` bytecode) returns 0 when two
 * refs cover the same offset with equivalent ranges from different elements,
 * falling back to stable-sort insertion order. JS's intrinsic self-ref sits
 * closer to the leaf in the leaf-to-file walk and is collected first, so it
 * wins; `IdentifierHighlighterPass` then highlights JS's resolution target
 * (the chain qualifier `foo`) instead of `bar`.
 *
 * The companion fix at [SkyTemplateGotoDeclarationHandler] short-circuits the
 * navigation pipeline (Cmd+click / Cmd+B) before that race; this factory does
 * the same for the highlight pipeline. Together they keep template-tag PHP
 * identifiers behaving consistently in JS host context.
 *
 * Scope:
 *   - Fires only on HTML/XML host or `.sky` files (matches the contributor).
 *   - Fires only when the offset sits inside a SkyTemplate range AND our
 *     reference detector emits a ref covering the offset.
 *   - Returns null otherwise, so non-template HTML/JS/CSS highlighting is
 *     unaffected.
 */
class SkyTemplateHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactory {

    override fun createHighlightUsagesHandler(
        editor: Editor,
        file: PsiFile,
    ): HighlightUsagesHandlerBase<*>? {
        if (!TemplateLangFileFilter.shouldProcess(file)) return null
        if (file !is XmlFile && file !is SkyTemplateFile) return null

        val text = file.text
        if (text.isEmpty() || '{' !in text) return null

        val offset = editor.caretModel.offset
        // Confirm cursor sits inside a real template tag — keeps non-template
        // braces (CSS rules, JS object literals) on default highlight behavior.
        val templateRanges = SkyTemplateRanges.computeTemplateRanges(text)
        if (templateRanges.none { it.containsOffset(offset) }) return null

        // Find our ref covering the cursor. Use the same RefDetector pipeline
        // that powers Find Usages and Cmd+click, so what gets highlighted
        // matches what gets navigated.
        val allRefs = SkyTemplateRefDetector.detect(text)
        val refAtCursor = allRefs.firstOrNull { it.rangeInHost.containsOffset(offset) }
            ?: return null

        // Highlight every ref in the file with the same simple name + kind.
        // Same-FQN comparison would require resolving every ref against
        // PhpIndex, which is heavy for a per-keystroke pass and unnecessary —
        // a name+kind match in the same file is the right granularity for
        // local "occurrences of this symbol" highlighting.
        val targetName = simpleName(refAtCursor.nameInSource)
        val targetKind = refAtCursor.kind
        val occurrences = allRefs
            .filter { simpleName(it.nameInSource) == targetName && it.kind == targetKind }
            .map { it.rangeInHost }

        return Handler(editor, file, occurrences)
    }

    private fun simpleName(qualified: String): String =
        qualified.substringAfterLast('\\')

    private class Handler(
        editor: Editor,
        file: PsiFile,
        private val occurrences: List<TextRange>,
    ) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {

        // No real "targets" — we already know the ranges to highlight.
        // The base class needs the targets list to drive `computeUsages`,
        // and `selectTargets` to resolve any ambiguity. We bypass both by
        // returning a single-item placeholder list and computing ranges
        // directly.
        override fun getTargets(): List<PsiElement> = listOf(myFile)

        override fun selectTargets(
            targets: MutableList<out PsiElement>,
            selectionConsumer: Consumer<in MutableList<out PsiElement>>,
        ) {
            selectionConsumer.consume(targets)
        }

        override fun computeUsages(targets: MutableList<out PsiElement>) {
            occurrences.forEach { myReadUsages.add(it) }
        }
    }
}
