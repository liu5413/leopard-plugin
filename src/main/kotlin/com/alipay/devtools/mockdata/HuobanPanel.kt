package com.alipay.devtools.mockdata

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import com.intellij.ui.table.JBTable
import javax.swing.*
import javax.swing.table.DefaultTableModel

class HuobanPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val home = System.getProperty("user.home")
    private val extraPath = "$home/.local/bin:$home/.antcli:$home/.acli/bin:/usr/local/bin:/opt/homebrew/bin"
    private val prefsFile: Path = Path.of(home, ".huoban_plugin_prefs.json")

    private val logArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font("Monospaced", Font.PLAIN, 12)
    }

    private val versionCombo = ComboBox<String>().apply {
        preferredSize = Dimension(200, preferredSize.height)
    }
    private val sprintCombo = ComboBox<SprintItem>().apply {
        preferredSize = Dimension(256, preferredSize.height)
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ) = super.getListCellRendererComponent(list, (value as? SprintItem)?.displayName ?: "", index, isSelected, cellHasFocus)
        }
    }
    private val buildTypeCombo = ComboBox(arrayOf("灰度包", "测试包"))
    private val modulePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val buildBtn = JButton("开始打包").apply { isEnabled = false }
    private val selectAllCheckbox = JCheckBox("全选").apply { isEnabled = false }
    private val selectedCountLabel = JLabel("已选 0 个")
    private val packageTableModel = object : DefaultTableModel(arrayOf("版本", "大小(MB)", "类型", "状态", "构建时间", "构建人"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val packageTable = JBTable(packageTableModel).apply {
        rowHeight = 28
        tableHeader.reorderingAllowed = false
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        showHorizontalLines = true
        showVerticalLines = false
        intercellSpacing = Dimension(0, 1)
        autoResizeMode = JTable.AUTO_RESIZE_NEXT_COLUMN
        columnModel.getColumn(0).preferredWidth = 80  // 版本
        columnModel.getColumn(1).preferredWidth = 60   // 大小
        columnModel.getColumn(2).preferredWidth = 100   // 类型
        columnModel.getColumn(3).preferredWidth = 60   // 状态
        columnModel.getColumn(3).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ) = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).also {
                foreground = if (!isSelected) when (value) {
                    "打包失败" -> java.awt.Color.RED
                    "打包中" -> java.awt.Color(200, 150, 0)
                    else -> table?.foreground
                } else table?.foreground
            }
        }
        columnModel.getColumn(4).preferredWidth = 140  // 构建时间
        columnModel.getColumn(5).preferredWidth = 50   // 构建人
    }

    private var allSprints = listOf<SprintItem>()
    private val moduleCheckboxes = mutableListOf<Pair<JCheckBox, ModuleItem>>()
    private var suppressSave = false
    private var sprintLinkField: JTextField

    @Volatile
    private var resolvedCliPath: String? = null

    init {
        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)

        val acliStatusPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply { alignmentX = LEFT_ALIGNMENT }
        val acliStatusLabel = JLabel("acli: 检查中...")
        acliStatusPanel.add(acliStatusLabel)
        topPanel.add(acliStatusPanel)

        val versionPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply { alignmentX = LEFT_ALIGNMENT }
        versionPanel.add(JLabel("版本:"))
        versionPanel.add(versionCombo)
        versionPanel.add(JButton("↻").apply {
            toolTipText = "刷新迭代列表"
            preferredSize = Dimension(50, preferredSize.height)
            addActionListener {
                ApplicationManager.getApplication().executeOnPooledThread {
                    resolvedCliPath = null
                    loadVersions()
                }
            }
        })
        topPanel.add(versionPanel)

        val sprintPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply { alignmentX = LEFT_ALIGNMENT }
        sprintPanel.add(JLabel("迭代:"))
        sprintPanel.add(sprintCombo)
        topPanel.add(sprintPanel)

        val sprintLinkPanel = JPanel(BorderLayout()).apply {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 24)
        }
        sprintLinkField = JTextField("").apply {
            isEditable = false
            border = BorderFactory.createEmptyBorder(0, 48, 0, 0)
            isOpaque = false
            foreground = java.awt.Color(0x3574F0)
            font = Font("Dialog", Font.PLAIN, 11)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (text.isNotEmpty()) com.intellij.ide.BrowserUtil.browse(text)
                }
            })
        }
        sprintLinkPanel.add(sprintLinkField, BorderLayout.CENTER)
        topPanel.add(sprintLinkPanel)

        val buildTypePanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply { alignmentX = LEFT_ALIGNMENT }
        buildTypePanel.add(JLabel("包类型:"))
        buildTypePanel.add(buildTypeCombo)
        topPanel.add(buildTypePanel)

        val moduleHeader = JPanel(FlowLayout(FlowLayout.LEFT)).apply { alignmentX = LEFT_ALIGNMENT }
        moduleHeader.add(JLabel("模块包:"))
        moduleHeader.add(selectAllCheckbox)
        moduleHeader.add(selectedCountLabel)
        topPanel.add(moduleHeader)

        val moduleScroll = JBScrollPane(modulePanel)

        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply { alignmentX = LEFT_ALIGNMENT }
        btnPanel.add(buildBtn)

        // 构建 tab 内容
        val buildPanel = JPanel(BorderLayout())
        buildPanel.add(moduleScroll, BorderLayout.CENTER)
        buildPanel.add(btnPanel, BorderLayout.SOUTH)

        // 安装包 tab 内容
        packageTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val row = packageTable.rowAtPoint(e.point)
                    copyPackageUrl(row)
                }
            }

            override fun mousePressed(e: java.awt.event.MouseEvent) { showPopup(e) }
            override fun mouseReleased(e: java.awt.event.MouseEvent) { showPopup(e) }

            private fun showPopup(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = packageTable.rowAtPoint(e.point)
                if (row < 0 || row >= packageExtraInfo.size) return
                packageTable.setRowSelectionInterval(row, row)

                val popup = JPopupMenu().apply {
                    border = BorderFactory.createLineBorder(java.awt.Color(220, 220, 220))
                }
                val menuFont = Font("Dialog", Font.PLAIN, 13)
                val baselineFile = findBaselineGradleFile()
                if (baselineFile != null) {
                    val state = packageTableModel.getValueAt(row, 3) as? String ?: ""
                    popup.add(JMenuItem("  🔄 更新当前基线").apply {
                        font = menuFont
                        border = BorderFactory.createEmptyBorder(6, 8, 6, 16)
                        isEnabled = (state == "打包成功")
                        addActionListener { updateBaseline(row, baselineFile) }
                    })
                }
                popup.add(JMenuItem("  📋 复制下载链接").apply {
                    font = menuFont
                    border = BorderFactory.createEmptyBorder(6, 8, 6, 16)
                    addActionListener { copyPackageUrl(row) }
                })
                popup.add(JMenuItem("  📱 生成二维码").apply {
                    font = menuFont
                    border = BorderFactory.createEmptyBorder(6, 8, 6, 16)
                    addActionListener { showQrCode(row) }
                })
                popup.show(e.component, e.x, e.y)
            }
        })
        val refreshBtn = JButton("刷新").apply {
            addActionListener {
                log("刷新安装包列表")
                val sprint = sprintCombo.selectedItem as? SprintItem ?: return@addActionListener
                loadPackages(sprint.projectUniqueId)
            }
        }
        val packageBtnPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(refreshBtn)
        }
        val packagePanel = JPanel(BorderLayout())
        packagePanel.add(packageBtnPanel, BorderLayout.NORTH)
        packagePanel.add(JBScrollPane(packageTable), BorderLayout.CENTER)

        val tabbedPane = JTabbedPane()
        tabbedPane.addTab("构建", buildPanel)
        tabbedPane.addTab("安装包", packagePanel)

        // 切换到安装包 tab 时刷新列表
        tabbedPane.addChangeListener {
//            if (tabbedPane.selectedIndex == 1) {
//                val sprint = sprintCombo.selectedItem as? SprintItem
//                if (sprint != null) {
//                    loadPackages(sprint.projectUniqueId)
//                }
//            }
        }

        val upperPanel = JPanel(BorderLayout())
        upperPanel.add(topPanel, BorderLayout.NORTH)
        upperPanel.add(tabbedPane, BorderLayout.CENTER)

        val splitter = JSplitPane(JSplitPane.VERTICAL_SPLIT, upperPanel, JBScrollPane(logArea)).apply {
            resizeWeight = 0.3
            isContinuousLayout = true
        }
        add(splitter, BorderLayout.CENTER)

        selectAllCheckbox.addActionListener {
            val selected = selectAllCheckbox.isSelected
            moduleCheckboxes.forEach { (cb, _) -> cb.isSelected = selected }
            updateSelectedCount()
        }

        versionCombo.addActionListener {
            if (suppressSave) return@addActionListener
            val version = versionCombo.selectedItem as? String ?: return@addActionListener
            populateSprintsForVersion(version)
            savePrefs()
        }

        sprintCombo.addActionListener {
            if (suppressSave) return@addActionListener
            val sprint = sprintCombo.selectedItem as? SprintItem ?: return@addActionListener
            sprintLinkField.text = sprint.projectUniqueId
            modulePanel.removeAll()
            moduleCheckboxes.clear()
            loadModules(sprint.projectUniqueId)
            packageTableModel.rowCount = 0
            packageExtraInfo.clear()
            loadPackages(sprint.projectUniqueId)
            savePrefs()
        }

        buildBtn.addActionListener { startBuild() }

        checkAcliAndInit(acliStatusLabel)
    }

    private fun loadPrefs(): JsonObject? {
        return try {
            if (Files.exists(prefsFile)) {
                JsonParser.parseString(Files.readString(prefsFile)).asJsonObject
            } else null
        } catch (_: Exception) { null }
    }

    private fun savePrefs() {
        try {
            val obj = JsonObject()
            (versionCombo.selectedItem as? String)?.let { obj.addProperty("version", it) }
            (sprintCombo.selectedItem as? SprintItem)?.let { obj.addProperty("sprintId", it.projectUniqueId) }
            Files.writeString(prefsFile, obj.toString())
        } catch (_: Exception) {}
    }

    private fun log(msg: String) {
        SwingUtilities.invokeLater {
            logArea.append("$msg\n")
            logArea.caretPosition = logArea.document.length
        }
    }

    private fun buildEnv(): Map<String, String> {
        val env = System.getenv().toMutableMap()
        val path = env.getOrDefault("PATH", "")
        env["PATH"] = "$extraPath:$path"
        return env
    }

    private fun resolveCliPath(): String {
        resolvedCliPath?.let { return it }
        val candidates = listOf(
            "$home/.local/bin/huoban-cli",
            "$home/.antcli/huoban-cli",
            "$home/.acli/bin/huoban-cli",
            "/usr/local/bin/huoban-cli",
            "/opt/homebrew/bin/huoban-cli"
        )
        for (c in candidates) {
            if (Files.isExecutable(Path.of(c))) {
                resolvedCliPath = c
                return c
            }
        }
        val whichOut = runShell("which huoban-cli 2>/dev/null").trim()
        if (whichOut.isNotEmpty() && !whichOut.contains("not found") && Files.isExecutable(Path.of(whichOut))) {
            resolvedCliPath = whichOut
            return whichOut
        }
        return "huoban-cli"
    }

    private fun runCli(vararg args: String): String {
        val cliPath = resolveCliPath()
        val cmd = listOf(cliPath) + args.toList() + listOf("--format=json", "--no-interactive")
//        log("> ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd).apply {
            environment().putAll(buildEnv())
            redirectErrorStream(true)
        }
        val process = pb.start()
        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.forEachLine { line ->
                output.appendLine(line)
            }
        }
        process.waitFor()
        return output.toString().trim()
    }

    private fun runShell(command: String): String {
        val pb = ProcessBuilder("bash", "-c", command).apply {
            environment().putAll(buildEnv())
            redirectErrorStream(true)
        }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        return output
    }

    private fun checkAcliAndInit(statusLabel: JLabel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val acliPath = runShell("which acli 2>/dev/null")
                if (acliPath.isEmpty() || acliPath.contains("not found")) {
                    log("acli 未安装，正在安装...")
                    SwingUtilities.invokeLater { statusLabel.text = "acli: 安装中..." }
                    val installResult = runShell("curl -fsSL https://artifacts.antgroup-inc.cn/t/MAIN_SITE/artifact/repositories/ant-cli-common/cli/acli/install/install.sh | bash")
                    log(installResult)
                }

                val huobanPath = runShell("which huoban-cli 2>/dev/null")
                if (huobanPath.isEmpty() || huobanPath.contains("not found")) {
                    log("huoban-cli 未安装，正在通过 acli 安装...")
                    SwingUtilities.invokeLater { statusLabel.text = "huoban-cli: 安装中..." }
                    val installResult = runShell("acli install huoban-cli --yes")
                    log(installResult)
                }

                val version = runShell("huoban-cli version 2>/dev/null || echo 'unknown'")
                log("huoban-cli 版本: $version")
                SwingUtilities.invokeLater { statusLabel.text = "huoban-cli: $version" }

                log("正在检查更新...")
                val updateResult = runShell("acli upgrade huoban-cli --yes 2>/dev/null || echo 'skip'")
                if (updateResult.isNotEmpty() && updateResult != "skip") {
                    log(updateResult)
                    val newVersion = runShell("huoban-cli version 2>/dev/null || echo 'unknown'")
                    if (newVersion != version) {
                        log("huoban-cli 已更新到: $newVersion")
                        SwingUtilities.invokeLater { statusLabel.text = "huoban-cli: $newVersion" }
                    }
                }

                loadVersions()
            } catch (e: Exception) {
                log("初始化失败: ${e.message}")
                SwingUtilities.invokeLater { statusLabel.text = "acli: 检查失败" }
            }
        }
    }

    private fun loadVersions(retryCount: Int = 0) {
        try {
            log("正在加载版本列表...")
            val output = runCli("sprint", "list", "--product-name=LEOPARD", "--type=all", "--page-size=100")
            val json = JsonParser.parseString(output).asJsonObject
            val resultObj = json.getAsJsonObject("result")
            val list = resultObj?.getAsJsonArray("list") ?: run {
                log("未找到迭代数据 (result.list 为空)")
                return
            }

            val sprints = mutableListOf<SprintItem>()
            val versions = mutableSetOf<String>()

            for (element in list) {
                val obj = element.asJsonObject
                val v = obj.get("packageVersion")?.asString ?: continue
                val name = obj.get("sprintName")?.asString ?: ""
                val puid = obj.get("sprintUniqueId")?.asString ?: continue
                val status = obj.get("sprintStatusShow")?.asString ?: ""
                val nameLower = name.lowercase()
                val platform = when {
                    nameLower.contains("android") -> "Android"
                    nameLower.contains("ios") || nameLower.contains("xcode") -> "iOS"
                    nameLower.contains("ohos") || nameLower.contains("harmonyos") || nameLower.contains("harmony") || nameLower.contains("鸿蒙") -> "Harmony"
                    else -> "Android"
                }
                versions.add(v)
                sprints.add(SprintItem(name, puid, v, status, platform))
            }

            allSprints = sprints
            val prefs = loadPrefs()
            val savedVersion = prefs?.get("version")?.asString
            val savedSprintId = prefs?.get("sprintId")?.asString

            SwingUtilities.invokeLater {
                suppressSave = true
                versionCombo.removeAllItems()
                versions.sortedDescending().forEach { versionCombo.addItem(it) }
                if (savedVersion != null && versions.contains(savedVersion)) {
                    versionCombo.selectedItem = savedVersion
                } else if (versions.isNotEmpty()) {
                    versionCombo.selectedIndex = 0
                }

                val selectedVersion = versionCombo.selectedItem as? String
                if (selectedVersion != null) {
                    populateSprintsForVersion(selectedVersion)
                    if (savedSprintId != null) {
                        for (i in 0 until sprintCombo.itemCount) {
                            if (sprintCombo.getItemAt(i).projectUniqueId == savedSprintId) {
                                sprintCombo.selectedIndex = i
                                break
                            }
                        }
                    }
                }
                suppressSave = false

                val sprint = sprintCombo.selectedItem as? SprintItem
                if (sprint != null) {
                    sprintLinkField.text = sprint.projectUniqueId
                    loadModules(sprint.projectUniqueId)
                    loadPackages(sprint.projectUniqueId)
                }

                log("✅已加载 ${versions.size} 个版本, ${sprints.size} 个迭代")
            }
        } catch (e: Exception) {
            if (retryCount < 2) {
                log("加载版本失败，${retryCount + 3}s 后重试...")
                Thread.sleep((retryCount + 3) * 1000L)
                loadVersions(retryCount + 3)
            } else {
                log("❌加载版本失败: ${e.message}")
            }
        }
    }

    private fun populateSprintsForVersion(version: String) {
        val filtered = allSprints.filter { it.version == version }
        sprintCombo.removeAllItems()
        filtered.forEach { sprintCombo.addItem(it) }
        if (filtered.isNotEmpty()) {
            sprintCombo.selectedIndex = 0
        }
    }

    private fun loadModules(projectUniqueId: String) {
        log("正在加载迭代模块列表...")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val output = runCli("sprint", "module", "list", "--project-unique-id=$projectUniqueId", "--page-size=100")
                val json = JsonParser.parseString(output).asJsonObject
                val data = json.getAsJsonObject("result")?.getAsJsonArray("list")
                    ?: json.getAsJsonArray("data")
                    ?: run {
                        log("未找到模块数据")
                        SwingUtilities.invokeLater {
                            modulePanel.removeAll()
                            moduleCheckboxes.clear()
                        }
                        return@executeOnPooledThread
                    }

                val allModules = mutableListOf<ModuleItem>()
                for (element in data) {
                    val obj = element.asJsonObject
                    val moduleId = obj.get("moduleId")?.asLong ?: continue
                    val artifactId = obj.get("artifactId")?.asString ?: ""
                    val groupId = obj.get("groupId")?.asString ?: ""
                    val comment = obj.getAsJsonObject("module")?.get("comment")?.asString ?: ""
                    val name = if (comment.isNotEmpty()) "$artifactId ($comment)" else artifactId
                    allModules.add(ModuleItem(moduleId, artifactId, groupId, name))
                }

                val priorityOrder = listOf("leopard", "leopardaccount", "middleware-integration", "infra-integration")
                val sortedModules = allModules.sortedWith(compareBy { module ->
                    val idx = priorityOrder.indexOf(module.artifactId)
                    if (idx >= 0) idx else priorityOrder.size
                })

                SwingUtilities.invokeLater {
                    modulePanel.removeAll()
                    moduleCheckboxes.clear()
                    for (m in sortedModules) {
                        val cb = JCheckBox(m.displayName)
                        cb.addActionListener { updateSelectedCount() }
                        moduleCheckboxes.add(cb to m)
                        modulePanel.add(cb)
                    }
                    modulePanel.revalidate()
                    modulePanel.repaint()
                    selectAllCheckbox.isEnabled = sortedModules.isNotEmpty()
                    selectAllCheckbox.isSelected = false
                    buildBtn.isEnabled = true
                    updateSelectedCount()
                    log("✅加载模块成功,共 ${sortedModules.size} 个模块")
                }
            } catch (e: Exception) {
                log("❌加载模块失败: ${e.message}")
            }
        }
    }

    private fun updateSelectedCount() {
        val count = moduleCheckboxes.count { it.first.isSelected }
        selectedCountLabel.text = "已选 $count 个"
    }

    private val packageExtraInfo = mutableListOf<PackageExtra>()

    data class PackageExtra(val downloadUrl: String, val fileNameCn: String, val instanceHeadline: String, val sizeDisplay: String, val createTime: String = "")

    private fun copyPackageUrl(row: Int) {
        if (row < 0 || row >= packageExtraInfo.size) return
        val state = packageTableModel.getValueAt(row, 3) as? String ?: ""
        if (state == "打包中") {
            log("⏳正在打包中，请稍后再试")
            return
        }
        if (state == "打包失败") {
            log("❌打包失败，无法获取下载链接")
            return
        }
        val url = packageExtraInfo.getOrNull(row)?.downloadUrl ?: ""
        if (url.isNotEmpty()) {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(url), null)
            val version = packageTableModel.getValueAt(row, 0) as? String ?: ""
            log("✅已复制下载链接（$version）: \n$url")
        } else {
            log("该安装包暂无下载链接")
        }
    }

    private fun findBaselineGradleFile(): java.io.File? {
        val basePath = project.basePath ?: return null
        val file = java.io.File(basePath, "bundle_runtime/build.gradle")
        if (!file.exists()) return null
        val content = file.readText()
        val regex = Regex("""^\s*apkDownloadUrl\s*=\s*"https?://[^"]+"""", RegexOption.MULTILINE)
        return if (regex.containsMatchIn(content)) file else null
    }

    private fun updateBaseline(row: Int, gradleFile: java.io.File) {
        if (row < 0 || row >= packageExtraInfo.size) return
        val newUrl = packageExtraInfo.getOrNull(row)?.downloadUrl ?: ""
        if (newUrl.isEmpty()) {
            log("该安装包暂无下载链接")
            return
        }
        try {
            val content = gradleFile.readText()
            val regex = Regex("""(apkDownloadUrl\s*=\s*")https?://[^"]+"""")
            val newContent = regex.replace(content) { "${it.groupValues[1]}$newUrl\"" }
            if (newContent == content) {
                log("⚠️未找到可替换的基线")
                return
            }
            gradleFile.writeText(newContent)
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(gradleFile)
            vf?.refresh(false, false)
            val version = packageTableModel.getValueAt(row, 0) as? String ?: ""
            log("✅已更新基线（$version）")
            triggerGradleSync()
        } catch (e: Exception) {
            log("❌更新基线失败: ${e.message}")
        }
    }

    private fun triggerGradleSync() {
        try {
            val connection = project.messageBus.connect()
            connection.subscribe(
                com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener.TOPIC,
                object : com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener {
                    override fun onImportFinished(projectPath: String?) {
                        log("✅ Gradle 同步完成")
                        connection.disconnect()
                    }

                    override fun onImportFailed(projectPath: String?, t: Throwable) {
                        log("❌ Gradle 同步失败: ${t.message}")
                        connection.disconnect()
                    }
                }
            )
            ApplicationManager.getApplication().invokeLater {
                val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .build()
                val syncIds = listOf(
                    "Android.SyncProject",
                    "ExternalSystem.RefreshAllProjects"
                )
                for (id in syncIds) {
                    val action = actionManager.getAction(id) ?: continue
                    val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                        action, null, "", dataContext
                    )
                    action.actionPerformed(event)
                    break
                }
            }
            log("🔄正在同步 Gradle...")
        } catch (e: Exception) {
            log("⚠️Gradle sync 触发失败: ${e.message}")
        }
    }

    private fun showQrCode(row: Int) {
        if (row < 0 || row >= packageExtraInfo.size) return
        val url = packageExtraInfo.getOrNull(row)?.downloadUrl ?: ""
        if (url.isEmpty()) {
            log("该安装包暂无下载链接，无法生成二维码")
            return
        }

        val version = packageTableModel.getValueAt(row, 0) as? String ?: ""

        try {
        val qrImage = generateQrImage(url, 200)
        if (qrImage == null) {
            log("❌生成二维码失败")
            return
        }

        val platform = (sprintCombo.selectedItem as? SprintItem)?.platform ?: ""
        val iconPath = when (platform) {
            "Android" -> "/icons/android.svg"
            "iOS" -> "/icons/apple.svg"
            "HarmonyOS" -> "/icons/hm.svg"
            else -> ""
        }
        try {
            if (iconPath.isNotEmpty()) {
                val iconSize = qrImage.width / 5
                val svgStream = HuobanPanel::class.java.getResourceAsStream(iconPath)
                if (svgStream != null) {
                    val svgBytes = svgStream.readBytes()
                    svgStream.close()
                    val rawImg = com.intellij.util.SVGLoader.load(
                        java.io.ByteArrayInputStream(svgBytes), iconSize.toFloat() / 80f
                    )
                    if (rawImg != null) {
                        val qrG = qrImage.createGraphics()
                        val x = (qrImage.width - iconSize) / 2
                        val y = (qrImage.height - iconSize) / 2
                        val bgPad = 4
                        qrG.color = java.awt.Color.WHITE
                        qrG.fillRoundRect(x - bgPad, y - bgPad, iconSize + bgPad * 2, iconSize + bgPad * 2, 8, 8)
                        qrG.drawImage(rawImg, x, y, iconSize, iconSize, null)
                        qrG.dispose()
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 绘制完整图片：顶部文字 + 二维码 + 底部提示
        val extra = packageExtraInfo.getOrNull(row)
        val fileNameCn = extra?.fileNameCn ?: ""
        val sizeDisplay = extra?.sizeDisplay ?: ""
        val createTime = extra?.createTime ?: ""
        val versionLine = if (sizeDisplay.isNotEmpty()) "$version (${sizeDisplay}M)" else version

        val padding = 24
        val lineGap = 6
        val qrTopGap = 16
        val qrBottomGap = 12

        val line1Font = Font("Dialog", Font.BOLD, 16)
        val line2Font = Font("Dialog", Font.PLAIN, 12)
        val timeFont = Font("Dialog", Font.PLAIN, 11)
        val hintFont = Font("Dialog", Font.PLAIN, 12)

        val tempImg = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val tempG = tempImg.createGraphics()
        tempG.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val line1H = tempG.getFontMetrics(line1Font).height
        val line2H = tempG.getFontMetrics(line2Font).height
        val timeH = if (createTime.isNotEmpty()) tempG.getFontMetrics(timeFont).height else 0
        val hintH = tempG.getFontMetrics(hintFont).height
        tempG.dispose()

        val hintText = "扫码前请确保手机已连接内网"
        val timeGap = if (createTime.isNotEmpty()) lineGap else 0
        val textBlockH = line1H + lineGap + line2H + timeGap + timeH
        val imgW = qrImage.width + padding * 2
        val imgH = padding + textBlockH + qrTopGap + qrImage.height + qrBottomGap + hintH + padding

        val compositeImage = java.awt.image.BufferedImage(imgW, imgH, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = compositeImage.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, imgW, imgH)

        var curY = padding

        g.color = java.awt.Color.BLACK
        g.font = line1Font
        val l1W = g.fontMetrics.stringWidth(fileNameCn)
        g.drawString(fileNameCn, (imgW - l1W) / 2, curY + g.fontMetrics.ascent)
        curY += line1H + lineGap

        g.color = java.awt.Color(100, 100, 100)
        g.font = line2Font
        val l2W = g.fontMetrics.stringWidth(versionLine)
        g.drawString(versionLine, (imgW - l2W) / 2, curY + g.fontMetrics.ascent)
        curY += line2H

        if (createTime.isNotEmpty()) {
            curY += lineGap
            g.color = java.awt.Color(130, 130, 130)
            g.font = timeFont
            val timeW = g.fontMetrics.stringWidth(createTime)
            g.drawString(createTime, (imgW - timeW) / 2, curY + g.fontMetrics.ascent)
            curY += timeH
        }
        curY += qrTopGap

        g.drawImage(qrImage, padding, curY, null)
        curY += qrImage.height + qrBottomGap

        g.color = java.awt.Color(80, 80, 80)
        g.font = hintFont
        val hintW = g.fontMetrics.stringWidth(hintText)
        g.drawString(hintText, (imgW - hintW) / 2, curY + g.fontMetrics.ascent)

        g.dispose()

        // 气泡弹出展示二维码
        val popupPanel = JPanel(BorderLayout())
        popupPanel.add(JLabel(ImageIcon(compositeImage)), BorderLayout.CENTER)
        val copyBtn = JButton("复制").apply {
            addActionListener {
                val transferable = object : java.awt.datatransfer.Transferable {
                    override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
                    override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor?) = flavor == java.awt.datatransfer.DataFlavor.imageFlavor
                    override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor?): Any = compositeImage
                }
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
                log("✅二维码图片已复制到剪贴板（$version）")
            }
        }
        val btnPanel = JPanel(FlowLayout(FlowLayout.CENTER))
        btnPanel.add(copyBtn)
        popupPanel.add(btnPanel, BorderLayout.SOUTH)

        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupPanel, null)
            .setTitle("二维码")
            .setFocusable(true)
            .setRequestFocus(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        popup.showInCenterOf(this)
        } catch (e: Throwable) {
            log("❌复制二维码失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun generateQrImage(text: String, size: Int): java.awt.image.BufferedImage? {
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
                com.google.zxing.EncodeHintType.MARGIN to 0,
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H
            )
            val bitMatrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
            val encRegion = bitMatrix.getEnclosingRectangle()
            val cropX = encRegion[0]
            val cropY = encRegion[1]
            val cropW = encRegion[2]
            val cropH = encRegion[3]
            val margin = 4
            val outSize = maxOf(cropW, cropH) + margin * 2
            val img = java.awt.image.BufferedImage(outSize, outSize, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, outSize, outSize)
            val offsetX = (outSize - cropW) / 2
            val offsetY = (outSize - cropH) / 2
            for (y in 0 until cropH) {
                for (x in 0 until cropW) {
                    if (bitMatrix.get(cropX + x, cropY + y)) {
                        img.setRGB(offsetX + x, offsetY + y, 0xFF000000.toInt())
                    }
                }
            }
            g.dispose()
            val scaled = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val sg = scaled.createGraphics()
            sg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            sg.drawImage(img, 0, 0, size, size, null)
            sg.dispose()
            scaled
        } catch (e: Exception) {
            null
        }
    }

    private fun loadPackages(projectUniqueId: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val platform = (sprintCombo.selectedItem as? SprintItem)?.platform?.ifEmpty { "Android" } ?: "Android"
                val output = runCli("package", "list", "--product-name=LEOPARD", "--project-unique-id=$projectUniqueId", "--platform=$platform", "--page-size=50")
                val json = JsonParser.parseString(output).asJsonObject
                val resultElement = json.get("result")
                val list = when {
                    resultElement == null -> null
                    resultElement.isJsonArray -> resultElement.asJsonArray
                    resultElement.isJsonObject -> resultElement.asJsonObject.getAsJsonArray("list")
                    else -> null
                }
                log("正在加载安装包列表...")
                SwingUtilities.invokeLater {
                    packageTableModel.rowCount = 0
                    packageExtraInfo.clear()
                    log("✅加载安装包列表成功")
                    if (list != null && list.size() > 0) {
                        for (element in list) {
                            val obj = element.asJsonObject
                            val buildType = obj.get("buildType")?.asString ?: ""
                            if (platform == "Android") {
                                if (buildType != "debug") continue
                            } else if (platform == "iOS") {
                                if (buildType != "enterprise_debug") continue
                            }

                            val version = obj.get("version")?.asString ?: ""
                            val size = obj.get("sizeDisplay")?.asString ?: "0"
                            val type = obj.get("typeDisplay")?.asString ?: obj.get("type")?.asString ?: ""
                            val state = obj.get("state")?.asString ?: ""
                            val stateCn = when (state) {
                                "packing" -> "打包中"
                                "success" -> "打包成功"
                                "fail" -> "打包失败"
                                else -> state
                            }
                            val time = obj.get("createTimeString")?.asString ?: ""
                            val userName = obj.get("userName")?.asString ?: ""
                            val downloadUrl = obj.get("downloadUrl")?.asString ?: obj.get("url")?.asString ?: ""
                            val fileNameCn = obj.get("fileNameCn")?.asString ?: ""
                            val instanceHeadline = obj.get("instanceHeadline")?.asString ?: ""
                            packageTableModel.addRow(arrayOf(version, size, type, stateCn, time, userName))
                            packageExtraInfo.add(PackageExtra(downloadUrl, fileNameCn, instanceHeadline, size, time))
                        }
                    }
                }
            } catch (e: Exception) {
                log("❌加载安装包列表失败: ${e.message}")
            }
        }
    }

    private fun startBuild() {
        val sprint = sprintCombo.selectedItem as? SprintItem ?: run {
            log("请先选择迭代")
            return
        }

        val selectedModules = moduleCheckboxes.filter { it.first.isSelected }.map { it.second }
        val buildTypeStr = if (buildTypeCombo.selectedIndex == 0) "release" else "test"
        val buildTypeName = if (buildTypeCombo.selectedIndex == 0) "灰度包" else "测试包"

        buildBtn.isEnabled = false
        buildBtn.text = "打包中..."

        log("========================================")
        log("开始打包流程")
        log("迭代: ${sprint.displayName}")
        log("类型: $buildTypeName")
        if (selectedModules.isNotEmpty()) {
            log("模块: ${selectedModules.joinToString(", ") { it.artifactId }}")
        } else {
            log("模块: 无（仅构建安装包）")
        }
        log("========================================")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val buildArgs = mutableListOf(
                    "package", "build",
                    "--project-unique-id=${sprint.projectUniqueId}",
                    "--platform=${sprint.platform.ifEmpty { "Android" }}",
                    "--product-name=LEOPARD",
                    "--type=$buildTypeStr"
                )

                if (selectedModules.isNotEmpty()) {
                    val moduleIds = selectedModules.joinToString(",") { it.moduleId.toString() }
                    buildArgs.add("--with-module-ids=$moduleIds")
                    log("")
                    log("联合构建: 模块包 + 安装包 ($buildTypeName)")
                    log("模块 IDs: $moduleIds")
                } else {
                    log("")
                    log("构建安装包 ($buildTypeName)")
                }
                log("")

                val pkgOutput = runCli(*buildArgs.toTypedArray())

                val pkgRequestId = extractPackageRequestId(pkgOutput)
                if (pkgRequestId != null) {
                    log("构建请求 ID: $pkgRequestId")
                    // 轮询在独立线程中执行，不阻塞当前线程，多次打包互不干扰
                    val sprintId = sprint.projectUniqueId
                    ApplicationManager.getApplication().executeOnPooledThread {
                        pollBuildStatus(pkgRequestId, sprintId)
                    }
                } else {
                    log("未获取到构建请求 ID，无法轮询")
                }
            } catch (e: Exception) {
                log("打包流程异常: ${e.message}")
            } finally {
                SwingUtilities.invokeLater {
                    buildBtn.isEnabled = true
                    buildBtn.text = "开始打包"
                }
            }
        }
    }

    private fun pollBuildStatus(requestId: String, projectUniqueId: String) {
        log("开始轮询构建状态（每 60 秒一次，最多 15 次）...")
        for (i in 1..15) {
            log("")
            Thread.sleep(60_000)

            log("[轮询 $i/15] 查询构建状态...")
            val output = runCli("package", "group", "--request-id=$requestId")

            val status = parseBuildStatus(output)
            log("[轮询 $i/15] 状态: $status")
            when (status) {
                "SUCCESS" -> {
                    log("")
                    log("========================================")
                    log("               ✅构建成功!")
                    log("========================================")
                    speak("打包成功", "Tingting")
                    loadPackages(projectUniqueId)
                    return
                }
                "FAILED" -> {
                    log("")
                    log("========================================")
                    log("               ❌构建失败!")
                    log("========================================")
                    speak("打包失败", "Meijia")
                    loadPackages(projectUniqueId)
                    return
                }
            }
        }

        log("")
        log("========================================")
        log("⚠️轮询超时（已查询 15 次），请手动查询:https://huoban.alipay.com/subsite/sprint?active=build&devStage=package&projectUniqueId=$projectUniqueId")
        log("========================================")
        speak("打包超时", "Meijia")
        loadPackages(projectUniqueId)
    }

    private fun parseBuildStatus(output: String): String {
        return try {
            val json = JsonParser.parseString(output).asJsonObject
            val resultArray = json.getAsJsonArray("result")
            if (resultArray != null && resultArray.size() > 0) {
                val allStates = resultArray.map {
                    it.asJsonObject.get("state")?.asString ?: "unknown"
                }
                when {
                    allStates.all { it == "success" } -> "SUCCESS"
                    allStates.any { it == "fail" || it == "failed" } -> "FAILED"
                    allStates.any { it == "packing" || it == "building" } -> "打包中"
                    else -> allStates.joinToString(", ")
                }
            } else {
                "BUILDING"
            }
        } catch (e: Exception) {
            "unknown (parse error: ${e.message})"
        }
    }

    private fun speak(text: String, voice: String) {
        try {
            ProcessBuilder("say", "-v", voice, text).start()
        } catch (_: Exception) {}
    }

    private fun extractRequestId(output: String): String? {
        return try {
            val json = JsonParser.parseString(output).asJsonObject
            val resultMes = json.getAsJsonObject("resultMes")
            val result = resultMes?.getAsJsonArray("result")
            result?.get(0)?.asJsonObject?.get("uniqueId")?.asString
        } catch (e: Exception) {
            val regex = Regex("PackageJob_\\d+")
            regex.find(output)?.value
        }
    }

    private fun extractPackageRequestId(output: String): String? {
        return try {
            val json = JsonParser.parseString(output).asJsonObject
            json.get("requestId")?.asString
                ?: json.get("request_id")?.asString
                ?: run {
                    val regex = Regex("InstallPackageJob_\\d+")
                    regex.find(output)?.value
                }
        } catch (e: Exception) {
            val regex = Regex("InstallPackageJob_\\d+|PackageJob_\\d+")
            regex.find(output)?.value
        }
    }

    data class SprintItem(val name: String, val projectUniqueId: String, val version: String, val status: String, val platform: String) {
        val displayName get() = "$name [$status]"
    }

    data class ModuleItem(val moduleId: Long, val artifactId: String, val groupId: String, val displayName: String)
}
