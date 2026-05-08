package com.github.liu5413.leopardplugin.actions

import com.github.liu5413.leopardplugin.settings.BuildNotificationSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.util.IconLoader

class BuildNotificationToggleAction : ToggleAction(
    "Build Sound Notification",
    "Toggle sound notification on Gradle build completion",
    IconLoader.getIcon("/icons/bell.svg", BuildNotificationToggleAction::class.java)
) {

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return BuildNotificationSettings.getInstance(project).enabled
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        BuildNotificationSettings.getInstance(project).enabled = state
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        val enabled = project?.let { BuildNotificationSettings.getInstance(it).enabled } ?: false
        if (enabled) {
            e.presentation.icon = IconLoader.getIcon("/icons/bell.svg", BuildNotificationToggleAction::class.java)
        } else {
            e.presentation.icon = IconLoader.getIcon("/icons/bellMuted.svg", BuildNotificationToggleAction::class.java)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}