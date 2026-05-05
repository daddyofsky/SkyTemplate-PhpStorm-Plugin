package com.novaframework.templatelang.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.xml.XmlFile
import com.intellij.openapi.util.TextRange
import com.intellij.util.ProcessingContext
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateFile
import com.novaframework.templatelang.sky.SkyTemplateRanges

class SkyTemplateReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        if (!TemplateLangFileFilter.shouldProcess(file)) return PsiReference.EMPTY_ARRAY
        // Belt-and-suspenders: even after the whitelist gate, restrict to PSI
        // shapes our detector understands (some host languages may map an
        // arbitrary file extension to a non-XML PSI root).
        if (file !is XmlFile && file !is SkyTemplateFile) return PsiReference.EMPTY_ARRAY
        val text = file.text
        if (text.length < 3 || '{' !in text) return PsiReference.EMPTY_ARRAY

        val scan = scanCached(file)
        val refs = scan.references
        if (refs.isEmpty()) return PsiReference.EMPTY_ARRAY

        // Comment ranges win — every byte inside `{*…*}` is plain text per
        // user spec, no references attached even if syntax allows.
        val commentRanges = scan.commentRanges
        val elementRange = element.textRange ?: return PsiReference.EMPTY_ARRAY
        if (commentRanges.any { it.contains(elementRange) }) return PsiReference.EMPTY_ARRAY

        val elementRefs = refs.mapNotNull { r ->
            val refRange = r.rangeInHost
            when {
                !elementRange.contains(refRange) -> null
                SkyTemplateRanges.anyOverlap(commentRanges, refRange.startOffset, refRange.endOffset) -> null
                else -> {
                    val rangeInElement = refRange.shiftLeft(elementRange.startOffset)
                    SkyTemplatePhpReference(
                        element,
                        rangeInElement,
                        r.kind,
                        r.nameInSource,
                        r.classNameInSource,
                        r.callTargetName,
                        r.callTargetClass,
                    )
                }
            }
        }
        if (elementRefs.isEmpty()) return PsiReference.EMPTY_ARRAY
        return elementRefs.toTypedArray()
    }

    private data class FileScan(
        val references: List<SkyTemplateRefDetector.Ref>,
        val commentRanges: List<TextRange>,
    )

    private fun scanCached(file: PsiFile): FileScan =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                FileScan(
                    references = SkyTemplateRefDetector.detect(file.text, baseOffset = 0),
                    commentRanges = SkyTemplateRanges.computeCommentRanges(file.text),
                ),
                file,
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }
}
