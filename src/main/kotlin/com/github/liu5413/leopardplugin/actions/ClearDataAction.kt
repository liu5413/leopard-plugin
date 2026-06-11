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

class ClearDataAction : AnAction(
    "Clear Data",
    "Clear app data on device",
    IconLoader.getIcon("/icons/clearData.svg", ClearDataAction::class.java)
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        BuildContentManager.getInstance(project).getOrCreateToolWindow().show()
        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = AdbHelper.getConnectedDevices(project)
            ApplicationManager.getApplication().invokeLater {
                when {
                    devices.isEmpty() -> AdbHelper.showNoBuild(project, "Clear Data", "No connected devices found")
                    devices.size == 1 -> runClearData(project, devices[0])
                    else -> AdbHelper.showDeviceChooser(project, devices) { device -> runClearData(project, device) }
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun runClearData(project: Project, device: AdbHelper.DeviceInfo) {
        val basePath = project.basePath ?: return
        val buildId = Object()
        val title = "Clear Data"

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
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$ adb -s ${device.serial} shell pm clear ${AppConstants.PACKAGE_NAME}\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Device: ${device.model} (${device.serial})\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Channel: ${AdbHelper.lastDeviceChannel} — ${AdbHelper.lastDiagnostic}\n\n", true))
                buildViewManager.onEvent(
                    buildId,
                    MessageEventImpl(buildId, MessageEvent.Kind.INFO, null, "Clearing data for ${AppConstants.PACKAGE_NAME} on ${device.model}...", null)
                )

                val result = AdbHelper.executeShellCommand(project, device.serial, "pm", "clear", AppConstants.PACKAGE_NAME)
                if (result.output.isNotBlank()) {
                    buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, result.output, true))
                }
                val channel = if (result.usedDdmlib) "[ddmlib]" else "[cli]"

                val clearSucceeded = result.exitCode == 0 && result.output.contains("Success")
                if (clearSucceeded) {
                    buildViewManager.onEvent(
                        buildId,
                        FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "$title finished successfully $channel", SuccessResultImpl())
                    )
                } else {
                    runFallbackOpenSettings(project, device, basePath, buildId, buildViewManager, title, result.exitCode)
                }
            } catch (ex: Exception) {
                buildViewManager.onEvent(
                    buildId,
                    FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "Error: ${ex.message}", FailureResultImpl(ex))
                )
            }
        }
    }

    private fun runFallbackOpenSettings(
        project: Project,
        device: AdbHelper.DeviceInfo,
        basePath: String,
        buildId: Any,
        buildViewManager: BuildViewManager,
        title: String,
        clearExitCode: Int
    ) {
        try {
            buildViewManager.onEvent(
                buildId,
                MessageEventImpl(
                    buildId,
                    MessageEvent.Kind.WARNING,
                    null,
                    "pm clear failed (exit code $clearExitCode). Falling back to open app storage settings...",
                    null
                )
            )

            val fallbackCmd = "am start -a android.settings.APPLICATION_DETAILS_SETTINGS " +
                "-d package:${AppConstants.PACKAGE_NAME} " +
                "--es :settings:fragment_args_key storage"
            buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "\n$ adb -s ${device.serial} shell $fallbackCmd\n", true))

            val result = AdbHelper.executeShellCommand(
                project, device.serial,
                "am", "start",
                "-a", "android.settings.APPLICATION_DETAILS_SETTINGS",
                "-d", "package:${AppConstants.PACKAGE_NAME}",
                "--es", ":settings:fragment_args_key", "storage"
            )
            if (result.output.isNotBlank()) {
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, result.output, true))
            }

            val fallbackSucceeded = result.exitCode == 0 &&
                !result.output.contains("Error", ignoreCase = true) &&
                !result.output.contains("Exception", ignoreCase = true)

            if (fallbackSucceeded) {
                buildViewManager.onEvent(
                    buildId,
                    MessageEventImpl(
                        buildId,
                        MessageEvent.Kind.WARNING,
                        null,
                        "pm clear failed; app storage settings opened on ${device.model}. Please clear data manually.",
                        null
                    )
                )
                buildViewManager.onEvent(
                    buildId,
                    FinishBuildEventImpl(
                        buildId,
                        null,
                        System.currentTimeMillis(),
                        "$title finished with warnings (manual action required)",
                        SuccessResultImpl()
                    )
                )
            } else {
                buildViewManager.onEvent(
                    buildId,
                    FinishBuildEventImpl(
                        buildId,
                        null,
                        System.currentTimeMillis(),
                        "$title failed (pm clear exit=$clearExitCode, fallback exit=${result.exitCode})",
                        FailureResultImpl()
                    )
                )
            }
        } catch (ex: Exception) {
            buildViewManager.onEvent(
                buildId,
                FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "Error during fallback: ${ex.message}", FailureResultImpl(ex))
            )
        }
    }
}
