package com.novaframework.templatelang.reference

import com.intellij.codeInsight.hints.HintInfo
import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.codeInsight.hints.InlayParameterHintsProvider
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.xml.XmlFile
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Function as PhpFunction
import com.jetbrains.php.lang.psi.elements.Parameter as PhpParameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.novaframework.templatelang.inspection.SkyTemplateCallArguments
import com.novaframework.templatelang.inspection.SkyTemplateCallArguments.ArgKind
import com.novaframework.templatelang.inspection.SkyTemplateCallArguments.CallMode
import com.novaframework.templatelang.inspection.SkyTemplateCallArguments.CallSite
import com.novaframework.templatelang.inspection.SkyTemplateCallArguments.ClassifiedArg
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.settings.TemplateLangSettings
import com.novaframework.templatelang.sky.SkyTemplateFile

/**
 * Inlay parameter-name hints for SkyTemplate paren calls and pipe `=` filter
 * args. Mirrors PhpStorm's PHP hints: positional args display the parameter
 * name as a `name:` chip, named args (already written by the user) are
 * skipped to avoid double-display.
 *
 * The provider runs once per file (via the overridden traversal) and emits
 * an [InlayInfo] for each positional argument it can pair with a callee
 * parameter. Names come from the PHP `Function` / `Method` PSI looked up
 * through PhpIndex.
 *
 * Call-site collection / argument splitting / named-arg classification are
 * delegated to [SkyTemplateCallArguments] so the inlay provider and the
 * Phase 3 argument-validation inspections share one source of truth. (This
 * also closes the prior off-by-one in the local `Cls::method` class-name
 * extraction — see `_test/handoff/phase4_release_1.1.0`.)
 */
class SkyTemplateInlayParameterHintsProvider : InlayParameterHintsProvider {

    override fun getDefaultBlackList(): Set<String> = emptySet()

    /**
     * Collect hints once at the file level. Default traversal would visit
     * every leaf and re-run our scan on each — wasteful and produces
     * duplicate hints because our offsets are absolute. Restricting the
     * traversal to a single root node ensures one pass per file.
     */
    override fun createTraversal(root: PsiElement): SyntaxTraverser<PsiElement> =
        SyntaxTraverser.psiTraverser(root).filter { it === root }

    override fun getParameterHints(element: PsiElement): List<InlayInfo> {
        val file = element.containingFile ?: return emptyList()
        // Run only when invoked with the file itself — leaves trigger the
        // same traversal but get filtered out above.
        if (element !== file) return emptyList()
        if (!TemplateLangFileFilter.shouldProcess(file)) return emptyList()
        if (file !is XmlFile && file !is SkyTemplateFile) return emptyList()
        val project = file.project
        if (DumbService.isDumb(project)) return emptyList()
        val settings = TemplateLangSettings.getInstance(project)
        val text = file.text
        if (text.length < 3 || '{' !in text) return emptyList()

        val phpIndex = PhpIndex.getInstance(project)
        val out = ArrayList<InlayInfo>()
        for (call in SkyTemplateCallArguments.collectCalls(text)) {
            val parameters = resolveParameters(phpIndex, settings, call) ?: continue
            if (parameters.isEmpty()) continue
            val pipeMode = call.mode == CallMode.PIPE
            val classified = SkyTemplateCallArguments
                .splitArguments(text, call.argListStart, call.argListEnd, keepBlanks = pipeMode)
                .map { SkyTemplateCallArguments.classify(text, it, pipeMode) }
            if (pipeMode) {
                emitPipeHints(parameters, classified, out)
            } else {
                emitParenHints(parameters, classified, out)
            }
        }
        return out
    }

    /**
     * Paren / static-method call: each positional bucket maps to PHP
     * parameter `consumed` in left-to-right order. Already-named buckets are
     * skipped so they don't get a redundant chip; the same shape as
     * PhpStorm's built-in PHP hints.
     */
    private fun emitParenHints(
        parameters: List<PhpParameter>,
        classified: List<ClassifiedArg>,
        out: MutableList<InlayInfo>,
    ) {
        var consumed = 0
        for (arg in classified) {
            if (consumed >= parameters.size) break
            when (arg.kind) {
                ArgKind.NAMED -> { /* user already wrote `name:` — no chip */ }
                ArgKind.HASH_PLACEHOLDER -> {
                    // `##` is meaningless in paren mode — classifier only
                    // emits it in pipe mode, but stay defensive.
                    consumed++
                }
                ArgKind.POSITIONAL -> {
                    out += InlayInfo(parameters[consumed].name + ":", arg.raw.startInclusive)
                    consumed++
                }
            }
        }
    }

    /**
     * Pipe-filter call: chip indexing must mirror the SkyTemplate compiler's
     * exact `parseFunction` semantics. The compiler splits tokens into a
     * positional bucket and a named bucket (in token order), then emits
     * `array_merge($positional, $named)` — so PHP's *positional* argument
     * index only advances for POSITIONAL / `##` tokens; a NAMED token
     * (`name=value`) consumes no positional slot at all, regardless of
     * where it sits among the other tokens:
     *
     *   - `##` ∈ tokens (user-explicit) → the positional counter starts at 0
     *     (the `##` token itself occupies slot 0 but gets no chip).
     *   - `##` ∉ tokens (compiler auto-prepends `##` at slot 0) → the
     *     counter starts at 1 (slot 0 is already taken by the implicit
     *     pipe-input value).
     *
     * This matches `{x|sprintf=%05d, ##}` mapping `%05d` to param[0] (the
     * format string), `{x|fn=a, b}` mapping `a, b` to param[1+], and
     * `{x|fn=name=v, a}` mapping `a` to param[1] (NOT param[2] — `name=v`
     * consumes no positional slot even though it's the first token).
     */
    private fun emitPipeHints(
        parameters: List<PhpParameter>,
        classified: List<ClassifiedArg>,
        out: MutableList<InlayInfo>,
    ) {
        val hasExplicitHash = classified.any { it.kind == ArgKind.HASH_PLACEHOLDER }
        // counter = 0 when `##` is written (it will consume slot 0 itself),
        // 1 when the compiler auto-prepends `##` (slot 0 already taken).
        var positionalIdx = if (hasExplicitHash) 0 else 1
        for (arg in classified) {
            when (arg.kind) {
                ArgKind.NAMED -> { /* consumes no positional slot, no chip */ }
                ArgKind.HASH_PLACEHOLDER -> positionalIdx++ // no chip, but occupies its slot
                ArgKind.POSITIONAL -> {
                    if (positionalIdx < parameters.size) {
                        out += InlayInfo(parameters[positionalIdx].name + ":", arg.raw.startInclusive)
                    }
                    positionalIdx++
                }
            }
        }
    }

    override fun getHintInfo(element: PsiElement): HintInfo? = null

    // ── PHP resolution ──────────────────────────────────────────────────────

    private fun resolveParameters(
        phpIndex: PhpIndex,
        settings: TemplateLangSettings,
        call: CallSite,
    ): List<PhpParameter>? {
        return when (call.mode) {
            CallMode.PAREN_STATIC -> {
                val cls = call.calleeClass ?: return null
                lookupClasses(phpIndex, settings, cls)
                    .firstNotNullOfOrNull { it.findMethodByName(call.calleeName) }
                    ?.parameters?.toList()
            }
            CallMode.PIPE -> {
                if (call.calleeClass != null) {
                    // `{var|Cls::m=…}` — direct static-method call, never
                    // routed through the formatter.
                    lookupClasses(phpIndex, settings, call.calleeClass)
                        .firstNotNullOfOrNull { it.findMethodByName(call.calleeName) }
                        ?.parameters?.toList()
                } else {
                    // Mirror the compiler's pipe-filter dispatch: a formatter
                    // method with this name wins and the global function is
                    // never called (see SkyTemplatePhpReference.multiResolve).
                    val formatterMethods = SkyTemplateFormatterLookup.findMethods(phpIndex, settings, call.calleeName)
                    (formatterMethods.firstOrNull() ?: lookupFunctions(phpIndex, settings, call.calleeName).firstOrNull())
                        ?.parameters?.toList()
                }
            }
            CallMode.PAREN -> {
                lookupFunctions(phpIndex, settings, call.calleeName)
                    .firstOrNull()?.parameters?.toList()
            }
        }
    }

    private fun lookupFunctions(
        phpIndex: PhpIndex,
        settings: TemplateLangSettings,
        name: String,
    ): Collection<PhpFunction> {
        val out = LinkedHashSet<PhpFunction>()
        for (fqn in candidateFqns(name, settings)) out += phpIndex.getFunctionsByFQN(fqn)
        if (out.isEmpty() && !name.startsWith("\\")) {
            val simple = name.substringAfterLast('\\')
            if (simple.isNotEmpty()) out += phpIndex.getFunctionsByName(simple)
        }
        return out
    }

    private fun lookupClasses(
        phpIndex: PhpIndex,
        settings: TemplateLangSettings,
        name: String,
    ): Collection<PhpClass> {
        val out = LinkedHashSet<PhpClass>()
        for (fqn in candidateFqns(name, settings)) out += phpIndex.getClassesByFQN(fqn)
        if (out.isEmpty() && !name.startsWith("\\")) {
            val simple = name.substringAfterLast('\\')
            if (simple.isNotEmpty()) out += phpIndex.getClassesByName(simple)
        }
        return out
    }

    private fun candidateFqns(name: String, settings: TemplateLangSettings): List<String> {
        if (name.startsWith("\\")) return listOf(name)
        val out = LinkedHashSet<String>()
        val ns = settings.namespace.trim().trim('\\')
        if (ns.isNotEmpty()) out.add("\\$ns\\$name")
        out.add("\\$name")
        return out.toList()
    }
}
