package com.novaframework.templatelang.sky

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

/**
 * Hand-written lexer for SkyTemplate.
 *
 * State machine:
 *   OUTER     — non-template text, until '{' (not preceded by '$') or '{*'
 *   IN_COMMENT — inside {* ... *}
 *   IN_TAG    — inside { ... }, tokenising tag prefix, keywords, expression tokens
 *
 * The lexer is intentionally permissive — it never fails. Unrecognised characters
 * inside a tag are emitted as BAD_CHARACTER so the editor still highlights them.
 */
class SkyTemplateLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0

    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var currentToken: IElementType? = null

    private var state: Int = STATE_OUTER

    /**
     * Most recent emitted token, excluding WHITE_SPACE_INSIDE / LINE_COMMENT.
     * Drives variable-start detection for `.` so an expression like
     * `{? !.category.name || !.category.code}` correctly classifies the
     * dots after `!` and `||` as SCOPE_LOOP rather than property-access DOT.
     *
     * Resets to `null` on `start()`. When the layered editor highlighter
     * restarts mid-stream (state-only resume), this stays null and the
     * `.`-classification falls through to a conservative character-based
     * walk-back. Same-buffer fresh `start()` (test harness, annotator
     * per-range slicing) is the primary code path and stays accurate.
     */
    private var lastNonWsToken: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.tokenStart = startOffset
        this.tokenEnd = startOffset
        this.state = initialState
        this.lastNonWsToken = null
        this.currentToken = null
        advance()
    }

    override fun getState(): Int = state
    override fun getTokenType(): IElementType? = currentToken
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        // Capture the previously emitted token for context-aware decisions
        // before the next scan overwrites currentToken.
        val prev = currentToken
        if (prev != null && prev !== T.WHITE_SPACE_INSIDE && prev !== T.LINE_COMMENT) {
            lastNonWsToken = prev
        }

        if (tokenEnd >= endOffset) {
            tokenStart = tokenEnd
            currentToken = null
            return
        }
        tokenStart = tokenEnd
        when (state) {
            STATE_OUTER -> scanOuter()
            STATE_IN_COMMENT -> scanComment()
            STATE_IN_TAG -> scanInTag()
            else -> scanOuter()
        }
    }

    // ── OUTER ──────────────────────────────────────────────────────────────────

    private fun scanOuter() {
        // {*comment*} or {tag} (but not ${var})
        var i = tokenStart
        while (i < endOffset) {
            val c = buffer[i]
            if (c == '{' && !precededByDollar(i)) {
                if (i + 1 < endOffset && buffer[i + 1] == '*') {
                    // {* — comment open. If we have preceding text, emit OUTER first.
                    if (i > tokenStart) {
                        tokenEnd = i
                        currentToken = T.OUTER_CONTENT
                        return
                    }
                    tokenEnd = i + 2
                    currentToken = T.COMMENT_OPEN
                    state = STATE_IN_COMMENT
                    return
                }
                // `{\` — standalone escape literal IF no closing `}` appears
                // before the next newline / EOF. With a `}` on the same line
                // we still treat as a regular tag (LBRACE + IN_TAG flow);
                // that case includes `{\}` (literal `{}` output) and
                // `{\hello}` (literal `{hello}` output).
                if (i + 1 < endOffset && buffer[i + 1] == '\\' && !hasCloseBeforeLineEnd(i + 2)) {
                    if (i > tokenStart) {
                        tokenEnd = i
                        currentToken = T.OUTER_CONTENT
                        return
                    }
                    tokenEnd = i + 2
                    currentToken = T.ESCAPE_LITERAL
                    return
                }
                // { — tag open. Emit any prior OUTER first.
                if (i > tokenStart) {
                    tokenEnd = i
                    currentToken = T.OUTER_CONTENT
                    return
                }
                tokenEnd = i + 1
                currentToken = T.LBRACE
                state = STATE_IN_TAG
                return
            }
            // `\}` — standalone escape literal in OUTER state. Emit any
            // preceding OUTER content first.
            if (c == '\\' && i + 1 < endOffset && buffer[i + 1] == '}') {
                if (i > tokenStart) {
                    tokenEnd = i
                    currentToken = T.OUTER_CONTENT
                    return
                }
                tokenEnd = i + 2
                currentToken = T.ESCAPE_LITERAL
                return
            }
            i++
        }
        tokenEnd = endOffset
        currentToken = T.OUTER_CONTENT
    }

    /**
     * True if a `}` character appears at or after [from] before the
     * next newline or end-of-buffer. Used to disambiguate `{\` standalone
     * from `{\hello}` regular escape directives.
     */
    private fun hasCloseBeforeLineEnd(from: Int): Boolean {
        var i = from
        while (i < endOffset) {
            val c = buffer[i]
            if (c == '}') return true
            if (c == '\n') return false
            i++
        }
        return false
    }

    private fun precededByDollar(offset: Int): Boolean =
        offset > 0 && buffer[offset - 1] == '$'

    // ── COMMENT ────────────────────────────────────────────────────────────────

    private fun scanComment() {
        var i = tokenStart
        while (i < endOffset - 1) {
            if (buffer[i] == '*' && buffer[i + 1] == '}') {
                if (i > tokenStart) {
                    tokenEnd = i
                    currentToken = T.COMMENT_CONTENT
                    return
                }
                tokenEnd = i + 2
                currentToken = T.COMMENT_CLOSE
                state = STATE_OUTER
                return
            }
            i++
        }
        // unterminated comment — consume rest as content
        tokenEnd = endOffset
        currentToken = T.COMMENT_CONTENT
    }

    // ── IN_TAG ─────────────────────────────────────────────────────────────────

    private fun scanInTag() {
        val first = buffer[tokenStart]

        // Closing brace?
        if (first == '}') {
            tokenEnd = tokenStart + 1
            currentToken = T.RBRACE
            state = STATE_OUTER
            return
        }

        // Whitespace
        if (first.isWhitespace()) {
            var i = tokenStart
            while (i < endOffset && buffer[i].isWhitespace()) i++
            tokenEnd = i
            currentToken = T.WHITE_SPACE_INSIDE
            return
        }

        // Tag prefix: only at the very first non-whitespace position after '{'
        // We approximate "first position" by looking back to the most recent LBRACE.
        if (T.isTagPrefixChar(first) && atTagStart()) {
            // ?: is a 2-char prefix
            if (first == '?' && peek(1) == ':') {
                tokenEnd = tokenStart + 2
                currentToken = T.TAG_PREFIX
                return
            }
            tokenEnd = tokenStart + 1
            currentToken = T.TAG_PREFIX
            return
        }

        // Strings
        if (first == '\'' || first == '"') {
            scanString(first)
            return
        }

        // Numbers
        if (first.isDigit()) {
            var i = tokenStart + 1
            while (i < endOffset && (buffer[i].isDigit() || buffer[i] == '.')) i++
            tokenEnd = i
            currentToken = T.NUMBER
            return
        }

        // Identifier (keyword / variable name / function name)
        if (first == '_' || first.isLetter()) {
            var i = tokenStart + 1
            while (i < endOffset) {
                val c = buffer[i]
                if (c == '_' || c.isLetterOrDigit()) i++ else break
            }
            tokenEnd = i
            val word = buffer.subSequence(tokenStart, tokenEnd).toString()
            // Keyword match is case-insensitive — `SkyTemplateCompiler::PATTERN_TAG`
            // uses the `/i` flag, so `{LOOP}`, `{Loop}`, `{loop}` are all
            // tokenised as TAG_KEYWORD and rendered with the keyword colour.
            // The starts-with `_` check below stays case-sensitive: SCOPE_RESERVED
            // names (`_index`, `_GET`, …) are conventionally lower-snake / upper.
            currentToken = when {
                word.lowercase() in T.KEYWORDS && atTagStart() -> T.TAG_KEYWORD
                word.startsWith("_") -> T.SCOPE_RESERVED
                // `c` followed immediately by a literal `.` is the SkyTemplate
                // constant-scope marker (`{c.NAME}`). After identifier scan
                // finishes, `tokenEnd` points one past the last char of the
                // identifier (= position of the candidate `.`). A space
                // between `c` and `.` breaks the marker — same as the
                // SkyTemplate compiler regex.
                word == "c" && tokenEnd < endOffset && buffer[tokenEnd] == '.' -> T.SCOPE_CONST
                else -> T.IDENTIFIER
            }
            return
        }

        // Punctuation / operators
        when (first) {
            '.' -> {
                // Loop-scope marker (one or more dots, scope depth = dot
                // count) at the start of a variable expression; mirrors
                // SkyTemplate's `(?<scope>_|\.+|c\.)?` regex. Dot count:
                //   `.var`     → current loop
                //   `..var`    → parent loop
                //   `...var`   → grandparent loop
                // Mid-tag, a single `.` between identifiers is property
                // access (DOT) — `{var.field}`, `{=foo().name}` keep the
                // dim "dot" colour.
                //
                // PATTERN_VAR can match anywhere inside an expression, so
                // a dot is also a variable scope marker after operators
                // like `!`, `||`, `&&`, `+`, `,` etc. — see compiler's
                // `parseExpressionCallback` regex `[^\w\h.]*\h*PATTERN_VAR`.
                var i = tokenStart
                while (i < endOffset && buffer[i] == '.') i++
                val dotCount = i - tokenStart
                val atVariableStart = dotCount >= 2 || atVariableStart()
                if (atVariableStart) {
                    tokenEnd = i
                    currentToken = T.SCOPE_LOOP
                    return
                }
                // Single mid-tag `.` — disambiguate property access from
                // string concatenation by whitespace adjacency:
                //   `var.key`        → tight   ⇒ DOT (property/array access)
                //   `var . var2`     → spaced  ⇒ OPERATOR (string concat)
                //   `=foo() . bar()` → spaced  ⇒ OPERATOR (string concat)
                //   `=fn().foo`      → tight   ⇒ DOT (property on call result)
                //
                // Buffer peek uses `tokenStart > 0` (matches `precededByDollar`)
                // rather than `>= startOffset`, so incremental re-lex resuming
                // mid-tag still sees the actual char before — the buffer always
                // contains the full document, the slice only constrains what
                // we LEX. Without this, a mid-tag re-lex starting at the `.`
                // would default `before` to whitespace and incorrectly flip
                // tight property-access dots to OPERATOR.
                val before = if (tokenStart > 0) buffer[tokenStart - 1] else ' '
                val after = if (tokenStart + 1 < endOffset) buffer[tokenStart + 1] else ' '
                tokenEnd = tokenStart + 1
                currentToken = if (before.isWhitespace() || after.isWhitespace()) T.OPERATOR else T.DOT
                return
            }
            '-' -> {
                if (peek(1) == '>') {
                    tokenEnd = tokenStart + 2
                    currentToken = T.ARROW
                    return
                }
                tokenEnd = tokenStart + 1
                currentToken = T.OPERATOR
                return
            }
            '?' -> {
                if (peek(1) == '-' && peek(2) == '>') {
                    tokenEnd = tokenStart + 3
                    currentToken = T.ARROW
                    return
                }
                tokenEnd = tokenStart + 1
                currentToken = T.OPERATOR
                return
            }
            ':' -> {
                if (peek(1) == ':') {
                    tokenEnd = tokenStart + 2
                    currentToken = T.DBL_COLON
                    return
                }
                // Single `:` — distinct token (COLON) so the ref-detector
                // can recognise PHP-8 named-arg syntax. Highlighted as an
                // operator via the same mapping in SyntaxHighlighter.
                tokenEnd = tokenStart + 1
                currentToken = T.COLON
                return
            }
            '|' -> {
                if (peek(1) == '|') {
                    tokenEnd = tokenStart + 2
                    currentToken = T.OPERATOR
                    return
                }
                tokenEnd = tokenStart + 1
                currentToken = T.PIPE
                return
            }
            '@' -> { tokenEnd = tokenStart + 1; currentToken = T.AT; return }
            '#' -> { tokenEnd = tokenStart + 1; currentToken = T.HASH; return }
            '=' -> {
                if (peek(1) == '=') {
                    tokenEnd = tokenStart + (if (peek(2) == '=') 3 else 2)
                    currentToken = T.OPERATOR
                    return
                }
                tokenEnd = tokenStart + 1
                currentToken = T.EQ
                return
            }
            '!' -> {
                if (peek(1) == '=') {
                    tokenEnd = tokenStart + (if (peek(2) == '=') 3 else 2)
                    currentToken = T.OPERATOR
                    return
                }
                tokenEnd = tokenStart + 1
                currentToken = T.OPERATOR
                return
            }
            '(' -> { tokenEnd = tokenStart + 1; currentToken = T.LPAREN; return }
            ')' -> { tokenEnd = tokenStart + 1; currentToken = T.RPAREN; return }
            '[' -> { tokenEnd = tokenStart + 1; currentToken = T.LBRACKET; return }
            ']' -> { tokenEnd = tokenStart + 1; currentToken = T.RBRACKET; return }
            ',' -> { tokenEnd = tokenStart + 1; currentToken = T.COMMA; return }
            '\\' -> { tokenEnd = tokenStart + 1; currentToken = T.NS_SEP; return }
            '+', '*', '%', '<', '>', '&', '^', '~' -> {
                tokenEnd = tokenStart + 1
                currentToken = T.OPERATOR
                return
            }
            '/' -> {
                // // line comment until end of tag or newline
                if (peek(1) == '/') {
                    var i = tokenStart + 2
                    while (i < endOffset && buffer[i] != '\n' && buffer[i] != '}') i++
                    tokenEnd = i
                    currentToken = T.LINE_COMMENT
                    return
                }
                tokenEnd = tokenStart + 1
                currentToken = T.OPERATOR
                return
            }
        }

        // Unrecognised
        tokenEnd = tokenStart + 1
        currentToken = T.BAD_CHARACTER
    }

    private fun scanString(quote: Char) {
        var i = tokenStart + 1
        while (i < endOffset) {
            val c = buffer[i]
            if (c == '\\' && i + 1 < endOffset) { i += 2; continue }
            if (c == quote) { i++; break }
            if (c == '\n') break
            i++
        }
        tokenEnd = i
        currentToken = T.STRING
    }

    private fun peek(delta: Int): Char =
        if (tokenStart + delta < endOffset) buffer[tokenStart + delta] else ' '

    /**
     * Are we at the beginning of the tag (just past `{` or `{ `)?
     * Walks backwards to the most recent `{` skipping whitespace.
     */
    private fun atTagStart(): Boolean {
        var i = tokenStart - 1
        while (i >= startOffset && buffer[i].isWhitespace()) i--
        return i >= startOffset && buffer[i] == '{'
    }

    /**
     * Are we at the beginning of a variable expression — either right
     * after `{` (with optional whitespace), right after a tag-prefix
     * char (`{?…`, `{=…`, `{?:…`, etc.), OR right after an operator
     * inside an expression (`!.foo`, `a || .foo`, `fn(.foo)`)? Used to
     * disambiguate loop-scope `.` markers from property-access `.`:
     *   `{.name}`               → variable start ⇒ SCOPE_LOOP
     *   `{?.name}`              → after `?` prefix, variable start ⇒ SCOPE_LOOP
     *   `{? !.name}`            → after `!` operator, variable start ⇒ SCOPE_LOOP
     *   `{? a || .b}`           → after `||`, variable start ⇒ SCOPE_LOOP
     *   `{var.name}`            → after identifier, NOT variable start ⇒ DOT
     *   `{=foo().name}`         → after RPAREN, NOT variable start ⇒ DOT
     *
     * Primary path uses [lastNonWsToken] (state-based, accurate). When the
     * layered editor highlighter restarts mid-stream the state has been
     * reset, so we fall back to a buffer walk-back that covers the same
     * cases char-by-char.
     */
    private fun atVariableStart(): Boolean {
        val last = lastNonWsToken
        if (last != null) {
            return when (last) {
                // Tag-boundary openers
                T.LBRACE, T.TAG_PREFIX, T.TAG_KEYWORD,
                // Expression-internal boundaries (operators, separators, openers)
                T.OPERATOR, T.EQ, T.COMMA, T.LPAREN, T.LBRACKET,
                T.PIPE, T.NS_SEP, T.COLON -> true
                // Continuation tokens — `.` is property access, not scope marker
                else -> false
            }
        }
        return atVariableStartByChars()
    }

    /**
     * Buffer walk-back fallback for [atVariableStart] when the lexer
     * state was reset (incremental re-lex from a saved state with no
     * captured prior token). Walks back through whitespace, tag-prefix
     * chars, and other operator-like chars that can precede a fresh
     * variable expression.
     */
    private fun atVariableStartByChars(): Boolean {
        var i = tokenStart - 1
        while (i >= startOffset) {
            val c = buffer[i]
            when {
                c == '{' -> return true
                c.isWhitespace() -> i--
                // Walk through tag-prefix chars (`?:`, `&?`, etc.) at tag
                // start. Mid-expression these chars (e.g. `&`, `=`, `+`) are
                // operators that also boundary-mark a fresh variable, so
                // skipping vs returning true converges on the same answer.
                T.isTagPrefixChar(c) -> i--
                // Operator / punctuation that boundary-marks a fresh variable.
                c == '!' || c == '(' || c == '[' || c == ',' || c == ';' ||
                c == '<' || c == '>' || c == '|' || c == '^' || c == '~' ||
                c == '*' || c == '-' -> return true
                // Word chars / property-access continuation / closers — NOT
                // a fresh variable start.
                else -> return false
            }
        }
        return false
    }

    companion object {
        const val STATE_OUTER = 0
        const val STATE_IN_TAG = 1
        const val STATE_IN_COMMENT = 2
    }
}
