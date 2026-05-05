package com.novaframework.templatelang.reference

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Project-source scope of files we treat as SkyTemplate hosts. Driven by
 * [TemplateLangSettings.fileExtensions] (default `["html", "sky"]`) so the
 * scope tracks the same whitelist that gates annotators / inspections /
 * references. The `sky` extension is always included — losing it from the
 * user's whitelist must not silently demote the own file type out of search.
 *
 * Used by [SkyTemplatePhpImplicitUsageProvider] to query the IntelliJ word
 * index for symbol names referenced from templates, and by
 * [SkyTemplateUseScopeEnlarger] to extend the use-scope of PHP declarations
 * so refactorings / inspections traversing `getUseScope()` cover template
 * files too.
 *
 * Library content is excluded — templates are project-owned.
 */
internal class SkyTemplateFilesScope(private val project: Project) : GlobalSearchScope(project) {
    override fun contains(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase() ?: return false
        if (ext == "sky") return true
        return ext in TemplateLangSettings.getInstance(project).fileExtensions
    }

    override fun isSearchInModuleContent(aModule: Module): Boolean = true
    override fun isSearchInLibraries(): Boolean = false
}
