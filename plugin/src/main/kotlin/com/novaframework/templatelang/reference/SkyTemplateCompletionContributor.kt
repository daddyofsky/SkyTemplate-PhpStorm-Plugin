package com.novaframework.templatelang.reference

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.jetbrains.php.PhpIndex
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
            is SkyTemplateCompletionContext.Result.Function ->
                offerFunctions(phpIndex, matcher, ctx.withParens, result)
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
        val classFqn = qualifyClass(ctx.classNameInSource, settings)
        val classes = phpIndex.getClassesByFQN(classFqn)
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

    /** Apply project namespace to a bare identifier. Absolute FQNs pass through. */
    private fun qualifyClass(name: String, settings: TemplateLangSettings): String {
        if (name.startsWith("\\")) return name
        val ns = settings.namespace.trim().trim('\\')
        return if (ns.isEmpty()) "\\$name" else "\\$ns\\$name"
    }
}
