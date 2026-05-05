package com.novaframework.templatelang.sky

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke tests for the file-template registration. Verifies the descriptor
 * factory advertises the expected starter templates and that each template's
 * `*.ft` resource is actually shipped on the classpath.
 *
 * Pure unit test — no IDE platform required. Tests pull the resource file
 * via the same classloader the IntelliJ Platform uses at runtime.
 */
class SkyTemplateFileTemplatesTest {

    @Test fun bothTemplateResourcesShipInJar() {
        // Cross-checks the names that the descriptor factory uses
        // (`SkyTemplate Empty.sky` and `SkyTemplate Page.html`) against
        // the resource JAR. We verify the resource side here; the
        // descriptor-side wiring is exercised end-to-end by the IDE
        // when the user opens the New File menu — instantiating
        // FileTemplateDescriptor in a pure unit test pulls in the
        // FileTypeRegistry, which isn't initialised outside a fixture.
        assertNotNull(
            "SkyTemplate Empty.sky.ft must ship in JAR",
            readTemplate("SkyTemplate Empty.sky.ft"),
        )
        assertNotNull(
            "SkyTemplate Page.html.ft must ship in JAR",
            readTemplate("SkyTemplate Page.html.ft"),
        )
    }

    @Test fun emptyTemplateResourceShipsAndReferencesNameAndCaret() {
        val text = readTemplate("SkyTemplate Empty.sky.ft")
        assertNotNull("template resource must ship in JAR", text)
        // `${NAME}` so the user-supplied filename is reflected in the comment.
        assertTrue(
            "empty template should reference \${NAME} macro: $text",
            text!!.contains("\${NAME}"),
        )
        // `${CARET}` so the platform places the cursor sensibly after creation.
        assertTrue(
            "empty template should include \${CARET} placeholder: $text",
            text.contains("\${CARET}"),
        )
    }

    @Test fun pageTemplateResourceShipsAndIsHtml5() {
        val text = readTemplate("SkyTemplate Page.html.ft")
        assertNotNull("template resource must ship in JAR", text)
        assertTrue(
            "page template should start with HTML5 doctype: $text",
            text!!.trimStart().startsWith("<!DOCTYPE html>"),
        )
        assertTrue(
            "page template should expose a {title} variable so users see the wire-up: $text",
            text.contains("{title}"),
        )
        assertTrue(
            "page template should reference \${NAME} macro: $text",
            text.contains("\${NAME}"),
        )
        assertTrue(
            "page template should include \${CARET} placeholder: $text",
            text.contains("\${CARET}"),
        )
    }

    private fun readTemplate(name: String): String? {
        // Use the SkyTemplateFileTemplates classloader — the same one the
        // platform uses to resolve `fileTemplates/*.ft` at runtime.
        val cl = SkyTemplateFileTemplates::class.java.classLoader
        return cl.getResourceAsStream("fileTemplates/$name")?.bufferedReader()?.use { it.readText() }
    }
}
