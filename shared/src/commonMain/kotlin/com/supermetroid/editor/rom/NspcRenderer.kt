package com.supermetroid.editor.rom

import com.supermetroid.editor.util.EditorLog
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * N-SPC sequence renderer for Super Metroid.
 *
 * Parses the conductor + 8-channel sequence data from SPC RAM and renders
 * audio by pitch-shifting BRR-decoded samples according to note events.
 *
 * Key SPC RAM addresses (from Kejardon's SM SPC RAM map):
 *   $581E+: Song start addresses (conductor entry points per play index)
 *   $6C00+: Instrument table (6 bytes each: SRCN, ADSR1, ADSR2, Gain, tuning high/low)
 *   $6D00+: Sample directory (4 bytes each: startAddr, loopAddr)
 *   $1E66+: Pitch table (13 entries for one octave of semitones)
 */
object NspcRenderer {

    private const val SONG_TABLE_ADDR = 0x581E
    private const val INSTR_TABLE_ADDR = 0x6C00
    private const val SAMPLE_DIR_ADDR = 0x6D00
    private const val PITCH_TABLE_ADDR = 0x1E66
    private const val MAX_INSTRUMENTS = 42
    private const val MAX_SAMPLES = 40

    private val DEFAULT_PITCH_TABLE = intArrayOf(
        0x085F, 0x08DE, 0x0965, 0x09F4, 0x0A8C, 0x0B2C,
        0x0BD6, 0x0C8B, 0x0D4A, 0x0E14, 0x0EEA, 0x0FCD, 0x10BE
    )

    data class NoteEvent(
        val tickStart: Int,
        val tickDuration: Int,
        val channel: Int,
        val noteValue: Int,
        val instrumentIdx: Int,
        val volume: Float,
        val isTie: Boolean = false,
        val isRest: Boolean = false
    )

    data class InstrumentEntry(
        val srcn: Int,
        val adsr1: Int,
        val adsr2: Int,
        val gain: Int,
        /** Raw little-endian packing of the two stored bytes; use [nativePitch] for the engine value. */
        val pitchAdj: Int,
        val index: Int = -1,
        val tableAddr: Int = 0,
        val sampleStartAddr: Int = -1,
        val sampleLoopAddr: Int = -1,
        val brrByteLength: Int = -1,
        val brrLoops: Boolean = false
    ) {
        /** The SPC engine reads the instrument's tuning bytes high byte first (8.8 fixed point). */
        val nativePitch: Int get() = ((pitchAdj and 0xFF) shl 8) or ((pitchAdj ushr 8) and 0xFF)
        val tuningMultiplier: Double get() = nativePitch / 256.0
    }

    private fun readWord(ram: ByteArray, addr: Int): Int {
        if (addr < 0 || addr + 1 >= ram.size) return 0
        return (ram[addr].toInt() and 0xFF) or ((ram[addr + 1].toInt() and 0xFF) shl 8)
    }

    fun readInstrumentTable(ram: ByteArray): List<InstrumentEntry> {
        val entries = mutableListOf<InstrumentEntry>()
        for (i in 0 until MAX_INSTRUMENTS) {
            val off = INSTR_TABLE_ADDR + i * 6
            if (off + 5 >= ram.size) break
            val srcn = ram[off].toInt() and 0xFF
            if (srcn >= MAX_SAMPLES && srcn < 0x80) break
            val sampleDirOff = if (srcn < MAX_SAMPLES) SAMPLE_DIR_ADDR + srcn * 4 else -1
            val sampleStart = if (sampleDirOff >= 0 && sampleDirOff + 3 < ram.size) readWord(ram, sampleDirOff) else -1
            val sampleLoop = if (sampleDirOff >= 0 && sampleDirOff + 3 < ram.size) readWord(ram, sampleDirOff + 2) else -1
            val (brrBytes, brrLoops) = scanBrrLength(ram, sampleStart)
            entries.add(InstrumentEntry(
                srcn = srcn,
                adsr1 = ram[off + 1].toInt() and 0xFF,
                adsr2 = ram[off + 2].toInt() and 0xFF,
                gain = ram[off + 3].toInt() and 0xFF,
                pitchAdj = readWord(ram, off + 4),
                index = i,
                tableAddr = off,
                sampleStartAddr = sampleStart,
                sampleLoopAddr = sampleLoop,
                brrByteLength = brrBytes,
                brrLoops = brrLoops
            ))
        }
        return entries
    }

    private fun scanBrrLength(ram: ByteArray, startAddr: Int): Pair<Int, Boolean> {
        if (startAddr !in 0 until ram.size) return -1 to false
        var addr = startAddr
        var blocks = 0
        var loops = false
        while (addr + 8 < ram.size && blocks < 4096) {
            val header = ram[addr].toInt() and 0xFF
            loops = loops || (header and 0x02) != 0
            blocks++
            addr += 9
            if ((header and 0x01) != 0) return blocks * 9 to loops
        }
        return -1 to loops
    }

    private fun readPitchTable(ram: ByteArray): IntArray {
        val table = IntArray(13)
        for (i in 0 until 13) {
            val w = readWord(ram, PITCH_TABLE_ADDR + i * 2)
            table[i] = if (w > 0) w else DEFAULT_PITCH_TABLE[i]
        }
        if (table.all { it == 0 }) return DEFAULT_PITCH_TABLE
        return table
    }

    /**
     * Parse the N-SPC sequence from SPC RAM for a given play index.
     * Returns a list of note events suitable for rendering.
     */
    fun parseSequence(
        spcRam: ByteArray,
        playIndex: Int,
        maxTicks: Int = 80000,
        maxBlocks: Int = 200
    ): List<NoteEvent> {
        // One parser owns timing, percussion and cross-block state for both the editor
        // and the approximate renderer. Native SPC playback remains the reference.
        val song = NspcSequence.parse(spcRam, playIndex, maxTicks, maxBlocks)
        return song.channels.flatMapIndexed { channel, voice ->
            voice.notes.map { note ->
                val volume = voice.commands.lastOrNull { it.tick <= note.tick && it.command == 0xED && it.params.isNotEmpty() }
                    ?.params?.first()?.div(255f) ?: 1f
                NoteEvent(note.tick, (note.duration * NspcSequence.QUANTIZE_TABLE[note.quantize]).toInt().coerceAtLeast(1),
                    channel, note.noteValue, note.instrument, volume * NspcSequence.VELOCITY_TABLE[note.velocity])
            }
        }.sortedWith(compareBy<NoteEvent> { it.tickStart }.thenBy { it.channel })
    }

    /**
     * Render parsed note events to stereo PCM audio.
     */
    fun renderToWav(
        events: List<NoteEvent>,
        spcRam: ByteArray,
        tempo: Int = 0,
        sampleRate: Int = 32000,
        maxSeconds: Double = 120.0,
        normalize: Boolean = true,
        targetPeak: Float = 28000f
    ): ShortArray {
        if (events.isEmpty()) return ShortArray(0)

        val instruments = readInstrumentTable(spcRam)
        val pitchTable = readPitchTable(spcRam)

        // Determine actual tempo from SPC RAM or use default
        val effectiveTempo = if (tempo > 0) tempo else {
            findTempoFromEvents(spcRam, events) ?: 36
        }

        val tickMs = 512.0 / effectiveTempo
        val samplesPerTick = (sampleRate * tickMs / 1000.0).roundToInt().coerceAtLeast(1)

        val maxTick = events.maxOf { it.tickStart + it.tickDuration }
        val totalSamples = minOf(
            (maxTick + 100) * samplesPerTick,
            (maxSeconds * sampleRate).toInt()
        )
        if (totalSamples <= 0) return ShortArray(0)

        EditorLog.info("[NSPC-RENDER] ${events.size} events, tempo=$effectiveTempo, " +
            "tickMs=${"%.1f".format(tickMs)}, samplesPerTick=$samplesPerTick, " +
            "maxTick=$maxTick, totalSamples=$totalSamples")

        val mixBuf = FloatArray(totalSamples)

        // Pre-decode BRR samples for each instrument
        val sampleCache = mutableMapOf<Int, Pair<ShortArray, Int>>()

        var renderedCount = 0
        var skippedCount = 0

        for (event in events) {
            if (event.isRest || event.isTie || event.noteValue < 0x80) continue

            val instrIdx = event.instrumentIdx
            val instr = instruments.getOrNull(instrIdx)
            if (instr == null) { skippedCount++; continue }
            val srcn = instr.srcn
            if (srcn >= 0x80) continue

            val dirOff = SAMPLE_DIR_ADDR + srcn * 4
            if (dirOff + 3 >= spcRam.size) continue
            val startAddr = readWord(spcRam, dirOff)
            val loopAddr = readWord(spcRam, dirOff + 2)

            val entry = SpcData.SampleDirEntry(srcn, startAddr, loopAddr)
            val decoded = sampleCache.getOrPut(srcn) {
                try {
                    SpcData.decodeBrrWithLoop(spcRam, entry, maxBlocks = 4096)
                } catch (_: Exception) {
                    Pair(ShortArray(0), -1)
                }
            }

            val pcm = decoded.first
            if (pcm.size < 16) continue

            // Calculate pitch ratio for this note
            val noteIdx = (event.noteValue - 0x80).coerceIn(0, 71)
            val octave = noteIdx / 12
            val semitone = noteIdx % 12
            val basePitch = pitchTable[semitone]
            // Match the engine: octave-6 pitch table, then the big-endian 8.8
            // instrument multiplier. The DSP accepts the low 14 pitch bits.
            val pitch = (basePitch * 2) shr (6 - octave)
            val dspPitch = ((pitch * instr.nativePitch) ushr 8) and 0x3FFF
            val finalRatio = dspPitch / 4096.0

            // Determine the duration in samples
            val durSamples = (event.tickDuration * samplesPerTick).coerceAtLeast(1)
            val startSample = event.tickStart * samplesPerTick
            if (startSample >= totalSamples) continue

            val volume = event.volume.coerceIn(0f, 1f) * 0.7f

            // Simple ADSR envelope: attack + sustain + release
            val attackSamples = minOf(durSamples / 10, sampleRate / 50).coerceAtLeast(1)
            val releaseSamples = minOf(durSamples / 4, sampleRate / 10).coerceAtLeast(1)

            val loopStart = decoded.second
            val hasLoop = loopStart in 0 until pcm.size

            for (i in 0 until durSamples) {
                val outIdx = startSample + i
                if (outIdx >= totalSamples) break

                // Source position with pitch ratio
                var srcPos = (i * finalRatio)
                val srcIdx = srcPos.toInt()
                val frac = srcPos - srcIdx

                val sampleVal: Double
                if (hasLoop && srcIdx >= pcm.size) {
                    val loopLen = pcm.size - loopStart
                    if (loopLen <= 0) break
                    val loopIdx = loopStart + ((srcIdx - loopStart) % loopLen)
                    val nextIdx = loopStart + ((srcIdx - loopStart + 1) % loopLen)
                    sampleVal = pcm[loopIdx.coerceIn(0, pcm.size - 1)] * (1.0 - frac) +
                            pcm[nextIdx.coerceIn(0, pcm.size - 1)] * frac
                } else if (srcIdx < pcm.size - 1) {
                    sampleVal = pcm[srcIdx] * (1.0 - frac) + pcm[srcIdx + 1] * frac
                } else if (srcIdx < pcm.size) {
                    sampleVal = pcm[srcIdx].toDouble()
                } else {
                    // Non-looping sample exhausted — silence for remainder
                    sampleVal = 0.0
                }

                // Envelope
                val env = when {
                    i < attackSamples -> i.toFloat() / attackSamples
                    i > durSamples - releaseSamples -> (durSamples - i).toFloat() / releaseSamples
                    else -> 1.0f
                }

                mixBuf[outIdx] += (sampleVal * volume * env).toFloat()
            }
            renderedCount++
        }

        EditorLog.info("[NSPC-RENDER] Rendered $renderedCount notes, skipped $skippedCount (no instrument), " +
            "unique instruments: ${sampleCache.keys.sorted()}")

        val gain = if (normalize) {
            val peak = mixBuf.maxOf { abs(it) }.coerceAtLeast(1f)
            targetPeak / peak
        } else {
            1f
        }
        return ShortArray(mixBuf.size) { (mixBuf[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /**
     * Try to find the initial tempo from the first E7 command in any channel's data.
     */
    private fun findTempoFromEvents(spcRam: ByteArray, @Suppress("UNUSED_PARAMETER") events: List<NoteEvent>): Int? {
        // Scan song data area for an E7 (tempo) command
        val songTableAddr = readWord(spcRam, SONG_TABLE_ADDR + 5 * 2)
        if (songTableAddr < 0x1500) return null

        // Look for E7 XX pattern in the first block's channel data
        for (addr in songTableAddr until minOf(songTableAddr + 0x2000, spcRam.size - 1)) {
            val b = spcRam[addr].toInt() and 0xFF
            if (b == 0xE7 && addr + 1 < spcRam.size) {
                val tempo = spcRam[addr + 1].toInt() and 0xFF
                if (tempo in 10..200) return tempo
            }
        }
        return null
    }

    /**
     * Convenience: parse + render in one call.
     */
    fun renderTrack(
        spcRam: ByteArray,
        playIndex: Int,
        sampleRate: Int = 32000,
        maxSeconds: Double = 90.0
    ): ShortArray {
        val events = parseSequence(spcRam, playIndex)
        if (events.isEmpty()) return ShortArray(0)
        return renderToWav(events, spcRam, sampleRate = sampleRate, maxSeconds = maxSeconds)
    }
}
