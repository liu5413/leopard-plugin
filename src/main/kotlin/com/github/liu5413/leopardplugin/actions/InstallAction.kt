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
import java.io.File

class InstallAction : AnAction(
    "Install App",
    "Install app to device",
    IconLoader.getIcon("/icons/install.svg", InstallAction::class.java)
) {
    companion object {
        private const val APK_PATH = "bundle_runtime/build/intermediates/apk/debug/bundle_runtime-debug.apk"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val apkFile = File(project.basePath, APK_PATH)
        if (!apkFile.exists()) {
            AdbHelper.showNoBuild(project, "Install", "APK file not found: ${apkFile.absolutePath}")
            return
        }
        BuildContentManager.getInstance(project).getOrCreateToolWindow().show()
        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = AdbHelper.getConnectedDevices(project)
            ApplicationManager.getApplication().invokeLater {
                when {
                    devices.isEmpty() -> AdbHelper.showNoBuild(project, "Install", "No connected devices found")
                    devices.size == 1 -> runInstall(project, devices[0], apkFile)
                    else -> AdbHelper.showDeviceChooser(project, devices) { device -> runInstall(project, device, apkFile) }
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun runInstall(project: Project, device: AdbHelper.DeviceInfo, apkFile: File) {
        val basePath = project.basePath ?: return
        val buildId = Object()
        val title = "Install"

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
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$ adb -s ${device.serial} install -r \"${apkFile.absolutePath}\"\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "APK: ${apkFile.name}\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Device: ${device.model} (${device.serial})\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Channel: ${AdbHelper.lastDeviceChannel} — ${AdbHelper.lastDiagnostic}\n\n", true))
                buildViewManager.onEvent(
                    buildId,
                    MessageEventImpl(buildId, MessageEvent.Kind.INFO, null, "Installing ${apkFile.name} to ${device.model}...", null)
                )

                val result = AdbHelper.installApk(project, device.serial, apkFile.absolutePath)
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
