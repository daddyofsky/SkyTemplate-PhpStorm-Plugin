package com.novaframework.templatelang.inspection

import com.intellij.openapi.util.TextRange
import com.novaframework.templatelang.sky.SkyTemplateLexer
import com.novaframework.templatelang.sky.SkyTemplateRanges
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

/**
 * Pure-logic, file-level scope analyser. Walks each `{ … }` template tag in
 * source order, mirrors the SkyTemplate compiler's `arrBlock` / `depth`
 * stack updates, and emits structural-correctness issues across four
 * categories. No IntelliJ infrastructure — directly testable.
 *
 * Categories (each maps to one of the M7+ inspections):
 *
 *   - [Code.LOOP_DEPTH_TOO_DEEP] — a loop-scope variable
 *     (`.var`, `..var`, `.var@N`) requests a parent-loop depth that exceeds
 *     the actual loop nesting at that position. Mirrors `parseLoopVar`'s
 *     `up = max(@N, scope_dots - 1)` formula vs `$this->depth`.
 *
 *   - [Code.RESERVED_OUTSIDE_LOOP] — `_index`, `_number`, `_key`, or
 *     `_value` (with optional `@N`) used outside an enclosing loop, where
 *     `parseReservedVar` would clamp depth to 0 and reference the
 *     undefined `$i0` / `$k0` / `$v0`.
 *
 *   - [Code.REDUNDANT_AT_ON_NON_LOOP] — bare `var@` / `var@N` on a plain
 *     variable (no leading scope dot, name doesn't start with `_`). The
 *     `parseNormalVar` path silently ignores `@`, so this is dead syntax
 *     noise; the user almost always meant `.var@N` (loop scope).
 *
 *   - [Code.DUPLICATE_ELSE] — `{:}` / `{else}` (no condition) followed by
 *     another `{:}`/`{:cond}`/`{else}`/`{elseif …}` inside the same
 *     `{?…}` / `{if …}` / `{?:…}` block. The first bare else is the
 *     final branch; the compiler still emits PHP for the second one,
 *     but the resulting `} else { } else {` is a PHP syntax error.
 *
 * The walk also tracks loop nesting (`@`, `%`, `loop`, `each`, `foreach`,
 * `for`, `while`) and "loop-empty" branches (`{:}` after a loop opener
 * decrements depth per `tagElse`).
 */
object SkyTemplateScopeAnalyzer {

    /**
     * Names handled by the compiler's `parseReservedVar` loop-scope branch
     * (`SkyTemplateCompiler::parseReservedVar`, lines 612–615). These are the
     * only `_*` identifiers that consume `$i<depth>` / `$k<depth>` / `$v<depth>`
     * and therefore actually require an enclosing loop. Any other `_*` name —
     * `_GET`, `_POST`, `_REQUEST`, `_COOKIE`, `_SESSION`, `_SERVER`, `_ENV`,
     * `_FILES`, plus user globals — falls through to `parseGlobalVar`, which
     * compiles to `$_<NAME>` (superglobal) or `$_D['<name>']` (template global)
     * and is valid at any nesting depth.
     *
     * Match is case-sensitive to mirror the compiler's `match($var_name)` arms.
     */
    private val LOOP_SCOPE_RESERVED_NAMES = setOf("_index", "_number", "_key", "_value")

    enum class Severity { ERROR, WARNING, WEAK_WARNING }

    enum class Code {
        LOOP_DEPTH_TOO_DEEP,
        RESERVED_OUTSIDE_LOOP,
        REDUNDANT_AT_ON_NON_LOOP,
        REDUNDANT_AT_ZERO,
        DUPLICATE_ELSE,
    }

    data class Issue(
        val code: Code,
        val severity: Severity,
        val range: TextRange,
        val message: String,
    )

    /**
     * Block-stack frame mirroring the compiler's `arrBlock[]` entries.
     *
     * | Frame              | Source            | Loop-depth contribution |
     * | ------------------ | ----------------- | ----------------------- |
     * | LOOP               | `{loop X}` / `@`  |  +1                     |
     * | EACH               | `{each X}`        |  +1                     |
     * | FOREACH            | `{foreach … as …}` / `%` | +1               |
     * | FOR                | `{for …}`         |  +1                     |
     * | WHILE              | `{while …}`       |  +1                     |
     * | IF                 | `{if …}` / `{?…}` /`{?:…}` |  0             |
     * | IF_ELSE            | after `{:cond}` / `{elseif …}`     |  0     |
     * | IF_ELSE_FINAL      | after bare `{:}` / `{else}`        |  0     |
     * | LOOP_ELSE          | `{:}` after a loop  | -1 (already applied)   |
     */
    private enum class Frame(val isLoop: Boolean) {
        LOOP(true),
        EACH(true),
        FOREACH(true),
        FOR(true),
        WHILE(true),
        IF(false),
        IF_ELSE(false),       // after `{:cond}` (elseif form); more elseif still allowed
        IF_ELSE_FINAL(false), // after bare `{:}` / `{else}`; any further branch is duplicate
        LOOP_ELSE(false),     // `{:}` after loop opener — body is the empty case
    }

    private enum class TagKind { LOOP_OPEN, IF_OPEN, BRANCH, CLOSE, ONE_SHOT, COMMENT }

    fun analyze(text: CharSequence): List<Issue> {
        if (text.isEmpty() || '{' !in text) return emptyList()
        val ranges = SkyTemplateRanges.computeTemplateRanges(text)
        if (ranges.isEmpty()) return emptyList()

        val issues = ArrayList<Issue>()
        val stack = ArrayDeque<Frame>()

        for (tagRange in ranges) {
            val open = tagRange.startOffset
            val close = tagRange.endOffset
            // Skip pure comment ranges — `{*…*}`.
            if (open + 1 < close && text[open + 1] == '*') continue
            // Skip wrapped `<!--{…}-->` outer markers — locate inner `{`.
            val innerOpen = findInnerOpen(text, open, close)
            val innerClose = findInnerClose(text, open, close)
            if (innerOpen < 0 || innerClose < 0 || innerOpen >= innerClose) continue

            val slice = text.subSequence(innerOpen, innerClose + 1)
            val parsed = parseTag(slice, innerOpen) ?: continue

            // Apply tag's effect on the block stack BEFORE checking variables —
            // so a closer is correctly registered, but a NEW opener's body
            // (variables inside a loop opener's *argument* expression) is
            // checked against the OUTER scope, not the new loop's scope.
            // Order:
            //   1. Stack effect for closers and branches (alters the frame the
            //      tag's own body looks at).
            //   2. Check variables in tag arg against that frame.
            //   3. Stack effect for openers (applies AFTER body check so the
            //      opener's own arg is in the outer scope).
            applyPreVariableStackEffect(parsed, stack, issues)

            val loopDepth = currentLoopDepth(stack)
            for (varRef in parsed.varRefs) {
                checkVariable(varRef, loopDepth, issues)
            }

            applyPostVariableStackEffect(parsed, stack)
        }

        return issues
    }

    /**
     * Parsed view of a single `{ … }` tag — its kind plus the variables
     * we want to validate inside its argument expression / body line.
     */
    private data class ParsedTag(
        val kind: TagKind,
        val branchHasArg: Boolean,
        val branchTagRange: TextRange,
        val varRefs: List<VarRef>,
    )

    /**
     * @property leadingDots count of `.` chars at the start of the variable
     *           (0 for plain / reserved names; 1 for `.var`; 2 for `..var`; …).
     * @property atDigits the `@` modifier digits if present, or `null` if no `@`.
     *           Empty string for bare `@`. The compiler treats `@` and `@0`
     *           identically as `up=1`.
     * @property isReserved true when the name starts with `_` (compiler's
     *           `parseReservedVar` path).
     * @property nameRange where the entire variable expression sits in host
     *           coordinates — used as the highlight range.
     */
    private data class VarRef(
        val leadingDots: Int,
        val atDigits: String?,
        val isReserved: Boolean,
        val nameRange: TextRange,
        val atRange: TextRange?,
    )

    private fun parseTag(slice: CharSequence, baseOffset: Int): ParsedTag? {
        val lexer = SkyTemplateLexer()
        lexer.start(slice, 0, slice.length, 0)

        // First, find the tag's "head" token — TAG_PREFIX or TAG_KEYWORD —
        // skipping the LBRACE and any whitespace. That tells us the kind.
        var kind: TagKind? = null
        var branchHasArg = false
        var headStart = -1
        var headEnd = -1

        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            when (type) {
                T.LBRACE, T.WHITE_SPACE_INSIDE -> { /* skip */ }
                T.TAG_PREFIX -> {
                    headStart = lexer.tokenStart
                    headEnd = lexer.tokenEnd
                    val word = slice.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                    kind = when (word) {
                        "@", "%" -> TagKind.LOOP_OPEN
                        "?", "?:", "&" -> TagKind.IF_OPEN  // `&` = refer is one-shot but block-stack-neutral; treat as IF for arg-only passes
                        ":" -> TagKind.BRANCH
                        "/" -> TagKind.CLOSE
                        "=", ";", "#", "+", "]", "\\" -> TagKind.ONE_SHOT
                        else -> TagKind.ONE_SHOT
                    }
                    // For BRANCH, branchHasArg = whether non-whitespace follows before RBRACE.
                    if (kind == TagKind.BRANCH) {
                        branchHasArg = hasBodyAfter(slice, lexer.tokenEnd)
                    }
                    break
                }
                T.TAG_KEYWORD -> {
                    headStart = lexer.tokenStart
                    headEnd = lexer.tokenEnd
                    val word = slice.subSequence(lexer.tokenStart, lexer.tokenEnd).toString().lowercase()
                    kind = when (word) {
                        "loop"    -> TagKind.LOOP_OPEN
                        "each"    -> TagKind.LOOP_OPEN
                        "foreach" -> TagKind.LOOP_OPEN
                        "for"     -> TagKind.LOOP_OPEN
                        "while"   -> TagKind.LOOP_OPEN
                        "if"      -> TagKind.IF_OPEN
                        "else"    -> TagKind.BRANCH.also { branchHasArg = false }
                        "elseif"  -> TagKind.BRANCH.also { branchHasArg = true }
                        "end"     -> TagKind.CLOSE
                        else      -> TagKind.ONE_SHOT
                    }
                    break
                }
                else -> {
                    // Tag starts directly with an identifier (variable form
                    // `{name}` or `{c.NAME}`). Treat as a variable-only one-shot.
                    kind = TagKind.ONE_SHOT
                    break
                }
            }
            lexer.advance()
        }

        if (kind == null) return null

        // Re-lex from start to collect ALL variables within the tag.
        // (The first pass left the lexer mid-stream.)
        val varRefs = collectVariables(slice, baseOffset)
        val branchRange = if (headStart >= 0 && headEnd > 0) {
            TextRange(baseOffset + headStart, baseOffset + headEnd)
        } else {
            TextRange(baseOffset, baseOffset + slice.length)
        }

        return ParsedTag(kind, branchHasArg, branchRange, varRefs)
    }

    /**
     * True if any non-whitespace, non-comment, non-RBRACE token sits between
     * [from] and the close of the tag.
     */
    private fun hasBodyAfter(slice: CharSequence, from: Int): Boolean {
        var i = from
        while (i < slice.length) {
            val c = slice[i]
            if (c == '}') return false
            if (!c.isWhitespace()) return true
            i++
        }
        return false
    }

    private fun collectVariables(slice: CharSequence, baseOffset: Int): List<VarRef> {
        val lexer = SkyTemplateLexer()
        lexer.start(slice, 0, slice.length, 0)
        val refs = ArrayList<VarRef>()

        var leadingDots = 0
        var dotsRangeStart = -1
        var dotsRangeEnd = -1

        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            when (type) {
                T.SCOPE_LOOP -> {
                    // `.` / `..` / `...` — count chars
                    leadingDots = lexer.tokenEnd - lexer.tokenStart
                    dotsRangeStart = lexer.tokenStart
                    dotsRangeEnd = lexer.tokenEnd
                }
                T.IDENTIFIER, T.SCOPE_RESERVED -> {
                    val nameStart = if (leadingDots > 0) dotsRangeStart else lexer.tokenStart
                    val identStart = lexer.tokenStart
                    val identEnd = lexer.tokenEnd
                    // Narrow SCOPE_RESERVED → "loop-scoped reserved" by name.
                    // The lexer paints every `_*` identifier with the reserved
                    // colour for highlighting, but only the four compiler-
                    // recognised loop names actually require a loop frame;
                    // `_GET`, `_SERVER`, etc. compile through parseGlobalVar.
                    val identName = slice.subSequence(identStart, identEnd).toString()
                    val isReserved = type == T.SCOPE_RESERVED && identName in LOOP_SCOPE_RESERVED_NAMES
                    // Look ahead for `@digits?` modifier. Save current state and
                    // peek next tokens.
                    lexer.advance()
                    var atDigits: String? = null
                    var atStart = -1
                    var atEnd = -1
                    if (lexer.tokenType == T.AT) {
                        atStart = lexer.tokenStart
                        atEnd = lexer.tokenEnd
                        lexer.advance()
                        if (lexer.tokenType == T.NUMBER) {
                            atDigits = slice.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
                            atEnd = lexer.tokenEnd
                            lexer.advance()
                        } else {
                            atDigits = ""  // bare `@`
                        }
                    }
                    // Build var-ref. nameRange covers leading dots + ident + (optional) @N.
                    val rangeEnd = if (atEnd > 0) atEnd else identEnd
                    refs.add(
                        VarRef(
                            leadingDots = leadingDots,
                            atDigits = atDigits,
                            isReserved = isReserved,
                            nameRange = TextRange(baseOffset + nameStart, baseOffset + rangeEnd),
                            atRange = if (atStart >= 0) TextRange(baseOffset + atStart, baseOffset + atEnd) else null,
                        )
                    )
                    // Reset dot accumulator — already consumed.
                    leadingDots = 0
                    dotsRangeStart = -1
                    dotsRangeEnd = -1
                    continue  // `lexer.advance()` already called above
                }
                T.DOT -> {
                    // Property-access dot — DOES NOT count toward leading scope.
                    // Reset accumulator just in case.
                    leadingDots = 0
                }
                T.WHITE_SPACE_INSIDE,
                T.LBRACE,
                T.RBRACE,
                T.TAG_PREFIX,
                T.TAG_KEYWORD,
                T.STRING,
                T.NUMBER,
                T.LINE_COMMENT,
                T.LPAREN, T.RPAREN,
                T.LBRACKET, T.RBRACKET,
                T.COMMA,
                T.PIPE,
                T.OPERATOR, T.EQ, T.ARROW, T.DBL_COLON, T.COLON,
                T.HASH, T.AT,
                T.NS_SEP,
                T.SCOPE_CONST,
                T.BAD_CHARACTER -> {
                    // Reset leading-dot accumulator if we see anything that
                    // breaks the dot-then-ident sequence.
                    leadingDots = 0
                }
                else -> {
                    leadingDots = 0
                }
            }
            lexer.advance()
        }
        return refs
    }

    /**
     * Compute effective `up` distance per the compiler:
     *   - if `@N` present (any digits, including `@0` and bare `@`):
     *       up = max(int(N), 1)  — `@` and `@0` both treated as `@1`
     *   - else for SCOPE_LOOP variables (`.var`, `..var`, …):
     *       up = leading_dots - 1
     *   - else (plain or reserved without `@`): up = 0
     */
    private fun effectiveUp(varRef: VarRef): Int {
        val atDigits = varRef.atDigits
        if (atDigits != null) {
            val n = atDigits.toIntOrNull() ?: 0
            return if (n < 1) 1 else n
        }
        return if (varRef.leadingDots > 0) varRef.leadingDots - 1 else 0
    }

    private fun checkVariable(varRef: VarRef, currentLoopDepth: Int, issues: MutableList<Issue>) {
        val isLoopScoped = varRef.leadingDots > 0
        val isReserved = varRef.isReserved
        val hasAt = varRef.atDigits != null

        // 1. Loop-depth-too-deep / reserved-outside-loop.
        //    The variable references `$vN[..]` or `$iN` where N = depth - up.
        //    That access is only valid when N >= 1 (the outermost loop
        //    pushes $v1/$i1; $v0/$i0 don't exist).
        if (isLoopScoped || isReserved || (hasAt && !isReserved)) {
            // hasAt on plain variable also flagged for redundancy below — we
            // still skip the depth check there since `parseNormalVar` ignores @.
            if (isLoopScoped || isReserved) {
                val up = effectiveUp(varRef)
                val requiredDepth = up + 1
                if (currentLoopDepth < requiredDepth) {
                    val code = if (isReserved && currentLoopDepth == 0)
                        Code.RESERVED_OUTSIDE_LOOP
                    else Code.LOOP_DEPTH_TOO_DEEP
                    val message = buildDepthMessage(code, varRef, currentLoopDepth, requiredDepth)
                    issues.add(Issue(code, Severity.WARNING, varRef.nameRange, message))
                }
            }
        }

        // 2. Redundant `@` on non-loop, non-reserved variable.
        //    `{var@}` / `{var@N}` — `parseNormalVar` ignores the modifier
        //    silently. Almost always a typo of `{.var@N}` (loop scope).
        if (hasAt && !isLoopScoped && !isReserved) {
            val atRange = varRef.atRange ?: varRef.nameRange
            issues.add(
                Issue(
                    code = Code.REDUNDANT_AT_ON_NON_LOOP,
                    severity = Severity.WARNING,
                    range = atRange,
                    message = "`@` modifier has no effect on a non-loop variable; the compiler ignores it. " +
                        "If you meant a parent-loop reference, prefix the variable with a leading dot " +
                        "(`.var@N` or `..var`).",
                )
            )
        }

        // 3. `@0` / bare `@` on a loop-scope or reserved variable. Both are
        //    treated by the compiler as `@1` (one level up). Spelling it
        //    `@0` reads as "no offset" which is misleading.
        if (hasAt && (isLoopScoped || isReserved)) {
            val atDigits = varRef.atDigits!!
            if (atDigits == "0") {
                val atRange = varRef.atRange ?: varRef.nameRange
                issues.add(
                    Issue(
                        code = Code.REDUNDANT_AT_ZERO,
                        severity = Severity.WEAK_WARNING,
                        range = atRange,
                        message = "`@0` is treated as `@1` (one level up) by the compiler — " +
                            "use `@1` explicitly if you want the parent loop, or remove `@0` to stay in the current scope.",
                    )
                )
            }
        }
    }

    private fun buildDepthMessage(code: Code, varRef: VarRef, current: Int, required: Int): String {
        val nameDescription = buildVarDescriptor(varRef)
        return when (code) {
            Code.RESERVED_OUTSIDE_LOOP ->
                "Reserved name `$nameDescription` requires an enclosing `{loop}` / `{foreach}` / `{for}` / `{while}` block " +
                    "(needs depth $required, currently $current)."
            Code.LOOP_DEPTH_TOO_DEEP ->
                "Loop-scope reference `$nameDescription` needs $required level(s) of loop nesting " +
                    "but only $current is open at this position. The compiler clamps to depth 0 — " +
                    "the resulting `\$v0[…]` / `\$i0` reads from undefined data."
            else -> "Loop-scope mismatch."
        }
    }

    private fun buildVarDescriptor(varRef: VarRef): String {
        val sb = StringBuilder()
        repeat(varRef.leadingDots) { sb.append('.') }
        sb.append(if (varRef.isReserved) "_…" else "…")
        if (varRef.atDigits != null) {
            sb.append('@')
            sb.append(varRef.atDigits)
        }
        return sb.toString()
    }

    /**
     * Pre-variable stack effect — applied to the block stack BEFORE we
     * check the variables inside the tag. Closers and branches need to
     * affect the depth that the tag's body sees:
     *   - `{/}` pops the current frame BEFORE its (empty) body — depth--
     *   - `{:}` / `{else}` after a loop pops the LOOP frame, replacing
     *     with a LOOP_ELSE frame at depth-1; the else body sees that.
     *   - `{:}` / `{else}` after an `if` does NOT change depth.
     *
     * Also detects:
     *   - duplicate-else: `{:}` after another `{:}` in the same block.
     */
    private fun applyPreVariableStackEffect(
        parsed: ParsedTag,
        stack: ArrayDeque<Frame>,
        issues: MutableList<Issue>,
    ) {
        when (parsed.kind) {
            TagKind.CLOSE -> {
                if (stack.isNotEmpty()) stack.removeLast()
            }
            TagKind.BRANCH -> {
                if (stack.isEmpty()) return  // orphan — separate inspection handles this
                val top = stack.last()
                when (top) {
                    Frame.IF -> {
                        // First branch — accept either `{:cond}` (elseif) or `{:}` (else).
                        stack.removeLast()
                        stack.addLast(if (parsed.branchHasArg) Frame.IF_ELSE else Frame.IF_ELSE_FINAL)
                    }
                    Frame.IF_ELSE -> {
                        // After an elseif — accept further elseif or final else.
                        stack.removeLast()
                        stack.addLast(if (parsed.branchHasArg) Frame.IF_ELSE else Frame.IF_ELSE_FINAL)
                    }
                    Frame.IF_ELSE_FINAL -> {
                        // Already in final-else state → duplicate.
                        issues.add(
                            Issue(
                                code = Code.DUPLICATE_ELSE,
                                severity = Severity.WARNING,
                                range = parsed.branchTagRange,
                                message = "Duplicate `{else}` in the same `{if}` block — once a bare `{:}` / `{else}` " +
                                    "branch is used, no further `{:}` / `{:cond}` / `{elseif …}` is reachable. " +
                                    "The compiler still emits PHP for it, producing an `} else { } else {` syntax error.",
                            )
                        )
                    }
                    Frame.LOOP, Frame.EACH, Frame.FOREACH, Frame.FOR, Frame.WHILE -> {
                        // `{:}` after a loop opens the empty branch (depth--).
                        stack.removeLast()
                        stack.addLast(Frame.LOOP_ELSE)
                    }
                    Frame.LOOP_ELSE -> {
                        issues.add(
                            Issue(
                                code = Code.DUPLICATE_ELSE,
                                severity = Severity.WARNING,
                                range = parsed.branchTagRange,
                                message = "Duplicate `{else}` after a `{loop}` / `{foreach}` / `{for}` / `{while}` block already entered its empty-branch state.",
                            )
                        )
                    }
                }
            }
            else -> { /* no pre-effect for openers / one-shots */ }
        }
    }

    private fun applyPostVariableStackEffect(parsed: ParsedTag, stack: ArrayDeque<Frame>) {
        when (parsed.kind) {
            TagKind.LOOP_OPEN -> stack.addLast(Frame.LOOP)
            TagKind.IF_OPEN -> stack.addLast(Frame.IF)
            else -> { /* no post-effect */ }
        }
    }

    private fun currentLoopDepth(stack: List<Frame>): Int =
        stack.count { it.isLoop }

    /**
     * Locate the inner `{` of a tag range. For `{ … }` returns
     * the offset of the leading `{`. For wrapped `<!--{ … }-->` returns
     * the offset of the `{` after the leading dashes.
     */
    private fun findInnerOpen(text: CharSequence, rangeStart: Int, rangeEnd: Int): Int {
        var i = rangeStart
        while (i < rangeEnd && text[i] != '{') i++
        return if (i < rangeEnd) i else -1
    }

    private fun findInnerClose(text: CharSequence, rangeStart: Int, rangeEnd: Int): Int {
        var i = rangeEnd - 1
        while (i > rangeStart && text[i] != '}') i--
        return if (i > rangeStart) i else -1
    }
}
