package com.github.liu5413.leopardplugin.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.IconLoader

class FireRunAction : AnAction(
    "Fire Run",
    "Run with fire!",
    IconLoader.getIcon("/icons/fireRun.svg", FireRunAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("FireRun")
            .createNotification("🔥 Fire Run!", "Blazing fast!", NotificationType.INFORMATION)
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
