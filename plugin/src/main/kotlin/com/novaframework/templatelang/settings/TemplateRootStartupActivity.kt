package com.novaframework.templatelang.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * On project open, apply the resource-root marker for the configured
 * `templateRoot` if the user has enabled `autoMarkTemplateRoot` in settings.
 *
 * Runs once per project open. Idempotent — [TemplateRootResourceMarker]
 * detects an existing marker and short-circuits, so re-running on subsequent
 * project openings is safe and silent.
 *
 * Errors are swallowed (logged via the marker's own logger). A failure to
 * mark a Resource Root should never block project loading or surface to the
 * user as a notification balloon — it's a non-essential convenience feature.
 */
class TemplateRootStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        runCatching { TemplateRootResourceMarker.applyIfEnabled(project) }
    }
}
