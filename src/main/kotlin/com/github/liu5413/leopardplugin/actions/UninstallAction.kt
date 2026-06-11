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

class UninstallAction : AnAction(
    "Uninstall App",
    "Uninstall app from device",
    IconLoader.getIcon("/icons/uninstall.svg", UninstallAction::class.java)
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        BuildContentManager.getInstance(project).getOrCreateToolWindow().show()
        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = AdbHelper.getConnectedDevices(project)
            ApplicationManager.getApplication().invokeLater {
                when {
                    devices.isEmpty() -> AdbHelper.showNoBuild(project, "Uninstall", "No connected devices found")
                    devices.size == 1 -> runUninstall(project, devices[0])
                    else -> AdbHelper.showDeviceChooser(project, devices) { device -> runUninstall(project, device) }
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun runUninstall(project: Project, device: AdbHelper.DeviceInfo) {
        val basePath = project.basePath ?: return
        val buildId = Object()
        val title = "Uninstall"

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
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$ adb -s ${device.serial} uninstall ${AppConstants.PACKAGE_NAME}\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Device: ${device.model} (${device.serial})\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Channel: ${AdbHelper.lastDeviceChannel} — ${AdbHelper.lastDiagnostic}\n\n", true))
                buildViewManager.onEvent(
                    buildId,
                    MessageEventImpl(buildId, MessageEvent.Kind.INFO, null, "Uninstalling ${AppConstants.PACKAGE_NAME} from ${device.model}...", null)
                )

                val result = AdbHelper.uninstallPackage(project, device.serial, AppConstants.PACKAGE_NAME)
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
