package com.github.liu5413.leopardplugin.listener

import com.github.liu5413.leopardplugin.settings.BuildNotificationSettings
import com.github.liu5413.leopardplugin.sound.SoundPlayer
import com.intellij.build.BuildProgressListener
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.SuccessResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

class BuildCompletionListener(private val project: Project) : BuildProgressListener {

    override fun onEvent(buildId: Any, event: BuildEvent) {
        if (event !is FinishBuildEvent) return
        if (!BuildNotificationSettings.getInstance(project).enabled) return
        when (event.result) {
            is SuccessResult -> ApplicationManager.getApplication().executeOnPooledThread { SoundPlayer.playSuccess() }
            is FailureResult -> ApplicationManager.getApplication().executeOnPooledThread { SoundPlayer.playFailure() }
        }
    }
}