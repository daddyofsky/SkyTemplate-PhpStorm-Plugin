package com.novaframework.templatelang.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-level settings for the SkyTemplate plugin.
 *
 * Single switch: enabled / disabled. Internally the same code path also
 * handles Template_ files (the two engines share the directive surface that
 * the plugin cares about — `{?expr}`, `{:}`, `{/}`, `{=expr}`, `{var|func}`,
 * `{c.NAME}`, etc.). No engine selector is exposed.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "SkyTemplateSettings",
    storages = [Storage("sky-template.xml")],
)
class TemplateLangSettings : PersistentStateComponent<TemplateLangSettings.State> {

    /** Mutable XmlSerializer-friendly state object. */
    class State {
        /** Master switch — when false the plugin is silent (no annotator, no references). */
        var enabled: Boolean = true

        /** PHP root namespace, default `\` (root). Identifiers in `{foo()}` resolve under this namespace. */
        var namespace: String = "\\"

        /** `use Class as Alias` shorthand list (one per element). SkyTemplate `useClass` config mirror. */
        var useClass: MutableList<String> = mutableListOf()

        /** Base directory for `{include "..."}` resolution, relative to project root. */
        var templateRoot: String = ""

        /**
         * When true, [templateRoot] is automatically marked as a *Resource Root*
         * in the IDE's project structure on project open and on settings apply.
         * Default `false` — opt-in to avoid surprising existing project layouts.
         * See `TemplateRootResourceMarker`.
         */
        var autoMarkTemplateRoot: Boolean = false

        /** SkyTemplate `safeMode` mirror — gates safe-mode inspections. */
        var safeMode: Boolean = false

        /** SkyTemplate `funcDeny` regex mirror. Empty string = use the default. */
        var funcDeny: String = ""

        /**
         * File extensions that activate SkyTemplate processing in non-`.sky`
         * host files (annotators, inspections, references, completion,
         * navigation). Compared case-insensitively as dot-less lowercase
         * strings; the configurable normalises user input on save.
         *
         * Default `["html", "sky"]`. Add `htm` / `xml` / `skyhtml` etc.
         * explicitly to opt those in. Files matching the SkyTemplate own
         * file type are always processed regardless of this list.
         */
        var fileExtensions: MutableList<String> = mutableListOf("html", "sky")
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) {
        XmlSerializerUtil.copyBean(s, state)
    }

    /** Convenient read-only access for the rest of the plugin. */
    val isEnabled: Boolean get() = state.enabled
    val namespace: String get() = state.namespace
    val useClass: List<String> get() = state.useClass.toList()
    val templateRoot: String get() = state.templateRoot
    val autoMarkTemplateRoot: Boolean get() = state.autoMarkTemplateRoot
    val safeMode: Boolean get() = state.safeMode
    val funcDeny: String get() = state.funcDeny

    /**
     * Whitelist of file extensions, normalised: trimmed, dot stripped,
     * lower-cased, blanks dropped, deduplicated. Original order preserved.
     * The list is recomputed every call — typical size is 2-5 entries so
     * the cost is negligible.
     */
    val fileExtensions: List<String>
        get() = state.fileExtensions
            .asSequence()
            .map { it.trim().trimStart('.').lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    companion object {
        @JvmStatic
        fun getInstance(project: Project): TemplateLangSettings = project.service()
    }
}
