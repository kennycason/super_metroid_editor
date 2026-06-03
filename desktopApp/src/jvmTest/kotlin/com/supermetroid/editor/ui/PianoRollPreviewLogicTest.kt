package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PianoRollPreviewLogicTest {

    @Test
    fun `additive overlay returns only newly added notes`() {
        val original = testSong(
            0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94, instrument = 0x1B))
        )
        val current = PianoRollPreviewLogic.deepCopySong(original)
        current.channels[0].notes += note(tick = 48, duration = 96, noteValue = 0x99, instrument = 0x1A)

        val events = PianoRollPreviewLogic.additiveOverlayEvents(current, original)

        assertNotNull(events)
        assertEquals(1, events!!.size)
        assertEquals(0, events.single().channel)
        assertEquals(48, events.single().tickStart)
        assertEquals(0x99, events.single().noteValue)
        assertEquals(0x1A, events.single().instrumentIdx)
    }

    @Test
    fun `additive overlay handles duplicate identical original notes by count`() {
        val duplicate = note(tick = 0, duration = 24, noteValue = 0x94, instrument = 0x1B)
        val original = testSong(0 to listOf(duplicate.copy(), duplicate.copy()))
        val current = PianoRollPreviewLogic.deepCopySong(original)
        current.channels[0].notes += duplicate.copy()

        val events = PianoRollPreviewLogic.additiveOverlayEvents(current, original)

        assertNotNull(events)
        assertEquals(1, events!!.size)
        assertEquals(0, events.single().tickStart)
        assertEquals(0x94, events.single().noteValue)
    }

    @Test
    fun `additive overlay returns empty list for unchanged song`() {
        val original = testSong(
            0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94, instrument = 0x1B)),
            3 to listOf(note(tick = 24, duration = 48, noteValue = 0x9C, instrument = 0x18))
        )
        val current = PianoRollPreviewLogic.deepCopySong(original)

        val events = PianoRollPreviewLogic.additiveOverlayEvents(current, original)

        assertNotNull(events)
        assertEquals(0, events!!.size)
    }

    @Test
    fun `additive overlay rejects moved original notes`() {
        val original = testSong(
            0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94, instrument = 0x1B))
        )
        val current = PianoRollPreviewLogic.deepCopySong(original)
        current.channels[0].notes.single().tick = 12

        assertNull(PianoRollPreviewLogic.additiveOverlayEvents(current, original))
    }

    @Test
    fun `additive overlay rejects deleted original notes`() {
        val original = testSong(
            0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94, instrument = 0x1B))
        )
        val current = PianoRollPreviewLogic.deepCopySong(original)
        current.channels[0].notes.clear()

        assertNull(PianoRollPreviewLogic.additiveOverlayEvents(current, original))
    }

    @Test
    fun `additive overlay rejects changed original note properties`() {
        val original = testSong(
            0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94, instrument = 0x1B))
        )
        val current = PianoRollPreviewLogic.deepCopySong(original)
        current.channels[0].notes.single().instrument = 0x1A

        assertNull(PianoRollPreviewLogic.additiveOverlayEvents(current, original))
    }

    @Test
    fun `delta overlay represents moved original note as remove plus add`() {
        val original = testSong(
            0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94, instrument = 0x1B))
        )
        val current = PianoRollPreviewLogic.deepCopySong(original)
        current.channels[0].notes.single().tick = 12

        val plan = PianoRollPreviewLogic.deltaOverlayPlan(current, original)

        assertNotNull(plan)
        assertEquals(1, plan!!.addEvents.size)
        assertEquals(1, plan.removeEvents.size)
        assertEquals(12, plan.addEvents.single().tickStart)
        assertEquals(0, plan.removeEvents.single().tickStart)
    }

    @Test
    fun `delta overlay represents deleted original note as remove only`() {
        val original = testSong(
            0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94, instrument = 0x1B))
        )
        val current = PianoRollPreviewLogic.deepCopySong(original)
        current.channels[0].notes.clear()

        val plan = PianoRollPreviewLogic.deltaOverlayPlan(current, original)

        assertNotNull(plan)
        assertEquals(0, plan!!.addEvents.size)
        assertEquals(1, plan.removeEvents.size)
        assertEquals(0x94, plan.removeEvents.single().noteValue)
    }

    @Test
    fun `delta overlay represents changed note properties as remove plus add`() {
        val original = testSong(
            0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94, instrument = 0x1B))
        )
        val current = PianoRollPreviewLogic.deepCopySong(original)
        current.channels[0].notes.single().instrument = 0x1A

        val plan = PianoRollPreviewLogic.deltaOverlayPlan(current, original)

        assertNotNull(plan)
        assertEquals(1, plan!!.addEvents.size)
        assertEquals(1, plan.removeEvents.size)
        assertEquals(0x1A, plan.addEvents.single().instrumentIdx)
        assertEquals(0x1B, plan.removeEvents.single().instrumentIdx)
    }

    @Test
    fun `noteEvent clamps quantize and velocity indexes`() {
        val event = PianoRollPreviewLogic.noteEvent(
            channel = 2,
            note = note(tick = 36, duration = 48, noteValue = 0x99, velocity = 99, quantize = 99, instrument = 0x1A)
        )

        assertEquals(2, event.channel)
        assertEquals(36, event.tickStart)
        assertEquals(48, event.tickDuration)
        assertEquals(1.0f, event.volume)
    }

    @Test
    fun `instrumentPatchWrites emits only changed six byte instrument entries`() {
        val original = listOf(
            instrument(index = 0, tableAddr = 0x6C00, srcn = 3, adsr1 = 0x8F, adsr2 = 0xE4, gain = 0x7F, pitchAdj = 0x1234),
            instrument(index = 1, tableAddr = 0x6C06, srcn = 4, adsr1 = 0x81, adsr2 = 0x22, gain = 0x11, pitchAdj = 0x0000)
        )
        val current = listOf(
            original[0].copy(adsr1 = 0x9A, pitchAdj = 0xABCD),
            original[1]
        )

        val writes = PianoRollPreviewLogic.instrumentPatchWrites(current, original)

        assertEquals(setOf(0x6C00), writes.keys)
        assertArrayEquals(
            byteArrayOf(0x03, 0x9A.toByte(), 0xE4.toByte(), 0x7F, 0xCD.toByte(), 0xAB.toByte()),
            writes.getValue(0x6C00)
        )
    }

    @Test
    fun `applyInstrumentEdits writes patches into SPC RAM and skips unchanged entries`() {
        val original = listOf(
            instrument(index = 0, tableAddr = 0x6C00, srcn = 3, adsr1 = 0x8F, adsr2 = 0xE4, gain = 0x7F, pitchAdj = 0x1234)
        )
        val current = listOf(original[0].copy(gain = 0x22))
        val ram = ByteArray(0x7000) { 0x55 }

        PianoRollPreviewLogic.applyInstrumentEdits(ram, current, original)

        assertArrayEquals(
            byteArrayOf(0x03, 0x8F.toByte(), 0xE4.toByte(), 0x22, 0x34, 0x12),
            ram.copyOfRange(0x6C00, 0x6C06)
        )
    }

    @Test
    fun `mixPreviewPcm clips and extends to longer input`() {
        val base = shortArrayOf(30_000, (-30_000).toShort())
        val overlay = shortArrayOf(10_000, (-10_000).toShort(), 123)

        val mixed = PianoRollPreviewLogic.mixPreviewPcm(base, overlay)

        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 123), mixed)
    }

    @Test
    fun `mixPreviewPcm returns original base object when overlay is empty`() {
        val base = shortArrayOf(1, 2, 3)

        val mixed = PianoRollPreviewLogic.mixPreviewPcm(base, ShortArray(0))

        assertSame(base, mixed)
    }

    @Test
    fun `mixPreviewDeltaPcm clips base plus additions minus removals`() {
        val base = shortArrayOf(30_000, (-30_000).toShort(), 100)
        val additions = shortArrayOf(10_000, 0, 50, 9)
        val removals = shortArrayOf(0, 10_000, 200)

        val mixed = PianoRollPreviewLogic.mixPreviewDeltaPcm(base, additions, removals)

        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, (-50).toShort(), 9), mixed)
    }

    @Test
    fun `mixPreviewDeltaPcm returns original base object when no delta exists`() {
        val base = shortArrayOf(1, 2, 3)

        val mixed = PianoRollPreviewLogic.mixPreviewDeltaPcm(base, ShortArray(0), ShortArray(0))

        assertSame(base, mixed)
    }

    @Test
    fun `deepCopySong isolates notes and command parameter arrays`() {
        val original = testSong(
            0 to listOf(note(tick = 0, duration = 24, noteValue = 0x94, instrument = 0x1B))
        )
        original.channels[0].commands += NspcSequence.ControlCommand(0, 0xE7, intArrayOf(27))

        val copy = PianoRollPreviewLogic.deepCopySong(original)
        copy.channels[0].notes.single().tick = 99
        copy.channels[0].commands.single().params[0] = 40

        assertEquals(0, original.channels[0].notes.single().tick)
        assertEquals(27, original.channels[0].commands.single().params[0])
    }

    private fun testSong(vararg notesByChannel: Pair<Int, List<NspcSequence.Note>>): NspcSequence.Song {
        val song = NspcSequence.Song(tempo = 27)
        for ((channel, notes) in notesByChannel) {
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
        instrument: Int
    ): NspcSequence.Note =
        NspcSequence.Note(
            tick = tick,
            duration = duration,
            noteValue = noteValue,
            velocity = velocity,
            quantize = quantize,
            instrument = instrument
        )

    private fun instrument(
        index: Int,
        tableAddr: Int,
        srcn: Int,
        adsr1: Int,
        adsr2: Int,
        gain: Int,
        pitchAdj: Int
    ): NspcRenderer.InstrumentEntry =
        NspcRenderer.InstrumentEntry(
            srcn = srcn,
            adsr1 = adsr1,
            adsr2 = adsr2,
            gain = gain,
            pitchAdj = pitchAdj,
            index = index,
            tableAddr = tableAddr
        )
}
