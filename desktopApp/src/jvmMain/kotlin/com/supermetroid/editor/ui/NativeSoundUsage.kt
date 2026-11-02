package com.supermetroid.editor.ui

/** Reachability inventory without playback-time limits; finite conductor exits and calls are both followed. */
internal object NativeSoundUsage {
    data class Result(val instruments: Set<Int>, val complete: Boolean, val sequenceBytes: Set<Int>)
    private val lengths = intArrayOf(1, 1, 2, 3, 0, 1, 2, 1, 2, 1, 1, 3, 0, 1, 2, 3, 1, 3, 3, 0, 1, 3, 0, 3, 3, 3, 1, 2)
    fun inspect(ram: ByteArray, playIndexes: List<Int>): Result {
        val used = mutableSetOf(0)
        val bases = mutableSetOf(0)
        val percussion = mutableSetOf<Int>()
        val conductors = ArrayDeque<Int>()
        val voices = ArrayDeque<Int>()
        val seenConductors = mutableSetOf<Int>()
        val seenVoices = mutableSetOf<Int>()
        val sequenceBytes = mutableSetOf<Int>()
        fun byte(at: Int): Int { require(at in ram.indices); sequenceBytes += at; return ram[at].toInt() and 255 }
        fun word(at: Int) = byte(at) or (byte(at + 1) shl 8)
        return try {
            for (play in playIndexes) conductors += word(0x581E + play * 2)
            while (conductors.isNotEmpty()) {
                var at = conductors.removeFirst()
                while (true) {
                    require(at in 0x1500..0xFFFE)
                    if (!seenConductors.add(at)) break
                    val command = word(at); at += 2
                    if (command == 0) break
                    if (command in listOf(0x80, 0x81)) continue
                    if (command < 0x100) {
                        conductors += word(at)
                        if (command > 0x81) break // Negative native repeat counts never reach their exit.
                        at += 2
                    } else for (channel in 0 until 8) {
                        val pointer = word(command + channel * 2)
                        if (pointer != 0) voices += pointer
                    }
                }
            }
            while (voices.isNotEmpty()) {
                var at = voices.removeFirst()
                while (true) {
                    require(at in 0x1500..0xFFFF)
                    if (!seenVoices.add(at)) break
                    val opcode = byte(at++)
                    if (opcode == 0) break
                    when {
                        opcode < 0x80 -> { if (byte(at) < 0x80) at++ }
                        opcode in 0x80..0xC9 -> Unit
                        opcode in 0xCA..0xDF -> percussion += opcode - 0xCA
                        opcode in 0xE0..0xFB -> {
                            val size = lengths[opcode - 0xE0]
                            for (offset in 0 until size) byte(at + offset)
                            if (opcode == 0xE0) { val id = byte(at); if (id < 0x80) used += id else percussion += id - 0xCA }
                            if (opcode == 0xFA) bases += byte(at)
                            if (opcode == 0xEF) voices += word(at)
                            at += size
                        }
                        else -> error("Unknown native voice command")
                    }
                }
            }
            for (base in bases) for (offset in percussion) used += (base + offset) and 255
            Result(used, true, sequenceBytes)
        } catch (_: IllegalArgumentException) { Result(used, false, sequenceBytes) }
        catch (_: IllegalStateException) { Result(used, false, sequenceBytes) }
    }
}
