package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Tests for the custom ASM embedding system.
 * Verifies that we can copy existing AI routines to free space
 * and update species header pointers correctly.
 */
class CustomAsmTest {

    @Test
    fun `read Shot AI bytes for Zoomer`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val rom = rp.getRomData()

        // Zoomer Shot AI at $A0:802D
        val shotAiPtr = readU16(rom, rp.snesToPc(0xA0DCFF) + 0x32)
        assertEquals(0x802D, shotAiPtr, "Zoomer Shot AI should be at \$802D")

        // Read the routine bytes (ends at RTS = 0x60)
        val routinePc = rp.snesToPc(0xA0802D)
        var len = 0
        for (i in 0 until 256) {
            if (rom[routinePc + i] == 0x60.toByte()) { len = i + 1; break }
        }
        assertEquals(31, len, "Standard Shot AI routine should be 31 bytes")

        // First byte should be JSL opcode (0x22)
        assertEquals(0x22, rom[routinePc].toInt() and 0xFF, "Should start with JSL")
        // Last byte should be RTS (0x60)
        assertEquals(0x60, rom[routinePc + len - 1].toInt() and 0xFF, "Should end with RTS")
    }

    @Test
    fun `verify free space exists in bank A0`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val rom = rp.getRomData()

        // Bank $A0 spans PC $100000-$107FFF
        val bankStart = rp.snesToPc(0xA08000)
        val bankEnd = rp.snesToPc(0xA0FFFF) + 1
        var freeStart = bankEnd
        while (freeStart > bankStart && rom[freeStart - 1] == 0xFF.toByte()) freeStart--
        val freeBytes = bankEnd - freeStart

        assertTrue(freeBytes > 100, "Bank \$A0 should have at least 100 bytes free, found $freeBytes")
    }

    @Test
    fun `copy Shot AI routine to free space and verify bytes match`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val rom = rp.getRomData()
        val romCopy = rom.copyOf()

        // Read original Shot AI bytes
        val routinePc = rp.snesToPc(0xA0802D)
        var len = 0
        for (i in 0 until 256) {
            if (rom[routinePc + i] == 0x60.toByte()) { len = i + 1; break }
        }
        val originalBytes = rom.copyOfRange(routinePc, routinePc + len)

        // Find free space at end of bank $A0
        val bankStart = rp.snesToPc(0xA08000)
        val bankEnd = rp.snesToPc(0xA0FFFF) + 1
        var freePtr = bankEnd
        while (freePtr > bankStart && romCopy[freePtr - 1] == 0xFF.toByte()) freePtr--
        freePtr++ // 1 byte gap

        // Write the routine
        System.arraycopy(originalBytes, 0, romCopy, freePtr, originalBytes.size)

        // Calculate new SNES address
        val newSnesPtr = 0x8000 + (freePtr - bankStart)

        // Update Zoomer's Shot AI pointer (header +$32)
        val headerPc = rp.snesToPc(0xA0DCFF)
        romCopy[headerPc + 0x32] = (newSnesPtr and 0xFF).toByte()
        romCopy[headerPc + 0x33] = ((newSnesPtr shr 8) and 0xFF).toByte()

        // Verify: new pointer points to correct data
        val readbackPtr = (romCopy[headerPc + 0x32].toInt() and 0xFF) or
            ((romCopy[headerPc + 0x33].toInt() and 0xFF) shl 8)
        assertEquals(newSnesPtr, readbackPtr)

        // Verify: bytes at new location match original
        for (i in originalBytes.indices) {
            assertEquals(originalBytes[i], romCopy[freePtr + i],
                "Byte $i mismatch: expected ${originalBytes[i]}, got ${romCopy[freePtr + i]}")
        }

        // Verify: original routine is untouched
        for (i in originalBytes.indices) {
            assertEquals(originalBytes[i], romCopy[routinePc + i],
                "Original routine should not be modified")
        }
    }

    @Test
    fun `hex string to bytes conversion`() {
        val hexStr = "22 77 A4 A0 6B 60"
        val bytes = hexStr.trim().split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull(16)?.toByte() }
            .toByteArray()

        assertEquals(6, bytes.size)
        assertEquals(0x22, bytes[0].toInt() and 0xFF) // JSL
        assertEquals(0x77, bytes[1].toInt() and 0xFF)
        assertEquals(0xA4, bytes[2].toInt() and 0xFF)
        assertEquals(0xA0, bytes[3].toInt() and 0xFF)
        assertEquals(0x6B, bytes[4].toInt() and 0xFF) // RTL
        assertEquals(0x60, bytes[5].toInt() and 0xFF) // RTS
    }

    @Test
    fun `existing Touch AI routine can be parsed as hex string`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val rom = rp.getRomData()

        // Read Touch AI at $A0:8023 (41 bytes ending in RTS)
        val pc = rp.snesToPc(0xA08023)
        var len = 0
        for (i in 0 until 256) {
            if (rom[pc + i] == 0x60.toByte()) { len = i + 1; break }
        }
        assertTrue(len > 0, "Should find RTS")

        // Convert to hex string
        val hexStr = (0 until len).joinToString(" ") {
            "%02X".format(rom[pc + it].toInt() and 0xFF)
        }
        assertTrue(hexStr.startsWith("22"), "Should start with JSL opcode")
        assertTrue(hexStr.endsWith("60"), "Should end with RTS")

        // Parse back to bytes
        val roundtrip = hexStr.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(len, roundtrip.size)
        for (i in 0 until len) {
            assertEquals(rom[pc + i], roundtrip[i])
        }
    }
}
