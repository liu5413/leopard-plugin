package com.github.liu5413.leopardplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Tag

@Service(Service.Level.PROJECT)
@State(name = "LogViewerSettings", storages = [Storage("leopard-log-viewer.xml")])
class LogViewerSettings : PersistentStateComponent<LogViewerSettings> {

    @Tag("delimiters")
    var delimiters: String = ""

    @Tag("contains")
    var contains: String = ""

    @Tag("history")
    var history: String = ""

    @Tag("lastFileName")
    var lastFileName: String = ""

    override fun getState(): LogViewerSettings = this

    override fun loadState(state: LogViewerSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun getDelimitersList(): List<String> =
        delimiters.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun getContainsList(): List<String> =
        contains.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun getHistoryList(): List<String> =
        history.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    fun saveDelimiters(list: List<String>) {
        delimiters = list.joinToString(",")
    }

    fun saveContains(list: List<String>) {
        contains = list.joinToString(",")
    }

    fun addToHistory(item: String) {
        val trimmed = item.trim()
        if (trimmed.isEmpty()) return
        val list = getHistoryList().toMutableList()
        list.remove(trimmed)
        list.add(0, trimmed)
        history = list.joinToString("\n")
    }

    fun removeFromHistory(item: String) {
        val list = getHistoryList().toMutableList()
        list.remove(item.trim())
        history = list.joinToString("\n")
    }

    companion object {
        fun getInstance(project: Project): LogViewerSettings =
            project.getService(LogViewerSettings::class.java)
    }
}