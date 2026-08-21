package com.novaframework.templatelang.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Cache-behaviour coverage for [SkyTemplateRangeCache] — P-BUG-02. Verifies
 * that repeated calls against the SAME `CharSequence` identity return the
 * cached list instance (no recompute) while a different identity (even with
 * identical content) evicts and recomputes, so a document edit can never
 * observe a stale entry. Also covers the live-mutable-view shape a
 * `Document.getCharsSequence()` caller hits: identity stays constant across
 * an in-place edit, so the cache keys on identity + length to avoid serving
 * ranges computed against pre-edit content (see
 * [SkyTemplateEnterHandlerIntegrationTest] for the end-to-end regression
 * this guards — an Enter press mutates the document and immediately
 * re-reads it to reindent the line below).
 */
class SkyTemplateRangeCacheTest {

    @Test fun sameIdentity_templateRanges_returnsCachedInstance() {
        SkyTemplateRangeCache.clearForTest()
        val text = "{if a}{name}{/}"
        val first = SkyTemplateRangeCache.get(text)
        val second = SkyTemplateRangeCache.get(text)
        assertSame(first, second)
        assertEquals(3, first.size)
    }

    @Test fun differentIdentity_sameContent_recomputes() {
        SkyTemplateRangeCache.clearForTest()
        val textA = "{if a}{name}{/}"
        val textB = "{if a}{name}{/}".toCharArray().concatToString()
        val first = SkyTemplateRangeCache.get(textA)
        val second = SkyTemplateRangeCache.get(textB)
        assertNotSame(first, second)
        assertEquals(first.size, second.size)
    }

    @Test fun newEntry_evictsPreviousIdentity() {
        SkyTemplateRangeCache.clearForTest()
        val textA = "{if a}{/}"
        val textB = "{loop x}{/}"
        val forA1 = SkyTemplateRangeCache.get(textA)
        SkyTemplateRangeCache.get(textB)
        val forA2 = SkyTemplateRangeCache.get(textA)
        // Same content as forA1, but the entry was evicted by textB in
        // between — a fresh (distinct) list is computed, not the stale one.
        assertNotSame(forA1, forA2)
        assertEquals(forA1.size, forA2.size)
    }

    @Test fun independentFields_cachedTogetherUnderOneEntry() {
        SkyTemplateRangeCache.clearForTest()
        val text = "<script>{?var}\nfunction f() {\n}\n{/}</script>"
        val template1 = SkyTemplateRangeCache.get(text)
        val comment1 = SkyTemplateRangeCache.getCommentRanges(text)
        val indent1 = SkyTemplateRangeCache.getIndentRanges(text)
        val embedded1 = SkyTemplateRangeCache.getProtectedEmbeddedRanges(text)
        val pairing1 = SkyTemplateRangeCache.getBlockPairing(text)

        // Same CharSequence identity — every field comes back from the same
        // entry without recomputation.
        assertSame(template1, SkyTemplateRangeCache.get(text))
        assertSame(comment1, SkyTemplateRangeCache.getCommentRanges(text))
        assertSame(indent1, SkyTemplateRangeCache.getIndentRanges(text))
        assertSame(embedded1, SkyTemplateRangeCache.getProtectedEmbeddedRanges(text))
        assertSame(pairing1, SkyTemplateRangeCache.getBlockPairing(text))
    }

    @Test fun clearForTest_forcesRecompute() {
        val text = "{if a}{/}"
        val first = SkyTemplateRangeCache.get(text)
        SkyTemplateRangeCache.clearForTest()
        val second = SkyTemplateRangeCache.get(text)
        assertNotSame(first, second)
        assertEquals(first.size, second.size)
    }

    /**
     * Regression for the identity-only design: `Document.getCharsSequence()`
     * returns the SAME instance across `replaceString` / `insertString`
     * calls (a live mutable view), so identity alone cannot detect an
     * in-place edit. A `StringBuilder` reproduces that shape — same
     * reference, mutated content — and the cache must key on length too so
     * a query issued right after the mutation doesn't get ranges computed
     * against the pre-edit text.
     */
    @Test fun sameIdentityMutableView_lengthChange_forcesRecompute() {
        SkyTemplateRangeCache.clearForTest()
        val sb = StringBuilder("{if a}{/}")
        val before = SkyTemplateRangeCache.get(sb)
        assertEquals(2, before.size)

        sb.insert(6, "{name}")
        val after = SkyTemplateRangeCache.get(sb)
        assertNotSame(before, after)
        assertEquals(3, after.size)
    }

    @Test fun sameIdentityMutableView_unchangedLength_stillCached() {
        SkyTemplateRangeCache.clearForTest()
        val sb = StringBuilder("{if a}{/}")
        val before = SkyTemplateRangeCache.get(sb)
        val after = SkyTemplateRangeCache.get(sb)
        assertSame(before, after)
    }
}
