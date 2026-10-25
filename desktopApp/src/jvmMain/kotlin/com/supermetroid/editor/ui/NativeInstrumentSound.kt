package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer.InstrumentEntry
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Native DSP controls. Stored bytes remain the sole saved/exported sound representation. */
internal object NativeInstrumentSound {
    enum class GainMode(val label: String, val bits: Int) {
        FIXED("Fixed level", 0), FALL("Linear fall", 0x80),
        EXPONENTIAL_FALL("Exponential fall", 0xA0), RISE("Linear rise", 0xC0), BENT_RISE("Bent rise", 0xE0)
    }

    fun usesEnvelope(entry: InstrumentEntry) = entry.adsr1 and 0x80 != 0
    fun attack(entry: InstrumentEntry) = entry.adsr1 and 15
    fun decay(entry: InstrumentEntry) = (entry.adsr1 ushr 4) and 7
    fun sustain(entry: InstrumentEntry) = entry.adsr2 ushr 5
    fun fade(entry: InstrumentEntry) = entry.adsr2 and 31
    fun gainMode(entry: InstrumentEntry) = if (entry.gain < 0x80) GainMode.FIXED
        else GainMode.entries.first { it.bits == entry.gain and 0xE0 }
    fun gainValue(entry: InstrumentEntry) = entry.gain and if (entry.gain < 0x80) 127 else 31

    fun envelope(entry: InstrumentEntry, enabled: Boolean) = entry.copy(adsr1 = (entry.adsr1 and 0x7F) or if (enabled) 0x80 else 0)
    fun attack(entry: InstrumentEntry, value: Int) = entry.copy(adsr1 = (entry.adsr1 and 0xF0) or value.coerceIn(0, 15))
    fun decay(entry: InstrumentEntry, value: Int) = entry.copy(adsr1 = (entry.adsr1 and 0x8F) or (value.coerceIn(0, 7) shl 4))
    fun sustain(entry: InstrumentEntry, value: Int) = entry.copy(adsr2 = (entry.adsr2 and 31) or (value.coerceIn(0, 7) shl 5))
    fun fade(entry: InstrumentEntry, value: Int) = entry.copy(adsr2 = (entry.adsr2 and 0xE0) or value.coerceIn(0, 31))
    // Switching gain modes preserves the applicable low bits, never guesses a new volume/rate.
    fun gainMode(entry: InstrumentEntry, mode: GainMode) = entry.copy(gain = mode.bits or (entry.gain and if (mode == GainMode.FIXED) 127 else 31))
    fun gainValue(entry: InstrumentEntry, value: Int) = entry.copy(gain = gainMode(entry).bits or value.coerceIn(0, if (entry.gain < 0x80) 127 else 31))

    fun tuningCents(entry: InstrumentEntry, sourcePitch: Int): Double? =
        if (sourcePitch > 0 && entry.nativePitch > 0) 1200.0 * log2(entry.nativePitch.toDouble() / sourcePitch) else null

    fun tuning(entry: InstrumentEntry, sourcePitch: Int, cents: Double): InstrumentEntry {
        require(sourcePitch in 1..65535 && cents.isFinite())
        val native = (sourcePitch * 2.0.pow(cents.coerceIn(-19200.0, 19200.0) / 1200)).roundToInt().coerceIn(1, 65535)
        return entry.copy(pitchAdj = ((native and 255) shl 8) or (native ushr 8))
    }

    // Same rate periods, offsets, integer updates, and hidden-envelope behavior as
    // tools/snes_spc/snes_spc/SPC_DSP.cpp: run_counters/run_envelope. The graph is
    // an isolated key-on guide, not a simulation of the sample, engine commands, or DSP phase.
    private val periods = intArrayOf(30721, 2048, 1536, 1280, 1024, 768, 640, 512, 384, 320, 256,
        192, 160, 128, 96, 80, 64, 48, 40, 32, 24, 20, 16, 12, 10, 8, 6, 5, 4, 3, 2, 1)
    private val offsets = intArrayOf(1, 0, 1040, 536, 0, 1040, 536, 0, 1040, 536, 0, 1040,
        536, 0, 1040, 536, 0, 1040, 536, 0, 1040, 536, 0, 1040, 536, 0, 1040, 536, 0, 1040, 0, 0)
    const val SAMPLE_RATE = 32000
    const val HOLD_FRAMES = SAMPLE_RATE * 2
    const val RELEASE_FRAMES = 256 // 2047 -> 0, subtracting eight per sample; not a user parameter.

    fun envelopeGuide(entry: InstrumentEntry, holdFrames: Int = HOLD_FRAMES): IntArray {
        require(holdFrames in 1..SAMPLE_RATE * 10)
        var env = 0
        var hidden = 0
        var stage = 0 // attack, decay, sustain
        var counter = 0
        return IntArray(holdFrames + RELEASE_FRAMES) { frame ->
            counter = if (counter == 0) 30719 else counter - 1
            if (frame >= holdFrames) {
                env = (env - 8).coerceAtLeast(0)
            } else {
                var next = env
                val data: Int
                val rate: Int
                if (usesEnvelope(entry)) {
                    data = entry.adsr2
                    if (stage == 0) {
                        rate = attack(entry) * 2 + 1
                        next += if (rate == 31) 0x400 else 0x20
                    } else {
                        next--; next -= next shr 8
                        rate = if (stage == 1) decay(entry) * 2 + 16 else fade(entry)
                    }
                } else {
                    data = entry.gain
                    rate = if (data < 128) 31 else data and 31
                    next = when (gainMode(entry)) {
                        GainMode.FIXED -> data * 16
                        GainMode.FALL -> next - 32
                        GainMode.EXPONENTIAL_FALL -> (next - 1).let { it - (it shr 8) }
                        GainMode.RISE -> next + 32
                        GainMode.BENT_RISE -> next + if (hidden >= 0x600) 8 else 32
                    }
                }
                if (stage == 1 && next shr 8 == data shr 5) stage = 2
                hidden = next
                if (next !in 0..2047) {
                    next = next.coerceIn(0, 2047)
                    if (stage == 0) stage = 1
                }
                if ((counter + offsets[rate]) % periods[rate] == 0) env = next
            }
            env
        }
    }

    fun attackSeconds(entry: InstrumentEntry): Double =
        if (attack(entry) == 15) 2.0 / SAMPLE_RATE else 64.0 * periods[attack(entry) * 2 + 1] / SAMPLE_RATE

    fun decaySeconds(entry: InstrumentEntry): Double =
        fallingSteps(2047, ((sustain(entry) + 1) * 256 - 1)) * periods[decay(entry) * 2 + 16].toDouble() / SAMPLE_RATE

    fun fadeSeconds(entry: InstrumentEntry): Double? = if (fade(entry) == 0) null else
        fallingSteps((sustain(entry) + 1) * 256 - 1, 0) * periods[fade(entry)].toDouble() / SAMPLE_RATE

    private fun fallingSteps(start: Int, end: Int): Int {
        var value = start
        var count = 0
        while (value > end) { value--; value -= value shr 8; count++ }
        return count
    }
}
