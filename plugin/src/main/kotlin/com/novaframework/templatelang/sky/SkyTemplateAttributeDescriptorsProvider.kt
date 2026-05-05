package com.novaframework.templatelang.sky

import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlElement
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.XmlAttributeDescriptor
import com.intellij.xml.XmlAttributeDescriptorsProvider
import com.novaframework.templatelang.settings.TemplateLangFileFilter

/**
 * Whitelists `tpl-*` attributes (e.g. `tpl-checked`, `tpl-disabled`,
 * `tpl-class`) so PhpStorm's HTML inspections do not flag them as
 * "Unknown HTML attribute".
 *
 * SkyTemplate compiler treats every `tpl-X="…"` as a smart attribute that
 * gets unwrapped into `{?…}X{/}`. Acting as if these names are known is the
 * simplest way to make HTML happy without disabling the inspection.
 *
 * Active only when the SkyTemplate plugin is enabled in project settings.
 */
class SkyTemplateAttributeDescriptorsProvider : XmlAttributeDescriptorsProvider {

    override fun getAttributeDescriptors(tag: XmlTag?): Array<XmlAttributeDescriptor> =
        XmlAttributeDescriptor.EMPTY

    override fun getAttributeDescriptor(attributeName: String?, tag: XmlTag?): XmlAttributeDescriptor? {
        if (attributeName == null || tag == null) return null
        if (!attributeName.startsWith("tpl-")) return null
        if (!TemplateLangFileFilter.shouldProcess(tag.containingFile)) return null
        return TplDashAttributeDescriptor(attributeName)
    }
}

/**
 * Minimal descriptor — we only need it to be non-null so HtmlUnknownAttribute
 * doesn't fire. We intentionally do not constrain the attribute value or
 * report a declaration; future M7 may add value validation for boolean-style
 * tpl attributes.
 */
internal class TplDashAttributeDescriptor(private val attrName: String) : XmlAttributeDescriptor {
    override fun isRequired(): Boolean = false
    override fun isFixed(): Boolean = false
    override fun hasIdType(): Boolean = false
    override fun hasIdRefType(): Boolean = false
    override fun getDefaultValue(): String? = null
    override fun isEnumerated(): Boolean = false
    override fun getEnumeratedValues(): Array<String>? = null
    override fun validateValue(context: XmlElement?, value: String?): String? = null
    override fun getName(context: PsiElement?): String = attrName
    override fun getName(): String = attrName
    override fun init(element: PsiElement?) {}
    override fun getDeclaration(): PsiElement? = null
}
