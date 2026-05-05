package com.novaframework.templatelang.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [SkyTemplateFoldingScanner]. The IntelliJ folding
 * builder is a thin shim that wires this scanner's regions into the
 * platform — every interesting matching decision lives here.
 */
class SkyTemplateFoldingScannerTest {

    private fun scan(text: String) = SkyTemplateFoldingScanner.scan(text)

    @Test fun emptyText_yieldsNothing() {
        assertTrue(scan("").isEmpty())
    }

    @Test fun plainHtml_yieldsNothing() {
        assertTrue(scan("<p>hello</p>").isEmpty())
    }

    // ── Block tags ─────────────────────────────────────────────────────────

    @Test fun simpleLoopBlock_oneRegion() {
        val src = "{loop users as u}\n  {u.name}\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals(0, regions[0].range.startOffset)
        assertEquals(src.length, regions[0].range.endOffset)
        assertEquals("{loop users as u} … {/}", regions[0].placeholder)
    }

    @Test fun simpleIfBlock_oneRegion() {
        val src = "{if a > 0}\n  yes\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("{if a > 0} … {/}", regions[0].placeholder)
    }

    @Test fun foreachBlock_oneRegion() {
        val src = "{foreach items as it}\n  body\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("{foreach items as it} … {/}", regions[0].placeholder)
    }

    @Test fun nestedLoopAndIf_twoRegions() {
        val src = "{loop xs as x}\n  {if x.ok}\n    body\n  {/}\n{/}"
        val regions = scan(src)
        assertEquals(2, regions.size)
        // Outer loop wraps the entire span.
        assertEquals(0, regions[0].range.startOffset)
        assertEquals(src.length, regions[0].range.endOffset)
        // Inner if comes after, ends at the inner `{/}` (not the outer one).
        val inner = regions[1]
        assertTrue(inner.range.startOffset > regions[0].range.startOffset)
        assertTrue(inner.range.endOffset < regions[0].range.endOffset)
        assertEquals("{if x.ok} … {/}", inner.placeholder)
    }

    @Test fun unmatchedOpener_yieldsNothing() {
        val src = "{loop x}\n  body  (no closer)"
        assertTrue(scan(src).isEmpty())
    }

    @Test fun unmatchedCloser_yieldsNothing() {
        val src = "body\n{/}"
        assertTrue(scan(src).isEmpty())
    }

    @Test fun singleLineBlock_notFolded() {
        // Same line — nothing useful to collapse. We elide.
        val src = "{loop x}{name}{/}"
        assertTrue(scan(src).isEmpty())
    }

    @Test fun elseBranchDoesNotResetDepth() {
        // `{else}` should NOT pop — the surrounding `{if}` … `{/}` is still
        // one fold region.
        val src = "{if a}\n  yes\n{else}\n  no\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("{if a} … {/}", regions[0].placeholder)
    }

    @Test fun elseifBranchDoesNotResetDepth() {
        val src = "{if a}\n  one\n{elseif b}\n  two\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
    }

    @Test fun colonElsePrefix_isBranchNotOpener() {
        // `{:}` (else prefix) — branch within open block, no separate fold.
        val src = "{?a}\n  yes\n{:}\n  no\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("{?a} … {/}", regions[0].placeholder)
    }

    @Test fun questionColonSwitchCase_isBranch() {
        // `{?:case}` — Template_ switch-case branch. No new fold.
        val src = "{?expr}\n  default\n{?:caseA}\n  body\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
    }

    @Test fun endKeyword_actsAsCloser() {
        // Placeholder renders the closer verbatim so the user can tell which
        // closer form (`{/}` vs `{end}`) was used at a glance.
        val src = "{loop x}\n  body\n{end}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("{loop x} … {end}", regions[0].placeholder)
    }

    @Test fun whileBlock_isFolded() {
        val src = "{while cond}\n  body\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("{while cond} … {/}", regions[0].placeholder)
    }

    @Test fun forBlock_isFolded() {
        val src = "{for i = 0; i < 10; i++}\n  {i}\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertTrue(regions[0].placeholder.startsWith("{for "))
    }

    @Test fun prefixLoopForm_isFolded() {
        val src = "{@items}\n  body\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("{@items} … {/}", regions[0].placeholder)
    }

    @Test fun longHeader_isClipped() {
        val header = "{if " + "verylongvariable.with.lots.of.dots.and.qualifiers.and.more " + "== 1}"
        val src = "$header\n  body\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        // Placeholder should be bounded — capped at MAX_HEADER + " … {/}".
        assertTrue(regions[0].placeholder.endsWith(" … {/}"))
        assertTrue(regions[0].placeholder.length <= 100)
    }

    @Test fun rawOutputIsNotABlock() {
        // `{=foo()}` is not a block — no fold.
        val src = "{=foo()}\nbody"
        assertTrue(scan(src).isEmpty())
    }

    @Test fun bareVariableIsNotABlock() {
        val src = "{name}\nbody"
        assertTrue(scan(src).isEmpty())
    }

    @Test fun leadingWhitespaceBeforeKeyword_disqualifies() {
        // SkyTemplate forbids whitespace between `{` and a keyword. The
        // scanner should NOT pair `{ if x}` as a block.
        val src = "{ if x}\n  body\n{/}"
        // The `{ if x}` doesn't classify as BLOCK_OPEN. The lone `{/}` is a
        // BLOCK_CLOSE with nothing to pop, so the result is empty.
        assertTrue(scan(src).isEmpty())
    }

    // ── Comments ───────────────────────────────────────────────────────────

    @Test fun singleLineComment_notFolded() {
        assertTrue(scan("{* note *}").isEmpty())
    }

    @Test fun multiLineComment_isFolded() {
        val src = "{*\n  multi-line\n  comment\n*}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals(0, regions[0].range.startOffset)
        assertEquals(src.length, regions[0].range.endOffset)
        assertEquals("{*…*}", regions[0].placeholder)
    }

    @Test fun wrappedMultiLineComment_isFolded() {
        val src = "<!--{*\n  hidden\n*}-->"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("<!--{*…*}-->", regions[0].placeholder)
    }

    // ── Line comments inside block tags (user regression) ────────────────────

    /**
     * Regression for `{@list // 목록 루프} … {: // else} … {/// end}` —
     * line comments must not break block pairing. Before the fix, the closer
     * `{/// end}` (`/` + `// end`) was being filtered out by the line-comment
     * stripper in `looksLikeTemplateBody` and never reached the classifier,
     * leaving the open block unmatched.
     */
    @Test fun blockWithLineCommentsAtAllPositions_isFolded() {
        val src = "{@list // 목록 루프}\n  body\n{: // else}\n  alt\n{/// end}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals(0, regions[0].range.startOffset)
        assertEquals(src.length, regions[0].range.endOffset)
        assertEquals("{@list // 목록 루프} … {/// end}", regions[0].placeholder)
    }

    @Test fun closerWithSpacedLineComment_actsAsCloser() {
        // The placeholder collapses internal whitespace runs to a single space,
        // so `{/  // 닫기}` renders as `{/ // 닫기}`.
        val src = "{?cond}\n  body\n{/  // 닫기}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("{?cond} … {/ // 닫기}", regions[0].placeholder)
    }

    @Test fun closerWithoutSpaceBeforeComment_actsAsCloser() {
        val src = "{loop xs as x}\n  body\n{/// done}"
        val regions = scan(src)
        assertEquals(1, regions.size)
    }

    // ── HTML-wrapped block tags ────────────────────────────────────────────

    @Test fun wrappedAtPrefixBlock_isFolded() {
        // The user's exact case — `<!--{@ data}-->` … `<!--{/}-->`.
        val src = "<!--{@ data}-->\n  body\n<!--{/}-->"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals(0, regions[0].range.startOffset)
        assertEquals(src.length, regions[0].range.endOffset)
        assertEquals("<!--{@ data}--> … <!--{/}-->", regions[0].placeholder)
    }

    @Test fun wrappedKeywordBlock_isFolded() {
        val src = "<!--{loop xs as x}-->\n  {x.name}\n<!--{/}-->"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("<!--{loop xs as x}--> … <!--{/}-->", regions[0].placeholder)
    }

    @Test fun mixedPlainOpenerWithWrappedCloser_isFolded() {
        // SkyTemplate compiler treats wrapped and plain forms interchangeably.
        val src = "{loop xs as x}\n  body\n<!--{/}-->"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("{loop xs as x} … <!--{/}-->", regions[0].placeholder)
    }

    @Test fun mixedWrappedOpenerWithPlainCloser_isFolded() {
        val src = "<!--{loop xs as x}-->\n  body\n{/}"
        val regions = scan(src)
        assertEquals(1, regions.size)
        assertEquals("<!--{loop xs as x}--> … {/}", regions[0].placeholder)
    }

    @Test fun nestedWrappedBlocks_yieldTwoRegions() {
        val src = "<!--{loop xs as x}-->\n  <!--{if x.ok}-->\n    body\n  <!--{/}-->\n<!--{/}-->"
        val regions = scan(src)
        assertEquals(2, regions.size)
    }

    @Test fun multipleCommentsBlocksAndContent_orderedByStart() {
        val src = """
            {*
              header note
            *}
            {loop xs as x}
              {x.name}
            {/}
            text
            {*
              footer note
            *}
        """.trimIndent()
        val regions = scan(src)
        assertEquals(3, regions.size)
        // Sorted by start offset.
        assertTrue(regions[0].range.startOffset < regions[1].range.startOffset)
        assertTrue(regions[1].range.startOffset < regions[2].range.startOffset)
    }
}
