package com.github.liu5413.leopardplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

class ClaudeAction : AnAction(
    "Claude Code",
    "Open Claude Code via cfuse",
    IconLoader.getIcon("/icons/cc-gui-icon.svg", ClaudeAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openClaudeTerminal(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun openClaudeTerminal(project: Project) {
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        val widget = terminalManager.createShellWidget(project.basePath, "Claude Code", true, false)
        widget.sendCommandToExecute("cfuse")
    }
}
