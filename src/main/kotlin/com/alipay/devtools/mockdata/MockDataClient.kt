package com.alipay.devtools.mockdata

import com.intellij.openapi.diagnostic.Logger
import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.util.CharsetUtil
import java.util.concurrent.CompletableFuture

/**
 * MockData Socket 客户端
 * 用于连接 Android 设备上的 MockData 服务
 */
class MockDataClient(
    private val host: String,
    private val port: Int
) {
    private val logger = Logger.getInstance(MockDataClient::class.java)
    private var channel: Channel? = null
    private var eventLoopGroup: EventLoopGroup? = null
    private val messageHandlers = mutableListOf<(MockDataMessage) -> Unit>()
    private val connectionListeners = mutableListOf<(Boolean) -> Unit>()

    /**
     * 连接到设备
     */
    fun connect(): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        eventLoopGroup = NioEventLoopGroup()

        val bootstrap = Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().apply {
                        addLast(StringDecoder(CharsetUtil.UTF_8))
                        addLast(StringEncoder(CharsetUtil.UTF_8))
                        addLast(MockDataClientHandler(this@MockDataClient))
                    }
                }
            })

        bootstrap.connect(host, port).addListener { channelFuture ->
            if (channelFuture.isSuccess) {
                channel = (channelFuture as ChannelFuture).channel()
                logger.info("Connected to MockData server at $host:$port")
                notifyConnectionState(true)
                future.complete(true)
            } else {
                logger.error("Failed to connect to MockData server", channelFuture.cause())
                notifyConnectionState(false)
                future.completeExceptionally(channelFuture.cause())
            }
        }

        return future
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        channel?.close()?.syncUninterruptibly()
        eventLoopGroup?.shutdownGracefully()
        notifyConnectionState(false)
        logger.info("Disconnected from MockData server")
    }

    /**
     * 发送消息到设备
     */
    fun send(message: MockDataMessage): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val channel = this.channel

        if (channel == null || !channel.isActive) {
            future.completeExceptionally(IllegalStateException("Not connected"))
            return future
        }

        val json = message.toJson()
        channel.writeAndFlush(json).addListener { writeFuture ->
            if (writeFuture.isSuccess) {
                logger.debug("Sent message: $json")
                future.complete(true)
            } else {
                logger.error("Failed to send message", writeFuture.cause())
                future.completeExceptionally(writeFuture.cause())
            }
        }

        return future
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
     * 处理收到的消息
     */
    internal fun handleMessage(json: String) {
        try {
            val message = MockDataMessage.fromJson(json)
            messageHandlers.forEach { it.invoke(message) }
        } catch (e: Exception) {
            logger.error("Failed to parse message: $json", e)
        }
    }

    private fun notifyConnectionState(connected: Boolean) {
        connectionListeners.forEach { it.invoke(connected) }
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean = channel?.isActive == true
}

/**
 * Netty 处理器
 */
private class MockDataClientHandler(private val client: MockDataClient) : SimpleChannelInboundHandler<String>() {

    override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
        client.handleMessage(msg)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        ctx.close()
    }
}
