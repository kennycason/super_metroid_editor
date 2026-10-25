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
            ),
            nativePayload = MusicNativePayloadEdit(
                formatLabel = "Raw N-SPC",
                sourceFileName = "musicdata.nspc",
                sourcePlayIndex = 5,
                blocks = mutableListOf(
                    MusicTransferBlockEdit(destAddr = 0x5830, dataBase64 = "AQIDBA==")
                )
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
        assertEquals("Raw N-SPC", edit.nativePayload?.formatLabel)
        assertEquals("musicdata.nspc", edit.nativePayload?.sourceFileName)
        assertEquals(0x5830, edit.nativePayload?.blocks?.single()?.destAddr)
        assertEquals("AQIDBA==", edit.nativePayload?.blocks?.single()?.dataBase64)
    }

    @Test
    fun `current project without optional music data defaults to no music edits`() {
        val decoded = json.decodeFromString(
            SmEditProject.serializer(),
            """{"projectFormat":"smedit-project","projectFormatVersion":1,"projectId":"music-defaults-test","romPath":"/tmp/sm.smc","rooms":{},"patches":[]}"""
        )

        assertTrue(decoded.musicEdits.isEmpty())
        assertTrue(decoded.musicInstrumentBanks.isEmpty())
        assertTrue(decoded.musicInstrumentNames.isEmpty())
    }

    @Test
    fun `display names survive saving and stay independent across sound banks`() {
        val project = SmEditProject(romPath = "/tmp/sm.smc")
        project.musicInstrumentNames[MusicTrackEdit.key(0x0F, 30)] = "Soft pulse"
        project.musicInstrumentNames[MusicTrackEdit.key(0x18, 30)] = "Metallic lead"
        val decoded = json.decodeFromString(SmEditProject.serializer(), json.encodeToString(SmEditProject.serializer(), project))
        assertEquals(project.musicInstrumentNames, decoded.musicInstrumentNames)
        assertTrue(decoded.musicEdits.isEmpty(), "Naming a sound must not create audio patches")
        assertTrue(decoded.musicInstrumentBanks.isEmpty())
    }

    @Test
    fun `authoritative bank settings and empty reconciliation markers survive project round trip`() {
        val project = SmEditProject(romPath = "/tmp/sm.smc")
        project.musicInstrumentBanks["03"] = MusicInstrumentBankEdit(mutableMapOf(
            28 to MusicInstrumentEdit(28, 0x6CA8, 24, 0x8F, 0xE0, 63, 0x0001)))
        project.musicInstrumentBanks["0F"] = MusicInstrumentBankEdit()
        val decoded = json.decodeFromString(SmEditProject.serializer(), json.encodeToString(SmEditProject.serializer(), project))
        assertEquals(project.musicInstrumentBanks, decoded.musicInstrumentBanks)
        assertTrue(decoded.musicInstrumentBanks.containsKey("0F"))
        assertTrue(decoded.musicInstrumentBanks.getValue("0F").instruments.isEmpty())
        assertTrue(decoded.musicEdits.isEmpty())
    }

    @Test
    fun `metatile table overrides survive project json round trip`() {
        val project = SmEditProject(romPath = "/tmp/sm.smc")
        project.customGfx.tileTables["3"] = "AAECAwQFBgc="
        project.customGfx.creTileTable = "CAcGBQQDAgE="

        val encoded = json.encodeToString(SmEditProject.serializer(), project)
        val decoded = json.decodeFromString(SmEditProject.serializer(), encoded)

        assertEquals("AAECAwQFBgc=", decoded.customGfx.tileTables["3"])
        assertEquals("CAcGBQQDAgE=", decoded.customGfx.creTileTable)
    }
}
