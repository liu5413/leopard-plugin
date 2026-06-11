package com.github.liu5413.leopardplugin.actions

import com.intellij.build.BuildContentManager
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
import com.github.liu5413.leopardplugin.utils.AdbHelper
import com.github.liu5413.leopardplugin.utils.AppConstants

class StartAppWithDebuggerAction : AnAction(
    "Start App with Debugger",
    "Start app on device and wait for debugger",
    IconLoader.getIcon("/icons/startAppDebug.svg", StartAppWithDebuggerAction::class.java)
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        BuildContentManager.getInstance(project).getOrCreateToolWindow().show()
        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = AdbHelper.getConnectedDevices(project)
            ApplicationManager.getApplication().invokeLater {
                when {
                    devices.isEmpty() -> AdbHelper.showNoBuild(project, "Start App with Debugger", "No connected devices found")
                    devices.size == 1 -> runStartAppWithDebugger(project, devices[0])
                    else -> AdbHelper.showDeviceChooser(project, devices) { device -> runStartAppWithDebugger(project, device) }
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun runStartAppWithDebugger(project: Project, device: AdbHelper.DeviceInfo) {
        val basePath = project.basePath ?: return
        val buildId = Object()
        val title = "Start App with Debugger"

        val buildDescriptor = DefaultBuildDescriptor(
            buildId, title, basePath, System.currentTimeMillis()
        )

        val buildViewManager = project.getService(BuildViewManager::class.java)
        buildViewManager.onEvent(
            buildId,
            StartBuildEventImpl(buildDescriptor, "$title: ${device.model}...")
        )

        BuildContentManager.getInstance(project).getOrCreateToolWindow().show()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$ adb -s ${device.serial} shell am start -D -n ${AppConstants.MAIN_ACTIVITY}\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Device: ${device.model} (${device.serial})\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Channel: ${AdbHelper.lastDeviceChannel} — ${AdbHelper.lastDiagnostic}\n\n", true))
                buildViewManager.onEvent(
                    buildId,
                    MessageEventImpl(buildId, MessageEvent.Kind.INFO, null, "Starting app ${AppConstants.PACKAGE_NAME} with debugger on ${device.model}...", null)
                )

                val result = AdbHelper.executeShellCommand(project, device.serial, "am", "start", "-D", "-n", AppConstants.MAIN_ACTIVITY)
                if (result.output.isNotBlank()) {
                    buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, result.output, true))
                }
                val channel = if (result.usedDdmlib) "[ddmlib]" else "[cli]"

                if (result.exitCode == 0) {
                    buildViewManager.onEvent(
                        buildId,
                        FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "$title finished successfully $channel", SuccessResultImpl())
                    )
                } else {
                    buildViewManager.onEvent(
                        buildId,
                        FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "$title failed with exit code ${result.exitCode} $channel", FailureResultImpl())
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
