package com.github.liu5413.leopardplugin.sound

import com.intellij.openapi.diagnostic.thisLogger
import java.awt.Toolkit
import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

object SoundPlayer {

    fun playSuccess() {
        playWav("/sounds/success.wav") ?: beep()
    }

    fun playFailure() {
        playWav("/sounds/failure.wav") ?: beep()
    }

    private fun playWav(resourcePath: String): Boolean {
        try {
            val stream = javaClass.getResourceAsStream(resourcePath)
                ?: run {
                    thisLogger().warn("Sound resource not found: $resourcePath")
                    return false
                }
            val audioInputStream = AudioSystem.getAudioInputStream(BufferedInputStream(stream))
            val clip = AudioSystem.getClip()
            clip.open(audioInputStream)
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    clip.close()
                }
            }
            clip.start()
            return true
        } catch (e: Exception) {
            thisLogger().warn("Failed to play WAV sound: $resourcePath", e)
            return false
        }
    }

    private fun beep() {
        try {
            Toolkit.getDefaultToolkit().beep()
        } catch (e: Exception) {
            thisLogger().warn("Failed to play beep", e)
        }
    }
}