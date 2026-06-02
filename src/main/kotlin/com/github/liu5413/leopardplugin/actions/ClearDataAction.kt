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
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.github.liu5413.leopardplugin.utils.AdbHelper
import com.github.liu5413.leopardplugin.utils.AppConstants
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ClearDataAction : AnAction(
    "Clear Data",
    "Clear app data on device",
    IconLoader.getIcon("/icons/clearData.svg", ClearDataAction::class.java)
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        BuildContentManager.getInstance(project).getOrCreateToolWindow().show()
        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = getConnectedDevices(project)
            ApplicationManager.getApplication().invokeLater {
                when {
                    devices.isEmpty() -> showNoBuild(project, "No connected devices found")
                    devices.size == 1 -> runClearData(project, devices[0])
                    else -> showDeviceChooser(project, devices)
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun getConnectedDevices(project: Project): List<DeviceInfo> {
        return try {
            val adb = AdbHelper.resolveAdbPath(project)
            val process = ProcessBuilder(adb, "devices", "-l")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lines()
                .filter { it.isNotBlank() && !it.startsWith("List of") && !it.startsWith("*") && it.matches(Regex("^\\S+\\s+device\\b.*")) }
                .mapNotNull { line ->
                    val serial = line.split("\\s+".toRegex()).firstOrNull() ?: return@mapNotNull null
                    val model = Regex("model:(\\S+)").find(line)?.groupValues?.get(1) ?: serial
                    DeviceInfo(serial, model)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showDeviceChooser(project: Project, devices: List<DeviceInfo>) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(devices)
            .setTitle("Select Device")
            .setRenderer { _, value, _, _, _ ->
                javax.swing.JLabel("${value.model}  (${value.serial})")
            }
            .setItemChosenCallback { device -> runClearData(project, device) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun showNoBuild(project: Project, message: String) {
        val buildId = Object()
        val buildDescriptor = DefaultBuildDescriptor(
            buildId, "Clear Data", project.basePath ?: "", System.currentTimeMillis()
        )
        val buildViewManager = project.getService(BuildViewManager::class.java)
        buildViewManager.onEvent(buildId, StartBuildEventImpl(buildDescriptor, "Clear Data"))
        BuildContentManager.getInstance(project).getOrCreateToolWindow().show()
        buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$message\n", true))
        buildViewManager.onEvent(
            buildId,
            FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), message, FailureResultImpl())
        )
    }

    private fun runClearData(project: Project, device: DeviceInfo) {
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
                val adb = AdbHelper.resolveAdbPath(project)
                val cmd = "$adb -s ${device.serial} shell pm clear ${AppConstants.PACKAGE_NAME}"
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$ $cmd\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Device: ${device.model} (${device.serial})\n\n", true))
                buildViewManager.onEvent(
                    buildId,
                    MessageEventImpl(buildId, MessageEvent.Kind.INFO, null, "Clearing data for ${AppConstants.PACKAGE_NAME} on ${device.model}...", null)
                )

                val process = ProcessBuilder(adb, "-s", device.serial, "shell", "pm", "clear", AppConstants.PACKAGE_NAME)
                    .directory(File(basePath))
                    .redirectErrorStream(true)
                    .start()

                val outputBuilder = StringBuilder()
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        outputBuilder.append(line).append('\n')
                        buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, line + "\n", true))
                    }
                }

                val exitCode = process.waitFor()
                val clearSucceeded = exitCode == 0 && outputBuilder.toString().contains("Success")
                if (clearSucceeded) {
                    buildViewManager.onEvent(
                        buildId,
                        FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "$title finished successfully", SuccessResultImpl())
                    )
                } else {
                    runFallbackOpenSettings(project, device, adb, basePath, buildId, buildViewManager, title, exitCode)
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
        device: DeviceInfo,
        adb: String,
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

            val fallbackCmd = "$adb -s ${device.serial} shell am start " +
                "-a android.settings.APPLICATION_DETAILS_SETTINGS " +
                "-d package:${AppConstants.PACKAGE_NAME} " +
                "--es \":settings:fragment_args_key\" storage"
            buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "\n$ $fallbackCmd\n", true))

            val fallbackProcess = ProcessBuilder(
                adb, "-s", device.serial, "shell", "am", "start",
                "-a", "android.settings.APPLICATION_DETAILS_SETTINGS",
                "-d", "package:${AppConstants.PACKAGE_NAME}",
                "--es", ":settings:fragment_args_key", "storage"
            )
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()

            val fallbackOutput = StringBuilder()
            BufferedReader(InputStreamReader(fallbackProcess.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    fallbackOutput.append(line).append('\n')
                    buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, line + "\n", true))
                }
            }

            val fallbackExitCode = fallbackProcess.waitFor()
            val fallbackText = fallbackOutput.toString()
            val fallbackSucceeded = fallbackExitCode == 0 &&
                !fallbackText.contains("Error", ignoreCase = true) &&
                !fallbackText.contains("Exception", ignoreCase = true)

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
                        "$title failed (pm clear exit=$clearExitCode, fallback exit=$fallbackExitCode)",
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

    private data class DeviceInfo(val serial: String, val model: String)
}