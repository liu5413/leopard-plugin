package com.alipay.devtools.mockdata

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/**
 * 连接配置
 */
data class ConnectionConfig(
    val host: String = "localhost",
    val port: Int = 0,
    val isAutoConnect: Boolean = false,
    val packageName: String? = null
)

/**
 * MockData 服务管理类
 * 提供对 MockData 所有能力的封装
 */
@Service(Service.Level.PROJECT)
class MockDataService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(MockDataService::class.java)
    private var client: MockDataWebSocketClient? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableFuture<MockDataMessage>>()
    private val deviceInfoListeners = mutableListOf<(DeviceInfo) -> Unit>()
    private val userInfoListeners = mutableListOf<(UserInfo) -> Unit>()
    // 监听 actionType=autoLaunch 的推送（环境切换 / 账号切换的最终结果）
    // 回调参数: success, reason
    private val autoLaunchListeners = mutableListOf<(Boolean, String?) -> Unit>()
    private val autoConnect = MockDataAutoConnect(project)
    private var currentConnectionConfig: ConnectionConfig? = null
    private var currentMappedPort: Int? = null
    private var currentEnv: String? = null

    // ========== 自动重连心跳 ==========
    @Volatile
    private var autoConnectMonitor: java.util.concurrent.ScheduledFuture<*>? = null
    private val autoConnectInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    // 自动重连开关：UI 上点击 [🚀 自动连接设备] 时置 true,点击 [断开] 时置 false。
    // 心跳 tick 会读这个标记决定是否真的发起 autoConnect,避免用户主动断开后又被心跳立刻拉回来。
    @Volatile
    var autoReconnectEnabled: Boolean = false

    companion object {
        const val WEBSOCKET_PORT = 2121  // MockData WebSocket 默认端口

        @JvmStatic
        fun getInstance(project: Project): MockDataService = project.service()
    }

    /**
     * 自动连接设备
     * @param packageName 可选，指定应用包名
     */
    fun autoConnect(packageName: String? = null): CompletableFuture<Boolean> {
        val config = MockDataAutoConnect.AutoConnectConfig(
            packageName = packageName,
            preferWebSocket = true,  // 使用 WebSocket 端口 2121
            localPort = 0
        )

        return autoConnect.autoConnect(config).thenCompose { result ->
            if (result.success) {
                currentMappedPort = result.localPort
                currentConnectionConfig = ConnectionConfig(
                    host = "localhost",
                    port = result.localPort,
                    isAutoConnect = true,
                    packageName = result.packageName
                )

                // 连接到本地映射的 WebSocket 端口，保留配置
                connect("localhost", result.localPort, preserveConfig = true).thenApply { connected ->
                    if (connected) {
                        logger.info("Auto-connected to ${result.packageName} via WebSocket port ${result.localPort}")
                    } else {
                        // 连接失败，清空配置
                        currentConnectionConfig = null
                        currentMappedPort = null
                    }
                    connected
                }
            } else {
                CompletableFuture.completedFuture(false)
            }
        }.exceptionally { error ->
            logger.warn("Auto-connect failed: ${error.message}")
            false
        }
    }

    /**
     * 获取当前连接配置
     */
    fun getConnectionConfig(): ConnectionConfig? = currentConnectionConfig

    /**
     * 获取当前连接的应用包名
     */
    fun getConnectedPackageName(): String? = currentConnectionConfig?.packageName

    /**
     * 获取当前环境
     */
    fun getCurrentEnv(): String? = currentEnv

    /**
     * 设置当前环境
     */
    fun setCurrentEnv(env: String) {
        currentEnv = env
    }

    /**
     * 连接到设备
     */
    fun connect(host: String, port: Int, preserveConfig: Boolean = false): CompletableFuture<Boolean> {
        // 如果 preserveConfig 为 true，保存当前配置
        val savedConfig = if (preserveConfig) currentConnectionConfig else null
        val savedMappedPort = if (preserveConfig) currentMappedPort else null

        // 断开现有连接，但不清理 forward（如果 preserveConfig 为 true）
        disconnect(cleanupForward = !preserveConfig)

        // 恢复配置
        if (preserveConfig) {
            currentConnectionConfig = savedConfig
            currentMappedPort = savedMappedPort
        } else {
            currentConnectionConfig = ConnectionConfig(
                host = host,
                port = port,
                isAutoConnect = false,
                packageName = null
            )
        }

        // 端侧 WsAntManServer 硬编码路径 /service(见 mockdata-build WsAntManServer.java:163
        // `new WebSocketServerProtocolHandler("/service", ...)`),不必再串行试 / 和 /mockdata。
        // 串行试 3 个 path × 单 path 超时,会把心跳 in-flight 锁住,UI 表现为"一直转不报错"。
        return tryConnectWithPath(host, port, "/service")
            .thenApply { connected ->
                if (connected) {
                    // 连接成功后立即发送 deviceInfo 进行验证(5秒内必须完成)
                    logger.info("Connection established, sending device info for verification...")
                    client?.send(MockDataMessage.deviceInfo().toJson())
                }
                connected
            }
    }

    /**
     * 用指定路径建立 WebSocket。切换 client 前会 close 旧的,避免旧 client 在后台继续重连泄漏。
     */
    private fun tryConnectWithPath(host: String, port: Int, path: String): CompletableFuture<Boolean> {
        // 先把上一个 client 关掉,避免上一次 connect 还没超时就被新 client 覆盖,
        // 旧 client 继续 onError → notifyConnectionState(false) 干扰新连接状态判断。
        client?.disconnect()
        client = MockDataWebSocketClient(host, port).apply {
            addMessageHandler { handleMessage(it) }
            addConnectionListener { connected ->
                logger.info("Connection state changed: $connected")
            }
        }
        return client!!.connect(path).exceptionally { error ->
            logger.warn("Failed to connect with path $path: ${error.message}")
            false
        }
    }

    /**
     * 断开连接
     * @param cleanupForward 是否清理 adb forward，自动连接时保持转发
     */
    fun disconnect(cleanupForward: Boolean = true) {
        // 清理 adb forward（可选）
        if (cleanupForward) {
            currentMappedPort?.let { port ->
                autoConnect.cleanupForward(port)
            }
            currentMappedPort = null
            currentConnectionConfig = null
            currentEnv = null
        }

        client?.disconnect()
        client = null
        pendingRequests.clear()
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean = client?.isConnected() == true

    /**
     * 发送消息并等待响应
     */
    private fun sendAndWait(message: MockDataMessage, timeoutMs: Long = 2000): CompletableFuture<MockDataMessage> {
        val future = CompletableFuture<MockDataMessage>()

        if (!isConnected()) {
            future.completeExceptionally(IllegalStateException("Not connected to device"))
            return future
        }

        // 从消息数据中提取 requestKey，如果不存在则生成新的
        val requestKey = if (message.data is JsonObject) {
            message.data.get("requestKey")?.asString ?: System.currentTimeMillis().toString()
        } else {
            System.currentTimeMillis().toString()
        }

        // 存储等待的请求
        pendingRequests[requestKey] = future

        // 设置超时
        CompletableFuture.delayedExecutor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .execute {
                pendingRequests.remove(requestKey)?.completeExceptionally(
                    java.util.concurrent.TimeoutException("Request timeout")
                )
            }

        // 发送消息
        client?.send(message.toJson())?.exceptionally { error ->
            pendingRequests.remove(requestKey)?.completeExceptionally(error)
            null
        }

        return future
    }

    /**
     * 处理收到的消息
     */
    private fun handleMessage(message: MockDataMessage) {
        try {
            logger.info("Received message: actionType=${message.actionType}, method=${message.method}, biz=${message.biz}")

            // 处理设备信息响应
            when {
                message.method == "deviceInfo" -> {
                    message.data?.let {
                        val deviceInfo = Gson().fromJson(it, DeviceInfo::class.java)
                        // 用 toList() 拍快照，避免 listener 内部移除自身时引发 ConcurrentModificationException
                        deviceInfoListeners.toList().forEach { listener -> listener(deviceInfo) }
                    }
                }
                message.actionType == "autoLaunch" -> {
                    // 环境切换 / 账号切换的最终结果。设备端 Env.sendToAntMan 推送：
                    // data { success: 1|0, reason: "...", bundleIdentifier, isSimulator }
                    // 成功后设备进程会被 kill 重启，WebSocket 紧随其后断开。
                    val dataObj = message.data?.asJsonObject
                    val success = dataObj?.get("success")?.asInt == 1
                    val reason = dataObj?.get("reason")?.asString
                    logger.info("Received autoLaunch result: success=$success, reason=$reason")
                    autoLaunchListeners.toList().forEach { listener -> listener(success, reason) }
                }
                message.actionType == "currentUserInfo" || message.actionType == "autoLogin" -> {
                    message.data?.let {
                        val userInfo = Gson().fromJson(it, UserInfo::class.java)
                        userInfoListeners.toList().forEach { listener -> listener(userInfo) }
                    }
                    // 也检查是否有 requestKey
                    val dataObj = message.data?.asJsonObject
                    dataObj?.get("requestKey")?.asString?.let { key ->
                        pendingRequests.remove(key)?.complete(message)
                    }
                }
                message.actionType == "screenShot" -> {
                    // 处理截图响应
                    val dataObj = message.data?.asJsonObject
                    val requestKey = dataObj?.get("requestKey")?.asString
                    if (requestKey != null) {
                        pendingRequests.remove(requestKey)?.complete(message)
                    }
                }
                message.method == "config" || message.biz != null -> {
                    // 收到配置消息，记录但不回复（避免无限循环）
                    logger.info("Received config/biz message: biz=${message.biz}, ignoring")
                }
            }

            // 处理其他响应
            val dataObj = if (message.data is JsonObject) message.data else null
            dataObj?.get("requestKey")?.asString?.let { key ->
                pendingRequests.remove(key)?.complete(message)
            }
        } catch (e: Exception) {
            logger.warn("Error handling message: ${e.message}")
            // 不要抛出异常，避免关闭连接
        }
    }

    // ==================== 环境切换 ====================

    /**
     * 切换环境
     * @param env 环境名称: online, pre, test, dev, sim
     * @param loginId 可选，登录账号
     * @param password 可选，登录密码
     */
    fun switchEnv(env: String, loginId: String? = null, password: String? = null): CompletableFuture<Boolean> {
        val activeClient = client
            ?: return CompletableFuture.failedFuture(IllegalStateException("Not connected"))

        // 协议真相（来源：mockdata 设备端 Env.java + AntManServer.java case 30）：
        //   - 设备执行切换后，无论成功失败，都会推送 actionType=autoLaunch + data{success, reason}。
        //   - 切换成功后设备进程会被 kill 重启，WebSocket 紧随其后断开。
        //   - 例外：env 与当前一致且 action=switchUser，设备只走 LoginHelper.login，不发 autoLaunch；
        //          这种情况下我们依赖超时兜底判失败，调用方应据此自行决定如何重试 / 仅登录。
        //
        // 策略：注册一次性 autoLaunch 监听 → 命中即终结；超时 8s 兜底。
        val result = CompletableFuture<Boolean>()
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val listenerRef = java.util.concurrent.atomic.AtomicReference<((Boolean, String?) -> Unit)?>()

        fun finish(success: Boolean, reason: String? = null) {
            if (!completed.compareAndSet(false, true)) return
            listenerRef.getAndSet(null)?.let { removeAutoLaunchListener(it) }
            if (success) {
                currentEnv = env
                logger.info("switchEnv succeeded: env=$env" + (reason?.let { ", reason=$it" } ?: ""))
            } else {
                logger.warn("switchEnv failed: env=$env, reason=${reason ?: "<none>"}")
            }
            result.complete(success)
        }

        val listener: (Boolean, String?) -> Unit = { success, reason -> finish(success, reason) }
        listenerRef.set(listener)
        addAutoLaunchListener(listener)

        // 超时兜底：8s 内未收到 autoLaunch（设备走了 LoginHelper.login 路径不发推送、或网络异常）
        val timeoutMs = 8000L
        CompletableFuture.delayedExecutor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .execute {
                if (!completed.get()) finish(false, "切换超时,设备未返回 autoLaunch 结果")
            }

        // 发送切换消息；send 失败立即终结
        activeClient.send(MockDataMessage.switchEnv(env, loginId, password).toJson())
            .whenComplete { sent, sendErr ->
                when {
                    sendErr != null -> finish(false, "发送失败: ${sendErr.message}")
                    sent != true -> finish(false, "发送返回 false")
                    // sent == true：保留监听，等设备 autoLaunch 推送 / 超时
                }
            }

        return result
    }

    /**
     * 查询登录状态
     */
    fun queryLoginStatus(): CompletableFuture<UserInfo> {
        val requestKey = "login_${System.currentTimeMillis()}"
        val future = CompletableFuture<UserInfo>()

        if (!isConnected()) {
            future.completeExceptionally(IllegalStateException("Not connected to device"))
            return future
        }

        logger.info("Querying login status, connected=${isConnected()}")

        // 创建带 requestKey 的查询消息
        val message = MockDataMessage(
            actionType = "autoLogin",
            actionSubType = "queryLoginStatus",
            data = JsonObject().apply { addProperty("requestKey", requestKey) }
        )

        sendAndWait(message, 1000)
            .thenAccept { response ->
                logger.info("Received login status response: ${response.toJson()}")
                // 从响应中提取用户信息
                val dataObj = response.data?.asJsonObject
                if (dataObj != null) {
                    val env = dataObj.get("env")?.asString
                    currentEnv = env
                    val userInfo = UserInfo(
                        loginId = dataObj.get("loginId")?.asString,
                        userId = dataObj.get("userId")?.asString,
                        env = env,
                        password = dataObj.get("password")?.asString
                    )
                    future.complete(userInfo)
                } else {
                    future.completeExceptionally(IllegalStateException("No user data in response"))
                }
            }
            .exceptionally { error ->
                future.completeExceptionally(error)
                null
            }

        return future
    }

    /**
     * 切换 SOFA 分组
     */
    fun switchSofaGroup(groupName: String): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.switchSofaGroup(groupName).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * 请求设备信息
     */
    fun requestDeviceInfo(): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.deviceInfo().toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    // ==================== Mock 能力 ====================

    /**
     * Mock RPC 响应
     */
    fun mockRpc(operationType: String, response: JsonObject): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.rpcMock(operationType, response).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * Mock HTTP 响应
     */
    fun mockHttp(requestId: String, response: JsonObject): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.httpMock(requestId, response).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * Mock LBS 位置
     */
    fun mockLbs(latitude: Double, longitude: Double, address: String? = null): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.lbsMock(latitude, longitude, address).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * Mock JSAPI 响应
     */
    fun mockJsapi(apiName: String, response: JsonObject): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.jsapiMock(apiName, response).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * Mock 配置项
     */
    fun mockConfig(key: String, value: String): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.configMock(key, value).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    // ==================== 设备控制 ====================

    /**
     * 截图
     */
    fun screenshot(): CompletableFuture<String> {
        val requestKey = "screenshot_${System.currentTimeMillis()}"
        val future = CompletableFuture<String>()

        sendAndWait(MockDataMessage.screenshot(requestKey), 3000)
            .thenAccept { response ->
                val dataObj = response.data?.asJsonObject
                val base64Data = dataObj?.get("data")?.asString
                if (base64Data != null) {
                    future.complete(base64Data)
                } else {
                    future.completeExceptionally(IllegalStateException("No screenshot data"))
                }
            }
            .exceptionally { error ->
                future.completeExceptionally(error)
                null
            }

        return future
    }

    /**
     * 读取剪贴板
     */
    fun readClipboard(): CompletableFuture<String> {
        val future = CompletableFuture<String>()

        client?.send(MockDataMessage.readClipboard().toJson())
            ?.thenAccept {
                // 等待剪贴板内容回调
                // 这里简化处理，实际应该添加专门的监听器
                future.complete("")
            }
            ?.exceptionally { error ->
                future.completeExceptionally(error)
                null
            }

        return future
    }

    /**
     * 写入剪贴板
     */
    fun writeClipboard(text: String): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.writeClipboard(text).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * 路由跳转
     */
    fun navigateTo(appId: String, params: JsonObject? = null): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.navigateTo(appId, params).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * 重启应用
     */
    fun restartApp(): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.suicide().toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * 清除应用数据
     */
    fun clearAppData(): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.clearData().toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    // ==================== 文件操作 ====================

    /**
     * 列出目录文件
     */
    fun listFiles(path: String): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.listFiles(path).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * 读取文件
     */
    fun readFile(vararg paths: String): CompletableFuture<String> {
        val requestKey = "file_${System.currentTimeMillis()}"

        return sendAndWait(MockDataMessage.readFile(requestKey, *paths), 3000)
            .thenApply { response ->
                response.data?.toString() ?: ""
            }
    }

    // ==================== 其他功能 ====================

    /**
     * 切换语言
     */
    fun switchLanguage(language: String): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.switchLanguage(language).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * 刷新 VoiceOver
     */
    fun refreshVoiceOver(): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.refreshVoiceOver().toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * 启用/禁用 Hook
     */
    fun setHookEnabled(enable: Boolean): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.hookConfig(enable).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * 发送 RPC
     */
    fun sendRpc(
        operationType: String,
        requestClass: String,
        bundleName: String,
        params: Any,
        headers: Map<String, String>? = null
    ): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.sendRpc(operationType, requestClass, bundleName, params, headers).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    /**
     * 发送广播
     * @param action 广播 action 名称
     * @param data 广播参数，可选
     */
    fun sendBroadcast(action: String, data: JsonObject? = null): CompletableFuture<Boolean> {
        return client?.send(MockDataMessage.sendBroadcast(action, data).toJson())
            ?: CompletableFuture.failedFuture(IllegalStateException("Not connected"))
    }

    // ==================== 监听器 ====================

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        client?.addConnectionListener(listener)
    }

    fun addDeviceInfoListener(listener: (DeviceInfo) -> Unit) {
        deviceInfoListeners.add(listener)
    }

    fun removeDeviceInfoListener(listener: (DeviceInfo) -> Unit) {
        deviceInfoListeners.remove(listener)
    }

    fun addUserInfoListener(listener: (UserInfo) -> Unit) {
        userInfoListeners.add(listener)
    }

    fun removeUserInfoListener(listener: (UserInfo) -> Unit) {
        userInfoListeners.remove(listener)
    }

    fun addAutoLaunchListener(listener: (Boolean, String?) -> Unit) {
        autoLaunchListeners.add(listener)
    }

    fun removeAutoLaunchListener(listener: (Boolean, String?) -> Unit) {
        autoLaunchListeners.remove(listener)
    }

    /**
     * 启动自动连接心跳：每 [intervalSeconds] 秒检查一次连接状态，未连接则尝试自动连接（包名自动检测）。
     * 同一 service 实例内幂等，重复调用只会保留首次启动的心跳。
     */
    fun startAutoConnectMonitor(intervalSeconds: Long = 2) {
        if (autoConnectMonitor != null) return
        synchronized(this) {
            if (autoConnectMonitor != null) return
            val executor = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
            autoConnectMonitor = executor.scheduleWithFixedDelay({
                try {
                    if (project.isDisposed) {
                        stopAutoConnectMonitor()
                        return@scheduleWithFixedDelay
                    }
                    if (isConnected()) return@scheduleWithFixedDelay
                    // 用户主动断开过(autoReconnectEnabled=false)就别再自动拉回来
                    if (!autoReconnectEnabled) return@scheduleWithFixedDelay
                    // 防止上一轮 autoConnect 还没结束，下一轮又叠加触发
                    if (!autoConnectInFlight.compareAndSet(false, true)) return@scheduleWithFixedDelay
                    autoConnect(null).whenComplete { success, err ->
                        autoConnectInFlight.set(false)
                        if (err != null) {
                            logger.warn("auto-reconnect failed: ${err.message}")
                        } else if (success != true) {
                            logger.info("auto-reconnect tick: still not connected, will retry next tick")
                        } else {
                            logger.info("auto-reconnect tick: connected to ${getConnectionConfig()?.packageName}")
                        }
                    }
                } catch (ex: Exception) {
                    autoConnectInFlight.set(false)
                    logger.warn("auto-reconnect monitor tick threw: ${ex.message}")
                }
            }, 0, intervalSeconds, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /**
     * 停止自动连接心跳。idempotent。
     */
    fun stopAutoConnectMonitor() {
        autoConnectMonitor?.cancel(false)
        autoConnectMonitor = null
    }

    override fun dispose() {
        stopAutoConnectMonitor()
        disconnect()
    }
}
