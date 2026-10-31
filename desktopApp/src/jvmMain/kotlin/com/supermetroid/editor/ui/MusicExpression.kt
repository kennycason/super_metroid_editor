package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcSequence
import kotlin.math.roundToLong

/** Typed native automation. Unknown/source control commands stay in the song untouched. */
internal object MusicExpression {
    data class Parameter(val label: String, val range: IntRange = 0..255, val initial: Int = 0, val signed: Boolean = false, val mask: Int = 255) {
        fun value(raw: Int) = if (signed) raw.toByte().toInt() else raw and mask
        fun store(raw: Int, value: Int) = (raw and (mask xor 255)) or (value and mask)
    }
    data class Kind(val name: String, val opcode: Int, val parameters: List<Parameter>)
    private fun p(name: String, range: IntRange = 0..255, initial: Int = 0, signed: Boolean = false, mask: Int = 255) = Parameter(name, range, initial, signed, mask)
    val kinds = listOf(
        Kind("Volume", 0xED, listOf(p("Level", initial = 192))),
        Kind("Volume fade", 0xEE, listOf(p("Ticks", 1..255, 24), p("Target", initial = 128))),
        Kind("Pan", 0xE1, listOf(p("Pan · R to L", 0..20, 10, mask = 31))),
        Kind("Pan fade", 0xE2, listOf(p("Ticks", 1..255, 24), p("Target · R to L", 0..20, 10))),
        Kind("Tempo", 0xE7, listOf(p("Tempo", 1..255, 36))),
        Kind("Tempo fade", 0xE8, listOf(p("Ticks", 1..255, 48), p("Target tempo", 1..255, 36))),
        Kind("Master volume", 0xE5, listOf(p("Level", initial = 192))),
        Kind("Master fade", 0xE6, listOf(p("Ticks", 1..255, 24), p("Target", initial = 128))),
        Kind("Vibrato", 0xE3, listOf(p("Delay", initial = 0), p("Rate", initial = 24), p("Depth", initial = 12))),
        Kind("Vibrato off", 0xE4, emptyList()),
        Kind("Vibrato fade", 0xF0, listOf(p("Ticks", initial = 24))),
        Kind("Tremolo", 0xEB, listOf(p("Delay"), p("Rate", initial = 24), p("Depth", initial = 12))),
        Kind("Tremolo off", 0xEC, emptyList()),
        Kind("Pitch envelope to", 0xF1, listOf(p("Delay"), p("Ticks", 1..255, 12), p("Semitones", -128..127, 0, true))),
        Kind("Pitch envelope from", 0xF2, listOf(p("Delay"), p("Ticks", 1..255, 12), p("Semitones", -128..127, 0, true))),
        Kind("Pitch envelope off", 0xF3, emptyList()),
        Kind("Fine tuning", 0xF4, listOf(p("Fraction", initial = 0))),
        Kind("Pitch slide", 0xF9, listOf(p("Delay"), p("Ticks", 1..255, 12), p("Target pitch", NspcSequence.NOTE_MIN..NspcSequence.NOTE_MAX, 0xA4))),
        Kind("Echo on", 0xF5, listOf(p("Voices", initial = 255), p("Left", -128..127, 32, true), p("Right", -128..127, 32, true))),
        Kind("Echo off", 0xF6, emptyList()),
        // The stock engine places echo below $1500: more than two delay pages would wrap into its work RAM.
        Kind("Echo settings", 0xF7, listOf(p("Delay pages", 0..2, 1), p("Feedback", -128..127, 32, true), p("Filter", 0..3, 0))),
        Kind("Echo fade", 0xF8, listOf(p("Ticks", 1..255, 24), p("Left", -128..127, 32, true), p("Right", -128..127, 32, true)))
    )
    fun validate(event: NspcSequence.ControlCommand) {
        require(event.tick in 0..65535) { "Event tick must be 0–65535" }
        val kind = kinds.firstOrNull { it.opcode == event.command } ?: error("This source command is retained as native data")
        require(event.params.size == kind.parameters.size && event.params.all { it in 0..255 }) { "Invalid native control parameters" }
        require(kind.parameters.indices.all { kind.parameters[it].value(event.params[it]) in kind.parameters[it].range }) { "Control value is outside its native range" }
    }
    fun edit(song: NspcSequence.Song, channel: Int, index: Int?, event: NspcSequence.ControlCommand?): NspcSequence.Song {
        require(channel in 0..7)
        if (event != null) validate(event)
        val result = PianoRollPreviewLogic.deepCopySong(song)
        val commands = result.channels[channel].commands
        if (index == null) { requireNotNull(event); commands += event.copy(params = event.params.copyOf()) }
        else {
            require(index in commands.indices && kinds.any { it.opcode == commands[index].command }) { "This native source command is retained unchanged" }
            if (event == null) commands.removeAt(index) else commands[index] = event.copy(params = event.params.copyOf())
        }
        result.isModified = true
        return result
    }

    /** Piecewise tick clock including native tempo events and fixed-point tempo fades. */
    class Timing(song: NspcSequence.Song) {
        private val endTick = maxOf(song.totalTicks, song.channels.maxOf { c -> c.commands.maxOfOrNull { it.tick } ?: 0 }).coerceIn(0, 65535)
        private val times = DoubleArray(endTick + 1)
        val tempoChanges = mutableListOf<Pair<Int, Int>>()
        init {
            val changes = song.channels.flatMapIndexed { channel, voice -> voice.commands.filter { it.command in listOf(0xE7, 0xE8) }
                .map { Triple(channel, it.tick, it) } }.sortedWith(compareBy<Triple<Int, Int, NspcSequence.ControlCommand>> { it.second }.thenBy { it.first })
            var index = 0
            var tempo = song.tempo.coerceIn(1, 255) shl 8
            var remaining = 0; var delta = 0; var target = tempo
            var previous = song.tempo.coerceIn(1, 255)
            for (tick in 0 until endTick) {
                while (index < changes.size && changes[index].second <= tick) {
                    val event = changes[index++].third
                    if (event.command == 0xE7 && event.params.isNotEmpty()) { tempo = event.params[0].coerceIn(1, 255) shl 8; remaining = 0 }
                    if (event.command == 0xE8 && event.params.size == 2) {
                        remaining = event.params[0].coerceAtLeast(1); target = event.params[1].coerceIn(1, 255) shl 8; delta = (target - tempo) / remaining
                    }
                }
                val effective = (tempo shr 8).coerceAtLeast(1)
                if (effective != previous) { tempoChanges += tick to effective; previous = effective }
                times[tick + 1] = times[tick] + 512.0 / (tempo shr 8).coerceAtLeast(1)
                if (remaining > 0) { tempo += delta; remaining--; if (remaining == 0) tempo = target }
            }
        }
        fun millis(tick: Int): Long = times[tick.coerceIn(0, endTick)].roundToLong()
        fun tick(millis: Long): Int {
            var low = 0; var high = times.lastIndex
            while (low < high) { val mid = (low + high + 1) / 2; if (times[mid] <= millis) low = mid else high = mid - 1 }
            return low
        }
    }
}
