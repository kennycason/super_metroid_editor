package com.supermetroid.editor.rom

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
 *   $6C00+: Instrument table (6 bytes each: SRCN, ADSR1, ADSR2, Gain, pitchAdj lo/hi)
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
        val pitchAdj: Int
    )

    private fun readWord(ram: ByteArray, addr: Int): Int {
        if (addr < 0 || addr + 1 >= ram.size) return 0
        return (ram[addr].toInt() and 0xFF) or ((ram[addr + 1].toInt() and 0xFF) shl 8)
    }

    private fun readInstrumentTable(ram: ByteArray): List<InstrumentEntry> {
        val entries = mutableListOf<InstrumentEntry>()
        for (i in 0 until MAX_INSTRUMENTS) {
            val off = INSTR_TABLE_ADDR + i * 6
            if (off + 5 >= ram.size) break
            val srcn = ram[off].toInt() and 0xFF
            if (srcn >= MAX_SAMPLES && srcn < 0x80) break
            entries.add(InstrumentEntry(
                srcn = srcn,
                adsr1 = ram[off + 1].toInt() and 0xFF,
                adsr2 = ram[off + 2].toInt() and 0xFF,
                gain = ram[off + 3].toInt() and 0xFF,
                pitchAdj = readWord(ram, off + 4)
            ))
        }
        return entries
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
        val conductorAddr = readWord(spcRam, SONG_TABLE_ADDR + playIndex * 2)
        if (conductorAddr < 0x1500 || conductorAddr >= 0xFFF0) {
            System.err.println("[NSPC] Invalid conductor addr 0x${conductorAddr.toString(16)} for playIndex $playIndex")
            return emptyList()
        }

        val events = mutableListOf<NoteEvent>()
        var cPtr = conductorAddr
        var globalTick = 0
        var loopCount = 0
        var blocksProcessed = 0
        val visitedConductorAddrs = mutableSetOf<Int>()

        while (blocksProcessed < maxBlocks && globalTick < maxTicks) {
            if (cPtr < 0x1500 || cPtr + 1 >= spcRam.size) break
            if (cPtr in visitedConductorAddrs && loopCount > 0) break
            visitedConductorAddrs.add(cPtr)

            val word = readWord(spcRam, cPtr)
            cPtr += 2

            when {
                word == 0x0000 -> break
                word == 0x0080 || word == 0x0081 -> continue
                word < 0x0100 -> {
                    // Loop control: $00XX with 2-byte address argument
                    loopCount++
                    if (loopCount > 2) break
                    val target = readWord(spcRam, cPtr)
                    cPtr = target
                    continue
                }
                else -> {
                    // Block pointer: read 8 channel start addresses
                    val blockAddr = word
                    val channelAddrs = IntArray(8) { readWord(spcRam, blockAddr + it * 2) }
                    val blockTicks = parseBlock(spcRam, channelAddrs, globalTick, events, maxTicks - globalTick)
                    globalTick += blockTicks
                    blocksProcessed++
                }
            }
        }

        System.err.println("[NSPC] Parsed ${events.size} events, ${blocksProcessed} blocks, ${globalTick} ticks")
        return events
    }

    /**
     * Parse one block (8 channels in parallel) and emit note events.
     * Returns the number of ticks the block took.
     */
    private fun parseBlock(
        ram: ByteArray,
        channelAddrs: IntArray,
        globalTickOffset: Int,
        events: MutableList<NoteEvent>,
        maxTicks: Int
    ): Int {
        data class ChannelState(
            var ptr: Int,
            var ticksRemaining: Int = 0,
            var defaultDuration: Int = 48,
            var quantize: Int = 7,
            var velocity: Int = 15,
            var instrument: Int = 0,
            var volume: Float = 1.0f,
            var channelVolume: Float = 1.0f,
            var finished: Boolean = false,
            var currentTick: Int = 0,
            var subroutineReturn: Int = 0,
            var loopAddr: Int = 0,
            var loopCount: Int = 0,
            var transpose: Int = 0
        )

        val channels = Array(8) { ch ->
            val addr = channelAddrs[ch]
            ChannelState(
                ptr = addr,
                finished = addr < 0x1500 || addr == 0
            )
        }

        val quantizeTable = floatArrayOf(0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1.0f)
        val velocityTable = floatArrayOf(
            0.1f, 0.2f, 0.3f, 0.4f, 0.45f, 0.5f, 0.55f, 0.6f,
            0.65f, 0.7f, 0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1.0f
        )

        var blockTick = 0
        var anyFinished = false

        while (blockTick < maxTicks && !anyFinished) {
            for ((ch, state) in channels.withIndex()) {
                if (state.finished) continue
                if (state.ticksRemaining > 0) {
                    state.ticksRemaining--
                    continue
                }

                var safety = 0
                while (state.ticksRemaining <= 0 && !state.finished && safety++ < 200) {
                    if (state.ptr < 0 || state.ptr >= ram.size) { state.finished = true; break }
                    val b = ram[state.ptr].toInt() and 0xFF
                    state.ptr++

                    when {
                        b == 0x00 -> {
                            // End/return
                            if (state.loopCount > 1) {
                                state.loopCount--
                                state.ptr = state.loopAddr
                            } else if (state.loopCount == 1) {
                                state.loopCount = 0
                                state.ptr = state.subroutineReturn
                            } else {
                                state.finished = true
                                anyFinished = true
                            }
                        }
                        b in 0x01..0x7F -> {
                            state.defaultDuration = b
                            if (state.ptr < ram.size) {
                                val next = ram[state.ptr].toInt() and 0xFF
                                if (next < 0x80) {
                                    state.ptr++
                                    state.quantize = (next shr 4).coerceIn(0, 7)
                                    state.velocity = (next and 0x0F).coerceIn(0, 15)
                                }
                            }
                        }
                        b in 0x80..0xC7 -> {
                            val noteVal = b + state.transpose
                            val dur = state.defaultDuration
                            val playDur = (dur * quantizeTable[state.quantize]).toInt().coerceAtLeast(1)
                            events.add(NoteEvent(
                                tickStart = globalTickOffset + blockTick,
                                tickDuration = playDur,
                                channel = ch,
                                noteValue = noteVal.coerceIn(0x80, 0xC7),
                                instrumentIdx = state.instrument,
                                volume = state.channelVolume * velocityTable[state.velocity]
                            ))
                            state.ticksRemaining = dur - 1
                            state.currentTick += dur
                        }
                        b == 0xC8 -> {
                            // Tie - extend previous note
                            val dur = state.defaultDuration
                            events.add(NoteEvent(
                                tickStart = globalTickOffset + blockTick,
                                tickDuration = dur,
                                channel = ch,
                                noteValue = 0,
                                instrumentIdx = state.instrument,
                                volume = 0f,
                                isTie = true
                            ))
                            state.ticksRemaining = dur - 1
                            state.currentTick += dur
                        }
                        b == 0xC9 -> {
                            // Rest
                            val dur = state.defaultDuration
                            state.ticksRemaining = dur - 1
                            state.currentTick += dur
                        }
                        b in 0xCA..0xDF -> {
                            state.instrument = b - 0xCA + 0x17
                        }
                        b == 0xE0 -> {
                            if (state.ptr < ram.size) {
                                state.instrument = ram[state.ptr].toInt() and 0xFF
                                state.ptr++
                            }
                        }
                        b == 0xE1 -> { state.ptr++ } // pan
                        b == 0xE2 -> { state.ptr += 2 } // pan fade
                        b == 0xE3 -> { state.ptr += 3 } // vibrato on
                        b == 0xE4 -> {} // vibrato off
                        b == 0xE5 -> { state.ptr++ } // main volume
                        b == 0xE6 -> { state.ptr += 2 } // main volume fade
                        b == 0xE7 -> { state.ptr++ } // tempo
                        b == 0xE8 -> { state.ptr += 2 } // tempo fade
                        b == 0xE9 -> { state.ptr++ } // global transpose
                        b == 0xEA -> {
                            if (state.ptr < ram.size) {
                                val t = ram[state.ptr].toInt().toByte().toInt()
                                state.transpose = t
                                state.ptr++
                            }
                        }
                        b == 0xEB -> { state.ptr += 3 } // tremolo on
                        b == 0xEC -> {} // tremolo off
                        b == 0xED -> {
                            if (state.ptr < ram.size) {
                                state.channelVolume = (ram[state.ptr].toInt() and 0xFF) / 255f
                                state.ptr++
                            }
                        }
                        b == 0xEE -> { state.ptr += 2 } // volume fade
                        b == 0xEF -> {
                            // Call subroutine: [addrLo] [addrHi] [count]
                            if (state.ptr + 2 < ram.size) {
                                val subAddr = readWord(ram, state.ptr)
                                val count = ram[state.ptr + 2].toInt() and 0xFF
                                state.subroutineReturn = state.ptr + 3
                                state.loopAddr = subAddr
                                state.loopCount = count
                                state.ptr = subAddr
                            } else {
                                state.finished = true
                            }
                        }
                        b == 0xF0 -> { state.ptr++ } // vibrato fade
                        b == 0xF1 -> { state.ptr += 3 } // pitch envelope to
                        b == 0xF2 -> { state.ptr += 3 } // pitch envelope from
                        b == 0xF3 -> {} // pitch envelope off
                        b == 0xF4 -> { state.ptr++ } // tuning
                        b == 0xF5 -> { state.ptr += 3 } // echo params
                        b == 0xF6 -> {} // echo off
                        b == 0xF7 -> { state.ptr += 3 } // echo params
                        b == 0xF8 -> { state.ptr += 3 } // echo volume fade
                        b == 0xF9 -> { state.ptr += 3 } // pitch slide
                        b == 0xFA -> { state.ptr++ } // percussion patch base
                        b == 0xFB -> { state.ptr += 2 } // SM extension
                        b == 0xFC -> {} // SM extension
                        b == 0xFD -> {} // SM extension
                        b == 0xFE -> {} // SM extension
                        else -> {}
                    }
                }
            }

            blockTick++
            if (channels.all { it.finished }) break
        }

        return blockTick
    }

    /**
     * Render parsed note events to stereo PCM audio.
     */
    fun renderToWav(
        events: List<NoteEvent>,
        spcRam: ByteArray,
        tempo: Int = 0,
        sampleRate: Int = 32000,
        maxSeconds: Double = 120.0
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

        System.err.println("[NSPC-RENDER] ${events.size} events, tempo=$effectiveTempo, " +
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
            val shift = octave - 3
            val pitch = if (shift >= 0) basePitch shl shift else basePitch shr (-shift)
            val pitchRatio = pitch.toDouble() / 0x1000

            // Add instrument pitch adjustment
            val instrPitchAdj = if (instr.pitchAdj != 0) {
                val adj = instr.pitchAdj.toShort().toInt()
                adj.toDouble() / 0x1000
            } else 0.0
            val finalRatio = (pitchRatio + instrPitchAdj).coerceIn(0.01, 16.0)

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

        System.err.println("[NSPC-RENDER] Rendered $renderedCount notes, skipped $skippedCount (no instrument), " +
            "unique instruments: ${sampleCache.keys.sorted()}")

        // Normalize and convert to 16-bit PCM
        val peak = mixBuf.maxOf { abs(it) }.coerceAtLeast(1f)
        val gain = 28000f / peak
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
