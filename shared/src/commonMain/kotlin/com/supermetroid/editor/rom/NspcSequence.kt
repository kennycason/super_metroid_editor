package com.supermetroid.editor.rom

/**
 * Editable N-SPC sequence data model.
 *
 * Represents a complete song as 8 channels of note/command events,
 * parseable from SPC RAM and encodable back to N-SPC binary format.
 *
 * N-SPC note encoding: 0x80-0xC7 = notes (0x80 = C1, 0xC7 = B6)
 * Note value = (octave * 12) + semitone, where octave 0 = C1
 * MIDI-like: noteValue 0x80 = MIDI 24 (C1), so MIDI = noteValue - 0x80 + 24
 */
object NspcSequence {

    // Note value constants
    const val NOTE_MIN = 0x80       // C1
    const val NOTE_MAX = 0xC7       // B6
    const val NOTE_TIE = 0xC8
    const val NOTE_REST = 0xC9
    const val CMD_END = 0x00

    // Semitone names for display
    val SEMITONE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun noteToName(noteValue: Int): String {
        if (noteValue == NOTE_TIE) return "TIE"
        if (noteValue == NOTE_REST) return "REST"
        if (noteValue < NOTE_MIN || noteValue > NOTE_MAX) return "???"
        val idx = noteValue - NOTE_MIN
        val octave = idx / 12 + 1  // C1 = octave 1
        val semitone = idx % 12
        return "${SEMITONE_NAMES[semitone]}${octave}"
    }

    fun nameToNote(name: String): Int {
        if (name == "TIE") return NOTE_TIE
        if (name == "REST") return NOTE_REST
        val match = Regex("([A-G]#?)(\\d)").matchEntire(name) ?: return -1
        val semitone = SEMITONE_NAMES.indexOf(match.groupValues[1])
        val octave = match.groupValues[2].toIntOrNull() ?: return -1
        if (semitone < 0 || octave < 1 || octave > 6) return -1
        return NOTE_MIN + (octave - 1) * 12 + semitone
    }

    /** A single note in the piano roll. */
    data class Note(
        var tick: Int,              // start tick
        var duration: Int,          // duration in ticks
        var noteValue: Int,         // 0x80-0xC7 (C1-B6)
        var velocity: Int = 15,     // 0-15
        var quantize: Int = 7,      // 0-7
        var instrument: Int = 0     // instrument index
    ) {
        val endTick get() = tick + duration
        val pitchIndex get() = (noteValue - NOTE_MIN).coerceIn(0, 71)
        val octave get() = pitchIndex / 12 + 1
        val semitone get() = pitchIndex % 12
        val name get() = noteToName(noteValue)
    }

    /** Control command (non-note events like tempo, volume, pan, etc.) */
    data class ControlCommand(
        var tick: Int,
        var command: Int,           // 0xE0-0xFE
        var params: IntArray = IntArray(0)
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ControlCommand) return false
            return tick == other.tick && command == other.command && params.contentEquals(other.params)
        }
        override fun hashCode() = 31 * (31 * tick + command) + params.contentHashCode()
    }

    /** One channel's worth of data. */
    data class Channel(
        val notes: MutableList<Note> = mutableListOf(),
        val commands: MutableList<ControlCommand> = mutableListOf()
    ) {
        val totalTicks: Int get() = notes.maxOfOrNull { it.endTick } ?: 0
    }

    /** A complete editable song. */
    data class Song(
        val channels: Array<Channel> = Array(8) { Channel() },
        var tempo: Int = 36,
        var title: String = "",
        var isModified: Boolean = false
    ) {
        val totalTicks: Int get() = channels.maxOf { it.totalTicks }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Song) return false
            return tempo == other.tempo && channels.contentEquals(other.channels)
        }
        override fun hashCode() = 31 * tempo + channels.contentHashCode()
    }

    // ─── Parsing ────────────────────────────────────────────────────

    private fun readWord(ram: ByteArray, addr: Int): Int {
        if (addr < 0 || addr + 1 >= ram.size) return 0
        return (ram[addr].toInt() and 0xFF) or ((ram[addr + 1].toInt() and 0xFF) shl 8)
    }

    /**
     * Parse a complete song from SPC RAM.
     * Uses the conductor at the given play index to find all blocks and channels.
     */
    fun parse(spcRam: ByteArray, playIndex: Int, maxTicks: Int = 80000): Song {
        val song = Song()
        val conductorAddr = readWord(spcRam, 0x581E + playIndex * 2)
        if (conductorAddr < 0x1500 || conductorAddr >= 0xFFF0) return song

        // Find tempo from first E7 command
        song.tempo = findTempo(spcRam, conductorAddr) ?: 36

        var cPtr = conductorAddr
        var globalTick = 0
        var loopCount = 0
        val visited = mutableSetOf<Int>()

        while (globalTick < maxTicks) {
            if (cPtr < 0x1500 || cPtr + 1 >= spcRam.size) break
            if (cPtr in visited && loopCount > 0) break
            visited.add(cPtr)

            val word = readWord(spcRam, cPtr)
            cPtr += 2

            when {
                word == 0x0000 -> break
                word == 0x0080 || word == 0x0081 -> continue
                word < 0x0100 -> {
                    loopCount++
                    if (loopCount > 1) break // only parse one loop iteration
                    cPtr = readWord(spcRam, cPtr)
                }
                else -> {
                    val channelAddrs = IntArray(8) { readWord(spcRam, word + it * 2) }
                    val blockTicks = parseBlock(spcRam, channelAddrs, globalTick, song)
                    globalTick += blockTicks
                }
            }
        }

        return song
    }

    private fun parseBlock(
        ram: ByteArray,
        channelAddrs: IntArray,
        globalTickOffset: Int,
        song: Song
    ): Int {
        data class ChState(
            var ptr: Int,
            var ticksRemaining: Int = 0,
            var defaultDuration: Int = 48,
            var quantize: Int = 7,
            var velocity: Int = 15,
            var instrument: Int = 0,
            var channelVolume: Float = 1.0f,
            var finished: Boolean = false,
            var currentTick: Int = 0,
            var subroutineReturn: Int = 0,
            var loopAddr: Int = 0,
            var loopCount: Int = 0,
            var transpose: Int = 0
        )

        val states = Array(8) { ch ->
            val addr = channelAddrs[ch]
            ChState(ptr = addr, finished = addr < 0x1500 || addr == 0)
        }

        var blockTick = 0

        while (blockTick < 80000) {
            var anyActive = false
            for ((ch, st) in states.withIndex()) {
                if (st.finished) continue
                anyActive = true
                if (st.ticksRemaining > 0) {
                    st.ticksRemaining--
                    continue
                }

                var safety = 0
                while (st.ticksRemaining <= 0 && !st.finished && safety++ < 200) {
                    if (st.ptr < 0 || st.ptr >= ram.size) { st.finished = true; break }
                    val b = ram[st.ptr].toInt() and 0xFF
                    st.ptr++

                    when {
                        b == 0x00 -> {
                            if (st.loopCount > 1) {
                                st.loopCount--
                                st.ptr = st.loopAddr
                            } else if (st.loopCount == 1) {
                                st.loopCount = 0
                                st.ptr = st.subroutineReturn
                            } else {
                                st.finished = true
                            }
                        }
                        b in 0x01..0x7F -> {
                            st.defaultDuration = b
                            if (st.ptr < ram.size) {
                                val next = ram[st.ptr].toInt() and 0xFF
                                if (next < 0x80) {
                                    st.ptr++
                                    st.quantize = (next shr 4).coerceIn(0, 7)
                                    st.velocity = (next and 0x0F).coerceIn(0, 15)
                                }
                            }
                        }
                        b in 0x80..0xC7 -> {
                            val noteVal = (b + st.transpose).coerceIn(0x80, 0xC7)
                            val dur = st.defaultDuration
                            song.channels[ch].notes.add(Note(
                                tick = globalTickOffset + blockTick,
                                duration = dur,
                                noteValue = noteVal,
                                velocity = st.velocity,
                                quantize = st.quantize,
                                instrument = st.instrument
                            ))
                            st.ticksRemaining = dur - 1
                            st.currentTick += dur
                        }
                        b == 0xC8 -> { // tie
                            st.ticksRemaining = st.defaultDuration - 1
                            st.currentTick += st.defaultDuration
                            // Extend previous note's duration
                            val lastNote = song.channels[ch].notes.lastOrNull()
                            if (lastNote != null) {
                                lastNote.duration += st.defaultDuration
                            }
                        }
                        b == 0xC9 -> { // rest
                            st.ticksRemaining = st.defaultDuration - 1
                            st.currentTick += st.defaultDuration
                        }
                        b in 0xCA..0xDF -> {
                            st.instrument = b - 0xCA + 0x17
                        }
                        b >= 0xE0 -> {
                            val (cmd, params) = parseControlCommand(ram, st.ptr - 1, b, st)
                            song.channels[ch].commands.add(ControlCommand(
                                tick = globalTickOffset + blockTick,
                                command = b,
                                params = params
                            ))
                            st.ptr = cmd
                        }
                    }
                }
            }
            if (!anyActive) break
            blockTick++
        }

        return blockTick
    }

    /** Parse a control command, update state, return (new ptr, params). */
    private fun parseControlCommand(
        ram: ByteArray,
        cmdAddr: Int,
        cmd: Int,
        st: Any // using Any to avoid exposing ChState
    ): Pair<Int, IntArray> {
        var ptr = cmdAddr + 1 // skip command byte
        fun readByte(): Int {
            if (ptr >= ram.size) return 0
            val v = ram[ptr].toInt() and 0xFF
            ptr++
            return v
        }

        val paramCount = COMMAND_PARAM_COUNT.getOrElse(cmd) { 0 }

        // Special handling for commands that affect channel state
        @Suppress("UNCHECKED_CAST")
        when (cmd) {
            0xE0 -> {
                val inst = readByte()
                if (st is MutableMap<*, *>) { /* won't match */ }
                return Pair(ptr, intArrayOf(inst))
            }
            0xEF -> {
                // Subroutine call: addr_lo, addr_hi, count
                val lo = readByte()
                val hi = readByte()
                val count = readByte()
                return Pair(ptr, intArrayOf(lo, hi, count))
            }
            else -> {
                val params = IntArray(paramCount) { readByte() }
                return Pair(ptr, params)
            }
        }
    }

    /** Number of parameter bytes for each command 0xE0-0xFE. */
    private val COMMAND_PARAM_COUNT = mapOf(
        0xE0 to 1, // instrument
        0xE1 to 1, // pan
        0xE2 to 2, // pan fade
        0xE3 to 3, // vibrato on
        0xE4 to 0, // vibrato off
        0xE5 to 1, // main volume
        0xE6 to 2, // main volume fade
        0xE7 to 1, // tempo
        0xE8 to 2, // tempo fade
        0xE9 to 1, // global transpose
        0xEA to 1, // channel transpose
        0xEB to 3, // tremolo on
        0xEC to 0, // tremolo off
        0xED to 1, // channel volume
        0xEE to 2, // volume fade
        0xEF to 3, // subroutine call
        0xF0 to 1, // vibrato fade
        0xF1 to 3, // pitch env to
        0xF2 to 3, // pitch env from
        0xF3 to 0, // pitch env off
        0xF4 to 1, // fine tune
        0xF5 to 3, // echo on
        0xF6 to 0, // echo off
        0xF7 to 3, // echo params
        0xF8 to 3, // echo vol fade
        0xF9 to 3, // pitch slide
        0xFA to 1, // percussion base
        0xFB to 2, // skip byte (SM ext)
        0xFC to 0, // SM ext
        0xFD to 0, // SM ext
        0xFE to 0, // SM ext
    )

    private fun findTempo(ram: ByteArray, conductorAddr: Int): Int? {
        // Walk conductor to find first block, then scan for E7 command
        var cPtr = conductorAddr
        for (i in 0 until 20) {
            if (cPtr + 1 >= ram.size) break
            val word = readWord(ram, cPtr)
            cPtr += 2
            if (word == 0) break
            if (word >= 0x0100) {
                // Block pointer — scan first channel for E7
                val ch0Addr = readWord(ram, word)
                if (ch0Addr >= 0x1500) {
                    for (scan in ch0Addr until minOf(ch0Addr + 100, ram.size - 1)) {
                        if (ram[scan].toInt() and 0xFF == 0xE7 && scan + 1 < ram.size) {
                            val tempo = ram[scan + 1].toInt() and 0xFF
                            if (tempo in 10..200) return tempo
                        }
                    }
                }
                break
            }
        }
        return null
    }

    // ─── Encoding ───────────────────────────────────────────────────

    /**
     * Encode a song back to N-SPC binary format.
     * Returns a map of SPC RAM address -> bytes to write.
     *
     * Strategy: find where the original conductor for this play index points,
     * then overwrite that same region with our new data. This keeps everything
     * within the original sequence data area ($5820-$6BFF) and avoids corrupting
     * BRR samples or the SPC engine.
     *
     * @param spcRam The current SPC RAM (to find original conductor location)
     */
    fun encode(song: Song, playIndex: Int, spcRam: ByteArray? = null): Map<Int, ByteArray> {
        val writes = mutableMapOf<Int, ByteArray>()

        val totalNotes = song.channels.sumOf { it.notes.size }
        val totalCmds = song.channels.sumOf { it.commands.size }
        System.err.println("[NSPC-ENCODE] Encoding song: playIndex=$playIndex, tempo=${song.tempo}, " +
            "$totalNotes notes, $totalCmds commands across ${song.channels.count { it.notes.isNotEmpty() }} channels")

        // Find original conductor address for this play index
        val originalConductorAddr = if (spcRam != null) {
            readWord(spcRam, 0x581E + playIndex * 2)
        } else 0

        // Use original conductor location if valid, otherwise use a safe default
        // The sequence data area is $5820-$6BFF (~5KB)
        val conductorAddr = if (originalConductorAddr in 0x5820..0x6800) {
            originalConductorAddr
        } else {
            0x5830 // safe default within sequence data area
        }
        System.err.println("[NSPC-ENCODE] Original conductor at 0x${originalConductorAddr.toString(16)}, " +
            "using 0x${conductorAddr.toString(16)}")

        // Block pointer table right after conductor (conductor = 4 bytes: block_ptr + 0x0000 end)
        val blockTableAddr = conductorAddr + 4

        // Channel data starts after block pointer table (8 channels * 2 bytes = 16 bytes)
        var dataAddr = blockTableAddr + 16

        // Safety: ensure we don't write past instrument table at $6C00
        val maxDataAddr = 0x6C00

        val channelAddrs = IntArray(8)

        for (ch in 0 until 8) {
            channelAddrs[ch] = dataAddr
            val channelData = encodeChannel(song.channels[ch], song.tempo, ch == 0)
            System.err.println("[NSPC-ENCODE]   Ch $ch: ${song.channels[ch].notes.size} notes, " +
                "${song.channels[ch].commands.size} cmds -> ${channelData.size} bytes at 0x${dataAddr.toString(16)}")
            if (dataAddr + channelData.size > maxDataAddr) {
                System.err.println("[NSPC-ENCODE] WARNING: channel $ch data at 0x${dataAddr.toString(16)} " +
                    "would overflow into instrument table (${channelData.size} bytes). Truncating.")
                // Write truncated + end marker
                val truncated = channelData.copyOf(maxDataAddr - dataAddr - 1)
                truncated[truncated.size - 1] = 0x00 // end marker
                writes[dataAddr] = truncated
                dataAddr += truncated.size
                // Fill remaining channels with just end markers
                for (remaining in ch + 1 until 8) {
                    channelAddrs[remaining] = dataAddr
                    writes[dataAddr] = byteArrayOf(0x00)
                    dataAddr++
                }
                break
            }
            writes[dataAddr] = channelData
            dataAddr += channelData.size
        }

        // Write block pointer table
        val blockTable = ByteArray(16)
        for (ch in 0 until 8) {
            blockTable[ch * 2] = (channelAddrs[ch] and 0xFF).toByte()
            blockTable[ch * 2 + 1] = ((channelAddrs[ch] shr 8) and 0xFF).toByte()
        }
        writes[blockTableAddr] = blockTable

        // Write conductor: [blockTableAddr] [0x0000 end]
        val conductor = ByteArray(4)
        conductor[0] = (blockTableAddr and 0xFF).toByte()
        conductor[1] = ((blockTableAddr shr 8) and 0xFF).toByte()
        conductor[2] = 0x00
        conductor[3] = 0x00
        writes[conductorAddr] = conductor

        // Update conductor pointer in song table
        val songTableEntry = ByteArray(2)
        songTableEntry[0] = (conductorAddr and 0xFF).toByte()
        songTableEntry[1] = ((conductorAddr shr 8) and 0xFF).toByte()
        writes[0x581E + playIndex * 2] = songTableEntry

        System.err.println("[NSPC-ENCODE] Conductor at 0x${conductorAddr.toString(16)}, " +
            "data ends at 0x${dataAddr.toString(16)}, ${dataAddr - conductorAddr} bytes total")

        return writes
    }

    /** Encode a single channel's notes and commands to N-SPC binary. */
    private fun encodeChannel(channel: Channel, tempo: Int, isFirstChannel: Boolean): ByteArray {
        val out = mutableListOf<Int>()

        // Emit tempo on first channel
        if (isFirstChannel) {
            out.add(0xE7)
            out.add(tempo.coerceIn(1, 255))
        }

        // Sort notes by tick
        val notes = channel.notes.sortedBy { it.tick }
        // Sort commands by tick
        val commands = channel.commands.sortedBy { it.tick }

        var currentTick = 0
        var lastDuration = -1
        var lastQuantize = -1
        var lastVelocity = -1
        var lastInstrument = -1
        var noteIdx = 0
        var cmdIdx = 0

        while (noteIdx < notes.size || cmdIdx < commands.size) {
            // Find next event (note or command) by tick
            val nextNoteTick = if (noteIdx < notes.size) notes[noteIdx].tick else Int.MAX_VALUE
            val nextCmdTick = if (cmdIdx < commands.size) commands[cmdIdx].tick else Int.MAX_VALUE

            if (nextCmdTick <= nextNoteTick && cmdIdx < commands.size) {
                // Emit control command
                val cmd = commands[cmdIdx]
                // Insert rest if needed to advance to this tick
                if (cmd.tick > currentTick) {
                    emitRests(out, cmd.tick - currentTick, lastDuration)
                    currentTick = cmd.tick
                }
                out.add(cmd.command)
                for (p in cmd.params) out.add(p)
                cmdIdx++
            } else if (noteIdx < notes.size) {
                val note = notes[noteIdx]

                // Insert rests to reach this note's tick
                if (note.tick > currentTick) {
                    val gap = note.tick - currentTick
                    emitRests(out, gap, lastDuration)
                    currentTick = note.tick
                }

                // Emit instrument change if needed
                if (note.instrument != lastInstrument) {
                    if (note.instrument in 0x17..0x33) {
                        out.add(0xCA + note.instrument - 0x17)
                    } else {
                        out.add(0xE0)
                        out.add(note.instrument)
                    }
                    lastInstrument = note.instrument
                }

                // Emit duration/quantize/velocity if changed
                if (note.duration != lastDuration || note.quantize != lastQuantize || note.velocity != lastVelocity) {
                    out.add(note.duration.coerceIn(1, 0x7F))
                    val qv = ((note.quantize and 0x07) shl 4) or (note.velocity and 0x0F)
                    out.add(qv)
                    lastDuration = note.duration
                    lastQuantize = note.quantize
                    lastVelocity = note.velocity
                } else if (note.duration != lastDuration) {
                    out.add(note.duration.coerceIn(1, 0x7F))
                    lastDuration = note.duration
                }

                // Emit note
                out.add(note.noteValue.coerceIn(NOTE_MIN, NOTE_MAX))
                currentTick += note.duration
                noteIdx++
            }
        }

        // End marker
        out.add(CMD_END)

        return ByteArray(out.size) { out[it].toByte() }
    }

    /** Emit rest commands to fill a gap. */
    private fun emitRests(out: MutableList<Int>, ticks: Int, lastDuration: Int) {
        var remaining = ticks
        while (remaining > 0) {
            val dur = remaining.coerceIn(1, 0x7F)
            if (dur != lastDuration) {
                out.add(dur)
            }
            out.add(NOTE_REST)
            remaining -= dur
        }
    }

    // ─── Utilities ──────────────────────────────────────────────────

    /** Get the list of unique instruments used in a song. */
    fun usedInstruments(song: Song): Set<Int> {
        return song.channels.flatMap { ch -> ch.notes.map { it.instrument } }.toSet()
    }

    /** Quantize table values. */
    val QUANTIZE_TABLE = floatArrayOf(0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1.0f)

    /** Velocity table values. */
    val VELOCITY_TABLE = floatArrayOf(
        0.1f, 0.2f, 0.3f, 0.4f, 0.45f, 0.5f, 0.55f, 0.6f,
        0.65f, 0.7f, 0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1.0f
    )
}
