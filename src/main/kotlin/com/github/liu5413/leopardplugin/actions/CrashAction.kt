package com.github.liu5413.leopardplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

class CrashAction : AnAction(
    "Crash Log",
    "Watch crash logs via adb logcat",
    IconLoader.getIcon("/icons/crash.svg", CrashAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openCrashTerminal(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun openCrashTerminal(project: Project) {
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        val widget = terminalManager.createShellWidget(project.basePath, "Crash Log", true, false)
        widget.sendCommandToExecute("adb logcat -c && adb logcat -b crash")
    }
}
