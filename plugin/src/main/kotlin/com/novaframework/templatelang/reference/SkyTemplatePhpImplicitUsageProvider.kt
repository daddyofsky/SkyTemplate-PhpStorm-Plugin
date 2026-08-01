package com.novaframework.templatelang.reference

import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.jetbrains.php.lang.psi.elements.Constant
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Tells PhpStorm's "Unused declaration" daemon that a PHP function /
 * method / class constant / class is implicitly used when its name appears
 * in any project-source `.sky` / `.html` file.
 *
 * **Why this is needed.** PhpStorm's dead-code daemon
 * (`PhpUnusedSymbolsCache.isUsedImplicitlyOrFromCache`) effectively limits
 * its `ReferencesSearch` fall-through to the PHP-language file scope —
 * cross-language usages from templates are invisible to that path. The
 * user demonstrated this by observing that even literal PHP code embedded
 * in an `.html` host (`<?php foo2(); ?>`) does not clear the "unused"
 * highlight; only re-mapping `.html` to the PHP file type does. Since we
 * don't want users changing File Types (it would lose HTML highlighting),
 * the IntelliJ-standard alternative is `ImplicitUsageProvider`.
 *
 * **Why a word-index query and not a custom index.** The 0.5.24 attempt
 * carried its own project-level index of names (`SkyTemplateProjectIndex`)
 * with `MODIFICATION_COUNT`-based caching, which proved fragile —
 * incremental edits did not always invalidate the cache as expected.
 * Querying `PsiSearchHelper.processElementsWithWord` against the IntelliJ
 * platform's word index sidesteps the problem: the word index is
 * incrementally maintained by the platform, and a single hit is enough.
 *
 * **False positives are acceptable.** A name match is sufficient for
 * "implicitly used" — we don't verify that the hit is structurally inside
 * a `{ … }` template construct. If a project has prose text "use foo2 for
 * formatting" in a template file, the function `foo2()` will be marked
 * "used". For dead-code analysis the bias goes the safe direction: false
 * positives keep code marked as used, false negatives would incorrectly
 * suggest deleting a live function.
 *
 * **Toggle latency.** PhpStorm's daemon doesn't auto-rerun on PHP files
 * when an unrelated template file edits — the unused mark refreshes only
 * when the PHP file is re-analyzed (tab switch, edit, idle daemon cycle).
 * This is a deliberate trade-off vs. the 0.5.27 approach of forcing
 * `DaemonCodeAnalyzer.restart(file)` on every PSI tree change in a
 * template file, which the user reported as a performance issue.
 */
class SkyTemplatePhpImplicitUsageProvider : ImplicitUsageProvider {

    override fun isImplicitUsage(element: PsiElement): Boolean {
        if (!isCandidatePhpDeclaration(element)) return false
        val project = element.project
        if (project.isDisposed) return false
        if (!TemplateLangSettings.getInstance(project).isEnabled) return false
        val name = (element as? PsiNamedElement)?.name?.takeIf { it.isNotEmpty() } ?: return false
        return wordAppearsInTemplateFiles(element, name)
    }

    override fun isImplicitRead(element: PsiElement): Boolean = isImplicitUsage(element)
    override fun isImplicitWrite(element: PsiElement): Boolean = false

    /**
     * The five PHP declaration shapes [SkyTemplateRefDetector] can emit:
     *   - top-level / namespaced functions  (FUNCTION)
     *   - classes                            (CLASS)
     *   - methods                            (METHOD)
     *   - global / namespaced constants      (CONSTANT)
     *   - class constants (PHP `Field`)      (CLASS_CONSTANT)
     *
     * Plain class-level fields (instance properties) aren't reachable from
     * template syntax, so [Field] still counts only if it's a class
     * constant.
     */
    private fun isCandidatePhpDeclaration(element: PsiElement): Boolean = when (element) {
        is Function -> true            // covers top-level functions and Method
        is Method -> true              // explicit, in case Function check wouldn't hit
        is PhpClass -> true
        is Constant -> true
        is Field -> element.isConstant // only class CONSTANTS, not properties
        else -> false
    }

    private fun wordAppearsInTemplateFiles(element: PsiElement, name: String): Boolean {
        val helper = PsiSearchHelper.getInstance(element.project)
        val scope = SkyTemplateFilesScope(element.project)
        // Use ANY context — covers identifier-like text (IN_CODE) AND plain
        // text content (IN_PLAIN_TEXT). HTML XmlText body counts as plain
        // text in the platform's word-occurrence classification, and
        // template constructs sit inside that body.
        var found = false
        helper.processElementsWithWord(
            { _, _ ->
                found = true
                false  // stop at first hit
            },
            scope,
            name,
            UsageSearchContext.ANY,
            // PHP function / method / class names resolve case-insensitively
            // (`{=MyFunc()}` must count as usage of `function myfunc()`).
            // Already-documented false positives (name match without
            // structural verification) make widening the match harmless —
            // the bias here is "stays marked used", never "wrongly unused".
            false,
        )
        return found
    }
}
