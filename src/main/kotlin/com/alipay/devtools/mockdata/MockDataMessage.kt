package com.alipay.devtools.mockdata

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * MockData 消息封装
 */
data class MockDataMessage(
    @SerializedName("mode")
    val mode: String = "Normal",

    @SerializedName("actionType")
    val actionType: String? = null,

    @SerializedName("actionSubType")
    val actionSubType: String? = null,

    @SerializedName("data")
    val data: JsonElement? = null,

    @SerializedName("method")
    val method: String? = null,

    @SerializedName("biz")
    val biz: String? = null,

    @SerializedName("isMocked")
    val isMocked: Int? = null
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): MockDataMessage {
            return try {
                Gson().fromJson(json, MockDataMessage::class.java)
            } catch (e: Exception) {
                // 如果解析失败，尝试从原始 JSON 中提取基本信息
                val jsonObj = Gson().fromJson(json, JsonObject::class.java)
                MockDataMessage(
                    mode = jsonObj.get("mode")?.asString ?: "Normal",
                    actionType = jsonObj.get("actionType")?.asString,
                    actionSubType = jsonObj.get("actionSubType")?.asString,
                    data = jsonObj.get("data")?.asJsonObject,
                    method = jsonObj.get("method")?.asString,
                    biz = jsonObj.get("biz")?.asString,
                    isMocked = jsonObj.get("isMocked")?.asInt
                )
            }
        }

        /**
         * 创建设备信息查询消息
         */
        fun deviceInfo(): MockDataMessage = MockDataMessage(
            actionType = "",
            method = "deviceInfo",
            data = JsonObject().apply {
                addProperty("biz", "AntMan_Mac")
                addProperty("version", "5.0.0.20240101")
                addProperty("versionCode", 278)  // 必须 >= 278
                addProperty("name", "mac")  // 非 windows 即可
                add("supportFunction", com.google.gson.JsonArray().apply {
                    add("lbs")
                    add("http")
                    add("rpc")
                    add("config")
                    add("screenShot")
                    add("file")
                    add("clipboard")
                    add("db")
                })
            }
        )

        /**
         * 创建环境切换消息
         */
        fun switchEnv(env: String, loginId: String? = null, password: String? = null): MockDataMessage {
            val data = JsonObject().apply {
                addProperty("env", env)
                if (loginId != null) addProperty("loginId", loginId)
                if (password != null) addProperty("password", password)
                if (loginId != null) addProperty("action", "switchUser")
            }
            return MockDataMessage(
                actionType = "autoLogin",
                data = data
            )
        }

        /**
         * 创建查询登录状态消息
         */
        fun queryLoginStatus(): MockDataMessage = MockDataMessage(
            actionType = "autoLogin",
            actionSubType = "queryLoginStatus"
        )

        /**
         * 创建 SOFA 分组切换消息
         */
        fun switchSofaGroup(groupName: String): MockDataMessage = MockDataMessage(
            actionType = "SOFAGroup",
            actionSubType = "update",
            data = JsonObject().apply { addProperty("data", groupName) }
        )

        /**
         * 创建 RPC Mock 消息
         */
        fun rpcMock(operationType: String, response: JsonObject): MockDataMessage = MockDataMessage(
            actionType = "rpc",
            actionSubType = "update",
            data = JsonObject().apply {
                addProperty("operationType", operationType)
                add("data", response)
            }
        )

        /**
         * 创建 HTTP Mock 消息
         */
        fun httpMock(requestId: String, response: JsonObject): MockDataMessage = MockDataMessage(
            actionType = "http",
            actionSubType = "mockData",
            data = JsonObject().apply {
                addProperty("requestId", requestId)
                add("data", response)
            }
        )

        /**
         * 创建 LBS Mock 消息
         */
        fun lbsMock(latitude: Double, longitude: Double, address: String? = null): MockDataMessage = MockDataMessage(
            actionType = "lbs",
            actionSubType = "update",
            data = JsonObject().apply {
                addProperty("latitude", latitude)
                addProperty("longitude", longitude)
                if (address != null) addProperty("address", address)
            }
        )

        /**
         * 创建 JSAPI Mock 消息
         */
        fun jsapiMock(apiName: String, response: JsonObject): MockDataMessage = MockDataMessage(
            actionType = "jsapi",
            actionSubType = "update",
            data = JsonObject().apply {
                addProperty("key", apiName)
                add("data", response)
            }
        )

        /**
         * 创建配置 Mock 消息
         */
        fun configMock(key: String, value: String): MockDataMessage = MockDataMessage(
            actionType = "config",
            actionSubType = "override",
            data = JsonObject().apply {
                addProperty("key", key)
                addProperty("value", value)
            }
        )

        /**
         * 创建截图消息
         */
        fun screenshot(requestKey: String): MockDataMessage = MockDataMessage(
            actionType = "screenShot",
            data = JsonObject().apply { addProperty("requestKey", requestKey) }
        )

        /**
         * 创建剪贴板读取消息
         */
        fun readClipboard(): MockDataMessage = MockDataMessage(
            actionType = "clipboard",
            actionSubType = "read"
        )

        /**
         * 创建剪贴板写入消息
         */
        fun writeClipboard(text: String): MockDataMessage = MockDataMessage(
            actionType = "clipboard",
            actionSubType = "write",
            data = JsonObject().apply { addProperty("data", text) }
        )

        /**
         * 创建文件列表消息
         */
        fun listFiles(path: String): MockDataMessage = MockDataMessage(
            actionType = "file",
            actionSubType = "list",
            data = JsonObject().apply { addProperty("path", path) }
        )

        /**
         * 创建文件读取消息
         */
        fun readFile(requestKey: String, vararg paths: String): MockDataMessage = MockDataMessage(
            actionType = "file",
            actionSubType = "get",
            data = JsonObject().apply {
                addProperty("requestKey", requestKey)
                val pathArray = com.google.gson.JsonArray()
                paths.forEach { pathArray.add(it) }
                add("pathList", pathArray)
            }
        )

        /**
         * 创建路由跳转消息
         */
        fun navigateTo(appId: String, params: JsonObject? = null): MockDataMessage = MockDataMessage(
            actionType = "router",
            actionSubType = "startApp",
            data = JsonObject().apply {
                addProperty("appId", appId)
                if (params != null) add("params", params)
            }
        )

        /**
         * 创建应用自杀消息
         */
        fun suicide(): MockDataMessage = MockDataMessage(
            actionType = "suicide"
        )

        /**
         * 创建清除数据消息
         */
        fun clearData(): MockDataMessage = MockDataMessage(
            actionType = "clearData"
        )

        /**
         * 创建数据库解密消息
         */
        fun decryptDb(requestKey: String, content: String, key: String): MockDataMessage = MockDataMessage(
            actionType = "db",
            actionSubType = "decrypt",
            data = JsonObject().apply {
                addProperty("requestKey", requestKey)
                addProperty("content", content)
                addProperty("key", key)
            }
        )

        /**
         * 创建语言切换消息
         */
        fun switchLanguage(language: String): MockDataMessage = MockDataMessage(
            actionType = "language",
            data = JsonObject().apply { addProperty("language", language) }
        )

        /**
         * 创建 VoiceOver 刷新消息
         */
        fun refreshVoiceOver(): MockDataMessage = MockDataMessage(
            actionType = "voiceOver",
            actionSubType = "refresh"
        )

        /**
         * 创建多端同步消息
         */
        fun multiPlatformSync(data: List<JsonObject>): MockDataMessage = MockDataMessage(
            actionType = "multiplatform",
            data = JsonObject().apply {
                val array = com.google.gson.JsonArray()
                data.forEach { array.add(it) }
                add("data", array)
            }
        )

        /**
         * 创建 Hook 配置消息
         */
        fun hookConfig(enable: Boolean): MockDataMessage = MockDataMessage(
            actionType = "hookConfig",
            actionSubType = if (enable) "enable" else "disable"
        )

        /**
         * 创建小组件 Mock 消息
         */
        fun widgetMock(data: JsonObject): MockDataMessage = MockDataMessage(
            actionType = "__widget__",
            actionSubType = "update",
            data = data
        )

        /**
         * 创建发送 RPC 消息
         */
        fun sendRpc(
            operationType: String,
            requestClass: String,
            bundleName: String,
            params: Any,
            headers: Map<String, String>? = null
        ): MockDataMessage = MockDataMessage(
            actionType = "send",
            data = JsonObject().apply {
                addProperty("operationType", operationType)
                addProperty("requestClass", requestClass)
                addProperty("bundleName", bundleName)
                add("params", Gson().toJsonTree(params))
                headers?.let {
                    val headerObj = JsonObject()
                    it.forEach { (k, v) -> headerObj.addProperty(k, v) }
                    add("addHeaders", headerObj)
                }
            }
        )

        /**
         * 创建发送广播消息
         * 对应 AntManServer case 27
         */
        fun sendBroadcast(action: String, data: JsonObject? = null): MockDataMessage = MockDataMessage(
            actionType = "notification",
            actionSubType = action,
            data = data
        )
    }
}

/**
 * 设备信息响应
 */
data class DeviceInfo(
    @SerializedName("platform") val platform: String?,
    @SerializedName("systemName") val systemName: String?,
    @SerializedName("systemVersion") val systemVersion: Int?,
    @SerializedName("env") val env: String?,
    @SerializedName("version") val version: String?,
    @SerializedName("appVersion") val appVersion: String?,
    @SerializedName("bundleIdentifier") val bundleIdentifier: String?,
    @SerializedName("displayName") val displayName: String?,
    @SerializedName("screenWidth") val screenWidth: Int?,
    @SerializedName("screenHeight") val screenHeight: Int?,
    @SerializedName("ipAddress") val ipAddress: String?,
    @SerializedName("productId") val productId: String?,
    @SerializedName("icon") val icon: String?
) {
    companion object {
        fun fromMessage(message: MockDataMessage): DeviceInfo? {
            return message.data?.let { Gson().fromJson(it, DeviceInfo::class.java) }
        }
    }
}

/**
 * 用户信息响应
 */
data class UserInfo(
    @SerializedName("loginId") val loginId: String?,
    @SerializedName("userId") val userId: String?,
    @SerializedName("env") val env: String?,
    @SerializedName("password") val password: String?
) {
    companion object {
        fun fromMessage(message: MockDataMessage): UserInfo? {
            return message.data?.let { Gson().fromJson(it, UserInfo::class.java) }
        }
    }
}
