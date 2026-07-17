package com.novaframework.templatelang.sky

import com.intellij.openapi.util.TextRange

/**
 * Single-entry cache for [SkyTemplateRanges]'s scan results, keyed by
 * `CharSequence` identity **plus length**.
 *
 * Originally just [SkyTemplateRanges.computeTemplateRanges] for the
 * brace-matcher delegates ([SkyTemplateAwareJsBraceMatcher] /
 * [SkyTemplateAwareCssBraceMatcher]), which call this from `isLBraceToken` /
 * `isRBraceToken` / `isStructuralBrace` — paths that fire dozens of times
 * per cursor-on-brace highlight, walking the iterator to find the pair.
 * Now also covers comment / indent / protected-embedded ranges: the Enter
 * handler, the line-indent provider, and the closing-tag aligner each used
 * to call `computeTemplateRanges` / `computeCommentRanges` /
 * `computeProtectedEmbeddedRanges` independently per keystroke, re-lexing
 * the whole document several times over for a single Enter press.
 *
 * A full lex over the document text per call would dominate the cost on
 * large HTML files, so we cache by reference identity of the underlying
 * `CharSequence` — typically the document's `getCharsSequence()` instance.
 *
 * **Why length is part of the key.** `Document.getCharsSequence()` returns
 * a live, mutable VIEW whose identity stays the SAME for the entire
 * lifetime of the document — `replaceString` / `insertString` mutate the
 * existing instance in place rather than handing back a new one. Keying
 * purely on identity would therefore keep serving a `List<TextRange>`
 * computed against whatever content existed the moment each field was
 * first lazily evaluated, even after the document has since grown or
 * shrunk — exactly the shape of a self-modifying handler that edits the
 * document and immediately re-reads it (the Enter handler applying a
 * caret-line fix, then reindenting the line below in the same call).
 * Comparing `text.length` on every lookup is a cheap, purely-local way to
 * detect that the live view has moved on and force a fresh entry, without
 * plumbing a `Document` / modification-stamp reference through every call
 * site (several of which — [SkyTemplateIndentContext],
 * [SkyTemplatePostFormatLogic] — only ever see a bare `CharSequence`).
 * Length alone cannot catch an in-place edit that preserves length (e.g.
 * replacing one character with another of the same width without any
 * insertion/deletion), but every caller in this codebase only mutates via
 * `replaceString`/`insertString`/`deleteString` calls that change the
 * indent-prefix length, so this is sufficient in practice; callers that
 * need airtight correctness after a same-length edit should call
 * [invalidate] explicitly.
 *
 * The cache holds a single entry (one `(identity, length)` pair at a
 * time). Concurrent calls may recompute, but the result is identical so
 * the race is benign — we deliberately avoid synchronisation to keep the
 * fast path lock-free. Each field inside an entry is computed lazily and
 * independently, so a caller that only needs e.g. comment ranges doesn't
 * pay for the others.
 */
internal object SkyTemplateRangeCache {
    @Volatile
    private var cached: Entry? = null

    private class Entry(val text: CharSequence, val length: Int) {
        val templateRanges: List<TextRange> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            SkyTemplateRanges.computeTemplateRanges(text)
        }
        val commentRanges: List<TextRange> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            SkyTemplateRanges.computeCommentRanges(text)
        }
        val indentRanges: List<TextRange> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            SkyTemplateRanges.computeIndentRanges(text)
        }
        val protectedEmbeddedRanges: List<TextRange> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            SkyTemplateRanges.computeProtectedEmbeddedRanges(text)
        }
        val blockPairing: SkyTemplateFoldingScanner.BlockPairingResult by lazy(LazyThreadSafetyMode.PUBLICATION) {
            SkyTemplateFoldingScanner.analyze(text)
        }
    }

    private fun entryFor(text: CharSequence): Entry {
        val current = cached
        if (current != null && current.text === text && current.length == text.length) return current
        val entry = Entry(text, text.length)
        cached = entry
        return entry
    }

    fun get(text: CharSequence): List<TextRange> = entryFor(text).templateRanges

    fun getCommentRanges(text: CharSequence): List<TextRange> = entryFor(text).commentRanges

    fun getIndentRanges(text: CharSequence): List<TextRange> = entryFor(text).indentRanges

    fun getProtectedEmbeddedRanges(text: CharSequence): List<TextRange> = entryFor(text).protectedEmbeddedRanges

    fun getBlockPairing(text: CharSequence): SkyTemplateFoldingScanner.BlockPairingResult = entryFor(text).blockPairing

    /**
     * Drop the current entry unconditionally. Not required for correctness
     * (the length check in [entryFor] already catches every mutation shape
     * used in this codebase) but cheap insurance for a caller that just
     * mutated the document and wants to guarantee the next lookup
     * recomputes, regardless of whether the edit happened to preserve
     * length.
     */
    fun invalidate() {
        cached = null
    }

    /** Test-only alias for [invalidate] — resets cache state between tests. */
    @org.jetbrains.annotations.TestOnly
    fun clearForTest() {
        cached = null
    }
}
