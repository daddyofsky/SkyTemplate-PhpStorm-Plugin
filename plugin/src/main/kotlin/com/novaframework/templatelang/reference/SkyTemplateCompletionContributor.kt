package com.novaframework.templatelang.reference

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Typing-time completion of PHP symbols inside SkyTemplate constructs.
 *
 * Activates when [SkyTemplateCompletionContext] recognises the caret as being
 * inside one of the template directives that maps onto a PHP symbol slot:
 *
 *   - `{= … }`, `{? … }`, `{; … }`, `{: … }`, `{?: … }`, `{if … }`, … →
 *     **functions** (with auto-`()` insertion).
 *   - `{var | … }` pipe filter → **functions** (no auto-`()` — pipe form is
 *     parenless).
 *   - `{c. … }` → **constants**.
 *   - `{… Cls:: … }` (in any context) → **class members** of `Cls`. Methods
 *     come with `()` in expression context but without parens in pipe form
 *     (`{var|Cls::method}`). Under `c.` only class constants are offered.
 *
 * Reference-based `getVariants()` won't help here because our reference
 * detector needs a syntactically-complete construct (e.g. function call with
 * parens), so for typing-time completion we infer context from the raw text up
 * to the caret. See [SkyTemplateCompletionContext] for the analyzer.
 */
class SkyTemplateCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val containingFile = parameters.position.containingFile ?: return
        if (!TemplateLangFileFilter.shouldProcess(containingFile)) return
        val project = parameters.position.project
        val settings = TemplateLangSettings.getInstance(project)

        val text = containingFile.text
        val caret = parameters.offset

        val ctx = SkyTemplateCompletionContext.infer(text, caret) ?: return
        val phpIndex = PhpIndex.getInstance(project)
        val matcher = result.prefixMatcher

        when (ctx) {
            is SkyTemplateCompletionContext.Result.Function -> {
                offerFunctions(phpIndex, matcher, ctx.withParens, result)
                // Pipe form (`{var|…}`, withParens=false) also dispatches to
                // formatter-class methods — compiler checks method_exists on
                // the configured formatter before falling back to functions.
                if (!ctx.withParens) offerFormatterMethods(phpIndex, settings, matcher, result)
            }
            SkyTemplateCompletionContext.Result.Constant ->
                offerConstants(phpIndex, matcher, result)
            is SkyTemplateCompletionContext.Result.ClassMember ->
                offerClassMembers(phpIndex, settings, ctx, result)
        }
    }

    private fun offerFunctions(
        phpIndex: PhpIndex,
        matcher: PrefixMatcher,
        withParens: Boolean,
        result: CompletionResultSet,
    ) {
        phpIndex.getAllFunctionNames(matcher).forEach { name ->
            result.addElement(buildFunctionLookup(name, withParens))
        }
    }

    /**
     * Methods of the configured formatter class, offered in pipe-filter
     * position. Own methods only (matches [offerClassMembers]' static-context
     * behaviour); inherited methods still resolve through the reference layer.
     * Magic methods are skipped — the compiler's filter-name pattern requires
     * a leading letter so `__call` and friends can never be written as a pipe.
     */
    private fun offerFormatterMethods(
        phpIndex: PhpIndex,
        settings: TemplateLangSettings,
        matcher: PrefixMatcher,
        result: CompletionResultSet,
    ) {
        val classes = SkyTemplateFormatterLookup.formatterClasses(phpIndex, settings)
        val seen = HashSet<String>()
        for (cls in classes) {
            for (method in cls.methods) {
                if (method.name.startsWith("__")) continue
                if (!matcher.prefixMatches(method.name)) continue
                if (!seen.add(method.name)) continue
                result.addElement(buildMethodLookup(method.name, cls.name, withParens = false))
            }
        }
    }

    private fun offerConstants(
        phpIndex: PhpIndex,
        matcher: PrefixMatcher,
        result: CompletionResultSet,
    ) {
        phpIndex.getAllConstantNames(matcher).forEach { name ->
            result.addElement(LookupElementBuilder.create(name))
        }
    }

    /**
     * `Cls::` member completion. Iterates the class's static methods and
     * constants. Inherited members are NOT included (mirrors PhpStorm's own
     * `getOwn*` behaviour for static-context completion); explicit FQN access
     * to inherited members still resolves through the reference layer.
     */
    private fun offerClassMembers(
        phpIndex: PhpIndex,
        settings: TemplateLangSettings,
        ctx: SkyTemplateCompletionContext.Result.ClassMember,
        result: CompletionResultSet,
    ) {
        val classes = lookupClasses(phpIndex, settings, ctx.classNameInSource)
        if (classes.isEmpty()) return

        // De-duplicate when several classes share the same FQN (rare; partial-
        // index states or duplicate declarations across vendor copies).
        val seenConstants = HashSet<String>()
        val seenMethods = HashSet<String>()
        for (cls in classes) {
            for (field in cls.fields) {
                if (!field.isConstant) continue
                if (!seenConstants.add(field.name)) continue
                result.addElement(
                    LookupElementBuilder.create(field.name)
                        .withTypeText(cls.name)
                )
            }
            if (ctx.constantsOnly) continue
            for (method in cls.methods) {
                if (!method.modifier.isStatic) continue
                if (!seenMethods.add(method.name)) continue
                result.addElement(buildMethodLookup(method.name, cls.name, ctx.withMethodParens))
            }
        }
    }

    private fun buildFunctionLookup(name: String, withParens: Boolean): LookupElement {
        var le = LookupElementBuilder.create(name).withTailText("()", true)
        if (withParens) le = le.withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
        return le
    }

    private fun buildMethodLookup(name: String, ownerName: String, withParens: Boolean): LookupElement {
        var le = LookupElementBuilder.create(name)
            .withTailText("()", true)
            .withTypeText(ownerName)
        if (withParens) le = le.withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
        return le
    }

    /**
     * Resolve a `Cls::` qualifier to candidate classes, sharing
     * [SkyTemplatePhpReference]'s candidate-FQN chain (`candidateFqns`) so
     * completion and reference resolution see the same class: `useClass`
     * alias expansion → configured namespace → global namespace → (if all
     * of those miss) a simple-name fallback across every namespace. Without
     * alias support, `{=Bar::<caret>}` under a `useClass = ["…Foo as Bar"]`
     * setting resolved `Bar` as a literal (nonexistent) class name and
     * silently offered no members.
     */
    // internal (not private): unit-tested directly from
    // SkyTemplateCompletionContributorTest without needing to drive the
    // full CompletionParameters/CompletionResultSet machinery.
    internal fun lookupClasses(
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
     * Build the ordered FQN candidate list for a name written in template
     * source. Absolute FQNs (leading `\`) are returned as-is; everything
     * else is expanded through useClass / configured-namespace / global in
     * that order — mirrors `SkyTemplatePhpReference.candidateFqns`.
     */
    private fun candidateFqns(nameInSrc: String, settings: TemplateLangSettings): List<String> {
        if (nameInSrc.startsWith("\\")) return listOf(nameInSrc)
        val out = LinkedHashSet<String>()
        expandUseClassAlias(nameInSrc, settings)?.let(out::add)
        val ns = settings.namespace.trim().trim('\\')
        if (ns.isNotEmpty()) out.add("\\$ns\\$nameInSrc")
        out.add("\\$nameInSrc")
        return out.toList()
    }

    /**
     * If the first segment of [nameInSrc] matches a `useClass` alias,
     * replace that segment with the full FQN. Mirrors
     * `SkyTemplatePhpReference.expandUseClassAlias`.
     */
    private fun expandUseClassAlias(nameInSrc: String, settings: TemplateLangSettings): String? {
        if (settings.useClass.isEmpty()) return null
        val sepIdx = nameInSrc.indexOf('\\')
        val firstSeg = if (sepIdx < 0) nameInSrc else nameInSrc.substring(0, sepIdx)
        val rest = if (sepIdx < 0) "" else nameInSrc.substring(sepIdx)
        for (entry in settings.useClass) {
            val parsed = parseUseClassEntry(entry) ?: continue
            if (parsed.alias == firstSeg) return "\\${parsed.fqn}$rest"
        }
        return null
    }

    private data class UseClassEntry(val fqn: String, val alias: String)

    private fun parseUseClassEntry(entry: String): UseClassEntry? {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return null
        val asIdx = indexOfAsKeyword(trimmed)
        if (asIdx < 0) {
            val fqn = trimmed.trimStart('\\')
            if (fqn.isEmpty()) return null
            val alias = fqn.substringAfterLast('\\')
            return UseClassEntry(fqn, alias)
        }
        val fqn = trimmed.substring(0, asIdx).trim().trimStart('\\')
        val alias = trimmed.substring(asIdx + " as ".length).trim()
        if (fqn.isEmpty() || alias.isEmpty()) return null
        return UseClassEntry(fqn, alias)
    }

    /** `" as "` token, ASCII case-insensitive, requiring whitespace on both sides. */
    private fun indexOfAsKeyword(s: String): Int {
        var i = 0
        while (i < s.length - 3) {
            if (s[i].isWhitespace()
                && (s[i + 1] == 'a' || s[i + 1] == 'A')
                && (s[i + 2] == 's' || s[i + 2] == 'S')
                && s[i + 3].isWhitespace()
            ) return i
            i++
        }
        return -1
    }
}
