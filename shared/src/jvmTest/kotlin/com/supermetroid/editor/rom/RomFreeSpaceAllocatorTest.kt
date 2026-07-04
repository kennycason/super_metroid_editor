package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RomFreeSpaceAllocatorTest {
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
