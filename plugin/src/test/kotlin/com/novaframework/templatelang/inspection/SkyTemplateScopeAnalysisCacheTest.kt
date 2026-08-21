package com.novaframework.templatelang.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache-behaviour coverage for [SkyTemplateScopeAnalysisCache] — mirrors
 * [com.novaframework.templatelang.sky.SkyTemplateRangeCacheTest]'s identity
 * + length recipe. [SkyTemplateLoopScopeInspection], [SkyTemplateRedundantAtInspection],
 * [SkyTemplateDuplicateElseInspection], and [SkyTemplateScopeAnnotator] all
 * call [SkyTemplateScopeAnalysisCache.get] on the same `viewProvider.contents`
 * instance within one highlighting pass; this verifies that a document edit
 * can never leave a stale entry behind for the next pass.
 */
class SkyTemplateScopeAnalysisCacheTest {

    @Test fun sameIdentity_returnsCachedInstance() {
        SkyTemplateScopeAnalysisCache.clearForTest()
        val text = "{.name}"
        val first = SkyTemplateScopeAnalysisCache.get(text)
        val second = SkyTemplateScopeAnalysisCache.get(text)
        assertSame(first, second)
        assertEquals(1, first.size)
    }

    @Test fun differentIdentity_sameContent_recomputes() {
        SkyTemplateScopeAnalysisCache.clearForTest()
        val textA = "{.name}"
        val textB = "{.name}".toCharArray().concatToString()
        val first = SkyTemplateScopeAnalysisCache.get(textA)
        val second = SkyTemplateScopeAnalysisCache.get(textB)
        assertNotSame(first, second)
        assertEquals(first.size, second.size)
    }

    @Test fun newEntry_evictsPreviousIdentity() {
        SkyTemplateScopeAnalysisCache.clearForTest()
        val textA = "{.name}"
        val textB = "{..name}"
        val forA1 = SkyTemplateScopeAnalysisCache.get(textA)
        SkyTemplateScopeAnalysisCache.get(textB)
        val forA2 = SkyTemplateScopeAnalysisCache.get(textA)
        // Same content as forA1, but the single-entry cache was evicted by
        // textB in between — a fresh (distinct) list is computed, not stale.
        assertNotSame(forA1, forA2)
        assertEquals(forA1.size, forA2.size)
    }

    /**
     * Regression for the identity-only design: a live mutable view (e.g.
     * `Document.getCharsSequence()`) keeps the SAME reference across an
     * in-place edit, so identity alone can't detect the mutation. A
     * `StringBuilder` reproduces that shape — the cache must key on length
     * too, or a lookup issued right after the edit would return issues
     * computed against the pre-edit text.
     */
    @Test fun sameIdentityMutableView_lengthChange_forcesRecompute() {
        SkyTemplateScopeAnalysisCache.clearForTest()
        val sb = StringBuilder("{loop xs}{.name}{/}")
        val before = SkyTemplateScopeAnalysisCache.get(sb)
        assertTrue(before.isEmpty())

        // Delete the enclosing loop, leaving a bare `{.name}` — now too deep.
        sb.setLength(0)
        sb.append("{.name}")
        val after = SkyTemplateScopeAnalysisCache.get(sb)
        assertNotSame(before, after)
        assertEquals(1, after.size)
    }

    @Test fun sameIdentityMutableView_unchangedLength_stillCached() {
        SkyTemplateScopeAnalysisCache.clearForTest()
        val sb = StringBuilder("{.name}")
        val before = SkyTemplateScopeAnalysisCache.get(sb)
        val after = SkyTemplateScopeAnalysisCache.get(sb)
        assertSame(before, after)
    }

    @Test fun clearForTest_forcesRecompute() {
        val text = "{.name}"
        val first = SkyTemplateScopeAnalysisCache.get(text)
        SkyTemplateScopeAnalysisCache.clearForTest()
        val second = SkyTemplateScopeAnalysisCache.get(text)
        assertNotSame(first, second)
        assertEquals(first.size, second.size)
    }
}
