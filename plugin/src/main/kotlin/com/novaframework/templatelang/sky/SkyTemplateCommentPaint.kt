package com.novaframework.templatelang.sky

import com.intellij.ide.todo.TodoConfiguration
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.search.TodoPattern

/**
 * Splits each `{*…*}` comment range into the spans the annotator paints —
 * plain comment text plus the TODO-pattern matches that must keep the IDE's
 * TODO colours.
 *
 * In `*.html` host files a SkyTemplate comment is plain text to the HTML
 * lexer, so the platform's TODO highlight visitor never sees `{* TODO … *}`;
 * on the wrapped `<!--{* TODO … *}-->` shape it does (the shell makes it a
 * real `XmlComment`), but [SkyTemplateHtmlErrorFilter] drops that highlight
 * together with the rest of the host noise inside a comment. Painting the
 * TODO spans ourselves — and leaving them out of the grey comment overlay —
 * gives both shapes the configured TODO colour. `*.sky` files need none of
 * this: SkyTemplate's own comment tokens reach the platform through
 * [SkyTemplateParserDefinition.getCommentTokens].
 *
 * Patterns and colours come from *Settings → Editor → TODO*, matched the
 * same way the platform matches them (per pattern regex, first match wins on
 * overlap). Multi-line TODO continuation is not honoured — a match ends at
 * its line end.
 */
internal object SkyTemplateCommentPaint {

    enum class Kind { COMMENT, TODO }

    class Segment(val range: TextRange, val kind: Kind, val todoAttributes: TextAttributes?)

    /**
     * Paint segments for [commentRanges], in document order. A comment with
     * no TODO match yields exactly one COMMENT segment spanning the whole
     * comment (the pre-TODO behaviour).
     */
    fun segments(text: CharSequence, commentRanges: List<TextRange>): List<Segment> {
        if (commentRanges.isEmpty()) return emptyList()
        val patterns = TodoConfiguration.getInstance().todoPatterns
        val out = ArrayList<Segment>(commentRanges.size)
        for (comment in commentRanges) {
            val todos = todoMatches(text, comment, patterns)
            if (todos.isEmpty()) {
                out += Segment(comment, Kind.COMMENT, null)
                continue
            }
            var cursor = comment.startOffset
            for (todo in todos) {
                if (todo.range.startOffset > cursor) {
                    out += Segment(TextRange(cursor, todo.range.startOffset), Kind.COMMENT, null)
                }
                out += todo
                cursor = todo.range.endOffset
            }
            if (cursor < comment.endOffset) {
                out += Segment(TextRange(cursor, comment.endOffset), Kind.COMMENT, null)
            }
        }
        return out
    }

    /**
     * `true` when `[start, end)` is exactly one of the spans [segments]
     * paints. [SkyTemplateHtmlErrorFilter] uses this to keep our own overlay
     * alive while still dropping foreign highlights inside a comment.
     */
    fun isPaintedSpan(
        text: CharSequence,
        commentRanges: List<TextRange>,
        start: Int,
        end: Int,
    ): Boolean = segments(text, commentRanges).any {
        it.range.startOffset == start && it.range.endOffset == end
    }

    private fun todoMatches(
        text: CharSequence,
        comment: TextRange,
        patterns: Array<TodoPattern>,
    ): List<Segment> {
        if (patterns.isEmpty()) return emptyList()
        val bounds = bodyBounds(text, comment) ?: return emptyList()
        val (bodyStart, bodyEnd) = bounds
        if (bodyEnd <= bodyStart) return emptyList()

        val body = text.subSequence(bodyStart, bodyEnd)
        val found = ArrayList<Segment>()
        for (pattern in patterns) {
            val regex = pattern.pattern ?: continue
            val attributes = pattern.attributes?.textAttributes
            val matcher = regex.matcher(body)
            while (matcher.find()) {
                if (matcher.end() == matcher.start()) continue
                found += Segment(
                    TextRange(bodyStart + matcher.start(), bodyStart + matcher.end()),
                    Kind.TODO,
                    attributes,
                )
            }
        }
        if (found.size < 2) return found

        found.sortBy { it.range.startOffset }
        val merged = ArrayList<Segment>(found.size)
        var lastEnd = -1
        for (segment in found) {
            if (segment.range.startOffset < lastEnd) continue
            merged += segment
            lastEnd = segment.range.endOffset
        }
        return merged
    }

    /**
     * Body bounds of a comment range — the text between `{*` and `*}`, with
     * the optional `<!--` / `-->` shell of the wrapped form excluded. An
     * unterminated `{* …` keeps everything up to the range end.
     */
    private fun bodyBounds(text: CharSequence, comment: TextRange): Pair<Int, Int>? {
        var open = -1
        var i = comment.startOffset
        while (i < comment.endOffset - 1) {
            if (text[i] == '{' && text[i + 1] == '*') { open = i; break }
            i++
        }
        if (open < 0) return null

        var close = comment.endOffset
        var j = comment.endOffset - 2
        while (j > open + 1) {
            if (text[j] == '*' && text[j + 1] == '}') { close = j; break }
            j--
        }
        return (open + 2) to close
    }
}
