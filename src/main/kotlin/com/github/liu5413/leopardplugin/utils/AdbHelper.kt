package com.github.liu5413.leopardplugin.utils

import com.intellij.openapi.util.SystemInfo
import java.io.File

object AdbHelper {
    private val adbFileName = if (SystemInfo.isWindows) "adb.exe" else "adb"

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

    fun resolveAdbPath(): String {
        for (sdkPath in candidateSdkPaths) {
            val adb = File(sdkPath, "platform-tools/$adbFileName")
            if (adb.isFile && adb.canExecute()) return adb.absolutePath
        }
        return adbFileName
    }

    fun resolveAdbPath(project: com.intellij.openapi.project.Project): String {
        val fromSdk = resolveAdbPath()
        if (fromSdk != adbFileName) return fromSdk

        val sdkPath = project.basePath?.let {
            findSdkFromLocalProperties(File(it))
        }
        if (sdkPath != null) {
            val adb = File(sdkPath, "platform-tools/$adbFileName")
            if (adb.isFile && adb.canExecute()) return adb.absolutePath
        }

        return adbFileName
    }

    private fun findSdkFromLocalProperties(projectDir: File): String? {
        val propsFile = File(projectDir, "local.properties") ?: return null
        if (!propsFile.isFile) return null
        val lines = propsFile.readLines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("sdk.dir=")) {
                val path = trimmed.removePrefix("sdk.dir=").trim()
                if (File(path).isDirectory) return path
            }
        }
        return null
    }
}