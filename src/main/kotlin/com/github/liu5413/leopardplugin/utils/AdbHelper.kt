package com.github.liu5413.leopardplugin.utils

import com.intellij.build.BuildContentManager
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfo
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object AdbHelper {
    private val LOG = Logger.getInstance(AdbHelper::class.java)
    private val adbFileName = if (SystemInfo.isWindows) "adb.exe" else "adb"

    data class DeviceInfo(val serial: String, val model: String) {
        override fun toString(): String = "$model  ($serial)"
    }

    data class CommandResult(val exitCode: Int, val output: String, val usedDdmlib: Boolean = false)

    /** 上一次 getConnectedDevices 使用的通道 */
    @Volatile
    var lastDeviceChannel: String = ""
        private set

    /** 上一次 ddmlib 尝试的诊断信息 */
    @Volatile
    var lastDiagnostic: String = ""
        private set

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    fun getConnectedDevices(project: Project): List<DeviceInfo> {
        val diag = StringBuilder()
        try {
            val devices = getDevicesViaDdmlib(project)
            if (devices != null) {
                lastDeviceChannel = "[ddmlib]"
                lastDiagnostic = "ddmlib OK, ${devices.size} device(s)"
                LOG.info("[ddmlib] Got ${devices.size} device(s)")
                return devices
            }
            diag.append("getDevicesViaDdmlib returned null")
            LOG.info("[ddmlib] getDevicesViaDdmlib returned null, falling back to CLI")
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            diag.append("${cause.javaClass.simpleName}: ${cause.message}")
            LOG.warn("[ddmlib] Not available, falling back to CLI: ${e.javaClass.name}: ${e.message}")
        }
        lastDeviceChannel = "[cli]"
        lastDiagnostic = "ddmlib failed ($diag), using CLI fallback"
        return getDevicesViaCli(project)
    }

    fun executeShellCommand(project: Project, serial: String, vararg command: String): CommandResult {
        try {
            val result = executeViaDdmlib(project, serial, command.joinToString(" "))
            if (result != null) return result
        } catch (e: Throwable) {
            LOG.info("[ddmlib] Shell command fallback to CLI: ${e.javaClass.simpleName}")
        }
        return executeViaCli(project, serial, *command)
    }

    fun installApk(project: Project, serial: String, apkPath: String): CommandResult {
        try {
            val result = installViaDdmlib(project, serial, apkPath)
            if (result != null) return result
        } catch (e: Throwable) {
            LOG.info("[ddmlib] Install fallback to CLI: ${e.javaClass.simpleName}")
        }
        return installViaCli(project, serial, apkPath)
    }

    fun uninstallPackage(project: Project, serial: String, packageName: String): CommandResult {
        try {
            val result = uninstallViaDdmlib(project, serial, packageName)
            if (result != null) return result
        } catch (e: Throwable) {
            LOG.info("[ddmlib] Uninstall fallback to CLI: ${e.javaClass.simpleName}")
        }
        val adb = resolveAdbPath(project)
        return runProcess(adb, "-s", serial, "uninstall", packageName)
    }

    fun showDeviceChooser(project: Project, devices: List<DeviceInfo>, callback: (DeviceInfo) -> Unit) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(devices)
            .setTitle("Select Device")
            .setRenderer { _, value, _, _, _ ->
                javax.swing.JLabel("${value.model}  (${value.serial})")
            }
            .setItemChosenCallback { device -> callback(device) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    fun showNoBuild(project: Project, title: String, message: String) {
        val buildId = Object()
        val buildDescriptor = DefaultBuildDescriptor(
            buildId, title, project.basePath ?: "", System.currentTimeMillis()
        )
        val buildViewManager = project.getService(BuildViewManager::class.java)
        buildViewManager.onEvent(buildId, StartBuildEventImpl(buildDescriptor, title))
        BuildContentManager.getInstance(project).getOrCreateToolWindow().show()
        buildViewManager.onEvent(buildId, OutputBuildEventImpl(buildId, "$message\n", true))
        buildViewManager.onEvent(
            buildId,
            FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), message, FailureResultImpl())
        )
    }

    // ──────────────────────────────────────────────
    // ddmlib channel (Android Studio only)
    // ──────────────────────────────────────────────

    private fun getDebugBridge(project: Project): Any? {
        val bridge = tryGetBridgeViaAdbService(project)
            ?: tryGetBridgeViaAndroidSdkUtils(project)
        LOG.info("[ddmlib] getDebugBridge returned: ${bridge?.javaClass?.name ?: "null"}")
        return bridge
    }

    /** Primary: AdbService.getInstance().getDebugBridge(project).get() */
    private fun tryGetBridgeViaAdbService(project: Project): Any? {
        return try {
            val serviceClass = Class.forName("com.android.tools.idea.adb.AdbService")
            val service = serviceClass.getMethod("getInstance").invoke(null)
            // Try getDebugBridge(Project) first (AS 2025.3+)
            val future = try {
                serviceClass.getMethod("getDebugBridge", Project::class.java).invoke(service, project)
            } catch (_: NoSuchMethodException) {
                // Fallback to getDebugBridge(File)
                val adbFile = resolveAdbFile(project) ?: return null
                serviceClass.getMethod("getDebugBridge", File::class.java).invoke(service, adbFile)
            }
            future?.javaClass?.getMethod("get", Long::class.java, TimeUnit::class.java)
                ?.invoke(future, 5L, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            LOG.info("[ddmlib] AdbService failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Legacy fallback: AndroidSdkUtils.getDebugBridge(project) */
    private fun tryGetBridgeViaAndroidSdkUtils(project: Project): Any? {
        return try {
            val clazz = Class.forName("org.jetbrains.android.sdk.AndroidSdkUtils")
            val method = clazz.getMethod("getDebugBridge", Project::class.java)
            var bridge: Any? = null
            ApplicationManager.getApplication().invokeAndWait {
                bridge = method.invoke(null, project)
            }
            bridge
        } catch (e: Throwable) {
            LOG.info("[ddmlib] AndroidSdkUtils failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun resolveAdbFile(project: Project): File? {
        // AdbFileProvider.fromProject(project)
        try {
            val providerClass = Class.forName("com.android.tools.idea.adb.AdbFileProvider")
            val fromProject = providerClass.getMethod("fromProject", Project::class.java)
            val provider = fromProject.invoke(null, project) ?: return null
            val adb = providerClass.getMethod("get").invoke(provider) as? File
            if (adb != null && adb.isFile && adb.canExecute()) return adb
        } catch (_: Throwable) {}
        val path = resolveAdbPath(project)
        val file = File(path)
        return if (file.isFile && file.canExecute()) file else null
    }

    private fun getDevicesViaDdmlib(project: Project): List<DeviceInfo>? {
        val bridge = getDebugBridge(project) ?: return null
        val isConnected = bridge.javaClass.getMethod("isConnected").invoke(bridge) as Boolean
        LOG.info("[ddmlib] bridge.isConnected = $isConnected")
        if (!isConnected) return null
        @Suppress("UNCHECKED_CAST")
        val devices = bridge.javaClass.getMethod("getDevices").invoke(bridge) as? Array<Any>
        LOG.info("[ddmlib] bridge.getDevices() returned ${devices?.size ?: "null"} device(s)")
        if (devices == null) return null
        return devices.mapNotNull { device ->
            try {
                val serial = device.javaClass.getMethod("getSerialNumber").invoke(device) as String
                val name = try {
                    device.javaClass.getMethod("getName").invoke(device) as? String ?: serial
                } catch (_: Throwable) { serial }
                DeviceInfo(serial, name)
            } catch (e: Throwable) {
                LOG.warn("[ddmlib] Failed to read device info: ${e.message}")
                null
            }
        }
    }

    private fun findDdmlibDevice(project: Project, serial: String): Any? {
        val bridge = getDebugBridge(project) ?: return null
        @Suppress("UNCHECKED_CAST")
        val devices = bridge.javaClass.getMethod("getDevices").invoke(bridge) as? Array<Any> ?: return null
        return devices.firstOrNull {
            (it.javaClass.getMethod("getSerialNumber").invoke(it) as String) == serial
        }
    }

    private fun executeViaDdmlib(project: Project, serial: String, shellCommand: String): CommandResult? {
        val device = findDdmlibDevice(project, serial) ?: return null
        val receiverClass = Class.forName("com.android.ddmlib.CollectingOutputReceiver")
        val receiver = receiverClass.getDeclaredConstructor().newInstance()
        val iShellReceiverClass = Class.forName("com.android.ddmlib.IShellOutputReceiver")
        device.javaClass.getMethod(
            "executeShellCommand",
            String::class.java,
            iShellReceiverClass,
            Long::class.java,
            TimeUnit::class.java
        ).invoke(device, shellCommand, receiver, 15L, TimeUnit.SECONDS)
        val output = receiverClass.getMethod("getOutput").invoke(receiver) as String
        LOG.info("[ddmlib] Executed: $shellCommand")
        return CommandResult(0, output, usedDdmlib = true)
    }

    private fun installViaDdmlib(project: Project, serial: String, apkPath: String): CommandResult? {
        val device = findDdmlibDevice(project, serial) ?: return null
        val result = device.javaClass.getMethod(
            "installPackage",
            String::class.java,
            Boolean::class.java,
            Array<String>::class.java
        ).invoke(device, apkPath, true, emptyArray<String>()) as? String
        LOG.info("[ddmlib] Install result: $result")
        return if (result == null) {
            CommandResult(0, "Success", usedDdmlib = true)
        } else {
            CommandResult(1, result, usedDdmlib = true)
        }
    }

    private fun uninstallViaDdmlib(project: Project, serial: String, packageName: String): CommandResult? {
        val device = findDdmlibDevice(project, serial) ?: return null
        val result = device.javaClass.getMethod(
            "uninstallPackage",
            String::class.java
        ).invoke(device, packageName) as? String
        LOG.info("[ddmlib] Uninstall result: $result")
        return if (result == null) {
            CommandResult(0, "Success", usedDdmlib = true)
        } else {
            CommandResult(1, result, usedDdmlib = true)
        }
    }

    // ──────────────────────────────────────────────
    // CLI channel (fallback)
    // ──────────────────────────────────────────────

    private fun getDevicesViaCli(project: Project): List<DeviceInfo> {
        return try {
            val adb = resolveAdbPath(project)
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
            LOG.warn("[cli] Failed to list devices: ${e.message}")
            emptyList()
        }
    }

    private fun executeViaCli(project: Project, serial: String, vararg command: String): CommandResult {
        val adb = resolveAdbPath(project)
        return runProcess(adb, "-s", serial, "shell", *command)
    }

    private fun installViaCli(project: Project, serial: String, apkPath: String): CommandResult {
        val adb = resolveAdbPath(project)
        return runProcess(adb, "-s", serial, "install", "-r", apkPath)
    }

    private fun runProcess(vararg command: String): CommandResult {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append('\n')
            }
        }
        val exitCode = process.waitFor()
        return CommandResult(exitCode, output.toString())
    }

    // ──────────────────────────────────────────────
    // ADB path resolution (used by CLI channel + MockDataAutoConnect)
    // ──────────────────────────────────────────────

    private val candidateSdkPaths: List<String>
        get() = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_SDK_HOME"),
            userHomeSdkPath(),
        )

    private fun userHomeSdkPath(): String? {
        val home = System.getProperty("user.home") ?: return null
        val path = if (SystemInfo.isMac) "$home/Library/Android/sdk"
                   else if (SystemInfo.isLinux) "$home/Android/Sdk"
                   else "$home/AppData/Local/Android/sdk"
        return if (File(path).isDirectory) path else null
    }

    private fun findAdbInSdkPath(sdkPath: String?): String? {
        if (sdkPath.isNullOrBlank()) return null
        val adb = File(sdkPath, "platform-tools/$adbFileName")
        return if (adb.isFile && adb.canExecute()) adb.absolutePath else null
    }

    fun resolveAdbPath(): String {
        for (sdkPath in candidateSdkPaths) {
            findAdbInSdkPath(sdkPath)?.let { return it }
        }
        findAdbFromIdeProperties()?.let { return it }
        return adbFileName
    }

    fun resolveAdbPath(project: Project): String {
        // Use AdbFileProvider (stable across AS versions)
        resolveAdbFile(project)?.let { return it.absolutePath }

        for (sdkPath in candidateSdkPaths) {
            findAdbInSdkPath(sdkPath)?.let { return it }
        }

        project.basePath?.let { findSdkFromLocalProperties(File(it)) }
            ?.let { findAdbInSdkPath(it) }
            ?.let { return it }

        findAdbFromIdeProperties()?.let { return it }
        findAdbFromProjectProperties(project)?.let { return it }

        return adbFileName
    }

    private fun findAdbFromIdeProperties(): String? {
        val props = PropertiesComponent.getInstance()
        val sdkPath = props.getValue("android.sdk.path")
        return findAdbInSdkPath(sdkPath)
    }

    private fun findAdbFromProjectProperties(project: Project): String? {
        val props = PropertiesComponent.getInstance(project)
        val sdkPath = props.getValue("android.sdk.path")
        return findAdbInSdkPath(sdkPath)
    }

    private fun findSdkFromLocalProperties(projectDir: File): String? {
        val propsFile = File(projectDir, "local.properties")
        if (!propsFile.isFile) return null
        for (line in propsFile.readLines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("sdk.dir=")) {
                val path = trimmed.removePrefix("sdk.dir=").trim()
                if (File(path).isDirectory) return path
            }
        }
        return null
    }
}
