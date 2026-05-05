package com.novaframework.templatelang.reference

import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar

/**
 * Wires [SkyTemplateReferenceProvider] onto host elements so PhpStorm's
 * reference search picks up `{=foo()}`, `{=Cls::method()}`, `{c.NAME}`,
 * `{var|trim}` etc. as PHP-symbol usages — which means PHP `Find Usages`
 * includes template hits, and Ctrl+Click on a template identifier jumps to
 * the PHP definition.
 *
 * **Why broad element registration?**
 *
 * HTML PSI structure for template constructs is wildly inconsistent:
 *
 *   - `<p>{=foo()}</p>`           → `XmlText` (in tag body)
 *   - `attr="{=foo()}"`           → `XmlAttributeValue`
 *   - `<input {?cond}>`           → `XmlAttribute` name area (NO `XmlAttributeValue`)
 *   - `{=foo()}` (top-level)      → child of `HtmlDocumentImpl`, NO `XmlText`
 *
 * Trying to enumerate every host class is brittle, while file-root-only
 * references do not reliably participate in caret-based Go to Definition.
 * We register broadly and let the provider return refs only when the current
 * element range contains a detected symbol. The detector still scans the full
 * file once and caches the result, so this stays cheap in normal editor use.
 *
 * Performance: the provider caches the per-file reference list with
 * `PsiModificationTracker`, so the lex+detect pass runs at most once per
 * modification cycle.
 */
class SkyTemplateReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val provider = SkyTemplateReferenceProvider()
        // Register broadly. Ctrl+Click / Find Usages usually asks the PSI
        // element under the caret, not just the file root. The provider keeps
        // the expensive scan cached at file level and returns refs only when
        // the requested element actually covers a detected symbol range.
        //
        // Priority: HIGHER_PRIORITY ensures our contributor's refs appear
        // BEFORE other contributors' (and the platform's intrinsic) refs in
        // the array returned from `getReferences()`. `findReferenceAt(offset)`
        // returns the FIRST ref whose range contains the offset, so being
        // first in the list means our PHP-symbol ref wins when other
        // contributors (e.g. JS string-literal refs that span the entire
        // literal) cover the same offset. The user-reported case is a
        // template tag inside a JS string literal, where without the
        // priority bump the JS intrinsic ref for the literal could shadow
        // our per-identifier refs and steer Cmd+click navigation to the
        // wrong target.
        registrar.registerReferenceProvider(
            psiElement(),
            provider,
            PsiReferenceRegistrar.HIGHER_PRIORITY,
        )
    }
}
