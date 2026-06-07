package com.novaframework.templatelang.sky

/**
 * Heuristic helpers used by both [SkyTemplateEnterHandler] and the
 * [SkyTemplatePostFormatProcessor] to keep a SkyTemplate top-level block
 * properly nested under its enclosing HTML element.
 *
 * The HTML formatter does not understand `{loop …}` / `{?…}` / `{/}`
 * directives — when Reformat Code runs in an `*.html` host it tends to
 * strip those lines back to column 0 even when the user originally wrote
 * them indented inside a `<div>` / `<ul>` / etc. Without compensation,
 * the user's intent is lost and Enter (which uses the line's *current*
 * indent) produces a deeper layout than Reformat does — the
 * "엔터/Reformat depth 가 다름" bug.
 *
 * The helpers here look just one line above the opener: if that line
 * ends with an opening HTML tag (not closing, not self-closing), the
 * SkyTemplate opener is treated as that tag's first child and lifted
 * to `<parent.indent> + step`. The check is intentionally cheap and
 * heuristic — a real HTML PSI walk would be more accurate but only
 * available to in-IDE call sites; the post-format processor and the
 * pure Enter logic share this code path so they stay in lockstep.
 */
internal object SkyTemplateIndentContext {

    /**
     * If the opener at [openerOffset] sits on its own line as the first
     * non-whitespace content of that line, AND the most recent non-blank
     * line above ends with an opening HTML tag, return that line's
     * indent + [indentStep]. Else return null (no lift applies and the
     * caller should keep the opener's raw indent).
     *
     * "Opening HTML tag" means a tag whose body starts with `<word`
     * (letter), not `</…` (closer) and not `…/>` (self-closing). This
     * covers `<div>`, `<ul>`, `<li class="x">`, `<section data-y='z'>`,
     * but excludes `<br/>`, `</div>`, comments, and DOCTYPE declarations.
     */
    fun expectedHtmlChildIndent(
        text: CharSequence,
        openerOffset: Int,
        indentStep: String,
    ): String? {
        if (openerOffset <= 0 || openerOffset > text.length) return null
        val lineStart = lineStartOf(text, openerOffset)

        // The opener must be the first non-whitespace on its line — inline
        // forms like `<div>{?cond}` are not "first child of an HTML tag"
        // in the structural sense and lifting them would over-indent.
        for (i in lineStart until openerOffset) {
            val c = text[i]
            if (c != ' ' && c != '\t') return null
        }

        // Walk up over blank lines to find the most recent non-blank line.
        var cursor = lineStart - 1   // position of the '\n' that ends the previous line
        while (cursor >= 0) {
            // cursor is at the line terminator of the previous line; locate
            // the start of that line.
            val prevLineEnd = cursor                                   // exclusive (the '\n' itself)
            var prevLineStart = cursor
            while (prevLineStart > 0 && text[prevLineStart - 1] != '\n') prevLineStart--

            // Trim leading horizontal whitespace.
            var contentStart = prevLineStart
            while (contentStart < prevLineEnd
                && (text[contentStart] == ' ' || text[contentStart] == '\t')
            ) contentStart++

            // Trim trailing whitespace (incl. CR for CRLF line endings).
            var contentEnd = prevLineEnd - 1
            while (contentEnd >= contentStart
                && (text[contentEnd] == ' ' || text[contentEnd] == '\t' || text[contentEnd] == '\r')
            ) contentEnd--

            if (contentStart > contentEnd) {
                // Blank line — keep walking up.
                cursor = prevLineStart - 1
                continue
            }

            return if (looksLikeOpeningHtmlTag(text, contentStart, contentEnd)) {
                text.subSequence(prevLineStart, contentStart).toString() + indentStep
            } else null
        }
        return null
    }

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
        val regions = SkyTemplateRanges.computeProtectedEmbeddedRanges(text)
        val region = regions.firstOrNull { it.startOffset <= offset && offset <= it.endOffset }
            ?: return 0
        val skyRanges = SkyTemplateRanges.computeIndentRanges(text)
        var depth = 0
        var i = region.startOffset
        val end = offset.coerceAtMost(region.endOffset)
        var inString = ' '
        while (i < end) {
            // A SkyTemplate tag's `{` `}` are not JS / CSS braces — skip whole.
            val sky = skyRanges.firstOrNull { it.startOffset <= i && i < it.endOffset }
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
     * True if `text[start..end]` (both inclusive, both non-whitespace) is
     * shaped like `<word…>` — an opening HTML tag.
     *
     * Conditions:
     *   - Starts with `<` followed by a letter — rules out `</…` (closer)
     *     and `<!DOCTYPE…`, `<!--…-->`, `<?…?>` headers.
     *   - Ends with `>` not preceded by `/` — rules out `<br/>` /
     *     `<img src="…"/>` self-closing forms.
     *
     * The interior of the tag is not validated — `<div onclick="x>y">`
     * or any quoted-attribute form passes; that's acceptable for an
     * indent heuristic since false positives just mean a slightly
     * deeper indent than strictly necessary.
     */
    private fun looksLikeOpeningHtmlTag(text: CharSequence, start: Int, end: Int): Boolean {
        if (end - start < 1) return false                              // need at least `<x>`
        if (text[start] != '<') return false
        if (text[end] != '>') return false
        // Closing tag: `</...>`
        if (start + 1 <= end && text[start + 1] == '/') return false
        // Self-closing: `.../>`
        if (end - 1 >= start && text[end - 1] == '/') return false
        // First char after `<` must be a letter.
        if (start + 1 > end) return false
        val firstChar = text[start + 1]
        return firstChar.isLetter()
    }

    private fun lineStartOf(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }
}
