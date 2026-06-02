package com.alipay.devtools.mockdata.actions

import com.alipay.devtools.mockdata.MockDataService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

/**
 * Mock RPC Action
 */
class MockDataRpcAction : AnAction("Mock RPC", "Mock RPC Response", null), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = MockDataService.getInstance(project)

        if (!service.isConnected()) {
            Messages.showErrorDialog(project, "Please connect to MockData first", "Error")
            return
        }

        val operationType = Messages.showInputDialog(
            project,
            "Enter RPC operation type:",
            "Mock RPC",
            Messages.getQuestionIcon(),
            "alipay.test.rpc",
            null
        )?.trim().orEmpty()

        if (operationType.isEmpty()) return

        val response = com.google.gson.JsonObject().apply {
            addProperty("success", true)
            addProperty("data", "mocked response")
        }

        service.mockRpc(operationType, response)
            .thenAccept { success: Boolean ->
                javax.swing.SwingUtilities.invokeLater {
                    if (success) {
                        Messages.showInfoMessage(
                            project,
                            "RPC $operationType mocked successfully",
                            "Mock RPC"
                        )
                    } else {
                        Messages.showErrorDialog(
                            project,
                            "Failed to mock RPC: $operationType",
                            "Mock RPC"
                        )
                    }
                }
            }
            .exceptionally { error: Throwable ->
                javax.swing.SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Mock RPC error: ${error.message ?: error.javaClass.simpleName}",
                        "Mock RPC"
                    )
                }
                null
            }
    }
}
