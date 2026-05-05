package com.novaframework.templatelang.sky

import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter
import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.lang.css.CSSLanguage
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.css.impl.util.editor.CssBraceMatcher

/**
 * CSS counterpart of [SkyTemplateAwareJsBraceMatcher] — keeps the
 * platform's CSS brace pairing in standalone `.css` files but suppresses
 * the `{` / `}` chars from a `{=…}` / `{?…}` template construct embedded
 * in a `<style>` block of an HTML / XML host so that brace matching,
 * structural navigation, and "go to matching brace" don't pair the
 * template's `{` against an unrelated CSS rule body.
 */
class SkyTemplateAwareCssBraceMatcher : PairedBraceMatcherAdapter(
    CssBraceMatcher(),
    CSSLanguage.INSTANCE,
) {

    override fun isLBraceToken(
        iterator: HighlighterIterator,
        fileText: CharSequence,
        fileType: FileType,
    ): Boolean {
        if (!super.isLBraceToken(iterator, fileText, fileType)) return false
        return !overlapsTemplateRange(iterator.start, fileText, fileType)
    }

    override fun isRBraceToken(
        iterator: HighlighterIterator,
        fileText: CharSequence,
        fileType: FileType,
    ): Boolean {
        if (!super.isRBraceToken(iterator, fileText, fileType)) return false
        return !overlapsTemplateRange(iterator.start, fileText, fileType)
    }

    override fun isStructuralBrace(
        iterator: HighlighterIterator,
        text: CharSequence,
        fileType: FileType,
    ): Boolean {
        if (!super.isStructuralBrace(iterator, text, fileType)) return false
        return !overlapsTemplateRange(iterator.start, text, fileType)
    }

    private fun overlapsTemplateRange(
        offset: Int,
        text: CharSequence,
        fileType: FileType,
    ): Boolean {
        if (fileType !is HtmlFileType && fileType !is XmlFileType) return false
        val ranges = SkyTemplateRangeCache.get(text)
        if (ranges.isEmpty()) return false
        return SkyTemplateRanges.anyOverlap(ranges, offset, offset + 1)
    }
}
