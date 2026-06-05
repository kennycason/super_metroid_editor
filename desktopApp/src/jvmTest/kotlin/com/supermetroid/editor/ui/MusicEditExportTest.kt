package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MusicEditExportTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `fitSongToEncodedBudget trims tail notes and commands until sequence fits`() {
        val song = NspcSequence.Song(tempo = 40)
        repeat(12) { i ->
            song.channels[0].notes += NspcSequence.Note(
                tick = i * 24,
                duration = 24,
                noteValue = NspcSequence.NOTE_MIN + i,
                velocity = 15,
                quantize = 7,
                instrument = 0x1E
            )
            song.channels[0].commands += NspcSequence.ControlCommand(
                tick = i * 24,
                command = 0xED,
                params = intArrayOf(0x60 + i)
            )
        }
        val firstThreeOnly = NspcSequence.Song(tempo = song.tempo).also { partial ->
            partial.channels[0].notes += song.channels[0].notes.take(3).map { it.copy() }
            partial.channels[0].commands += song.channels[0].commands.take(3).map { it.copy(params = it.params.copyOf()) }
        }
        val budget = NspcSequence.encodedSequenceSize(firstThreeOnly)

        val fit = MusicSequenceBudget.fitSongToEncodedBudget(song, budget)

        assertEquals(budget, fit.budgetBytes)
        assertTrue(fit.encodedBytes <= budget)
        assertTrue(fit.removedNotes > 0)
        assertTrue(fit.removedCommands > 0)
        assertTrue(fit.song.channels[0].notes.size < song.channels[0].notes.size)
        assertTrue(fit.song.channels[0].commands.size < song.channels[0].commands.size)
        assertEquals(NspcSequence.NOTE_MIN, fit.song.channels[0].notes.first().noteValue)
        assertTrue(fit.song.channels[0].notes.last().tick < song.channels[0].notes.last().tick)
    }

    @Test
    fun `encodedSequenceSize matches relocated encoder payload`() {
        val song = NspcSequence.Song(tempo = 32)
        repeat(4) { i ->
            song.channels[0].commands += NspcSequence.ControlCommand(
                tick = i * 48,
                command = 0xE0,
                params = intArrayOf(0x18 + i)
            )
            song.channels[0].notes += NspcSequence.Note(
                tick = i * 48,
                duration = 36,
                noteValue = NspcSequence.NOTE_MIN + 12 + i,
                velocity = 12,
                quantize = 6,
                instrument = 0x18 + i
            )
            song.channels[2].notes += NspcSequence.Note(
                tick = i * 48 + 12,
                duration = 24,
                noteValue = NspcSequence.NOTE_MIN + 24 + i,
                velocity = 10,
                quantize = 5,
                instrument = 0x1E
            )
        }

        val conductorAddr = 0x6000
        val encodedBytes = NspcSequence.encodedSequenceSize(song)
        val songTableEntry = MusicSequenceBudget.SONG_TABLE_BASE + 5 * 2
        val writes = NspcSequence.encode(
            song = song,
            playIndex = 5,
            spcRam = ByteArray(MusicSequenceBudget.SEQUENCE_EXPORT_MAX),
            failOnOverflow = true,
            conductorAddrOverride = conductorAddr,
            sequenceEndAddr = conductorAddr + encodedBytes
        )
        val sequenceWrites = writes.filterKeys { it != songTableEntry }

        assertEquals(encodedBytes, sequenceWrites.values.sumOf { it.size })
        for ((addr, data) in sequenceWrites) {
            assertTrue(addr >= conductorAddr)
            assertTrue(addr + data.size <= conductorAddr + encodedBytes)
        }
    }

    @Test
    fun `encode does not reuse unsafe low original block table`() {
        val song = NspcSequence.Song(tempo = 21)
        song.channels[0].notes += NspcSequence.Note(
            tick = 0,
            duration = 24,
            noteValue = NspcSequence.NOTE_MIN + 12,
            velocity = 15,
            quantize = 7,
            instrument = 0x1E
        )
        val spcRam = ByteArray(MusicSequenceBudget.SEQUENCE_EXPORT_MAX)
        val playIndex = 6
        val conductorAddr = 0x5D9D
        writeSpcU16(spcRam, MusicSequenceBudget.SONG_TABLE_BASE + playIndex * 2, conductorAddr)
        writeSpcU16(spcRam, conductorAddr, 0x18F0)

        val writes = NspcSequence.encode(song, playIndex, spcRam, failOnOverflow = true)

        val songTableEntry = MusicSequenceBudget.SONG_TABLE_BASE + playIndex * 2
        for ((addr, data) in writes) {
            if (addr == songTableEntry) continue
            assertTrue(
                addr >= MusicSequenceBudget.SEQUENCE_DATA_MIN,
                "unsafe low SPC write at 0x${addr.toString(16)}"
            )
            assertTrue(addr + data.size <= MusicSequenceBudget.LOW_SEQUENCE_MAX)
        }
        assertTrue(0x18F0 !in writes.keys)
    }

    @Test
    fun `vanilla title after-button has separate track and relocation budgets`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val parser = RomParser(romBytes)
        val songSet = 0x03
        val playIndex = 6
        val spcRam = SpcData.buildInitialSpcRam(parser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, songSet))
        }
        val originalSong = NspcSequence.parse(spcRam, playIndex)
        val trackBudget = MusicSequenceBudget.trackBudgetForTrack(parser, songSet, playIndex)
        val flattenedBytes = NspcSequence.encodedSequenceSize(originalSong)

        assertEquals(
            MusicSequenceBudget.originalSequenceBytes(spcRam, playIndex),
            trackBudget.trackBytes
        )
        assertTrue(
            flattenedBytes > trackBudget.trackBytes,
            "phase-1 flattened edit size should expose pressure against the per-track budget"
        )
        assertTrue(
            trackBudget.relocationBytes >= trackBudget.trackBytes,
            "relocation budget is a separate larger export-placement pool"
        )
    }

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
    fun `export writes saved title screen after-button note edits into ROM transfer data`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroidTitle.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())
        val songSet = 0x03
        val playIndex = 6

        val spcRam = SpcData.buildInitialSpcRam(parser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, songSet))
        }
        val originalSong = NspcSequence.parse(spcRam, playIndex)
        val originalInstruments = NspcRenderer.readInstrumentTable(spcRam)
        assumeTrue(originalSong.channels.any { it.notes.isNotEmpty() }, "Title Screen after-button track has no notes")

        val editedSong = PianoRollPreviewLogic.deepCopySong(originalSong)
        val channelIndex = editedSong.channels.indexOfFirst { it.notes.isNotEmpty() }
        val changedNote = editedSong.channels[channelIndex].notes.first()
        val expectedNoteValue = if (changedNote.noteValue < NspcSequence.NOTE_MAX) {
            changedNote.noteValue + 1
        } else {
            changedNote.noteValue - 1
        }
        changedNote.noteValue = expectedNoteValue
        editedSong.isModified = true

        val track = SpcData.TrackInfo(songSet = songSet, playIndex = playIndex, name = "Title Screen (After Button)", area = "Menu")
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
        assertEquals(expectedNoteValue, exportedSong.channels[channelIndex].notes.first().noteValue)
    }

    @Test
    fun `export trims tail to fit multiple relocated title edits`() {
        val romBytes = TestRomHelper.loadRomBytes()
        assumeTrue(romBytes != null, "Test ROM not found")
        romBytes!!

        val inputRom = File(tempDir, "SuperMetroidTitlePair.smc")
        inputRom.writeBytes(romBytes)
        val parser = RomParser(inputRom.readBytes())
        val songSet = 0x03

        val spcRam = SpcData.buildInitialSpcRam(parser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, songSet))
        }
        val originalInstruments = NspcRenderer.readInstrumentTable(spcRam)

        val state = EditorState()
        state.testMode = true
        state.initForRom(inputRom.absolutePath)

        val titleEdit = addOneNoteValueEdit(
            state = state,
            spcRam = spcRam,
            originalInstruments = originalInstruments,
            songSet = songSet,
            playIndex = 5,
            name = "Title Screen"
        )
        val afterButtonEdit = addOneNoteValueEdit(
            state = state,
            spcRam = spcRam,
            originalInstruments = originalInstruments,
            songSet = songSet,
            playIndex = 6,
            name = "Title Screen After Button"
        )

        val exportedPath = state.exportToRom(parser)

        assertNotNull(exportedPath)
        val exportedParser = RomParser(File(exportedPath!!).readBytes())
        val exportedRam = SpcData.buildInitialSpcRam(exportedParser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exportedParser, songSet))
        }

        assertExportedNote(exportedRam, titleEdit)
        assertExportedNote(exportedRam, afterButtonEdit)
        assertNotEquals(
            readSpcU16(spcRam, 0x581E + afterButtonEdit.playIndex * 2),
            readSpcU16(exportedRam, 0x581E + afterButtonEdit.playIndex * 2),
            "Title Screen after-button should point at a relocated conductor"
        )
    }

    private data class ExpectedNoteEdit(
        val playIndex: Int,
        val channelIndex: Int,
        val expectedNoteValue: Int
    )

    private fun addOneNoteValueEdit(
        state: EditorState,
        spcRam: ByteArray,
        originalInstruments: List<NspcRenderer.InstrumentEntry>,
        songSet: Int,
        playIndex: Int,
        name: String
    ): ExpectedNoteEdit {
        val originalSong = NspcSequence.parse(spcRam, playIndex)
        val channelIndex = originalSong.channels.indexOfFirst { it.notes.isNotEmpty() }
        assumeTrue(channelIndex >= 0, "$name has no editable notes")

        val editedSong = PianoRollPreviewLogic.deepCopySong(originalSong)
        val changedNote = editedSong.channels[channelIndex].notes.first()
        val expectedNoteValue = if (changedNote.noteValue < NspcSequence.NOTE_MAX) {
            changedNote.noteValue + 1
        } else {
            changedNote.noteValue - 1
        }
        changedNote.noteValue = expectedNoteValue
        editedSong.isModified = true

        val track = SpcData.TrackInfo(songSet = songSet, playIndex = playIndex, name = name, area = "Menu")
        state.setMusicEdit(
            MusicEditConversion.key(songSet, playIndex),
            MusicEditConversion.toProjectEdit(track, editedSong, originalInstruments)
        )
        return ExpectedNoteEdit(playIndex, channelIndex, expectedNoteValue)
    }

    private fun assertExportedNote(spcRam: ByteArray, edit: ExpectedNoteEdit) {
        val exportedSong = NspcSequence.parse(spcRam, edit.playIndex)
        assertEquals(edit.expectedNoteValue, exportedSong.channels[edit.channelIndex].notes.first().noteValue)
    }

    private fun readSpcU16(spcRam: ByteArray, addr: Int): Int {
        return (spcRam[addr].toInt() and 0xFF) or ((spcRam[addr + 1].toInt() and 0xFF) shl 8)
    }

    private fun writeSpcU16(spcRam: ByteArray, addr: Int, value: Int) {
        spcRam[addr] = (value and 0xFF).toByte()
        spcRam[addr + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
