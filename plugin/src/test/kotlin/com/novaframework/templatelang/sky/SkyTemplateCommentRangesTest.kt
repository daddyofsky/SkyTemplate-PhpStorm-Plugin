package com.novaframework.templatelang.sky

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the file-level comment range detection (the recipe consumed by
 * [SkyTemplateAnnotator]'s file pass and by [SkyTemplateHtmlErrorFilter]).
 *
 * Covers:
 *   - plain `{* … *}` single-line
 *   - plain `{* … *}` multi-line
 *   - HTML-wrapped `<!--{* … *}-->` including the user-reported bug where an
 *     inner `<!--…-->` HTML comment must not break the wrap detection
 *   - non-wrapped HTML comment containing template comment (no wrap)
 */
class SkyTemplateCommentRangesTest {

    private fun comments(text: String) = SkyTemplateRanges.computeCommentRanges(text)

    @Test fun plainSingleLine() {
        val text = "before {* hi *} after"
        val r = comments(text)
        assertEquals(1, r.size)
        assertEquals("{* hi *}", text.substring(r[0].startOffset, r[0].endOffset))
    }

    @Test fun plainMultiLineSpansAcrossNewlines() {
        val text = "<div>\n  {* line1\n     line2\n     line3 *}\n</div>"
        val r = comments(text)
        assertEquals(1, r.size)
        val sub = text.substring(r[0].startOffset, r[0].endOffset)
        assertTrue(sub.startsWith("{*"))
        assertTrue(sub.endsWith("*}"))
        assertTrue("line1" in sub && "line3" in sub)
    }

    @Test fun plainCommentWithEmbeddedHtmlGetsCoveredAsOneRange() {
        // The user's stated requirement (without HTML wrapper) — a multi-line
        // comment whose body contains HTML must still be one comment range so
        // we can paint it uniformly.
        val text = """
            <div>
            {*
            <ul>
              <li>{title}</li>
              <!--<li>{hit}</li>-->
            </ul>
            *}
            </div>
        """.trimIndent()
        val r = comments(text)
        assertEquals(1, r.size)
        val sub = text.substring(r[0].startOffset, r[0].endOffset)
        assertTrue(sub.startsWith("{*"))
        assertTrue(sub.endsWith("*}"))
        assertTrue("<ul>" in sub)
        assertTrue("<!--<li>" in sub)
    }

    @Test fun htmlWrappedFormCoversOuterMarkers() {
        // The exact case the user reported — outer `<!--{*` … `*}-->` must
        // be a single range covering the OUTER `<!--` and `-->` markers too.
        val text = """
            <!--{*
            <ul>
            	<li>{title}</li>
            	<li>{name}</li>
            	<!--<li>{hit}</li>-->
            </ul>
            *}-->
        """.trimIndent()
        val r = comments(text)
        assertEquals(1, r.size)
        val sub = text.substring(r[0].startOffset, r[0].endOffset)
        assertTrue(sub.startsWith("<!--{*"))
        assertTrue(sub.endsWith("*}-->"))
        // Inner <!--<li>…</li>--> must be wholly inside.
        assertTrue("<!--<li>{hit}</li>-->" in sub)
    }

    @Test fun nonWrappedHtmlCommentDoesNotMergeIntoWrapped() {
        // `<!-- {* foo *} -->` (with whitespace) is a regular HTML comment
        // containing a template comment. The wrap regex must NOT catch the
        // OUTER `<!--` `-->` since there's whitespace before `{*`.
        val text = "<!-- {* foo *} -->"
        val r = comments(text)
        assertEquals(1, r.size)
        // Just `{* foo *}` — outer HTML comment markers stay HTML-coloured.
        val sub = text.substring(r[0].startOffset, r[0].endOffset)
        assertEquals("{* foo *}", sub)
    }

    @Test fun multipleWrappedAndPlainComments() {
        val text = "<!--{* a *}--> mid {* b *} end <!--{* c *}-->"
        val r = comments(text)
        assertEquals(3, r.size)
        assertEquals("<!--{* a *}-->", text.substring(r[0].startOffset, r[0].endOffset))
        assertEquals("{* b *}",         text.substring(r[1].startOffset, r[1].endOffset))
        assertEquals("<!--{* c *}-->",  text.substring(r[2].startOffset, r[2].endOffset))
    }

    @Test fun unterminatedPlainCommentBecomesBestEffortRange() {
        val text = "<div>{* never closed</div>"
        val r = comments(text)
        assertEquals(1, r.size)
        assertEquals(text.indexOf("{*"), r[0].startOffset)
        assertEquals(text.length, r[0].endOffset)
    }

    @Test fun fileWithoutCommentMarkersProducesNothing() {
        assertTrue(comments("<p>just html</p>").isEmpty())
        assertTrue(comments("{var}{loop x}{/}").isEmpty())  // tags but no comments
        assertTrue(comments("").isEmpty())
    }

    @Test fun computeTemplateRangesIncludesWrappedDirectivesNotJustComments() {
        // M6.2 filter feeds on this — non-comment wrapped tags should also count.
        val text = "<!--{var}--> hi <!--{loop x}--> body <!--{/}-->"
        val r = SkyTemplateRanges.computeTemplateRanges(text)
        // 3 wrapped ranges, no plain ones.
        assertTrue(r.size >= 3)
        assertTrue(r.any { text.substring(it.startOffset, it.endOffset) == "<!--{var}-->" })
        assertTrue(r.any { text.substring(it.startOffset, it.endOffset) == "<!--{loop x}-->" })
        assertTrue(r.any { text.substring(it.startOffset, it.endOffset) == "<!--{/}-->" })
    }

    @Test fun anyOverlapWorksUnsorted() {
        val rs = listOf(TextRange(50, 60), TextRange(10, 20), TextRange(30, 40))
        // anyOverlap must not assume sorted ordering.
        assertTrue(SkyTemplateRanges.anyOverlap(rs, 35, 38))
        assertTrue(SkyTemplateRanges.anyOverlap(rs, 55, 70))
        assertFalse(SkyTemplateRanges.anyOverlap(rs, 0, 10))
        assertFalse(SkyTemplateRanges.anyOverlap(rs, 60, 70))
    }

    /**
     * The exact user-reported scenario: when the wrap contains an inner HTML
     * comment, simulated HTML parser errors inside that wrap must overlap our
     * template range so [SkyTemplateHtmlErrorFilter] drops them.
     */
    @Test fun userExampleWrappedComment_innerHtmlErrorsAllOverlapTheTemplateRange() {
        val text = """<!--{*
<ul>
	<li>{title}</li>
	<li>{name}</li>
	<!--<li>{hit}</li>-->
</ul>
*}-->"""
        val ranges = SkyTemplateRanges.computeTemplateRanges(text)

        // Exactly one wrapped range — covers the whole block.
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].startOffset)
        assertEquals(text.length, ranges[0].endOffset)

        // Pretend HTML parser fires an error on the inner `</ul>` (it appears
        // unmatched once the inner `-->` prematurely closes the outer comment).
        val ulCloseAt = text.indexOf("</ul>")
        assertTrue(SkyTemplateRanges.anyOverlap(ranges, ulCloseAt, ulCloseAt + 5))

        // The inner `-->` itself.
        val innerArrowAt = text.indexOf("</li>-->") + "</li>".length
        assertTrue(SkyTemplateRanges.anyOverlap(ranges, innerArrowAt, innerArrowAt + 3))

        // The closing `*}-->` outer marker.
        val outerCloseAt = text.lastIndexOf("*}-->")
        assertTrue(SkyTemplateRanges.anyOverlap(ranges, outerCloseAt, outerCloseAt + 5))
    }
}
