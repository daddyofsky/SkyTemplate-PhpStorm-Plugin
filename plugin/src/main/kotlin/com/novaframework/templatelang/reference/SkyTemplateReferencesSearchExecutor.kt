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
            { element, _ ->
                walkAndCollectSkyRefs(element, target, consumer)
            },
            scope,
            name,
            UsageSearchContext.ANY,
            true,  // case-sensitive — PHP symbol names are case-sensitive at the index
        )
    }

    /**
     * Walk leaf → parent on this candidate, collecting the first
     * [SkyTemplatePhpReference] that resolves to [target]. Returns false
     * to stop the outer search early when the consumer rejects (cancellation).
     */
    private fun walkAndCollectSkyRefs(
        leaf: PsiElement,
        target: PsiElement,
        consumer: Processor<in PsiReference>,
    ): Boolean {
        var node: PsiElement? = leaf
        while (node != null) {
            for (ref in node.references) {
                if (ref is SkyTemplatePhpReference && ref.isReferenceTo(target)) {
                    if (!consumer.process(ref)) return false
                    return true  // one ref per candidate offset is enough
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
