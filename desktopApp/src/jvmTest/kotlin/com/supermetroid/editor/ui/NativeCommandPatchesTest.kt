package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class NativeCommandPatchesTest {
    @TempDir lateinit var directory: File
    @Test fun `operand edits retain conductor calls loops sequence bytes and note timing`() {
        val ram = ByteArray(65536)
        fun word(at: Int, value: Int) { ram[at] = value.toByte(); ram[at + 1] = (value ushr 8).toByte() }
        word(0x5828, 0x5900); word(0x5900, 0x5910); word(0x5910, 0x5930)
        byteArrayOf(0xE7.toByte(), 36, 0xE0.toByte(), 24, 0xEF.toByte(), 0x50, 0x59, 2, 0).copyInto(ram, 0x5930)
        byteArrayOf(0xED.toByte(), 100, 48, 0x7F, 0xA4.toByte(), 0).copyInto(ram, 0x5950)
        val original = NspcSequence.parse(ram, 5)
        val edits = PianoRollPreviewLogic.deepCopySong(original)
        val levels = edits.channels[0].commands.filter { it.command == 0xED }
        assertEquals(2, levels.size)
        levels.first().params[0] = 80
        assertNull(NativeCommandPatches.plan(ram, 5, edits), "One occurrence cannot silently alter all calls to shared bytes")
        levels.last().params[0] = 80
        val plan = NativeCommandPatches.plan(ram, 5, edits)!!
        assertEquals(setOf(0x5951), plan.keys)
        val updated = ram.copyOf().also { for ((at, data) in plan) data.copyInto(it, at) }
        assertContentEquals(ram.copyOfRange(0x5900, 0x5951), updated.copyOfRange(0x5900, 0x5951))
        assertEquals(original.channels[0].notes, NspcSequence.parse(updated, 5).channels[0].notes)
        assertTrue(NspcSequence.parse(updated, 5).channels[0].commands.filter { it.command == 0xED }.all { it.params[0] == 80 })
        word(0x582A, 0x5900)
        val sibling = NativeSoundUsage.inspect(ram, listOf(6))
        assertTrue(sibling.complete); assertTrue(0x5951 in sibling.sequenceBytes)
        assertNull(NativeCommandPatches.plan(ram, 5, edits, sibling.sequenceBytes), "An operand shared with a sibling needs isolated re-encoding")
    }
    @Test fun `controller only edits create saved project history and export without flattening the native arrangement`() {
        val file = File(directory, "base.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val parser = RomParser(file.readBytes())
        val editor = EditorState().apply { testMode = true; initForRom(file.path) }
        val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
        val state = SoundEditorState().apply { selectTrack(track); openPianoRoll(parser, editor) }
        val original = PianoRollPreviewLogic.deepCopySong(state.editingSong!!)
        val changed = PianoRollPreviewLogic.deepCopySong(original)
        val source = MusicBankResolver.sourceRam(parser, editor.project, 3)
        val native = NspcSequence.parse(source, 5)
        val opcode = native.channels.flatMap { it.commands }.first { it.command == 0xED || it.command == 0xE5 }.command
        for (voice in changed.channels) for (command in voice.commands.filter { it.command == opcode }) command.params[0] = (command.params[0] / 2)
        assertNotNull(NativeCommandPatches.plan(source, 5, changed))
        state.recordPianoRollEdit("Expression"); state.notifySongChanged(changed, editor)
        assertEquals(1, editor.project.musicEdits.size, "Commands must not be lost by the note-only dirty check")
        assertEquals(1, state.pianoRollUndoDepth)
        val projectFile = File(directory, "expression.smedit")
        assertTrue(ProjectFileService.saveProject(editor.project, projectFile.path, null, false, {}))
        val reopened = ProjectFileService.loadProject(projectFile)
        val expected = MusicBankResolver.resolve(parser, reopened, 3).ram
        val rom = parser.copyRomData(); assertTrue(MusicRomExporter(reopened, {}).applyMusicEditsToRom(rom) > 0)
        val exported = RomParser(rom)
        val actual = SpcData.buildInitialSpcRam(exported).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, 3)) }
        assertTrue(expected.contentEquals(actual))
        assertEquals(original.channels.map { it.notes }, NspcSequence.parse(actual, 5).channels.map { it.notes })
        assertTrue(state.undoPianoRollEdit(editor)); assertTrue(editor.project.musicEdits.isEmpty())
        assertTrue(state.redoPianoRollEdit(editor)); assertEquals(1, editor.project.musicEdits.size)
    }
    @Test fun `usage inventory follows finite loop exits and subroutine operands without a playback tick cutoff`() {
        val ram = ByteArray(65536)
        fun word(at: Int, value: Int) { ram[at] = value.toByte(); ram[at + 1] = (value ushr 8).toByte() }
        word(0x5828, 0x5900)
        word(0x5900, 0x5920); word(0x5902, 2); word(0x5904, 0x5900); word(0x5906, 0x5930)
        word(0x5920, 0x5940); word(0x5930, 0x5950)
        byteArrayOf(0xE0.toByte(), 24, 127, 0x7F, 0xA4.toByte(), 0).copyInto(ram, 0x5940)
        byteArrayOf(0xEF.toByte(), 0x60, 0x59, 10, 0).copyInto(ram, 0x5950)
        byteArrayOf(0xE0.toByte(), 41, 127, 0x7F, 0xA4.toByte(), 0).copyInto(ram, 0x5960)
        val usage = NativeSoundUsage.inspect(ram, listOf(5))
        assertTrue(usage.complete); assertTrue(41 in usage.instruments)
        ram[0x5960] = 0xFF.toByte()
        assertFalse(NativeSoundUsage.inspect(ram, listOf(5)).complete)
    }
}
