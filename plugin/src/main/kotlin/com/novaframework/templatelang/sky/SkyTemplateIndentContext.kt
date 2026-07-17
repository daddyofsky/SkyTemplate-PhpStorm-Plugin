package com.novaframework.templatelang.sky

/**
 * Embedded-code helper shared by [SkyTemplateEnterHandler] and the
 * line-indent provider: supplies the JS / CSS brace nesting that the
 * unified HTML+Sky line walk ([SkyTemplatePostFormatLogic]) cannot see.
 *
 * The opener-lift heuristic that used to live here (one-line-above HTML
 * tag check) was replaced by
 * [SkyTemplatePostFormatLogic.computeIndentForLine], which resolves the
 * nearest enclosing opener — HTML or template — at its actual indent.
 */
internal object SkyTemplateIndentContext {

    /**
     * Net unclosed `{` (minus `}`) inside the enclosing protected `<script>` /
     * `<style>` body, from the body start up to [offset], counting only real
     * JS / CSS braces — SkyTemplate tag ranges, string / template literals,
     * and `//` `/* */` comments are skipped. Returns 0 when [offset] is not
     * inside such a body, so callers outside embedded code get no brace
     * contribution.
     *
     * **Why.** The embedded JS / CSS formatter can't see SkyTemplate block
     * tags, so Enter indentation there has to combine the Sky/HTML depth
     * (`computeIndentForLine`) with the host language's own brace nesting —
     * this supplies the latter so a `function () {` body inside `{?var}`
     * indents one level past the Sky body depth.
     */
    fun embeddedBraceDepth(text: CharSequence, offset: Int): Int {
        val regions = SkyTemplateRangeCache.getProtectedEmbeddedRanges(text)
        val region = regions.firstOrNull { it.startOffset <= offset && offset <= it.endOffset }
            ?: return 0
        val skyRanges = SkyTemplateRangeCache.getIndentRanges(text)
        // skyRanges is sorted by startOffset (see computeIndentRanges), so a
        // single forward-advancing cursor finds the range containing `i` in
        // O(region + ranges) total instead of O(region * ranges) — the loop
        // below only ever moves `cursor` forward, never re-scans from 0.
        var cursor = 0
        var depth = 0
        var i = region.startOffset
        val end = offset.coerceAtMost(region.endOffset)
        var inString = ' '
        while (i < end) {
            // A SkyTemplate tag's `{` `}` are not JS / CSS braces — skip whole.
            while (cursor < skyRanges.size && skyRanges[cursor].endOffset <= i) cursor++
            val sky = if (cursor < skyRanges.size && skyRanges[cursor].startOffset <= i) skyRanges[cursor] else null
            if (sky != null) { i = sky.endOffset; continue }
            val c = text[i]
            if (inString != ' ') {
                if (c == '\\' && i + 1 < end) { i += 2; continue }
                if (c == inString) inString = ' '
                i++
                continue
            }
            when (c) {
                '\'', '"', '`' -> inString = c
                '/' -> when {
                    i + 1 < end && text[i + 1] == '/' -> { while (i < end && text[i] != '\n') i++ }
                    i + 1 < end && text[i + 1] == '*' -> {
                        i += 2
                        while (i + 1 < end && !(text[i] == '*' && text[i + 1] == '/')) i++
                        i += 2
                    }
                    else -> i++
                }
                '{' -> { depth++; i++ }
                '}' -> { if (depth > 0) depth--; i++ }
                else -> i++
            }
        }
        return depth
    }

    /**
     * [embeddedBraceDepth] counts the `{` still open BEFORE [lineStart],
     * which includes the brace that a `}`-first line at [lineStart] is
     * itself about to close — so a closer line reads one level too deep.
     * Callers combine this with [embeddedBraceDepth] to compensate:
     * `if (closesOwnBrace(text, lineStart)) depth - 1 else depth`.
     */
    fun startsWithCloseBrace(text: CharSequence, lineStart: Int): Boolean {
        var i = lineStart
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i < text.length && text[i] == '}'
    }

}
