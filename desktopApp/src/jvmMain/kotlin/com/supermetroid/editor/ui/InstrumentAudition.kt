package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NativeSpcEmulator
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import kotlin.math.ceil

/** An isolated, disposable SPC song. Never re-encodes or mutates the user's arrangement. */
internal object InstrumentAudition {
    const val PLAY_INDEX = 5
    const val TEMPO = 36
    private const val CONDUCTOR = 0x5830
    private const val BLOCK_TABLE = 0x5834
    private const val CHANNEL = 0x5844

    data class Preview(val ram: ByteArray, val seconds: Int)

    fun build(ram: ByteArray, instrument: NspcRenderer.InstrumentEntry, note: NspcSequence.Note, tempo: Int = TEMPO): Preview {
        require(ram.size == 0x10000)
        require(instrument.index in 0 until 42)
        val result = ram.copyOf()
        fun word(addr: Int, value: Int) {
            result[addr] = value.toByte()
            result[addr + 1] = (value ushr 8).toByte()
        }
        word(0x581E + PLAY_INDEX * 2, CONDUCTOR)
        word(CONDUCTOR, BLOCK_TABLE)
        word(CONDUCTOR + 2, 0)
        for (channel in 0 until 8) word(BLOCK_TABLE + channel * 2, if (channel == 0) CHANNEL else 0)

        val previewTempo = tempo.coerceIn(1, 255)
        val duration = note.duration.coerceIn(1, (2.0 * previewTempo / 0.512).toInt().coerceAtLeast(1))
        val stream = mutableListOf(
            0xE7, previewTempo, 0xE5, 0xC0, 0xED, 0xFF, 0xE1, 10, 0xF6,
            0xE0, instrument.index // Always select explicitly: percussion opcodes trigger notes.
        )
        var remaining = duration
        var first = true
        while (remaining > 0) {
            val length = minOf(remaining, 127)
            stream += listOf(length, (note.quantize.coerceIn(0, 7) shl 4) or note.velocity.coerceIn(0, 15),
                if (first) note.noteValue.coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX) else 0xC8)
            first = false
            remaining -= length
        }
        // Give the engine time to key off before the conductor finishes.
        stream += listOf(24, 0xC9, 0)
        stream.map(Int::toByte).toByteArray().copyInto(result, CHANNEL)
        PianoRollPreviewLogic.instrumentBytes(instrument).copyInto(result, 0x6C00 + instrument.index * 6)
        val seconds = ceil(duration * 0.512 / previewTempo + 0.5).toInt()
        return Preview(result, seconds)
    }

    fun render(preview: Preview): ShortArray = NativeSpcEmulator().use { emulator ->
        emulator.loadFromRam(preview.ram, emptyList(), PLAY_INDEX, playbackWarmupSeconds = 0)
        emulator.renderMono(preview.seconds)
    }
}
