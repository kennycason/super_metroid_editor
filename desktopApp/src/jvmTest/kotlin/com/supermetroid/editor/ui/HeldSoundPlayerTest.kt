package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.*

class HeldSoundPlayerTest {
    private fun preview(): InstrumentAudition.Preview {
        val ram = SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())
        byteArrayOf(0xB3.toByte(), 0x03, 0x57, 0x77, 0x53, 0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte()).copyInto(ram, 0x9000)
        byteArrayOf(0, 0x90.toByte(), 0, 0x90.toByte()).copyInto(ram, 0x6D00)
        return InstrumentAudition.build(ram, NspcRenderer.InstrumentEntry(0, 0x8A, 0xE0, 0, 0x0001, 24),
            NspcSequence.Note(0, 12, NspcSequence.nameToNote("C4"), instrument = 24), held = true)
    }

    @Test fun `held voice sustains across native tie phrases and releases on the stock stop command`() {
        val preview = preview()
        NativeSpcEmulator().use { emulator ->
            emulator.loadFromRam(preview.ram, emptyList(), InstrumentAudition.PLAY_INDEX, playbackWarmupSeconds = 0)
            val pcm = emulator.renderMono(9)
            assertTrue(pcm.any { abs(it.toInt()) > 100 })
            for (from in 32000 until pcm.size - 320 step 320) {
                assertTrue((from until from + 320).sumOf { abs(pcm[it].toInt()).toLong() } > 10000,
                    "A held voice cannot key off/retrigger at tied-block boundaries: frame $from")
            }
            emulator.stopMusic()
            val release = emulator.renderMonoFrames(6400)
            assertTrue(release.takeLast(3200).all { abs(it.toInt()) < 5 }, "Native stop must key off the voice")
        }
    }

    @Test fun `small native buffers preserve exactly the same audio timeline as a whole render`() {
        val preview = preview()
        fun emulator() = NativeSpcEmulator().apply {
            loadFromRam(preview.ram, emptyList(), InstrumentAudition.PLAY_INDEX, playbackWarmupSeconds = 0)
        }
        val expected = emulator().use { it.renderMono(2) }
        val actual = emulator().use { engine -> (0 until 200).flatMap { engine.renderMonoFrames(320).toList() }.toShortArray() }
        assertTrue(expected.contentEquals(actual), "Chunking must retain DSP and filter history")
    }

    internal class Output : HeldSoundPlayer.Output {
        val writes = LinkedBlockingQueue<ByteArray>()
        val advance = Semaphore(0)
        @Volatile var opened = false
        @Volatile var closed = false
        @Volatile var finished = false
        override fun open() { opened = true }
        override fun write(bytes: ByteArray) { if (!closed) { writes.add(bytes); check(advance.tryAcquire(5, TimeUnit.SECONDS) || closed) { "Test output was not advanced" } } }
        override fun finish() { finished = true }
        override fun close() { closed = true; advance.release(100) }
    }

    @Test fun `release drains a native tail and stop closes pending output without restarting it`() = runBlocking {
        val output = Output()
        val player = HeldSoundPlayer { output }
        var started = false
        val preview = preview()
        val playing = launch { player.play(preview) { started = true } }
        withTimeout(5000) { while (output.writes.isEmpty()) delay(1) }
        assertTrue(started); assertTrue(output.opened); assertFalse(output.closed)
        player.release(); output.advance.release(100)
        withTimeout(5000) { playing.join() }
        assertTrue(output.finished); assertTrue(output.closed)
        assertTrue(output.writes.size >= 13)
        val next = launch(start = CoroutineStart.UNDISPATCHED) { player.play(preview) { error("Stopped startup must not play") } }
        player.stop(); next.join()
        assertTrue(next.isCancelled)
    }
}
