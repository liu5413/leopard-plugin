package com.alipay.devtools.mockdata.actions

import com.alipay.devtools.mockdata.MockDataService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages

/**
 * 一键自动连接 MockData Action
 */
class MockDataAutoConnectAction : AnAction(
    "MockData Auto Connect",
    "Automatically detect and connect to MockData on device",
    null
), DumbAware {

    // 预设应用：显示名 -> 包名（null 表示自动检测）
    private val presetApps: Map<String, String?> = linkedMapOf(
        "自动检测" to null,
        "com.eg.android.AlipayGphone (支付宝)" to "com.eg.android.AlipayGphone",
        "com.antfortune.wealth (财富)" to "com.antfortune.wealth",
        "com.antgroup.zhixiaobao.android (支小宝)" to "com.antgroup.zhixiaobao.android",
        "com.antgroup.anzhener.android (安诊儿)" to "com.antgroup.anzhener.android",
        "hk.alipay.wallet (支付宝HK)" to "hk.alipay.wallet",
        "com.antgroup.leopard.android (Leopard)" to "com.antgroup.leopard.android"
    )

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = MockDataService.getInstance(project)

        val displayNames = presetApps.keys.toTypedArray()
        val selected = Messages.showEditableChooseDialog(
            "选择要连接的应用（也可手动输入自定义包名）:",
            "MockData Auto Connect",
            Messages.getQuestionIcon(),
            displayNames,
            displayNames[0],
            null
        )?.trim().orEmpty()

        if (selected.isEmpty()) return

        // 命中预设：使用映射值；否则视为用户自定义包名
        val packageName: String? = if (presetApps.containsKey(selected)) {
            presetApps[selected]
        } else {
            selected
        }

        service.autoConnect(packageName)
            .thenAccept { success: Boolean ->
                javax.swing.SwingUtilities.invokeLater {
                    if (success) {
                        val config = service.getConnectionConfig()
                        val pkg = config?.packageName ?: "设备"
                        val port = config?.port?.toString() ?: "未知"
                        Messages.showInfoMessage(
                            project,
                            "成功连接到 $pkg\n端口: $port",
                            "连接成功"
                        )
                    } else {
                        Messages.showErrorDialog(
                            project,
                            "自动连接失败，请检查:\n" +
                                "1. 设备是否通过 USB/WiFi 连接\n" +
                                "2. 应用是否已启动\n" +
                                "3. adb 是否在 PATH 中",
                            "连接失败"
                        )
                    }
                }
            }
            .exceptionally { error: Throwable ->
                javax.swing.SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "自动连接异常: ${error.message ?: error.javaClass.simpleName}",
                        "连接失败"
                    )
                }
                null
            }
    }
}
