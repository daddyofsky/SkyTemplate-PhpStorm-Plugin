package com.novaframework.templatelang.reference

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.xml.XmlFile
import com.intellij.util.ProcessingContext
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateFile

/**
 * Forces SkyTemplate's PHP-symbol resolution to win over the host language's
 * own reference at the click offset.
 *
 * **Why this exists.** Inside a `<script>` block on an HTML host file, JS PSI
 * parses `{=foo() . bar()}` as a chained member-access expression
 * (`JSReferenceExpression` whose qualifier is `foo()` and whose name is `bar`).
 * The element's intrinsic self-reference covers the `bar` name range — same
 * range as our [SkyTemplatePhpReference]. The platform's
 * `PsiMultiReference` comparator returns 0 for equal ranges and falls back to
 * stable-sort insertion order; the JS self-reference is collected first
 * because it sits closer to the leaf in the leaf-to-file walk, so JS wins
 * and its resolution (which falls back to the chain's qualifier `foo` when
 * the member can't be typed) drives Cmd+click. Result: clicking on `bar`
 * navigates to `foo`.
 *
 * The same pattern shows up for `{? foo() . bar()}` (`?`-prefix expression
 * tag) but NOT for `{? foo() && bar()}`, because `&&` parses as a binary
 * expression with two independent operand references — there's no chain
 * self-reference covering `bar` to compete with our PhpReference.
 *
 * Solution: register a [GotoDeclarationHandler]. Handlers run BEFORE the
 * platform's reference-based resolution, and the first one returning a
 * non-null target short-circuits the rest. We delegate to the same
 * [SkyTemplateReferenceProvider] used for Find Usages, walk leaf-to-file
 * with hit-on-first-cover semantics, and return the resolution if we own
 * a reference at the offset. JS's chain reference is bypassed entirely.
 *
 * Scope:
 *   - Fires only on HTML/XML host files (matches the contributor's filter).
 *   - Fires only when our provider returns a ref covering the offset.
 *   - Returns null otherwise — leaving regular JS / HTML / CSS navigation
 *     unchanged for everything outside template tags.
 */
class SkyTemplateGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null
        val file = sourceElement.containingFile ?: return null
        if (!TemplateLangFileFilter.shouldProcess(file)) return null
        if (file !is XmlFile && file !is SkyTemplateFile) return null

        val ourRef = findSkyRefAt(sourceElement, offset) ?: return null
        val targets = when (ourRef) {
            is PsiPolyVariantReference -> ourRef.multiResolve(false)
                .mapNotNull { it.element }
                .toTypedArray()
            else -> ourRef.resolve()?.let { arrayOf(it) }
        }
        return if (targets.isNullOrEmpty()) null else targets
    }

    /**
     * Walk leaf-to-file like the platform's [com.intellij.psi.impl.SharedPsiElementImplUtil.findReferenceAt]
     * does, but consult ONLY our provider. The first matching ref wins. This
     * mirrors [SkyTemplateReferenceIntegrationTest.simulatedFindReferenceAt]
     * — kept locally to avoid coupling production code to a test helper.
     */
    private fun findSkyRefAt(sourceElement: PsiElement, offset: Int): SkyTemplatePhpReference? {
        val provider = SkyTemplateReferenceProvider()
        var element: PsiElement? = sourceElement
        // findElementAt may have given us a leaf; walk up checking every
        // ancestor that could carry a ref. Stop at the file root.
        while (element != null) {
            val range = element.textRange
            if (range != null) {
                val refs = provider.getReferencesByElement(element, ProcessingContext())
                val relOffset = offset - range.startOffset
                val match = refs.firstOrNull { it.rangeInElement.contains(relOffset) }
                if (match is SkyTemplatePhpReference) return match
            }
            element = element.parent
        }
        return null
    }
}
