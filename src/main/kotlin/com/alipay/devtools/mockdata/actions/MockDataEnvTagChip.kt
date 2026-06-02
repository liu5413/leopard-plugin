package com.alipay.devtools.mockdata.actions

import com.alipay.devtools.mockdata.MockDataService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JComponent

/**
 * Tag 样式 2:JBLabel + RoundedLineBorder,按 env 上色,接近 Plugins 页 chip 视觉。
 */
class MockDataEnvTagChip : AnAction(), CustomComponentAction, DumbAware {

    // env -> (中文名, 主题色)
    private val envMap: Map<String, Pair<String, JBColor>> = mapOf(
        "online" to ("线上" to JBColor(Color(0xE53935), Color(0xEF5350))),
        "pre" to ("预发" to JBColor(Color(0xF57C00), Color(0xFFB74D))),
        "test" to ("测试" to JBColor(Color(0x1976D2), Color(0x64B5F6))),
        "dev" to ("开发" to JBColor(Color(0x388E3C), Color(0x81C784))),
        "sim" to ("仿真" to JBColor(Color(0x7B1FA2), Color(0xBA68C8)))
    )

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {}

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        JBLabel().apply {
            isOpaque = false
            border = makeChipBorder(JBColor.border())
        }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        val lbl = component as JBLabel
        lbl.text = presentation.text
        val color = presentation.getClientProperty(COLOR_KEY) ?: JBColor.border()
        lbl.foreground = color
        lbl.border = makeChipBorder(color)
    }

    override fun update(e: AnActionEvent) {
        val svc = e.project?.let { MockDataService.getInstance(it) }
        val env = svc?.takeIf { it.isConnected() }?.getCurrentEnv()
        val pair = env?.let { envMap[it] }
        e.presentation.text = "Chip: ${pair?.first ?: "--"}"
        e.presentation.putClientProperty(COLOR_KEY, pair?.second)
    }

    private fun makeChipBorder(color: Color) = BorderFactory.createCompoundBorder(
        RoundedLineBorder(color, 10, 1),
        JBUI.Borders.empty(1, 8)
    )

    companion object {
        private val COLOR_KEY = Key.create<Color>("MockDataEnvTagChip.color")
    }
}
