package com.novaframework.templatelang.sky

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

class SkyTemplateBraceMatcher : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(T.LBRACE, T.RBRACE, true),
        BracePair(T.COMMENT_OPEN, T.COMMENT_CLOSE, false),
        BracePair(T.LPAREN, T.RPAREN, false),
        BracePair(T.LBRACKET, T.RBRACKET, false),
    )

    override fun getPairs(): Array<BracePair> = pairs
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
}
