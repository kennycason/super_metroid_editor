package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.NspcRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PianoRollEditLogicTest {

    @Test
    fun `selectNotesInRect selects overlapping notes in visible channels only`() {
        val a = note(tick = 0, duration = 24, noteValue = 0x94)
        val b = note(tick = 60, duration = 24, noteValue = 0x96)
        val hidden = note(tick = 12, duration = 24, noteValue = 0x94)
        val song = songOf(0 to listOf(a, b), 2 to listOf(hidden))

        val selected = PianoRollEditLogic.selectNotesInRect(
            song = song,
            visibleChannels = listOf(0),
            leftTick = 20,
            rightTick = 72,
            minNoteValue = 0x93,
            maxNoteValue = 0x96
        )

        assertEquals(2, selected.size)
        assertSame(a, selected[0].note)
        assertSame(b, selected[1].note)
    }

    @Test
    fun `moveSelection moves every selected note and clamps tick and pitch`() {
        val low = note(tick = 0, duration = 48, noteValue = NspcSequence.NOTE_MIN)
        val high = note(tick = 960, duration = 96, noteValue = NspcSequence.NOTE_MAX)
        val song = songOf(0 to listOf(low), 1 to listOf(high))

        val moved = PianoRollEditLogic.moveSelection(
            song,
            selection = listOf(PianoRollEditLogic.NoteRef(0, low), PianoRollEditLogic.NoteRef(1, high)),
            deltaTick = 120,
            deltaPitch = 8,
            totalTicks = 1000
        )

        assertTrue(moved)
        assertEquals(120, low.tick)
        assertEquals(NspcSequence.NOTE_MIN + 8, low.noteValue)
        assertEquals(904, high.tick)
        assertEquals(NspcSequence.NOTE_MAX, high.noteValue)
    }

    @Test
    fun `deleteSelection removes by note identity not equal values`() {
        val first = note(tick = 0, duration = 24, noteValue = 0x94)
        val duplicate = first.copy()
        val song = songOf(0 to listOf(first, duplicate))

        val removed = PianoRollEditLogic.deleteSelection(song, listOf(PianoRollEditLogic.NoteRef(0, first)))

        assertEquals(1, removed)
        assertEquals(1, song.channels[0].notes.size)
        assertSame(duplicate, song.channels[0].notes.single())
    }

    @Test
    fun `copyPaste preserves note properties and relative timing`() {
        val left = note(tick = 120, duration = 24, noteValue = 0x94, instrument = 0x1B)
        val right = note(tick = 168, duration = 96, noteValue = 0x99, velocity = 9, quantize = 4, instrument = 0x1A)
        val song = songOf(0 to listOf(left), 3 to listOf(right))

        val clipboard = PianoRollEditLogic.copySelection(
            listOf(PianoRollEditLogic.NoteRef(3, right), PianoRollEditLogic.NoteRef(0, left))
        )
        val pasted = PianoRollEditLogic.pasteSelection(song, clipboard, pasteTick = 240, totalTicks = 1000)

        assertEquals(2, pasted.size)
        assertEquals(240, pasted[0].note.tick)
        assertEquals(288, pasted[1].note.tick)
        assertEquals(0, pasted[0].channel)
        assertEquals(3, pasted[1].channel)
        assertEquals(0x1A, pasted[1].note.instrument)
        assertEquals(9, pasted[1].note.velocity)
        assertEquals(4, pasted[1].note.quantize)
    }

    @Test
    fun `sanitizeSelection drops stale note references`() {
        val live = note(tick = 0, duration = 24, noteValue = 0x94)
        val stale = note(tick = 0, duration = 24, noteValue = 0x94)
        val song = songOf(0 to listOf(live))

        val clean = PianoRollEditLogic.sanitizeSelection(
            song,
            listOf(PianoRollEditLogic.NoteRef(0, live), PianoRollEditLogic.NoteRef(0, stale))
        )

        assertEquals(1, clean.size)
        assertSame(live, clean.single().note)
    }

    @Test
    fun `sanitizeSelection dedupes repeated references by identity`() {
        val live = note(tick = 0, duration = 24, noteValue = 0x94)
        val song = songOf(0 to listOf(live))

        val clean = PianoRollEditLogic.sanitizeSelection(
            song,
            listOf(PianoRollEditLogic.NoteRef(0, live), PianoRollEditLogic.NoteRef(0, live))
        )

        assertEquals(1, clean.size)
        assertSame(live, clean.single().note)
    }

    @Test
    fun `moveSelection returns false for empty or no-op selection`() {
        val n = note(tick = 0, duration = 24, noteValue = 0x94)
        val song = songOf(0 to listOf(n))

        assertFalse(PianoRollEditLogic.moveSelection(song, emptyList(), 12, 0, 1000))
        assertFalse(PianoRollEditLogic.moveSelection(song, listOf(PianoRollEditLogic.NoteRef(0, n)), 0, 0, 1000))
    }

    @Test
    fun `scrollPixels applies fast horizontal piano roll multiplier`() {
        assertEquals(120f, PianoRollEditLogic.scrollPixels(5f))
    }

    @Test
    fun `edit history undo redo snapshots song and instruments independently`() {
        val history = PianoRollEditLogic.EditHistory(maxDepth = 10)
        val song = songOf(0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94, instrument = 1)))
        val instruments = listOf(instrument(index = 1, srcn = 3))

        history.record(song, instruments, "move")
        song.channels[0].notes.single().tick = 48
        val editedInstruments = listOf(instruments.single().copy(srcn = 7))

        val undo = history.undo(song, editedInstruments)

        assertEquals(0, undo!!.song.channels[0].notes.single().tick)
        assertEquals(3, undo.instruments.single().srcn)
        assertEquals(0, history.undoDepth)
        assertEquals(1, history.redoDepth)

        val redo = history.redo(undo.song, undo.instruments)

        assertEquals(48, redo!!.song.channels[0].notes.single().tick)
        assertEquals(7, redo.instruments.single().srcn)
        assertEquals(1, history.undoDepth)
        assertEquals(0, history.redoDepth)
    }

    @Test
    fun `edit history record clears redo stack`() {
        val history = PianoRollEditLogic.EditHistory(maxDepth = 10)
        val song = songOf(0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94)))

        history.record(song, emptyList(), "first")
        song.channels[0].notes.single().tick = 24
        history.undo(song, emptyList())
        history.record(song, emptyList(), "second")

        assertEquals(1, history.undoDepth)
        assertEquals(0, history.redoDepth)
    }

    @Test
    fun `edit history enforces max depth`() {
        val history = PianoRollEditLogic.EditHistory(maxDepth = 2)
        val song = songOf(0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94)))

        history.record(song, emptyList(), "one")
        song.channels[0].notes.single().tick = 12
        history.record(song, emptyList(), "two")
        song.channels[0].notes.single().tick = 24
        history.record(song, emptyList(), "three")

        assertEquals(2, history.undoDepth)
        assertEquals("three", history.undo(song, emptyList())!!.label)
        assertEquals("two", history.undo(song, emptyList())!!.label)
    }

    private fun songOf(vararg byChannel: Pair<Int, List<NspcSequence.Note>>): NspcSequence.Song {
        val song = NspcSequence.Song(tempo = 27)
        for ((channel, notes) in byChannel) {
            song.channels[channel].notes.addAll(notes)
        }
        return song
    }

    private fun note(
        tick: Int,
        duration: Int,
        noteValue: Int,
        velocity: Int = 15,
        quantize: Int = 7,
        instrument: Int = 0x18
    ): NspcSequence.Note =
        NspcSequence.Note(
            tick = tick,
            duration = duration,
            noteValue = noteValue,
            velocity = velocity,
            quantize = quantize,
            instrument = instrument
        )

    private fun instrument(index: Int, srcn: Int): NspcRenderer.InstrumentEntry =
        NspcRenderer.InstrumentEntry(
            srcn = srcn,
            adsr1 = 0,
            adsr2 = 0,
            gain = 0,
            pitchAdj = 0,
            index = index,
            tableAddr = 0x6C00 + index * 6
        )
}
