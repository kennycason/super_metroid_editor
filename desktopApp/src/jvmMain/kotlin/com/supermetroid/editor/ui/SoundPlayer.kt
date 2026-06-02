package com.supermetroid.editor.ui

import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

class SoundPlayer {
    private var clip: Clip? = null
    private var totalFrames: Long = 0
    var onComplete: (() -> Unit)? = null

    fun play(pcmSamples: ShortArray, sampleRate: Int = 32000, loop: Boolean = false, startFrame: Long = 0) {
        stop()
        if (pcmSamples.isEmpty()) return

        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val byteData = pcmToBytes(pcmSamples)

        try {
            val newClip = AudioSystem.getClip()
            newClip.open(format, byteData, 0, byteData.size)
            totalFrames = newClip.frameLength.toLong()
            if (startFrame > 0) {
                newClip.framePosition = startFrame.toInt().coerceIn(0, newClip.frameLength - 1)
            }
            newClip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP && !loop && clip === newClip) {
                    onComplete?.invoke()
                }
            }
            clip = newClip
            if (loop) newClip.loop(Clip.LOOP_CONTINUOUSLY) else newClip.start()
        } catch (e: Exception) {
            System.err.println("Audio playback error: ${e.message}")
        }
    }

    fun stop() {
        val current = clip
        clip = null
        totalFrames = 0
        current?.let {
            try { if (it.isRunning) it.stop(); it.close() } catch (_: Exception) {}
        }
    }

    fun isActive(): Boolean = clip?.isRunning == true

    fun positionFraction(): Float {
        val c = clip ?: return 0f
        if (totalFrames <= 0) return 0f
        return (c.framePosition.toFloat() / totalFrames).coerceIn(0f, 1f)
    }

    fun seekFraction(fraction: Float) {
        val c = clip ?: return
        val frame = (fraction * totalFrames).toInt().coerceIn(0, c.frameLength - 1)
        c.framePosition = frame
    }
}

fun pcmToBytes(pcm: ShortArray): ByteArray {
    val bytes = ByteArray(pcm.size * 2)
    for (i in pcm.indices) {
        val s = pcm[i].toInt()
        bytes[i * 2] = (s and 0xFF).toByte()
        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return bytes
}

fun exportWav(pcm: ShortArray, sampleRate: Int, file: File) {
    val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
    val bytes = pcmToBytes(pcm)
    val bais = ByteArrayInputStream(bytes)
    val ais = AudioInputStream(bais, format, pcm.size.toLong())
    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file)
}
