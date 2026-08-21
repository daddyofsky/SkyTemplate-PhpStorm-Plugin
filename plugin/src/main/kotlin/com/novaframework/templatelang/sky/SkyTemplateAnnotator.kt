package com.novaframework.templatelang.sky

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

/**
 * Adds SkyTemplate syntax highlighting to plain HTML / XML files **without**
 * changing the file's primary FileType.
 *
 * Single pass per file, gated on `element is PsiFile`. Two phases:
 *   1. **Comment ranges** — every `{*…*}` and `<!--{*…*}-->` paint with the
 *      comment colour. Multi-line / multi-element / HTML-wrapped forms work
 *      because the scan runs over the full file text instead of relying on
 *      HTML PSI to cleanly delimit comments.
 *   2. **Token-level colours** — variables, tag prefixes, keywords, pipes,
 *      strings, etc. across the whole file. Tokens that fall inside a
 *      comment range are skipped (the comment overlay owns them).
 *
 * Why file-level rather than per-element?
 * HTML PSI structure depends heavily on surrounding markup. `<p>{=foo()}</p>`
 * exposes the content as an `XmlText` (which earlier per-element scans saw),
 * but a top-level `{=foo()}` (no parent tag) is parsed as anonymous body /
 * document content and never reached our `XmlText` matcher — leaving the
 * tokens uncoloured. A single file-scan removes that coupling.
 *
 * Tradeoff: tokens appearing inside `<script>`/`<style>` injection ranges
 * also get the SkyTemplate overlay. SkyTemplate's compiler does process
 * those positions, so the visual treatment is consistent with what the
 * runtime would see.
 */
class SkyTemplateAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Only the file root — single pass per highlighting run.
        if (element !is PsiFile) return
        if (!TemplateLangFileFilter.shouldProcess(element)) return

        // `viewProvider.contents` (not `element.text`) — a stable CharSequence
        // instance across calls within the same document state, so it hits
        // SkyTemplateRangeCache instead of forcing a fresh full-file rescan
        // every time the daemon re-annotates this file.
        val text = element.viewProvider.contents
        if (text.length < 2 || '{' !in text) return

        // Phase 1: comment overlays (full ranges, including HTML-wrapped form).
        // Resolve the comment colour via the global editor scheme so we can
        // pass `enforcedTextAttributes` — required so a `{*…*}` comment sitting
        // inside an enclosing HTML `<!-- … -->` (`XmlComment` PSI) renders with
        // SkyTemplate's comment colour rather than the HTML comment colour.
        // The plain `textAttributes(key)` form would merge with the HTML
        // attributes instead of overriding them.
        //
        // The overlay is painted per [SkyTemplateCommentPaint] segment rather
        // than per comment range: a TODO / FIXME match inside the comment gets
        // its configured TODO attributes and the grey overlay stops at its
        // bounds, so the comment colour can't cover it.
        val commentRanges = SkyTemplateRangeCache.getCommentRanges(text)
        if (commentRanges.isNotEmpty()) {
            val commentAttrs = EditorColorsManager.getInstance().globalScheme
                .getAttributes(SkyTemplateColors.COMMENT)
            for (segment in SkyTemplateCommentPaint.segments(text, commentRanges)) {
                val builder = holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(segment.range)
                val enforced =
                    if (segment.kind == SkyTemplateCommentPaint.Kind.TODO) segment.todoAttributes
                    else commentAttrs
                if (enforced != null) {
                    builder.enforcedTextAttributes(enforced)
                } else {
                    // Fallback: no resolved attrs (theme without BLOCK_COMMENT).
                    // The merging form still tints the range with the SkyTemplate
                    // comment colour, just without overriding HTML XmlComment.
                    builder.textAttributes(SkyTemplateColors.COMMENT)
                }
                builder.create()
            }
        }

        // Phase 2: token-level colours for non-comment template tokens.
        // Restrict to ranges that look like genuine SkyTemplate tags so that
        // CSS rules (`.foo { color: red; }`) and JS object literals / blocks
        // inside `<script>` / `<style>` don't get repainted as template tokens.
        //
        // We lex EACH template range independently — a single file-wide lex
        // would carry IN_TAG state from one tag's `{` to the next tag's `{`,
        // mis-tokenising the inner template's open brace as BAD_CHARACTER.
        // Concretely, in
        //     function getNoServiceAlert(){
        //         {=serviceLimit('A1')}
        //     }
        // the function body's `{` opens IN_TAG and stays there until the
        // template's `}`, so a flat scan would never see the template's `{`
        // as LBRACE. Per-range slicing avoids that entirely.
        val templateRanges = SkyTemplateRangeCache.get(text)

        // Phase 1.5: standalone escape literals — `{\` (no closing `}` on
        // the line) and `\}` (no opening `{` on the line). These DON'T
        // form a paired template range so phase 2 wouldn't see them.
        // Per SkyTemplate compiler `\` is the escape prefix — the standalone
        // forms exist on their own and are not required to pair.
        paintStandaloneEscapes(text, templateRanges, commentRanges, holder)

        if (templateRanges.isEmpty()) return

        for (range in templateRanges) {
            // Skip comment ranges — they were painted in phase 1 and the
            // comment overlay must take precedence.
            if (isInsideAny(commentRanges, range.startOffset, range.endOffset)) continue
            paintRange(text, range, holder)
        }
    }

    private fun paintStandaloneEscapes(
        text: CharSequence,
        templateRanges: List<TextRange>,
        commentRanges: List<TextRange>,
        holder: AnnotationHolder,
    ) {
        if ('\\' !in text) return
        val attrs = SkyTemplateColors.TAG_PREFIX
        var i = 0
        while (i < text.length - 1) {
            val a = text[i]
            val b = text[i + 1]
            val isOpenEscape = a == '{' && b == '\\'
            val isCloseEscape = a == '\\' && b == '}'
            if (!isOpenEscape && !isCloseEscape) {
                i++
                continue
            }
            // Skip if the 2-char span overlaps a paired template tag or
            // comment — those paths handle the chars themselves.
            if (overlapsAny(templateRanges, i, i + 2) ||
                overlapsAny(commentRanges, i, i + 2)
            ) {
                i++
                continue
            }
            // For `{\`, only treat as standalone when no `}` precedes the
            // line end. `{\hello}` (regular escape directive) has its own
            // paired template range that the overlap check above already
            // excluded.
            if (isOpenEscape && hasCloseBeforeLineEnd(text, i + 2)) {
                i++
                continue
            }
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(i, i + 2))
                .textAttributes(attrs)
                .create()
            i += 2
        }
    }

    private fun overlapsAny(ranges: List<TextRange>, start: Int, end: Int): Boolean {
        for (r in ranges) {
            if (r.startOffset < end && start < r.endOffset) return true
        }
        return false
    }

    private fun hasCloseBeforeLineEnd(text: CharSequence, from: Int): Boolean {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '}') return true
            if (c == '\n') return false
            i++
        }
        return false
    }

    private fun paintRange(text: CharSequence, range: TextRange, holder: AnnotationHolder) {
        val slice = text.subSequence(range.startOffset, range.endOffset)
        val lexer = SkyTemplateLexer()
        lexer.start(slice, 0, slice.length, 0)
        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType!!
            if (tokenType !== T.OUTER_CONTENT && !isCommentToken(tokenType)) {
                val absStart = range.startOffset + lexer.tokenStart
                val absEnd = range.startOffset + lexer.tokenEnd
                colorFor(tokenType)?.let { attrs ->
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(TextRange(absStart, absEnd))
                        .textAttributes(attrs)
                        .create()
                }
            }
            lexer.advance()
        }
    }

    private fun isInsideAny(ranges: List<TextRange>, start: Int, end: Int): Boolean {
        if (ranges.isEmpty()) return false
        for (r in ranges) {
            if (r.startOffset <= start && end <= r.endOffset) return true
        }
        return false
    }

    /**
     * Block-comment tokens that phase 1 (file-wide `commentRanges`)
     * already painted — phase 2 must skip them so the overlay isn't
     * applied twice. LINE_COMMENT (`//…` inside a tag) is intentionally
     * NOT in this set: it's emitted per-tag by the lexer and phase 1
     * doesn't see it, so phase 2 paints it via [colorFor].
     */
    private fun isCommentToken(tokenType: IElementType): Boolean = when (tokenType) {
        T.COMMENT_OPEN, T.COMMENT_CLOSE, T.COMMENT_CONTENT -> true
        else -> false
    }

    private fun colorFor(tokenType: IElementType): TextAttributesKey? = when (tokenType) {
        T.COMMENT_OPEN, T.COMMENT_CLOSE, T.COMMENT_CONTENT, T.LINE_COMMENT -> SkyTemplateColors.COMMENT
        T.LBRACE, T.RBRACE -> SkyTemplateColors.BRACES
        T.TAG_PREFIX, T.ESCAPE_LITERAL -> SkyTemplateColors.TAG_PREFIX
        T.TAG_KEYWORD -> SkyTemplateColors.TAG_KEYWORD
        T.SCOPE_RESERVED -> SkyTemplateColors.SCOPE_RESERVED
        T.SCOPE_LOOP -> SkyTemplateColors.SCOPE_LOOP
        T.SCOPE_CONST -> SkyTemplateColors.SCOPE_CONST
        T.IDENTIFIER -> SkyTemplateColors.IDENTIFIER
        // AT / HASH are mid-tag variable modifiers — see SkyTemplateSyntaxHighlighter.
        T.AT, T.HASH -> SkyTemplateColors.TAG_PREFIX
        T.OPERATOR, T.ARROW, T.DBL_COLON, T.COLON, T.EQ, T.NS_SEP -> SkyTemplateColors.OPERATOR
        T.PIPE -> SkyTemplateColors.PIPE
        T.STRING -> SkyTemplateColors.STRING
        T.NUMBER -> SkyTemplateColors.NUMBER
        T.DOT -> SkyTemplateColors.DOT
        T.COMMA -> SkyTemplateColors.COMMA
        T.LPAREN, T.RPAREN -> SkyTemplateColors.PARENS
        T.LBRACKET, T.RBRACKET -> SkyTemplateColors.BRACKETS
        T.BAD_CHARACTER -> SkyTemplateColors.BAD_CHARACTER
        else -> null
    }
}
