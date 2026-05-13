package com.github.liu5413.leopardplugin.toolWindow

import com.github.liu5413.leopardplugin.settings.LogViewerSettings
import com.github.liu5413.leopardplugin.utils.LogFilter
import com.github.liu5413.leopardplugin.utils.LogLevel
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants

class PreviewLogPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val settings = LogViewerSettings.getInstance(project)

    private val fileComboBox = ComboBox<String>()
    private val delimiterRows = mutableListOf<KeywordRow>()
    private val containsRows = mutableListOf<KeywordRow>()
    private val delimitersRowsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val containsRowsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val consoleView: ConsoleViewImpl

    /**
     * Custom ConsoleViewContentType for delimiter background colors.
     * Matches Main.kt's bgList: RED_BG, GREEN_BG, YELLOW_BG, BLUE_BG, PURPLE_BG, CYAN_BG
     */
    private val delimiterContentTypes: List<ConsoleViewContentType>

    /** Dark green for DEBUG */
    private val debugContentType: ConsoleViewContentType

    /** Yellow for WARN */
    private val warnContentType: ConsoleViewContentType

    /** Red for ERROR */
    private val errorContentType: ConsoleViewContentType

    /** Blue for default/unknown (INFO/VERBOSE use default color) */
    private val blueContentType: ConsoleViewContentType

    init {
        // Background colors for delimiters — matching Main.kt bgList order
        val bgColors = listOf(
            Color(255, 200, 200),  // RED_BG    (light red)
            Color(200, 255, 200),  // GREEN_BG  (light green)
            Color(255, 255, 200),  // YELLOW_BG (light yellow)
            Color(200, 220, 255),  // BLUE_BG   (light blue)
            Color(230, 200, 255),  // PURPLE_BG (light purple)
            Color(200, 255, 255),  // CYAN_BG   (light cyan)
        )

        delimiterContentTypes = bgColors.mapIndexed { i, bg ->
            createContentType("DELIMITER_BG_$i", TextAttributes(null, bg, null, null, Font.PLAIN))
        }

        // Dark green for DEBUG (D/)
        debugContentType = createContentType(
            "LOG_DEBUG",
            TextAttributes(Color(0, 128, 0), null, null, null, Font.PLAIN)
        )

        // Yellow for WARN (W/)
        warnContentType = createContentType(
            "LOG_WARN",
            TextAttributes(Color(255, 204, 0), null, null, null, Font.PLAIN)
        )

        // Red for ERROR (E/)
        errorContentType = createContentType(
            "LOG_ERROR",
            TextAttributes(Color(204, 0, 0), null, null, null, Font.PLAIN)
        )

        // Blue for default/unknown
        blueContentType = createContentType(
            "LOG_BLUE",
            TextAttributes(Color(0, 102, 204), null, null, null, Font.PLAIN)
        )

        // File selector
        val filePanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Log File:"))
            add(fileComboBox)
            add(JButton("↻ Refresh").apply {
                addActionListener { refreshFileList() }
            })
        }

        // Delimiters section (left column)
        val delimitersSection = JPanel(BorderLayout(4, 4)).apply {
            val header = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JBLabel("Delimiters:"))
                add(JButton("+").apply {
                    addActionListener { addDelimiterRow("") }
                })
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(
                delimitersRowsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            ), BorderLayout.CENTER)
        }

        // Contains section (right column)
        val containsSection = JPanel(BorderLayout(4, 4)).apply {
            val header = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JBLabel("Contains:"))
                add(JButton("+").apply {
                    addActionListener { addContainsRow("") }
                })
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(
                containsRowsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            ), BorderLayout.CENTER)
        }

        // Two columns side by side
        val columnsPanel = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.BOTH
                weightx = 1.0
                weighty = 1.0
                insets = Insets(0, 4, 0, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0
            add(delimitersSection, gbc)
            gbc.gridx = 1; gbc.gridy = 0
            add(containsSection, gbc)
        }

        // Console
        consoleView = ConsoleViewImpl(project, true)

        // Buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Filter").apply {
                addActionListener { doFilter() }
            })
            add(JButton("Clear").apply {
                addActionListener { consoleView.clear() }
            })
        }

        // Top config area
        val configPanel = JPanel(BorderLayout()).apply {
            add(filePanel, BorderLayout.NORTH)
            add(columnsPanel, BorderLayout.CENTER)
        }

        // Layout
        add(configPanel, BorderLayout.NORTH)
        add(buttonPanel, BorderLayout.WEST)
        add(consoleView.component, BorderLayout.CENTER)

        // Init
        loadSavedState()
        refreshFileList()
    }

    /**
     * Create a custom ConsoleViewContentType with the given TextAttributes.
     * This allows arbitrary foreground/background colors in the console.
     */
    private fun createContentType(name: String, attrs: TextAttributes): ConsoleViewContentType {
        val key = TextAttributesKey.createTextAttributesKey(name)
        com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.setAttributes(key, attrs)
        return ConsoleViewContentType(name, key)
    }

    private fun refreshFileList() {
        val baseDir = project.basePath ?: return
        val files = File(baseDir).listFiles()
            ?.filter { it.name.startsWith("log") && it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            ?: emptyList()

        fileComboBox.model = DefaultComboBoxModel(files.toTypedArray())

        val lastFile = settings.lastFileName
        if (lastFile.isNotEmpty() && files.contains(lastFile)) {
            fileComboBox.selectedItem = lastFile
        } else if (files.isNotEmpty()) {
            fileComboBox.selectedIndex = 0
        }
    }

    private fun addDelimiterRow(initialValue: String) {
        val row = createKeywordRow(initialValue, delimitersRowsPanel, delimiterRows)
        delimiterRows.add(row)
        delimitersRowsPanel.add(row.panel)
        delimitersRowsPanel.revalidate()
        delimitersRowsPanel.repaint()
    }

    private fun addContainsRow(initialValue: String) {
        val row = createKeywordRow(initialValue, containsRowsPanel, containsRows)
        containsRows.add(row)
        containsRowsPanel.add(row.panel)
        containsRowsPanel.revalidate()
        containsRowsPanel.repaint()
    }

    private data class KeywordRow(val panel: JPanel, val textField: JTextField)

    private fun createKeywordRow(
        initialValue: String,
        parentPanel: JPanel,
        rowsList: MutableList<KeywordRow>
    ): KeywordRow {
        val textField = JTextField(initialValue, 25)

        val historyCombo = ComboBox<String>().apply {
            refreshHistory(this)
            addActionListener { e ->
                if (e.actionCommand == "comboBoxChanged") {
                    val selected = selectedItem as? String ?: return@addActionListener
                    textField.text = selected
                }
            }
        }

        val deleteHistoryBtn = JButton("✕").apply {
            toolTipText = "Delete from history"
            margin = Insets(0, 2, 0, 2)
            addActionListener {
                val selected = historyCombo.selectedItem as? String ?: return@addActionListener
                settings.removeFromHistory(selected)
                refreshHistory(historyCombo)
            }
        }

        val removeRowBtn = JButton("−").apply {
            toolTipText = "Remove this row"
            margin = Insets(0, 2, 0, 2)
            addActionListener {
                val row = rowsList.find { it.textField == textField } ?: return@addActionListener
                parentPanel.remove(row.panel)
                rowsList.remove(row)
                parentPanel.revalidate()
                parentPanel.repaint()
            }
        }

        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2)).apply {
            add(textField)
            add(historyCombo)
            add(deleteHistoryBtn)
            add(removeRowBtn)
        }

        textField.addActionListener {
            val text = textField.text.trim()
            if (text.isNotEmpty()) {
                settings.addToHistory(text)
                refreshHistory(historyCombo)
            }
        }

        return KeywordRow(panel, textField)
    }

    private fun refreshHistory(combo: ComboBox<String>) {
        val model = DefaultComboBoxModel(settings.getHistoryList().toTypedArray())
        combo.model = model
    }

    private fun doFilter() {
        val fileName = fileComboBox.selectedItem as? String ?: return
        val baseDir = project.basePath ?: return
        val file = File(baseDir, fileName)
        if (!file.exists()) return

        val delimiters = delimiterRows.map { it.textField.text.trim() }.filter { it.isNotEmpty() }
        val contains = containsRows.map { it.textField.text.trim() }.filter { it.isNotEmpty() }

        // Save state
        settings.saveDelimiters(delimiters)
        settings.saveContains(contains)
        settings.lastFileName = fileName
        (delimiters + contains).forEach { settings.addToHistory(it) }

        // Read and filter
        val lines = file.readLines()
        val filtered = LogFilter.filterLines(lines, delimiters, contains)

        // Print to console with user-specified color logic:
        //   I/ or I  → white
        //   D/ or D  → dark green
        //   W/ or W  → yellow
        //   E/ or E or Exception/Crash → red
        //   default  → blue
        consoleView.clear()
        for ((index, line) in filtered) {
            val msg = "行 ${index + 1}: $line"
            val segments = LogFilter.segmentString(msg, delimiters)
            val level = LogFilter.detectLevel(msg)

            for (segment in segments) {
                if (segment.isDelimiter) {
                    // Cycle background colors by delimiter index
                    val ct = delimiterContentTypes[segment.delimiterIndex % delimiterContentTypes.size]
                    consoleView.print(segment.text, ct)
                } else {
                    val ct = when (level) {
                        LogLevel.INFO -> ConsoleViewContentType.NORMAL_OUTPUT
                        LogLevel.VERBOSE -> blueContentType
                        LogLevel.DEBUG -> debugContentType
                        LogLevel.WARN -> warnContentType
                        LogLevel.ERROR -> errorContentType
                    }
                    consoleView.print(segment.text, ct)
                }
            }
            consoleView.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
        }
    }

    private fun loadSavedState() {
        settings.getDelimitersList().forEach { addDelimiterRow(it) }
        settings.getContainsList().forEach { addContainsRow(it) }
        if (delimiterRows.isEmpty()) addDelimiterRow("")
        if (containsRows.isEmpty()) addContainsRow("")
    }
}