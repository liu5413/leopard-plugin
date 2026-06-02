package com.alipay.devtools.mockdata.actions

import com.alipay.devtools.mockdata.MockDataService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

/**
 * Mock LBS Action
 */
class MockDataLbsAction : AnAction("Mock LBS", "Mock Location", null), DumbAware {

    private val locations: Map<String, Pair<Double, Double>> = linkedMapOf(
        "Shanghai" to (31.2304 to 121.4737),
        "Beijing" to (39.9042 to 116.4074),
        "Shenzhen" to (22.5431 to 114.0579),
        "Hangzhou" to (30.2741 to 120.1551)
    )

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = MockDataService.getInstance(project)

        if (!service.isConnected()) {
            Messages.showErrorDialog(project, "Please connect to MockData first", "Error")
            return
        }

        val cities = locations.keys.toTypedArray()
        // 使用非可编辑的选择框，避免用户输入未知城市后被静默回落到默认坐标。
        // showChooseDialog 在新版本平台标记为 deprecated，但平台没有提供等价无锚点替代物，先抑制。
        @Suppress("DEPRECATION")
        val selectedIndex = Messages.showChooseDialog(
            project,
            "Select location:",
            "Mock Location",
            Messages.getQuestionIcon(),
            cities,
            cities[0]
        )

        if (selectedIndex < 0) return
        val selected = cities[selectedIndex]
        val (lat, lng) = locations[selected] ?: return

        service.mockLbs(lat, lng, selected)
            .thenAccept { success: Boolean ->
                javax.swing.SwingUtilities.invokeLater {
                    if (success) {
                        Messages.showInfoMessage(
                            project,
                            "Location mocked to $selected ($lat, $lng)",
                            "Mock LBS"
                        )
                    } else {
                        Messages.showErrorDialog(
                            project,
                            "Failed to mock location: $selected",
                            "Mock LBS"
                        )
                    }
                }
            }
            .exceptionally { error: Throwable ->
                javax.swing.SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Mock LBS error: ${error.message ?: error.javaClass.simpleName}",
                        "Mock LBS"
                    )
                }
                null
            }
    }
}
