package com.novaframework.templatelang.settings

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jps.model.java.JavaResourceRootType

/**
 * Maintains the IDE's *Resource Root* marker on the directory configured in
 * [TemplateLangSettings.templateRoot]. When [TemplateLangSettings.autoMarkTemplateRoot]
 * is enabled the marker is applied on project open (via the startup activity)
 * and on each settings apply (via [TemplateLangConfigurable]).
 *
 * Resource Root marking is purely a project-structure hint — it lets PhpStorm
 * differentiate the templates folder from regular sources in the project view
 * and signals to indexers that the contents are non-code resources. The
 * directory continues to be indexed (it lives inside a content root either way).
 *
 * Path resolution is split into [TemplateRootPath] so the relative-to-absolute
 * computation is unit-testable without IDE infrastructure.
 */
object TemplateRootResourceMarker {

    private val log = Logger.getInstance(TemplateRootResourceMarker::class.java)

    /**
     * Apply the resource-root marker if the setting is enabled and a
     * `templateRoot` is configured. No-op otherwise. Safe to call from any
     * thread — performs the project-structure mutation under a write action.
     *
     * Returns the [Result] for callers (e.g. the "Apply now" button) that want
     * to surface success/failure to the user. The startup activity ignores it.
     */
    fun applyIfEnabled(project: Project): Result {
        if (project.isDisposed) return Result.Disposed
        val settings = TemplateLangSettings.getInstance(project)
        if (!settings.isEnabled) return Result.PluginDisabled
        if (!settings.autoMarkTemplateRoot) return Result.AutoMarkDisabled
        return apply(project, settings.templateRoot)
    }

    /**
     * Apply the resource-root marker for [templateRootRelative], regardless of
     * the auto-mark toggle. Used by the "Apply now" button so the user can
     * trigger a one-shot mark even when the toggle is off (e.g. they enabled
     * the toggle for the first time and want immediate effect).
     */
    fun apply(project: Project, templateRootRelative: String): Result {
        if (project.isDisposed) return Result.Disposed
        val basePath = project.basePath ?: return Result.NoProjectBase
        val absolutePath = TemplateRootPath.resolveAbsolute(basePath, templateRootRelative)
            ?: return Result.EmptyTemplateRoot

        val templateRootDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
            ?: return Result.DirectoryMissing(absolutePath)
        if (!templateRootDir.isDirectory) return Result.NotADirectory(absolutePath)

        val module = pickModule(project, templateRootDir) ?: return Result.NoModule
        val markResult = markAsResourceRoot(module, templateRootDir)
        if (markResult is Result.Marked) {
            log.info("SkyTemplate: marked '${templateRootDir.path}' as Resource Root in module '${module.name}'")
        }
        return markResult
    }

    /**
     * Walk the project's modules and return the one whose content roots contain
     * [target]. Falls back to the first module so single-module projects still
     * work even when the file isn't (yet) inside a content root.
     */
    private fun pickModule(project: Project, target: VirtualFile): Module? {
        val byIndex = ProjectFileIndex.getInstance(project).getModuleForFile(target, /* honorExclusion = */ false)
        if (byIndex != null) return byIndex
        val modules = ModuleManager.getInstance(project).modules
        return modules.firstOrNull { module ->
            ModuleRootManager.getInstance(module).contentEntries.any { entry ->
                val file = entry.file ?: return@any false
                VfsUtilCore.isAncestor(file, target, /* strict = */ false)
            }
        } ?: modules.firstOrNull()
    }

    private fun markAsResourceRoot(module: Module, target: VirtualFile): Result {
        val rootManager = ModuleRootManager.getInstance(module)
        val containingEntry = rootManager.contentEntries.firstOrNull { entry ->
            val file = entry.file ?: return@firstOrNull false
            VfsUtilCore.isAncestor(file, target, /* strict = */ false)
        } ?: return Result.NotInsideContentRoot

        // Already marked? (read-only check, no model mutation)
        val targetUrl = target.url
        val alreadyMarked = containingEntry.getSourceFolders(JavaResourceRootType.RESOURCE)
            .any { it.url == targetUrl }
        if (alreadyMarked) return Result.AlreadyMarked

        return WriteAction.computeAndWait<Result, Throwable> {
            // Re-fetch a modifiable model — the snapshot above is read-only.
            val modifiable = rootManager.modifiableModel
            try {
                val entry: ContentEntry? = modifiable.contentEntries.firstOrNull { entry ->
                    val file = entry.file ?: return@firstOrNull false
                    VfsUtilCore.isAncestor(file, target, /* strict = */ false)
                }
                if (entry == null) {
                    modifiable.dispose()
                    return@computeAndWait Result.NotInsideContentRoot
                }
                // Re-check inside the modifiable view — another writer may have
                // beaten us.
                val redundant = entry.getSourceFolders(JavaResourceRootType.RESOURCE)
                    .any { it.url == targetUrl }
                if (redundant) {
                    modifiable.dispose()
                    return@computeAndWait Result.AlreadyMarked
                }
                entry.addSourceFolder(target, JavaResourceRootType.RESOURCE)
                modifiable.commit()
                Result.Marked(target.path)
            } catch (t: Throwable) {
                if (!modifiable.isDisposed) modifiable.dispose()
                throw t
            }
        }
    }

    sealed class Result {
        /** Successfully added the resource-root marker. */
        data class Marked(val absolutePath: String) : Result()
        /** Already marked — no-op. */
        object AlreadyMarked : Result()
        /** Plugin master switch is off; nothing applied. */
        object PluginDisabled : Result()
        /** `autoMarkTemplateRoot` toggle is off; only `applyIfEnabled` returns this. */
        object AutoMarkDisabled : Result()
        /** [TemplateLangSettings.templateRoot] is empty / blank. */
        object EmptyTemplateRoot : Result()
        /** Project has no `basePath`. */
        object NoProjectBase : Result()
        /** Directory at the resolved absolute path doesn't exist. */
        data class DirectoryMissing(val absolutePath: String) : Result()
        /** Resolved path exists but isn't a directory. */
        data class NotADirectory(val absolutePath: String) : Result()
        /** Project has zero modules. */
        object NoModule : Result()
        /** Target directory is outside every content root in the picked module. */
        object NotInsideContentRoot : Result()
        /** Project is already disposed. */
        object Disposed : Result()
    }
}

/**
 * Pure path resolution. No VFS / IDE state — unit-testable.
 */
internal object TemplateRootPath {

    /**
     * Combine the project's [basePath] with a user-specified [templateRoot]
     * (relative to the project root). Returns:
     *   - The absolute path on success
     *   - `null` when [templateRoot] is blank / null after trimming
     *   - The `templateRoot` itself when it's already absolute (starts with `/`
     *     on POSIX; treats `[A-Za-z]:` prefixes as absolute too for portability).
     *
     * Strips redundant `.` / leading `./`, normalises `\` to `/`, and trims
     * trailing slashes from the result.
     */
    fun resolveAbsolute(basePath: String, templateRoot: String?): String? {
        val rel = templateRoot?.trim() ?: return null
        if (rel.isEmpty()) return null
        val normalisedRel = rel.replace('\\', '/').removePrefix("./").trimEnd('/')
        if (normalisedRel.isEmpty()) return null
        if (isAbsolute(normalisedRel)) return normalisedRel
        val cleanedBase = basePath.trimEnd('/')
        return "$cleanedBase/$normalisedRel"
    }

    private fun isAbsolute(path: String): Boolean {
        if (path.startsWith('/')) return true
        // Windows drive letter (`C:/...`).
        return path.length >= 3 && path[1] == ':' && (path[2] == '/' || path[2] == '\\')
    }
}
