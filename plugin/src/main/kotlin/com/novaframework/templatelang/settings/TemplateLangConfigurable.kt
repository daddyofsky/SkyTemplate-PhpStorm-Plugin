package com.novaframework.templatelang.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/**
 * Project Settings → Tools → SkyTemplate.
 *
 * Single-engine configuration. The internal code path also handles
 * Template_ files — both share the directive surface this plugin cares
 * about — but the setting itself is just a master enable/disable plus
 * the SkyTemplate runtime mirrors used for navigation, inspections, and
 * formatting.
 */
class TemplateLangConfigurable(private val project: Project)
    : BoundConfigurable("SkyTemplate"), SearchableConfigurable {

    private val settings get() = TemplateLangSettings.getInstance(project)
    private val state get() = settings.state

    override fun getId(): String = "com.novaframework.skytemplate.settings"
    override fun getDisplayName(): String = "SkyTemplate"

    override fun createPanel(): DialogPanel {
        val useClassArea = JBTextArea(6, 40).apply { lineWrap = false }
        useClassArea.text = state.useClass.joinToString("\n")

        val fileExtensionsArea = JBTextArea(4, 40).apply { lineWrap = false }
        fileExtensionsArea.text = state.fileExtensions.joinToString("\n")

        // Plain `bindText` compares the field's raw text against the (always
        // trimmed) stored state, so a trailing/leading space left in the
        // field reads as "still modified" right after Apply. Same
        // explicit onApply/onReset/onIsModified trio as useClass /
        // fileExtensions above, trimming on both sides of the comparison.
        val formatterClassField = JBTextField(30).apply {
            text = state.formatterClass
            name = "skyTemplateFormatterClassField"
        }

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
                row("Formatter class:") {
                    cell(formatterClassField)
                        .comment(
                            "Mirrors SkyTemplate <code>formatter</code> config. Pipe filters " +
                                "(<code>{var|func}</code>) whose name is a method of this class " +
                                "resolve to that method instead of a global function."
                        )
                        .onApply { state.formatterClass = formatterClassField.text.trim() }
                        .onReset { formatterClassField.text = state.formatterClass }
                        .onIsModified { formatterClassField.text.trim() != state.formatterClass }
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

        }
    }
}
