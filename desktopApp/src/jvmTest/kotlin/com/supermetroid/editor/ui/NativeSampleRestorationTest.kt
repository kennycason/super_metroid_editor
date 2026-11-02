package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.*

/** Deliberately test-only serialization. The application exporter still rejects DIR/BRR leaks. */
class NativeSampleRestorationTest {
    @TempDir lateinit var directory: File
    private data class Authored(val project: SmEditProject, val banks: Map<Int, MusicBankResolver.Resolved>, val samples: Map<Int, MusicSamplePlacement.Range>)
    private data class Prototype(val rom: ByteArray, val authored: Authored, val plan: MusicSampleRestoration.Plan)

    private fun author(parser: RomParser): Authored {
        val project = SmEditProject(romPath = "test.sfc")
        val banks = sortedMapOf<Int, MusicBankResolver.Resolved>()
        val samples = sortedMapOf<Int, MusicSamplePlacement.Range>()
        for ((set, shape) in listOf(3 to SampleAuthoring.Shape.SINE, 0x0F to SampleAuthoring.Shape.PULSE)) {
            val note = NspcSequence.Note(0, 120, NspcSequence.nameToNote("C4"), instrument = 0)
            val song = NspcSequence.Song(tempo = 36).also { it.channels[0].notes += note }
            val track = SpcData.KNOWN_TRACKS.first { it.songSet == set && it.playIndex == 5 }
            project.musicEdits[MusicEditConversion.key(set, 5)] = MusicEditConversion.toProjectEdit(track, song)
            val bank = MusicBankResolver.resolve(parser, project, set)
            val sample = SampleAuthoring.encode(SampleAuthoring.generate(shape).copy(loopStart = 32))
            val context = SampleBankPreview.load(parser, SampleBankPreview.Target(set, note, "Test bank", "Test sound", project))
            val allocation = context.layout.place(sample)
            samples[set] = allocation
            val loop = allocation.start + sample.loopByteOffset!!
            val native = NspcRenderer.readInstrumentTable(SampleAuthoring.preview(bank.ram, sample).ram)[24]
            // Explicitly BORROW existing slot 0 in this isolated fixture. This is not
            // evidence of a spare slot or permission to replace a common music/FX source.
            val writes = MusicTransferChainBudget.mergeBlocks(bank.blocks, mapOf(
                allocation.start to sample.brr,
                0x6D00 to byteArrayOf(allocation.start.toByte(), (allocation.start ushr 8).toByte(), loop.toByte(), (loop ushr 8).toByte()),
                0x6C00 to PianoRollPreviewLogic.instrumentBytes(native.copy(index = 0, tableAddr = 0x6C00)),
                // Leave a second, old BRR footprint changed too. Repointing DIR alone
                // must not hide the need to repair that retained common recording.
                0x6E01 to byteArrayOf((bank.ram[0x6E01].toInt() xor set).toByte()),
            )).associate { it.destAddr to it.data }
            val ram = MusicBankResolver.sourceRam(parser, SmEditProject(romPath = "test.sfc"), set).also { result ->
                writes.forEach { (at, bytes) -> bytes.copyInto(result, at) }
            }
            banks[set] = MusicBankResolver.Resolved(ram, writes, 1, true)
        }
        return Authored(project, banks, samples)
    }

    private fun prototype(parser: RomParser): Prototype {
        val authored = author(parser)
        val plan = MusicSampleRestoration.plan(parser, authored.project, authored.banks)
        val rom = parser.copyRomData()
        val pointers = SpcData.TRANSFER_SONG_SETS.associateWith { SpcData.readSongSetPointer(parser, it) }
        val sources = pointers.mapValues { (_, pointer) -> SpcTransferStream.read(parser, pointer) }
        val allocator = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes, guardBytes = 2)
        allocator.protectRanges(sources.values.flatMap { it.romRanges } +
            pointers.keys.map { SpcData.findSongSetPointerEntryPc(parser, it).let { pc -> pc until pc + 3 } } +
            listOf(parser.snesToPc(0x808024) until parser.snesToPc(0x808111)))
        val code = assertNotNull(allocator.reserve(MusicTransferLoader.code.size, listOf(0xB8), "sample proof code", 16))
        val retainedWrites = mutableListOf<Pair<Int, ByteArray>>()
        val prefixes = pointers.filterKeys { it > 0 }.mapNotNull { (set, pointer) ->
            val source = sources.getValue(set)
            val writes = MusicTransferChainBudget.mergeBlocks(authored.banks[set]?.blocks.orEmpty(), plan.writes[set].orEmpty())
            if (writes.isEmpty()) return@mapNotNull null
            val loaded = BooleanArray(65536)
            source.blocks.forEach { loaded.fill(true, it.destAddr, it.destAddr + it.data.size) }
            val covered = sortedMapOf<Int, Int>()
            val missing = sortedMapOf<Int, Int>()
            for (block in writes) block.data.forEachIndexed { i, byte ->
                (if (loaded[block.destAddr + i]) covered else missing)[block.destAddr + i] = byte.toInt() and 255
            }
            retainedWrites += SpcData.buildRomWritesForSpcRamWrites(parser, set, SpcData.packSpcBytesToWrites(covered))
            // Keep the tested instrument-only production serializer untouched. This
            // unrestricted encoder exists ONLY inside the native sample proof fixture.
            val out = ByteArrayOutputStream()
            fun word(value: Int) { out.write(value and 255); out.write((value ushr 8) and 255) }
            for ((at, bytes) in SpcData.packSpcBytesToWrites(missing)) { word(bytes.size); word(at); out.write(bytes) }
            word(0xFFFF); repeat(3) { out.write((pointer ushr (it * 8)) and 255) }
            val bytes = out.toByteArray()
            val allocation = assertNotNull(allocator.reserve(bytes.size, listOf(0xB8), "sample proof bank $set", 2))
            Triple(set, allocation, bytes)
        }
        // All space is reserved before writing, and every source chain remains retained.
        for ((at, bytes) in retainedWrites) bytes.copyInto(rom, at)
        for ((set, allocation, bytes) in prefixes) {
            allocator.write(allocation, bytes)
            val entry = SpcData.findSongSetPointerEntryPc(parser, set)
            repeat(3) { rom[entry + it] = (allocation.snesAddress ushr (it * 8)).toByte() }
        }
        for ((at, bytes) in MusicTransferLoader.patchWrites(parser, code.snesAddress)) bytes.copyInto(rom, at)
        assertTrue(prefixes.sumOf { it.third.size } + code.size < 4096, "The proof must fit without copying music chains or expanding the ROM")
        assertEquals(parser.getRomData().size, rom.size)
        // Re-read actual serialized bytes, not just the abstract prefix maps.
        val output = RomParser(rom)
        val concrete = pointers.keys.filter { it > 0 }.associateWith { set ->
            val blocks = SpcData.findSongSetTransferData(output, set)
            val actual = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, blocks) }
            val expected = authored.banks[set]?.ram ?: MusicBankResolver.sourceRam(parser, authored.project, set)
            assertContentEquals(expected, actual, "Serialized sample proof bank $set")
            MusicBankResolver.Resolved(actual, MusicTransferChainBudget.mergeBlocks(blocks, emptyMap()).associate { it.destAddr to it.data }, 0, false)
        }
        val checked = MusicBankTransitions.inspect(parser, authored.project, concrete)
        assertTrue(checked.canExport, "Serialized proof must restore both incoming/outgoing dependencies: ${checked.issues.take(4)}")
        assertTrue(checked.instrumentRestorations.isEmpty())
        return Prototype(rom, authored, plan)
    }

    @Test fun `native queue restores directory BRR instruments and created recordings across all banks and reentry`() {
        val parser = TestRomHelper.requireRomParser()
        val prototype = prototype(parser)
        val initial = SpcData.buildInitialSpcRam(parser)
        NativeMusicUploadFixture(directory, prototype.rom).use { fixture ->
            for (set in listOf(3, 0x0F, 6, 3, 0x42, 0x0F, 0x48, 3) + SpcData.TRANSFER_SONG_SETS.filter { it > 0 }) {
                fixture.queueBank(set)
                val expected = prototype.authored.banks[set]?.ram ?: MusicBankResolver.sourceRam(parser, prototype.authored.project, set)
                val actual = fixture.ram()
                assertContentEquals(expected.copyOfRange(0x6C00, 0x6C06), actual.copyOfRange(0x6C00, 0x6C06), "Instrument, bank $set")
                assertContentEquals(expected.copyOfRange(0x6D00, 0x6D04), actual.copyOfRange(0x6D00, 0x6D04), "Directory, bank $set")
                assertContentEquals(expected.copyOfRange(0x6E00, 0x6E09), actual.copyOfRange(0x6E00, 0x6E09), "Retained BRR, bank $set")
                prototype.authored.samples[set]?.let { range ->
                    assertContentEquals(expected.copyOfRange(range.start, range.endExclusive), actual.copyOfRange(range.start, range.endExclusive), "Created recording, bank $set")
                }
                assertContentEquals(initial.copyOfRange(0x1500, 0x530E), actual.copyOfRange(0x1500, 0x530E), "Engine/effects must not be rewritten")
            }
            for (set in listOf(3, 0x0F, 3)) {
                fixture.queueBank(set); fixture.play(5)
                assertTrue(fixture.frames(45).any { it != 0.toShort() }, "The created recording must play through the native arrangement in bank $set")
            }
            fixture.queueBank(0x0F)
            assertTrue(fixture.softResetToTitle().any { it != 0.toShort() }, "Soft reset/title loading must still play")
            val title = prototype.authored.banks.getValue(3).ram
            assertContentEquals(title.copyOfRange(0x6D00, 0x6D04), fixture.ram().copyOfRange(0x6D00, 0x6D04))
        }
    }

    @Test fun `each effect library is independently audible after native sample restoration with a silent control`() {
        val parser = TestRomHelper.requireRomParser()
        val prototype = prototype(parser)
        val expected = MusicBankResolver.sourceRam(parser, prototype.authored.project, 6)
        for (library in 1..3) NativeMusicUploadFixture(File(directory, "effect-$library"), prototype.rom).use { fixture ->
            fixture.queueBank(3); fixture.queueBank(0x0F); fixture.queueBank(6)
            assertContentEquals(expected.copyOfRange(0x6D00, 0x6D04), fixture.ram().copyOfRange(0x6D00, 0x6D04))
            assertContentEquals(expected.copyOfRange(0x6E00, 0x6E09), fixture.ram().copyOfRange(0x6E00, 0x6E09))
            fixture.effect(library, 0); fixture.frames(8)
            assertTrue(fixture.frames(30).all { it == 0.toShort() }, "No BGM or residual effect may mask the control")
            fixture.effect(library, if (library == 3) 5 else 1)
            assertTrue(fixture.frames(60).any { it != 0.toShort() }, "Native effect library $library must remain audible")
        }
    }

    @Test fun `unmodified uploader leaves changed directory and BRR bytes behind on a warm bank load`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.copyRomData()
        val initial = SpcData.buildInitialSpcRam(parser)
        val changed = initial.copyOfRange(0x6E00, 0x6E09).also { it[1] = (it[1].toInt() xor 1).toByte() }
        val dir = initial.copyOfRange(0x6D00, 0x6D04).also { it[1] = (it[1].toInt() xor 1).toByte() }
        val raw = byteArrayOf(9, 0, 0, 0x6E) + changed + byteArrayOf(4, 0, 0, 0x6D) + dir + byteArrayOf(0, 0, 0, 0x15)
        val allocation = assertNotNull(RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes)
            .allocate(raw, listOf(0xB8), "native sample leak control", 2))
        NativeMusicUploadFixture(directory, rom).use { fixture ->
            fixture.upload(allocation.snesAddress)
            fixture.queueBank(0x0F)
            assertContentEquals(changed, fixture.ram().copyOfRange(0x6E00, 0x6E09))
            assertContentEquals(dir, fixture.ram().copyOfRange(0x6D00, 0x6D04))
            assertFalse(changed.contentEquals(initial.copyOfRange(0x6E00, 0x6E09)))
            assertFalse(dir.contentEquals(initial.copyOfRange(0x6D00, 0x6D04)))
        }
    }
}
