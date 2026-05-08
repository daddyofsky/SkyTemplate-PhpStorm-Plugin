package com.novaframework.templatelang.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.novaframework.templatelang.sky.SkyTemplateFile

/**
 * Decides whether a given file should be processed by the SkyTemplate
 * plugin's PSI-driven entry points (annotators, inspections, references,
 * completion, navigation). Combines the master enable switch with the
 * user-controlled file-extension whitelist from [TemplateLangSettings].
 *
 * Rules:
 *   1. `null` file or disposed project → reject.
 *   2. Master switch off → reject.
 *   3. SkyTemplate own file type (`SkyTemplateFile`) → accept regardless of
 *      whitelist — the file type itself is unambiguously ours.
 *   4. Otherwise: accept iff the file's extension (lower-cased, no dot)
 *      appears in the normalised whitelist.
 *
 * The whitelist defaults to `["html", "sky"]`. Files like `.md` / `.py` /
 * `.txt` that happen to contain `{?…}` / `{=…}` sequences are NOT processed
 * unless the user explicitly opts those extensions in.
 *
 * Both [shouldProcess] and [shouldProcessVirtualFile] are call-site
 * convenience overloads; they share the same accept/reject semantics.
 * Callers that already hold a `PsiFile` should prefer [shouldProcess].
 */
object TemplateLangFileFilter {

    /**
     * `true` if [file] passes every gate. Safe to call from any thread that
     * may legally touch [PsiFile.getProject].
     */
    fun shouldProcess(file: PsiFile?): Boolean {
        if (file == null) return false
        val project = file.project
        if (project.isDisposed) return false
        val settings = TemplateLangSettings.getInstance(project)
        if (!settings.isEnabled) return false
        if (file is SkyTemplateFile) return true
        val ext = extensionOf(file.name) ?: return false
        return ext in settings.fileExtensions
    }

    /**
     * `true` if the [VirtualFile] passes every gate. Used at call sites
     * that lack a parsed PSI (e.g. background indexers, file iterators).
     * Cannot detect [SkyTemplateFile] without PSI — falls back to the same
     * extension match used elsewhere; `.sky` is in the default whitelist
     * so this matters only when the user has removed it explicitly.
     */
    fun shouldProcessVirtualFile(project: Project, file: VirtualFile?): Boolean {
        if (file == null) return false
        if (project.isDisposed) return false
        val settings = TemplateLangSettings.getInstance(project)
        if (!settings.isEnabled) return false
        val ext = file.extension?.lowercase() ?: return false
        return ext in settings.fileExtensions
    }

    /**
     * Extract the file extension as a dot-less lower-case string, or null
     * when the file has no extension (`README`, `Makefile`, …). Trailing
     * dot (`foo.`) also returns null because there is no real extension
     * after the separator.
     */
    private fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }
}
