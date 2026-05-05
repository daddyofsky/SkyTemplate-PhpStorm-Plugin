package com.novaframework.templatelang.sky

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * IDE-level coverage for [SkyTemplateFoldingBuilder]. We don't use
 * `myFixture.testFolding(...)` because that helper expects a fixture file
 * with `<fold>` markers and matches against `getPlaceholderText` exactly —
 * easy to drift from when placeholder formatting changes. Instead, we
 * compare the folding model's region count and ranges directly.
 */
class SkyTemplateFoldingIntegrationTest : BasePlatformTestCase() {

    /** Configure a `*.sky` file and return the fold-region descriptors. */
    private fun foldRegions(text: String): List<Pair<Int, Int>> {
        myFixture.configureByText("a.sky", text)
        // Force highlighting so the folding builder runs.
        myFixture.doHighlighting()
        val model = myFixture.editor.foldingModel
        return model.allFoldRegions.map { it.startOffset to it.endOffset }
    }

    fun testLoopBlockProducesFoldRegion() {
        val src = "{loop xs as x}\n  {x.name}\n{/}"
        val regions = foldRegions(src)
        assertEquals(1, regions.size)
        assertEquals(0, regions[0].first)
        assertEquals(src.length, regions[0].second)
    }

    fun testNestedBlocksProduceTwoRegions() {
        val src = "{loop xs as x}\n  {if x.ok}\n    body\n  {/}\n{/}"
        val regions = foldRegions(src)
        assertEquals(2, regions.size)
    }

    fun testSingleLineCommentNotFolded() {
        val regions = foldRegions("{* note *}")
        assertEquals(0, regions.size)
    }

    fun testMultiLineCommentFolded() {
        val src = "{*\n  note\n  more\n*}"
        val regions = foldRegions(src)
        assertEquals(1, regions.size)
        assertEquals(0, regions[0].first)
        assertEquals(src.length, regions[0].second)
    }

    fun testRawOutputNotFolded() {
        val regions = foldRegions("{=foo()}\nbody\n{=bar()}\n")
        assertEquals(0, regions.size)
    }

    fun testUnmatchedOpenerNotFolded() {
        val regions = foldRegions("{loop x}\n  body  (no closer)")
        assertEquals(0, regions.size)
    }

    fun testEndKeywordCloses() {
        val src = "{loop x}\n  body\n{end}"
        val regions = foldRegions(src)
        assertEquals(1, regions.size)
    }

    fun testElseBranchKeepsSingleRegion() {
        val src = "{if a}\n  yes\n{else}\n  no\n{/}"
        val regions = foldRegions(src)
        assertEquals(1, regions.size)
    }

    /** `@` prefix block (Template_ shorthand foreach) must fold like the keyword form. */
    fun testAtPrefixBlockFolds() {
        val src = "{@items}\n  body\n{/}"
        val regions = foldRegions(src)
        assertEquals(1, regions.size)
        assertEquals(0, regions[0].first)
        assertEquals(src.length, regions[0].second)
    }

    /** `?` prefix block (if shorthand) folds. */
    fun testQuestionPrefixBlockFolds() {
        val src = "{?cond}\n  body\n{/}"
        val regions = foldRegions(src)
        assertEquals(1, regions.size)
    }

    /**
     * `.html` host files. Folding builder is registered for HTML language
     * too — the same scanner logic over the document text produces fold
     * regions even though the file is parsed as HTML, not SkyTemplate.
     */
    fun testHtmlHostFileFolds() {
        myFixture.configureByText(
            "page.html",
            "<p>{if user}\n  hi\n{/}</p>",
        )
        myFixture.doHighlighting()
        val regions = myFixture.editor.foldingModel.allFoldRegions
            .map { it.startOffset to it.endOffset }
        // The `{if user} … {/}` span — three lines — must produce one region
        // even though the file's primary language is HTML.
        val ourRegions = regions.filter {
            val text = myFixture.file.text.substring(it.first, it.second)
            text.startsWith("{if") && text.endsWith("{/}")
        }
        assertEquals(1, ourRegions.size)
    }

    /**
     * Nested folds must be independent — collapsing the outer `{loop}` should
     * not be tied to the inner `{if}`. We verify this by checking each region
     * has a `null` group (or distinct groups). Same-group regions would fold
     * together, which is wrong for unrelated nested blocks.
     */
    /** User-reported regression: `<!--{@ data}-->` … `<!--{/}-->` block must fold. */
    fun testWrappedAtPrefixBlockFoldsInHtml() {
        myFixture.configureByText(
            "page.html",
            "<div>\n<!--{@ data}-->\n  body\n<!--{/}-->\n</div>",
        )
        myFixture.doHighlighting()
        val regions = myFixture.editor.foldingModel.allFoldRegions
            .map { it.startOffset to it.endOffset }
        val ourRegions = regions.filter {
            val text = myFixture.file.text.substring(it.first, it.second)
            text.startsWith("<!--{@") && text.endsWith("<!--{/}-->")
        }
        assertEquals(1, ourRegions.size)
    }

    fun testNestedFoldsAreIndependent() {
        val src = "{loop xs as x}\n  {if x.ok}\n    body\n  {/}\n{/}"
        myFixture.configureByText("a.sky", src)
        myFixture.doHighlighting()
        val regions = myFixture.editor.foldingModel.allFoldRegions
        assertEquals(2, regions.size)
        // No two regions share a non-null group identifier.
        val groups = regions.mapNotNull { it.group }
        assertTrue(
            "nested fold regions must not share a FoldingGroup, got groups=$groups",
            groups.distinct().size == groups.size,
        )
    }
}
