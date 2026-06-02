package com.alipay.devtools.mockdata.actions

import com.alipay.devtools.mockdata.DeviceInfo
import com.alipay.devtools.mockdata.MockDataService
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.JComponent

/**
 * Tag 样式 1:CustomComponentAction + JBLabel,展示 "displayName (appVersion)"。
 *
 * 数据源走 [MockDataService.addDeviceInfoListener] 订阅,不再每轮 update() 现查 service —
 * - listener 把最新 [DeviceInfo] 写入按 project 维护的缓存
 * - listener 触发 ActivityTracker.inc(),让工具栏尽快走下一轮 update()
 * - update() 只从缓存读字段拼文本,几乎无开销
 */
class MockDataEnvTagLabel : AnAction(), CustomComponentAction, DumbAware {

    // 按 project 缓存最新 DeviceInfo;WeakHashMap 防止 project 关闭后泄漏
    private val latestInfo: MutableMap<Project, DeviceInfo> =
        Collections.synchronizedMap(WeakHashMap())

    // 已挂监听器的 project,保证同一 project 只订阅一次
    private val subscribed: MutableSet<Project> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    // 纯展示,不响应点击
    override fun actionPerformed(e: AnActionEvent) {}

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        JBLabel().apply { border = JBUI.Borders.empty(0, 8) }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        (component as JBLabel).text = presentation.text
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.let { MockDataService.getInstance(it) }

        if (project != null && service != null) {
            ensureSubscribed(project, service)
        }

        val connected = service?.isConnected() == true
        val info = project?.let { latestInfo[it] }

        e.presentation.text = if (connected && info != null) {
            formatText(info.displayName, info.appVersion)
        } else {
            ""
        }
    }

    /**
     * 首次拿到 project 时挂监听器。Action 是平台单例,init 里没有 project,只能懒注册。
     */
    private fun ensureSubscribed(project: Project, service: MockDataService) {
        if (!subscribed.add(project)) return
        service.addDeviceInfoListener { deviceInfo ->
            latestInfo[project] = deviceInfo
            // 催一次工具栏 update,不依赖 BGT 轮询节奏;inc() 内部仅 incrementAndGet,线程安全
            ActivityTracker.getInstance().inc()
        }
    }

    private fun formatText(displayName: String?, appVersion: String?): String {
        val name = displayName?.takeIf { it.isNotBlank() } ?: "-"
        val ver = appVersion?.takeIf { it.isNotBlank() } ?: "-"
        return "$name ($ver)"
    }
}
