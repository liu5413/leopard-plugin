package com.alipay.devtools.mockdata

import com.github.liu5413.leopardplugin.utils.AdbHelper
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * MockData 自动连接管理器
 * 通过 adb forward 自动连接设备
 */
class MockDataAutoConnect(private val project: Project) {

    private val logger = Logger.getInstance(MockDataAutoConnect::class.java)

    /**
     * 应用类型对应的 MockData 端口
     */
    companion object {
        val APP_PORTS = mapOf(
            "com.antgroup.leopard.android" to 1218,      // Leopard
            "com.eg.android.AlipayGphone" to 1212,      // 支付宝
            "com.antfortune.wealth" to 1213,            // 财富
            "com.antgroup.zhixiaobao.android" to 1214, // 支小宝
            "com.antgroup.anzhener.android" to 1215,   // 安诊儿
            "hk.alipay.wallet" to 1216,                // 支付宝HK
            "com.antgroup.aijk.android" to 1217,     // AIJK
        )
        const val DEFAULT_PORT = 1218  // 默认端口
        const val WS_PORT = 2121       // WebSocket 端口
    }

    /**
     * 自动连接配置
     */
    data class AutoConnectConfig(
        val packageName: String? = null,  // 指定包名，null 则自动检测
        val preferWebSocket: Boolean = false,  // 是否优先使用 WebSocket 端口
        val localPort: Int = 0  // 本地映射端口，0 则自动分配
    )

    /**
     * 连接结果
     */
    data class ConnectResult(
        val success: Boolean,
        val packageName: String? = null,
        val devicePort: Int = 0,
        val localPort: Int = 0,
        val errorMessage: String? = null
    )

    /**
     * 自动检测并连接设备
     */
    fun autoConnect(config: AutoConnectConfig = AutoConnectConfig()): CompletableFuture<ConnectResult> {
        return if (config.packageName != null) {
            // 指定了包名，直接连接
            connectApp(config.packageName, config.preferWebSocket, config.localPort)
        } else {
            // 自动检测已连接设备上的应用
            autoDetectAndConnect(config.preferWebSocket, config.localPort)
        }
    }

    /**
     * 自动检测设备上的应用并连接
     */
    private fun autoDetectAndConnect(preferWebSocket: Boolean, localPort: Int): CompletableFuture<ConnectResult> {
        val future = CompletableFuture<ConnectResult>()

        // 获取已连接设备列表
        getConnectedDevices().thenAccept { devices ->
            if (devices.isEmpty()) {
                future.complete(ConnectResult(false, errorMessage = "No connected devices found"))
                return@thenAccept
            }

            val device = devices.first() // 使用第一个设备
            logger.info("Using device: $device")

            // 检测设备上运行的 MockData 应用
            detectMockDataApps(device).thenAccept { apps ->
                if (apps.isEmpty()) {
                    future.complete(ConnectResult(false, errorMessage = "No MockData apps found on device"))
                    return@thenAccept
                }

                // 尝试连接第一个找到的应用
                val targetPackage = apps.first()
                logger.info("Found MockData app: $targetPackage")

                connectAppInternal(device, targetPackage, preferWebSocket, localPort)
                    .thenAccept { result ->
                        future.complete(result)
                    }
                    .exceptionally { error ->
                        future.complete(ConnectResult(false, errorMessage = error.message))
                        null
                    }
            }
        }.exceptionally { error ->
            future.complete(ConnectResult(false, errorMessage = error.message))
            null
        }

        return future
    }

    /**
     * 连接指定应用
     */
    private fun connectApp(packageName: String, preferWebSocket: Boolean, localPort: Int): CompletableFuture<ConnectResult> {
        return getConnectedDevices().thenCompose { devices ->
            if (devices.isEmpty()) {
                CompletableFuture.completedFuture(ConnectResult(false, errorMessage = "No connected devices"))
            } else {
                connectAppInternal(devices.first(), packageName, preferWebSocket, localPort)
            }
        }
    }

    /**
     * 内部连接实现
     */
    private fun connectAppInternal(
        device: String,
        packageName: String,
        preferWebSocket: Boolean,
        localPort: Int
    ): CompletableFuture<ConnectResult> {
        val future = CompletableFuture<ConnectResult>()

        // 确定设备端口
        val devicePort = if (preferWebSocket) {
            WS_PORT
        } else {
            APP_PORTS[packageName] ?: DEFAULT_PORT
        }

        // 确定本地端口
        val actualLocalPort = if (localPort > 0) localPort else findAvailablePort()

        logger.info("Connecting to $packageName on device port $devicePort, local port $actualLocalPort")

        // 先在设备侧探测端口是否真的 LISTEN。adb forward 即便目标进程没 listen 也能"建立"
        // (adb daemon 自己接 TCP),后续 WebSocket 才会卡到超时。这里前置探测把"端侧未启动 SDK"
        // 的失败提早到几百 ms 内返回,而不是拖到 3s WebSocket 超时。
        verifyDevicePortListen(device, devicePort).thenAccept { listening ->
            if (!listening) {
                future.complete(ConnectResult(
                    false,
                    errorMessage = "Device port $devicePort is not LISTEN. " +
                        "确认 $packageName 已启动且集成 mockdata SDK。"
                ))
                return@thenAccept
            }

            // 设置 adb forward
            setupAdbForward(device, devicePort, actualLocalPort).thenAccept { success ->
                if (!success) {
                    future.complete(ConnectResult(false, errorMessage = "Failed to setup adb forward"))
                    return@thenAccept
                }

                future.complete(ConnectResult(
                    success = true,
                    packageName = packageName,
                    devicePort = devicePort,
                    localPort = actualLocalPort
                ))
            }.exceptionally { error ->
                future.complete(ConnectResult(false, errorMessage = error.message))
                null
            }
        }.exceptionally { error ->
            future.complete(ConnectResult(false, errorMessage = error.message))
            null
        }

        return future
    }

    /**
     * 获取已连接的 adb 设备列表(只保留 state=device,跳过 unauthorized/offline/no permissions)
     */
    private fun getConnectedDevices(): CompletableFuture<List<String>> {
        return executeAdbCommand("devices").thenApply { output ->
            output.lines()
                .drop(1) // 跳过 "List of devices attached"
                .mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 2 && parts[1].trim() == "device") parts[0].trim() else null
                }
                .filter { it.isNotEmpty() }
                .also { logger.info("Found devices: $it") }
        }
    }

    /**
     * 检测设备上运行的 MockData 应用
     */
    private fun detectMockDataApps(device: String): CompletableFuture<List<String>> {
        val future = CompletableFuture<List<String>>()

        // 获取运行中的应用
        executeAdbCommand("-s", device, "shell", "ps", "-A").thenAccept { output ->
            val runningApps = output.lines()
                .mapNotNull { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    parts.lastOrNull()?.takeIf { it.contains(".") }
                }
                .toSet()

            logger.info("Running apps: ${runningApps.size}")

            // 匹配已知的 MockData 应用
            val mockDataApps = APP_PORTS.keys.filter { packageName ->
                runningApps.any { it == packageName || it.startsWith(packageName) }
            }

            // 如果没找到，尝试通过端口监听检测
            if (mockDataApps.isEmpty()) {
                detectByPort(device).thenAccept { apps ->
                    future.complete(apps)
                }.exceptionally { error ->
                    logger.error("Failed to detect by port", error)
                    future.complete(emptyList())
                    null
                }
            } else {
                future.complete(mockDataApps)
            }
        }.exceptionally { error ->
            logger.error("Failed to detect apps", error)
            future.complete(emptyList())
            null
        }

        return future
    }

    /**
     * 通过端口监听检测应用
     *
     * 注意:WS_PORT(2121)所有 mockdata App 共享,不能用它反推具体包名,
     * 这里只匹配各 App 各自的 TCP 端口(1212-1218),否则会把 7 个包名全标记成"在跑"。
     */
    private fun detectByPort(device: String): CompletableFuture<List<String>> {
        return executeAdbCommand("-s", device, "shell", "netstat", "-tln").thenApply { output ->
            val listeningPorts = parseListeningPorts(output)
            logger.info("Listening ports: $listeningPorts")

            APP_PORTS.filterValues { it in listeningPorts }.keys.toList()
        }
    }

    /**
     * 从 netstat -tln 输出里解析出 LISTEN 状态的本地端口列表。
     */
    private fun parseListeningPorts(output: String): Set<Int> {
        val portRegex = ":(\\d+)".toRegex()
        return output.lines()
            .filter { it.contains("LISTEN", ignoreCase = true) }
            .mapNotNull { line ->
                portRegex.findAll(line)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                    .lastOrNull() // 本地端口在最后一段冒号后
            }
            .toSet()
    }

    /**
     * 设置 adb forward
     */
    private fun setupAdbForward(device: String, devicePort: Int, localPort: Int): CompletableFuture<Boolean> {
        // 先移除可能存在的 forward
        return executeAdbCommand("-s", device, "forward", "--remove", "tcp:$localPort")
            .exceptionally { "" } // 忽略错误
            .thenCompose {
                // 设置新的 forward
                executeAdbCommand("-s", device, "forward", "tcp:$localPort", "tcp:$devicePort")
            }
            .thenApply { output ->
                logger.info("Adb forward setup: local $localPort -> device $devicePort")
                true
            }
            .exceptionally { error ->
                logger.error("Failed to setup adb forward", error)
                false
            }
    }

    /**
     * 探测设备端某端口是否真的处于 LISTEN 状态。
     *
     * 必要性:adb forward 不依赖目标端口是否被监听 — adb daemon 自己接 TCP,Socket().connect 永远成功,
     * 所以原先 verifyConnection(localPort) 几乎一定返回 true,真正失败要拖到 WebSocket 超时才暴露。
     *
     * 实现:优先 `netstat -tln`(可读)失败再 fallback 到 `cat /proc/net/tcp /proc/net/tcp6`(端口为十六进制)。
     */
    private fun verifyDevicePortListen(device: String, devicePort: Int): CompletableFuture<Boolean> {
        return executeAdbCommand("-s", device, "shell", "netstat", "-tln")
            .thenApply { output -> devicePort in parseListeningPorts(output) }
            .thenCompose { found ->
                if (found) CompletableFuture.completedFuture(true)
                else checkProcNetTcp(device, devicePort)
            }
            .exceptionally { error ->
                logger.warn("verifyDevicePortListen failed: ${error.message}; falling through as not-listening")
                false
            }
    }

    /**
     * 从 /proc/net/tcp(+tcp6)里查端口是否处于 LISTEN(state=0A,十六进制)。
     * 行格式:`  sl  local_address  rem_address  st  ...`,其中 local_address = `HEXIP:HEXPORT`。
     */
    private fun checkProcNetTcp(device: String, devicePort: Int): CompletableFuture<Boolean> {
        val hexPort = devicePort.toString(16).uppercase().padStart(4, '0')
        return executeAdbCommand(
            "-s", device, "shell",
            "cat", "/proc/net/tcp", "/proc/net/tcp6"
        ).thenApply { output ->
            output.lines().any { line ->
                val cols = line.trim().split("\\s+".toRegex())
                cols.size >= 4 && cols[1].endsWith(":$hexPort") && cols[3] == "0A"
            }
        }.exceptionally { false }
    }

    /**
     * 执行 adb 命令
     *
     * 通过 [AdbHelper] 解析 adb 绝对路径,避免依赖 IDE 进程的 PATH。
     * Android Studio 从 macOS Finder/Dock 启动时 PATH 只有 launchd 的默认值,
     * 不会加载 ~/.zshrc 里的 ANDROID_HOME export,裸 `adb` 直接 `IOException`。
     */
    private val adbPath: String by lazy { AdbHelper.resolveAdbPath(project) }

    private fun executeAdbCommand(vararg args: String): CompletableFuture<String> {
        return CompletableFuture.supplyAsync {
            val command = listOf(adbPath) + args
            logger.debug("Executing: ${command.joinToString(" ")}")

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }

            // waitFor(timeout) 超时不抛异常,只返回 false;若仍未结束必须 destroy,
            // 否则后面 exitValue() 会抛 IllegalThreadStateException,被外层吞掉变成"假失败"。
            // adb start-server 冷启动可能 >3s,给 10s 兜底。
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw RuntimeException("Adb command timeout (>10s): ${command.joinToString(" ")}")
            }

            if (process.exitValue() != 0) {
                throw RuntimeException("Adb command failed: $output")
            }

            output
        }
    }

    /**
     * 查找可用端口
     */
    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { socket ->
            socket.localPort
        }
    }

    /**
     * 清理 adb forward
     */
    fun cleanupForward(localPort: Int): CompletableFuture<Boolean> {
        return executeAdbCommand("forward", "--remove", "tcp:$localPort")
            .thenApply { true }
            .exceptionally { false }
    }
}
