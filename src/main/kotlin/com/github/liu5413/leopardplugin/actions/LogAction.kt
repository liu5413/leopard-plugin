package com.github.liu5413.leopardplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.datatransfer.StringSelection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LogAction : AnAction(
    "Logcat",
    "Capture logcat output to file",
    IconLoader.getIcon("/icons/log.svg", LogAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openLogTerminal(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun openLogTerminal(project: Project) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val fileName = "log-$timestamp.txt"
        val filePath = "${project.basePath}/$fileName"
        CopyPasteManager.getInstance().setContents(StringSelection(filePath))
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        val widget = terminalManager.createShellWidget(project.basePath, "Logcat", true, false)
        widget.sendCommandToExecute("adb logcat > $fileName")
    }
}
