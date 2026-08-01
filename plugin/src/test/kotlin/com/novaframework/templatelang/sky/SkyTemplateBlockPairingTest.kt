package com.novaframework.templatelang.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [SkyTemplateFoldingScanner.analyze] — specifically the
 * diagnostic fields (`unpairedOpens`, `orphanBranches`) consumed by the M7
 * inspections (`SkyTemplateUnclosedBlockInspection`,
 * `SkyTemplateOrphanElseInspection`).
 *
 * Fold-region behaviour is covered by [SkyTemplateFoldingScannerTest]; this
 * file focuses on cases where the structure is incorrect so we can drive
 * the inspections with confidence.
 */
class SkyTemplateBlockPairingTest {

    private fun analyze(text: String) = SkyTemplateFoldingScanner.analyze(text)

    // ── unpaired opens ────────────────────────────────────────────────────────

    @Test fun unclosedLoopProducesOneUnpairedOpen() {
        val r = analyze("{loop xs as x}\n  body\n")
        assertEquals(1, r.unpairedOpens.size)
        assertEquals("{loop xs as x}", r.unpairedOpens[0].openText)
    }

    @Test fun unclosedIfProducesOneUnpairedOpen() {
        val r = analyze("{if cond}\n  body\n")
        assertEquals(1, r.unpairedOpens.size)
        assertEquals("{if cond}", r.unpairedOpens[0].openText)
    }

    @Test fun unclosedPrefixIfProducesOneUnpairedOpen() {
        val r = analyze("{?cond}\n  body\n")
        assertEquals(1, r.unpairedOpens.size)
        assertTrue(r.unpairedOpens[0].openText.startsWith("{?"))
    }

    @Test fun unclosedForeachProducesOneUnpairedOpen() {
        val r = analyze("{foreach items as it}\n  body\n")
        assertEquals(1, r.unpairedOpens.size)
    }

    @Test fun unclosedAtLoopProducesOneUnpairedOpen() {
        val r = analyze("{@items}\n  body\n")
        assertEquals(1, r.unpairedOpens.size)
    }

    @Test fun unclosedPercentLoopProducesOneUnpairedOpen() {
        val r = analyze("{%items}\n  body\n")
        assertEquals(1, r.unpairedOpens.size)
    }

    @Test fun nestedUnclosedReportsOuterOnly() {
        // Outer loop never closes; inner loop pairs cleanly. Stack: [outer].
        // Closer pops inner. Outer remains.
        val r = analyze("{loop xs as x}{loop ys as y}{/}\n  body\n")
        assertEquals(1, r.unpairedOpens.size)
        assertTrue("expected outer loop in unpaired list",
            r.unpairedOpens[0].openText.contains("xs as x"))
    }

    @Test fun nestedInnerUnclosedReportedNotOuter() {
        // The single `{/}` sits at indent 0 — same column as `{@products}`
        // — so it can only logically close the outer block. The inner
        // `{?.name}` at deeper indent must be the forgotten close.
        // Pure stack semantics would have wrongly flagged the OUTER
        // (since LIFO pops the inner for the only `{/}`); indent-aware
        // pairing pops the deeper opener as unpaired and lets the
        // closer match the outer.
        val text = "{@products}\n<li>\n\t{?.name}\n\t{.name}\n</li>\n{/}\n"
        val r = analyze(text)
        assertEquals(1, r.unpairedOpens.size)
        assertEquals("{?.name}", r.unpairedOpens[0].openText)
    }

    @Test fun multipleInnerUnclosedAtSameIndentReported() {
        // Two openers at the same deeper indent share a single outer
        // closer. The earlier inner is unpaired; the second matches LIFO
        // when indents are equal — so only one inner gets flagged.
        val text = "{@a}\n  {@b}\n  {@c}\n  {/}\n{/}\n"
        val r = analyze(text)
        assertEquals(1, r.unpairedOpens.size)
        assertEquals("{@b}", r.unpairedOpens[0].openText)
    }

    @Test fun closerAtSameIndentAsOpenerStillMatchesLifo() {
        // Pure inline / single-line nesting: every tag at indent 0,
        // both `{/}` close in LIFO order. No false positives.
        val r = analyze("{loop xs}{loop ys}{/}{/}")
        assertEquals(0, r.unpairedOpens.size)
    }

    @Test fun pairedLoopProducesNoUnpairedOpen() {
        val r = analyze("{loop xs as x}\n  body\n{/}")
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
    }

    @Test fun pairedWithEndKeywordProducesNoUnpairedOpen() {
        val r = analyze("{loop xs as x}\n  body\n{end}")
        assertEquals(0, r.unpairedOpens.size)
    }

    @Test fun deeplyNestedAllPairedProducesNoUnpairedOpen() {
        val r = analyze("{loop xs}{if y}{foreach a as b}{/}{/}{/}")
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
    }

    @Test fun unclosedInsideCommentNotReported() {
        // The bogus `{loop x}` lives inside a `{*…*}` comment — must be
        // ignored by analyze().
        val r = analyze("{* {loop x} *}body")
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
    }

    @Test fun wrappedDirectivesInsideWrappedCommentDoNotBreakPairing() {
        // `<!--{? foo}-->` / `<!--{/}-->` INSIDE a `<!--{* … *}-->` comment
        // are inert. The inner `<!--{/}-->` must not pop the live outer
        // `{?}` frame, and the `{:}` / `{/}` after the comment must pair
        // with it cleanly.
        val text = """<!--{? outer}-->
  <!--{*
  <!--{? foo}-->
  x
  <!--{/}-->
  *}-->
  <!--{:}-->
  y
<!--{/}-->"""
        val r = analyze(text)
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
    }

    @Test fun plainDirectivesInsidePlainCommentDoNotBreakPairing() {
        val text = "{?outer}\n{*\n{?foo}\nx\n{/}\n*}\n{:}\ny\n{/}"
        val r = analyze(text)
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
    }

    @Test fun htmlWrappedUnpairedReportsOpen() {
        // `<!--{loop x}-->` with no closer.
        val r = analyze("<!--{loop x}-->\nbody\n")
        assertEquals(1, r.unpairedOpens.size)
    }

    @Test fun htmlWrappedPairedProducesNoUnpairedOpen() {
        val r = analyze("<!--{loop x}-->\nbody\n<!--{/}-->")
        assertEquals(0, r.unpairedOpens.size)
    }

    @Test fun emptyTextProducesNothing() {
        val r = analyze("")
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
        assertEquals(0, r.foldRegions.size)
    }

    @Test fun textWithoutBracesProducesNothing() {
        val r = analyze("plain text with no braces here")
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
    }

    // ── orphan branches ───────────────────────────────────────────────────────

    @Test fun orphanElseAtTopLevelReported() {
        val r = analyze("{else}fallback{/}")
        assertEquals(1, r.orphanBranches.size)
        assertEquals("else", r.orphanBranches[0].keyword)
    }

    @Test fun orphanColonElseAtTopLevelReported() {
        val r = analyze("{:}fallback")
        assertEquals(1, r.orphanBranches.size)
        assertEquals(":", r.orphanBranches[0].keyword)
    }

    @Test fun orphanColonExprReported() {
        // `{:case}` outside any open block is still an orphan branch.
        val r = analyze("{:case1}foo")
        assertEquals(1, r.orphanBranches.size)
        assertEquals(":", r.orphanBranches[0].keyword)
    }

    @Test fun orphanElseifReported() {
        val r = analyze("{elseif cond}body")
        assertEquals(1, r.orphanBranches.size)
        assertEquals("elseif", r.orphanBranches[0].keyword)
    }

    @Test fun elseInsideIfNotReported() {
        val r = analyze("{if x}\n  a\n{else}\n  b\n{/}")
        assertEquals(0, r.orphanBranches.size)
    }

    @Test fun elseInsideLoopNotReported() {
        // SkyTemplate allows branches inside any open block — be permissive.
        val r = analyze("{loop xs as x}\n  a\n{else}\n  b\n{/}")
        assertEquals(0, r.orphanBranches.size)
    }

    @Test fun elseAfterClosedIfReported() {
        // The `{if x}{/}` pair closes; the trailing `{else}` is orphan.
        val r = analyze("{if x}{/}{else}body")
        assertEquals(1, r.orphanBranches.size)
    }

    @Test fun multipleOrphansReported() {
        val r = analyze("{else}a{:}b")
        assertEquals(2, r.orphanBranches.size)
    }

    @Test fun elvisStandaloneIsUnpairedOpen() {
        // `{?:expr}` is SkyTemplate's "elvis" fallback. Per compiler's
        // tagElvis: emits `if ($e=expr) { echo $e; } else {` and pushes
        // `'if'` onto arrBlock — i.e. it opens a block that MUST be
        // closed by `{/}` (same as `{if expr}`). Standalone form is
        // therefore an unclosed block, not a branch.
        val r = analyze("{?:fallbackValue}")
        assertEquals(1, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
        assertEquals("{?:fallbackValue}", r.unpairedOpens[0].openText)
    }

    @Test fun elvisProperlyClosedProducesNoErrors() {
        // `{?:val}fallback{/}` — output `val` if truthy, else the body.
        // Properly closed; no diagnostics.
        val r = analyze("{?:val}fallback{/}")
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
    }

    @Test fun elvisInsideOuterBlockClosesCorrectly() {
        // Nesting sanity: outer `{loop}` and inner `{?:e}fb{/}` both
        // pair correctly.
        val r = analyze("{loop xs as x}{?:.name}guest{/}{/}")
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
    }

    // ── orphan close (intentionally NOT reported) ─────────────────────────────

    @Test fun orphanCloseIsNotReported() {
        // `{/}` with no opener is intentionally silent — partial fragments
        // legitimately end with a closer whose opener is in another file.
        val r = analyze("{/}")
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
    }

    @Test fun orphanEndKeywordIsNotReported() {
        val r = analyze("{end}")
        assertEquals(0, r.unpairedOpens.size)
        assertEquals(0, r.orphanBranches.size)
    }
}
