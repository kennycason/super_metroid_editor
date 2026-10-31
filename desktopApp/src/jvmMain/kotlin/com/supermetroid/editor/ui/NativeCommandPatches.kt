package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.SpcData

/** Preserve native calls/loops for operand-only edits; conflicting shared occurrences need re-encoding. */
internal object NativeCommandPatches {
    fun plan(ram: ByteArray, playIndex: Int, song: NspcSequence.Song, protectedBytes: Set<Int> = emptySet()): Map<Int, ByteArray>? {
        val original = NspcSequence.parse(ram, playIndex)
        if (song.tempo != original.tempo) return null
        val wanted = java.util.TreeMap<Int, Int>()
        for (channel in 0 until 8) {
            val before = original.channels[channel]; val after = song.channels[channel]
            if (before.notes != after.notes || before.commands.size != after.commands.size) return null
            for ((index, source) in before.commands.withIndex()) {
                val edit = after.commands[index]
                if (source.tick != edit.tick || source.command != edit.command || source.params.size != edit.params.size || source.sourceAddress < 0x5800) return null
                if (!source.params.contentEquals(edit.params)) runCatching { MusicExpression.validate(edit) }.getOrElse { return null }
                if (source.sourceAddress + source.params.size >= ram.size || (ram[source.sourceAddress].toInt() and 255) != source.command) return null
                for (parameter in source.params.indices) {
                    val at = source.sourceAddress + parameter + 1
                    val value = edit.params[parameter]
                    if ((ram[at].toInt() and 255) != source.params[parameter] || value !in 0..255) return null
                    if (at in wanted && wanted[at] != value) return null
                    wanted[at] = value
                }
            }
        }
        val changed = java.util.TreeMap(wanted.filter { (at, value) -> (ram[at].toInt() and 255) != value })
        if (changed.keys.any { it in protectedBytes }) return null
        return SpcData.packSpcBytesToWrites(changed)
    }
}
