package com.alipay.devtools.mockdata.actions

import com.alipay.devtools.mockdata.MockDataService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import java.io.File
import java.util.Base64

/**
 * Screenshot Action
 */
class MockDataScreenshotAction : AnAction("Screenshot", "Take Screenshot", null), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = MockDataService.getInstance(project)

        if (!service.isConnected()) {
            Messages.showErrorDialog(project, "Please connect to MockData first", "Error")
            return
        }

        service.screenshot()
            .thenAccept { base64: String ->
                try {
                    val bytes = Base64.getDecoder().decode(base64)
                    val dir = File(System.getProperty("user.home"), "Pictures/MockData")
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw java.io.IOException("Failed to create directory: ${dir.absolutePath}")
                    }
                    val file = File(dir, "mockdata_screenshot_${System.currentTimeMillis()}.png")
                    file.writeBytes(bytes)

                    javax.swing.SwingUtilities.invokeLater {
                        Messages.showInfoMessage(
                            project,
                            "Screenshot saved to: ${file.absolutePath}",
                            "Screenshot"
                        )
                    }
                } catch (ex: Exception) {
                    javax.swing.SwingUtilities.invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to save screenshot: ${ex.message ?: ex.javaClass.simpleName}",
                            "Screenshot"
                        )
                    }
                }
            }
            .exceptionally { error: Throwable ->
                javax.swing.SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Screenshot failed: ${error.message ?: error.javaClass.simpleName}",
                        "Screenshot"
                    )
                }
                null
            }
    }
}
