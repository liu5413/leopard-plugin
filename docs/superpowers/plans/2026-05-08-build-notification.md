# Build Notification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a toggleable sound notification that plays when Gradle builds complete in the IDE — a "ding" on success, a "buzz" on failure.

**Architecture:** PersistentStateComponent stores the on/off toggle. An ExternalSystemTaskNotificationListener watches all Gradle build completions. A toolbar ToggleAction with a bell icon lets the user toggle the setting. SoundPlayer plays WAV files from resources on a background thread.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (ExternalSystemTaskNotificationListener, PersistentStateComponent, ToggleAction), javax.sound.sampled

---

### Task 1: Create BuildNotificationSettings

**Files:**
- Create: `src/main/kotlin/com/github/liu5413/leopardplugin/settings/BuildNotificationSettings.kt`

- [ ] **Step 1: Create the settings class**

```kotlin
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
```

- [ ] **Step 2: Register in plugin.xml**

Add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<projectService serviceImplementation="com.github.liu5413.leopardplugin.settings.BuildNotificationSettings"/>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/liu5413/leopardplugin/settings/BuildNotificationSettings.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat: add BuildNotificationSettings with persistent toggle state"
```

---

### Task 2: Create SoundPlayer and sound resources

**Files:**
- Create: `src/main/kotlin/com/github/liu5413/leopardplugin/sound/SoundPlayer.kt`
- Create: `src/main/resources/sounds/success.wav`
- Create: `src/main/resources/sounds/failure.wav`

- [ ] **Step 1: Create SoundPlayer utility**

```kotlin
package com.github.liu5413.leopardplugin.sound

import com.intellij.openapi.diagnostic.thisLogger
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

object SoundPlayer {

    fun playSuccess() {
        play("/sounds/success.wav")
    }

    fun playFailure() {
        play("/sounds/failure.wav")
    }

    private fun play(resourcePath: String) {
        try {
            val stream = javaClass.getResourceAsStream(resourcePath)
                ?: run {
                    thisLogger().warn("Sound resource not found: $resourcePath")
                    return
                }
            val audioInputStream = AudioSystem.getAudioInputStream(stream)
            val clip = AudioSystem.getClip()
            clip.open(audioInputStream)
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    clip.close()
                }
            }
            clip.start()
        } catch (e: Exception) {
            thisLogger().warn("Failed to play sound: $resourcePath", e)
        }
    }
}
```

- [ ] **Step 2: Generate WAV sound files**

Create a small Kotlin script to generate the WAV files programmatically (44100 Hz, 16-bit, mono):

```kash
mkdir -p src/main/resources/sounds
```

Run this Kotlin/JVM one-liner to generate `success.wav` (high-pitched ding, 880Hz, 300ms):

```kotlin
// Generate success.wav - high-pitched ding (880Hz, 300ms)
import java.io.*
import javax.sound.sampled.*

val sampleRate = 44100f
val duration = 0.3 // seconds
val frequency = 880.0 // Hz
val numSamples = (sampleRate * duration).toInt()
val buffer = ByteArray(numSamples * 2)
for (i in 0 until numSamples) {
    val t = i.toDouble() / sampleRate
    val envelope = if (t < 0.01) t / 0.01 else Math.exp(-t * 8.0)
    val sample = (Math.sin(2.0 * Math.PI * frequency * t) * envelope * Short.MAX_VALUE * 0.5).toInt()
    val clamped = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    buffer[i * 2] = (clamped.toInt() and 0xff).toByte()
    buffer[i * 2 + 1] = (clamped.toInt() shr 8 and 0xff).toByte()
}
val format = AudioFormat(sampleRate, 16, 1, true, false)
val ais = AudioInputStream(ByteArrayInputStream(buffer), format, numSamples.toLong())
AudioSystem.write(ais, AudioFileFormat.Type.WAVE, File("src/main/resources/sounds/success.wav"))
```

Run this to generate `failure.wav` (low-pitched buzz, 220Hz, 500ms):

```kotlin
// Generate failure.wav - low-pitched buzz (220Hz, 500ms)
import java.io.*
import javax.sound.sampled.*

val sampleRate = 44100f
val duration = 0.5 // seconds
val frequency = 220.0 // Hz
val numSamples = (sampleRate * duration).toInt()
val buffer = ByteArray(numSamples * 2)
for (i in 0 until numSamples) {
    val t = i.toDouble() / sampleRate
    val envelope = if (t < 0.01) t / 0.01 else Math.exp(-t * 4.0)
    val sawtooth = 2.0 * (t * frequency - Math.floor(t * frequency + 0.5))
    val sample = (sawtooth * envelope * Short.MAX_VALUE * 0.4).toInt()
    val clamped = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    buffer[i * 2] = (clamped.toInt() and 0xff).toByte()
    buffer[i * 2 + 1] = (clamped.toInt() shr 8 and 0xff).toByte()
}
val format = AudioFormat(sampleRate, 16, 1, true, false)
val ais = AudioInputStream(ByteArrayInputStream(buffer), format, numSamples.toLong())
AudioSystem.write(ais, AudioFileFormat.Type.WAVE, File("src/main/resources/sounds/failure.wav"))
```

Use `kotlinc -script` or a standalone Kotlin script to run these. Alternatively, use `jshell`:

```bash
# Generate success.wav
mkdir -p src/main/resources/sounds
jshell -q <<'EOF'
import java.io.*; import javax.sound.sampled.*; float sr=44100f; int n=(int)(sr*0.3); byte[] b=new byte[n*2]; for(int i=0;i<n;i++){double t=i/sr;double e=t<0.01?t/0.01:Math.exp(-t*8);short s=(short)(Math.sin(2*Math.PI*880*t)*e*Short.MAX_VALUE*0.5);b[i*2]=(byte)(s&0xff);b[i*2+1]=(byte)((s>>8)&0xff);} AudioInputStream a=new AudioInputStream(new ByteArrayInputStream(b),new AudioFormat(sr,16,1,true,false),n); AudioSystem.write(a,AudioFileFormat.Type.WAVE,new File("src/main/resources/sounds/success.wav")); /exit
EOF

# Generate failure.wav
jshell -q <<'EOF'
import java.io.*; import javax.sound.sampled.*; float sr=44100f; int n=(int)(sr*0.5); byte[] b=new byte[n*2]; for(int i=0;i<n;i++){double t=i/sr;double e=t<0.01?t/0.01:Math.exp(-t*4);double saw=2*(t*220-Math.floor(t*220+0.5));short s=(short)(saw*e*Short.MAX_VALUE*0.4);b[i*2]=(byte)(s&0xff);b[i*2+1]=(byte)((s>>8)&0xff);} AudioInputStream a=new AudioInputStream(new ByteArrayInputStream(b),new AudioFormat(sr,16,1,true,false),n); AudioSystem.write(a,AudioFileFormat.Type.WAVE,new File("src/main/resources/sounds/failure.wav")); /exit
EOF
```

- [ ] **Step 3: Verify WAV files exist**

```bash
ls -la src/main/resources/sounds/
```

Expected: `success.wav` and `failure.wav` files present.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/github/liu5413/leopardplugin/sound/SoundPlayer.kt src/main/resources/sounds/success.wav src/main/resources/sounds/failure.wav
git commit -m "feat: add SoundPlayer utility and WAV sound resources"
```

---

### Task 3: Create BuildCompletionListener

**Files:**
- Create: `src/main/kotlin/com/github/liu5413/leopardplugin/listener/BuildCompletionListener.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add Gradle plugin dependency in build.gradle.kts**

Add `bundledPlugin("com.intellij.gradle")` inside the `intellijPlatform` block:

```kotlin
intellijPlatform {
    intellijIdeaCommunity("2025.1")
    bundledPlugin("org.jetbrains.plugins.terminal")
    bundledPlugin("com.intellij.gradle")
    testFramework(TestFrameworkType.Platform)
}
```

- [ ] **Step 2: Add Gradle plugin dependency in plugin.xml**

Add after the existing `<depends>` lines:

```xml
<depends>com.intellij.gradle</depends>
```

- [ ] **Step 3: Create BuildCompletionListener**

```kotlin
package com.github.liu5413.leopardplugin.listener

import com.github.liu5413.leopardplugin.settings.BuildNotificationSettings
import com.github.liu5413.leopardplugin.sound.SoundPlayer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

class BuildCompletionListener {

    fun onTaskEnd(id: ExternalSystemTaskId, event: ExternalSystemTaskNotificationEvent) {
        if (id.type != ExternalSystemTaskType.EXECUTE_TASK) return

        val project = findProject(id) ?: return
        val settings = BuildNotificationSettings.getInstance(project)
        if (!settings.enabled) return

        val hasFailures = event.description?.contains("FAIL", ignoreCase = true) == true

        ApplicationManager.getApplication().executeOnPooledThread {
            if (hasFailures) {
                SoundPlayer.playFailure()
            } else {
                SoundPlayer.playSuccess()
            }
        }
    }

    private fun findProject(id: ExternalSystemTaskId): Project? {
        val ideProject = id.findExternalProjectPath()?.let { path ->
            ProjectManager.getInstance().openProjects.firstOrNull {
                it.basePath == path
            }
        }
        return ideProject
    }
}
```

- [ ] **Step 4: Register listener in plugin.xml**

Add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<externalSystemTaskNotificationListener
    implementation="com.github.liu5413.leopardplugin.listener.BuildCompletionListenerAdapter"/>
```

- [ ] **Step 5: Create adapter class for IntelliJ's listener interface**

IntelliJ's `externalSystemTaskNotificationListener` extension point requires implementing `ExternalSystemTaskNotificationListener`. Create an adapter:

```kotlin
package com.github.liu5413.leopardplugin.listener

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener

class BuildCompletionListenerAdapter : ExternalSystemTaskNotificationListener {

    private val listener = BuildCompletionListener()

    override fun onEnd(id: ExternalSystemTaskId, event: ExternalSystemTaskNotificationEvent) {
        listener.onTaskEnd(id, event)
    }

    // No-op implementations for other callbacks
    override fun onStart(id: ExternalSystemTaskId, event: ExternalSystemTaskNotificationEvent) {}
    override fun onStatusChange(event: ExternalSystemTaskNotificationEvent) {}
    override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {}
    override fun beforeTaskExecution(id: ExternalSystemTaskId, event: ExternalSystemTaskNotificationEvent) {}
    override fun onTaskFailure(id: ExternalSystemTaskId, event: ExternalSystemTaskNotificationEvent, callback: TaskCallback) {}
    override fun onTaskSuccess(id: ExternalSystemTaskId, event: ExternalSystemTaskNotificationEvent, callback: TaskCallback) {
        listener.onTaskEnd(id, event)
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/liu5413/leopardplugin/listener/ build.gradle.kts src/main/resources/META-INF/plugin.xml
git commit -m "feat: add BuildCompletionListener for Gradle build completion detection"
```

---

### Task 4: Create BuildNotificationToggleAction with bell icons

**Files:**
- Create: `src/main/kotlin/com/github/liu5413/leopardplugin/actions/BuildNotificationToggleAction.kt`
- Create: `src/main/resources/icons/bell.svg`
- Create: `src/main/resources/icons/bell_dark.svg`
- Create: `src/main/resources/icons/bellMuted.svg`
- Create: `src/main/resources/icons/bellMuted_dark.svg`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create bell icon SVGs**

Create `src/main/resources/icons/bell.svg` (bright bell for light theme):

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
  <path fill="#6C707E" d="M8 1a4.5 4.5 0 0 0-4.5 4.5v3.19l-.95 1.9A1 1 0 0 0 3.45 12.5h2.55a2 2 0 0 0 4 0h2.55a1 1 0 0 0 .89-1.45L12.5 8.69V5.5A4.5 4.5 0 0 0 8 1zm1 11.5a1 1 0 0 1-2 0h2zM4.5 5.5a3.5 3.5 0 1 1 7 0v3.31l.95 1.9a.5.5 0 0 1-.45.72H4a.5.5 0 0 1-.45-.72l.95-1.9V5.5z"/>
</svg>
```

Create `src/main/resources/icons/bell_dark.svg` (bright bell for dark theme):

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
  <path fill="#CED0D6" d="M8 1a4.5 4.5 0 0 0-4.5 4.5v3.19l-.95 1.9A1 1 0 0 0 3.45 12.5h2.55a2 2 0 0 0 4 0h2.55a1 1 0 0 0 .89-1.45L12.5 8.69V5.5A4.5 4.5 0 0 0 8 1zm1 11.5a1 1 0 0 1-2 0h2zM4.5 5.5a3.5 3.5 0 1 1 7 0v3.31l.95 1.9a.5.5 0 0 1-.45.72H4a.5.5 0 0 1-.45-.72l.95-1.9V5.5z"/>
</svg>
```

Create `src/main/resources/icons/bellMuted.svg` (dimmed bell for light theme):

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
  <path fill="#BDBDBD" d="M8 1a4.5 4.5 0 0 0-4.5 4.5v3.19l-.95 1.9A1 1 0 0 0 3.45 12.5h2.55a2 2 0 0 0 4 0h2.55a1 1 0 0 0 .89-1.45L12.5 8.69V5.5A4.5 4.5 0 0 0 8 1zm1 11.5a1 1 0 0 1-2 0h2zM4.5 5.5a3.5 3.5 0 1 1 7 0v3.31l.95 1.9a.5.5 0 0 1-.45.72H4a.5.5 0 0 1-.45-.72l.95-1.9V5.5z"/>
  <line x1="3" y1="3" x2="13" y2="13" stroke="#BDBDBD" stroke-width="1.5" stroke-linecap="round"/>
</svg>
```

Create `src/main/resources/icons/bellMuted_dark.svg` (dimmed bell for dark theme):

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
  <path fill="#5A5A5A" d="M8 1a4.5 4.5 0 0 0-4.5 4.5v3.19l-.95 1.9A1 1 0 0 0 3.45 12.5h2.55a2 2 0 0 0 4 0h2.55a1 1 0 0 0 .89-1.45L12.5 8.69V5.5A4.5 4.5 0 0 0 8 1zm1 11.5a1 1 0 0 1-2 0h2zM4.5 5.5a3.5 3.5 0 1 1 7 0v3.31l.95 1.9a.5.5 0 0 1-.45.72H4a.5.5 0 0 1-.45-.72l.95-1.9V5.5z"/>
  <line x1="3" y1="3" x2="13" y2="13" stroke="#5A5A5A" stroke-width="1.5" stroke-linecap="round"/>
</svg>
```

- [ ] **Step 2: Create BuildNotificationToggleAction**

```kotlin
package com.github.liu5413.leopardplugin.actions

import com.github.liu5413.leopardplugin.settings.BuildNotificationSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader

class BuildNotificationToggleAction : ToggleAction(
    "Build Sound Notification",
    "Toggle sound notification on Gradle build completion",
    IconLoader.getIcon("/icons/bell.svg", BuildNotificationToggleAction::class.java)
) {

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return BuildNotificationSettings.getInstance(project).enabled
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        BuildNotificationSettings.getInstance(project).enabled = state
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        val enabled = project?.let { BuildNotificationSettings.getInstance(it).enabled } ?: false
        if (enabled) {
            e.presentation.icon = IconLoader.getIcon("/icons/bell.svg", BuildNotificationToggleAction::class.java)
        } else {
            e.presentation.icon = IconLoader.getIcon("/icons/bellMuted.svg", BuildNotificationToggleAction::class.java)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
```

- [ ] **Step 3: Register action in plugin.xml**

Add inside `<actions>`, after the last `</action>`:

```xml
<action id="BuildNotificationToggleAction"
        class="com.github.liu5413.leopardplugin.actions.BuildNotificationToggleAction"
        text="Build Sound Notification"
        description="Toggle sound notification on Gradle build completion">
    <add-to-group group-id="MainToolbarRight" anchor="first"/>
    <add-to-group group-id="NavBarToolBar" anchor="last"/>
</action>
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/github/liu5413/leopardplugin/actions/BuildNotificationToggleAction.kt src/main/resources/icons/bell.svg src/main/resources/icons/bell_dark.svg src/main/resources/icons/bellMuted.svg src/main/resources/icons/bellMuted_dark.svg src/main/resources/META-INF/plugin.xml
git commit -m "feat: add BuildNotificationToggleAction with bell icon toggle"
```

---

### Task 5: Build and verify

- [ ] **Step 1: Build the plugin**

```bash
./gradlew buildPlugin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run IDE sandbox to test**

```bash
./gradlew runIde
```

Expected: IDE launches, bell icon appears in toolbar, clicking toggles state.

- [ ] **Step 3: Final commit if any fixes needed**

```bash
git add -A
git commit -m "fix: address build/test issues from verification"
```