package com.novaframework.templatelang.sky

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [SkyTemplateIndentContext.embeddedBraceDepth] and
 * [SkyTemplateIndentContext.startsWithCloseBrace] — the callers
 * ([SkyTemplateEnterHandler], [SkyTemplateLineIndentProvider]) combine both
 * to compensate for a `}`-only line counting its own closing brace as
 * still-open depth.
 */
class SkyTemplateIndentContextLogicTest {

    /** Depth callers actually apply: raw depth, minus one when the line self-closes. */
    private fun compensatedDepth(text: CharSequence, lineStart: Int): Int {
        val raw = SkyTemplateIndentContext.embeddedBraceDepth(text, lineStart)
        return if (SkyTemplateIndentContext.startsWithCloseBrace(text, lineStart)) {
            (raw - 1).coerceAtLeast(0)
        } else {
            raw
        }
    }

    @Test fun selfClosingLine_rawDepthCountsItsOwnBrace() {
        // P-BUG-03: `embeddedBraceDepth` alone counts the still-open `{`
        // from `function test() {` even when the queried line's own first
        // char is the `}` that closes it — one level too deep. A Sky tag
        // must be present inside the `<script>` body for the region to be
        // recognised as "protected embedded code" at all.
        val text = "<script>\n{?var}\nfunction test() {\n}\n{/}\n</script>"
        val closeLineStart = text.indexOf("}\n{/}")
        assertEquals(1, SkyTemplateIndentContext.embeddedBraceDepth(text, closeLineStart))
    }

    @Test fun selfClosingLine_compensatedDepthIsZero() {
        val text = "<script>\n{?var}\nfunction test() {\n}\n{/}\n</script>"
        val closeLineStart = text.indexOf("}\n{/}")
        assertEquals(0, compensatedDepth(text, closeLineStart))
    }

    @Test fun bodyLineInsideBrace_depthUnaffectedByCompensation() {
        // A body line (not itself a closer) must NOT be compensated —
        // only lines whose first non-whitespace char is `}`.
        val text = "<script>\n{?var}\nfunction test() {\nbody\n}\n{/}\n</script>"
        val bodyLineStart = text.indexOf("body")
        assertEquals(1, SkyTemplateIndentContext.embeddedBraceDepth(text, bodyLineStart))
        assertEquals(1, compensatedDepth(text, bodyLineStart))
    }

    @Test fun nestedSelfClosingLine_compensatedDepthKeepsOuterLevel() {
        // Two nested JS braces; the inner `}` line must compensate down
        // to the outer brace's depth (1), not the raw count (2).
        val text = "<script>\n{?var}\nfunction f() {\n  if (x) {\n  }\n}\n{/}\n</script>"
        val innerCloseStart = text.indexOf("  }\n}")
        assertEquals(2, SkyTemplateIndentContext.embeddedBraceDepth(text, innerCloseStart))
        assertEquals(1, compensatedDepth(text, innerCloseStart))
    }

    @Test fun startsWithCloseBrace_indentedClosingBraceLine_isTrue() {
        val text = "        }\n"
        assertEquals(true, SkyTemplateIndentContext.startsWithCloseBrace(text, 0))
    }

    @Test fun startsWithCloseBrace_bodyLine_isFalse() {
        val text = "        body;\n"
        assertEquals(false, SkyTemplateIndentContext.startsWithCloseBrace(text, 0))
    }

    @Test fun outsideEmbeddedRegion_depthIsZeroRegardlessOfBraceText() {
        val text = "<p>plain text { not js }</p>"
        assertEquals(0, SkyTemplateIndentContext.embeddedBraceDepth(text, 5))
    }
}
