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
 * Keeps only the knobs the plugin actually consumes — the master
 * enable/disable, PHP namespace + `useClass` aliases + formatter class
 * for symbol resolution, and the file-extension whitelist. Safe-mode,
 * template-root, and `indentBlockBody` toggles were removed in 1.1.6 —
 * none had stable use cases (the planned safe-mode inspections never
 * shipped, the file-extension setting alone covers what `templateRoot`
 * was meant for, and the indent-toggle ended up unable to express
 * per-block style preferences while violating Reformat's "convention
 * enforcement" role; the plugin now always applies the conventional
 * depth + 1 layout, and users wanting one-off variations adjust by
 * hand and avoid Reformat on those files).
 *
 * Internally the same code path also handles Template_ files (the two
 * engines share the directive surface that the plugin cares about —
 * `{?expr}`, `{:}`, `{/}`, `{=expr}`, `{var|func}`, `{c.NAME}`, etc.).
 * No engine selector is exposed.
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

        /**
         * Formatter class FQN — SkyTemplate `formatter` config mirror. When a
         * pipe filter name (`{var|func}`) is a method of this class, the
         * compiler emits `_F::func(...)` instead of a plain function call, so
         * the plugin resolves those names against this class first.
         * Empty = no formatter configured.
         */
        var formatterClass: String = ""

        /**
         * File extensions that activate SkyTemplate processing in non-`.sky`
         * host files (annotators, inspections, references, completion,
         * navigation). Compared case-insensitively as dot-less lowercase
         * strings; the configurable normalises user input on save.
         *
         * Default `["html", "sky"]`. Add `htm` / `xml` etc.
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
    val formatterClass: String get() = state.formatterClass.trim()

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
