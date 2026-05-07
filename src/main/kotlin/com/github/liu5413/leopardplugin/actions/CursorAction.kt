package com.github.liu5413.leopardplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

class CursorAction : AnAction(
    "Cursor Agent",
    "Open Cursor Agent",
    IconLoader.getIcon("/icons/cursor-icon.svg", CursorAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openCursorTerminal(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun openCursorTerminal(project: Project) {
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        val widget = terminalManager.createShellWidget(project.basePath, "Cursor Agent", true, false)
        widget.sendCommandToExecute("cursor-agent")
    }
}