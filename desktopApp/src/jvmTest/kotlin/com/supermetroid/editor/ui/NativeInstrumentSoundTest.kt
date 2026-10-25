package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer.InstrumentEntry
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.*

class NativeInstrumentSoundTest {
    private val sound = InstrumentEntry(24, 0xA9, 0xB5, 0x63, 0x0001, 28, 0x6CA8, 0x9000, 0x9000, 144, true)
    private val native = NativeInstrumentSound

    @Test fun `envelope edits change only the intended bitfield and preserve inactive gain and metadata`() {
        for (byte in 0..255) {
            val source = sound.copy(adsr1 = byte, adsr2 = byte)
            for (enabled in listOf(true, false)) {
                val changed = native.envelope(source, enabled)
                assertEquals(enabled, native.usesEnvelope(changed))
                assertEquals(source, changed.copy(adsr1 = source.adsr1))
                assertEquals(byte and 127, changed.adsr1 and 127)
            }
            for (value in 0..31) {
                val attack = native.attack(source, value)
                assertEquals(value.coerceAtMost(15), native.attack(attack))
                assertEquals(byte and 0xF0, attack.adsr1 and 0xF0)
                assertEquals(source, attack.copy(adsr1 = source.adsr1))
                val decay = native.decay(source, value)
                assertEquals(value.coerceAtMost(7), native.decay(decay))
                assertEquals(byte and 0x8F, decay.adsr1 and 0x8F)
                val sustain = native.sustain(source, value)
                assertEquals(value.coerceAtMost(7), native.sustain(sustain))
                assertEquals(byte and 31, sustain.adsr2 and 31)
                val fade = native.fade(source, value)
                assertEquals(value, native.fade(fade))
                assertEquals(byte and 0xE0, fade.adsr2 and 0xE0)
                assertEquals(source, fade.copy(adsr2 = source.adsr2))
            }
        }
    }

    @Test fun `gain modes and values round trip their native fields without rewriting the envelope`() {
        for (mode in NativeInstrumentSound.GainMode.entries) for (value in 0..127) {
            val changed = native.gainValue(native.gainMode(sound, mode), value)
            assertEquals(mode, native.gainMode(changed))
            assertEquals(value.coerceAtMost(if (mode == NativeInstrumentSound.GainMode.FIXED) 127 else 31), native.gainValue(changed))
            assertEquals(sound, changed.copy(gain = sound.gain))
            assertEquals(changed.gain, native.envelope(native.envelope(changed, false), true).gain)
        }
    }

    @Test fun `musical tuning is source relative big endian native pitch with no sample or envelope mutation`() {
        val octave = native.tuning(sound, 256, 1200.0)
        assertEquals(512, octave.nativePitch)
        assertEquals(0x0002, octave.pitchAdj)
        assertEquals(1200.0, native.tuningCents(octave, 256))
        assertEquals(sound, octave.copy(pitchAdj = sound.pitchAdj))
        val down = native.tuning(sound, 256, -1200.0)
        assertEquals(128, down.nativePitch)
        assertEquals(0x8000, down.pitchAdj)
        assertEquals(-1200.0, native.tuningCents(down, 256))
        val fine = native.tuning(sound, 1200, 25.0)
        assertTrue(abs(native.tuningCents(fine, 1200)!! - 25) < 1)
        assertEquals(sound.pitchAdj, native.tuning(octave, 256, 0.0).pitchAdj)
    }

    @Test fun `tuning respects native boundaries and refuses a fabricated baseline`() {
        assertEquals(1, native.tuning(sound, 1, -2400.0).nativePitch)
        assertEquals(65535, native.tuning(sound, 65535, 2400.0).nativePitch)
        assertEquals(65535, native.tuning(sound, 1, 19200.0).nativePitch)
        assertEquals(1, native.tuning(sound, 65535, -19200.0).nativePitch)
        assertNull(native.tuningCents(sound, 0))
        assertNull(native.tuningCents(sound.copy(pitchAdj = 0), 256))
        assertFailsWith<IllegalArgumentException> { native.tuning(sound, 0, 0.0) }
        assertFailsWith<IllegalArgumentException> { native.tuning(sound, 256, Double.NaN) }
    }

    @Test fun `fixed gain guide uses native level and fixed eight unit release`() {
        val levels = native.envelopeGuide(sound.copy(adsr1 = 0, gain = 64), holdFrames = 128)
        assertTrue(levels.take(128).all { it == 1024 })
        assertEquals(1016, levels[128])
        assertEquals(0, levels[255])
        assertTrue(levels.drop(255).all { it == 0 })
        assertEquals(2032, native.envelopeGuide(sound.copy(adsr1 = 0, gain = 127), 1).first())
    }

    @Test fun `maximum native attack and held sustain do not create a conventional release parameter`() {
        val levels = native.envelopeGuide(sound.copy(adsr1 = 0x8F, adsr2 = 0xE0), 1000)
        assertEquals(1024, levels[0])
        assertEquals(2047, levels[1])
        assertTrue(levels.slice(2 until 1000).all { it == 2047 })
        assertEquals(2039, levels[1000])
        assertEquals(0, levels.last())
        assertEquals(256, NativeInstrumentSound.RELEASE_FRAMES)
    }

    @Test fun `rate zero and falling gain start silent while bent rise slows above its knee`() {
        for (gain in listOf(0x80, 0x9F, 0xA0, 0xBF, 0xC0, 0xE0)) {
            assertTrue(native.envelopeGuide(sound.copy(adsr1 = 0, gain = gain), 500).all { it == 0 }, "gain=$gain")
        }
        val linear = native.envelopeGuide(sound.copy(adsr1 = 0, gain = 0xDF), 200)
        val bent = native.envelopeGuide(sound.copy(adsr1 = 0, gain = 0xFF), 200)
        assertEquals(linear[40], bent[40])
        assertTrue(linear[60] > bent[60])
        assertEquals(2047, bent[199])
    }

    @Test fun `envelope guide decays to the chosen level then fades at the held-note sustain rate`() {
        val held = native.envelopeGuide(sound.copy(adsr1 = 0xFF, adsr2 = 0), 32000)
        assertTrue(held.last { it > 0 } <= 2047)
        assertTrue(held[31999] in 248..263, "Native stage changes precede the rate-counter update: ${held[31999]}")
        val fading = native.envelopeGuide(sound.copy(adsr1 = 0xFF, adsr2 = 31), 32000)
        assertEquals(0, fading[31999])
        val slowAttack = native.envelopeGuide(sound.copy(adsr1 = 0x80, adsr2 = 0xE0), 32000)
        assertTrue(slowAttack[31999] < 600)
        for (levels in listOf(held, fading, slowAttack)) assertTrue(levels.all { it in 0..2047 })
    }

    @Test fun `timing readouts use native periods and distinguish hold from sustain fade`() {
        assertEquals(4.096, native.attackSeconds(sound.copy(adsr1 = 0)))
        assertEquals(0.0000625, native.attackSeconds(sound.copy(adsr1 = 15)))
        assertEquals(0.0, native.decaySeconds(sound.copy(adsr2 = 0xE0)))
        assertNull(native.fadeSeconds(sound.copy(adsr2 = 0xE0)))
        assertTrue(native.fadeSeconds(sound.copy(adsr2 = 1))!! > native.fadeSeconds(sound.copy(adsr2 = 31))!!)
    }
}
