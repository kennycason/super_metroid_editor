package com.supermetroid.editor.ui

import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener
import kotlin.test.*

/** A device that accepts start() before its running flag changes, like asynchronous Java Sound outputs. */
internal class DeferredSoundClip(
    private val openError: Exception? = null,
    private val startError: Exception? = null,
) {
    var running = false
    var started = false
    var closed = false
    var position = 0
    private val listeners = mutableListOf<LineListener>()
    val clip: Clip = Proxy.newProxyInstance(Clip::class.java.classLoader, arrayOf(Clip::class.java)) { _, method, args ->
        when (method.name) {
            "open" -> { openError?.let { throw it }; null }
            "start", "loop" -> { startError?.let { throw it }; started = true; null }
            "close" -> { closed = true; running = false; null }
            "stop" -> { running = false; null }
            "isRunning", "isActive" -> running
            "isOpen" -> !closed
            "getFrameLength" -> 32000
            "getFramePosition" -> position
            "getLongFramePosition" -> position.toLong()
            "setFramePosition" -> { position = args!![0] as Int; null }
            "addLineListener" -> { listeners += args!![0] as LineListener; null }
            "toString" -> "DeferredSoundClip"
            else -> error("Unexpected Clip call: ${method.name}")
        }
    } as Clip

    fun complete() {
        running = false
        position = 32000
        val event = LineEvent(clip, LineEvent.Type.STOP, position.toLong())
        listeners.forEach { it.update(event) }
    }
}

class SoundPlayerTest {
    @Test fun `accepted asynchronous starts are not reported as audio output failures`() {
        val device = DeferredSoundClip()
        val player = SoundPlayer { device.clip }
        var completed = 0
        player.onComplete = { completed++ }
        assertEquals(SoundPlayer.PlaybackStart.STARTED, player.play(shortArrayOf(1, 2, 3)))
        assertTrue(device.started)
        assertFalse(player.isActive(), "The output has not delivered its running-state update yet")
        assertEquals(0, completed)
        device.running = true
        assertTrue(player.isActive())
        device.complete()
        assertEquals(1, completed)
        player.stop()
        assertTrue(device.closed)
    }

    @Test fun `real open and start failures close the device and report failure explicitly`() {
        for (device in listOf(DeferredSoundClip(openError = IllegalStateException("No device")),
            DeferredSoundClip(startError = IllegalStateException("Device disconnected")))) {
            val player = SoundPlayer { device.clip }
            assertEquals(SoundPlayer.PlaybackStart.OUTPUT_UNAVAILABLE, player.play(shortArrayOf(1)))
            assertTrue(device.closed)
            assertFalse(player.isActive())
        }
    }

    @Test fun `empty audio is distinct from a missing output and stops the previous clip`() {
        val device = DeferredSoundClip()
        var opens = 0
        val player = SoundPlayer { opens++; device.clip }
        assertEquals(SoundPlayer.PlaybackStart.EMPTY, player.play(ShortArray(0)))
        assertEquals(0, opens)
        player.play(shortArrayOf(1))
        assertEquals(SoundPlayer.PlaybackStart.EMPTY, player.play(ShortArray(0)))
        assertEquals(1, opens)
        assertTrue(device.closed)
    }

    @Test fun `late completion from a replaced clip cannot complete the new preview`() {
        val devices = listOf(DeferredSoundClip(), DeferredSoundClip())
        var next = 0
        val player = SoundPlayer { devices[next++].clip }
        var completed = 0
        player.onComplete = { completed++ }
        player.play(shortArrayOf(1))
        player.play(shortArrayOf(2))
        devices[0].complete()
        assertEquals(0, completed)
        devices[1].complete()
        assertEquals(1, completed)
        player.stop()
    }
}
