package com.alipay.devtools.mockdata

import com.intellij.openapi.diagnostic.Logger
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * MockData WebSocket 客户端
 * 使用 WebSocket 协议连接 MockData 服务（端口 2121）
 */
class MockDataWebSocketClient(
    private val host: String,
    private val port: Int
) {
    private val logger = Logger.getInstance(MockDataWebSocketClient::class.java)
    private var webSocketClient: WebSocketClient? = null
    private val messageHandlers = mutableListOf<(MockDataMessage) -> Unit>()
    private val connectionListeners = mutableListOf<(Boolean) -> Unit>()
    private val pendingResponses = ConcurrentHashMap<String, CompletableFuture<String>>()
    private var pingTimer: java.util.Timer? = null

    @Volatile
    private var isConnected = false

    /**
     * 连接到 WebSocket 服务
     * @param timeoutMs 连接超时(ms);自动连接心跳每 2s tick 一次,这里默认 3s 即可
     */
    fun connect(path: String = "/service", timeoutMs: Long = 3000): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        try {
            val uri = URI("ws://$host:$port$path")
            logger.info("Connecting to WebSocket: $uri (timeout=${timeoutMs}ms)")

            webSocketClient = object : WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake?) {
                    logger.info("WebSocket connected to $host:$port, handshake: $handshake")
                    isConnected = true
                    notifyConnectionState(true)
                    if (!future.isDone) {
                        future.complete(true)
                    }
                    // 禁用自动 ping，让服务端控制连接
                    // startPingTimer()
                }

                override fun onMessage(message: String) {
                    logger.debug("Received message: $message")
                    handleMessage(message)
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    val codeMeaning = when (code) {
                        1000 -> "Normal closure"
                        1001 -> "Going away"
                        1002 -> "Protocol error"
                        1003 -> "Unsupported data"
                        1005 -> "No status received"
                        1006 -> "Abnormal closure"
                        1008 -> "Policy violation"
                        1009 -> "Message too big"
                        1010 -> "Mandatory extension"
                        1011 -> "Server error"
                        1015 -> "TLS handshake failed"
                        else -> "Unknown code"
                    }
                    logger.warn("WebSocket closed: $reason (code: $code - $codeMeaning, remote: $remote)")
                    isConnected = false
                    notifyConnectionState(false)
                }

                override fun onError(ex: Exception) {
                    logger.error("WebSocket error", ex)
                    isConnected = false
                    notifyConnectionState(false)
                    if (!future.isDone) {
                        future.completeExceptionally(ex)
                    }
                }
            }

            webSocketClient?.connect()

            // 连接超时:到点未完成就显式 fail + close,避免 future 永久挂起
            Thread {
                Thread.sleep(timeoutMs)
                if (!future.isDone) {
                    future.completeExceptionally(RuntimeException("Connection timeout after ${timeoutMs}ms"))
                    webSocketClient?.close()
                }
            }.start()

        } catch (e: Exception) {
            logger.error("Failed to create WebSocket client", e)
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        isConnected = false
        stopPingTimer()
        webSocketClient?.close()
        webSocketClient = null
        notifyConnectionState(false)
        logger.info("Disconnected from WebSocket")
    }

    /**
     * 启动定时ping
     */
    private fun startPingTimer() {
        stopPingTimer()
        pingTimer = java.util.Timer("WebSocketPing").apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    if (isConnected && webSocketClient?.isOpen == true) {
                        try {
                            webSocketClient?.sendPing()
                            logger.debug("Sent ping to keep connection alive")
                        } catch (e: Exception) {
                            logger.warn("Failed to send ping: ${e.message}")
                        }
                    }
                }
            }, 30000, 30000) // 每30秒ping一次
        }
    }

    /**
     * 停止定时ping
     */
    private fun stopPingTimer() {
        pingTimer?.cancel()
        pingTimer = null
    }

    /**
     * 发送消息
     */
    fun send(message: String): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        logger.debug("Attempting to send message, isConnected=$isConnected, isOpen=${webSocketClient?.isOpen}")

        if (!isConnected || webSocketClient?.isOpen != true) {
            logger.warn("Cannot send message: not connected (isConnected=$isConnected, isOpen=${webSocketClient?.isOpen})")
            future.completeExceptionally(IllegalStateException("Not connected"))
            return future
        }

        try {
            webSocketClient?.send(message)
            logger.debug("Sent message: $message")
            future.complete(true)
        } catch (e: Exception) {
            logger.error("Failed to send message", e)
            future.completeExceptionally(e)
        }

        return future
    }

    /**
     * 处理收到的消息
     */
    private fun handleMessage(json: String) {
        try {
            val message = MockDataMessage.fromJson(json)
            logger.debug("Parsed message: actionType=${message.actionType}, method=${message.method}, biz=${message.biz}")
            messageHandlers.forEach { it.invoke(message) }
        } catch (e: Exception) {
            logger.warn("Failed to parse message: $json, error: ${e.message}")
            // 不要抛出异常，避免关闭连接
            // 创建一个原始消息对象传递给处理器
            try {
                val rawMessage = MockDataMessage(
                    mode = "Normal",
                    actionType = "raw",
                    data = com.google.gson.JsonObject().apply {
                        addProperty("raw", json)
                    }
                )
                messageHandlers.forEach { it.invoke(rawMessage) }
            } catch (ignored: Exception) {
                // 忽略
            }
        }
    }

    /**
     * 添加消息处理器
     */
    fun addMessageHandler(handler: (MockDataMessage) -> Unit) {
        messageHandlers.add(handler)
    }

    /**
     * 移除消息处理器
     */
    fun removeMessageHandler(handler: (MockDataMessage) -> Unit) {
        messageHandlers.remove(handler)
    }

    /**
     * 添加连接状态监听器
     */
    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener)
    }

    /**
     * 通知连接状态变化
     */
    private fun notifyConnectionState(connected: Boolean) {
        connectionListeners.forEach { it.invoke(connected) }
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean = isConnected && webSocketClient?.isOpen == true
}
