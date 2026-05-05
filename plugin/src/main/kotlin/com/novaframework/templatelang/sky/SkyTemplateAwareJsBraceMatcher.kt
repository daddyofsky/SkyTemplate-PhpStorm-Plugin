package com.novaframework.templatelang.sky

import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter
import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.lang.javascript.highlighting.JSBraceMatcher
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType

/**
 * JavaScript brace matcher that ignores `{` / `}` characters that fall
 * inside a SkyTemplate construct in HTML / XML host files.
 *
 * Why subclass [PairedBraceMatcherAdapter]?
 *   `BraceMatchingUtil.getBraceMatcher(FileType, Language)` returns the
 *   adapter unwrapped only if the underlying matcher *is* an instance
 *   of `PairedBraceMatcherAdapter` (or `XmlAwareBraceMatcher`). By
 *   subclassing the adapter we both (a) inherit the standard JS
 *   pair definitions via the wrapped [JSBraceMatcher] and (b) get the
 *   offset-aware overrides (`isLBraceToken(iter, …)`) we need to filter.
 *
 * The filter only fires in HTML / XML host files. Standalone `*.js`
 * (and `*.sky` multi-tree, where the JS tree only sees OUTER_CONTENT
 * regions to begin with) is unaffected.
 */
class SkyTemplateAwareJsBraceMatcher : PairedBraceMatcherAdapter(
    JSBraceMatcher(),
    JavascriptLanguage.INSTANCE,
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
