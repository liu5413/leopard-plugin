# Build Notification Design

Add a toggle to enable/disable sound notification when Gradle builds complete in the IDE.

## Architecture

### Components

1. **BuildNotificationSettings** — `PersistentStateComponent` (project-level), persists the on/off toggle state
2. **BuildCompletionListener** — implements `ExternalSystemTaskNotificationListener`, listens for all Gradle build completion events in the IDE
3. **BuildNotificationToggleAction** — toolbar bell icon button, toggles the setting, icon changes with state
4. **SoundPlayer** — utility class to play notification sounds

### Data Flow

```
IDE Gradle build → ExternalSystemTaskNotificationListener.onEnd()
  → Check BuildNotificationSettings.isEnabled
  → Enabled + success → SoundPlayer.play("success.wav")
  → Enabled + failure → SoundPlayer.play("failure.wav")
```

## Sound Effects

Two short WAV files bundled in plugin resources:
- `success.wav` — high-pitched short "ding" sound
- `failure.wav` — low-pitched short "buzz" sound

Played via `javax.sound.sampled.AudioSystem` on a background thread to avoid blocking UI.

## Toggle Button

- Bell icon on the Build toolbar
- Selected state = notifications enabled (highlighted icon)
- Unselected state = notifications disabled (dimmed icon)
- State persisted across IDE restarts via `PersistentStateComponent`

## Registration in plugin.xml

- `BuildNotificationSettings` as `projectService`
- `BuildCompletionListener` as `projectService` (auto-subscribes to `ExternalSystemTaskNotificationListener` EP)
- `BuildNotificationToggleAction` in Build toolbar group

## Dependencies

- Requires `org.jetbrains.plugins.gradle` bundled plugin (already available in IntelliJ IDEA Community)