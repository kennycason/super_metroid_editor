package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.*

class MusicSamplePlacementTest {
    private fun recording(frames: Int = 32) = SampleAuthoring.encode(SampleDraft("Test", List(frames) { 1000 }))
    private fun target(project: SmEditProject = SmEditProject(romPath = "test.sfc"), bank: Int = 3, pitch: String = "C4", slot: Int = 24) =
        SampleBankPreview.Target(bank, NspcSequence.Note(0, 120, NspcSequence.nameToNote(pitch), instrument = slot), "Title Screen", "Test sound", project)

    @Test fun `uploads including zero and FF padding are ownership not free space`() {
        val start = MusicSamplePlacement.START
        val layout = MusicSamplePlacement.inspect(ByteArray(65536), listOf(
            SpcData.TransferBlock(start, ByteArray(32)),
            SpcData.TransferBlock(start + 16, ByteArray(48) { 0xFF.toByte() }),
            SpcData.TransferBlock(0x6BF0, ByteArray(32)),
        ))
        assertEquals(listOf(MusicSamplePlacement.Range(start + 64, 0x6BF0)), layout.gaps)
        assertEquals(0x6BF0 - start - 64, layout.totalBytes)
        assertEquals(start + 64, layout.place(recording()).start)
    }

    @Test fun `fragmented total space cannot admit a recording larger than each gap`() {
        val start = MusicSamplePlacement.START
        val layout = MusicSamplePlacement.inspect(ByteArray(65536), listOf(
            SpcData.TransferBlock(start + 18, ByteArray(20)),
            SpcData.TransferBlock(start + 56, ByteArray(MusicSamplePlacement.END - start - 56)),
        ))
        assertEquals(36, layout.totalBytes)
        assertEquals(18, layout.largestBytes)
        assertEquals(MusicSamplePlacement.Range(start, start + 18), layout.place(recording()))
        assertTrue(assertFailsWith<IllegalStateException> { layout.place(recording(48)) }.message.orEmpty().contains("largest bank gap 18 B"))
    }

    @Test fun `directory start linked loop and unterminated chains reserve their bytes even without uploads`() {
        val ram = ByteArray(65536)
        fun word(at: Int, value: Int) { ram[at] = value.toByte(); ram[at + 1] = (value ushr 8).toByte() }
        word(0x6D00, 0x6500); word(0x6D02, 0x6600)
        ram[0x6500] = 3; ram[0x6600] = 1
        word(0x6D04, 0x6700) // No END flag: do not mistake unknown data for free RAM.
        val layout = MusicSamplePlacement.inspect(ram, emptyList())
        for (range in listOf(0x6500 until 0x6509, 0x6600 until 0x6609, 0x6700 until 0x6C00)) {
            assertTrue(layout.gaps.none { it.start <= range.last && it.endExclusive > range.first })
        }
    }

    @Test fun `malformed blocks and BRR metadata fail closed without truncation`() {
        for (block in listOf(SpcData.TransferBlock(-1, byteArrayOf(1)), SpcData.TransferBlock(65535, byteArrayOf(1, 2)))) {
            assertFailsWith<IllegalArgumentException> { MusicSamplePlacement.inspect(ByteArray(65536), listOf(block)) }
        }
        val layout = MusicSamplePlacement.inspect(ByteArray(65536), emptyList())
        val sample = recording()
        for (invalid in listOf(sample.copy(brr = byteArrayOf()), sample.copy(brr = ByteArray(10)),
            sample.copy(pcm = ShortArray(16)), sample.copy(loopFrame = 1), sample.copy(loopFrame = 32),
            sample.copy(brr = sample.brr.copyOf().also { it[0] = 1 }),
            sample.copy(brr = sample.brr.copyOf().also { it[9] = 0 }),
            sample.copy(brr = sample.brr.copyOf().also { it[9] = 3 }))) {
            assertFailsWith<IllegalArgumentException> { layout.place(invalid) }
        }
        val full = MusicSamplePlacement.inspect(ByteArray(65536), listOf(
            SpcData.TransferBlock(MusicSamplePlacement.START, ByteArray(MusicSamplePlacement.END - MusicSamplePlacement.START))))
        assertEquals(0, full.largestBytes)
        assertFailsWith<IllegalStateException> { full.place(sample) }
    }

    @Test fun `all native bank layouts preserve boot and bank uploads without claiming spare source slots`() {
        val parser = TestRomHelper.requireRomParser()
        val original = parser.copyRomData()
        val project = SmEditProject(romPath = "test.sfc")
        val boot = SpcData.parseTransferBlocks(original, parser.snesToPc(0xCF8000))
        for (set in SpcData.TRANSFER_SONG_SETS.filter { it > 0 }) {
            val context = SampleBankPreview.load(parser, target(project, set))
            val bank = SpcData.findSongSetTransferData(parser, set)
            for (gap in context.layout.gaps) {
                assertTrue(gap.start >= MusicSamplePlacement.START && gap.endExclusive <= MusicSamplePlacement.END)
                assertTrue((boot + bank).none { it.destAddr < gap.endExclusive && it.destAddr + it.data.size > gap.start }, "Bank $set must preserve every uploaded byte")
            }
            assertTrue(context.layout.largestBytes >= 18, "Bank $set should fit a two-block recording without reclamation")
            val sample = recording()
            val allocation = context.layout.place(sample)
            val preview = context.build(sample)
            assertContentEquals(sample.brr, preview.ram.copyOfRange(allocation.start, allocation.endExclusive))
            assertEquals(context.layout.place(sample), allocation, "Planning is deterministic, not a reservation or project mutation")
        }
        assertTrue(project.musicEdits.isEmpty()); assertTrue(project.musicInstrumentBanks.isEmpty())
        assertContentEquals(original, parser.copyRomData())
        val wreckedShip = SampleBankPreview.load(parser, target(project, 0x30))
        assertEquals(217, wreckedShip.layout.largestBytes)
        assertFailsWith<IllegalStateException> { wreckedShip.layout.place(SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE))) }
    }

    private fun importedProject(parser: RomParser, engineChange: Boolean = false): SmEditProject {
        val project = SmEditProject(romPath = "test.sfc")
        for ((play, address) in listOf(5 to 0x6500, 6 to 0x6700)) {
            val song = NspcSequence.Song(tempo = 36).also { it.channels[0].notes += NspcSequence.Note(0, 48, 0xA4, instrument = 24) }
            val blocks = NspcSequence.encode(song, play, conductorAddrOverride = address, sequenceEndAddr = address + 128)
                .map { (at, data) -> SpcData.TransferBlock(at, data) }.toMutableList()
            if (play == 6) {
                blocks += SpcData.TransferBlock(0x6600, recording().brr)
                blocks += SpcData.TransferBlock(0x6D60, byteArrayOf(0, 0x66, 0, 0x66))
                if (engineChange) blocks += SpcData.TransferBlock(0x1500, byteArrayOf((SpcData.buildInitialSpcRam(parser)[0x1500].toInt() xor 1).toByte()))
            }
            val payload = MusicEditConversion.toProjectNativePayload(MusicTrackInterchange.NativePayload(blocks, play, "$play.nspc", "N-SPC"), song)
            val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == play }
            project.musicEdits[MusicEditConversion.key(3, play)] = MusicEditConversion.toProjectEdit(track, song, payload)
        }
        return project
    }

    @Test fun `sibling imports and relocated arrangement writes all remain reserved`() {
        val parser = TestRomHelper.requireRomParser()
        val project = importedProject(parser)
        val edit = project.musicEdits.getValue("03:05")
        val changed = MusicEditConversion.toSong(edit).also { it.channels[0].notes[0].noteValue++ }
        val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
        project.musicEdits["03:05"] = MusicEditConversion.toProjectEdit(track, changed, edit.nativePayload)
        val resolved = MusicBankResolver.resolve(parser, project, 3)
        val context = SampleBankPreview.load(parser, target(project))
        val imports = project.musicEdits.values.flatMap { MusicEditConversion.toTransferBlocks(it.nativePayload!!) }
        for (gap in context.layout.gaps) assertTrue((resolved.blocks + imports).none {
            it.destAddr < gap.endExclusive && it.destAddr + it.data.size > gap.start
        })
        assertNotNull(ImportedMusicResolver.resolve(parser, project.musicEdits.getValue("03:05")).allocation,
            "Fixture must include an actual relocated edit")
    }

    @Test fun `unsupported bank engine and invalid targets are rejected without fallback`() {
        val parser = TestRomHelper.requireRomParser()
        assertTrue(assertFailsWith<IllegalArgumentException> { SampleBankPreview.load(parser, target(importedProject(parser, true))) }
            .message.orEmpty().contains("original SPC engine"))
        for (bad in listOf(target(bank = 0), target(bank = 2), target(slot = 42))) {
            assertFailsWith<IllegalArgumentException> { SampleBankPreview.load(parser, bad) }
        }
    }

    @Test fun `tryout uses detached shared envelope tuning note and correct trimmed loop pointers`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "test.sfc")
        val source = MusicBankResolver.sourceRam(parser, project, 3)
        val instrument = NspcRenderer.readInstrumentTable(source)[24].copy(adsr1 = 0, gain = 0x55, pitchAdj = 0x0180)
        project.musicInstrumentBanks["03"] = MusicInstrumentBankEdit(mutableMapOf(24 to MusicInstrumentBanks.entry(instrument)))
        val input = target(project, pitch = "F1")
        val context = SampleBankPreview.load(parser, input)
        val draft = SampleAuthoring.generate(SampleAuthoring.Shape.TRIANGLE).copy(trimStart = 16, trimEnd = 256, loopStart = 80)
        val sample = SampleAuthoring.encode(draft)
        val allocation = context.layout.place(sample)
        val preview = context.build(sample)
        fun word(at: Int) = (preview.ram[at].toInt() and 255) or ((preview.ram[at + 1].toInt() and 255) shl 8)
        assertEquals(allocation.start, word(0x6D00 + instrument.srcn * 4))
        assertEquals(allocation.start + 36, word(0x6D02 + instrument.srcn * 4))
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(instrument), preview.ram.copyOfRange(0x6C90, 0x6C96))
        assertEquals(NspcSequence.nameToNote("F1"), NspcSequence.parse(preview.ram, InstrumentAudition.PLAY_INDEX).channels[0].notes.first().noteValue)
        assertContentEquals(source.copyOfRange(0x6E00, 65536), preview.ram.copyOfRange(0x6E00, 65536), "All existing high-RAM recordings must remain intact")
        input.note.noteValue = 0xA4
        project.musicInstrumentBanks.clear()
        assertTrue(preview.ram.contentEquals(context.build(sample).ram), "Later input changes cannot alter an open tryout")
        project.musicInstrumentBanks["03"] = MusicInstrumentBankEdit(mutableMapOf(24 to MusicInstrumentBanks.entry(instrument.copy(srcn = 0x85))))
        val noise = SampleBankPreview.load(parser, target(project)).build(sample)
        assertEquals(0, noise.ram[0x6C90].toInt(), "A noise sound can try a recording using a private DIR entry, not an inferred unused slot")
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(instrument).drop(1).toByteArray(), noise.ram.copyOfRange(0x6C91, 0x6C96))
    }

    @Test fun `native bank tryout honors pitch and silent gain instead of using recorded rate tuning`() {
        val parser = TestRomHelper.requireRomParser()
        val ram = SpcData.buildInitialSpcRam(parser)
        val sample = SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE))
        val raw = SampleAuthoring.preview(ram, sample)
        val tuned = NspcRenderer.readInstrumentTable(raw.ram)[24].copy(srcn = 24)
        fun project(gain: Int) = SmEditProject(romPath = "test.sfc").also {
            it.musicInstrumentBanks["03"] = MusicInstrumentBankEdit(mutableMapOf(24 to MusicInstrumentBanks.entry(tuned.copy(gain = gain))))
        }
        val low = InstrumentAudition.render(SampleBankPreview.load(parser, target(project(127))).build(sample))
        val high = InstrumentAudition.render(SampleBankPreview.load(parser, target(project(127), pitch = "C5")).build(sample))
        fun crossings(pcm: ShortArray) = (10001 until 20000).count { pcm[it - 1] < 0 && pcm[it] >= 0 }
        assertTrue(crossings(low) in 74..83, "C4 recorded-rate reference: ${crossings(low)} crossings")
        assertTrue(crossings(high) in 150..163, "C5 must double sample playback pitch: ${crossings(high)} crossings")
        val silent = InstrumentAudition.render(SampleBankPreview.load(parser, target(project(0))).build(sample))
        assertTrue(silent.maxOf { abs(it.toInt()) } == 0, "The sound's silent gain must not be replaced with an audible fallback")
    }

    @Test fun `stop and draft changes discard pending bank tryout audio`() = runBlocking<Unit> {
        val parser = TestRomHelper.requireRomParser()
        val bank = SampleBankPreview.load(parser, target())
        val clips = mutableListOf<DeferredSoundClip>()
        val state = SampleWorkbenchState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        state.prepare()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { state.previewInBank(bank) }
        assertTrue(state.previewing)
        state.update(state.draft.copy(gainPercent = 25)); pending.join()
        assertTrue(clips.isEmpty()); assertFalse(state.playing); assertFalse(state.previewing)
        state.prepare(); state.previewInBank(bank)
        assertTrue(state.playing); assertEquals("Playing as Test sound", state.message)
        state.stop()
        clips.single().complete()
        assertFalse(state.playing)
        assertEquals("Preview stopped", state.message)
    }
}
