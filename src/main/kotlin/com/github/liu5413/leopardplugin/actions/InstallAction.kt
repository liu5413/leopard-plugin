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
            showNoBuild(project, "APK file not found: ${apkFile.absolutePath}")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = getConnectedDevices()
            ApplicationManager.getApplication().invokeLater {
                when {
                    devices.isEmpty() -> showNoBuild(project, "No connected devices found")
                    devices.size == 1 -> runInstall(project, devices[0], apkFile)
                    else -> showDeviceChooser(project, devices, apkFile)
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

    private fun showDeviceChooser(project: Project, devices: List<DeviceInfo>, apkFile: File) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(devices)
            .setTitle("Select Device")
            .setRenderer { _, value, _, _, _ ->
                javax.swing.JLabel("${value.model}  (${value.serial})")
            }
            .setItemChosenCallback { device -> runInstall(project, device, apkFile) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun showNoBuild(project: Project, message: String) {
        val buildId = Object()
        val buildDescriptor = DefaultBuildDescriptor(
            buildId, "Install", project.basePath ?: "", System.currentTimeMillis()
        )
        val buildViewManager = project.getService(BuildViewManager::class.java)
        buildViewManager.onEvent(buildId, StartBuildEventImpl(buildDescriptor, "Install"))
        BuildContentManager.getInstance(project).getOrCreateToolWindow().show()
        buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$message\n", true))
        buildViewManager.onEvent(
            buildId,
            FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), message, FailureResultImpl())
        )
    }

    private fun runInstall(project: Project, device: DeviceInfo, apkFile: File) {
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
                val cmd = "adb -s ${device.serial} install -r \"${apkFile.absolutePath}\""
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$ $cmd\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "APK: ${apkFile.name}\n", true))
                buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "Device: ${device.model} (${device.serial})\n\n", true))
                buildViewManager.onEvent(
                    buildId,
                    MessageEventImpl(buildId, MessageEvent.Kind.INFO, null, "Installing ${apkFile.name} to ${device.model}...", null)
                )

                val process = ProcessBuilder("adb", "-s", device.serial, "install", "-r", apkFile.absolutePath)
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