package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class RomFreeSpaceAllocatorTest {
    @Test fun `new live ranges protect erased bytes across all existing and future allocator views`() {
        val rom = ByteArray(0x10000) { -1 }
        val allocator = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes, guardBytes = 2)
        val earlierView = allocator.observing {}
        earlierView.protectRanges(listOf(0x6000 until 0x8004))
        assertNull(allocator.reserve(1, listOf(0x80), "live end of bank"))
        val next = earlierView.observing {}.reserve(4, listOf(0x81), "after live prefix")!!
        assertEquals(0x8006, next.pcOffset)
        assertEquals(next.pcOffset + next.size, allocator.reserve(4, listOf(0x81), "shared cursor")!!.pcOffset)
        assertTrue(rom.all { it == (-1).toByte() }, "Protection and reservation do not mutate ROM")
    }

    @Test fun `late protection advances cached cursors without losing earlier reservations`() {
        val rom = ByteArray(0x10000) { -1 }
        val allocator = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes, guardBytes = 2)
        val first = allocator.reserve(4, listOf(0x80), "earlier exporter")!!
        assertEquals(2, first.pcOffset)
        allocator.observing {}.protectRanges(listOf(0x100 until 0x200))
        assertEquals(0x202, allocator.reserve(4, listOf(0x80), "after discovered source")!!.pcOffset)
        allocator.write(first, byteArrayOf(1, 2, 3, 4))
        assertEquals(listOf<Byte>(1, 2, 3, 4), rom.slice(first.pcOffset until first.pcOffset + first.size))
    }

    @Test fun `source protection rejects overlaps with previously reserved but unwritten bytes`() {
        val rom = ByteArray(0x10000) { -1 }
        val allocator = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes)
        val first = allocator.observing {}.reserve(16, listOf(0x80), "reserved source conflict")!!
        assertFailsWith<IllegalArgumentException> {
            allocator.protectRanges(listOf(first.pcOffset until first.pcOffset + first.size))
        }
        assertEquals(first.pcOffset + first.size, allocator.reserve(4, listOf(0x80), "next")!!.pcOffset)
        assertTrue(rom.all { it == (-1).toByte() })
    }

    @Test fun `invalid late protection is atomic and empty ranges are harmless`() {
        val rom = ByteArray(0x10000) { -1 }
        val allocator = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes)
        allocator.protectRanges(listOf(IntRange.EMPTY))
        assertFailsWith<IllegalArgumentException> { allocator.protectRanges(listOf(0..0x100, -1..1)) }
        assertFailsWith<IllegalArgumentException> { allocator.protectRanges(listOf(rom.size..rom.size)) }
        assertEquals(1, allocator.reserve(4, listOf(0x80), "invalid protection added nothing")!!.pcOffset)
    }

    @Test fun `source envelopes exclude unchanged fill bytes across shared allocator views`() {
        val rom = ByteArray(0x300000)
        rom.fill(-1, 0x7F000, 0x80000)
        val allocator = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes, guardBytes = 2,
            excludedRanges = listOf(0x7F080 until 0x7F180))
        val observed = mutableListOf<RomAllocation>()
        val first = allocator.observing(observed::add).allocate(byteArrayOf(1, 2), listOf(0x8F), "after live code")!!
        assertEquals(0x7F182, first.pcOffset)
        assertEquals(0x7F184, allocator.reserve(8, listOf(0x8F), "next")!!.pcOffset)
        assertEquals(listOf(first), observed)
        val exhausted = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes,
            excludedRanges = listOf(0x7FF00 until 0x80000))
        assertNull(exhausted.reserve(1, listOf(0x8F), "cannot reuse code padding"))
    }
    @Test
    fun `reserves and writes from trailing bank free space`() {
        val rom = ByteArray(0x300000) { 0x00 }
        val bank = 0x86
        val bankEnd = snesToPc((bank shl 16) or 0xFFFF) + 1
        val freeStart = bankEnd - 0x100
        rom.fill(0xFF.toByte(), freeStart, bankEnd)

        val allocator = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes)
        val first = allocator.reserve(16, listOf(bank), "first")!!
        assertEquals(freeStart + 1, first.pcOffset)
        assertEquals((bank shl 16) or ((first.pcOffset % 0x8000) + 0x8000), first.snesAddress)

        allocator.write(first, ByteArray(16) { 0x5A })
        assertTrue((first.pcOffset until first.pcOffset + 16).all { rom[it] == 0x5A.toByte() })

        val second = allocator.allocate(ByteArray(8) { 0xA5.toByte() }, listOf(bank), "second")!!
        assertEquals(first.pcOffset + 16, second.pcOffset)
    }

    @Test
    fun `returns null when no bank has enough contiguous free space`() {
        val rom = ByteArray(0x300000) { 0x00 }
        val bank = 0x86
        val bankEnd = snesToPc((bank shl 16) or 0xFFFF) + 1
        rom.fill(0xFF.toByte(), bankEnd - 4, bankEnd)

        val allocator = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes)
        assertNull(allocator.reserve(8, listOf(bank), "too large"))
    }

    @Test
    fun `observed allocator views share reservations before bytes are written`() {
        val rom = ByteArray(0x300000) { 0x00 }
        val bank = 0x8F
        val bankEnd = snesToPc((bank shl 16) or 0xFFFF) + 1
        val freeStart = bankEnd - 0x100
        rom.fill(0xFF.toByte(), freeStart, bankEnd)
        val firstObserved = mutableListOf<RomAllocation>()
        val secondObserved = mutableListOf<RomAllocation>()

        val session = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes)
        val first = session.observing(firstObserved::add).reserve(16, listOf(bank), "room data")!!
        // Deliberately do not write [first]. The shared cursor alone must keep a
        // second exporter from independently selecting the same trailing bytes.
        val second = session.observing(secondObserved::add).reserve(8, listOf(bank), "music data")!!

        assertEquals(first.pcOffset + first.size, second.pcOffset)
        assertEquals(listOf(first), firstObserved)
        assertEquals(listOf(second), secondObserved)
    }

    @Test
    fun `supports a format-defined zero fill byte for expanded banks`() {
        val rom = ByteArray(0x400000) { 0x7F }
        val bank = 0xE0
        val bankStart = snesToPc((bank shl 16) or 0x8000)
        val bankEnd = snesToPc((bank shl 16) or 0xFFFF) + 1
        rom.fill(0x00, bankStart, bankEnd)

        val allocator = RomFreeSpaceAllocator(
            rom,
            ::snesToPc,
            ::pcToSnes,
            freeFillByteForBank = { candidate -> if (candidate >= 0xE0) 0x00 else 0xFF },
        )
        val allocation = allocator.allocate(ByteArray(32) { 0x5A }, listOf(bank), "expanded data")!!

        assertEquals(bankStart + 1, allocation.pcOffset)
        assertTrue(
            (allocation.pcOffset until allocation.pcOffset + allocation.size)
                .all { rom[it] == 0x5A.toByte() }
        )
    }

    private fun snesToPc(snesAddress: Int): Int {
        val bank = (snesAddress shr 16) and 0xFF
        val address = snesAddress and 0xFFFF
        return ((bank and 0x7F) * 0x8000) + (address and 0x7FFF)
    }

    private fun pcToSnes(pcOffset: Int): Int {
        val bank = (pcOffset / 0x8000) or 0x80
        val address = (pcOffset % 0x8000) + 0x8000
        return (bank shl 16) or address
    }
}
