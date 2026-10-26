package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class NativeMusicUploadTest {
    @TempDir lateinit var directory: File

    @Test fun `real CPU and SPC upload all native banks through retained audio RAM`() {
        val parser = TestRomHelper.requireRomParser()
        NativeMusicUploadFixture(directory, parser.copyRomData()).use { fixture ->
            val expected = SpcData.buildInitialSpcRam(parser)
            assertContentEquals(expected.copyOfRange(0x6C00, 0x10000), fixture.ram().copyOfRange(0x6C00, 0x10000), "Native boot upload")
            for ((index, songSet) in SpcData.TRANSFER_SONG_SETS.filter { it > 0 }.withIndex()) {
                fixture.upload(SpcData.readSongSetPointer(parser, songSet), narrowCaller = index % 2 == 0)
                SpcData.applyTransferBlocks(expected, SpcData.findSongSetTransferData(parser, songSet))
                val actual = fixture.ram()
                assertContentEquals(expected.copyOfRange(0x6C00, 0x10000), actual.copyOfRange(0x6C00, 0x10000),
                    "Actual CPU/APU instrument, directory and BRR bytes for bank ${songSet.toString(16)}")
            }
            fixture.upload(SpcData.readSongSetPointer(parser, 3))
            fixture.play(5)
            assertTrue(fixture.frames(120).any { it != 0.toShort() }, "Native SPC music must resume after warm bank uploads")
        }
    }

    @Test fun `vanilla warm bank upload demonstrably leaves a changed common instrument behind`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.copyRomData()
        val entry = SpcData.buildInitialSpcRam(parser).copyOfRange(0x6C00, 0x6C06).also { it[3] = (it[3].toInt() xor 1).toByte() }
        val raw = byteArrayOf(6, 0, 0, 0x6C) + entry + byteArrayOf(0, 0, 0, 0x15)
        val allocation = assertNotNull(RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes)
            .allocate(raw, listOf(0xB8), "native leak negative control"))
        NativeMusicUploadFixture(directory, rom).use { fixture ->
            fixture.upload(allocation.snesAddress)
            fixture.upload(SpcData.readSongSetPointer(parser, 0x0F))
            assertContentEquals(entry, fixture.ram().copyOfRange(0x6C00, 0x6C06))
            assertFalse(entry.contentEquals(SpcData.buildInitialSpcRam(parser).copyOfRange(0x6C00, 0x6C06)))
        }
    }

    @Test fun `real CPU continuation restores shared instruments across every native bank without copying chains`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.copyRomData()
        val before = rom.copyOf()
        val pointers = SpcData.TRANSFER_SONG_SETS.associateWith { SpcData.readSongSetPointer(parser, it) }
        val sources = pointers.values.associateWith { MusicRomTransferChain.read(parser, it) }
        val allocator = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes)
        allocator.protectRanges(sources.values.map { it.romRange })
        val hook = assertNotNull(allocator.reserve(MusicTransferLoader.code.size, listOf(0xB8), "native continuation code", 16))
        val initial = SpcData.buildInitialSpcRam(parser)
        val common = initial.copyOfRange(0x6C00, 0x6C06)
        val changed = common.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() }
        val lastCommon = initial.copyOfRange(0x6C8A, 0x6C90)
        val targetEntries = pointers.filterKeys { it > 0 }.mapValues { (_, pointer) ->
            initial.copyOf().also { SpcData.applyTransferBlocks(it, sources.getValue(pointer).blocks) }
                .copyOfRange(0x6CA8, 0x6CAE)
        }
        val prefixes = pointers.filterKeys { it > 0 }.mapValues { (songSet, pointer) ->
            val bytes = MusicTransferLoader.instrumentPrefix(mapOf(
                0x6C00 to if (songSet == 3) changed else common,
                0x6C8A to lastCommon, 0x6CA8 to targetEntries.getValue(songSet)), pointer)
            val allocation = assertNotNull(allocator.reserve(bytes.size, listOf(0xB8), "native bank prefix", 2))
            allocation to bytes
        }
        // Reserve all code/prefixes before writing this test ROM. The real exporter
        // intentionally still refuses common edits until read/export integration.
        for ((allocation, bytes) in prefixes.values) allocator.write(allocation, bytes)
        for ((pc, bytes) in MusicTransferLoader.patchWrites(parser, hook.snesAddress)) bytes.copyInto(rom, pc)
        assertTrue(hook.size + prefixes.values.sumOf { it.second.size } < 1024,
            "Restoration should not duplicate ~434 KiB of retained music chains")
        assertTrue(prefixes.values.maxOf { it.first.pcOffset + it.first.size } - hook.pcOffset < 1024,
            "Even alignment gaps should fit the native demonstration in 1 KiB")
        for (source in sources.values) assertContentEquals(before.sliceArray(source.romRange), rom.sliceArray(source.romRange))
        NativeMusicUploadFixture(directory, rom).use { fixture ->
            val expected = initial.copyOf()
            val sequence = listOf(3, 0x0F, 3, 6, 3, 0x42) + prefixes.keys + listOf(3, 0x0F, 3)
            for ((index, songSet) in sequence.withIndex()) {
                fixture.upload(prefixes.getValue(songSet).first.snesAddress, narrowCaller = index % 2 == 0)
                (if (songSet == 3) changed else common).copyInto(expected, 0x6C00)
                lastCommon.copyInto(expected, 0x6C8A)
                targetEntries.getValue(songSet).copyInto(expected, 0x6CA8)
                SpcData.applyTransferBlocks(expected, sources.getValue(pointers.getValue(songSet)).blocks)
                assertContentEquals(expected.copyOfRange(0x6C00, 65536), fixture.ram().copyOfRange(0x6C00, 65536),
                    "Actual single-session continuation into bank ${songSet.toString(16)}")
            }
            fixture.play(5)
            assertTrue(fixture.frames(120).any { it != 0.toShort() }, "Music must play after continued warm uploads")
        }
        assertContentEquals(before.copyOfRange(parser.snesToPc(0x8FE7E1), parser.snesToPc(0x8FE7E1) + 0x4B),
            rom.copyOfRange(parser.snesToPc(0x8FE7E1), parser.snesToPc(0x8FE7E1) + 0x4B))
    }

    @Test fun `continuation near bank end and native header overflow preserve data bank kick and stack`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.copyRomData()
        val initial = SpcData.buildInitialSpcRam(parser)
        val first = initial.copyOfRange(0x6C00, 0x6C06).also { it[3] = (it[3].toInt() xor 1).toByte() }
        val second = initial.copyOfRange(0x6C06, 0x6C0C).also { it[3] = (it[3].toInt() xor 2).toByte() }
        // The size word is at B9:FFFE; the destination word is at BA:8000.
        val retained = byteArrayOf(6, 0, 0, 0x6C) + first + byteArrayOf(0, 0, 0, 0x15)
        retained.copyInto(rom, parser.snesToPc(0xB9FFFE))
        val prefix = MusicTransferLoader.instrumentPrefix(mapOf(0x6C06 to second), 0xB9FFFE)
        assertEquals(15, prefix.size)
        prefix.copyInto(rom, parser.snesToPc(0xB8FFF0))
        for ((at, bytes) in MusicTransferLoader.patchWrites(parser, 0xB88000)) bytes.copyInto(rom, at)
        NativeMusicUploadFixture(directory, rom).use { fixture ->
            repeat(4) { index ->
                fixture.upload(0xB8FFF0, narrowCaller = index % 2 == 0)
                assertContentEquals(first + second, fixture.ram().copyOfRange(0x6C00, 0x6C0C))
            }
        }
    }

    @Test fun `native effects from all three libraries resume after common instrument restoration`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.copyRomData()
        val common = SpcData.buildInitialSpcRam(parser).copyOfRange(0x6C00, 0x6C06)
        val changed = common.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        val allocator = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes)
        val code = assertNotNull(allocator.reserve(MusicTransferLoader.code.size, listOf(0xB8), "effect-test hook", 16))
        val prefixes = listOf(3, 0x0F).associateWith { songSet ->
            val bytes = MusicTransferLoader.instrumentPrefix(mapOf(0x6C00 to if (songSet == 3) changed else common),
                SpcData.readSongSetPointer(parser, songSet))
            val allocation = assertNotNull(allocator.allocate(bytes, listOf(0xB8), "effect-test prefix", 16))
            allocation.snesAddress
        }
        for ((at, bytes) in MusicTransferLoader.patchWrites(parser, code.snesAddress)) bytes.copyInto(rom, at)
        NativeMusicUploadFixture(directory, rom).use { fixture ->
            fixture.upload(prefixes.getValue(3))
            assertContentEquals(changed, fixture.ram().copyOfRange(0x6C00, 0x6C06))
            fixture.upload(prefixes.getValue(0x0F), narrowCaller = true)
            assertContentEquals(common, fixture.ram().copyOfRange(0x6C00, 0x6C06))
            for (library in 1..3) {
                fixture.effect(library, 0)
                fixture.frames(8)
                fixture.effect(library, 1)
                assertTrue(fixture.frames(45).any { it != 0.toShort() }, "Native effect $library/1 must remain audible")
                assertContentEquals(common, fixture.ram().copyOfRange(0x6C00, 0x6C06))
            }
        }
    }
}
