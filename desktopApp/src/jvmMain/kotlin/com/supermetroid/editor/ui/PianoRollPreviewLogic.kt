package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence

internal object PianoRollPreviewLogic {
    data class DeltaOverlayPlan(
        val addEvents: List<NspcRenderer.NoteEvent>,
        val removeEvents: List<NspcRenderer.NoteEvent>
    ) {
        val hasDelta: Boolean get() = addEvents.isNotEmpty() || removeEvents.isNotEmpty()
    }

    private data class NoteSignature(
        val tick: Int,
        val duration: Int,
        val noteValue: Int,
        val velocity: Int,
        val quantize: Int,
        val instrument: Int
    )

    fun deepCopySong(song: NspcSequence.Song): NspcSequence.Song {
        val copy = NspcSequence.Song(tempo = song.tempo, title = song.title, isModified = song.isModified)
        for (ch in 0 until 8) {
            copy.channels[ch].notes.addAll(song.channels[ch].notes.map { it.copy() })
            copy.channels[ch].commands.addAll(song.channels[ch].commands.map {
                NspcSequence.ControlCommand(it.tick, it.command, it.params.copyOf())
            })
        }
        return copy
    }

    fun additiveOverlayEvents(
        song: NspcSequence.Song,
        original: NspcSequence.Song?
    ): List<NspcRenderer.NoteEvent>? {
        val plan = deltaOverlayPlan(song, original) ?: return null
        return if (plan.removeEvents.isEmpty()) plan.addEvents else null
    }

    fun deltaOverlayPlan(
        song: NspcSequence.Song,
        original: NspcSequence.Song?
    ): DeltaOverlayPlan? {
        original ?: return null
        val addEvents = mutableListOf<NspcRenderer.NoteEvent>()
        val removeEvents = mutableListOf<NspcRenderer.NoteEvent>()

        for (ch in 0 until 8) {
            val remainingOriginal = original.channels[ch].notes.toMutableList()

            for (note in song.channels[ch].notes) {
                val sig = signatureOf(note)
                val originalIndex = remainingOriginal.indexOfFirst { signatureOf(it) == sig }
                if (originalIndex >= 0) {
                    remainingOriginal.removeAt(originalIndex)
                } else {
                    addEvents += noteEvent(ch, note)
                }
            }

            removeEvents += remainingOriginal.map { noteEvent(ch, it) }
        }

        return DeltaOverlayPlan(addEvents, removeEvents)
    }

    fun noteEvent(channel: Int, note: NspcSequence.Note): NspcRenderer.NoteEvent {
        val qTable = NspcSequence.QUANTIZE_TABLE
        val vTable = NspcSequence.VELOCITY_TABLE
        val quantize = note.quantize.coerceIn(qTable.indices)
        val velocity = note.velocity.coerceIn(vTable.indices)
        val playDur = (note.duration * qTable[quantize]).toInt().coerceAtLeast(1)
        return NspcRenderer.NoteEvent(
            tickStart = note.tick,
            tickDuration = playDur,
            channel = channel,
            noteValue = note.noteValue,
            instrumentIdx = note.instrument,
            volume = vTable[velocity]
        )
    }

    fun instrumentPatchWrites(
        current: List<NspcRenderer.InstrumentEntry>,
        original: List<NspcRenderer.InstrumentEntry>
    ): Map<Int, ByteArray> {
        if (current.isEmpty() || original.isEmpty()) return emptyMap()
        val writes = linkedMapOf<Int, ByteArray>()
        for (i in current.indices) {
            val currentEntry = current[i]
            val originalEntry = original.getOrNull(i) ?: continue
            if (instrumentBytes(currentEntry).contentEquals(instrumentBytes(originalEntry))) continue
            writes[currentEntry.tableAddr] = instrumentBytes(currentEntry)
        }
        return writes
    }

    fun applyInstrumentEdits(
        spcRam: ByteArray,
        current: List<NspcRenderer.InstrumentEntry>,
        original: List<NspcRenderer.InstrumentEntry>
    ) {
        for ((addr, data) in instrumentPatchWrites(current, original)) {
            if (addr >= 0 && addr + data.size <= spcRam.size) {
                data.copyInto(spcRam, addr)
            }
        }
    }

    fun writeInstrumentEntries(
        ram: ByteArray,
        instruments: List<NspcRenderer.InstrumentEntry>
    ) {
        for (entry in instruments) {
            val data = instrumentBytes(entry)
            if (entry.tableAddr >= 0 && entry.tableAddr + data.size <= ram.size) {
                data.copyInto(ram, entry.tableAddr)
            }
        }
    }

    fun instrumentBytes(entry: NspcRenderer.InstrumentEntry): ByteArray =
        byteArrayOf(
            entry.srcn.coerceIn(0, 255).toByte(),
            entry.adsr1.coerceIn(0, 255).toByte(),
            entry.adsr2.coerceIn(0, 255).toByte(),
            entry.gain.coerceIn(0, 255).toByte(),
            (entry.pitchAdj and 0xFF).toByte(),
            ((entry.pitchAdj shr 8) and 0xFF).toByte()
        )

    fun mixPreviewPcm(base: ShortArray, overlay: ShortArray): ShortArray {
        if (overlay.isEmpty()) return base
        val len = maxOf(base.size, overlay.size)
        val mixed = ShortArray(len)
        for (i in 0 until len) {
            val sample = (base.getOrNull(i)?.toInt() ?: 0) + (overlay.getOrNull(i)?.toInt() ?: 0)
            mixed[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return mixed
    }

    fun mixPreviewDeltaPcm(
        base: ShortArray,
        additions: ShortArray,
        removals: ShortArray
    ): ShortArray {
        if (additions.isEmpty() && removals.isEmpty()) return base
        val len = maxOf(base.size, additions.size, removals.size)
        val mixed = ShortArray(len)
        for (i in 0 until len) {
            val sample = (base.getOrNull(i)?.toInt() ?: 0) +
                (additions.getOrNull(i)?.toInt() ?: 0) -
                (removals.getOrNull(i)?.toInt() ?: 0)
            mixed[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return mixed
    }

    fun peakOf(samples: ShortArray): Int =
        if (samples.isEmpty()) 0 else samples.maxOf { kotlin.math.abs(it.toInt()) }

    private fun signatureOf(note: NspcSequence.Note): NoteSignature =
        NoteSignature(note.tick, note.duration, note.noteValue, note.velocity, note.quantize, note.instrument)
}
