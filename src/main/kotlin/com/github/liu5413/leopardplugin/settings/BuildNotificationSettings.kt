package com.github.liu5413.leopardplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "BuildNotificationSettings", storages = [Storage("leopard-build-notification.xml")])
class BuildNotificationSettings : PersistentStateComponent<BuildNotificationSettings> {

    var enabled: Boolean = true

    override fun getState(): BuildNotificationSettings = this

    override fun loadState(state: BuildNotificationSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): BuildNotificationSettings =
            project.getService(BuildNotificationSettings::class.java)
    }
}