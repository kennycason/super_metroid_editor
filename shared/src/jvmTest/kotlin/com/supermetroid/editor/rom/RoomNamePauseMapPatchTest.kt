package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomNamePauseMapPatchTest {
    @Test
    fun `encodes room names with pause area label font tiles`() {
        val words = RoomNamePauseMapPatch.encodeRoomNameTilemap("A-B")
            .chunked(2)
            .map { it[0] or (it[1] shl 8) }

        assertEquals(0x3830, words[0])
        assertEquals(0x2801, words[1])
        assertEquals(0x3831, words[2])
        assertEquals(3, words.size)
    }

    @Test
    fun `aligns trimmed room names within the pause text row`() {
        fun startTile(alignment: RoomNamePauseMapPatch.RoomNameAlignment): Int =
            RoomNamePauseMapPatch.layoutRoomName("HELL", alignment).startTile

        assertEquals(0, startTile(RoomNamePauseMapPatch.RoomNameAlignment.LEFT))
        assertEquals(10, startTile(RoomNamePauseMapPatch.RoomNameAlignment.CENTER))
        assertEquals(20, startTile(RoomNamePauseMapPatch.RoomNameAlignment.RIGHT))
        assertEquals(8, RoomNamePauseMapPatch.layoutRoomName("HELL").tilemapBytes.size)
    }

    @Test
    fun `installs payload into available bank after existing writes`() {
        val rom = ByteArray(0x300000) { 0x00 }
        markTailFree(rom, 0x86, 0x1000)
        writeOriginalPauseMapCall(rom, 0x828D25)
        writeOriginalPauseMapCall(rom, 0x8291DD)

        val result = RoomNamePauseMapPatch.install(
            romData = rom,
            snesToPc = ::snesToPc,
            pcToSnes = ::pcToSnes,
            rooms = listOf(
                RoomInfo(id = "0x91F8", handle = "landing_site", name = "Landing Site"),
                RoomInfo(id = "0x92FD", handle = "parlor", name = "Parlor and Alcatraz"),
            ),
            overrides = mapOf("92FD" to "Custom Name", "C123" to "Custom Room"),
            alignment = RoomNamePauseMapPatch.RoomNameAlignment.CENTER,
        )

        assertEquals(0x86, result.allocation.bank)
        assertEquals(3, result.roomCount)
        assertTrue(result.payloadSize > 0)

        val hook1 = snesToPc(0x828D25)
        val hook2 = snesToPc(0x8291DD)
        assertEquals(0x22, rom[hook1].toInt() and 0xFF)
        assertEquals(0x86, rom[hook1 + 3].toInt() and 0xFF)
        assertEquals(rom[hook1 + 1], rom[hook2 + 1])
        assertEquals(rom[hook1 + 2], rom[hook2 + 2])
        assertEquals(rom[hook1 + 3], rom[hook2 + 3])

        val payloadPc = result.allocation.pcOffset
        assertEquals(0x22, rom[payloadPc].toInt() and 0xFF)
        assertEquals(0xC3, rom[payloadPc + 1].toInt() and 0xFF)
        assertEquals(0x93, rom[payloadPc + 2].toInt() and 0xFF)
        assertEquals(0x82, rom[payloadPc + 3].toInt() and 0xFF)

        val payloadBytes = rom.slice(result.allocation.pcOffset until result.allocation.pcOffset + result.payloadSize)
        assertTrue(containsLittleEndianWord(payloadBytes, 0x91F8))
        assertTrue(containsLittleEndianWord(payloadBytes, 0x92FD))
        assertTrue(containsLittleEndianWord(payloadBytes, 0xC123))
    }

    private fun writeOriginalPauseMapCall(rom: ByteArray, snesAddress: Int) {
        val pc = snesToPc(snesAddress)
        rom[pc] = 0x22
        rom[pc + 1] = 0xC3.toByte()
        rom[pc + 2] = 0x93.toByte()
        rom[pc + 3] = 0x82.toByte()
    }

    private fun markTailFree(rom: ByteArray, bank: Int, bytes: Int) {
        val bankEnd = snesToPc((bank shl 16) or 0xFFFF) + 1
        rom.fill(0xFF.toByte(), bankEnd - bytes, bankEnd)
    }

    private fun containsLittleEndianWord(bytes: List<Byte>, word: Int): Boolean {
        for (i in 0 until bytes.lastIndex) {
            if ((bytes[i].toInt() and 0xFF) == (word and 0xFF) &&
                (bytes[i + 1].toInt() and 0xFF) == ((word shr 8) and 0xFF)
            ) {
                return true
            }
        }
        return false
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
