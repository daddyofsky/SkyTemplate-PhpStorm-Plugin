package com.novaframework.templatelang.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UseScopeEnlarger
import com.jetbrains.php.lang.psi.elements.Constant
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Extends a PHP element's "use scope" to cover `.sky` / `.html` files.
 *
 * Why: PhpStorm narrows `getUseScope()` for some declarations (e.g. a
 * private method's scope shrinks to its containing class), and
 * `ReferencesSearch` honours that narrowing. Without this enlarger,
 * inspections / refactorings traversing `getUseScope()` would skip
 * template files even when [SkyTemplatePhpReference] would resolve there.
 * Companion piece to [SkyTemplatePhpImplicitUsageProvider]: that one fixes
 * the daemon-level "unused" highlight, this one fixes search-scope
 * filtering for everything else.
 */
class SkyTemplateUseScopeEnlarger : UseScopeEnlarger() {
    override fun getAdditionalUseScope(element: PsiElement): SearchScope? {
        if (!isCandidate(element)) return null
        val project = element.project
        if (project.isDisposed) return null
        if (!TemplateLangSettings.getInstance(project).isEnabled) return null
        return SkyTemplateFilesScope(project)
    }

    private fun isCandidate(element: PsiElement): Boolean = when (element) {
        is Function -> true
        is Method -> true
        is PhpClass -> true
        is Constant -> true
        is Field -> element.isConstant
        else -> false
    }
}
