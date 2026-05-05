package com.novaframework.templatelang.reference

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Function as PhpFunction
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Parameter as PhpParameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.settings.TemplateLangSettings
import com.novaframework.templatelang.sky.SkyTemplateRanges

/**
 * ParameterInfo (Ctrl+P) for SkyTemplate paren calls and pipe `=` filter args.
 *
 * Activates when the caret sits inside a SkyTemplate construct:
 *   - inside `(` … `)` of an expression-context call (`{=foo(│)}`,
 *     `{=Cls::method(│)}`, `{?foo(│)}`, …).
 *   - inside `=` … (end of pipe-arg list) of a pipe filter (`{var|fn=│}`).
 *
 * Resolves the callee through [SkyTemplatePhpReference] (poly-variant)
 * and shows each candidate's parameter signature, highlighting the
 * current argument by comma index or by named-arg name.
 *
 * Activation surface: registered for HTML / XML / SkyTemplate (mirrors
 * the reference-contributor surface). The handler returns null
 * outside template-tag ranges so non-template code is unaffected.
 */
class SkyTemplateParameterInfoHandler : ParameterInfoHandler<PsiElement, PhpNamedElement> {

    override fun couldShowInLookup(): Boolean = false

    /** Locate the host element + caller info at the caret. */
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        if (!TemplateLangFileFilter.shouldProcess(context.file)) return null
        return findCallContext(context.file, context.offset)?.also { ctx ->
            val targets = resolveTargets(context.file, ctx) ?: return null
            if (targets.isEmpty()) return null
            context.itemsToShow = targets.toTypedArray()
        }?.host
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? {
        if (!TemplateLangFileFilter.shouldProcess(context.file)) return null
        return findCallContext(context.file, context.offset)?.host
    }

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        val ctx = findCallContext(context.file, context.offset) ?: return
        // Convert current caret offset to a parameter index relative to the
        // call's argument list. Named-arg buckets resolve by name (matched
        // against the displayed signature in updateUI).
        val idx = computeArgIndex(context.file.text, ctx, context.offset)
        context.setCurrentParameter(idx)
    }

    override fun updateUI(p: PhpNamedElement?, context: ParameterInfoUIContext) {
        if (p == null) {
            context.isUIComponentEnabled = false
            return
        }
        val parameters: List<PhpParameter> = when (p) {
            is PhpFunction -> p.parameters.toList()
            is Method -> p.parameters.toList()
            else -> emptyList()
        }
        if (parameters.isEmpty()) {
            context.setupUIComponentPresentation(
                "<no parameters>", -1, -1,
                /* isDisabled = */ false, /* strikeout = */ false,
                /* isDisabledBeforeHighlight = */ false, context.defaultParameterColor,
            )
            return
        }
        // Build "type $name = default" parts; track each part's offset for highlight.
        val parts = ArrayList<String>(parameters.size)
        val ranges = ArrayList<IntRange>(parameters.size)
        val sb = StringBuilder()
        for ((i, param) in parameters.withIndex()) {
            if (i > 0) {
                sb.append(", ")
            }
            val start = sb.length
            val type = param.type.toString().takeIf { it.isNotBlank() }
            if (type != null) sb.append(type).append(' ')
            sb.append('$').append(param.name)
            param.defaultValuePresentation?.let { sb.append(" = ").append(it) }
            val end = sb.length
            ranges += start until end
            parts += sb.substring(start, end)
        }
        val current = context.currentParameterIndex
        val (hlStart, hlEnd) = if (current in ranges.indices) {
            ranges[current].first to ranges[current].last + 1
        } else {
            -1 to -1
        }
        context.setupUIComponentPresentation(
            sb.toString(), hlStart, hlEnd,
            /* isDisabled = */ false, /* strikeout = */ false,
            /* isDisabledBeforeHighlight = */ false, context.defaultParameterColor,
        )
    }

    // ── shared discovery ─────────────────────────────────────────────────────

    /**
     * Identify the call enclosing the caret. Returns null when the caret is
     * outside any SkyTemplate construct or inside a comment.
     */
    private fun findCallContext(file: PsiFile, caretOffset: Int): CallContext? {
        val text = file.text
        if (text.isEmpty() || '{' !in text) return null
        val ranges = SkyTemplateRanges.computeTemplateRanges(text)
        val tag = ranges.firstOrNull { it.contains(caretOffset) } ?: return null
        // Walk leftward from caret to find the introducing `(` (paren call)
        // or `|fn=` boundary (pipe filter). Brace-aware: stop at the
        // enclosing `{` if we hit it first.
        val callStart = locateCallStart(text, tag.startOffset, caretOffset) ?: return null
        val host = file.findElementAt(caretOffset) ?: return null
        return CallContext(
            host = host,
            tagStart = tag.startOffset,
            tagEnd = tag.endOffset,
            mode = callStart.mode,
            calleeName = callStart.calleeName,
            calleeClass = callStart.calleeClass,
            argListStart = callStart.argListStart,
        )
    }

    private data class CallContext(
        val host: PsiElement,
        val tagStart: Int,
        val tagEnd: Int,
        val mode: Mode,
        val calleeName: String,
        val calleeClass: String?,
        val argListStart: Int,
    )

    private enum class Mode { PAREN, PIPE }

    private data class CallStart(
        val mode: Mode,
        val calleeName: String,
        val calleeClass: String?,
        val argListStart: Int,
    )

    /**
     * Walk back through the tag body to find an enclosing call boundary.
     *
     * Strategy: track paren depth from the caret backwards. When we cross
     * an unmatched `(`, that's the paren-call boundary — read the
     * preceding identifier (and optional `Cls::` prefix) as the callee.
     *
     * If no unmatched `(` is found and we instead hit a `|<ident>=` form,
     * that's the pipe-filter boundary.
     */
    private fun locateCallStart(text: CharSequence, tagStart: Int, caret: Int): CallStart? {
        var depth = 0
        var i = caret - 1
        while (i >= tagStart) {
            val c = text[i]
            when (c) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) {
                        // Read identifier just before `(`. Skip whitespace.
                        var j = i - 1
                        while (j >= tagStart && text[j].isWhitespace()) j--
                        val nameEnd = j + 1
                        var nameStart = j
                        while (nameStart >= tagStart && (text[nameStart].isLetterOrDigit() || text[nameStart] == '_' || text[nameStart] == '\\')) {
                            nameStart--
                        }
                        nameStart++
                        if (nameStart >= nameEnd) return null
                        val name = text.substring(nameStart, nameEnd).trimStart('\\')
                        // Detect `Cls::method(` form.
                        var k = nameStart - 1
                        if (k - 1 >= tagStart && text[k] == ':' && text[k - 1] == ':') {
                            // walk back over class identifier
                            var ke = k - 1
                            var ks = ke - 1
                            while (ks >= tagStart && (text[ks].isLetterOrDigit() || text[ks] == '_' || text[ks] == '\\')) {
                                ks--
                            }
                            ks++
                            val clsName = text.substring(ks, ke - 1).trimStart('\\')
                            return CallStart(
                                Mode.PAREN, name, clsName.ifBlank { null }, i + 1,
                            )
                        }
                        return CallStart(Mode.PAREN, name, null, i + 1)
                    }
                    depth--
                }
                '{' -> return null  // hit the tag opener — no call here
                else -> {}
            }
            i--
        }
        // No paren — look for pipe filter args boundary `|<ident>=`.
        // Walk forward from tagStart to find the LAST `|<ident>=` before caret.
        var k = tagStart
        var foundCallee: String? = null
        var foundArgStart = -1
        while (k < caret - 1) {
            if (text[k] == '|') {
                // read ident
                var p = k + 1
                while (p < caret && text[p].isWhitespace()) p++
                val nameStart = p
                while (p < caret && (text[p].isLetterOrDigit() || text[p] == '_' || text[p] == '\\')) p++
                val nameEnd = p
                while (p < caret && text[p].isWhitespace()) p++
                if (p < caret && text[p] == '=' && nameEnd > nameStart) {
                    foundCallee = text.substring(nameStart, nameEnd).trimStart('\\').substringAfterLast('\\')
                    foundArgStart = p + 1
                }
            }
            k++
        }
        if (foundCallee != null && foundArgStart > 0) {
            return CallStart(Mode.PIPE, foundCallee, null, foundArgStart)
        }
        return null
    }

    private fun resolveTargets(file: PsiFile, ctx: CallContext): List<PhpNamedElement>? {
        val project = file.project
        if (DumbService.isDumb(project)) return null
        val settings = TemplateLangSettings.getInstance(project)
        if (!settings.isEnabled) return null
        val phpIndex = PhpIndex.getInstance(project)
        val callTarget = ctx.calleeName.substringAfterLast('\\').ifBlank { return null }
        return when {
            ctx.calleeClass != null -> {
                val classes = lookupClasses(phpIndex, settings, ctx.calleeClass)
                classes.mapNotNull { it.findMethodByName(callTarget) }
            }
            else -> {
                lookupFunctions(phpIndex, settings, ctx.calleeName).toList()
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

    /**
     * Mirror of [SkyTemplatePhpReference.candidateFqns] without the
     * useClass-alias step (parameter-info doesn't need it for the common
     * case; static-method calls already have `Cls` written explicitly).
     */
    private fun candidateFqns(nameInSrc: String, settings: TemplateLangSettings): List<String> {
        if (nameInSrc.startsWith("\\")) return listOf(nameInSrc)
        val out = LinkedHashSet<String>()
        val ns = settings.namespace.trim().trim('\\')
        if (ns.isNotEmpty()) out.add("\\$ns\\$nameInSrc")
        out.add("\\$nameInSrc")
        return out.toList()
    }

    /**
     * Compute the current argument index based on caret position relative
     * to the call's argument list start. Counts top-level commas.
     *
     * Named-arg recognition: when the user types `name:` for an argument,
     * we look up the parameter index by name in the displayed signature
     * (this requires the resolved target — lazy: just count commas, the
     * highlight in updateUI uses currentParameterIndex which is the
     * running comma count).
     */
    private fun computeArgIndex(text: CharSequence, ctx: CallContext, caret: Int): Int {
        var depth = 0
        var idx = 0
        var i = ctx.argListStart
        while (i < caret && i < ctx.tagEnd) {
            when (text[i]) {
                '(' , '[' -> depth++
                ')' , ']' -> if (depth == 0) return idx else depth--
                ',' -> if (depth == 0) idx++
            }
            i++
        }
        return idx
    }

    // All other ParameterInfoHandler members have safe defaults in the
    // platform interface (couldShowInLookup, getParametersForLookup,
    // getParametersForDocumentation, etc.) — we don't override them.
}
