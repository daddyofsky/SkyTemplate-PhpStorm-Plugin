package com.novaframework.templatelang.inspection

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.util.ProcessingContext
import com.novaframework.templatelang.reference.SkyTemplatePhpReference
import com.novaframework.templatelang.reference.SkyTemplateRefDetector
import com.novaframework.templatelang.reference.SkyTemplateReferenceProvider
import com.novaframework.templatelang.settings.TemplateLangFileFilter
import com.novaframework.templatelang.sky.SkyTemplateFile

/**
 * Pure file-level analysis shared by [SkyTemplateUndefinedSymbolInspection]
 * (runs on `*.sky` via the inspection profile) and the host annotator
 * (runs on `*.html` outside the inspection profile). Mirrors the M7 pattern
 * (`SkyTemplateBlockPairing` + structural inspection / annotator pair).
 *
 * Returns absolute-file [TextRange]s so both call sites use the same offset
 * coordinate. The analyzer itself owns the master-toggle and file-type
 * filters so each wrapper stays a thin shim.
 */
object SkyTemplateUndefinedSymbolAnalyzer {

    /**
     * @property range Absolute offset in the host file.
     * @property message User-visible diagnostic.
     */
    data class Diagnostic(val range: TextRange, val message: String)

    fun analyze(file: PsiFile): List<Diagnostic> {
        if (file !is XmlFile && file !is SkyTemplateFile) return emptyList()
        if (!TemplateLangFileFilter.shouldProcess(file)) return emptyList()

        val refs = SkyTemplateReferenceProvider().getReferencesByElement(file, ProcessingContext())
        if (refs.isEmpty()) return emptyList()

        // When the host is the file itself, `rangeInElement` already lives in
        // the file's absolute coordinate system — no shift required.
        return refs
            .filterIsInstance<SkyTemplatePhpReference>()
            // PARAMETER_NAME refs are soft (Phase 1 spec D-2): unresolved
            // named-arg names are intentionally silent because the callee
            // may be a dynamic call, missing-from-index function, or a
            // built-in we don't have a stub for. The analyzer skips them
            // up front so [messageFor] only handles symbol-style kinds.
            .filter { it.kind != SkyTemplateRefDetector.Kind.PARAMETER_NAME }
            .filter { it.multiResolve(false).isEmpty() }
            .map { ref -> Diagnostic(ref.rangeInElement, messageFor(ref)) }
    }

    /**
     * Build the user-visible message. Uses the *simple name* (last segment
     * after the namespace separator) so the diagnostic mirrors the literal
     * identifier the user typed, not the qualified form the resolver tried.
     */
    private fun messageFor(ref: SkyTemplatePhpReference): String {
        val simpleName = ref.nameInSource.substringAfterLast('\\')
        val simpleClass = ref.classNameInSource?.substringAfterLast('\\')
        return when (ref.kind) {
            SkyTemplateRefDetector.Kind.FUNCTION ->
                "Cannot resolve function `$simpleName()`"
            SkyTemplateRefDetector.Kind.METHOD ->
                "Cannot resolve method `${simpleClass ?: "?"}::$simpleName()`"
            SkyTemplateRefDetector.Kind.CLASS ->
                "Cannot resolve class `$simpleName`"
            SkyTemplateRefDetector.Kind.CONSTANT ->
                "Cannot resolve constant `$simpleName`"
            SkyTemplateRefDetector.Kind.CLASS_CONSTANT ->
                "Cannot resolve class constant `${simpleClass ?: "?"}::$simpleName`"
            // Filtered out in [analyze]; included for exhaustiveness so a
            // future ref kind that slips past the filter still surfaces a
            // diagnostic instead of being silently dropped.
            SkyTemplateRefDetector.Kind.PARAMETER_NAME ->
                "Unknown parameter `$simpleName`"
        }
    }
}
