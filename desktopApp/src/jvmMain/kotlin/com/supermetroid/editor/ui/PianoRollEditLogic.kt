package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence.Note
import com.supermetroid.editor.rom.NspcSequence.Song
import java.util.Collections
import java.util.IdentityHashMap

internal object PianoRollEditLogic {
    const val HORIZONTAL_SCROLL_MULTIPLIER = 24f

    data class NoteRef(val channel: Int, val note: Note)

    data class ClipboardNote(
        val channel: Int,
        val tickOffset: Int,
        val duration: Int,
        val noteValue: Int,
        val velocity: Int,
        val quantize: Int,
        val instrument: Int
    )

    data class EditSnapshot(
        val song: Song,
        val instruments: List<NspcRenderer.InstrumentEntry>,
        val label: String
    )

    class EditHistory(private val maxDepth: Int = 100) {
        private val undoStack = mutableListOf<EditSnapshot>()
        private val redoStack = mutableListOf<EditSnapshot>()

        val undoDepth: Int get() = undoStack.size
        val redoDepth: Int get() = redoStack.size

        fun clear() {
            undoStack.clear()
            redoStack.clear()
        }

        fun record(song: Song, instruments: List<NspcRenderer.InstrumentEntry>, label: String) {
            undoStack += snapshotOf(song, instruments, label)
            while (undoStack.size > maxDepth) undoStack.removeAt(0)
            redoStack.clear()
        }

        fun undo(currentSong: Song, currentInstruments: List<NspcRenderer.InstrumentEntry>): EditSnapshot? {
            if (undoStack.isEmpty()) return null
            val snapshot = undoStack.removeAt(undoStack.lastIndex)
            redoStack += snapshotOf(currentSong, currentInstruments, "Redo ${snapshot.label}")
            return snapshot
        }

        fun redo(currentSong: Song, currentInstruments: List<NspcRenderer.InstrumentEntry>): EditSnapshot? {
            if (redoStack.isEmpty()) return null
            val snapshot = redoStack.removeAt(redoStack.lastIndex)
            undoStack += snapshotOf(currentSong, currentInstruments, "Undo ${snapshot.label}")
            return snapshot
        }

        private fun snapshotOf(
            song: Song,
            instruments: List<NspcRenderer.InstrumentEntry>,
            label: String
        ): EditSnapshot =
            EditSnapshot(
                song = PianoRollPreviewLogic.deepCopySong(song),
                instruments = instruments.map { it.copy() },
                label = label
            )
    }

    fun scrollPixels(rawHorizontalDelta: Float): Float =
        rawHorizontalDelta * HORIZONTAL_SCROLL_MULTIPLIER

    fun sanitizeSelection(song: Song, selection: List<NoteRef>): List<NoteRef> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Note, Boolean>())
        return selection.filter { ref ->
            ref.channel in song.channels.indices &&
                song.channels[ref.channel].notes.any { it === ref.note } &&
                seen.add(ref.note)
        }
    }

    fun selectNotesInRect(
        song: Song,
        visibleChannels: List<Int>,
        leftTick: Int,
        rightTick: Int,
        minNoteValue: Int,
        maxNoteValue: Int
    ): List<NoteRef> {
        val startTick = minOf(leftTick, rightTick)
        val endTick = maxOf(leftTick, rightTick)
        val lowPitch = minOf(minNoteValue, maxNoteValue).coerceAtLeast(NspcSequence.NOTE_MIN)
        val highPitch = maxOf(minNoteValue, maxNoteValue).coerceAtMost(NspcSequence.NOTE_MAX)

        return visibleChannels
            .filter { it in song.channels.indices }
            .flatMap { channel ->
                song.channels[channel].notes
                    .filter { note ->
                        note.endTick >= startTick &&
                            note.tick <= endTick &&
                            note.noteValue in lowPitch..highPitch
                    }
                    .map { note -> NoteRef(channel, note) }
            }
    }

    fun moveSelection(
        song: Song,
        selection: List<NoteRef>,
        deltaTick: Int,
        deltaPitch: Int,
        totalTicks: Int
    ): Boolean {
        val refs = sanitizeSelection(song, selection)
        if (refs.isEmpty() || (deltaTick == 0 && deltaPitch == 0)) return false

        var changed = false
        for (ref in refs) {
            val note = ref.note
            val nextTick = (note.tick + deltaTick).coerceIn(0, (totalTicks - note.duration).coerceAtLeast(0))
            val nextNoteValue = (note.noteValue + deltaPitch).coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX)
            if (nextTick != note.tick || nextNoteValue != note.noteValue) {
                changed = true
                note.tick = nextTick
                note.noteValue = nextNoteValue
            }
        }
        return changed
    }

    fun deleteSelection(song: Song, selection: List<NoteRef>): Int {
        val refs = sanitizeSelection(song, selection)
        if (refs.isEmpty()) return 0
        val identities = Collections.newSetFromMap(IdentityHashMap<Note, Boolean>())
        refs.forEach { identities += it.note }
        var removed = 0
        for (channel in song.channels) {
            removed += channel.notes.count { identities.contains(it) }
            channel.notes.removeAll { identities.contains(it) }
        }
        return removed
    }

    fun copySelection(selection: List<NoteRef>): List<ClipboardNote> {
        if (selection.isEmpty()) return emptyList()
        val minTick = selection.minOf { it.note.tick }
        return selection
            .sortedWith(compareBy<NoteRef> { it.note.tick }.thenBy { it.channel }.thenBy { it.note.noteValue })
            .map { ref ->
                ClipboardNote(
                    channel = ref.channel,
                    tickOffset = ref.note.tick - minTick,
                    duration = ref.note.duration,
                    noteValue = ref.note.noteValue,
                    velocity = ref.note.velocity,
                    quantize = ref.note.quantize,
                    instrument = ref.note.instrument
                )
            }
    }

    fun pasteSelection(
        song: Song,
        clipboard: List<ClipboardNote>,
        pasteTick: Int,
        totalTicks: Int
    ): List<NoteRef> {
        if (clipboard.isEmpty()) return emptyList()
        val refs = mutableListOf<NoteRef>()
        for (clip in clipboard) {
            val channel = clip.channel.coerceIn(song.channels.indices)
            val tick = (pasteTick + clip.tickOffset).coerceIn(0, (totalTicks - clip.duration).coerceAtLeast(0))
            val note = Note(
                tick = tick,
                duration = clip.duration.coerceAtLeast(1),
                noteValue = clip.noteValue.coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX),
                velocity = clip.velocity.coerceIn(0, 15),
                quantize = clip.quantize.coerceIn(0, 7),
                instrument = clip.instrument.coerceIn(0, 255)
            )
            song.channels[channel].notes += note
            refs += NoteRef(channel, note)
        }
        return refs
    }
}
