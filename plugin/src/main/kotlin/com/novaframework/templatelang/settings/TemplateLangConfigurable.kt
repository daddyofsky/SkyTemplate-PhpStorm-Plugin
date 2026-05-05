package com.novaframework.templatelang.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/**
 * Project Settings → Tools → SkyTemplate.
 *
 * Single-engine configuration. The internal code path also handles Template_
 * files — both share the directive surface this plugin cares about — but the
 * setting itself is just a master enable/disable plus SkyTemplate runtime
 * mirrors used for navigation and inspections.
 */
class TemplateLangConfigurable(private val project: Project)
    : BoundConfigurable("SkyTemplate"), SearchableConfigurable {

    private val settings get() = TemplateLangSettings.getInstance(project)
    private val state get() = settings.state

    override fun getId(): String = "com.novaframework.skytemplate.settings"
    override fun getDisplayName(): String = "SkyTemplate"

    /**
     * After the panel's bindings flush user input into the State, run the
     * resource-root marker iff the toggle is on. Mirrors the pattern other
     * IntelliJ plugins use for "settings-driven side effects" (e.g. updating
     * include paths, registering external annotations).
     */
    override fun apply() {
        super.apply()
        // applyIfEnabled re-reads the persisted state, so it sees the values
        // we just wrote via the panel bindings.
        TemplateRootResourceMarker.applyIfEnabled(project)
    }

    override fun createPanel(): DialogPanel {
        val useClassArea = JBTextArea(6, 40).apply { lineWrap = false }
        useClassArea.text = state.useClass.joinToString("\n")

        val fileExtensionsArea = JBTextArea(4, 40).apply { lineWrap = false }
        fileExtensionsArea.text = state.fileExtensions.joinToString("\n")

        return panel {
            row {
                checkBox("Enable SkyTemplate support")
                    .bindSelected({ state.enabled }, { state.enabled = it })
                    .comment(
                        "Turns on syntax highlighting, references, and completions for SkyTemplate."
                    )
            }

            group("PHP namespace") {
                row("Root namespace:") {
                    textField()
                        .bindText({ state.namespace }, { state.namespace = it })
                        .columns(30)
                        .comment(
                            "Default <code>\\</code>. Identifiers in <code>{foo()}</code> resolve under this namespace."
                        )
                }
                row("Use class:") {
                    cell(useClassArea)
                        .align(AlignX.FILL)
                        .comment("One <code>ClassFqn as Alias</code> per line. Mirrors SkyTemplate <code>useClass</code> config.")
                        .onApply {
                            state.useClass = useClassArea.text.lineSequence()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toMutableList()
                        }
                        .onReset { useClassArea.text = state.useClass.joinToString("\n") }
                        .onIsModified {
                            useClassArea.text.lineSequence()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toList() != state.useClass.toList()
                        }
                }
            }

            group("Project layout") {
                row("Template root:") {
                    textField()
                        .bindText({ state.templateRoot }, { state.templateRoot = it })
                        .columns(30)
                        .comment("Base directory for <code>{include \"…\"}</code>. Relative to project root, e.g. <code>view</code>.")
                }
                row {
                    checkBox("Auto-mark template root as Resource Root")
                        .bindSelected({ state.autoMarkTemplateRoot }, { state.autoMarkTemplateRoot = it })
                        .comment(
                            "When enabled, the directory above is automatically marked as a " +
                            "<i>Resource Root</i> in the IDE's project structure on project open. " +
                            "Click <i>Apply now</i> to mark it without restarting."
                        )
                }
                row {
                    button("Apply now") { _ ->
                        val rootRel = state.templateRoot
                        val res = TemplateRootResourceMarker.apply(project, rootRel)
                        Messages.showInfoMessage(
                            project,
                            describe(res, rootRel),
                            "SkyTemplate — Mark Resource Root",
                        )
                    }
                        .comment("Marks <code>" + (state.templateRoot.ifBlank { "&lt;not configured&gt;" }) +
                            "</code> as Resource Root using the current setting, regardless of the toggle above.")
                }
            }

            group("File scope") {
                row("File extensions:") {
                    cell(fileExtensionsArea)
                        .align(AlignX.FILL)
                        .comment(
                            "Files with these extensions are processed for SkyTemplate constructs " +
                                "(annotators, inspections, references, completion). " +
                                "<code>.sky</code> files are always processed regardless. " +
                                "One per line. Default: <code>html</code>, <code>sky</code>."
                        )
                        .onApply {
                            state.fileExtensions = fileExtensionsArea.text.lineSequence()
                                .map { it.trim().trimStart('.').lowercase() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                                .toMutableList()
                        }
                        .onReset { fileExtensionsArea.text = state.fileExtensions.joinToString("\n") }
                        .onIsModified {
                            val current = fileExtensionsArea.text.lineSequence()
                                .map { it.trim().trimStart('.').lowercase() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                                .toList()
                            current != state.fileExtensions.toList()
                        }
                }
            }

            // (Safe mode group continues below.)
            group("Safe mode") {
                row {
                    checkBox("Safe mode enabled")
                        .bindSelected({ state.safeMode }, { state.safeMode = it })
                        .comment("Mirrors the SkyTemplate runtime <code>safeMode</code> setting.")
                }
                row("Function deny pattern:") {
                    textField()
                        .bindText({ state.funcDeny }, { state.funcDeny = it })
                        .align(AlignX.FILL)
                        .comment("PCRE regex. Leave blank to use the SkyTemplate default.")
                }
            }
        }
    }

    private fun describe(result: TemplateRootResourceMarker.Result, templateRoot: String): String =
        when (result) {
            is TemplateRootResourceMarker.Result.Marked ->
                "Marked '${result.absolutePath}' as Resource Root."
            TemplateRootResourceMarker.Result.AlreadyMarked ->
                "Directory is already a Resource Root — no changes made."
            TemplateRootResourceMarker.Result.PluginDisabled ->
                "SkyTemplate is disabled. Enable it first, then click Apply now."
            TemplateRootResourceMarker.Result.AutoMarkDisabled ->
                "(internal) Auto-mark toggle is off — ignoring."
            TemplateRootResourceMarker.Result.EmptyTemplateRoot ->
                "Template root is empty. Configure 'Template root' above, then try again."
            TemplateRootResourceMarker.Result.NoProjectBase ->
                "Project has no base path. Cannot resolve a relative template root."
            is TemplateRootResourceMarker.Result.DirectoryMissing ->
                "Directory does not exist:\n${result.absolutePath}\n\nCreate it on disk, then try again."
            is TemplateRootResourceMarker.Result.NotADirectory ->
                "Path is not a directory:\n${result.absolutePath}"
            TemplateRootResourceMarker.Result.NoModule ->
                "Project has no module to apply the marker to."
            TemplateRootResourceMarker.Result.NotInsideContentRoot ->
                "Template root '$templateRoot' is outside every content root in this project. " +
                    "Add the parent directory to the project first."
            TemplateRootResourceMarker.Result.Disposed ->
                "Project is closed."
        }
}
