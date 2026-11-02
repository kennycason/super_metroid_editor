package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NativeSpcEmulator
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.coroutines.coroutineContext

/** A single held native voice. The audio device paces rendering; no repeated PCM/attack. */
class HeldSoundPlayer(private val createOutput: () -> Output = { JavaSoundOutput() }) {
    interface Output {
        fun open()
        fun write(bytes: ByteArray)
        fun finish()
        fun close()
    }
    private class JavaSoundOutput : Output {
        private val line = AudioSystem.getSourceDataLine(AudioFormat(32000f, 16, 1, true, false))
        override fun open() { line.open(AudioFormat(32000f, 16, 1, true, false), 2560); line.start() }
        override fun write(bytes: ByteArray) {
            var at = 0
            while (at < bytes.size && line.isOpen) {
                val written = line.write(bytes, at, bytes.size - at)
                if (written <= 0) break
                at += written
            }
        }
        override fun finish() { line.drain() }
        override fun close() { runCatching { line.stop() }; runCatching { line.flush() }; line.close() }
    }
    private class Session(val job: Job?) {
        val released = AtomicBoolean()
        @Volatile var output: Output? = null
        @Volatile var started = false
    }
    @Volatile private var session: Session? = null

    internal suspend fun play(preview: InstrumentAudition.Preview, onStarted: () -> Unit) {
        require(preview.held)
        stop()
        val current = Session(coroutineContext[Job])
        session = current
        try {
            withContext(Dispatchers.IO) {
                NativeSpcEmulator().use { emulator ->
                    emulator.loadFromRam(preview.ram, emptyList(), InstrumentAudition.PLAY_INDEX, playbackWarmupSeconds = 0)
                    ensureActive()
                    if (current.released.get() || session !== current) return@withContext
                    val output = createOutput()
                    current.output = output
                    output.open()
                    ensureActive()
                    if (current.released.get() || session !== current) return@withContext
                    current.started = true
                    onStarted()
                    while (!current.released.get() && session === current) {
                        ensureActive()
                        val pcm = emulator.renderMonoFrames(320) // 10 ms; preserve sample/envelope/filter state.
                        check(pcm.isNotEmpty()) { "Native held preview produced no audio" }
                        output.write(pcmToBytes(pcm))
                    }
                    ensureActive()
                    if (session === current) {
                        emulator.stopMusic()
                        // Native fixed release plus filter tail, then drain the small output buffer.
                        repeat(12) { ensureActive(); output.write(pcmToBytes(emulator.renderMonoFrames(320))) }
                        output.finish()
                    }
                }
            }
        } finally {
            current.output?.let { runCatching { it.close() } }
            if (session === current) session = null
        }
    }

    fun release() {
        val current = session ?: return
        if (!current.started) stop() else current.released.set(true)
    }

    fun stop() {
        val current = session ?: return
        session = null
        current.released.set(true)
        current.job?.cancel()
        current.output?.let { runCatching { it.close() } }
    }
}
