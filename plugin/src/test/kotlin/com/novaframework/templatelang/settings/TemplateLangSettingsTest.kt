package com.novaframework.templatelang.settings

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the settings state — no IDE fixture needed.
 * Verifies defaults, the enabled flag, and XML round-trip via the same
 * serializer the platform uses.
 */
class TemplateLangSettingsTest {

    @Test fun defaults() {
        val s = TemplateLangSettings.State()
        assertTrue(s.enabled)
        assertEquals("\\", s.namespace)
        assertTrue(s.useClass.isEmpty())
        // File extension whitelist defaults to html + sky only — explicit
        // opt-in for htm / xml / skyhtml etc.
        assertEquals(listOf("html", "sky"), s.fileExtensions.toList())
    }

    @Test fun loadStateRoundTrip() {
        val src = TemplateLangSettings.State().apply {
            enabled = true
            namespace = "App"
        }
        val settings = TemplateLangSettings()
        settings.loadState(src)
        assertEquals("App", settings.namespace)
        assertTrue(settings.isEnabled)
    }

    @Test fun useClassListIsCloned() {
        val src = TemplateLangSettings.State().apply {
            useClass = mutableListOf("App\\Util\\Format as F", "App\\Helper")
        }
        val settings = TemplateLangSettings()
        settings.loadState(src)
        assertEquals(listOf("App\\Util\\Format as F", "App\\Helper"), settings.useClass)
    }

    @Test fun xmlRoundTripPreservesAllFields() {
        val original = TemplateLangSettings.State().apply {
            enabled = true
            namespace = "App\\Web"
            useClass = mutableListOf("App\\Util\\F as F", "App\\Render")
            fileExtensions = mutableListOf("html", "sky", "skyhtml", "xml")
        }
        val xml = XmlSerializer.serialize(original)
        val restored = XmlSerializer.deserialize(xml, TemplateLangSettings.State::class.java)
        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.namespace, restored.namespace)
        assertEquals(original.useClass, restored.useClass)
        assertEquals(original.fileExtensions, restored.fileExtensions)
    }

    @Test fun fileExtensionsAccessorNormalizes() {
        // The accessor is the canonical view used by every gate. It strips
        // dots, lower-cases, trims, drops blanks, and de-duplicates while
        // preserving order. The mutable State list itself is untouched.
        val s = TemplateLangSettings()
        s.loadState(TemplateLangSettings.State().apply {
            fileExtensions = mutableListOf(".HTML", "  Sky  ", "html", "PY", "", ".", " . ")
        })
        assertEquals(listOf("html", "sky", "py"), s.fileExtensions)
    }

    @Test fun fileExtensionsAccessorEmptyWhenStateEmpty() {
        val s = TemplateLangSettings()
        s.loadState(TemplateLangSettings.State().apply {
            fileExtensions = mutableListOf()
        })
        assertTrue(s.fileExtensions.isEmpty())
    }

    @Test fun missingFileExtensionsTagDeserializesToDefault() {
        // Older sky-template.xml files written before Phase 5 have no
        // <fileExtensions> element. Verify XmlSerializer initialises the
        // field to its declared default rather than leaving it empty.
        val xml = XmlSerializer.serialize(TemplateLangSettings.State().apply {
            // No mutation — let serializer write only non-default fields.
        })
        // Strip any auto-emitted fileExtensions element so we simulate the
        // legacy XML shape exactly.
        val children = xml.children.toList()
        for (child in children) {
            if (child.getAttributeValue("name") == "fileExtensions") {
                xml.removeContent(child)
            }
        }
        val restored = XmlSerializer.deserialize(xml, TemplateLangSettings.State::class.java)
        assertEquals(listOf("html", "sky"), restored.fileExtensions.toList())
    }

    @Test fun enabledFlagDrivesIsEnabled() {
        val s = TemplateLangSettings()
        // default state is enabled
        assertTrue(s.isEnabled)

        s.loadState(TemplateLangSettings.State().apply { enabled = false })
        assertFalse(s.isEnabled)

        s.loadState(TemplateLangSettings.State().apply { enabled = true })
        assertTrue(s.isEnabled)
    }
}
