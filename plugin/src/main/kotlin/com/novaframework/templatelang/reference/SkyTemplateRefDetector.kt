package com.novaframework.templatelang.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import com.novaframework.templatelang.sky.SkyTemplateLexer
import com.novaframework.templatelang.sky.SkyTemplateRanges
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

/**
 * Pure detection of PHP-symbol references inside SkyTemplate source. No
 * IntelliJ infrastructure required — testable in isolation.
 *
 * Modelled after how the SkyTemplate compiler interprets directives:
 *
 *   ┌───────────────────────────┬───────────────────────────────────────────┐
 *   │ Construct                 │ What we emit                              │
 *   ├───────────────────────────┼───────────────────────────────────────────┤
 *   │ {=foo()}                  │ FUNCTION (expression-context tag)         │
 *   │ {=\Ns\foo()}              │ FUNCTION (absolute FQN)                   │
 *   │ {?foo()}                  │ FUNCTION (if condition)                   │
 *   │ {if foo()}                │ FUNCTION (keyword, expression body)       │
 *   │ {=Cls::method()}          │ CLASS + METHOD                            │
 *   │ {=Cls::CONST}             │ CLASS + CLASS_CONSTANT                    │
 *   │ {c.NAME}                  │ CONSTANT                                  │
 *   │ {c.\Ns\NAME}              │ CONSTANT (absolute)                       │
 *   │ {c.Cls::CONST}            │ CLASS + CLASS_CONSTANT                    │
 *   │ {var\|trim}               │ FUNCTION (pipe)                           │
 *   │ {var\|sprintf=%05d, ##}   │ FUNCTION (pipe with args)                 │
 *   │ {var\|Cls::method}        │ CLASS + METHOD (pipe, no parens)          │
 *   │ {var\|Ns\Cls::method}     │ CLASS + METHOD (pipe, namespaced)         │
 *   └───────────────────────────┴───────────────────────────────────────────┘
 *
 * Plain `{foo()}` is **NOT** detected because SkyTemplate's `parseVar` does
 * not recognise function-call syntax — to invoke, the directive must use a
 * tag prefix (`=`, `?`, `:`, `?:`, `;`) or expression keyword (`if`, `else`,
 * `foreach`, `for`, `while`).
 *
 * Identifiers after a `.` are object property/method access (e.g. `user.name`,
 * `obj.method()`) and are intentionally skipped — those map to template
 * variables / loop scope; resolving them needs `assign(...)` tracking (M5).
 */
object SkyTemplateRefDetector {

    enum class Kind { FUNCTION, CLASS, METHOD, CONSTANT, CLASS_CONSTANT, PARAMETER_NAME }

    /**
     * @property kind        what the symbol is.
     * @property rangeInHost text range within the input `text`.
     * @property nameInSource literal identifier as written; may include leading `\` for absolute FQNs.
     * @property classNameInSource for METHOD / CLASS_CONSTANT: the class identifier text.
     * @property callTargetName for PARAMETER_NAME: simple name of the function /
     *           method whose parameter list this argument belongs to. The
     *           PhpReference layer uses this to locate the callee and then the
     *           parameter PSI by [nameInSource].
     * @property callTargetClass for PARAMETER_NAME on a static-method call
     *           (`Cls::method(name: $x)`), the class identifier text as written.
     *           `null` for a plain function call (`foo(name: $x)`) or a pipe
     *           filter (`{var|fmt=name=value}`) — pipe filters always invoke
     *           free functions, never methods.
     */
    data class Ref(
        val kind: Kind,
        val rangeInHost: TextRange,
        val nameInSource: String,
        val classNameInSource: String? = null,
        val callTargetName: String? = null,
        val callTargetClass: String? = null,
    )

    fun detect(text: CharSequence, baseOffset: Int = 0): List<Ref> {
        if (text.isEmpty() || '{' !in text) return emptyList()

        // Find template-tag ranges using the depth-tracked brace-pair finder.
        // This correctly handles nested CSS / JS / template constructs like
        // `<style>.foo { color: {color}; }</style>` where the lexer's flat
        // LBRACE/RBRACE pairing would close the outer `{` against the wrong `}`.
        val templateRanges = SkyTemplateRanges.computeTemplateRanges(text)
        if (templateRanges.isEmpty()) return emptyList()

        val refs = ArrayList<Ref>()
        for (range in templateRanges) {
            // Skip comment ranges — `{*…*}` never contains PHP references.
            val open = range.startOffset
            val close = range.endOffset
            if (open + 1 < close && text[open + 1] == '*') continue
            // Wrapped `<!--{ … }-->` — skip the wrapper bytes when lexing.
            val tagOpen = findTagOpen(text, open, close)
            val tagClose = findTagClose(text, open, close)
            if (tagOpen < 0 || tagClose < 0 || tagOpen >= tagClose) continue
            // Lex just the `{ … }` slice. Tokens have offsets relative to the slice;
            // baseOffset shifts emitted ranges back into the original document.
            val slice = text.subSequence(tagOpen, tagClose + 1)
            val tokens = lexAll(slice)
            if (tokens.isEmpty()) continue
            val first = tokens.firstOrNull { it.type === T.LBRACE } ?: continue
            val last = tokens.indexOfLast { it.type === T.RBRACE }
            if (last < 0) continue
            val firstIdx = tokens.indexOfFirst { it === first }
            scanTagBody(slice, tokens, firstIdx + 1, last, baseOffset + tagOpen, refs)
        }
        return refs
    }

    /** Skip leading `<!--` dashes / whitespace; return offset of the `{` that opens the tag. */
    private fun findTagOpen(text: CharSequence, start: Int, end: Int): Int {
        for (i in start until end) {
            if (text[i] == '{') return i
        }
        return -1
    }

    /** Find offset of the LAST `}` in [start, end) — closes the wrapped or plain form. */
    private fun findTagClose(text: CharSequence, start: Int, end: Int): Int {
        for (i in end - 1 downTo start) {
            if (text[i] == '}') return i
        }
        return -1
    }

    // ── tag-body scanner ──────────────────────────────────────────────────────────

    private fun scanTagBody(
        text: CharSequence,
        tokens: List<Tok>,
        from: Int,
        toExclusive: Int,
        baseOffset: Int,
        refs: MutableList<Ref>,
    ) {
        val ctx = determineContext(text, tokens, from, toExclusive)
        var i = ctx.bodyStart
        val isExpr = ctx.isExpression

        while (i < toExclusive) {
            i = skipWs(tokens, i, toExclusive)
            if (i >= toExclusive) break
            val t = tokens[i]
            i = when (t.type) {
                T.PIPE -> handlePipe(text, tokens, i + 1, toExclusive, baseOffset, refs)
                // SCOPE_CONST is the `c` in `{c.NAME}`. The lexer only emits this
                // when a literal `.` follows, so we always have a DOT token next
                // — skip past it before delegating to handleConstantScope, which
                // expects to see the constant's first IDENT.
                T.SCOPE_CONST -> handleConstantScope(text, tokens, skipDot(tokens, i + 1, toExclusive), toExclusive, baseOffset, refs)
                T.IDENTIFIER -> {
                    if (isAfterDot(tokens, i, from)) {
                        i + 1  // property / method access on a variable
                    } else {
                        val word = text.substring(t.start, t.end)
                        // Defensive fallback: as of 0.5.10 the lexer classifies
                        // `c` followed by a literal `.` as SCOPE_CONST, so this
                        // branch is normally unreachable for `{c.NAME}`. Kept
                        // for safety in case the lexer ever emits IDENTIFIER
                        // here (e.g. exotic edge cases or future refactors).
                        if (word == "c" && nextIsDot(tokens, i + 1, toExclusive)) {
                            handleConstantScope(
                                text, tokens, skipDot(tokens, i + 1, toExclusive),
                                toExclusive, baseOffset, refs
                            )
                        } else if (isExpr) {
                            handleExpressionStart(text, tokens, i, toExclusive, baseOffset, refs)
                        } else {
                            i + 1
                        }
                    }
                }
                T.NS_SEP -> if (isExpr) {
                    handleExpressionStart(text, tokens, i, toExclusive, baseOffset, refs)
                } else {
                    i + 1
                }
                else -> i + 1
            }
        }
    }

    private data class TagContext(val isExpression: Boolean, val bodyStart: Int)

    /**
     * Inspect the tag's leading prefix / keyword to decide whether the body is
     * an expression (top-level function calls etc. allowed) or something else
     * (variable name, file path, block name).
     */
    private fun determineContext(
        text: CharSequence,
        tokens: List<Tok>,
        from: Int,
        toExclusive: Int,
    ): TagContext {
        var i = skipWs(tokens, from, toExclusive)
        if (i >= toExclusive) return TagContext(false, i)
        val t = tokens[i]
        if (t.type === T.TAG_PREFIX) {
            return when (val prefix = text.substring(t.start, t.end)) {
                // Expression-context prefixes — body is a PHP expression that
                // can contain calls, static accesses, etc.
                //   `=`  raw output      `{=expr}`
                //   `?`  if condition    `{?cond}`
                //   `:`  else / case     `{:expr}` / `{:case}`
                //   `?:` elvis           `{?:expr}`
                //   `;`  php             `{;stmt}`
                //   `@`  loop alias      `{@expr}` — iterable can be `Cls::method()` etc.
                //   `%`  loop alias      `{%expr}` — same as `@`
                "=", "?", ":", "?:", ";", "@", "%" -> TagContext(true, i + 1)
                // `\` is the escape directive in SkyTemplate; it does NOT enter
                // expression context. Absolute-FQN function calls must be wrapped
                // in an expression-context prefix, e.g. `{=\App\foo()}`.
                else -> TagContext(false, i + 1)
            }
        }
        if (t.type === T.TAG_KEYWORD) {
            // Keywords are case-insensitive in SkyTemplate
            // (PATTERN_TAG uses /i). Compare lowercased so `{LOOP …}`
            // resolves identical context as `{loop …}`.
            val keyword = text.substring(t.start, t.end).lowercase()
            val isExpr = keyword in EXPRESSION_KEYWORDS
            return TagContext(isExpr, i + 1)
        }
        // No prefix / keyword: plain `{var}` style. Pipe filters and `c.`
        // constants still work; top-level identifiers are NOT calls.
        return TagContext(false, i)
    }

    private val EXPRESSION_KEYWORDS = setOf(
        "if", "else", "elseif", "foreach", "for", "while", "loop", "each",
    )

    // ── pipe ──────────────────────────────────────────────────────────────────────

    /**
     * `|` IDENT … or `|` Ns\Cls::method.
     * Pipe filters never use parentheses; an `=` after introduces filter args.
     */
    private fun handlePipe(
        text: CharSequence,
        tokens: List<Tok>,
        from: Int,
        toExclusive: Int,
        baseOffset: Int,
        refs: MutableList<Ref>,
    ): Int {
        val i = skipWs(tokens, from, toExclusive)
        val parsed = parseQualifiedIdent(text, tokens, i, toExclusive) ?: return i
        val afterIdent = skipWs(tokens, parsed.nextIdx, toExclusive)

        if (afterIdent < toExclusive && tokens[afterIdent].type === T.DBL_COLON) {
            // `|Cls::method` — class + method (always a method since pipe invokes).
            // Phase 1 spec D-3: pipe `Cls::method=name=value` named args are
            // out-of-scope for now; we still emit CLASS + METHOD refs and
            // intentionally do NOT scan a trailing `=…` for named args.
            refs += Ref(
                kind = Kind.CLASS,
                rangeInHost = parsed.lastTok.toRange(baseOffset),
                nameInSource = parsed.fqn,
            )
            var j = afterIdent + 1
            j = skipWs(tokens, j, toExclusive)
            if (j < toExclusive && tokens[j].type === T.IDENTIFIER) {
                val memberTok = tokens[j]
                refs += Ref(
                    kind = Kind.METHOD,
                    rangeInHost = memberTok.toRange(baseOffset),
                    nameInSource = text.substring(memberTok.start, memberTok.end),
                    classNameInSource = parsed.fqn,
                )
                return j + 1
            }
            return j
        }

        refs += Ref(
            kind = Kind.FUNCTION,
            rangeInHost = parsed.lastTok.toRange(baseOffset),
            nameInSource = parsed.fqn,
        )

        // Pipe filter args: `|fn=arg1, name=value, ##, name2=value2`.
        // The `=` after the filter name introduces csv-separated args. A
        // token shaped `<phpIdent>=<value>` (with the `=` at top level —
        // not inside quotes / parens / brackets) is a PHP-8 named arg.
        // We emit PARAMETER_NAME for the `<phpIdent>` portion.
        if (afterIdent < toExclusive && tokens[afterIdent].type === T.EQ) {
            val callSimple = simpleName(parsed.fqn)
            scanPipeFilterArgs(
                text, tokens, afterIdent + 1, toExclusive, baseOffset, refs,
                callTarget = callSimple,
            )
        }
        return parsed.nextIdx
    }

    /**
     * Scan filter arguments after `|fn=`. Tokens are split into csv buckets
     * by top-level COMMA; within each bucket, an IDENTIFIER followed (after
     * optional whitespace) by a top-level EQ is a named-arg name.
     *
     * Top-level here means depth 0 — parens / brackets within a value are
     * skipped. The pipe arg list ends at the next PIPE / RBRACE / RPAREN.
     * We stop scanning at any of those.
     *
     * The HASH-positional placeholder (`##`) is recognised the same way the
     * compiler does: a sequence of two HASH tokens (the lexer emits them as
     * separate `T.HASH` chars). It is NEVER a named arg.
     */
    private fun scanPipeFilterArgs(
        text: CharSequence,
        tokens: List<Tok>,
        from: Int,
        toExclusive: Int,
        baseOffset: Int,
        refs: MutableList<Ref>,
        callTarget: String,
    ) {
        var i = from
        // Process csv-separated buckets. Each iteration consumes one bucket.
        while (i < toExclusive) {
            i = skipWs(tokens, i, toExclusive)
            if (i >= toExclusive) break
            val t = tokens[i]
            // Pipe args end at the next PIPE (chained filter) or RBRACE.
            if (t.type === T.PIPE || t.type === T.RBRACE) break
            // Top-level COMMA separates buckets — just consume and continue.
            if (t.type === T.COMMA) { i++; continue }
            // Look for `<IDENT> =` at the start of the bucket — that's a
            // named arg. The `=` here is the SkyTemplate EQ token, same one
            // used as the filter-args separator; inside a bucket it can only
            // be a named-arg `=` because the bucket already started after
            // the filter's leading EQ.
            //
            // L-003 (Phase 2): if the EQ is immediately followed by another
            // EQ (i.e. `count==2`, `a===b`), treat the bucket as a
            // comparison expression — NOT a named arg. `>=`, `<=`, `!=`,
            // `<>` already fail the IDENT/EQ shape because their leading
            // operator char isn't part of the identifier.
            if (t.type === T.IDENTIFIER) {
                val next = skipWs(tokens, i + 1, toExclusive)
                if (next < toExclusive && tokens[next].type === T.EQ) {
                    val afterEq = next + 1
                    val isComparison = afterEq < toExclusive &&
                        tokens[afterEq].type === T.EQ
                    if (!isComparison) {
                        refs += Ref(
                            kind = Kind.PARAMETER_NAME,
                            rangeInHost = t.toRange(baseOffset),
                            nameInSource = text.substring(t.start, t.end),
                            callTargetName = callTarget,
                            callTargetClass = null,
                        )
                        // Skip past `=` and let the rest of the bucket be
                        // scanned as a value expression (function calls inside
                        // the value still surface their own refs).
                        i = next + 1
                        continue
                    }
                }
            }
            // Skip this token — it's a positional value char or the start
            // of a complex value. Recurse into nested constructs only if
            // they could contain refs (paren / class). For simplicity and
            // since pipe args are flat in practice, we just advance.
            i++
        }
    }

    // ── c. constant scope ─────────────────────────────────────────────────────────

    /**
     * After `c.` (or SCOPE_CONST + DOT): IDENT is a constant; if followed by
     * `::IDENT` it is a class constant access.
     */
    private fun handleConstantScope(
        text: CharSequence,
        tokens: List<Tok>,
        from: Int,
        toExclusive: Int,
        baseOffset: Int,
        refs: MutableList<Ref>,
    ): Int {
        val i = skipWs(tokens, from, toExclusive)
        val parsed = parseQualifiedIdent(text, tokens, i, toExclusive) ?: return i
        val afterIdent = skipWs(tokens, parsed.nextIdx, toExclusive)

        if (afterIdent < toExclusive && tokens[afterIdent].type === T.DBL_COLON) {
            // `c.Cls::CONST` — class + class constant.
            refs += Ref(
                kind = Kind.CLASS,
                rangeInHost = parsed.lastTok.toRange(baseOffset),
                nameInSource = parsed.fqn,
            )
            var j = afterIdent + 1
            j = skipWs(tokens, j, toExclusive)
            if (j < toExclusive && tokens[j].type === T.IDENTIFIER) {
                val memberTok = tokens[j]
                refs += Ref(
                    kind = Kind.CLASS_CONSTANT,
                    rangeInHost = memberTok.toRange(baseOffset),
                    nameInSource = text.substring(memberTok.start, memberTok.end),
                    classNameInSource = parsed.fqn,
                )
                return j + 1
            }
            return j
        }

        refs += Ref(
            kind = Kind.CONSTANT,
            rangeInHost = parsed.lastTok.toRange(baseOffset),
            nameInSource = parsed.fqn,
        )
        return parsed.nextIdx
    }

    // ── expression-context: function call / class member ─────────────────────────

    private fun handleExpressionStart(
        text: CharSequence,
        tokens: List<Tok>,
        from: Int,
        toExclusive: Int,
        baseOffset: Int,
        refs: MutableList<Ref>,
    ): Int {
        val parsed = parseQualifiedIdent(text, tokens, from, toExclusive) ?: return from + 1
        val afterIdent = skipWs(tokens, parsed.nextIdx, toExclusive)
        return when {
            afterIdent < toExclusive && tokens[afterIdent].type === T.LPAREN -> {
                refs += Ref(
                    kind = Kind.FUNCTION,
                    rangeInHost = parsed.lastTok.toRange(baseOffset),
                    nameInSource = parsed.fqn,
                )
                // Scan the argument list to surface PARAMETER_NAME refs and
                // recurse into nested expressions (so `foo(bar(x: 1))` also
                // emits `bar` as FUNCTION). Resume scanning past the `)` so
                // the outer walker doesn't re-enter the same arg list.
                val callSimple = simpleName(parsed.fqn)
                val rparen = scanArgumentList(
                    text, tokens, afterIdent + 1, toExclusive, baseOffset, refs,
                    callTarget = callSimple,
                    callClass = null,
                )
                if (rparen < toExclusive) rparen + 1 else rparen
            }
            afterIdent < toExclusive && tokens[afterIdent].type === T.DBL_COLON -> {
                refs += Ref(
                    kind = Kind.CLASS,
                    rangeInHost = parsed.lastTok.toRange(baseOffset),
                    nameInSource = parsed.fqn,
                )
                var j = afterIdent + 1
                j = skipWs(tokens, j, toExclusive)
                if (j < toExclusive && tokens[j].type === T.IDENTIFIER) {
                    val memberTok = tokens[j]
                    val afterMember = skipWs(tokens, j + 1, toExclusive)
                    val isMethod = afterMember < toExclusive && tokens[afterMember].type === T.LPAREN
                    refs += Ref(
                        kind = if (isMethod) Kind.METHOD else Kind.CLASS_CONSTANT,
                        rangeInHost = memberTok.toRange(baseOffset),
                        nameInSource = text.substring(memberTok.start, memberTok.end),
                        classNameInSource = parsed.fqn,
                    )
                    if (isMethod) {
                        // Static method call — scan args for named-arg refs.
                        val methodName = text.substring(memberTok.start, memberTok.end)
                        val rparen = scanArgumentList(
                            text, tokens, afterMember + 1, toExclusive, baseOffset, refs,
                            callTarget = methodName,
                            callClass = parsed.fqn,
                        )
                        if (rparen < toExclusive) rparen + 1 else rparen
                    } else {
                        j + 1
                    }
                } else j
            }
            else -> parsed.nextIdx
        }
    }

    /**
     * Scan a `(...)` argument list starting AFTER the opening LPAREN.
     * At depth 0 within this list:
     *   - IDENTIFIER followed (after whitespace) by COLON (single, not `::`)
     *     emits a PARAMETER_NAME ref for the identifier.
     *   - Other identifier-led constructs are dispatched to the standard
     *     expression walker (`handleExpressionStart`) so nested
     *     `foo(bar: x, baz(qux: 1))` correctly tags `bar`/`qux` as
     *     PARAMETER_NAME and `baz` as FUNCTION.
     *
     * Returns the index of the matching RPAREN, or [toExclusive] if the
     * list is unterminated. The caller is expected to advance past `)`.
     */
    private fun scanArgumentList(
        text: CharSequence,
        tokens: List<Tok>,
        from: Int,
        toExclusive: Int,
        baseOffset: Int,
        refs: MutableList<Ref>,
        callTarget: String,
        callClass: String?,
    ): Int {
        var i = from
        while (i < toExclusive) {
            val t = tokens[i]
            when (t.type) {
                T.RPAREN -> return i
                T.WHITE_SPACE_INSIDE, T.COMMA, T.LINE_COMMENT -> { i++ }
                T.IDENTIFIER -> {
                    // PHP-8 named-argument: IDENT followed by `:` (single, not `::`).
                    // Spec D-1 prev guard: the IDENT must begin an argument slot —
                    // its previous semantic token must be the opening LPAREN
                    // (i.e. `prevIdx < from`, since `from` sits one past LPAREN)
                    // or a COMMA (slot separator). Any other preceding token —
                    // notably `?` of a ternary `cond ? IDENT : alt` — means the
                    // colon belongs to a different construct and the IDENT is
                    // not a named-arg.
                    val next = skipWs(tokens, i + 1, toExclusive)
                    val isColonNext = next < toExclusive && tokens[next].type === T.COLON
                    val isNamedArgSlot = if (!isColonNext) false else {
                        val prevIdx = lastSemanticBefore(tokens, i, from)
                        prevIdx < from ||
                            tokens[prevIdx].type === T.LPAREN ||
                            tokens[prevIdx].type === T.COMMA
                    }
                    if (isNamedArgSlot) {
                        refs += Ref(
                            kind = Kind.PARAMETER_NAME,
                            rangeInHost = t.toRange(baseOffset),
                            nameInSource = text.substring(t.start, t.end),
                            callTargetName = callTarget,
                            callTargetClass = callClass,
                        )
                        // Advance past the `:` so the value expression after it
                        // is scanned in its own right (allowing nested calls).
                        i = next + 1
                    } else {
                        // Hand off to the expression walker for FUNCTION /
                        // CLASS / METHOD / CONSTANT detection within the value.
                        // Skip property access (`obj.prop`) the same way the
                        // top-level walker does.
                        i = if (isAfterDot(tokens, i, from)) {
                            i + 1
                        } else {
                            handleExpressionStart(text, tokens, i, toExclusive, baseOffset, refs)
                        }
                    }
                }
                T.SCOPE_CONST -> {
                    // `c.NAME` inside an arg — same handling as top-level.
                    i = handleConstantScope(
                        text, tokens, skipDot(tokens, i + 1, toExclusive),
                        toExclusive, baseOffset, refs,
                    )
                }
                T.NS_SEP -> {
                    // Absolute FQN start: `\Cls::method(...)` etc.
                    i = handleExpressionStart(text, tokens, i, toExclusive, baseOffset, refs)
                }
                T.LPAREN -> {
                    // Nested parens inside an argument value (e.g. `foo((bool)$x)`)
                    // — recurse so nested commas don't end the outer arg list.
                    val inner = scanArgumentList(
                        text, tokens, i + 1, toExclusive, baseOffset, refs,
                        callTarget = callTarget, callClass = callClass,
                    )
                    i = if (inner < toExclusive) inner + 1 else inner
                }
                T.LBRACKET -> {
                    // Skip past the matching `]` so a comma inside `[a, b]`
                    // doesn't fool the named-arg scanner. We do not recurse
                    // — array literals never carry named args.
                    var depth = 1
                    var j = i + 1
                    while (j < toExclusive && depth > 0) {
                        when (tokens[j].type) {
                            T.LBRACKET -> depth++
                            T.RBRACKET -> depth--
                        }
                        j++
                    }
                    i = j
                }
                T.PIPE -> {
                    // Pipe inside a paren arg is rare but possible — delegate.
                    i = handlePipe(text, tokens, i + 1, toExclusive, baseOffset, refs)
                }
                else -> { i++ }
            }
        }
        return toExclusive
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private data class Tok(val type: IElementType, val start: Int, val end: Int) {
        fun toRange(baseOffset: Int) = TextRange(baseOffset + start, baseOffset + end)
    }
    private data class Parsed(val fqn: String, val lastTok: Tok, val nextIdx: Int)

    private fun parseQualifiedIdent(
        text: CharSequence,
        tokens: List<Tok>,
        from: Int,
        toExclusive: Int,
    ): Parsed? {
        if (from >= toExclusive) return null
        val sb = StringBuilder()
        var i = from
        if (tokens[i].type === T.NS_SEP) {
            sb.append('\\')
            i++
        }
        if (i >= toExclusive || tokens[i].type !== T.IDENTIFIER) return null
        var lastIdent = tokens[i]
        sb.append(text.substring(lastIdent.start, lastIdent.end))
        i++
        while (i < toExclusive && tokens[i].type === T.NS_SEP) {
            i++
            if (i >= toExclusive || tokens[i].type !== T.IDENTIFIER) break
            sb.append('\\')
            lastIdent = tokens[i]
            sb.append(text.substring(lastIdent.start, lastIdent.end))
            i++
        }
        return Parsed(sb.toString(), lastIdent, i)
    }

    private fun skipWs(tokens: List<Tok>, from: Int, toExclusive: Int): Int {
        var i = from
        while (i < toExclusive && tokens[i].type === T.WHITE_SPACE_INSIDE) i++
        return i
    }

    private fun skipDot(tokens: List<Tok>, from: Int, toExclusive: Int): Int {
        val i = skipWs(tokens, from, toExclusive)
        return if (i < toExclusive && tokens[i].type === T.DOT) i + 1 else i
    }

    private fun nextIsDot(tokens: List<Tok>, from: Int, toExclusive: Int): Boolean {
        val i = skipWs(tokens, from, toExclusive)
        return i < toExclusive && tokens[i].type === T.DOT
    }

    /**
     * True when the immediately preceding (skipping whitespace) token is `.` —
     * we use this to recognise `obj.prop` / `obj.method()` patterns whose
     * identifier we must NOT report (it belongs to a runtime variable, not a
     * statically-resolvable PHP function/class).
     */
    private fun isAfterDot(tokens: List<Tok>, currentIdx: Int, lowerBound: Int): Boolean {
        var i = currentIdx - 1
        while (i >= lowerBound && tokens[i].type === T.WHITE_SPACE_INSIDE) i--
        return i >= lowerBound && tokens[i].type === T.DOT
    }

    /**
     * Walk back from [currentIdx] and return the index of the last token whose
     * type is not WHITE_SPACE_INSIDE / LINE_COMMENT. Returns a value strictly
     * less than [lowerBound] when no semantic token exists in
     * `[lowerBound, currentIdx)`.
     *
     * Used by [scanArgumentList] to enforce the named-argument prev guard
     * (spec D-1): an `IDENT:` slot is a PHP-8 named argument only when the
     * preceding semantic token is the opening LPAREN (i.e. result < lowerBound
     * because `from` sits one past LPAREN) or a COMMA.
     */
    private fun lastSemanticBefore(
        tokens: List<Tok>,
        currentIdx: Int,
        lowerBound: Int,
    ): Int {
        var i = currentIdx - 1
        while (i >= lowerBound &&
            (tokens[i].type === T.WHITE_SPACE_INSIDE ||
                tokens[i].type === T.LINE_COMMENT)
        ) {
            i--
        }
        return i
    }

    /**
     * Strip namespace separators / leading backslash to get the simple symbol
     * name. Used as the PARAMETER_NAME ref's `callTargetName` so the resolver
     * can match it against `Function.name` / `Method.name`.
     */
    private fun simpleName(qualified: String): String =
        qualified.trimStart('\\').substringAfterLast('\\')

    private fun findRBrace(tokens: List<Tok>, from: Int): Int {
        var depth = 0
        for (i in from until tokens.size) {
            when (tokens[i].type) {
                T.LBRACE -> depth++
                T.RBRACE -> if (depth == 0) return i else depth--
                else -> {}
            }
        }
        return -1
    }

    private fun lexAll(text: CharSequence): List<Tok> {
        val out = ArrayList<Tok>()
        val lexer = SkyTemplateLexer()
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null) {
            out += Tok(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return out
    }
}
