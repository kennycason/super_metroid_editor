package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MusicEditExportTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `export writes saved music instrument edits into ROM transfer data`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroid.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())
        val songSet = 0x0F
        val playIndex = 5

        val spcRam = SpcData.buildInitialSpcRam(parser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, songSet))
        }
        val originalSong = NspcSequence.parse(spcRam, playIndex)
        val originalInstruments = NspcRenderer.readInstrumentTable(spcRam)
        assumeTrue(originalInstruments.isNotEmpty(), "No instruments found")

        val editedInstruments = originalInstruments.toMutableList()
        val changed = originalInstruments.first().copy(gain = originalInstruments.first().gain xor 0x01)
        editedInstruments[0] = changed

        val track = SpcData.TrackInfo(songSet = songSet, playIndex = playIndex, name = "Green Brinstar", area = "Brinstar")
        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)
        state.project.musicEdits[MusicEditConversion.key(songSet, playIndex)] =
            MusicEditConversion.toProjectEdit(track, originalSong, editedInstruments)
        state.markDirty()

        val exportedPath = state.exportToRom(parser)

        assertNotNull(exportedPath)
        val exportedParser = RomParser(File(exportedPath!!).readBytes())
        assertNotEquals(
            SpcData.readSongSetPointer(parser, songSet),
            SpcData.readSongSetPointer(exportedParser, songSet),
            "music edits should export through a relocated song-set transfer chain"
        )
        val exportedRam = SpcData.buildInitialSpcRam(exportedParser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exportedParser, songSet))
        }
        val exportedInstrument = NspcRenderer.readInstrumentTable(exportedRam).first()
        assertEquals(changed.gain, exportedInstrument.gain)
    }

    @Test
    fun `export writes saved title screen note edits into ROM transfer data`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroidTitle.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())
        val songSet = 0x03
        val playIndex = 5

        val spcRam = SpcData.buildInitialSpcRam(parser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, songSet))
        }
        val originalSong = NspcSequence.parse(spcRam, playIndex)
        val originalInstruments = NspcRenderer.readInstrumentTable(spcRam)
        assumeTrue(originalSong.channels[0].notes.isNotEmpty(), "Title Screen channel 0 has no notes")

        val editedSong = PianoRollPreviewLogic.deepCopySong(originalSong)
        val changedNote = editedSong.channels[0].notes.first()
        val expectedNoteValue = (changedNote.noteValue + 1).coerceAtMost(NspcSequence.NOTE_MAX)
        changedNote.noteValue = expectedNoteValue
        editedSong.isModified = true

        val track = SpcData.TrackInfo(songSet = songSet, playIndex = playIndex, name = "Title Screen", area = "Menu")
        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)
        state.setMusicEdit(
            MusicEditConversion.key(songSet, playIndex),
            MusicEditConversion.toProjectEdit(track, editedSong, originalInstruments)
        )

        val exportedPath = state.exportToRom(parser)

        assertNotNull(exportedPath)
        val exportedParser = RomParser(File(exportedPath!!).readBytes())
        assertNotEquals(
            SpcData.readSongSetPointer(parser, songSet),
            SpcData.readSongSetPointer(exportedParser, songSet),
            "note edits should export through a relocated song-set transfer chain"
        )
        val exportedRam = SpcData.buildInitialSpcRam(exportedParser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exportedParser, songSet))
        }
        val exportedSong = NspcSequence.parse(exportedRam, playIndex)
        assertEquals(expectedNoteValue, exportedSong.channels[0].notes.first().noteValue)
    }
}
