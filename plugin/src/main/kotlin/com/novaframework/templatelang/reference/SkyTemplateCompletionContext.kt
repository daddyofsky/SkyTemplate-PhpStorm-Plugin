package com.novaframework.templatelang.reference

/**
 * Pure inference of the completion intent at the caret. Decoupled from the
 * IntelliJ platform so the logic is unit-testable.
 *
 * Walks backwards from the caret looking for the enclosing `{`, then inspects
 * the body up to the caret to decide what the user is typing:
 *
 *   - `{= … }`, `{? … }`, `{; … }`, `{: … }`, `{?: … }`  → Function (with parens)
 *   - `{if … }`, `{else … }`, `{foreach … }`, …          → Function (with parens)
 *   - `{var | … }`                                       → Function (no parens, pipe form)
 *   - `{c. … }`                                          → Constant
 *   - `{… Cls:: … }` / `{= Cls::met}` / `{var|Cls::m}` / `{c.Cls::CONST}`
 *                                                        → ClassMember
 *
 * Class member intent always wins when a `::` token appears with a non-empty
 * qualifier directly to its left, regardless of the leading prefix. The
 * `withMethodParens` / `constantsOnly` flags carry context so the contributor
 * can decide whether to insert `()` and which kinds of members to offer.
 */
internal object SkyTemplateCompletionContext {

    sealed class Result {
        data class Function(val withParens: Boolean) : Result()
        object Constant : Result()
        data class ClassMember(
            /** Identifier as written (`Cls`, `Ns\Cls`, `\Ns\Cls`). Caller qualifies. */
            val classNameInSource: String,
            /** True for expression context; false for pipe form (no parens). */
            val withMethodParens: Boolean,
            /** True when the leading prefix is `c.` — only class constants make sense. */
            val constantsOnly: Boolean,
        ) : Result()
    }

    fun infer(text: CharSequence, caret: Int): Result? {
        var i = caret - 1
        while (i >= 0) {
            val ch = text[i]
            if (ch == '\n' || ch == '}') return null
            if (ch == '{' && (i == 0 || text[i - 1] != '$')) {
                val body = text.subSequence(i + 1, caret).toString()
                return analyzeBody(body)
            }
            i--
        }
        return null
    }

    private fun analyzeBody(body: String): Result? {
        if (body.isEmpty()) return null

        // `Cls::` member completion takes precedence whenever a class qualifier
        // immediately precedes a `::` somewhere in the body.
        val dblColonIdx = findLastDblColon(body)
        if (dblColonIdx >= 0) {
            val qualifier = extractQualifierEndingAt(body, dblColonIdx)
            if (!qualifier.isNullOrEmpty()) {
                val beforeColon = body.substring(0, dblColonIdx)
                val pipeBeforeColon = beforeColon.lastIndexOf('|') >= 0
                val cScope = body.length >= 2 && body[0] == 'c' && body[1] == '.'
                return Result.ClassMember(
                    classNameInSource = qualifier,
                    withMethodParens = !pipeBeforeColon,
                    constantsOnly = cScope,
                )
            }
        }

        // Pipe filter — `{var|here}` always offers functions with no parens
        // (pipe form invokes without explicit parens).
        if (body.lastIndexOf('|') >= 0) return Result.Function(withParens = false)

        val first = body[0]
        return when {
            first == '=' || first == '?' || first == ';' -> Result.Function(withParens = true)
            first == ':' && (body.length == 1 || body[1] != ':') -> Result.Function(withParens = true)
            first == 'c' && body.length >= 2 && body[1] == '.' -> Result.Constant
            first.isLetter() -> {
                val firstWord = body.takeWhile { it.isLetterOrDigit() || it == '_' }
                if (firstWord.lowercase() in EXPRESSION_KEYWORDS) Result.Function(withParens = true) else null
            }
            else -> null
        }
    }

    /** Last `::` offset, or -1. Returns the offset of the FIRST of the pair. */
    private fun findLastDblColon(body: String): Int {
        var i = body.length - 2
        while (i >= 0) {
            if (body[i] == ':' && body[i + 1] == ':') return i
            i--
        }
        return -1
    }

    /**
     * Walk backwards from [dblColonOffset] over the qualifier preceding `::`.
     * Accepts identifier chars and `\` (namespace separator). Trims trailing
     * horizontal whitespace before the `::`.
     */
    private fun extractQualifierEndingAt(body: String, dblColonOffset: Int): String? {
        var end = dblColonOffset
        while (end > 0 && (body[end - 1] == ' ' || body[end - 1] == '\t')) end--
        var start = end
        while (start > 0) {
            val c = body[start - 1]
            if (c.isLetterOrDigit() || c == '_' || c == '\\') start--
            else break
        }
        if (start == end) return null
        return body.substring(start, end)
    }

    private val EXPRESSION_KEYWORDS = setOf(
        "if", "else", "elseif", "foreach", "for", "while", "loop", "each",
    )
}
