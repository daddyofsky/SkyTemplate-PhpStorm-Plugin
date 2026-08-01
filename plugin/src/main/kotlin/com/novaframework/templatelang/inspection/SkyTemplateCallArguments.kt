package com.novaframework.templatelang.inspection

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.xml.XmlFile
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Function as PhpFunction
import com.jetbrains.php.lang.psi.elements.Method as PhpMethod
import com.jetbrains.php.lang.psi.elements.Parameter as PhpParameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.novaframework.templatelang.reference.SkyTemplateFormatterLookup
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.settings.TemplateLangSettings
import com.novaframework.templatelang.sky.SkyTemplateFile
import com.novaframework.templatelang.sky.SkyTemplateRangeCache

/**
 * File-level analyser for Phase 3 argument-validation inspections. Walks every
 * SkyTemplate call site (paren / static-method / pipe form) and emits any of
 * the six rule diagnostics:
 *
 *   - rule a: required parameter missing                    (WARNING)
 *   - rule b: too many arguments (callee not variadic)      (WARNING)
 *   - rule c: named argument name unknown                   (WARNING)
 *   - rule d: duplicate named argument                      (ERROR)
 *   - rule e: positional argument after named               (ERROR)
 *   - rule f: variadic (`...$args`) — adjusts a/b/c, never a standalone diagnostic.
 *
 * Two LocalInspection wrappers filter [analyze] output by [Rule]:
 *   - [SkyTemplateArgumentCountInspection] keeps {a, b}
 *   - [SkyTemplateNamedArgumentInspection]  keeps {c, d, e}
 *
 * Type checks are intentionally **out of scope** — only argument count, name,
 * and position are validated. Object-method calls (`{=user.method(...)}`) are
 * also out of scope (would need variable type inference).
 *
 * **Poly-variant tolerance.** Unresolved callees (FQN chain returns nothing)
 * skip the diagnostic — the dedicated `SkyTemplateUndefinedSymbolInspection`
 * already covers that case. Multiple resolutions adopt the **most permissive**
 * signature (lowest required count, any-variadic-passes, any-name-known) to
 * avoid false positives.
 *
 * Algorithm reuse: call-site collection ([collectCalls]), argument splitting
 * ([splitArguments]), and named-arg classification ([classify]) are the
 * single source of truth for both this analyser and
 * `SkyTemplateInlayParameterHintsProvider`. The inlay provider delegates to
 * these helpers so a regression in either feature is caught by the
 * integration tests of the other.
 */
object SkyTemplateCallArguments {

    enum class Rule {
        A_REQUIRED_MISSING,
        B_TOO_MANY,
        C_UNKNOWN_NAMED,
        D_DUPLICATE_NAMED,
        E_POSITIONAL_AFTER_NAMED,
    }

    /**
     * @property range Absolute file offsets — same convention as
     *   [SkyTemplateUndefinedSymbolAnalyzer.Diagnostic].
     */
    data class Diagnostic(val range: TextRange, val message: String, val rule: Rule)

    /**
     * Pure file-level analysis. Returns an empty list when:
     *   - the file is not `*.sky` / HTML host;
     *   - the master `Enable SkyTemplate support` toggle is off;
     *   - we're in dumb mode (PhpIndex unavailable);
     *   - there are no call sites with resolvable callees.
     */
    fun analyze(file: PsiFile): List<Diagnostic> {
        if (file !is XmlFile && file !is SkyTemplateFile) return emptyList()
        if (!TemplateLangFileFilter.shouldProcess(file)) return emptyList()
        // Checked live on every call, never cached: a project mid-indexing
        // must keep re-trying here, not get an empty result baked into the
        // cache past the point the index becomes usable.
        if (DumbService.isDumb(file.project)) return emptyList()
        return analyzeCached(file)
    }

    /**
     * [SkyTemplateArgumentCountInspection], [SkyTemplateNamedArgumentInspection],
     * and [SkyTemplateArgumentAnnotator] each call [analyze] once per
     * highlighting pass on the same file/root — this collapses their three
     * full-text call-site scans + PhpIndex signature resolutions into one.
     * `PsiModificationTracker.MODIFICATION_COUNT` is project-wide, so an
     * edit to any PHP file that changes a resolved callee's signature
     * invalidates this too, not just edits to this SkyTemplate file.
     */
    private fun analyzeCached(file: PsiFile): List<Diagnostic> =
        CachedValuesManager.getCachedValue(file) {
            val text = file.viewProvider.contents
            val diagnostics = if (text.length < 3 || '{' !in text) {
                emptyList()
            } else {
                computeDiagnostics(file, text)
            }
            CachedValueProvider.Result.create(diagnostics, file, PsiModificationTracker.MODIFICATION_COUNT)
        }

    private fun computeDiagnostics(file: PsiFile, text: CharSequence): List<Diagnostic> {
        val settings = TemplateLangSettings.getInstance(file.project)
        val phpIndex = PhpIndex.getInstance(file.project)
        val out = ArrayList<Diagnostic>()
        // Local memoisation: same callee can repeat in a file (e.g. `e()` in
        // every output position). PhpIndex itself caches but we save the
        // signature-extraction step.
        val cache = HashMap<CalleeKey, List<Signature>>()

        for (call in collectCalls(text)) {
            val sigs = cache.getOrPut(CalleeKey(call.calleeClass, call.calleeName)) {
                resolveSignatures(phpIndex, settings, call)
            }
            if (sigs.isEmpty()) continue   // unresolved → skip; UndefinedSymbol covers it
            evaluateCall(text, call, sigs, out)
        }
        return out
    }

    // ── public testable surface ────────────────────────────────────────────

    /**
     * Signature summary used by all rules. Built once per resolved callee.
     *
     * @property paramCount    total declared parameters (includes variadic).
     * @property requiredCount count of the leading-prefix of parameters with
     *   no default value AND not variadic. PHP allows trailing default-value
     *   parameters; if a stub mistakenly puts a non-default after a default
     *   we stop counting at the first default to stay safe.
     * @property variadic      last parameter is `...$x`.
     * @property paramNames    names of every parameter (including variadic).
     */
    data class Signature(
        val paramCount: Int,
        val requiredCount: Int,
        val variadic: Boolean,
        val paramNames: Set<String>,
    )

    /**
     * Pure helper for unit tests — extract a [Signature] from a parameters
     * array (taken from [PhpFunction.getParameters] / [PhpMethod.getParameters]).
     */
    fun signatureOf(parameters: Array<out PhpParameter>): Signature {
        if (parameters.isEmpty()) {
            return Signature(0, 0, variadic = false, paramNames = emptySet())
        }
        val names = LinkedHashSet<String>(parameters.size)
        var required = 0
        var stillRequired = true
        var variadic = false
        for ((i, p) in parameters.withIndex()) {
            names.add(p.name)
            val isVar = p.isVariadic
            val hasDefault = p.defaultValuePresentation != null
            if (i == parameters.size - 1 && isVar) variadic = true
            if (stillRequired) {
                if (isVar || hasDefault) {
                    stillRequired = false
                } else {
                    required++
                }
            }
        }
        return Signature(parameters.size, required, variadic, names)
    }

    // ── call-site collection (mirrors InlayParameterHintsProvider) ─────────

    enum class CallMode { PAREN, PAREN_STATIC, PIPE }

    /**
     * @property argListStart absolute offset of the first byte AFTER the
     *   opening `(` (paren) or `=` (pipe).
     * @property argListEnd   absolute offset of the closing `)` (paren) or
     *   the next `|` / `}` boundary (pipe).
     */
    data class CallSite(
        val mode: CallMode,
        val calleeName: String,
        val calleeClass: String?,
        val callStart: Int,
        val argListStart: Int,
        val argListEnd: Int,
    )

    fun collectCalls(text: CharSequence): List<CallSite> {
        val out = ArrayList<CallSite>()
        if (text.isEmpty() || '{' !in text) return out
        for (range in SkyTemplateRangeCache.get(text)) {
            val open = range.startOffset
            val close = range.endOffset
            if (isCommentRange(text, open, close)) continue
            collectInTag(text, open, close, out)
        }
        return out
    }

    /**
     * True when the `{ … }` / `<!--{ … }-->` [range] is a `{*…*}` comment —
     * comments must not contribute call sites (a `{=foo()}` written as an
     * example inside a comment isn't a real call). The plain form starts
     * `{*` directly, but an HTML-wrapped comment (`<!--{* … *}-->`) starts
     * `<!--`, so `text[open + 1]` alone misses it; find the range's actual
     * `{` first and check the character right after it.
     */
    private fun isCommentRange(text: CharSequence, open: Int, close: Int): Boolean {
        var i = open
        while (i < close && text[i] != '{') i++
        return i + 1 < close && text[i + 1] == '*'
    }

    private fun collectInTag(
        text: CharSequence,
        open: Int,
        close: Int,
        out: MutableList<CallSite>,
        scanFrom: Int = open + 1,
    ) {
        var i = scanFrom
        // Quote-aware: a `(` inside a string-literal argument (e.g.
        // `foo("bar(")`) must never be mistaken for a nested call opener
        // once we start recursing into argument ranges below — the outer
        // parser convention (matchingRParen / pipeArgEnd / splitArguments)
        // already treats quoted content as opaque, so this scan must match.
        var inQuote = ' '
        while (i < close) {
            val c = text[i]
            if (inQuote != ' ') {
                if (c == '\\' && i + 1 < close) { i += 2; continue }
                if (c == inQuote) inQuote = ' '
                i++
                continue
            }
            if (c == '\'' || c == '"') {
                inQuote = c
                i++
                continue
            }
            if (c.isLetter() || c == '_') {
                val nameStart = i
                while (i < close && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '\\')) i++
                val nameEnd = i
                var j = i
                while (j < close && text[j].isWhitespace()) j++
                if (j < close && text[j] == '(') {
                    val name = text.substring(nameStart, nameEnd).trimStart('\\')
                    var clsName: String? = null
                    // `nameStart - 1` is the second `:` of the `Cls::` prefix
                    // (when present); `nameStart - 2` is the first `:`. The
                    // class identifier ends at `nameStart - 3` and runs
                    // backwards while the byte is letter / digit / `_` / `\`.
                    //
                    // (`SkyTemplateInlayParameterHintsProvider` delegates to
                    // this routine — the historical short-trim there is now
                    // fixed by the same shared logic. See
                    // `SkyTemplateCallArgumentsTest.collectCalls_staticMethod`
                    // for the regression pin.)
                    val pre = nameStart - 1
                    if (pre - 1 >= open && text[pre] == ':' && text[pre - 1] == ':') {
                        val firstColon = pre - 1   // end-exclusive of class
                        var ks = firstColon - 1
                        while (ks >= open && (text[ks].isLetterOrDigit() || text[ks] == '_' || text[ks] == '\\')) ks--
                        ks++
                        clsName = text.substring(ks, firstColon).trimStart('\\').ifBlank { null }
                    }
                    val argStart = j + 1
                    val argEnd = matchingRParen(text, argStart, close)
                    out += CallSite(
                        mode = if (clsName != null) CallMode.PAREN_STATIC else CallMode.PAREN,
                        calleeName = name.substringAfterLast('\\'),
                        calleeClass = clsName,
                        callStart = nameStart,
                        argListStart = argStart,
                        argListEnd = argEnd,
                    )
                    // Recurse into the argument list so nested calls
                    // (`{=trim(substr("x"))}` → `substr` too) are also
                    // collected and validated, not just the outermost call.
                    if (argEnd > argStart) {
                        collectInTag(text, argStart, argEnd, out, scanFrom = argStart)
                    }
                    i = if (argEnd < close) argEnd + 1 else argEnd
                    continue
                }
                i = nameEnd
                continue
            } else if (c == '|') {
                var j = i + 1
                while (j < close && text[j].isWhitespace()) j++
                val nameStart = j
                while (j < close && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '\\')) j++
                val classEnd = j
                // Optional `::method` suffix — `{var|Cls::m=…}` is compiler-
                // supported (filter-name char class is `[\w\\:]*`) and is
                // NEVER routed through the formatter (`method_exists`
                // never matches a literal `Cls::m` string); it compiles to
                // a direct static call. Consume it here so the scan reaches
                // the trailing `=` — previously `j` stopped at the first
                // `:` and the whole construct fell through unrecognised.
                var methodStart = -1
                var methodEnd = -1
                if (j + 1 < close && text[j] == ':' && text[j + 1] == ':') {
                    var m = j + 2
                    val mStart = m
                    while (m < close && (text[m].isLetterOrDigit() || text[m] == '_')) m++
                    if (m > mStart) {
                        methodStart = mStart
                        methodEnd = m
                        j = m
                    }
                }
                if (classEnd > nameStart) {
                    var k = j
                    while (k < close && text[k].isWhitespace()) k++
                    if (k < close && text[k] == '=') {
                        val argStart = k + 1
                        val argEnd = pipeArgEnd(text, argStart, close)
                        val calleeClass: String?
                        val calleeMethod: String
                        if (methodStart >= 0) {
                            calleeClass = text.substring(nameStart, classEnd).trimStart('\\').ifBlank { null }
                            calleeMethod = text.substring(methodStart, methodEnd)
                        } else {
                            calleeClass = null
                            calleeMethod = text.substring(nameStart, classEnd).trimStart('\\').substringAfterLast('\\')
                        }
                        if (calleeMethod.isNotBlank() && (methodStart < 0 || calleeClass != null)) {
                            out += CallSite(
                                mode = CallMode.PIPE,
                                calleeName = calleeMethod,
                                calleeClass = calleeClass,
                                callStart = nameStart,
                                argListStart = argStart,
                                argListEnd = argEnd,
                            )
                            i = argEnd
                            continue
                        }
                    }
                }
            }
            i++
        }
    }

    private fun matchingRParen(text: CharSequence, from: Int, end: Int): Int {
        var depth = 1
        var i = from
        var inQuote = ' '
        while (i < end) {
            val c = text[i]
            if (inQuote != ' ') {
                if (c == '\\' && i + 1 < end) { i += 2; continue }
                if (c == inQuote) inQuote = ' '
                i++
                continue
            }
            when (c) {
                '\'', '"' -> inQuote = c
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return end
    }

    private fun pipeArgEnd(text: CharSequence, from: Int, end: Int): Int {
        var depth = 0
        var i = from
        var inQuote = ' '
        while (i < end) {
            val c = text[i]
            if (inQuote != ' ') {
                if (c == '\\' && i + 1 < end) { i += 2; continue }
                if (c == inQuote) inQuote = ' '
                i++
                continue
            }
            when (c) {
                '\'', '"' -> inQuote = c
                '(' , '[' -> depth++
                ')' , ']' -> if (depth > 0) depth--
                '|' -> if (depth == 0) return i
                '}' -> if (depth == 0) return i
            }
            i++
        }
        return end
    }

    // ── argument splitting (top-level commas, quote/paren-aware) ───────────

    /**
     * @property startInclusive first byte of the bucket (incl. leading whitespace).
     * @property endExclusive   one past the last byte (incl. trailing whitespace).
     */
    data class ArgRange(val startInclusive: Int, val endExclusive: Int)

    /**
     * @param keepBlanks pipe-filter mode only. The compiler tokenises pipe
     *   args with `str_getcsv` and keeps every blank token as a positional
     *   `''` argument (`{v|fn=a,,b}` → 4 PHP args, not 2) — so once a comma
     *   is present, blank buckets must NOT be dropped. The single case that
     *   still collapses to zero buckets is a wholly-blank arg list with no
     *   comma at all (`{v|fn=}`), which mirrors the compiler's separate
     *   `$args === ''` bypass (direct `fn($code)` call, no tokenising).
     *   Paren / static-method calls keep the default (`false`): a trailing
     *   comma there is PHP-8 syntax sugar and adds no argument.
     */
    fun splitArguments(text: CharSequence, start: Int, end: Int, keepBlanks: Boolean = false): List<ArgRange> {
        val out = ArrayList<ArgRange>()
        if (start >= end) return out
        var depth = 0
        var bucketStart = start
        var i = start
        var inQuote = ' '
        while (i < end) {
            val c = text[i]
            if (inQuote != ' ') {
                if (c == '\\' && i + 1 < end) { i += 2; continue }
                if (c == inQuote) inQuote = ' '
                i++
                continue
            }
            when (c) {
                '\'', '"' -> inQuote = c
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    out += ArgRange(bucketStart, i)
                    bucketStart = i + 1
                }
            }
            i++
        }
        if (bucketStart < end) out += ArgRange(bucketStart, end)
        if (!keepBlanks) {
            // Drop empty buckets (e.g. trailing comma) — the existing inlay
            // provider does this same filter.
            return out.filter { isNonBlank(text, it.startInclusive, it.endExclusive) }
        }
        // keepBlanks: preserve every bucket EXCEPT the single-bucket,
        // wholly-blank case (no comma anywhere) — that one bucket represents
        // "no args written at all", the compiler's `$args === ''` bypass.
        if (out.size == 1 && !isNonBlank(text, out[0].startInclusive, out[0].endExclusive)) {
            return emptyList()
        }
        return out
    }

    private fun isNonBlank(text: CharSequence, from: Int, to: Int): Boolean {
        for (i in from until to) if (!text[i].isWhitespace()) return true
        return false
    }

    // ── per-bucket classification ─────────────────────────────────────────

    enum class ArgKind { POSITIONAL, NAMED, HASH_PLACEHOLDER }

    /**
     * @property kind positional / named / `##` placeholder.
     * @property name parameter name when [kind] == [ArgKind.NAMED].
     * @property nameRange absolute range of the bare name identifier (for
     *   diagnostic highlighting on rule c / d).
     */
    data class ClassifiedArg(
        val raw: ArgRange,
        val kind: ArgKind,
        val name: String? = null,
        val nameRange: TextRange? = null,
    )

    /**
     * Classify a single bucket. [pipeMode] selects the named-arg shape:
     *   - paren: `IDENT:` (single colon — `::` excluded).
     *   - pipe:  `IDENT=` (excluding `==` comparisons — L-003 guard).
     */
    fun classify(text: CharSequence, arg: ArgRange, pipeMode: Boolean): ClassifiedArg {
        // Trim leading whitespace.
        var s = arg.startInclusive
        while (s < arg.endExclusive && text[s].isWhitespace()) s++
        if (s >= arg.endExclusive) {
            // Empty (shouldn't happen — splitArguments drops blanks) — treat as positional.
            return ClassifiedArg(arg, ArgKind.POSITIONAL)
        }
        // `##` placeholder (pipe only). The two `#` are emitted as plain
        // characters in the host text — the lexer side splits them, but at
        // the text level they're consecutive.
        if (pipeMode && s + 1 < arg.endExclusive && text[s] == '#' && text[s + 1] == '#') {
            // Tail must be whitespace (anything else means a different token starting with `##`).
            var t = s + 2
            while (t < arg.endExclusive && text[t].isWhitespace()) t++
            if (t >= arg.endExclusive) return ClassifiedArg(arg, ArgKind.HASH_PLACEHOLDER)
        }
        // Identifier-led named-arg shape?
        if (text[s].isLetter() || text[s] == '_') {
            var p = s
            while (p < arg.endExclusive && (text[p].isLetterOrDigit() || text[p] == '_')) p++
            val nameEnd = p
            var q = p
            while (q < arg.endExclusive && text[q].isWhitespace()) q++
            if (q < arg.endExclusive) {
                val ch = text[q]
                if (!pipeMode && ch == ':' && (q + 1 >= arg.endExclusive || text[q + 1] != ':')) {
                    return ClassifiedArg(
                        arg, ArgKind.NAMED,
                        name = text.substring(s, nameEnd),
                        nameRange = TextRange(s, nameEnd),
                    )
                }
                if (pipeMode && ch == '=' && (q + 1 >= arg.endExclusive || text[q + 1] != '=')) {
                    return ClassifiedArg(
                        arg, ArgKind.NAMED,
                        name = text.substring(s, nameEnd),
                        nameRange = TextRange(s, nameEnd),
                    )
                }
            }
        }
        return ClassifiedArg(arg, ArgKind.POSITIONAL)
    }

    // ── per-call rule evaluation ───────────────────────────────────────────

    private fun evaluateCall(
        text: CharSequence,
        call: CallSite,
        sigs: List<Signature>,
        out: MutableList<Diagnostic>,
    ) {
        val pipeMode = call.mode == CallMode.PIPE
        val buckets = splitArguments(text, call.argListStart, call.argListEnd, keepBlanks = pipeMode)
        val classified = buckets.map { classify(text, it, pipeMode) }

        val calleeLabel = formatCallee(call)

        // ── Rule e (positional after named) — paren form only. ────────────
        // The compiler's paren-call codegen passes arguments through
        // verbatim in written order, so PHP 8's "positional after named"
        // restriction applies there. Pipe filters are different: `parseFunction()`
        // (SkyTemplateCompiler.php:874-887) always reorders as
        // `array_merge($positional, $named)` before emitting the call, so
        // `{var|str_pad=pad_type=1,10}` compiles fine regardless of the
        // written order — flagging it here would be a false positive.
        if (!pipeMode) {
            var sawNamed = false
            for (a in classified) {
                when (a.kind) {
                    ArgKind.NAMED -> sawNamed = true
                    ArgKind.POSITIONAL -> if (sawNamed) {
                        out += Diagnostic(
                            range = TextRange(a.raw.startInclusive, a.raw.endExclusive),
                            message = "Cannot use positional argument after named argument in `${calleeLabel}(...)`",
                            rule = Rule.E_POSITIONAL_AFTER_NAMED,
                        )
                    }
                    ArgKind.HASH_PLACEHOLDER -> { /* neutral */ }
                }
            }
        }

        // ── Rule d (duplicate named) — also paren + pipe. ─────────────────
        run {
            val seen = HashSet<String>()
            for (a in classified) {
                if (a.kind == ArgKind.NAMED) {
                    val name = a.name ?: continue
                    if (!seen.add(name)) {
                        val nameRange = a.nameRange
                            ?: TextRange(a.raw.startInclusive, a.raw.endExclusive)
                        out += Diagnostic(
                            range = nameRange,
                            message = "Duplicate named argument `$name` for `${calleeLabel}(...)`",
                            rule = Rule.D_DUPLICATE_NAMED,
                        )
                    }
                }
            }
        }

        // ── Rule c (unknown named name) — poly-variant tolerant. ──────────
        // Permissive: any candidate signature defining the name OR being
        // variadic (variadic functions accept arbitrary names in PHP 8) ⇒
        // pass.
        run {
            for (a in classified) {
                if (a.kind != ArgKind.NAMED) continue
                val name = a.name ?: continue
                val accepted = sigs.any { it.variadic || name in it.paramNames }
                if (!accepted) {
                    val nameRange = a.nameRange
                        ?: TextRange(a.raw.startInclusive, a.raw.endExclusive)
                    out += Diagnostic(
                        range = nameRange,
                        message = "Unknown parameter `$name` for `${calleeLabel}(...)`",
                        rule = Rule.C_UNKNOWN_NAMED,
                    )
                }
            }
        }

        // Rule a/b need a "user-arg count". For pipe mode, the compiler
        // auto-prepends `##` when absent so the pipe-input always occupies
        // PHP arg 0. For paren / static-method mode the count is direct.
        val positionalCount = classified.count { it.kind == ArgKind.POSITIONAL }
        val hashCount = classified.count { it.kind == ArgKind.HASH_PLACEHOLDER }
        val namedCount = classified.count { it.kind == ArgKind.NAMED }
        val totalUserArgs = positionalCount + hashCount + namedCount

        // Effective PHP arg count seen by the callee:
        //   - paren / static: totalUserArgs.
        //   - pipe: if `##` ∈ tokens → tokenCount; else compiler prepends → tokenCount + 1.
        val phpArgCount = if (pipeMode) {
            if (hashCount > 0) totalUserArgs else totalUserArgs + 1
        } else {
            totalUserArgs
        }

        // ── Rule b (too many) — permissive. ───────────────────────────────
        // Pass if ANY candidate is variadic OR has paramCount >= phpArgCount.
        run {
            val anyAccepts = sigs.any { it.variadic || it.paramCount >= phpArgCount }
            if (!anyAccepts) {
                // Find the first user-visible "extra" bucket for the highlight.
                // For paren mode that's bucket index `paramCount`. For pipe
                // mode the pipe-input is implicit — when auto-prepended,
                // bucket index `paramCount - 1` (in user space) is the
                // first overflowing user bucket.
                val maxParam = sigs.maxOfOrNull { it.paramCount } ?: 0
                val extraIdx = if (pipeMode && hashCount == 0) {
                    // auto-prepend: PHP arg 0 = pipe-input, user bucket k = PHP arg k+1.
                    // First extra is bucket where user index k satisfies k+1 >= maxParam ⇒ k = maxParam - 1.
                    (maxParam - 1).coerceAtLeast(0)
                } else {
                    maxParam
                }
                val highlight = classified.getOrNull(extraIdx)?.raw
                    ?: classified.lastOrNull()?.raw
                    ?: ArgRange(call.argListStart, call.argListEnd)
                out += Diagnostic(
                    range = TextRange(highlight.startInclusive, highlight.endExclusive),
                    message = "Too many arguments for `${calleeLabel}(...)`: expected $maxParam, got $phpArgCount",
                    rule = Rule.B_TOO_MANY,
                )
            }
        }

        // ── Rule a (required missing) — permissive. ───────────────────────
        // A param is "covered" if any of:
        //   - a positional / hash-placeholder slot reaches it, OR
        //   - a named arg writes its name.
        // Permissive: pass when the most-permissive (lowest required count)
        // candidate's required-prefix is fully covered.
        run {
            // Count user-positionals contributing to PHP positional slots.
            // For pipe mode the compiler auto-prepends `##` (or honours an
            // explicit `##`) so PHP arg 0 is always filled. Other user
            // positionals fill subsequent slots in order.
            //
            // We compute the highest contiguous PHP-slot index covered by
            // positionals (`positionalReach`) then test whether each
            // candidate's required prefix is satisfied.
            val positionalReach = if (pipeMode) {
                if (hashCount > 0) positionalCount + hashCount else positionalCount + 1
            } else {
                positionalCount
            }
            val namedNames: Set<String> = classified
                .asSequence()
                .filter { it.kind == ArgKind.NAMED }
                .mapNotNull { it.name }
                .toSet()

            val minRequired = sigs.minOf { it.requiredCount }
            // Pick the matching candidate (the one whose requiredCount equals
            // minRequired). If multiple, prefer the one with most parameter
            // names known so the satisfies check is most permissive.
            val pivot = sigs.filter { it.requiredCount == minRequired }
                .maxByOrNull { it.paramNames.size } ?: sigs.first()

            // Required slots are PHP indices [0 .. minRequired). A slot at
            // index k is satisfied when:
            //   - k < positionalReach (a positional/hash bucket reached it), OR
            //   - the parameter name at position k is in namedNames.
            // We don't know parameter ordering by index inside [Signature]
            // (only the name set), so the per-index check uses name only when
            // we can map it. Simplification: count satisfied = max(positionalReach, ?) +
            // |namedNames ∩ paramNames|. To avoid double-counting (positional
            // already covered the same slot the user named), we approximate
            // safely by treating positionals as covering distinct slots and
            // named names as covering the remaining param-name set — that's
            // permissive (it can over-count satisfaction) which fits the
            // false-positive-avoidance policy.
            val nameMatches = namedNames.count { it in pivot.paramNames }
            val satisfied = positionalReach + nameMatches
            if (satisfied < minRequired) {
                val opener = (call.argListStart - 1).coerceAtLeast(call.callStart)
                out += Diagnostic(
                    range = TextRange(opener, call.argListStart),
                    message = "Missing required argument(s) for `${calleeLabel}(...)`: needs $minRequired, got $satisfied",
                    rule = Rule.A_REQUIRED_MISSING,
                )
            }
        }
    }

    private fun formatCallee(call: CallSite): String =
        if (call.calleeClass != null) "${call.calleeClass}::${call.calleeName}" else call.calleeName

    // ── PHP signature lookup (poly-variant) ────────────────────────────────

    private data class CalleeKey(val cls: String?, val name: String)

    private fun resolveSignatures(
        phpIndex: PhpIndex,
        settings: TemplateLangSettings,
        call: CallSite,
    ): List<Signature> {
        return when (call.mode) {
            CallMode.PAREN_STATIC -> {
                val cls = call.calleeClass ?: return emptyList()
                lookupClasses(phpIndex, settings, cls)
                    .mapNotNull { it.findMethodByName(call.calleeName) }
                    .map { signatureOf(it.parameters) }
            }
            CallMode.PIPE -> {
                if (call.calleeClass != null) {
                    // `{var|Cls::m=…}` — a direct static-method call, never
                    // routed through the formatter (`method_exists` never
                    // matches a `Cls::m` string).
                    lookupClasses(phpIndex, settings, call.calleeClass)
                        .mapNotNull { it.findMethodByName(call.calleeName) }
                        .map { signatureOf(it.parameters) }
                } else {
                    // Mirror the compiler's pipe-filter dispatch: a formatter
                    // method with this name wins and the global function is
                    // never called (see SkyTemplatePhpReference.multiResolve) —
                    // so a same-named global function with a different arity
                    // must not be unioned in, or it would produce a false
                    // "missing required argument(s)" / "too many arguments".
                    val formatterMethods = SkyTemplateFormatterLookup.findMethods(phpIndex, settings, call.calleeName)
                    if (formatterMethods.isNotEmpty()) {
                        formatterMethods.map { signatureOf(it.parameters) }
                    } else {
                        lookupFunctions(phpIndex, settings, call.calleeName).map { signatureOf(it.parameters) }
                    }
                }
            }
            CallMode.PAREN -> {
                lookupFunctions(phpIndex, settings, call.calleeName)
                    .map { signatureOf(it.parameters) }
            }
        }
    }

    private fun lookupFunctions(
        phpIndex: PhpIndex,
        settings: TemplateLangSettings,
        nameInSrc: String,
    ): Collection<PhpFunction> {
        val out = LinkedHashSet<PhpFunction>()
        for (fqn in candidateFqns(nameInSrc, settings)) out += phpIndex.getFunctionsByFQN(fqn)
        if (out.isEmpty() && !nameInSrc.startsWith("\\")) {
            val simple = nameInSrc.substringAfterLast('\\')
            if (simple.isNotEmpty()) out += phpIndex.getFunctionsByName(simple)
        }
        return out
    }

    private fun lookupClasses(
        phpIndex: PhpIndex,
        settings: TemplateLangSettings,
        nameInSrc: String,
    ): Collection<PhpClass> {
        val out = LinkedHashSet<PhpClass>()
        for (fqn in candidateFqns(nameInSrc, settings)) out += phpIndex.getClassesByFQN(fqn)
        if (out.isEmpty() && !nameInSrc.startsWith("\\")) {
            val simple = nameInSrc.substringAfterLast('\\')
            if (simple.isNotEmpty()) out += phpIndex.getClassesByName(simple)
        }
        return out
    }

    private fun candidateFqns(nameInSrc: String, settings: TemplateLangSettings): List<String> {
        if (nameInSrc.startsWith("\\")) return listOf(nameInSrc)
        val out = LinkedHashSet<String>()
        val ns = settings.namespace.trim().trim('\\')
        if (ns.isNotEmpty()) out.add("\\$ns\\$nameInSrc")
        out.add("\\$nameInSrc")
        return out.toList()
    }
}
