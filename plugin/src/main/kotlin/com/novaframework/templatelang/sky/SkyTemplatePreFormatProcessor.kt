package com.novaframework.templatelang.sky

import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.impl.source.codeStyle.PreFormatProcessor
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Pre-format step that snapshots every SkyTemplate tag inside the range
 * about to be reformatted, so [SkyTemplatePostFormatProcessor] can put a
 * mangled tag back together afterwards.
 *
 * **Why this exists.** In `*.html` host files the `<script>` body is
 * lexer-EMBEDDED JavaScript (and `<style>` is embedded CSS), not language
 * injection, so the JS / CSS formatter owns those regions and treats a Sky
 * tag like `{=json_encode(data)}` as code: it reads the `{` as an
 * object-literal / block open and splits the tag across lines
 * (`{ = json_encode(data)\n}\n;`). A spike proved injection can't own the
 * `<script>` body (it isn't a `PsiLanguageInjectionHost`), so the only
 * contained fix is to protect each tag across the host formatter and
 * restore it after — see `docs/design/spike-verdict-script-injection.md`.
 *
 * **How it works.** Two snapshot scopes, both restored the same way by the
 * post pass (replace the marker's current text with the original):
 *
 *   1. **Whole `<script>` / `<style>` body** when it carries a Sky tag — see
 *      [SkyTemplateRanges.computeProtectedEmbeddedRanges]. The formatter
 *      mangles the whitespace BETWEEN tags too (splits a one-line
 *      `const a = {=foo};`, breaks an inline `{?var}true{:}false{/}` over
 *      several lines, inserts blank lines, mis-indents), which a per-tag
 *      snapshot can't reach; restoring the body verbatim does. Markers are
 *      greedy so edge whitespace is captured.
 *   2. **Each Sky tag OUTSIDE those bodies** (HTML-context tags). The
 *      formatter only ever inserts / removes WHITESPACE inside such a tag and
 *      never deletes its non-whitespace characters, so a [RangeMarker] over
 *      `[start, end)` still spans the (mangled) tag afterwards. These aren't
 *      mangled in practice — the snapshot is the prior safety net, kept intact.
 *
 * `(marker, originalText)` pairs go into Document user-data; the post-format
 * pass restores each, then runs its existing block re-indent on top.
 *
 * Scope: gated on [TemplateLangFileFilter] like the rest of the plugin.
 */
class SkyTemplatePreFormatProcessor : PreFormatProcessor {

    override fun process(element: ASTNode, range: TextRange): TextRange {
        val psiFile = element.psi?.containingFile ?: return range
        if (!TemplateLangFileFilter.shouldProcess(psiFile)) return range
        val document = psiFile.viewProvider.document ?: return range

        // A prior snapshot with no matching post pass (re-entrant format,
        // cancelled run) would otherwise leak markers and shadow this one.
        document.getUserData(SNAPSHOT_KEY)?.let { stale ->
            stale.forEach { it.first.dispose() }
            document.putUserData(SNAPSHOT_KEY, null)
        }

        val text = document.charsSequence
        val snapshot = ArrayList<Pair<RangeMarker, String>>()

        // 1. Whole `<script>` / `<style>` bodies that carry Sky tags. The
        //    JS / CSS formatter mangles the whitespace BETWEEN tags (splits a
        //    one-line `const a = {=foo};`, breaks an inline `{?var}…{/}` over
        //    several lines, inserts blank lines, mis-indents) — damage a
        //    per-tag snapshot can't reach. Snapshot the body verbatim and the
        //    post pass puts it back. Markers are greedy so whitespace the
        //    formatter inserts at the body's very edges (right after `>` /
        //    before `</…>`) is captured too.
        val protectedRegions = SkyTemplateRanges.computeProtectedEmbeddedRanges(text)
        for (region in protectedRegions) {
            if (region.startOffset >= range.endOffset || region.endOffset <= range.startOffset) continue
            val original = document.getText(region)
            val marker = document.createRangeMarker(region.startOffset, region.endOffset)
            marker.isGreedyToLeft = true
            marker.isGreedyToRight = true
            snapshot += marker to original
        }

        // 2. Sky tags OUTSIDE those protected bodies (HTML-context tags). Not
        //    mangled in practice, so this is the prior per-tag safety net for
        //    everything but script / style — left intact to preserve behaviour.
        for (tag in SkyTemplateRanges.computeTemplateRanges(text)) {
            if (tag.startOffset >= range.endOffset || tag.endOffset <= range.startOffset) continue
            if (SkyTemplateRanges.anyOverlap(protectedRegions, tag.startOffset, tag.endOffset)) continue
            val original = document.getText(TextRange(tag.startOffset, tag.endOffset))
            snapshot += document.createRangeMarker(tag.startOffset, tag.endOffset) to original
        }

        if (snapshot.isNotEmpty()) document.putUserData(SNAPSHOT_KEY, snapshot)

        return range
    }

    companion object {
        /** Tag snapshots handed from the pre-format pass to the post-format pass. */
        val SNAPSHOT_KEY: Key<List<Pair<RangeMarker, String>>> =
            Key.create("SkyTemplate.preFormatTagSnapshot")
    }
}
