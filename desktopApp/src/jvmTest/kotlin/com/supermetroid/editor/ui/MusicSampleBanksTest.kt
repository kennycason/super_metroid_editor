package com.supermetroid.editor.ui

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class MusicSampleBanksTest {
    @TempDir lateinit var directory: File
    private val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
    private val sibling = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 6 }
    private data class Fixture(val parser: RomParser, val editor: EditorState, val sound: SoundEditorState)
    private fun fixture(): Fixture {
        val rom = File(directory, "base.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val parser = RomParser(rom.readBytes())
        val editor = EditorState().apply { testMode = true; initForRom(rom.path) }
        val sound = SoundEditorState().apply { selectTrack(track); openPianoRoll(parser, editor) }
        return Fixture(parser, editor, sound)
    }
    private fun target(f: Fixture, slot: Int = 24) = SampleBankPreview.Target(3,
        NspcSequence.Note(0, 120, NspcSequence.nameToNote("C4"), instrument = slot), track.name, "Test sound",
        MusicBankTransitions.snapshot(f.editor.project), track.id)
    private fun sample(shape: SampleAuthoring.Shape = SampleAuthoring.Shape.TRIANGLE) =
        SampleAuthoring.encode(SampleAuthoring.generate(shape).copy(trimStart = 16, trimEnd = 256, loopStart = 80))

    @Test fun `using a sample preserves other sources envelope tuning and arrangements and supports undo save reopen and ROM export`() = runBlocking {
        val f = fixture()
        val before = MusicBankResolver.resolve(f.parser, f.editor.project, 3).ram
        val original = f.sound.pianoRollInstruments[24]
        val audio = sample()
        val plan = MusicSampleBanks.plan(f.parser, target(f), audio, "Glass triangle")
        val audition = SampleBankPreview.load(f.parser, target(f)).build(audio)
        assertEquals(40, plan.srcn)
        assertTrue(plan.changed); assertFalse(plan.reused)
        assertTrue(f.sound.useWorkbenchSample(plan, f.parser, f.editor))
        assertEquals(1, f.sound.pianoRollUndoDepth)
        assertTrue(f.editor.project.musicEdits.isEmpty())
        val bank = f.editor.project.musicInstrumentBanks.getValue("03")
        assertEquals(1, bank.samples.size)
        val updated = f.sound.pianoRollInstruments[24]
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(original).drop(1).toByteArray(),
            PianoRollPreviewLogic.instrumentBytes(updated).drop(1).toByteArray())
        val ram = MusicBankResolver.resolve(f.parser, f.editor.project, 3).ram
        assertContentEquals(InstrumentAudition.render(audition), InstrumentAudition.render(InstrumentAudition.build(
            MusicBankResolver.sourceRam(f.parser, f.editor.project, 3), updated, target(f).note)), "Applied sound must match its audition")
        assertContentEquals(before.copyOfRange(0x6D00, 0x6DA0), ram.copyOfRange(0x6D00, 0x6DA0))
        for (i in 0 until 42) if (i != 24) assertContentEquals(before.copyOfRange(0x6C00 + i * 6, 0x6C06 + i * 6), ram.copyOfRange(0x6C00 + i * 6, 0x6C06 + i * 6))
        assertContentEquals(before.copyOfRange(0x6E00, 65536), ram.copyOfRange(0x6E00, 65536))
        for (play in listOf(5, 6)) assertEquals(NspcSequence.parse(before, play), NspcSequence.parse(ram, play))
        val saved = bank.samples.getValue(40)
        assertContentEquals(audio.brr, ram.copyOfRange(saved.startAddr, saved.startAddr + audio.brr.size))
        fun word(at: Int) = (ram[at].toInt() and 255) or ((ram[at + 1].toInt() and 255) shl 8)
        assertEquals(saved.startAddr + audio.loopByteOffset!!, word(0x6DA2))
        f.sound.loadPianoRollSampleCatalog()
        val catalog = f.sound.pianoRollSamples.single { it.srcn == 40 }
        assertEquals("Project sample", catalog.origin)
        assertEquals("Glass triangle", f.editor.musicSampleNames(3)[catalog.resourceId])
        val file = File(directory, "custom.smedit")
        assertTrue(ProjectFileService.saveProject(f.editor.project, file.path, null, false, {}))
        val editor = EditorState().apply { testMode = true; initForProject(file, ProjectFileService.loadProject(file)) }
        val reopened = SoundEditorState().apply { selectTrack(sibling); openPianoRoll(f.parser, editor) }
        assertContentEquals(ram, reopened.buildPianoRollSpcSnapshotRam(f.parser, reopened.editingSong!!))
        val rom = f.parser.copyRomData()
        assertFailsWith<IllegalStateException> { MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom,
            RomFreeSpaceAllocator(rom, f.parser::snesToPc, f.parser::pcToSnes, excludedRanges = listOf(rom.indices))) }
        assertContentEquals(f.parser.getRomData(), rom, "A failed export must not write samples, pointers or loader code")
        assertEquals(1, MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom))
        val exported = RomParser(rom)
        val output = SpcData.buildInitialSpcRam(exported).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, 3)) }
        assertContentEquals(ram, output)
        assertTrue(f.sound.undoPianoRollEdit(f.editor))
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertTrue(f.sound.redoPianoRollEdit(f.editor))
        assertEquals(saved, f.editor.project.musicInstrumentBanks.getValue("03").samples.getValue(40))
        assertEquals(40, f.sound.pianoRollInstruments[24].srcn)
    }

    @Test fun `identical samples reuse memory and a repeated application is a no op`() {
        val f = fixture()
        val audio = sample()
        val first = MusicSampleBanks.plan(f.parser, target(f), audio, "Triangle")
        f.sound.useWorkbenchSample(first, f.parser, f.editor)
        val same = MusicSampleBanks.plan(f.parser, target(f), audio, "Triangle")
        assertTrue(same.reused); assertFalse(same.changed)
        assertFalse(f.sound.useWorkbenchSample(same, f.parser, f.editor))
        assertEquals(1, f.sound.pianoRollUndoDepth)
        val other = MusicSampleBanks.plan(f.parser, target(f, 28), audio, "Triangle")
        assertTrue(other.reused)
        assertEquals(first.remainingBytes, other.remainingBytes)
        f.sound.useWorkbenchSample(other, f.parser, f.editor)
        assertEquals(1, f.editor.project.musicInstrumentBanks.getValue("03").samples.size)
        assertEquals(40, f.sound.pianoRollInstruments[28].srcn)
        f.sound.updatePianoRollInstrument(f.sound.pianoRollInstruments[28].copy(gain = 61), f.parser, f.editor)
        assertEquals(1, f.editor.project.musicInstrumentBanks.getValue("03").samples.size, "Envelope edits must retain owned samples")
    }

    @Test fun `stale plans and samples that exceed memory cannot change the bank or history`() {
        val f = fixture()
        val plan = MusicSampleBanks.plan(f.parser, target(f), sample(), "Triangle")
        f.sound.updatePianoRollInstrument(f.sound.pianoRollInstruments[24].copy(gain = 1), f.parser, f.editor)
        val before = MusicBankTransitions.snapshot(f.editor.project)
        assertFailsWith<IllegalArgumentException> { f.sound.useWorkbenchSample(plan, f.parser, f.editor) }
        assertEquals(before.musicInstrumentBanks, f.editor.project.musicInstrumentBanks)
        assertEquals(0, f.sound.pianoRollUndoDepth)
        val tooLarge = SampleAuthoring.encode(SampleDraft("Long", List(SampleAuthoring.MAX_FRAMES) { 1000 }))
        assertFailsWith<IllegalStateException> { MusicSampleBanks.plan(f.parser, target(f), tooLarge, "Long") }
        assertEquals(before.musicInstrumentBanks, f.editor.project.musicInstrumentBanks)
        f.sound.selectTrack(sibling); f.sound.openPianoRoll(f.parser, f.editor)
        assertFailsWith<IllegalArgumentException> { f.sound.useWorkbenchSample(plan, f.parser, f.editor) }
    }

    @Test fun `corrupt sample ownership rejects overlaps and leaves ROM bytes untouched`() {
        val f = fixture()
        val plan = MusicSampleBanks.plan(f.parser, target(f), sample(), "Triangle")
        f.sound.useWorkbenchSample(plan, f.parser, f.editor)
        val saved = plan.bank.samples.getValue(40)
        for (corrupt in listOf(saved.copy(srcn = 0), saved.copy(startAddr = 0x6E00), saved.copy(loopByteOffset = 1), saved.copy(brrBase64 = "not base64"))) {
            f.editor.project.musicInstrumentBanks["03"] = plan.bank.copy(samples = mutableMapOf(40 to corrupt))
            val rom = f.parser.copyRomData()
            assertFails { MusicRomExporter(f.editor.project, {}).applyMusicEditsToRom(rom) }
            assertContentEquals(f.parser.getRomData(), rom)
        }
        f.editor.project.musicInstrumentBanks["03"] = plan.bank.copy(samples = mutableMapOf(40 to saved, 41 to saved.copy(srcn = 41)))
        assertFailsWith<IllegalArgumentException> { MusicBankResolver.resolve(f.parser, f.editor.project, 3) }
        val source = MusicBankResolver.sourceRam(f.parser, SmEditProject(romPath = "test.sfc"), 3)
        f.editor.project.musicInstrumentBanks["03"] = plan.bank
        assertFailsWith<IllegalArgumentException> { MusicSampleBanks.writes(f.parser, f.editor.project, 3, source,
            mapOf(0x6DA0 to ByteArray(4))) }
    }

    @Test fun `production export loads custom directory sources on the console and restores banks and effects`() {
        val f = fixture()
        val audio = sample(SampleAuthoring.Shape.SINE)
        val raw = SampleAuthoring.preview(SpcData.buildInitialSpcRam(f.parser), audio)
        val tuning = NspcRenderer.readInstrumentTable(raw.ram)[24].pitchAdj
        f.sound.updatePianoRollInstrument(f.sound.pianoRollInstruments[24].copy(adsr1 = 0, gain = 127, pitchAdj = tuning), f.parser, f.editor)
        val song = NspcSequence.Song(tempo = 36).also {
            it.channels[0].notes += NspcSequence.Note(0, 120, NspcSequence.nameToNote("C4"), instrument = 24)
        }
        f.sound.notifySongChanged(song, f.editor)
        val plan = MusicSampleBanks.plan(f.parser, target(f), audio, "Native sine")
        f.sound.useWorkbenchSample(plan, f.parser, f.editor)
        val otherTrack = SpcData.KNOWN_TRACKS.first { it.songSet == 0x0F && it.playIndex == 5 }
        val other = SoundEditorState().apply { selectTrack(otherTrack); openPianoRoll(f.parser, f.editor) }
        other.updatePianoRollInstrument(other.pianoRollInstruments[24].copy(adsr1 = 0, gain = 127, pitchAdj = tuning), f.parser, f.editor)
        other.notifySongChanged(PianoRollPreviewLogic.deepCopySong(song), f.editor)
        val otherAudio = sample(SampleAuthoring.Shape.TRIANGLE)
        val otherTarget = SampleBankPreview.Target(0x0F, target(f).note, otherTrack.name, "Other sound",
            MusicBankTransitions.snapshot(f.editor.project), otherTrack.id)
        val otherPlan = MusicSampleBanks.plan(f.parser, otherTarget, otherAudio, "Native triangle")
        other.useWorkbenchSample(otherPlan, f.parser, f.editor)
        val authored = mapOf(3 to (plan to audio), 0x0F to (otherPlan to otherAudio))
        val rom = f.parser.copyRomData()
        MusicRomExporter(f.editor.project, {}).applyMusicEditsToRom(rom)
        val boot = SpcData.buildInitialSpcRam(f.parser)
        val exported = RomParser(rom)
        val warm = boot.copyOf()
        NativeMusicUploadFixture(directory, rom).use { runtime ->
            for (set in listOf(3, 0x0F, 6, 3, 0x42, 3)) {
                runtime.queueBank(set)
                val expected = MusicBankResolver.resolve(f.parser, f.editor.project, set).ram
                val actual = runtime.ram()
                SpcData.applyTransferBlocks(warm, SpcData.findSongSetTransferData(exported, set))
                assertContentEquals(warm.copyOfRange(0x6C00, 0x6E00), actual.copyOfRange(0x6C00, 0x6E00), "Bank $set native transfer result")
                assertContentEquals(expected.copyOfRange(0x6C90, 0x6C96), actual.copyOfRange(0x6C90, 0x6C96), "Bank $set selected sound restoration")
                assertContentEquals(expected.copyOfRange(0x6DA0, 0x6E00), actual.copyOfRange(0x6DA0, 0x6E00), "Bank $set custom directory restoration")
                assertContentEquals(boot.copyOfRange(0x1500, 0x530E), actual.copyOfRange(0x1500, 0x530E))
                authored[set]?.let { (application, sample) ->
                    val stored = application.bank.samples.getValue(40)
                    assertContentEquals(sample.brr, actual.copyOfRange(stored.startAddr, stored.startAddr + sample.brr.size))
                    runtime.play(5)
                    assertTrue(runtime.frames(45).any { it != 0.toShort() }, "Custom source 40 must play in the native SPC engine")
                }
            }
            assertTrue(runtime.softResetToTitle().any { it != 0.toShort() }, "Soft reset must reload and play the project sample")
            assertContentEquals(MusicBankResolver.resolve(f.parser, f.editor.project, 3).ram.copyOfRange(0x6DA0, 0x6DA4),
                runtime.ram().copyOfRange(0x6DA0, 0x6DA4))
        }
        for (library in 1..3) NativeMusicUploadFixture(File(directory, "effect-$library"), rom).use { runtime ->
            runtime.queueBank(3); runtime.queueBank(0x0F); runtime.queueBank(6)
            runtime.effect(library, 0); runtime.frames(8)
            assertTrue(runtime.frames(30).all { it == 0.toShort() }, "Silent control must not mask the effect")
            runtime.effect(library, if (library == 3) 5 else 1)
            assertTrue(runtime.frames(60).any { it != 0.toShort() }, "Effect library $library must still work")
        }
    }
}
