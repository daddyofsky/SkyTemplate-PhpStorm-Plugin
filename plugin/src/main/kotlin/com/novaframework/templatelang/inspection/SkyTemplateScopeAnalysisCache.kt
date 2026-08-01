package com.novaframework.templatelang.inspection

/**
 * Single-entry cache for [SkyTemplateScopeAnalyzer.analyze], keyed by
 * `CharSequence` identity **plus length** — same recipe as
 * [com.novaframework.templatelang.sky.SkyTemplateRangeCache] (see that
 * class's KDoc for why identity+length is the right key for a live
 * `viewProvider.contents` / `document.charsSequence` instance).
 *
 * Every current caller ([SkyTemplateLoopScopeInspection],
 * [SkyTemplateRedundantAtInspection], [SkyTemplateDuplicateElseInspection],
 * [SkyTemplateScopeAnnotator]) already reads `file.viewProvider.contents`
 * (not `file.text`), so they all hit this cache on the identical
 * `CharSequence` instance when they run back-to-back in the same
 * highlighting pass — collapsing what used to be up to three independent
 * full-file scope walks into one.
 *
 * Kept as its own object rather than folded into `SkyTemplateRangeCache` to
 * avoid a `sky` → `inspection` package dependency: [SkyTemplateScopeAnalyzer]
 * is layered on top of `sky`'s lexer / range primitives, not part of them.
 */
internal object SkyTemplateScopeAnalysisCache {
    @Volatile
    private var cached: Entry? = null

    private class Entry(val text: CharSequence, val length: Int) {
        val issues: List<SkyTemplateScopeAnalyzer.Issue> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            SkyTemplateScopeAnalyzer.analyze(text)
        }
    }

    fun get(text: CharSequence): List<SkyTemplateScopeAnalyzer.Issue> {
        val current = cached
        if (current != null && current.text === text && current.length == text.length) return current.issues
        val entry = Entry(text, text.length)
        cached = entry
        return entry.issues
    }

    /** Test-only alias to reset cache state between tests. */
    @org.jetbrains.annotations.TestOnly
    fun clearForTest() {
        cached = null
    }
}
