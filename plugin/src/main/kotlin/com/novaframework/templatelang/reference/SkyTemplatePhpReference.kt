package com.novaframework.templatelang.reference

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.Parameter as PhpParameter
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Range-based, poly-variant PsiReference attached to template host elements
 * for SkyTemplate constructs that map onto PHP symbols.
 *
 * **Resolution strategy** — for each reference we generate an ordered list of
 * candidate FQNs and union the lookups. Order:
 *
 *   1. `useClass` alias expansion. `useClass = ["App\Helpers\Foo as Bar"]`
 *      makes `{=Bar::method()}` resolve under `\App\Helpers\Foo`. Entries
 *      without an explicit `as` use the class basename as the alias.
 *   2. Configured namespace prefix (if non-empty). With `namespace = App`,
 *      `{=foo()}` is also tried as `\App\foo`.
 *   3. Global namespace fallback — `{=foo()}` is always also tried as `\foo`.
 *
 * Why all three matter for Find Usages / Go to Definition:
 *
 *   - Framework helper functions (`htmlspecialchars()`-like), Composer-bundled
 *     globals, and small project-local helpers commonly live in the root
 *     namespace even when the project's main code is namespaced. Without (3),
 *     setting `namespace = App` silently breaks resolution for all of them.
 *   - Project-namespaced symbols are why (2) exists.
 *   - `useClass` is the SkyTemplate equivalent of PHP's `use … as …`. Without
 *     (1), template-side aliases that map a short identifier to a long FQN
 *     never resolve.
 *
 * Absolute FQNs (leading `\` written by the user) bypass all three steps and
 * are looked up exactly as written. The poly-variant API is preserved so
 * PhpStorm offers a candidate picker when multiple namespaces define the
 * same simple name (rare, but the duplicate-declarations test exercises it).
 */
class SkyTemplatePhpReference(
    host: PsiElement,
    rangeInElement: TextRange,
    val kind: SkyTemplateRefDetector.Kind,
    val nameInSource: String,
    val classNameInSource: String? = null,
    /**
     * For [SkyTemplateRefDetector.Kind.PARAMETER_NAME] only — the simple name
     * of the function or method whose parameter list this argument belongs to.
     * Required to locate the callee before resolving the parameter PSI.
     */
    val callTargetName: String? = null,
    /**
     * For [SkyTemplateRefDetector.Kind.PARAMETER_NAME] only — when the call is
     * a static method (`Cls::method(name: $x)`), the class identifier as
     * written in the template. `null` for a free function call or for pipe
     * filter named args (always free functions).
     */
    val callTargetClass: String? = null,
) : PsiPolyVariantReferenceBase<PsiElement>(
    host, rangeInElement,
    // PARAMETER_NAME is soft (Phase 1 spec D-2): a callee whose parameter list
    // we cannot resolve — dynamic call, missing PHP fixture, PHP < 8 target —
    // would otherwise surface noisy "unresolved reference" warnings on every
    // named arg. Other kinds keep the historical soft=false behaviour.
    /* soft = */ kind == SkyTemplateRefDetector.Kind.PARAMETER_NAME,
) {
    // soft=false (0.5.32): IntelliJ's PsiMultiReference.chooseReference() prefers
    // non-soft refs over soft when multiple references cover the same offset.
    // For `<a href="{=getKakaoLoginUrl()}">`, the platform attaches built-in URL
    // references (`SchemeReference`, `AuthorityReference`, `UrlPathReference`,
    // all non-soft) to the attribute value, and they cover the SkyTemplate
    // construct's range. With our refs marked soft, PsiMultiReference picked
    // the URL refs as primary; Ctrl+Click then asked the URL ref to resolve
    // (and it returned null), so navigation silently failed even though our
    // PHP-symbol resolution would have succeeded. Becoming non-soft makes
    // PsiMultiReference prefer ours, restoring Ctrl+Click and Find Usages
    // for any template construct sitting inside an attribute value where
    // the platform also creates URL-shaped references.
    //
    // Trade-off: an unresolved SkyTemplate ref (template references a PHP
    // function that doesn't exist) now produces an "unresolved reference"
    // warning instead of being silently soft. With the 0.5.31 simple-name
    // fallback, this only fires for genuinely undefined symbols — which is
    // useful diagnostic information rather than noise.

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        if (project.isDisposed) return ResolveResult.EMPTY_ARRAY
        // Every branch below ultimately calls into PhpIndex, which throws
        // IndexNotReadyException during indexing. The platform invokes
        // multiResolve from highlighting / XmlReferenceInspectionBase even in
        // dumb mode, so bail out cleanly instead of letting the exception
        // propagate up the inspection runner.
        if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY
        val settings = TemplateLangSettings.getInstance(project)
        val phpIndex = PhpIndex.getInstance(project)

        val candidates: Collection<PsiElement> = when (kind) {
            SkyTemplateRefDetector.Kind.FUNCTION ->
                lookupSymbol(
                    nameInSource, settings,
                    fqn = { phpIndex.getFunctionsByFQN(it) },
                    simple = { phpIndex.getFunctionsByName(it) },
                )
            SkyTemplateRefDetector.Kind.CLASS ->
                lookupSymbol(
                    nameInSource, settings,
                    fqn = { phpIndex.getClassesByFQN(it) },
                    simple = { phpIndex.getClassesByName(it) },
                )
            SkyTemplateRefDetector.Kind.CONSTANT ->
                lookupSymbol(
                    nameInSource, settings,
                    fqn = { phpIndex.getConstantsByFQN(it) },
                    simple = { phpIndex.getConstantsByName(it) },
                )
            SkyTemplateRefDetector.Kind.METHOD -> {
                val classes = lookupClasses(phpIndex, settings) ?: return ResolveResult.EMPTY_ARRAY
                val memberName = simpleName(nameInSource)
                classes.mapNotNull { it.findMethodByName(memberName) }
            }
            SkyTemplateRefDetector.Kind.CLASS_CONSTANT -> {
                val classes = lookupClasses(phpIndex, settings) ?: return ResolveResult.EMPTY_ARRAY
                val memberName = simpleName(nameInSource)
                classes.flatMap { phpClass ->
                    listOfNotNull(
                        phpClass.findOwnFieldByName(memberName, /* searchInherited = */ false)
                            ?: phpClass.findFieldByName(memberName, /* searchInherited = */ true)
                    )
                }.toSet()
            }
            SkyTemplateRefDetector.Kind.PARAMETER_NAME -> {
                val targetName = callTargetName ?: return ResolveResult.EMPTY_ARRAY
                val parameters: Collection<PhpParameter> = if (callTargetClass != null) {
                    // Static-method named arg — resolve the class through the
                    // configured candidate FQN chain, then locate the method
                    // and filter its parameters by [nameInSource].
                    val classes = lookupSymbol(
                        callTargetClass, settings,
                        fqn = { phpIndex.getClassesByFQN(it) },
                        simple = { phpIndex.getClassesByName(it) },
                    )
                    classes
                        .mapNotNull { it.findMethodByName(targetName) }
                        .flatMap { it.parameters.toList() }
                        .filter { it.name == nameInSource }
                } else {
                    // Free-function named arg (paren call OR pipe filter).
                    lookupSymbol(
                        targetName, settings,
                        fqn = { phpIndex.getFunctionsByFQN(it) },
                        simple = { phpIndex.getFunctionsByName(it) },
                    ).flatMap { it.parameters.toList() }
                     .filter { it.name == nameInSource }
                }
                parameters.toSet()
            }
        }
        if (candidates.isEmpty()) return ResolveResult.EMPTY_ARRAY
        return candidates.map(::PsiElementResolveResult).toTypedArray()
    }

    /**
     * Filled in by [SkyTemplateCompletionProvider] (separate CompletionContributor
     * for typing-time completion). Reference-based variants would only fire after
     * a syntactically-complete construct exists, which is too late in practice.
     */
    override fun getVariants(): Array<Any> = emptyArray()

    private fun lookupClasses(
        phpIndex: PhpIndex,
        settings: TemplateLangSettings,
    ): Collection<PhpClass>? {
        val classNameInSrc = classNameInSource ?: return null
        return lookupSymbol(
            classNameInSrc, settings,
            fqn = { phpIndex.getClassesByFQN(it) },
            simple = { phpIndex.getClassesByName(it) },
        )
    }

    /**
     * Resolve a template-side identifier through the FQN candidate chain
     * (`candidateFqns`), then — if nothing matched — fall back to a simple-
     * name lookup against `PhpIndex.get*ByName`.
     *
     * **Why the simple-name fallback exists.** Users frequently leave the
     * SkyTemplate `namespace` setting at the default `\` (root) even when
     * their project defines functions / classes / constants in non-global
     * namespaces (e.g. `namespace App\Helpers; function urlFor() {}`). With
     * FQN-only resolution, every such symbol silently fails to resolve — the
     * `\<name>` global candidate misses it because the actual FQN is
     * `\App\Helpers\<name>`. The user-reported case `<a href="{=getKakaoLoginUrl()}">`
     * is exactly this pattern: detection succeeds, resolution returns nothing,
     * Find Usages / Ctrl+Click both silently fail.
     *
     * **Why fallback only when FQN candidates are empty.** If the FQN chain
     * already resolves something, we trust it (the user has either configured
     * a namespace or written an absolute FQN). Falling back on top of a hit
     * would over-resolve into unrelated namespaces, causing a poly-variant
     * picker on every Ctrl+Click.
     *
     * **Multiple definitions sharing a simple name** are still surfaced:
     * `getFunctionsByName(simple)` returns ALL matching functions across all
     * namespaces. PhpStorm shows the candidate picker, the user disambiguates.
     * Find Usages from any of those definitions correctly counts the template
     * hit because `multiResolve` includes all of them.
     */
    private fun <T : PsiElement> lookupSymbol(
        nameInSrc: String,
        settings: TemplateLangSettings,
        fqn: (String) -> Collection<T>,
        simple: (String) -> Collection<T>,
    ): Collection<T> {
        val out = LinkedHashSet<T>()
        for (candidate in candidateFqns(nameInSrc, settings)) {
            out += fqn(candidate)
        }
        if (out.isEmpty() && !nameInSrc.startsWith("\\")) {
            // Absolute FQNs (`\Foo`) opted out of inference — don't widen them.
            val simpleName = nameInSrc.substringAfterLast('\\')
            if (simpleName.isNotEmpty()) out += simple(simpleName)
        }
        return out
    }

    /**
     * Build the ordered FQN candidate list for a name written in template source.
     * Absolute FQNs (leading `\`) are returned as-is; everything else is expanded
     * through useClass / configured-namespace / global in that order.
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
     * If the first segment of [nameInSrc] matches a `useClass` alias, replace
     * that segment with the full FQN. Examples (with `useClass = ["App\\Helpers\\Foo as Bar"]`):
     *
     *   nameInSrc = "Bar"             → "\\App\\Helpers\\Foo"
     *   nameInSrc = "Bar\\Inner"      → "\\App\\Helpers\\Foo\\Inner"
     *
     * For entries without an explicit `as`, the alias is the class basename.
     * `useClass = ["App\\Helpers\\Foo"]` ⇒ alias is `Foo`, so `{=Foo::m()}`
     * resolves under `\App\Helpers\Foo`.
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

    /**
     * Parse a `useClass` setting entry into its FQN + alias parts. Tolerates
     * leading `\` and surrounding whitespace. Returns null for empty / invalid
     * entries so the caller can skip them silently.
     */
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

    private fun simpleName(qualified: String): String =
        qualified.substringAfterLast('\\')

    override fun toString(): String =
        "SkyTemplatePhpReference($kind: $nameInSource${classNameInSource?.let { " on $it" } ?: ""})"
}
