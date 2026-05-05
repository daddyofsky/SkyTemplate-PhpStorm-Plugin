package com.novaframework.templatelang.sky

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

/**
 * Registers a [WordsScanner] for the SkyTemplate language so the IntelliJ
 * platform's word index includes IDENTIFIER / STRING / COMMENT tokens from
 * `.sky` files.
 *
 * Without this, `PsiSearchHelper.processElementsWithWord` skips `.sky`
 * files entirely — the platform builds the word index per-language via
 * each language's `FindUsagesProvider.getWordsScanner()`. The
 * [SkyTemplatePhpImplicitUsageProvider] relies on this index to detect
 * whether a PHP symbol's name appears in any template file.
 *
 * Find Usages on a SkyTemplate identifier itself is intentionally NOT
 * supported (`canFindUsagesFor = false`) — template identifiers map onto
 * PHP symbols, and Find Usages should originate from the PHP declaration
 * (which uses [SkyTemplatePhpReference] to discover template hits). The
 * methods that describe an element as a usage target are stubs; they are
 * unreachable when `canFindUsagesFor` returns `false`.
 */
class SkyTemplateFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        SkyTemplateLexer(),
        /* identifierTokens = */ com.intellij.psi.tree.TokenSet.create(T.IDENTIFIER),
        /* commentTokens = */ T.COMMENTS,
        /* literalTokens = */ T.STRINGS,
    )

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean = false
    override fun getHelpId(psiElement: PsiElement): String? = null
    override fun getType(element: PsiElement): String = ""
    override fun getDescriptiveName(element: PsiElement): String = element.text ?: ""
    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = element.text ?: ""
}
