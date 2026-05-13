package com.github.liu5413.leopardplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager

class PreviewLogAction : AnAction(
    "Preview Log",
    "Open log viewer tool window",
    IconLoader.getIcon("/icons/preview-log.svg", PreviewLogAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        activateToolWindow(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun activateToolWindow(project: Project) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("LogViewer")
        toolWindow?.activate(null)
    }
}