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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class UninstallAction : AnAction(
    "Uninstall App",
    "Uninstall app from device",
    IconLoader.getIcon("/icons/uninstall.svg", UninstallAction::class.java)
) {
    companion object {
        private const val PACKAGE_NAME = "com.antgroup.leopard.android"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = getConnectedDevices()
            ApplicationManager.getApplication().invokeLater {
                when {
                    devices.isEmpty() -> showNoBuild(project, "No connected devices found")
                    devices.size == 1 -> runUninstall(project, devices[0])
                    else -> showDeviceChooser(project, devices)
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun getConnectedDevices(): List<DeviceInfo> {
        return try {
            val process = ProcessBuilder("adb", "devices", "-l")
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
            .setItemChosenCallback { device -> runUninstall(project, device) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun showNoBuild(project: Project, message: String) {
        val buildId = Object()
        val buildDescriptor = DefaultBuildDescriptor(
            buildId, "Uninstall", project.basePath ?: "", System.currentTimeMillis()
        )
        val buildViewManager = project.getService(BuildViewManager::class.java)
        buildViewManager.onEvent(buildId, StartBuildEventImpl(buildDescriptor, "Uninstall"))
        BuildContentManager.getInstance(project).getOrCreateToolWindow().show()
        buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$message\n", true))
        buildViewManager.onEvent(
            buildId,
            FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), message, FailureResultImpl())
        )
    }

    private fun runUninstall(project: Project, device: DeviceInfo) {
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
                val cmd = "adb -s ${device.serial} uninstall $PACKAGE_NAME"
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$ $cmd\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Device: ${device.model} (${device.serial})\n\n", true))
                buildViewManager.onEvent(
                    buildId,
                    MessageEventImpl(buildId, MessageEvent.Kind.INFO, null, "Uninstalling $PACKAGE_NAME from ${device.model}...", null)
                )

                val process = ProcessBuilder("adb", "-s", device.serial, "uninstall", PACKAGE_NAME)
                    .directory(File(basePath))
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

    private data class DeviceInfo(val serial: String, val model: String)
}
