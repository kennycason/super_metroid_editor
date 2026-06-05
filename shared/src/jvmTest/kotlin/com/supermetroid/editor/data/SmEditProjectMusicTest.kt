package com.supermetroid.editor.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmEditProjectMusicTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `music edits survive project json round trip`() {
        val key = MusicTrackEdit.key(0x0F, 5)
        val project = SmEditProject(romPath = "/tmp/sm.smc")
        project.musicEdits[key] = MusicTrackEdit(
            songSet = 0x0F,
            playIndex = 5,
            trackName = "Green Brinstar",
            tempo = 27,
            channels = mutableListOf(
                MusicChannelEdit(
                    notes = mutableListOf(
                        MusicNoteEdit(tick = 12, duration = 48, noteValue = 0x94, velocity = 11, quantize = 6, instrument = 0x20)
                    ),
                    commands = mutableListOf(
                        MusicCommandEdit(tick = 0, command = 0xE7, params = listOf(27))
                    )
                )
            ),
            instruments = mutableListOf(
                MusicInstrumentEdit(index = 2, tableAddr = 0x6C0C, srcn = 4, adsr1 = 0x8F, adsr2 = 0xE4, gain = 0x7F, pitchAdj = 0x1234)
            )
        )

        val encoded = json.encodeToString(SmEditProject.serializer(), project)
        val decoded = json.decodeFromString(SmEditProject.serializer(), encoded)
        val edit = decoded.musicEdits.getValue(key)

        assertEquals(0x0F, edit.songSet)
        assertEquals(5, edit.playIndex)
        assertEquals(27, edit.tempo)
        assertEquals(0x94, edit.channels.single().notes.single().noteValue)
        assertEquals(listOf(27), edit.channels.single().commands.single().params)
        assertEquals(0x1234, edit.instruments.single().pitchAdj)
    }

    @Test
    fun `old project json defaults to no music edits`() {
        val decoded = json.decodeFromString(
            SmEditProject.serializer(),
            """{"romPath":"/tmp/sm.smc","rooms":{},"patches":[]}"""
        )

        assertTrue(decoded.musicEdits.isEmpty())
    }
}
