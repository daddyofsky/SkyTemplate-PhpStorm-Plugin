package com.novaframework.templatelang.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [SkyTemplateRanges.computeIndentRanges] — P2-1
 * (v1.2.4 wrapped-comment shell-expansion broke the `{*…*}` body-bounds
 * assumption baked into the indent walker).
 */
class SkyTemplateIndentRangesTest {

    private fun indentRanges(text: String) = SkyTemplateRanges.computeIndentRanges(text)

    @Test fun wrappedCommentInnerBlockTagsAreCollected() {
        val text = "<!--{*\n{loop items}\n<li>x</li>\n{/}\n*}-->"
        val r = indentRanges(text)
        assertTrue(
            "expected {loop items} in $r",
            r.any { text.substring(it.startOffset, it.endOffset) == "{loop items}" },
        )
        assertTrue(
            "expected {/} in $r",
            r.any { text.substring(it.startOffset, it.endOffset) == "{/}" },
        )
    }

    @Test fun plainCommentInnerBlockTagsStillCollected() {
        // Baseline (pre-existing) behaviour: unwrapped `{*…*}` comment body.
        val text = "{*\n{loop items}\n{/}\n*}"
        val r = indentRanges(text)
        assertTrue(r.any { text.substring(it.startOffset, it.endOffset) == "{loop items}" })
        assertTrue(r.any { text.substring(it.startOffset, it.endOffset) == "{/}" })
    }

    @Test fun wrappedCommentWithNestedCommentStillCollectsOuterBlockTags() {
        val text = "<!--{* {* n *} {loop x}{/} *}-->"
        val r = indentRanges(text)
        assertTrue(r.any { text.substring(it.startOffset, it.endOffset) == "{loop x}" })
        assertTrue(r.any { text.substring(it.startOffset, it.endOffset) == "{/}" })
    }

    @Test fun noCommentsFallsBackToTemplateRanges() {
        val text = "{loop x}\nbody\n{/}"
        assertEquals(SkyTemplateRanges.computeTemplateRanges(text), indentRanges(text))
    }
}
