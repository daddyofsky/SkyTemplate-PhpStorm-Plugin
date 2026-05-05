package com.novaframework.templatelang.sky

import com.intellij.openapi.util.TextRange

/**
 * Single-entry identity-keyed cache for [SkyTemplateRanges.computeTemplateRanges].
 *
 * The brace-matcher delegates ([SkyTemplateAwareJsBraceMatcher] /
 * [SkyTemplateAwareCssBraceMatcher]) call this from `isLBraceToken` /
 * `isRBraceToken` / `isStructuralBrace` — paths that fire dozens of times
 * per cursor-on-brace highlight, walking the iterator to find the pair.
 * A full lex over the document text per call would dominate the cost on
 * large HTML files, so we cache by reference identity of the underlying
 * `CharSequence` (typically the document's `getCharsSequence()` instance,
 * which stays stable until the document mutates).
 *
 * The cache holds a single (text, ranges) pair. Calling with a different
 * `CharSequence` instance evicts the previous entry. Concurrent calls may
 * recompute, but the result is identical so the race is benign — we
 * deliberately avoid synchronisation to keep the fast path lock-free.
 */
internal object SkyTemplateRangeCache {
    @Volatile
    private var cached: Entry? = null

    private class Entry(val text: CharSequence, val ranges: List<TextRange>)

    fun get(text: CharSequence): List<TextRange> {
        val current = cached
        if (current != null && current.text === text) return current.ranges
        val ranges = SkyTemplateRanges.computeTemplateRanges(text)
        cached = Entry(text, ranges)
        return ranges
    }
}
