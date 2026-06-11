# adb-idea 插件分析 (v1.6.20)

## 为什么 adb-idea 能用而我们不行

### 核心差异：获取 ADB 的方式完全不同

| 方面 | adb-idea | 我们的 AdbHelper |
|------|----------|-----------------|
| **ADB 获取方式** | 通过 `AndroidSdkUtils.getDebugBridge(project)` 获取 IDE 内置的 `AndroidDebugBridge` 实例 | 自己拼接 adb 可执行文件路径，通过 `ProcessBuilder` 执行命令行 |
| **设备通信方式** | 通过 `IDevice.executeShellCommand()` ddmlib API 直接通信 | 通过 `Runtime.exec("adb shell ...")` 命令行执行 |
| **设备发现方式** | 通过 `AndroidDebugBridge.getDevices()` 获取已连接设备列表 | 通过 `adb devices` 命令解析文本输出 |
| **项目感知** | 通过 `AndroidFacet` + `AndroidModel` 自动获取当前项目的 packageName | 需要手动传入 packageName |
| **依赖声明** | `depends` 声明了 `org.jetbrains.android` 模块，获得 Android 插件完整 API | 没有声明 Android 插件依赖，无法使用 Android SDK API |

**根本原因**：adb-idea 声明了对 `org.jetbrains.android` 和 `com.intellij.modules.androidstudio` 的依赖，因此可以直接使用 Android Studio 内置的 ADB bridge，走的是 IDE 内部的 ddmlib 通道。我们的插件没有这个依赖，只能走外部命令行，容易遇到 adb 路径找不到、环境变量缺失等问题。

---

## adb-idea 架构总览

```
┌─────────────────────────────────────────────────────┐
│                    Action 层                         │
│  KillAction / StartAction / UninstallAction / ...   │
│  每个 Action 继承 AnAction，调用 AdbFacade 对应方法     │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────┐
│                   AdbFacade                          │
│  单例入口，每个操作 = executeOnDevice(project, cmd)    │
│  内部流程:                                           │
│    1. 检查 Gradle Sync 是否进行中                     │
│    2. 通过 ObjectGraph 获取 DeviceResultFetcher       │
│    3. fetch() 获取设备 + facet + packageName          │
│    4. 在 ExecutorService 线程池中执行 Command          │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────┐
│              DeviceResultFetcher                     │
│  1. ProjectFacetManager.getFacets(AndroidFacet.ID)   │
│  2. 过滤出 isAndroidApp 的模块                        │
│  3. 多模块时弹 ModuleChooserDialog                    │
│  4. AndroidModel.get(facet).applicationId → pkg      │
│  5. Bridge.isReady() 检查 ADB 连接                    │
│  6. 设备数=1 直接用，>1 弹 DeviceChooserDialog         │
│  返回: DeviceResult(devices, facet, packageName)      │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────┐
│                  Bridge / BridgeImpl                  │
│  getAndroidBridge():                                 │
│    AndroidSdkUtils.getDebugBridge(project)            │
│    → 返回 com.android.ddmlib.AndroidDebugBridge       │
│  isReady(): bridge?.isConnected ?: false              │
│  connectedDevices(): bridge?.getDevices()?.asList()   │
└─────────────────────────────────────────────────────┘
```

---

## 关键 API 链路

### 1. 获取 ADB Bridge (最关键的一步)

```kotlin
// BridgeImpl.kt — 这就是 adb-idea 能用的根本原因
private fun getAndroidBridge(): AndroidDebugBridge? {
    return AndroidSdkUtils.getDebugBridge(project)
}
```

**`org.jetbrains.android.sdk.AndroidSdkUtils.getDebugBridge(Project)`** 是 Android Studio 的内部 API，它：
- 自动定位 Android SDK 路径（从 IDE 配置读取，不依赖环境变量）
- 自动初始化 ddmlib 连接
- 返回一个可以直接与设备通信的 `AndroidDebugBridge` 实例

### 2. 获取 PackageName (自动感知项目)

```kotlin
// DeviceResultFetcher.fetch()
val facets = ProjectFacetManager.getInstance(project).getFacets(AndroidFacet.ID)
val facet = getFacet(facets)  // 多模块时弹选择框
val packageName = AndroidModel.get(facet)?.applicationId  // 自动从 build.gradle 读取
```

### 3. 执行 Shell 命令 (ddmlib 内部通道)

```kotlin
// KillCommand — 所有命令都走这个模式
device.executeShellCommand(
    "am force-stop $packageName",
    GenericReceiver(),
    15L, TimeUnit.SECONDS
)
```

`IDevice.executeShellCommand()` 是 ddmlib 的 API，通过 ADB 协议直接与设备通信，**不需要知道 adb 可执行文件在哪**。

---

## 完整功能清单

### Action 列表

| Action 类 | 功能 | 底层 Command |
|-----------|------|-------------|
| `KillAction` | 强制停止应用 | `am force-stop {pkg}` |
| `StartAction` | 启动应用默认 Activity | `am start {debug} {pkg}/{activity}` |
| `RestartAction` | Kill + Start | KillCommand + StartDefaultActivityCommand |
| `UninstallAction` | 卸载应用 | `pm uninstall {pkg}` |
| `ClearDataAction` | 清除应用数据 | `pm clear {pkg}` |
| `ClearDataAndRestartAction` | 清除数据并重启 | ClearDataCommand + RestartPackageCommand |
| `StartWithDebuggerAction` | 启动并附加调试器 | StartDefaultActivityCommand(debug=true) + Debugger.attach() |
| `RestartWithDebuggerAction` | 重启并附加调试器 | Kill + Start(debug=true) + Debugger.attach() |
| `ClearDataAndRestartWithDebuggerAction` | 清除数据重启并调试 | ClearData + Restart + Debug |
| `GrantPermissionsAction` | 授予运行时权限 | 遍历 manifest 中声明的 dangerous 权限，逐个 `pm grant` |
| `RevokePermissionsAction` | 撤销运行时权限 | 遍历并 `pm revoke` |
| `RevokePermissionsAndRestartAction` | 撤销权限并重启 | Revoke + Restart |
| `EnableWifiAction` | 开启 WiFi | `svc wifi enable` |
| `DisableWifiAction` | 关闭 WiFi | `svc wifi disable` |
| `EnableMobileAction` | 开启移动数据 | `svc data enable` |
| `DisableMobileAction` | 关闭移动数据 | `svc data disable` |
| `QuickListAction` | 弹出操作列表 | 显示一个 Popup 包含上述所有操作 |

### UI 组件

| 类 | 功能 |
|----|------|
| `DeviceChooserDialog` | 多设备时弹出设备选择对话框（自定义的，不是 Android Studio 内置的） |
| `MyDeviceChooser` | 设备列表表格，显示设备名、序列号、状态、兼容性 |
| `ModuleChooserDialogHelper` | 多模块项目时弹出模块选择对话框 |
| `NotificationHelper` | 通过 IDE Notification 系统显示操作结果 |

### 支撑组件

| 类 | 功能 |
|----|------|
| `ObjectGraph` | Project Service，懒加载创建 Bridge / DeviceResultFetcher / Preferences |
| `BackwardCompatibleGetter<T>` | 向后兼容抽象类：先尝试新 API，LinkageError 时回退旧 API |
| `UseSameDevicesHelper` | 记住上次选择的设备，避免每次都弹选择框 |
| `ApplicationPreferences` | 应用级偏好设置持久化 |
| `ProjectPreferences` | 项目级偏好设置持久化 |
| `Debugger` | 附加调试器：关闭旧会话 → 等待进程出现 → 创建远程调试配置 → 附加 |
| `GenericReceiver` | IShellOutputReceiver 实现，收集 shell 命令输出行 |

---

## plugin.xml 依赖声明

```xml
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.androidstudio</depends>
<depends>org.jetbrains.android</depends>
```

这三个依赖缺一不可：
- `com.intellij.modules.platform` — IntelliJ 平台基础
- `com.intellij.modules.androidstudio` — 限定只在 Android Studio 中可用
- `org.jetbrains.android` — 提供 `AndroidFacet`、`AndroidSdkUtils`、`AndroidModel` 等 API

---

## 如何抄作业 —— 改造建议

### 方案 A：声明 Android 依赖，使用 ddmlib API（推荐）

1. **plugin.xml 添加依赖**:
   ```xml
   <depends>org.jetbrains.android</depends>
   ```

2. **build.gradle 添加 intellij android 插件**:
   ```kotlin
   intellij {
       plugins.set(listOf("org.jetbrains.android"))
   }
   ```

3. **重写 AdbHelper，核心改为**:
   ```kotlin
   object AdbHelper {
       fun getDebugBridge(project: Project): AndroidDebugBridge? {
           return AndroidSdkUtils.getDebugBridge(project)
       }

       fun getConnectedDevices(project: Project): List<IDevice> {
           return getDebugBridge(project)?.devices?.toList() ?: emptyList()
       }

       fun executeShellCommand(device: IDevice, command: String): String {
           val receiver = CollectingOutputReceiver()
           device.executeShellCommand(command, receiver, 15L, TimeUnit.SECONDS)
           return receiver.output
       }
   }
   ```

4. **优势**:
   - 不需要知道 adb 路径
   - 不需要 ProcessBuilder
   - 与 IDE 设备管理完全打通
   - 多设备选择可以复用 IDE 的 DeviceChooser

### 方案 B：保持命令行方式，但改进路径发现（轻量级）

如果不想加 Android 插件依赖（保持纯 IntelliJ 插件兼容性），改进现有 AdbHelper：

1. **优先从 IDE 的 Android SDK 配置读取**:
   ```kotlin
   // 通过 IntelliJ 的 SDK 管理器查找 Android SDK
   val sdkPath = ProjectRootManager.getInstance(project)
       .projectSdk?.homePath
   ```

2. **尝试反射调用 AndroidSdkUtils**（如果 Android 插件存在的话）:
   ```kotlin
   try {
       val clazz = Class.forName("org.jetbrains.android.sdk.AndroidSdkUtils")
       val method = clazz.getMethod("getDebugBridge", Project::class.java)
       val bridge = method.invoke(null, project) as? AndroidDebugBridge
       // 使用 bridge...
   } catch (e: ClassNotFoundException) {
       // 回退到命令行方式
   }
   ```

### 方案 C：可选依赖（兼顾两者）

```xml
<!-- 可选依赖：有 Android 插件时用 ddmlib，没有时回退命令行 -->
<depends optional="true" config-file="android-features.xml">org.jetbrains.android</depends>
```

在 `android-features.xml` 中注册使用 Android API 的扩展点实现。

---

## adb-idea 的 Shell 命令参考

```bash
# Kill
am force-stop {packageName}

# Start Activity
am start [-D] {packageName}/{activityName}
# -D 标记用于 debug 模式启动

# Uninstall
pm uninstall {packageName}

# Clear Data
pm clear {packageName}

# Check if installed
pm list packages {packageName}  # 有输出=已安装

# Grant permission
pm grant {packageName} {permission}

# Revoke permission
pm revoke {packageName} {permission}

# WiFi
svc wifi enable/disable

# Mobile Data
svc data enable/disable
```

---

## 获取默认 Activity 的方式

```kotlin
// adb-idea 使用 Android Studio 内置 API
val activityName = ApplicationManager.getApplication().runReadAction {
    DefaultActivityLocator(facet).getQualifiedActivityName(device)
}
```

`DefaultActivityLocator` 从 AndroidManifest.xml 解析出 `LAUNCHER` category 的 Activity。

---

## 调试器附加流程

```
1. Debugger(project, device, packageName, coroutineScope)
2. closeOldSessionAndRun() — 终止旧的调试会话
3. RunningProcessesGetter — 等待目标进程出现在设备上
4. 创建 Remote Debug Configuration
5. 附加到设备进程
```

---

## 兼容性处理策略

adb-idea 使用 `BackwardCompatibleGetter<T>` 处理跨版本兼容：

```kotlin
abstract class BackwardCompatibleGetter<T> {
    fun get(): T {
        return try {
            getCurrentImplementation()
        } catch (e: LinkageError) {
            getPreviousImplementation()
        } catch (e: Throwable) {
            if (isReflectiveException(e)) getPreviousImplementation()
            else throw RuntimeException(e)
        }
    }
}
```

还使用了 `jOOR` 反射库处理 API 变更，当新版 Android Studio 改了类/方法签名时不会崩溃。
