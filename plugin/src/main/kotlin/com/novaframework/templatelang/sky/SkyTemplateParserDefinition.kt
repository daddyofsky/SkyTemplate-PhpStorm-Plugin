package com.novaframework.templatelang.sky

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

class SkyTemplateParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = SkyTemplateLexer()

    override fun createParser(project: Project?): PsiParser = SkyTemplatePsiParser()

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = T.COMMENTS
    override fun getStringLiteralElements(): TokenSet = T.STRINGS

    override fun createElement(node: ASTNode): PsiElement = LeafPsiElement(node.elementType, node.text)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = SkyTemplateFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType(SkyTemplateLanguage)
    }
}

class SkyTemplateFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, SkyTemplateLanguage) {
    override fun getFileType(): FileType = SkyTemplateFileType
    override fun toString(): String = "SkyTemplate File"
}

/**
 * Trivial parser: produces a flat tree where every token is a child of the file
 * element. Real structural parsing (Tag / Variable / Expression nodes) is M3.
 */
class SkyTemplatePsiParser : PsiParser {
    override fun parse(root: com.intellij.psi.tree.IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        rootMarker.done(root)
        return builder.treeBuilt
    }
}
