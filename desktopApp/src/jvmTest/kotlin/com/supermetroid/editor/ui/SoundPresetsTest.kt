package com.supermetroid.editor.ui

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class SoundPresetsTest {
    @TempDir lateinit var directory: File
    private data class Fixture(val parser: RomParser, val editor: EditorState, val sound: SoundEditorState)
    private fun fixture(bank: Int = 3): Fixture {
        val file = File(directory, "base.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val parser = RomParser(file.readBytes())
        val editor = EditorState().apply { testMode = true; initForRom(file.path) }
        val sound = SoundEditorState().apply {
            selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == bank && it.playIndex == 5 }); openPianoRoll(parser, editor)
        }
        return Fixture(parser, editor, sound)
    }
    private fun target(f: Fixture, slot: Int = 24) = SampleBankPreview.Target(f.sound.currentSongSet,
        NspcSequence.Note(0, 96, NspcSequence.nameToNote("D4"), velocity = 10, quantize = 6, instrument = slot),
        "Test bank", "Test sound", MusicBankTransitions.snapshot(f.editor.project), f.sound.selectedTrackId)
    private fun generated(name: String = "Glass") : SoundPreset {
        val sample = SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.TRIANGLE).copy(trimStart = 16, trimEnd = 256, loopStart = 80))
        return SoundPreset(UUID.randomUUID().toString(), name, 0xAF, 0xA0, 0x7F, 0x0001,
            Base64.getEncoder().encodeToString(sample.brr), sample.loopByteOffset)
    }
    private fun capture(f: Fixture, slot: Int = 24) = SoundPresets.capture(f.parser,
        MusicBankResolver.sourceRam(f.parser, f.editor.project, f.sound.currentSongSet), slot, "Saved native sound")

    @Test fun `library is portable persistent and read only on open and isolates corrupt files`() {
        val path = File(directory, "library")
        val library = SoundPresetLibrary(path)
        assertTrue(library.load().presets.isEmpty()); assertFalse(path.exists())
        val a = generated("Zither"); val b = generated("Bell")
        library.save(a); library.save(b)
        assertEquals(listOf(b, a), SoundPresetLibrary(path).load().presets)
        val aFile = File(path, "${a.id}.smedit-sound")
        val before = aFile.readBytes(); val modified = aFile.lastModified()
        val broken = File(path, "broken.smedit-sound").apply { writeText("{broken") }
        File(path, "mismatch.smedit-sound").writeText(SoundPresets.encode(a))
        val loaded = library.load()
        assertEquals(listOf(b, a), loaded.presets); assertEquals(2, loaded.problems.size)
        assertContentEquals(before, aFile.readBytes()); assertEquals(modified, aFile.lastModified())
        assertEquals("{broken", broken.readText())
        library.save(a.copy(name = "New name"))
        assertEquals(4, path.listFiles()!!.size, "Updating a preset is atomic and leaves unrelated files alone")
        assertTrue(path.listFiles()!!.none { it.extension == "tmp" })
        library.delete(a); assertEquals(listOf(b), library.load().presets)
    }

    @Test fun `malformed native data and unsafe identities are rejected before saving`() {
        val p = generated()
        for (bad in listOf(p.copy(id = "../escape"), p.copy(version = 2), p.copy(name = " "), p.copy(name = "x".repeat(81)),
            p.copy(adsr1 = 256), p.copy(pitchAdj = -1), p.copy(brrBase64 = "bad base64"), p.copy(brrBase64 = ""),
            p.copy(loopByteOffset = 1), p.copy(loopByteOffset = 999999), p.copy(loopByteOffset = null),
            p.copy(noiseSource = 0x90), p.copy(brrBase64 = null), p.copy(brrBase64 = null, loopByteOffset = null, noiseSource = 3))) {
            assertFails { SoundPresetLibrary(File(directory, "invalid")).save(bad) }
        }
        assertFalse(File(directory, "invalid").exists())
    }

    @Test fun `native presets reuse destination samples with no new memory or changes on repeated application`() {
        val f = fixture()
        val p = capture(f)
        val ram = MusicBankResolver.sourceRam(f.parser, f.editor.project, 3)
        assertContentEquals(InstrumentAudition.render(InstrumentAudition.build(ram, f.sound.pianoRollInstruments[24], target(f).note)),
            InstrumentAudition.render(SoundPresets.preview(f.parser, p, 24, target(f).note, 36)))
        val before = MusicBankTransitions.snapshot(f.editor.project)
        val plan = SoundPresets.plan(f.parser, target(f), p)
        assertTrue(plan.reused); assertEquals(0, plan.addedBytes); assertFalse(plan.changed)
        assertFalse(f.sound.useSoundPreset(plan, f.parser, f.editor)); assertEquals(0, f.sound.pianoRollUndoDepth)
        val other = SoundPresets.plan(f.parser, target(f, 28), p)
        assertTrue(other.reused); assertTrue(other.bank.samples.isEmpty())
        assertTrue(f.sound.useSoundPreset(other, f.parser, f.editor)); assertEquals(1, f.sound.pianoRollUndoDepth)
        val saved = SoundPresets.capture(f.parser, MusicBankResolver.sourceRam(f.parser, f.editor.project, 3), 28, p.name)
        assertEquals(p.copy(id = saved.id), saved)
        assertEquals(before.musicEdits, f.editor.project.musicEdits)
        assertFalse(SoundPresets.plan(f.parser, target(f, 28), p).changed)
    }

    @Test fun `applying portable sample plus settings is one undo and survives library deletion save reopen and export`() {
        val f = fixture()
        val p = generated()
        val lib = SoundPresetLibrary(File(directory, "library")).also { it.save(p) }
        val before = MusicBankResolver.resolve(f.parser, f.editor.project, 3).ram
        val plan = SoundPresets.plan(f.parser, target(f), p)
        assertFalse(plan.reused); assertTrue(plan.addedBytes > 0)
        assertTrue(f.sound.useSoundPreset(plan, f.parser, f.editor)); assertEquals(1, f.sound.pianoRollUndoDepth)
        val entry = f.sound.pianoRollInstruments[24]
        assertEquals(40, entry.srcn)
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(p.instrument(24, 40)), PianoRollPreviewLogic.instrumentBytes(entry))
        val ram = MusicBankResolver.resolve(f.parser, f.editor.project, 3).ram
        assertContentEquals(before.copyOfRange(0x6D00, 0x6DA0), ram.copyOfRange(0x6D00, 0x6DA0))
        for (i in 0 until 42) if (i != 24) assertContentEquals(before.copyOfRange(0x6C00 + i * 6, 0x6C06 + i * 6), ram.copyOfRange(0x6C00 + i * 6, 0x6C06 + i * 6))
        val expected = InstrumentAudition.render(SoundPresets.preview(f.parser, p, 24, target(f).note, InstrumentAudition.TEMPO))
        val actual = InstrumentAudition.render(InstrumentAudition.build(MusicBankResolver.sourceRam(f.parser, f.editor.project, 3), entry, target(f).note))
        assertTrue(expected.any { it != 0.toShort() }); assertContentEquals(expected, actual)
        lib.delete(p)
        val file = File(directory, "presets.smedit")
        assertTrue(ProjectFileService.saveProject(f.editor.project, file.path, null, false, {}))
        val editor = EditorState().apply { testMode = true; initForProject(file, ProjectFileService.loadProject(file)) }
        assertContentEquals(ram, MusicBankResolver.resolve(f.parser, editor.project, 3).ram)
        val rom = f.parser.copyRomData(); assertEquals(1, MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom))
        val exported = RomParser(rom)
        val output = SpcData.buildInitialSpcRam(exported).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, 3)) }
        assertContentEquals(ram, output)
        assertTrue(f.sound.undoPianoRollEdit(f.editor)); assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertTrue(f.sound.redoPianoRollEdit(f.editor)); assertContentEquals(ram, MusicBankResolver.resolve(f.parser, f.editor.project, 3).ram)
        val captured = capture(f)
        val g = fixture(0x0F)
        val cross = SoundPresets.plan(g.parser, target(g, 25), captured)
        assertTrue(g.sound.useSoundPreset(cross, g.parser, g.editor))
        assertEquals(1, g.editor.project.musicInstrumentBanks.getValue("0F").samples.size)
        val copied = SoundPresets.capture(g.parser, MusicBankResolver.sourceRam(g.parser, g.editor.project, 0x0F), 25, captured.name)
        assertEquals(captured.copy(id = copied.id), copied)
    }

    @Test fun `noise presets preserve rate envelope and tuning without allocating samples`() {
        val f = fixture()
        val p = generated().copy(brrBase64 = null, loopByteOffset = null, noiseSource = 0x9D)
        val plan = SoundPresets.plan(f.parser, target(f), p)
        assertEquals(0, plan.addedBytes); assertTrue(plan.bank.samples.isEmpty())
        f.sound.useSoundPreset(plan, f.parser, f.editor)
        assertEquals(0x9D, f.sound.pianoRollInstruments[24].srcn)
        val captured = capture(f)
        assertEquals(p.copy(id = captured.id, name = captured.name), captured)
        val ram = MusicBankResolver.sourceRam(f.parser, f.editor.project, 3)
        val preview = InstrumentAudition.render(SoundPresets.preview(f.parser, p, 24, target(f).note, 36, noiseRam = ram))
        val applied = InstrumentAudition.render(InstrumentAudition.build(ram, f.sound.pianoRollInstruments[24], target(f).note))
        assertContentEquals(preview, applied)
    }

    @Test fun `applying the native baseline clears a manual override with undo and no empty bank record`() {
        val f = fixture()
        val original = capture(f)
        val source = f.sound.pianoRollInstruments[24]
        f.sound.commitPianoRollInstrument(f.sound.selectedTrackId, source.copy(gain = source.gain xor 1), f.parser, f.editor)
        assertEquals(1, f.sound.pianoRollUndoDepth)
        val edited = MusicBankTransitions.snapshot(f.editor.project)
        val plan = SoundPresets.plan(f.parser, target(f), original)
        assertTrue(plan.changed); assertTrue(plan.bank.instruments.isEmpty()); assertTrue(plan.bank.samples.isEmpty())
        assertTrue(f.sound.useSoundPreset(plan, f.parser, f.editor))
        assertEquals(2, f.sound.pianoRollUndoDepth); assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertTrue(f.sound.undoPianoRollEdit(f.editor)); assertEquals(edited.musicInstrumentBanks, f.editor.project.musicInstrumentBanks)
        assertTrue(f.sound.redoPianoRollEdit(f.editor)); assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
    }

    @Test fun `too large samples and stale targets leave bank and history untouched but remain auditionable`() {
        val f = fixture()
        val short = generated()
        val stale = SoundPresets.plan(f.parser, target(f), short)
        f.sound.updatePianoRollInstrument(f.sound.pianoRollInstruments[24].copy(gain = 1), f.parser, f.editor)
        val before = MusicBankTransitions.snapshot(f.editor.project)
        assertFailsWith<IllegalArgumentException> { f.sound.useSoundPreset(stale, f.parser, f.editor) }
        val long = SampleAuthoring.encode(SampleDraft("Long", List(SampleAuthoring.MAX_FRAMES) { 1000 }))
        val large = short.copy(brrBase64 = Base64.getEncoder().encodeToString(long.brr), loopByteOffset = long.loopByteOffset)
        assertFailsWith<IllegalStateException> { SoundPresets.plan(f.parser, target(f), large) }
        assertEquals(before.musicInstrumentBanks, f.editor.project.musicInstrumentBanks); assertEquals(0, f.sound.pianoRollUndoDepth)
        assertTrue(InstrumentAudition.render(SoundPresets.preview(f.parser, large, 24, target(f).note, 36)).any { it != 0.toShort() })
    }

    @Test fun `linked intro tail capture preserves predictor history and native playback without re encoding`() {
        val f = fixture()
        val ram = SpcData.buildInitialSpcRam(f.parser)
        val sample = SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE)).brr
        val intro = sample.copyOfRange(0, 18).also { it[9] = (it[9].toInt() or 3).toByte() }
        val tail = sample.copyOfRange(18, sample.size)
        intro.copyInto(ram, 0x8000); tail.copyInto(ram, 0x9000)
        byteArrayOf(0, 0x80.toByte(), 0, 0x90.toByte()).copyInto(ram, 0x6D00)
        val source = generated().instrument(24, 0)
        PianoRollPreviewLogic.instrumentBytes(source).copyInto(ram, 0x6C90)
        val saved = SoundPresets.capture(f.parser, ram, 24, "Linked")
        val flat = intro.copyOf().also { it[9] = (it[9].toInt() and 0xFE).toByte() } + tail
        assertContentEquals(flat, SoundPresets.bytes(saved)); assertEquals(18, saved.loopByteOffset)
        val note = target(f).note
        assertContentEquals(InstrumentAudition.render(InstrumentAudition.build(ram, source, note)),
            InstrumentAudition.render(SoundPresets.preview(f.parser, saved, 24, note, 36)))
        assertContentEquals(intro, ram.copyOfRange(0x8000, 0x8012), "Capture never edits source RAM")
        // A linked one-shot tail must finish rather than invent a loop.
        ram[0x9000 + tail.size - 9] = (ram[0x9000 + tail.size - 9].toInt() and 0xFD).toByte()
        assertNull(SoundPresets.capture(f.parser, ram, 24, "One shot").loopByteOffset)
    }
}
