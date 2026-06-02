package com.alipay.devtools.mockdata.actions

import com.alipay.devtools.mockdata.MockDataService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.JComponent

/**
 * 快速切换环境 Action - 使用 ComboBoxAction 下拉选择
 */
class MockDataSwitchEnvAction : ComboBoxAction(), DumbAware {

    // 环境映射
    private val envMap = linkedMapOf(
        "online" to "线上",
        "pre" to "预发",
        "test" to "测试",
        "dev" to "开发",
        "sim" to "仿真"
    )

    // 下拉项是无状态的，提前构建并缓存，避免每次打开 popup 都重建匿名子类
    private val cachedActionGroup: DefaultActionGroup by lazy {
        DefaultActionGroup().apply {
            envMap.forEach { (env, displayName) ->
                add(SwitchEnvItemAction(env, displayName))
            }
        }
    }

    // 已注册 deviceInfo 监听器的 project 集合：
    // - Action 在 init 时拿不到 project,只能首次 update() 时按 project 懒注册
    // - 用 WeakHashMap 避免在 project 关闭后泄漏
    private val listenerRegisteredProjects: MutableSet<Project> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    init {
        templatePresentation.text = "环境"
        templatePresentation.description = "切换 MockData 环境"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup = cachedActionGroup

    override fun update(e: AnActionEvent) {
        super.update(e)

        val project = e.project
        val service = project?.let { MockDataService.getInstance(it) }

        if (project != null && service != null) {
            ensureDeviceInfoListener(project, service)
        }

        val connected = service?.isConnected() == true

        if (connected) {
            val currentEnv = service?.getCurrentEnv() ?: "unknown"
            e.presentation.text = envMap[currentEnv] ?: currentEnv
            e.presentation.isEnabled = true
        } else {
            e.presentation.text = "--"
            e.presentation.isEnabled = false
        }
    }

    /**
     * 首次拿到 project 时为其挂上 deviceInfo 监听器,设备端推送的 env 会被同步到 service,
     * 下次 ComboBoxAction.update() 轮询时即可在工具栏上反映出来。同一 project 只会注册一次。
     */
    private fun ensureDeviceInfoListener(project: Project, service: MockDataService) {
        if (!listenerRegisteredProjects.add(project)) return
        service.addDeviceInfoListener { deviceInfo ->
            deviceInfo.env?.let { service.setCurrentEnv(it) }
        }
    }

    /**
     * 单个环境切换条目。提取为命名类，便于缓存复用与避免匿名类捕获过多上下文。
     */
    private class SwitchEnvItemAction(
        private val env: String,
        private val displayName: String
    ) : AnAction(displayName, "切换到 $displayName", null), DumbAware {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val service = MockDataService.getInstance(project)

            if (!service.isConnected()) {
                Messages.showErrorDialog(
                    project,
                    "未连接到 MockData，请先连接设备",
                    "Error"
                )
                return
            }

            runSwitchWithProgress(project, service)
        }

        private fun runSwitchWithProgress(project: Project, service: MockDataService) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "正在切换到 $displayName ...",
                /* canBeCancelled = */ false
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true

                    val success: Boolean = try {
                        // service.switchEnv 内部会 send -> 等待 2s -> queryLoginStatus，
                        // 用 join 在后台线程同步等待，避免抢占 EDT；上限给 10s 防止 hang。
                        service.switchEnv(env)
                            .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .join()
                    } catch (ex: Throwable) {
                        val cause = (ex as? java.util.concurrent.CompletionException)?.cause ?: ex
                        javax.swing.SwingUtilities.invokeLater {
                            Messages.showErrorDialog(
                                project,
                                "切换环境异常: ${cause.message ?: cause.javaClass.simpleName}",
                                "环境切换失败"
                            )
                        }
                        return
                    }

                    javax.swing.SwingUtilities.invokeLater {
                        if (success) {
                            Messages.showInfoMessage(
                                project,
                                "已切换到 $displayName",
                                "环境切换成功"
                            )
                        } else {
                            val actual = service.getCurrentEnv()
                            Messages.showErrorDialog(
                                project,
                                "切换到 $displayName 失败" +
                                    (actual?.let { "，当前实际环境: $it" } ?: ""),
                                "环境切换失败"
                            )
                        }
                    }
                }
            })
        }
    }
}
