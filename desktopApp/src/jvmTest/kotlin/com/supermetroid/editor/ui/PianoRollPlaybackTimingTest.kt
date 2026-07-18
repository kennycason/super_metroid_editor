package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcSequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PianoRollPlaybackTimingTest {
    @Test
    fun `preview length follows imported song ticks instead of fixed minute`() {
        val song = NspcSequence.Song(tempo = 29)
        song.channels[0].notes += NspcSequence.Note(
            tick = 0,
            duration = 6312,
            noteValue = NspcSequence.NOTE_MIN
        )

        assertEquals(114, PianoRollPlaybackTiming.previewSeconds(song))
    }

    @Test
    fun `tick seek converts through song tempo`() {
        assertEquals(111_439L, PianoRollPlaybackTiming.tickToMillis(6312, tempo = 29))
    }

    @Test
    fun `playback tick clamps visually without owning audio completion`() {
        val afterSongEnd = PianoRollPlaybackTiming.tickToMillis(7000, tempo = 29)

        assertEquals(
            6312,
            PianoRollPlaybackTiming.playbackTickAtMillis(afterSongEnd, tempo = 29, maxTick = 6312)
        )
    }

    @Test
    fun `very short previews keep a usable minimum`() {
        val song = NspcSequence.Song(tempo = 60)
        song.channels[0].notes += NspcSequence.Note(
            tick = 0,
            duration = 12,
            noteValue = NspcSequence.NOTE_MIN
        )

        assertEquals(5, PianoRollPlaybackTiming.previewSeconds(song))
    }
}
