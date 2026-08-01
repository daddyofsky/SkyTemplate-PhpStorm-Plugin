package com.novaframework.templatelang.reference

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.jetbrains.php.lang.psi.elements.Constant
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Explicit `ReferencesSearch.QueryExecutor` for PHP declarations whose
 * usages live in SkyTemplate files. Registered alongside PhpStorm's
 * built-in PHP searchers (`PhpReferenceAtOffsetSearcher` family); IntelliJ
 * runs every registered executor and unions the results, so this is a
 * safety net rather than a replacement.
 *
 * **Why a custom executor is needed even with a `psi.referenceContributor`
 * already wired up.** The reference contributor exposes our refs at the
 * PSI level, so `findReferenceAt` (Ctrl+Click / Go to Definition) walks
 * leaf → parent and finds them. Find Usages takes a different code path
 * via `ReferencesSearch.search(target)` whose default executors iterate
 * the index of word occurrences and ask each candidate's PSI for refs.
 * For an `XmlAttributeValue` such as `<a href="{=foo()}">` the leaf
 * (`XmlTokenImpl`) returns no references at all (HTML attaches refs at
 * the value-element level, not the leaf), and PhpStorm's default search
 * executors don't always walk up far enough to discover ours. Result:
 * Go to Definition works (leaf-up walk), Find Usages misses (leaf-only
 * scan).
 *
 * This executor:
 *   1. iterates the platform word index for the target's simple name,
 *      restricted to `.sky` / `.html` project files,
 *   2. for each candidate offset walks the PSI from the leaf upward
 *      (capped at PsiFile) until it hits a [SkyTemplatePhpReference],
 *   3. filters by `isReferenceTo(target)` so unrelated same-name refs
 *      don't pollute results.
 *
 * The walk is needed because the PSI level at which our reference is
 * attached varies — `XmlAttributeValue`, `XmlText`, `HtmlTag` body, or
 * `HtmlDocument` for top-level constructs. The contributor returns the
 * ref at the smallest covering element; this executor follows the same
 * walk in the search direction.
 */
class SkyTemplateReferencesSearchExecutor :
    QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(/* requireReadAction = */ true) {

    override fun processQuery(
        params: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val target = params.elementToSearch
        if (!isCandidatePhpDeclaration(target)) return
        val project = target.project
        if (project.isDisposed) return
        if (!TemplateLangSettings.getInstance(project).isEnabled) return
        val name = (target as? PsiNamedElement)?.name?.takeIf { it.isNotEmpty() } ?: return

        val helper = PsiSearchHelper.getInstance(project)
        // Intersect with the caller's chosen scope so a user-restricted
        // search (e.g. "Module only") still applies. Without intersection
        // we'd over-search every template file in the project regardless
        // of the user's filter.
        val scope = SkyTemplateFilesScope(project).intersectWith(params.effectiveSearchScope)

        helper.processElementsWithWord(
            { element, offsetInElement ->
                walkAndCollectSkyRefs(element, offsetInElement, target, consumer)
            },
            scope,
            name,
            UsageSearchContext.ANY,
            // PHP function / method / class names resolve case-insensitively
            // (only constants and variables are case-sensitive in PHP), so
            // `{=MyFunc()}` must be found when searching usages of `myFunc`.
            // Safe to widen here: `ref.isReferenceTo(target)` below still
            // requires an exact PSI-resolution match, so a differently-cased
            // Constant/Field target simply won't match and is filtered out.
            false,
        )
    }

    /**
     * Walk leaf → parent on this candidate, collecting the
     * [SkyTemplatePhpReference] whose range covers this specific word
     * occurrence and resolves to [target]. Returns false to stop the outer
     * search early when the consumer rejects (cancellation).
     *
     * [offsetInElement] identifies WHICH occurrence of the word within
     * [leaf] this callback invocation is for — `processElementsWithWord`
     * invokes the callback once per occurrence, so a single host element
     * covering two `{=foo()}` calls triggers two separate calls with
     * different offsets. Previously this parameter was ignored and the walk
     * always reported the FIRST matching ref found on the node, regardless
     * of which occurrence triggered the callback — duplicating the first
     * usage and silently dropping every other usage in the same element.
     */
    private fun walkAndCollectSkyRefs(
        leaf: PsiElement,
        offsetInElement: Int,
        target: PsiElement,
        consumer: Processor<in PsiReference>,
    ): Boolean {
        val absoluteOffset = leaf.textRange.startOffset + offsetInElement
        var node: PsiElement? = leaf
        while (node != null) {
            val nodeStart = node.textRange.startOffset
            for (ref in node.references) {
                if (ref is SkyTemplatePhpReference && ref.isReferenceTo(target)) {
                    val absoluteRefRange = ref.rangeInElement.shiftRight(nodeStart)
                    if (absoluteRefRange.containsOffset(absoluteOffset)) {
                        if (!consumer.process(ref)) return false
                        return true
                    }
                }
            }
            if (node is PsiFile) break
            node = node.parent
        }
        return true
    }

    private fun isCandidatePhpDeclaration(element: PsiElement): Boolean = when (element) {
        is Function -> true
        is Method -> true
        is PhpClass -> true
        is Constant -> true
        is Field -> element.isConstant
        else -> false
    }
}
