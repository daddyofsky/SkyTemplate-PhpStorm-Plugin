package com.novaframework.templatelang.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import java.awt.Container

/**
 * IDE-level checks for [TemplateLangConfigurable]'s `DialogPanel` binding
 * behaviour — in particular the "Apply leaves the panel modified" class of
 * bug that plain `bindText` has when the stored state normalises (trims)
 * the value but the comparison used by `isModified()` does not.
 */
class TemplateLangConfigurableTest : BasePlatformTestCase() {

    private fun formatterClassField(panel: Container): JBTextField {
        val queue = ArrayDeque<Container>()
        queue.addLast(panel)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            for (child in c.components) {
                if (child is JBTextField && child.name == "skyTemplateFormatterClassField") return child
                if (child is Container) queue.addLast(child)
            }
        }
        error("skyTemplateFormatterClassField not found in configurable panel")
    }

    fun testFormatterClassTrailingWhitespaceDoesNotLeaveModifiedAfterApply() {
        val configurable = TemplateLangConfigurable(project)
        val panel = configurable.createPanel()
        val field = formatterClassField(panel)

        field.text = "Fmt "
        assertTrue("panel should read as modified before Apply", panel.isModified())

        panel.apply()

        assertEquals("Fmt", TemplateLangSettings.getInstance(project).state.formatterClass)
        assertFalse("panel must not read as modified right after Apply", panel.isModified())
    }

    fun testFormatterClassResetRestoresStoredValue() {
        TemplateLangSettings.getInstance(project).state.formatterClass = "Existing\\Formatter"
        val configurable = TemplateLangConfigurable(project)
        val panel = configurable.createPanel()
        val field = formatterClassField(panel)

        field.text = "Something\\Else"
        panel.reset()

        assertEquals("Existing\\Formatter", field.text)
        assertFalse(panel.isModified())

        TemplateLangSettings.getInstance(project).state.formatterClass = ""
    }
}
