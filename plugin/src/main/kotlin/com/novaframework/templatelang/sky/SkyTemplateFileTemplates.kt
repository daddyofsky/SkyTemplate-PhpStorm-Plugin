package com.novaframework.templatelang.sky

import com.intellij.ide.fileTemplates.FileTemplateDescriptor
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptor
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptorFactory

/**
 * Registers the **New → SkyTemplate File** group with two starter templates:
 *
 *   - **SkyTemplate Empty** (`*.sky`) — minimal scaffold with a top-level
 *     comment carrying the user-supplied filename and the caret placeholder.
 *     Useful for partial templates that get included from a larger page.
 *
 *   - **SkyTemplate Page** (`*.html`) — HTML5 skeleton with
 *     `{title}` already wired into `<title>` and a body comment marking the
 *     content area. The default for a brand-new top-level template.
 *
 * The platform looks for the actual content under
 * `resources/fileTemplates/<displayName>.<extension>.ft`. Macros `${NAME}` and
 * `${CARET}` resolve at instantiation time to the chosen filename and the
 * post-creation caret position respectively.
 *
 * Wired up in `plugin.xml` via `<fileTemplateGroup>`.
 */
class SkyTemplateFileTemplates : FileTemplateGroupDescriptorFactory {

    override fun getFileTemplatesDescriptor(): FileTemplateGroupDescriptor {
        val group = FileTemplateGroupDescriptor("SkyTemplate", /* icon = */ null)
        // The descriptor name MUST match the template file's stem (everything
        // before the final `.<extension>.ft`).
        group.addTemplate(FileTemplateDescriptor("SkyTemplate Empty.sky"))
        group.addTemplate(FileTemplateDescriptor("SkyTemplate Page.html"))
        return group
    }
}
