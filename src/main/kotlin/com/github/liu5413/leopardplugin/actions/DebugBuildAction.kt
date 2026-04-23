package com.github.liu5413.leopardplugin.actions

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.MessageEventImpl
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import java.io.BufferedReader
import java.io.InputStreamReader

class DebugBuildAction : AnAction(
    "Debug Build",
    "Run gradle buildDebug",
    IconLoader.getIcon("/icons/debugBuild.svg", DebugBuildAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runDebugBuild(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun runDebugBuild(project: Project) {
        val basePath = project.basePath ?: return
        val buildId = Object()
        val title = "Debug Build"

        val buildDescriptor = DefaultBuildDescriptor(
            buildId, title, basePath, System.currentTimeMillis()
        )

        val buildViewManager = project.getService(BuildViewManager::class.java)
        buildViewManager.onEvent(
            buildId,
            StartBuildEventImpl(buildDescriptor, "$title: running...")
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$ ./gradlew buildDebug\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Working directory: $basePath\n\n", true))
                buildViewManager.onEvent(
                    buildId,
                    MessageEventImpl(buildId, MessageEvent.Kind.INFO, null, "Running ./gradlew buildDebug...", null)
                )

                val process = ProcessBuilder("./gradlew", "buildDebug")
                    .directory(java.io.File(basePath))
                    .redirectErrorStream(true)
                    .start()

                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, line + "\n", true))
                    }
                }

                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    buildViewManager.onEvent(
                        buildId,
                        FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "$title finished successfully", SuccessResultImpl())
                    )
                } else {
                    buildViewManager.onEvent(
                        buildId,
                        FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "$title failed with exit code $exitCode", FailureResultImpl())
                    )
                }
            } catch (ex: Exception) {
                buildViewManager.onEvent(
                    buildId,
                    FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "Error: ${ex.message}", FailureResultImpl(ex))
                )
            }
        }
    }
}
