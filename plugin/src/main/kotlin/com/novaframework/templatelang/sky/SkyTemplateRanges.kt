package com.novaframework.templatelang.sky

import com.intellij.openapi.util.TextRange
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

/**
 * Pure helpers — scan the file's source text and locate SkyTemplate
 * constructs. Used by:
 *
 *   - [SkyTemplateHtmlErrorFilter] — drops HTML/XML errors overlapping any
 *     template range.
 *   - [SkyTemplateAnnotator] — at file level, paints comment ranges so that
 *     multi-line / multi-element / HTML-wrapped template comments stay one
 *     contiguous block instead of being shredded by HTML PSI.
 *
 * No IntelliJ infrastructure required ⇒ unit-testable in isolation.
 */
object SkyTemplateRanges {

    /**
     * HTML-wrapped directive: `<!--{ … }-->` (any number of leading/trailing
     * dashes ≥2). SkyTemplate's compiler strips these wrappers before
     * compilation, so the IDE should treat them as a single template span.
     *
     * The body is matched lazily so the OUTER `}-->` of the user's
     *   `<!--{* … <!--<li>{hit}</li>--> … *}-->`
     * is found correctly even when an inner `-->` appears.
     */
    private val WRAPPED_DIRECTIVE = Regex("""<!--+\{[\s\S]*?\}--+>""")

    /**
     * All template-construct ranges (every `{…}` directive that *looks like a
     * template tag*, every `{*…*}` comment, every `<!--{…}-->` wrapped form).
     * Caller-side use: drop HTML / JS / CSS errors whose range overlaps any
     * of these.
     *
     * Plain `{…}` ranges are filtered through [looksLikeTemplateBody] so that
     * CSS rules (`.foo { color: red; }`) and JS object literals / blocks
     * (`{ a: 1, b: 2 }`, `{ stmt; }`) inside `<script>` / `<style>` injections
     * are NOT mistaken for template tags. Without that filter we would silence
     * genuine CSS / JS errors and re-colour those regions as template tokens.
     */
    fun computeTemplateRanges(text: CharSequence): List<TextRange> {
        if (text.isEmpty() || '{' !in text) return emptyList()
        val ranges = ArrayList<TextRange>()

        // 1. HTML-wrapped directives
        val wrapped = WRAPPED_DIRECTIVE.findAll(text)
            .map { TextRange(it.range.first, it.range.last + 1) }
            .toList()
        ranges += wrapped

        // 2. `{* … *}` comment ranges — must take precedence over plain `{…}`
        //    so nested `{` characters inside a comment body don't get scanned.
        val commentRanges = computeCommentRanges(text)
        for (r in commentRanges) {
            if (!isInside(r, wrapped)) ranges += r
        }

        // 3. PHP code regions `<?php … ?>` / `<?= … ?>` / `<? … ?>`. Their
        //    bodies (including any string literals inside) are NOT SkyTemplate
        //    territory — without this exclusion, a PHP string literal like
        //    `'<?php if (%s) { ?>'` has its `{ ?` mis-detected as a `{?…}`
        //    directive (the `?` passes the tag-prefix-char check and the
        //    bogus brace pair is formed against some later `}`).
        val phpRanges = computePhpRanges(text)

        // 4. Plain `{ … }` brace pairs with depth tracking. CSS / JS often nests
        //    braces (`@media { .foo { color: red; } }`, JS arrow blocks, etc.),
        //    so a flat lexer scan would close the outer `{` against the first
        //    inner `}` and miss the genuine template tag inside. We track
        //    depth and emit every pair, then filter through `looksLikeTemplateBody`
        //    so CSS rules / JS objects / blocks don't register as template tags.
        val pairs = findBracePairs(text, commentRanges + wrapped + phpRanges)
        for (pair in pairs) {
            if (isInside(pair, wrapped)) continue
            if (isInside(pair, commentRanges)) continue
            if (anyOverlap(phpRanges, pair.startOffset, pair.endOffset)) continue
            if (looksLikeTemplateBody(text, pair.startOffset, pair.endOffset)) {
                ranges += pair
            }
        }

        ranges.sortBy { it.startOffset }
        return ranges
    }

    /** `<script>` / `<style>` — embedded JS / CSS, owned by a real formatter. */
    private val EMBEDDED_CODE_TAGS = setOf("script", "style")

    /**
     * Body ranges of `<script>` / `<style>` elements whose content holds at
     * least one SkyTemplate tag.
     *
     * **Why.** In an `*.html` host the `<script>` body is lexer-embedded
     * JavaScript and `<style>` is embedded CSS — a real formatter that reads
     * `{…}` as code. On Reformat it mangles the whole region: it pushes the
     * `;` of `const a = {=foo};` onto its own line, breaks an inline
     * `{?var}true{:}false{/}` across lines, inserts blank lines around the
     * tags, and re-indents the body wrongly. None of that is fixable tag by
     * tag — the damage lands in the whitespace BETWEEN tags — so the
     * pre/post-format pair snapshots each such body and restores it verbatim,
     * leaving SkyTemplate-structured embedded code as the user wrote it (the
     * plugin's own block re-indent still runs on top afterwards).
     *
     * A `<script>` / `<style>` with NO SkyTemplate tag is left out, so a
     * genuine JS object literal (`const x = {a: 1, b: 2}`) or plain CSS still
     * gets formatted normally by the host.
     *
     * Body span is strictly between the open tag's `>` and the matching
     * `</script>` / `</style>`; the delimiters stay HTML-owned. An
     * unterminated element extends to EOF.
     */
    fun computeProtectedEmbeddedRanges(text: CharSequence): List<TextRange> {
        if (text.length < 2 || '<' !in text) return emptyList()
        val tags = computeTemplateRanges(text)
        if (tags.isEmpty()) return emptyList()

        val result = ArrayList<TextRange>()
        val n = text.length
        var i = 0
        while (i < n - 1) {
            if (text[i] != '<' || !text[i + 1].isLetter()) { i++; continue }
            var j = i + 1
            while (j < n && text[j].isLetterOrDigit()) j++
            val name = text.subSequence(i + 1, j).toString().lowercase()
            if (name !in EMBEDDED_CODE_TAGS) { i = j; continue }

            val openEnd = scanTagClose(text, i, n)
            if (openEnd == null) { i = j; continue }
            val bodyStart = openEnd + 1
            val closeStart = indexOfCloseTag(text, name, bodyStart, n)
            val bodyEnd = closeStart ?: n
            if (bodyEnd > bodyStart && anyOverlap(tags, bodyStart, bodyEnd)) {
                result += TextRange(bodyStart, bodyEnd)
            }
            i = if (closeStart != null) (scanTagClose(text, closeStart, n) ?: closeStart) + 1 else n
        }
        result.sortBy { it.startOffset }
        return result
    }

    /**
     * Offset of the `>` that closes the tag opening at [openOffset], honouring
     * single / double quoted attribute values so `<a title=">">` finds the
     * right `>`. Null when the tag does not close before [end].
     */
    private fun scanTagClose(text: CharSequence, openOffset: Int, end: Int): Int? {
        var i = openOffset + 1
        var quote = ' '
        while (i < end) {
            val c = text[i]
            if (quote != ' ') {
                if (c == quote) quote = ' '
            } else when (c) {
                '"', '\'' -> quote = c
                '>' -> return i
            }
            i++
        }
        return null
    }

    /**
     * Start offset of the next `</name …>` closer at or after [from]
     * (case-insensitive, word-boundary terminated), or null if none before
     * [end]. Per the HTML rule the first such closer ends the element.
     */
    private fun indexOfCloseTag(text: CharSequence, name: String, from: Int, end: Int): Int? {
        var i = from
        while (i < end - 1) {
            if (text[i] == '<' && text[i + 1] == '/') {
                var k = i + 2
                var m = 0
                while (k < end && m < name.length && text[k].lowercaseChar() == name[m]) { k++; m++ }
                if (m == name.length &&
                    (k >= end || text[k] == '>' || text[k] == '/' || text[k].isWhitespace())
                ) {
                    return i
                }
            }
            i++
        }
        return null
    }

    /**
     * Locate every `{` … `}` pair in [text], respecting nested braces and
     * skipping any pair whose `{` falls inside an exclusion zone (comment /
     * wrapped directive). Each result range *includes* the outer braces.
     *
     * `${` is never treated as a template open (JS template-literal syntax).
     * Strings are NOT respected — any `{` / `}` inside a CSS / JS string still
     * counts toward depth. This is acceptable because the rejection heuristic
     * downstream filters those cases out.
     */
    private fun findBracePairs(text: CharSequence, exclusions: List<TextRange>): List<TextRange> {
        if (text.isEmpty()) return emptyList()
        val pairs = ArrayList<TextRange>()
        val openStack = ArrayDeque<Int>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            // Skip the entire exclusion span on either '{' or '}'. PHP blocks
            // contain both halves of the user-reported case (`{` in
            // `<?php if (..) { ?>`, `}` in `<?php } ?>`), so a `}`-only inside
            // an exclusion would otherwise wrongly close an outer-HTML `{`.
            if ((c == '{' || c == '}') && anyContainsOffset(exclusions, i)) {
                val ex = exclusions.firstOrNull { it.startOffset <= i && i < it.endOffset }
                i = ex?.endOffset ?: (i + 1)
                continue
            }
            if (c == '{') {
                if (i > 0 && text[i - 1] == '$') { i++; continue }
                openStack.addLast(i)
                i++
            } else if (c == '}' && openStack.isNotEmpty()) {
                val open = openStack.removeLast()
                pairs += TextRange(open, i + 1)
                i++
            } else {
                i++
            }
        }
        // Best-effort: stray opens become open..end ranges so the visual /
        // suppression behaviour degrades gracefully on partial input.
        while (openStack.isNotEmpty()) {
            val open = openStack.removeLast()
            pairs += TextRange(open, n)
        }
        pairs.sortBy { it.startOffset }
        return pairs
    }

    private fun anyContainsOffset(ranges: List<TextRange>, offset: Int): Boolean {
        for (r in ranges) {
            if (r.startOffset <= offset && offset < r.endOffset) return true
        }
        return false
    }

    /**
     * PHP code regions: `<?php … ?>`, `<?= … ?>`, `<? … ?>` (short open).
     * Each region span includes the `<?` and `?>` delimiters themselves.
     *
     * Why we exclude these from SkyTemplate scanning. PHP source — even when
     * embedded in an HTML host file — is *not* template territory. A string
     * literal inside PHP can carry text shaped like a SkyTemplate construct
     * without being one (e.g. the SkyTemplate compiler itself emits
     * `'<?php if (%s) { ?>'`); without exclusion, the `{ ?` from such a
     * literal slips past `looksLikeTemplateBody` (the `?` is a tag-prefix
     * char, leading whitespace is allowed) and `findBracePairs` invents a
     * bogus tag range by pairing the literal's `{` with some later `}`.
     *
     * Detection rules (chosen to match PHP's own opener semantics, not a
     * full PHP parser):
     *   - `<?php` — must be word-boundary terminated (so `<?phpfoo` is NOT
     *     a PHP open).
     *   - `<?=` — short echo tag.
     *   - `<?` followed by whitespace or EOF — bare short open.
     *   - `<?xml` is intentionally NOT matched — the `xml` after `<?` fails
     *     all three rules above, so XML declarations stay outside the
     *     exclusion. (Their bodies wouldn't contain template constructs in
     *     practice either, so the choice is mostly aesthetic.)
     *
     * Limitations (acceptable for this heuristic):
     *   - String literals containing a literal `?>` inside PHP code (e.g.
     *     `$s = '?>';`) terminate our region prematurely. The leftover tail
     *     after the bogus close is then treated as outside-PHP. PHP itself
     *     doesn't terminate inside strings, but a full PHP tokenizer would
     *     be overkill for a SkyTemplate exclusion zone — the user-reported
     *     case (`'<?php if (%s) { ?>'` near the start of the file) is
     *     handled correctly because the `{` falls within the region we DO
     *     match.
     *   - Heredoc / nowdoc with `?>` inside the body: same caveat.
     *   - An unclosed `<?php` (no `?>` before EOF) extends to text length
     *     so the entire tail is treated as PHP — matches PHP's own
     *     behaviour for short PHP-only files.
     */
    fun computePhpRanges(text: CharSequence): List<TextRange> {
        val n = text.length
        if (n < 2 || '<' !in text) return emptyList()
        val ranges = ArrayList<TextRange>()
        var i = 0
        while (i < n - 1) {
            if (text[i] == '<' && text[i + 1] == '?' && isPhpOpenAt(text, i)) {
                val start = i
                var j = i + 2
                while (j < n - 1 && !(text[j] == '?' && text[j + 1] == '>')) j++
                val end = if (j < n - 1) j + 2 else n
                ranges += TextRange(start, end)
                i = end
                continue
            }
            i++
        }
        return ranges
    }

    private fun isPhpOpenAt(text: CharSequence, openOffset: Int): Boolean {
        // openOffset points at '<'; '?' is at openOffset+1 (caller checked).
        val after = openOffset + 2
        val n = text.length
        if (after >= n) return true                              // bare `<?` at EOF
        val c = text[after]
        if (c == '=') return true                                // `<?=`
        if (c.isWhitespace()) return true                        // `<? `, `<?\n`
        if (c == 'p' && after + 2 < n && text[after + 1] == 'h' && text[after + 2] == 'p') {
            // `<?php` — require word-boundary after.
            val k = after + 3
            return k >= n || !(text[k].isLetterOrDigit() || text[k] == '_')
        }
        return false
    }


    /**
     * Comment-only ranges. The lexer state (OUTER ↔ IN_COMMENT) tracks
     * `{* … *}` correctly across the entire file, so multi-line and
     * multi-element comments fall out for free.
     *
     * Wrapped form `<!--{*…*}-->` is added on top so its outer `<!--` and
     * `-->` markers get the comment colour too.
     */
    fun computeCommentRanges(text: CharSequence): List<TextRange> {
        if (text.isEmpty()) return emptyList()
        val ranges = ArrayList<TextRange>()

        // 1. Wrapped form, restricted to actual comment payloads `{* … *}`.
        val wrapped = WRAPPED_DIRECTIVE.findAll(text)
            .filter { it.value.contains("{*") && it.value.contains("*}") }
            .map { TextRange(it.range.first, it.range.last + 1) }
            .toList()
        ranges += wrapped

        // PHP regions own their text — a `{* … *}` sequence inside a PHP
        // string literal is NOT a SkyTemplate comment.
        val phpRanges = computePhpRanges(text)

        // 2. Plain `{* … *}`.
        if ("{*" in text) {
            val lexer = SkyTemplateLexer()
            lexer.start(text, 0, text.length, 0)
            var commentStart = -1
            while (lexer.tokenType != null) {
                when (lexer.tokenType) {
                    T.COMMENT_OPEN -> if (commentStart < 0) commentStart = lexer.tokenStart
                    T.COMMENT_CLOSE -> if (commentStart >= 0) {
                        val r = TextRange(commentStart, lexer.tokenEnd)
                        if (!isInside(r, wrapped) &&
                            !anyOverlap(phpRanges, r.startOffset, r.endOffset)
                        ) ranges += r
                        commentStart = -1
                    }
                    else -> Unit
                }
                lexer.advance()
            }
            // unterminated `{* …` — emit best-effort range so the HTML noise inside still gets suppressed.
            if (commentStart >= 0) {
                val r = TextRange(commentStart, text.length)
                if (!isInside(r, wrapped) &&
                    !anyOverlap(phpRanges, r.startOffset, r.endOffset)
                ) ranges += r
            }
        }

        ranges.sortBy { it.startOffset }
        return ranges
    }

    /**
     * Indent-only ranges: [computeTemplateRanges] plus the block-shaped
     * template tags that sit INSIDE `{*…*}` comment bodies.
     *
     * Comments neutralise their content for every other consumer (errors,
     * references, semantic colour, …) — see [computeTemplateRanges] / the
     * comment-range exclusions. The indenter is the one exception: a
     * `{loop}…{/}` written inside a comment is inert, but the user still wants
     * its body to indent under the opener, on par with HTML tags inside the
     * same comment. Only the indent walkers may use this; everyone else MUST
     * keep using [computeTemplateRanges] so neutralisation is preserved.
     *
     * Nested `{*…*}` markers inside the comment body are skipped — they are
     * comment delimiters, not block tags.
     */
    fun computeIndentRanges(text: CharSequence): List<TextRange> {
        val base = computeTemplateRanges(text)
        if ("{*" !in text) return base
        val comments = computeCommentRanges(text)
        if (comments.isEmpty()) return base
        val result = ArrayList(base)
        for (c in comments) {
            collectCommentInnerTagPairs(text, c.startOffset + 2, c.endOffset - 2, result)
        }
        result.sortBy { it.startOffset }
        return result
    }

    /**
     * Append every `{ … }` pair in `[from, to)` that [looksLikeTemplateBody]
     * accepts. Nested `{*…*}` spans are skipped whole (their inner braces are
     * comment text, not tags). Mirrors [findBracePairs]'s depth tracking but
     * scoped to a single comment body and without the external exclusion list.
     */
    private fun collectCommentInnerTagPairs(
        text: CharSequence,
        from: Int,
        to: Int,
        out: ArrayList<TextRange>,
    ) {
        if (from >= to) return
        val openStack = ArrayDeque<Int>()
        var i = from
        while (i < to) {
            val c = text[i]
            // Skip a nested `{*…*}` comment whole (balanced).
            if (c == '{' && i + 1 < to && text[i + 1] == '*') {
                var depth = 1
                var j = i + 2
                while (j < to - 1) {
                    if (text[j] == '{' && text[j + 1] == '*') { depth++; j += 2; continue }
                    if (text[j] == '*' && text[j + 1] == '}') {
                        depth--
                        j += 2
                        if (depth == 0) break
                        continue
                    }
                    j++
                }
                i = if (depth == 0) j else to
                continue
            }
            if (c == '{') {
                if (i > 0 && text[i - 1] == '$') { i++; continue }
                openStack.addLast(i)
                i++
            } else if (c == '}' && openStack.isNotEmpty()) {
                val open = openStack.removeLast()
                if (looksLikeTemplateBody(text, open, i + 1)) out += TextRange(open, i + 1)
                i++
            } else {
                i++
            }
        }
    }

    /**
     * Spans of Sky blocks inside which a host-language "duplicate" diagnostic
     * (HTML `Duplicate id reference`, JS `Duplicate declaration`) is a false
     * positive, because the HTML / JS parser sees every branch / iteration
     * flattened into one scope:
     *
     *   - **LOOP block** (`{@…}`, `{%…}`, `{loop …}`, `{foreach …}`,
     *     `{for …}`, `{while …}`, `{each …}`) — the body is emitted once per
     *     iteration, so a single `id` / declaration written inside it is not a
     *     real duplicate.
     *   - **IF / switch block with at least one branch** (`{:}` / `{:case}` /
     *     `{else}` / `{elseif}`) — the branches are mutually exclusive, so the
     *     same `id` / declaration appearing in two different branches never
     *     coexists at runtime.
     *
     * A plain `{?cond}…{/}` with NO branch is intentionally excluded — its body
     * can still collide with identical content sitting outside the `if`, so
     * those duplicates are kept.
     *
     * Span covers the opener's `{` through the matching closer's `}`. Pairing
     * is best-effort LIFO; on malformed input the worst case is that a block
     * is simply not recorded (no suppression), never a wrong suppression of
     * unrelated code.
     */
    fun computeDuplicateSuppressionRanges(text: CharSequence): List<TextRange> {
        if (text.isEmpty() || '{' !in text) return emptyList()
        val ranges = computeTemplateRanges(text)
        if (ranges.isEmpty()) return emptyList()

        data class Frame(val openerStart: Int, val isLoop: Boolean, var hasBranch: Boolean)
        val stack = ArrayDeque<Frame>()
        val result = ArrayList<TextRange>()
        for (r in ranges) {
            when (classifyBlockKind(text, r.startOffset, r.endOffset)) {
                BlockKind.OPEN_LOOP -> stack.addLast(Frame(r.startOffset, true, false))
                BlockKind.OPEN_IF -> stack.addLast(Frame(r.startOffset, false, false))
                BlockKind.BRANCH -> stack.lastOrNull()?.hasBranch = true
                BlockKind.CLOSE -> {
                    val f = stack.removeLastOrNull() ?: continue
                    if (f.isLoop || f.hasBranch) result += TextRange(f.openerStart, r.endOffset)
                }
                BlockKind.OTHER -> {}
            }
        }
        result.sortBy { it.startOffset }
        return result
    }

    private enum class BlockKind { OPEN_LOOP, OPEN_IF, BRANCH, CLOSE, OTHER }

    /**
     * Classify a `{ … }` tag for [computeDuplicateSuppressionRanges]. Splits
     * the generic OPEN into loop vs if so a branch-less `{?cond}` can be
     * distinguished from a repeating loop. Mirrors the prefix / keyword rules
     * used by the other tag classifiers in this plugin.
     */
    private fun classifyBlockKind(text: CharSequence, openOffset: Int, closeEndOffset: Int): BlockKind {
        val bodyStart = openOffset + 1
        val bodyEnd = closeEndOffset - 1
        if (bodyEnd <= bodyStart) return BlockKind.OTHER
        var i = bodyStart
        while (i < bodyEnd && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= bodyEnd) return BlockKind.OTHER
        val first = text[i]
        val hasLeadingWs = i > bodyStart
        if (first == '/') return BlockKind.CLOSE
        if (first == ':') return BlockKind.BRANCH
        if (first == '?' && i + 1 < bodyEnd && text[i + 1] == ':') return BlockKind.OTHER  // elvis
        if (first == '?') return BlockKind.OPEN_IF
        if (first == '@' || first == '%') return BlockKind.OPEN_LOOP
        if (hasLeadingWs) return BlockKind.OTHER
        if (!(first.isLetter() || first == '_')) return BlockKind.OTHER
        var j = i + 1
        while (j < bodyEnd && (text[j].isLetterOrDigit() || text[j] == '_')) j++
        val word = text.subSequence(i, j).toString().lowercase()
        val followedByBoundary = j >= bodyEnd || !(text[j].isLetterOrDigit() || text[j] == '_')
        if (!followedByBoundary) return BlockKind.OTHER
        return when (word) {
            "loop", "foreach", "for", "while", "each" -> BlockKind.OPEN_LOOP
            "if" -> BlockKind.OPEN_IF
            "else", "elseif" -> BlockKind.BRANCH
            "end" -> BlockKind.CLOSE
            else -> BlockKind.OTHER
        }
    }

    fun anyOverlap(ranges: List<TextRange>, start: Int, end: Int): Boolean {
        if (ranges.isEmpty() || start >= end) return false
        for (r in ranges) {
            if (r.startOffset < end && r.endOffset > start) return true
        }
        return false
    }

    private fun isInside(r: TextRange, container: List<TextRange>): Boolean =
        container.any { it.contains(r) }

    /**
     * Decide whether the `{…}` span at [start, end) — including the braces —
     * is actually a SkyTemplate tag, as opposed to a CSS rule body or JS
     * block / object literal that happens to share the brace shape.
     *
     * Returns true on any of:
     *   - First non-whitespace char is a SkyTemplate tag-prefix char
     *     (`?`, `:`, `=`, `;`, `#`, `@`, `+`, `\`, `/`, `&`, `]`, `%`).
     *   - First word is a SkyTemplate keyword (`if`, `else`, `loop`, …).
     *   - Body starts with `c.IDENT` (constant scope).
     *   - Body contains a pipe (`|`) — pipe filters never appear in CSS/JS.
     *   - Body contains `->` — SkyTemplate method-call arrow.
     *   - Body contains `::` — static-method / class-constant access.
     *   - Body contains `#NN` zerofill (`{num#5}`).
     *   - Body is a single dotted-identifier chain (`{var}`, `{var.key.sub}`)
     *     with no other punctuation — captures the most common variable form.
     *
     * Returns false for typical CSS `{ prop: val; ... }` and JS `{ k: v, k2: v2 }`
     * bodies — those carry `:` / `;` / `,` *without* any of the SkyTemplate
     * markers above, so the conservative answer is "not a template tag".
     */
    fun looksLikeTemplateBody(text: CharSequence, openOffset: Int, closeEndOffset: Int): Boolean {
        // Strip the outer braces; the open is `{` (1 char), the close is `}` (1 char).
        val bodyStart = openOffset + 1
        val bodyEnd = closeEndOffset - 1
        if (bodyEnd <= bodyStart) return false

        // Strictness rules from the compilers:
        //
        //   SkyTemplate (`PATTERN_TAG` / `PATTERN_VAR`)
        //     - Prefix char / keyword / var name must be IMMEDIATELY after `{`
        //       (no whitespace).
        //
        //   Template_ (`_compile_statement` line 596:
        //              `/^(\\*)\s*(:\?|[=#@?:\/+])?(.*)$/s`)
        //     - `\s*` between the leading escape and the prefix → whitespace
        //       between `{` and the prefix is allowed.
        //     - Has no keyword form (uses single-char prefixes `=`, `#`, `@`,
        //       `?`, `:`, `/`, `+`).
        //
        // We support both engines under one code path, so:
        //   - prefix char  → leading whitespace allowed (Template_ permissive)
        //   - variable     → leading whitespace allowed (Template_ permissive)
        //   - keyword form → leading whitespace forbidden (SkyTemplate strict;
        //                    Template_ has no keywords so this is moot)
        // The keyword strictness is what protects JS function bodies like
        // `{ if (cond) { ... } }` from misdetection.

        // Locate first non-horizontal-whitespace over the FULL body (no
        // line-comment stripping yet). Stripping `//` here would consume the
        // closer in `{/// end}` (`/` closer + `//` comment marker, no space)
        // and leave the body looking empty.
        var firstNonWs = bodyStart
        while (firstNonWs < bodyEnd && isHorizontalWhitespace(text[firstNonWs])) firstNonWs++
        if (firstNonWs >= bodyEnd) return false
        // If we stopped at a vertical whitespace (\r / \n), the body's leading
        // run is non-template — a newline appears before the first meaningful
        // character, which neither compiler permits.
        if (text[firstNonWs] == '\r' || text[firstNonWs] == '\n') return false
        val first = text[firstNonWs]
        val hasLeadingWhitespace = firstNonWs > bodyStart

        // 1. Tag-prefix char (any of `&` `?` `:` `/` `@` `%` `=` `;` `#` `+` `]`
        //    `\`, plus `*` for comment opener). Allowed even with leading
        //    whitespace (Template_ permissiveness). JS / CSS bodies essentially
        //    never start with these chars at depth-0 of `{ … }`.
        //
        //    `/` is special: it overlaps with the `//` line-comment marker,
        //    so a body like `// js comment\nstmt;` would otherwise pass via
        //    this early-return. Demand the closer shape `/\h*(?://.*)?` for
        //    `/` and let other prefixes through unconditionally.
        if (first == '/') {
            if (looksLikeCloserPattern(text, firstNonWs, bodyEnd)) return true
            // Else fall through — a stray `/` that isn't the closer pattern
            // (e.g. `{/end}` or `{/foo}`) will be rejected by the keyword
            // and dotted-chain checks below.
        } else if (T.isTagPrefixChar(first) || first == '*') {
            return true
        }

        // SkyTemplate's compiler allows a trailing single-line comment inside
        // template tags (the `(?:\h*//[^}\n]` branch of PATTERN_VAR, plus the
        // keyword-arg strip step in the dispatcher). Treat the body as ending
        // at any unescaped slash-slash (outside string literals) so the
        // heuristic sees just the meaningful content.
        val effectiveBodyEnd = findLineCommentStart(text, bodyStart, bodyEnd) ?: bodyEnd

        // Body-level markers — collected once so the keyword check and the
        // dotted-chain fallback can share the nested-brace signal.
        var hasPipe = false
        var hasArrow = false
        var hasDblColon = false
        var hasZerofill = false
        var hasColon = false
        var hasSemicolon = false
        var hasComma = false
        var hasNestedBrace = false
        var k = bodyStart
        var inString: Char = ' '
        while (k < effectiveBodyEnd) {
            val c = text[k]
            if (inString != ' ') {
                if (c == '\\' && k + 1 < bodyEnd) { k += 2; continue }
                if (c == inString) inString = ' '
                k++
                continue
            }
            when (c) {
                '\'', '"' -> inString = c
                '|' -> if (k + 1 >= effectiveBodyEnd || text[k + 1] != '|') hasPipe = true
                '-' -> if (k + 1 < effectiveBodyEnd && text[k + 1] == '>') {
                    hasArrow = true
                    k++
                }
                ':' -> if (k + 1 < effectiveBodyEnd && text[k + 1] == ':') {
                    hasDblColon = true
                    k++
                } else hasColon = true
                '#' -> {
                    // Zerofill `IDENT#NN` only — `#` must be preceded by a
                    // word char so CSS hex colors `#fff` / `#123abc` (which
                    // come after `:` or whitespace) don't trigger.
                    val prev = if (k > bodyStart) text[k - 1] else ' '
                    val prevIsWord = prev.isLetterOrDigit() || prev == '_'
                    val nextIsDigit = k + 1 < effectiveBodyEnd && text[k + 1].isDigit()
                    if (prevIsWord && nextIsDigit) hasZerofill = true
                }
                ';' -> hasSemicolon = true
                ',' -> hasComma = true
                '{', '}' -> hasNestedBrace = true
            }
            k++
        }

        // Strong template signals — pipe / `->` / `::` / zerofill don't appear
        // in non-pathological CSS or JS expression bodies. Demand no nested
        // braces so a JS arrow body `() => { return a | b; }` doesn't trigger.
        if ((hasPipe || hasArrow || hasDblColon || hasZerofill) && !hasNestedBrace) {
            return true
        }

        // 2. Keyword IMMEDIATELY at body start (no leading whitespace per
        //    SkyTemplate `PATTERN_TAG`). Case-insensitive (`/i` flag plus
        //    `strtolower($current)` in the dispatcher). The regex's `(?=\W)`
        //    enforces a word boundary after the keyword, so `{ifx}` falls
        //    through to the variable form. Reject when nested braces exist —
        //    that's a JS function body, not a SkyTemplate tag.
        if (!hasLeadingWhitespace && (first.isLetter() || first == '_')) {
            var j = bodyStart
            while (j < effectiveBodyEnd && (text[j].isLetterOrDigit() || text[j] == '_')) j++
            val word = text.subSequence(bodyStart, j).toString().lowercase()
            val followedByBoundary = j >= effectiveBodyEnd ||
                !(text[j].isLetterOrDigit() || text[j] == '_')
            if (word in T.KEYWORDS && followedByBoundary && !hasNestedBrace) return true
            // 3. `c.IDENT` constant scope (no leading whitespace; case-sensitive `c`).
            if (word == "c" && j < effectiveBodyEnd && text[j] == '.') return true
        }

        // 4. Single dotted-identifier chain `{var}` / `{var.key.sub}` /
        //    `{ var }` (Template_ permissive). Reject when the body has any
        //    of `:` / `;` / `,` / nested `{` `}` — those are CSS / JS markers.
        if (!hasColon && !hasSemicolon && !hasComma && !hasNestedBrace &&
            isDottedIdentChain(text, bodyStart, effectiveBodyEnd)
        ) {
            return true
        }

        return false
    }

    /**
     * True if [start, end) is a SkyTemplate variable shape:
     *   `<.+>?<IDENT>(@<digits>?)?(.<IDENT>)*`
     * with optional surrounding horizontal whitespace. Newlines disqualify.
     *
     * Matches the compiler's `(?<scope>_|\.+|c\.)?…(?<var_up>@\d*)?(?<var_array>(?:\.\w+)*)?`
     * shape closely enough to keep `looksLikeTemplateBody` from rejecting
     * legitimate variable forms in HTML / XML host files. Accepted:
     *   - `var`, `var.field`, `var.a.b.c`         — plain dotted chain
     *   - `.var`, `..parent`, `...grand`          — leading scope dots
     *   - `var@`, `var@2`, `.var@2`               — var_up modifier
     *   - `.category.name`                        — scope + property access
     *   - `_index`                                — reserved scope (covered)
     */
    private fun isDottedIdentChain(text: CharSequence, start: Int, end: Int): Boolean {
        var i = start
        while (i < end && isHorizontalWhitespace(text[i])) i++
        if (i >= end) return false
        // Optional leading scope-loop marker: one or more `.` chars
        // (`.var` / `..parent` / `...grand`).
        while (i < end && text[i] == '.') i++
        if (i >= end) return false
        // First identifier
        if (!(text[i].isLetter() || text[i] == '_')) return false
        i++
        while (i < end && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        // Optional `@` (var_up): bare `@` or `@digits`.
        if (i < end && text[i] == '@') {
            i++
            while (i < end && text[i].isDigit()) i++
        }
        // Optional `.IDENT` repeats (var_array — property access).
        while (i < end && text[i] == '.') {
            i++
            if (i >= end) return false
            if (!(text[i].isLetter() || text[i] == '_')) return false
            i++
            while (i < end && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        }
        // Trailing horizontal whitespace only
        while (i < end && isHorizontalWhitespace(text[i])) i++
        return i >= end
    }

    /** Horizontal whitespace per PCRE `\h` semantics — space and tab only. */
    private fun isHorizontalWhitespace(c: Char): Boolean = c == ' ' || c == '\t'

    /**
     * Match the closer-tag shape `/\h*(?://[^\n]*)?$`. Used to disambiguate
     * `{/}` / `{/  }` / `{/// comment}` / `{/  // comment}` from JS bodies that
     * happen to start with `//` (e.g. `{ // js comment }`).
     */
    private fun looksLikeCloserPattern(text: CharSequence, slashPos: Int, bodyEnd: Int): Boolean {
        var j = slashPos + 1
        while (j < bodyEnd && isHorizontalWhitespace(text[j])) j++
        if (j >= bodyEnd) return true
        if (j + 1 < bodyEnd && text[j] == '/' && text[j + 1] == '/') return true
        return false
    }

    /**
     * Locate the offset of an unescaped slash-slash line-comment start within
     * the body range, or null if none. String literals (`'…'` and `"…"`) are
     * skipped so a slash-slash sequence inside a string doesn't trigger.
     *
     * The SkyTemplate compiler permits a trailing line comment inside template
     * tags (PATTERN_VAR's optional comment branch, plus a strip step in the
     * keyword-tag dispatcher). See `SkyTemplateCompiler.php`.
     */
    private fun findLineCommentStart(text: CharSequence, start: Int, end: Int): Int? {
        var i = start
        var inString: Char = ' '
        while (i < end) {
            val c = text[i]
            if (inString != ' ') {
                if (c == '\\' && i + 1 < end) { i += 2; continue }
                if (c == inString) inString = ' '
                i++
                continue
            }
            when (c) {
                '\'', '"' -> { inString = c; i++ }
                '/' -> if (i + 1 < end && text[i + 1] == '/') return i else i++
                '\n', '\r' -> i++   // newline ends search per PATTERN_VAR `[^}\n]*`
                else -> i++
            }
        }
        return null
    }
}
