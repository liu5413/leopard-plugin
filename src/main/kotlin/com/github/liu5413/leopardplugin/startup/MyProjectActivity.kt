package com.github.liu5413.leopardplugin.startup

import com.github.liu5413.leopardplugin.listener.BuildCompletionListener
import com.intellij.build.BuildViewManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val buildViewManager = project.getService(BuildViewManager::class.java)
        buildViewManager.addListener(BuildCompletionListener(project), project)
    }
}