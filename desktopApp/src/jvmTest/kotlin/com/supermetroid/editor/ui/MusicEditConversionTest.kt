package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.SpcData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MusicEditConversionTest {
    @Test
    fun `project edit round trips song notes commands and instruments`() {
        val track = SpcData.TrackInfo(
            songSet = 0x0F,
            playIndex = 5,
            name = "Test Track",
            area = "test"
        )
        val song = NspcSequence.Song(tempo = 31, title = "Test Track", isModified = true)
        song.channels[2].notes += NspcSequence.Note(
            tick = 24,
            duration = 96,
            noteValue = 0x9A,
            velocity = 10,
            quantize = 5,
            instrument = 0x20
        )
        song.channels[2].commands += NspcSequence.ControlCommand(
            tick = 0,
            command = 0xE7,
            params = intArrayOf(31)
        )
        val instruments = listOf(
            NspcRenderer.InstrumentEntry(
                index = 2,
                tableAddr = 0x6C0C,
                srcn = 4,
                adsr1 = 0x8F,
                adsr2 = 0xE4,
                gain = 0x7F,
                pitchAdj = 0x1234
            )
        )

        val edit = MusicEditConversion.toProjectEdit(track, song, instruments)
        val restoredSong = MusicEditConversion.toSong(edit)
        val restoredInstruments = MusicEditConversion.toInstrumentEntries(edit)

        assertEquals("0F:05", MusicEditConversion.key(track.songSet, track.playIndex))
        assertEquals(31, restoredSong.tempo)
        assertEquals(1, restoredSong.channels[2].notes.size)
        assertEquals(0x9A, restoredSong.channels[2].notes.single().noteValue)
        assertEquals(10, restoredSong.channels[2].notes.single().velocity)
        assertEquals(0xE7, restoredSong.channels[2].commands.single().command)
        assertEquals(31, restoredSong.channels[2].commands.single().params.single())
        assertEquals(0x1234, restoredInstruments.single().pitchAdj)
    }
}
