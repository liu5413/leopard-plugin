package com.alipay.devtools.mockdata

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Image
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.*

/**
 * MockData 工具窗口
 */
class MockDataToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MockDataPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * MockData 主面板
 */
class MockDataPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = MockDataService.getInstance(project)
    private val logArea = JTextArea(20, 50).apply {
        isEditable = false
    }

    init {
        val tabbedPane = JBTabbedPane()

        // 环境切换面板
//        tabbedPane.addTab("环境", createEnvPanel())

        // 伙伴打包面板
        tabbedPane.addTab("伙伴", HuobanPanel(project))

        // 连接面板
        tabbedPane.addTab("连接", createConnectionPanel())

        // Mock 面板
//        tabbedPane.addTab("Mock", createMockPanel())

        // 设备控制面板
//        tabbedPane.addTab("设备", createDevicePanel())

        // 文件面板
//        tabbedPane.addTab("文件", createFilePanel())

        val logScrollPane = JScrollPane(logArea)
        add(tabbedPane, BorderLayout.CENTER)
        add(logScrollPane, BorderLayout.SOUTH)

        // "伙伴" tab (index 0) 有自己的日志区域，隐藏父级 logArea
        tabbedPane.addChangeListener {
            logScrollPane.isVisible = tabbedPane.selectedIndex != 0
            revalidate()
        }
        // 初始状态也检查一次
        logScrollPane.isVisible = tabbedPane.selectedIndex != 0
    }

    private fun createConnectionPanel(): JPanel {
        // 外层 BorderLayout,把 BoxLayout 内容塞到 NORTH。
        // 直接用 BoxLayout.Y_AXIS 时,FlowLayout 子面板的 maximumSize 是 Short.MAX_VALUE,
        // BoxLayout 会把纵向空白按 max-pref 分给它们,autoPanel 被拉得很高,按钮顶在面板上沿,
        // 视觉上离 statusLabel 隔了一大段空白。NORTH 只给 preferredHeight,内层不会再被拉伸。
        val outer = JPanel(BorderLayout())
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // 延迟查询用户信息的回调，在 userInfoPanel/renderUserInfo 定义后赋值
        var refreshUserInfo: (() -> Unit)? = null
        // 直接用已有 UserInfo 刷新 UI 的回调
        var applyUserInfo: ((UserInfo) -> Unit)? = null
        // 标记是否已通过推送或主动查询拿到用户信息（避免 poll 覆盖）
        var hasReceivedUserInfo = false
        // 自动登录进行中时,禁止 poll 覆盖 UI
        var autoLoginInProgress = false
        // 设备端推送登录成功时提前结束自动登录
        var pendingAutoLoginSuccess: ((UserInfo) -> Unit)? = null

        // 自动连接区域
        val autoPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val autoConnectBtn = JButton("🚀 自动连接设备")
        autoConnectBtn.toolTipText = "自动检测并连接设备上的 MockData 服务"

        val packageCombo = ComboBox<String>().apply {
            isEditable = true
            toolTipText = "可选：指定应用包名"
            preferredSize = Dimension(250, preferredSize.height)
        }
        // 添加常用应用
        MockDataAutoConnect.APP_PORTS.keys.forEach { packageCombo.addItem(it) }
        packageCombo.addItem("") // 空选项表示自动检测

        autoConnectBtn.addActionListener {
            autoConnectBtn.isEnabled = false
            autoConnectBtn.text = "连接中..."
            // 用户主动点 [自动连接],打开自动重连开关,后续心跳会一直尝试拉起连接
            service.autoReconnectEnabled = true

            val selectedPackage = packageCombo.selectedItem as? String
            val packageName = selectedPackage?.takeIf { it.isNotEmpty() }

            service.autoConnect(packageName).thenAccept { success: Boolean ->
                SwingUtilities.invokeLater {
                    autoConnectBtn.isEnabled = true
                    autoConnectBtn.text = "🚀 自动连接设备"

                    if (success) {
                        val config = service.getConnectionConfig()
                        log("✅ 已连接 ${config?.packageName} via port ${config?.port}")
                        // 请求设备信息
                        service.requestDeviceInfo()
                    } else {
                        log("❌ Auto-connect failed. Make sure:")
                        log("   1. Device is connected via USB/WiFi")
                        log("   2. App with MockData is running")
                        log("   3. adb is available in PATH")
                    }
                }
            }
        }

        autoPanel.add(autoConnectBtn)
//        autoPanel.add(JLabel("或指定应用:"))
//        autoPanel.add(packageCombo)


        // 手动连接区域:hostField/portField/connectBtn 都被注释了,disconnectBtn 也没接到 UI。
        // 这里只留一个 disconnect 行为对象供后续可能复用,不再把空的 manualPanel 加进 BoxLayout
        // —— 空面板被 BoxLayout 当作占一行,会再次撑大整体高度。
        val disconnectBtn = JButton("断开")
        disconnectBtn.addActionListener {
            // 用户主动断开,关闭自动重连开关,否则心跳会立刻把连接拉回来
            service.autoReconnectEnabled = false
            service.disconnect()
            log("Disconnected")
        }
        autoPanel.add(disconnectBtn)
        panel.add(autoPanel)

        // 踢登按钮(独立一行)
        val forceLogoutPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val forceLogoutBtn = JButton("踢登")
        forceLogoutBtn.toolTipText = "强制登出当前用户,1.1.20及版本以上"
        forceLogoutBtn.addActionListener {
            if (!service.isConnected()) {
                log("❌ 踢登失败: 当前未连接设备")
                return@addActionListener
            }
            service.sendBroadcast("LP_FORCE_LOGOUT", null).thenAccept { success: Boolean ->
                log("${if (success) "✅" else "❌"} 踢登 ${if (success) "成功" else "失败"}")
                if (success) refreshUserInfo?.invoke()
            }
        }
        forceLogoutPanel.add(forceLogoutBtn)

        val logoutBtn = JButton("退登")
        logoutBtn.toolTipText = "正常退出登录,1.1.80及版本以上"
        logoutBtn.addActionListener {
            if (!service.isConnected()) {
                log("❌ 退登失败: 当前未连接设备")
                return@addActionListener
            }
            service.sendBroadcast("LP_LOGOUT", null).thenAccept { success: Boolean ->
                log("${if (success) "✅" else "❌"} 退登 ${if (success) "成功" else "失败"}")
                if (success) refreshUserInfo?.invoke()
            }
        }
        forceLogoutPanel.add(logoutBtn)

        panel.add(forceLogoutPanel)

        // 自动登录区域
        val autoLoginPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        val phoneHistoryKey = "mockdata.phone.history"
        val phoneHistory = (props.getValue(phoneHistoryKey) ?: "")
            .split(",").filter { it.matches(Regex("^1\\d{10}$")) }.toMutableList()
        val phoneCombo = ComboBox(DefaultComboBoxModel(phoneHistory.toTypedArray())).apply {
            isEditable = true
            toolTipText = "输入11位手机号"
            preferredSize = Dimension(160, preferredSize.height)
        }
        val autoLoginBtn = JButton("自动登录")
        autoLoginBtn.toolTipText = "自动切换到预发环境并登录,1.1.80及版本以上"
        autoLoginBtn.addActionListener {
            if (!service.isConnected()) {
                log("❌ 自动登录失败: 当前未连接设备")
                return@addActionListener
            }
            val phone = (phoneCombo.editor.item as? String)?.trim() ?: ""
            if (!phone.matches(Regex("^1\\d{10}$"))) {
                log("❌ 请输入正确的11位手机号")
                return@addActionListener
            }
            // 记录历史
            if (phone !in phoneHistory) {
                phoneHistory.add(0, phone)
                (phoneCombo.model as DefaultComboBoxModel<String>).insertElementAt(phone, 0)
                props.setValue(phoneHistoryKey, phoneHistory.take(10).joinToString(","))
            }

            autoLoginBtn.isEnabled = false
            autoLoginBtn.text = "登录中..."
            autoLoginInProgress = true

            val autoLoginEnded = java.util.concurrent.atomic.AtomicBoolean(false)
            var reconnectListener: ((DeviceInfo) -> Unit)? = null

            fun endAutoLogin(message: String? = null, userInfo: UserInfo? = null) {
                if (!autoLoginEnded.compareAndSet(false, true)) return
                pendingAutoLoginSuccess = null
                reconnectListener?.let { service.removeDeviceInfoListener(it) }
                reconnectListener = null
                SwingUtilities.invokeLater {
                    autoLoginBtn.isEnabled = true
                    autoLoginBtn.text = "自动登录"
                    autoLoginInProgress = false
                    message?.let { log(it) }
                    userInfo?.let { applyUserInfo?.invoke(it) }
                }
            }

            pendingAutoLoginSuccess = { userInfo ->
                endAutoLogin("✅ 登录成功: ${userInfo.loginId}", userInfo)
            }

            // 整体超时兜底, 防止任意异步分支遗漏导致按钮永久卡在「登录中...」
            CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute {
                endAutoLogin("⚠️ 自动登录超时, 请检查设备状态")
            }

            val currentEnv = service.getCurrentEnv()
            val needSwitch = currentEnv != null && currentEnv != "pre"

            fun doLogin() {
                val data = JsonObject().apply { addProperty("phoneNumber", phone) }
                service.sendBroadcast("LP_AUTO_LOGIN", data).whenComplete { success, err ->
                    if (autoLoginEnded.get()) return@whenComplete
                    if (err != null || success != true) {
                        val reason = err?.message ?: "发送返回 false"
                        endAutoLogin("❌ 自动登录指令发送失败: $reason")
                        return@whenComplete
                    }
                    SwingUtilities.invokeLater {
                        log("📲 自动登录指令已发送 ($phone), 等待设备处理...")
                    }
                    // 登录需要时间, 延迟后查询确认结果; 若设备已推送用户信息则由 listener 提前结束
                    CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute {
                        if (autoLoginEnded.get()) return@execute
                        if (!service.isConnected()) {
                            endAutoLogin("⚠️ 连接已断开, 自动登录未完成")
                            return@execute
                        }
                        service.queryLoginStatus().whenComplete { userInfo, queryErr ->
                            if (autoLoginEnded.get()) return@whenComplete
                            if (queryErr != null) {
                                endAutoLogin("⚠️ 登录状态查询超时, 请在设备上确认")
                                return@whenComplete
                            }
                            val msg = if (!userInfo.userId.isNullOrEmpty()) {
                                "✅ 登录成功: ${userInfo.loginId ?: phone}"
                            } else {
                                "⚠️ 登录指令已发送, 但未查询到登录态, 请检查设备"
                            }
                            endAutoLogin(msg, userInfo)
                        }
                    }
                }
            }

            if (needSwitch) {
                log("当前环境: $currentEnv, 正在切换到预发...")
                service.switchEnv("pre").whenComplete { switched, err ->
                    if (autoLoginEnded.get()) return@whenComplete
                    if (err != null || switched != true) {
                        val reason = err?.message ?: "切换失败"
                        endAutoLogin("❌ 切换预发环境失败, 自动登录中止: $reason")
                        return@whenComplete
                    }
                    log("✅ 环境已切换到预发, 等待重连后自动登录...")
                    service.autoReconnectEnabled = true
                    val done = java.util.concurrent.atomic.AtomicBoolean(false)
                    val listener: (DeviceInfo) -> Unit = { _ ->
                        if (done.compareAndSet(false, true)) {
                            reconnectListener?.let { service.removeDeviceInfoListener(it) }
                            reconnectListener = null
                            log("✅ 重连成功, 开始自动登录...")
                            doLogin()
                        }
                    }
                    reconnectListener = listener
                    service.addDeviceInfoListener(listener)
                    CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute {
                        service.removeDeviceInfoListener(listener)
                        if (reconnectListener === listener) reconnectListener = null
                        if (done.compareAndSet(false, true)) {
                            endAutoLogin("❌ 切换环境后等待重连超时")
                        }
                    }
                }
            } else {
                doLogin()
            }
        }
        autoLoginPanel.add(phoneCombo)
        autoLoginPanel.add(autoLoginBtn)
        panel.add(autoLoginPanel)

        // 状态显示
        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT, 1, 5)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val statusLabel = JLabel("状态: 未连接")

        // 设备信息显示
        // BoxLayout.Y_AXIS 下子组件水平位置取自 alignmentX(默认 CENTER_ALIGNMENT=0.5),
        // JLabel 默认就是 0.5,会被居中 -> 一列 label 看着"飘在中间"。
        // 这里把面板自身和每个 label 都设为 LEFT_ALIGNMENT,Y_AXIS 才会按左对齐排列。
        val deviceInfoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = false
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val deviceInfoLabel = JLabel("设备信息: ").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val iconLabel = JLabel().apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val displayNameLabel = JLabel("  应用名: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val bundleIdLabel = JLabel("  包名: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val platformLabel = JLabel("  平台: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val versionLabel = JLabel("  版本: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val envLabel = JLabel("  环境: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val screenLabel = JLabel("  分辨率: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val ipLabel = JLabel("  IP: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val productIdLabel = JLabel("  Product ID: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }

        deviceInfoPanel.add(deviceInfoLabel)
        deviceInfoPanel.add(iconLabel)
        deviceInfoPanel.add(displayNameLabel)
        deviceInfoPanel.add(bundleIdLabel)
        deviceInfoPanel.add(platformLabel)
        deviceInfoPanel.add(versionLabel)
        deviceInfoPanel.add(envLabel)
        deviceInfoPanel.add(screenLabel)
        deviceInfoPanel.add(ipLabel)
        deviceInfoPanel.add(productIdLabel)

        // 用户信息显示(与 deviceInfoPanel 对称)
        val userInfoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = false
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        val userInfoLabel = JLabel("用户信息: ").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val loginIdLabel = JLabel("  登录账号: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val userIdLabel = JLabel("  用户 ID: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val userEnvLabel = JLabel("  环境: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val passwordLabel = JLabel("  密码: -").apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        val loginPlaceholderLabel = JLabel("  未登录").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            isVisible = false
        }
        userInfoPanel.add(userInfoLabel)
        userInfoPanel.add(loginIdLabel)
        userInfoPanel.add(userIdLabel)
        userInfoPanel.add(userEnvLabel)
        userInfoPanel.add(passwordLabel)
        userInfoPanel.add(loginPlaceholderLabel)

        // 渲染 UserInfo,loginId 为空时显示"未登录"占位
        fun renderUserInfo(userInfo: UserInfo?) {
            val loggedIn = !userInfo?.loginId.isNullOrEmpty()
            loginIdLabel.isVisible = loggedIn
            userIdLabel.isVisible = loggedIn
            userEnvLabel.isVisible = loggedIn
            passwordLabel.isVisible = loggedIn
            loginPlaceholderLabel.isVisible = !loggedIn
            if (loggedIn) {
                loginIdLabel.text = "  登录账号: ${userInfo?.loginId ?: "-"}"
                userIdLabel.text = "  用户 ID: ${userInfo?.userId ?: "-"}"
                userEnvLabel.text = "  环境: ${userInfo?.env ?: "-"}"
                passwordLabel.text = "  密码: ${userInfo?.password ?: "-"}"
            }
        }

        // 延迟查询并刷新用户信息（退登/登录操作后调用）
        fun refreshUserInfoAfterDelay(delaySec: Long = 2) {
            CompletableFuture.delayedExecutor(delaySec, TimeUnit.SECONDS).execute {
                if (!service.isConnected()) return@execute
                if (autoLoginInProgress) return@execute
                service.queryLoginStatus()
                    .thenAccept { userInfo ->
                        SwingUtilities.invokeLater {
                            userInfoPanel.isVisible = true
                            renderUserInfo(userInfo)
                        }
                    }
                    .exceptionally { _ -> null }
            }
        }
        refreshUserInfo = { refreshUserInfoAfterDelay() }
        applyUserInfo = { userInfo ->
            userInfoPanel.isVisible = true
            renderUserInfo(userInfo)
            hasReceivedUserInfo = true
            // 延迟清除标记，给设备端最后一次空推送留缓冲窗口
            CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute {
                autoLoginInProgress = false
            }
        }

        // 添加设备信息监听器
        service.addDeviceInfoListener { deviceInfo ->
            SwingUtilities.invokeLater {
                deviceInfoPanel.isVisible = true
                iconLabel.icon = decodeBase64Icon(deviceInfo.icon)
                iconLabel.isVisible = iconLabel.icon != null
                displayNameLabel.text = "  应用名: ${deviceInfo.displayName ?: "-"}"
                bundleIdLabel.text = "  包名: ${deviceInfo.bundleIdentifier ?: "-"}"
                platformLabel.text = "  平台: ${deviceInfo.platform ?: "-"} (${deviceInfo.systemName ?: ""} ${deviceInfo.systemVersion ?: ""})"
                versionLabel.text = "  版本: ${deviceInfo.version ?: "-"} (${deviceInfo.appVersion ?: ""})"
                envLabel.text = "  环境: ${deviceInfo.env ?: "-"}"
                screenLabel.text = "  分辨率: " + run {
                    val w = deviceInfo.screenWidth
                    val h = deviceInfo.screenHeight
                    if (w != null && h != null) "$w × $h" else "-"
                }
                ipLabel.text = "  IP: ${deviceInfo.ipAddress ?: "-"}"
                productIdLabel.text = "  Product ID: ${deviceInfo.productId ?: "-"}"
                // 同步更新 currentEnv
                deviceInfo.env?.let { service.setCurrentEnv(it) }
            }
        }

        // 用户信息监听器:设备端在 autoLogin / currentUserInfo 推送时更新
        service.addUserInfoListener { userInfo ->
            SwingUtilities.invokeLater {
                if (autoLoginInProgress && userInfo.loginId.isNullOrEmpty()) {
                    return@invokeLater
                }
                if (autoLoginInProgress && !userInfo.loginId.isNullOrEmpty()) {
                    pendingAutoLoginSuccess?.invoke(userInfo)
                    return@invokeLater
                }
                userInfoPanel.isVisible = true
                renderUserInfo(userInfo)
                if (!userInfo.loginId.isNullOrEmpty()) {
                    hasReceivedUserInfo = true
                }
            }
        }

        // 更新状态的函数
        fun updateStatus() {
            val isConnected = service.isConnected()
            val config = service.getConnectionConfig()
            statusLabel.text = if (isConnected) {
                val pkg = config?.packageName ?: "未知应用"
                val port = config?.port ?: "未知"
                "✅ 已连接: $pkg (端口: $port)"
            } else {
                "❌ 未连接"
            }
            if (!isConnected) {
                deviceInfoPanel.isVisible = false
                userInfoPanel.isVisible = false
                service.setCurrentEnv("--")
            }
        }

        // 连接成功后主动查一次登录状态(deviceInfo 由 connect() 内部触发,userInfo 这里补一次)
        // 用 wasConnected 边沿检测,只在 断->连 那一刻打一次。
        // 不能走 service.addConnectionListener: 它挂到当前 client 上,初始化时 client=null 挂不上,
        // 且 reconnect 会换 client 把监听弄丢。这里复用下面的 Timer(100ms)轮询。
        var wasConnected = false
        var reconnectQueryScheduled = false
        fun pollUserInfoOnConnect() {
            val nowConnected = service.isConnected()
            if (!nowConnected) {
                hasReceivedUserInfo = false
            }
            if (nowConnected && !wasConnected) {
                if (!reconnectQueryScheduled) {
                    reconnectQueryScheduled = true
                    CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute {
                        reconnectQueryScheduled = false
                        if (!service.isConnected()) return@execute
                        if (hasReceivedUserInfo || autoLoginInProgress) return@execute
                        service.queryLoginStatus()
                            .thenAccept { userInfo ->
                                SwingUtilities.invokeLater {
                                    userInfoPanel.isVisible = true
                                    renderUserInfo(userInfo)
                                }
                            }
                            .exceptionally { err ->
                                SwingUtilities.invokeLater {
                                    userInfoPanel.isVisible = true
                                    renderUserInfo(null)
                                }
                                log("查询登录状态失败: ${err.message}")
                                null
                            }
                    }
                }
            }
            wasConnected = nowConnected
        }

        // 定期更新状态（因为连接是异步的）
        val timer = javax.swing.Timer(100) {
            updateStatus()
            pollUserInfoOnConnect()
        }
        timer.start()

        // 立即更新一次
        updateStatus()

        statusPanel.add(statusLabel)
        panel.add(statusPanel)
        panel.add(deviceInfoPanel)
        panel.add(userInfoPanel)

        outer.add(panel, BorderLayout.NORTH)
        return outer
    }

    private fun createEnvPanel(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // 环境映射关系
        val envMap = mapOf(
            "online" to "线上",
            "pre" to "预发",
            "test" to "测试",
            "dev" to "开发",
            "sim" to "仿真"
        )
        val reverseEnvMap = envMap.entries.associate { it.value to it.key }

        // 当前环境显示 + 切换
        val envPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val currentEnvLabel = JLabel("当前环境: --")
        currentEnvLabel.font = currentEnvLabel.font.deriveFont(java.awt.Font.BOLD)

        // 使用中文显示的下拉框
        val envCombo = ComboBox(DefaultComboBoxModel(envMap.values.toTypedArray()))
        envCombo.isEnabled = false  // 默认禁用，连接后才启用

        // 登录信息（可选）
        val loginField = JTextField(12)
        loginField.toolTipText = "登录账号（可选）"
        val passwordField = JPasswordField(12)
        passwordField.toolTipText = "登录密码（可选）"

        // 标志位：防止代码设置选中项时触发切换
        var isProgrammaticUpdate = false

        // 下拉选择切换环境
        envCombo.addActionListener {
            if (isProgrammaticUpdate) {
                return@addActionListener
            }
            if (!service.isConnected()) {
                log("❌ Not connected to device")
                return@addActionListener
            }
            val selectedDisplay = envCombo.selectedItem as? String ?: return@addActionListener
            val env = reverseEnvMap[selectedDisplay] ?: return@addActionListener
            val loginId = loginField.text.takeIf { it.isNotEmpty() }
            val password = String(passwordField.password).takeIf { it.isNotEmpty() }

            log("Switching to $selectedDisplay...")
            service.switchEnv(env, loginId, password).thenAccept { success: Boolean ->
                if (success) {
                    log("✅ Switched to $selectedDisplay")
                } else {
                    log("❌ Failed to switch to $selectedDisplay")
                }
            }
        }

        envPanel.add(currentEnvLabel)
        envPanel.add(JLabel("  切换:"))
        envPanel.add(envCombo)
        envPanel.add(JLabel("  账号:"))
        envPanel.add(loginField)
        envPanel.add(JLabel("  密码:"))
        envPanel.add(passwordField)

        panel.add(envPanel)

        // 监听设备信息更新当前环境显示
        service.addDeviceInfoListener { deviceInfo ->
            SwingUtilities.invokeLater {
                val env = deviceInfo.env
                if (env != null) {
                    val displayName = envMap[env] ?: env
                    currentEnvLabel.text = "当前环境: $displayName"
                    // 同步更新下拉框选中状态（标记为代码设置，避免触发切换）
                    isProgrammaticUpdate = true
                    envCombo.selectedItem = displayName
                    isProgrammaticUpdate = false
                } else {
                    currentEnvLabel.text = "当前环境: --"
                }
                envCombo.isEnabled = true
            }
        }

        // 监听用户信息（环境切换后更新）
        service.addUserInfoListener { userInfo ->
            SwingUtilities.invokeLater {
                val env = userInfo.env
                if (env != null) {
                    val displayName = envMap[env] ?: env
                    currentEnvLabel.text = "当前环境: $displayName"
                    // 标记为代码设置，避免触发切换
                    isProgrammaticUpdate = true
                    envCombo.selectedItem = displayName
                    isProgrammaticUpdate = false
                }
            }
        }

        // 连接断开时重置
        javax.swing.Timer(500) {
            if (!service.isConnected()) {
                currentEnvLabel.text = "当前环境: --"
                envCombo.isEnabled = false
                // 重置 service 中的当前环境
                if (service.getCurrentEnv() != null) {
                    service.setCurrentEnv("")
                }
            }
        }.start()

        // SOFA 分组
        val sofaPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val sofaField = JTextField(15)
        val sofaBtn = JButton("切换SOFA分组")

        sofaBtn.addActionListener {
            val group = sofaField.text
            service.switchSofaGroup(group).thenAccept { success: Boolean ->
                log("Switch SOFA group to $group: $success")
            }
        }

        sofaPanel.add(JLabel("SOFA分组:"))
        sofaPanel.add(sofaField)
        sofaPanel.add(sofaBtn)

        panel.add(sofaPanel)

        return panel
    }

    private fun createMockPanel(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // RPC Mock
        val rpcPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val rpcOperationField = JTextField("alipay.test.rpc", 20)
        val rpcResponseField = JTextField("{\"result\":\"mock\"}", 20)
        val rpcBtn = JButton("Mock RPC")

        rpcBtn.addActionListener {
            val operation = rpcOperationField.text
            val response = JsonObject().apply {
                addProperty("result", "mock")
            }

            service.mockRpc(operation, response).thenAccept { success: Boolean ->
                log("Mock RPC $operation: $success")
            }
        }

        rpcPanel.add(JLabel("RPC:"))
        rpcPanel.add(rpcOperationField)
        rpcPanel.add(JLabel("响应:"))
        rpcPanel.add(rpcResponseField)
        rpcPanel.add(rpcBtn)
        panel.add(rpcPanel)

        // LBS Mock
        val lbsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val latField = JTextField("31.2304", 10)
        val lngField = JTextField("121.4737", 10)
        val addressField = JTextField("上海市", 15)
        val lbsBtn = JButton("Mock LBS")

        lbsBtn.addActionListener {
            val lat = latField.text.toDoubleOrNull() ?: 0.0
            val lng = lngField.text.toDoubleOrNull() ?: 0.0
            val address = addressField.text

            service.mockLbs(lat, lng, address).thenAccept { success: Boolean ->
                log("Mock LBS to ($lat, $lng): $success")
            }
        }

        lbsPanel.add(JLabel("纬度:"))
        lbsPanel.add(latField)
        lbsPanel.add(JLabel("经度:"))
        lbsPanel.add(lngField)
        lbsPanel.add(JLabel("地址:"))
        lbsPanel.add(addressField)
        lbsPanel.add(lbsBtn)
        panel.add(lbsPanel)

        // Config Mock
        val configPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val configKeyField = JTextField("test_config_key", 15)
        val configValueField = JTextField("test_value", 15)
        val configBtn = JButton("Mock Config")

        configBtn.addActionListener {
            val key = configKeyField.text
            val value = configValueField.text

            service.mockConfig(key, value).thenAccept { success: Boolean ->
                log("Mock Config $key=$value: $success")
            }
        }

        configPanel.add(JLabel("配置Key:"))
        configPanel.add(configKeyField)
        configPanel.add(JLabel("配置Value:"))
        configPanel.add(configValueField)
        configPanel.add(configBtn)
        panel.add(configPanel)

        return panel
    }

    private fun createDevicePanel(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT))

        // 截图按钮
        val screenshotBtn = JButton("截图")
        screenshotBtn.addActionListener {
            service.screenshot().thenAccept { base64: String ->
                log("Screenshot received, size: ${base64.length}")
                // 可以保存为文件或显示
            }.exceptionally { error: Throwable ->
                log("Screenshot failed: ${error.message}")
                null
            }
        }
        btnPanel.add(screenshotBtn)

        // 重启按钮
        val restartBtn = JButton("重启应用")
        restartBtn.addActionListener {
            service.restartApp().thenAccept { success: Boolean ->
                log("Restart app: $success")
            }
        }
        btnPanel.add(restartBtn)

        // 清除数据按钮
        val clearBtn = JButton("清除数据")
        clearBtn.addActionListener {
            service.clearAppData().thenAccept { success: Boolean ->
                log("Clear app data: $success")
            }
        }
        btnPanel.add(clearBtn)

        // 路由跳转
        val routerPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val appIdField = JTextField("20000001", 15)
        val routerBtn = JButton("跳转")
        routerBtn.addActionListener {
            val appId = appIdField.text
            service.navigateTo(appId).thenAccept { success: Boolean ->
                log("Navigate to $appId: $success")
            }
        }
        routerPanel.add(JLabel("AppId:"))
        routerPanel.add(appIdField)
        routerPanel.add(routerBtn)

        // 发送广播
        val broadcastPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val broadcastActionField = JTextField("LP_AUTO_LOGIN", 25)
        val broadcastDataField = JTextField("{\"phoneNumber\":\"19976980995\",\"code\":888888}", 20)
        val broadcastBtn = JButton("发送广播")
        broadcastBtn.addActionListener {
            val action = broadcastActionField.text
            val dataText = broadcastDataField.text

            try {
                val data = if (dataText.isNotEmpty() && dataText != "{}") {
                    com.google.gson.JsonParser.parseString(dataText).asJsonObject
                } else {
                    null
                }

                service.sendBroadcast(action, data).thenAccept { success: Boolean ->
                    log("发送:${broadcastActionCn(action)} ${if (success) "成功" else "失败"}")
                }
            } catch (e: Exception) {
                log("sent failed: ${e.message}")
            }
        }
        broadcastPanel.add(JLabel("广播Action:"))
        broadcastPanel.add(broadcastActionField)
        broadcastPanel.add(JLabel("参数(JSON):"))
        broadcastPanel.add(broadcastDataField)
        broadcastPanel.add(broadcastBtn)

        panel.add(btnPanel)
        panel.add(routerPanel)
        panel.add(broadcastPanel)

        return panel
    }

    private fun createFilePanel(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // 列出文件
        val listPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val pathField = JTextField("/sdcard", 20)
        val listBtn = JButton("列出文件")

        listBtn.addActionListener {
            val path = pathField.text
            service.listFiles(path).thenAccept { success: Boolean ->
                log("List files in $path: $success")
            }
        }

        listPanel.add(JLabel("路径:"))
        listPanel.add(pathField)
        listPanel.add(listBtn)
        panel.add(listPanel)

        return panel
    }

    private fun log(message: String) {
        SwingUtilities.invokeLater {
            logArea.append("$message\n")
            logArea.caretPosition = logArea.document.length
        }
    }

    private fun decodeBase64Icon(raw: String?): Icon? {
        if (raw.isNullOrBlank()) return null
        val payload = raw.substringAfter(",", raw).trim()
        return try {
            val bytes = Base64.getDecoder().decode(payload)
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            val scaled = image.getScaledInstance(48, 48, Image.SCALE_SMOOTH)
            ImageIcon(scaled)
        } catch (e: Exception) {
            log("Decode device icon failed: ${e.message}")
            null
        }
    }

    private fun broadcastActionCn(action: String): String = when (action) {
        "LP_AUTO_LOGIN" -> "自动登录"
        "LP_FORCE_LOGOUT" -> "踢登"
        "LP_LOGOUT" -> "退登"
        else -> action
    }
}
