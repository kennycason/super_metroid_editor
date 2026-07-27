package com.supermetroid.editor.rom

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RomCompatibilityTest {
    @Test
    fun `vanilla sized rom is supported for editing`() {
        val rom = syntheticRom(0x300000)

        val report = RomCompatibility.analyze(rom)

        assertTrue(report.supportedForEditing)
        assertEquals(0x300000, report.headerlessSize)
        assertNotNull(report.snesHeader)
        assertTrue(report.snesHeader!!.checksumValid)
    }

    @Test
    fun `expanded rom is rejected with clear compatibility report`() {
        val rom = syntheticRom(0x400000)
        writeCandidateRoomHeaderWithExpandedLevelData(rom)

        val report = RomCompatibility.analyze(rom)
        val message = report.userMessage("expanded.smc")

        assertFalse(report.supportedForEditing)
        assertEquals(0x100000, report.expandedBytes)
        assertTrue(report.candidateRoomHeadersInBank8F > 0)
        assertTrue(report.candidateExpandedLevelPointers > 0)
        assertContains(message, "Unsupported ROM layout")
        assertContains(message, "editing disabled")
        assertContains(message, "Expanded data")
        assertContains(message, "vanilla room map")
    }

    @Test
    fun `loadRom accepts expanded rom with readable room catalog as read only`() {
        val rom = syntheticRom(0x400000)
        writeCandidateRoomHeaderWithExpandedLevelData(rom)
        val file = File.createTempFile("smedit-expanded-readable-rom-", ".smc")
        try {
            file.writeBytes(rom)

            val parser = RomParser.loadRom(file.absolutePath)
            val catalog = parser.roomCatalog

            assertFalse(catalog.editable)
            assertTrue(catalog.readOnly)
            assertEquals(1, catalog.rooms.size)
            assertEquals(0x8094, catalog.rooms.single().getRoomIdAsInt())
            assertContains(catalog.loadNotice(file.name).orEmpty(), "Read-only expanded ROM layout loaded")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `tile graphics loads from relocated bank 8F tileset table`() {
        val rom = syntheticRom(0x400000)
        writeCandidateRoomHeaderWithExpandedLevelData(rom)
        writeRelocatedTilesetTable(rom)

        val parser = RomParser(rom)
        val catalog = parser.graphicsCatalog
        val tileGraphics = TileGraphics(parser)

        assertEquals(RomGraphicsCatalogSource.DISCOVERED_BANK_8F, catalog.source)
        assertEquals(0x8FA000, catalog.tableSnesAddress)
        assertTrue(tileGraphics.loadTileset(0))
        assertEquals(TileGraphics.CRE_METATILE_COUNT, tileGraphics.variableMetatileStart())
        val pixels = assertNotNull(tileGraphics.renderMetatile(TileGraphics.CRE_METATILE_COUNT))
        assertTrue(pixels.any { (it ushr 24) != 0 })
    }

    @Test
    fun `expanded rom fixture discovers graphics table and renders room tiles`() {
        val path = System.getProperty("smedit.expandedRomFixture").orEmpty()
        if (path.isBlank()) return
        val parser = RomParser.loadRom(path)
        val roomInfo = parser.roomCatalog.rooms.firstOrNull { it.getRoomIdAsInt() == 0x8120 }
            ?: parser.roomCatalog.rooms.first()
        val room = requireNotNull(parser.readRoomHeader(roomInfo.getRoomIdAsInt()))
        val tileGraphics = TileGraphics(parser)

        assertTrue(parser.roomCatalog.readOnly)
        assertNotNull(parser.graphicsCatalog.entry(room.tileset))
        assertTrue(tileGraphics.loadTileset(room.tileset))

        val render = requireNotNull(MapRenderer(parser).renderRoom(room))
        assertTrue(render.pixels.toSet().size > 8)
    }

    @Test
    fun `loadRom reports compatibility details for unsupported sizes`() {
        val rom = syntheticRom(0x400000)
        val file = File.createTempFile("smedit-expanded-rom-", ".smc")
        try {
            file.writeBytes(rom)

            val error = assertFailsWith<IllegalArgumentException> {
                RomParser.loadRom(file.absolutePath)
            }

            assertContains(error.message.orEmpty(), "Unsupported ROM layout")
            assertContains(error.message.orEmpty(), "SMEDIT currently supports vanilla-layout")
            assertContains(error.message.orEmpty(), file.name)
        } finally {
            file.delete()
        }
    }

    private fun syntheticRom(size: Int): ByteArray =
        ByteArray(size) { 0xFF.toByte() }.also(::writeLoRomHeader)

    private fun writeLoRomHeader(rom: ByteArray) {
        val offset = 0x7FC0
        "Super Metroid        ".encodeToByteArray().copyInto(rom, offset)
        rom[offset + 0x15] = 0x30
        rom[offset + 0x16] = 0x02
        rom[offset + 0x17] = 0x0C
        rom[offset + 0x18] = 0x03
        rom[offset + 0x19] = 0x00
        rom[offset + 0x1A] = 0x01
        rom[offset + 0x1B] = 0x00
        write16(rom, offset + 0x1C, 0x353B)
        write16(rom, offset + 0x1E, 0xCAC4)
    }

    private fun writeCandidateRoomHeaderWithExpandedLevelData(rom: ByteArray) {
        val roomPc = 0x78094
        rom[roomPc] = 0x01
        rom[roomPc + 1] = 0x00
        rom[roomPc + 2] = 0x12
        rom[roomPc + 3] = 0x03
        rom[roomPc + 4] = 0x05
        rom[roomPc + 5] = 0x01
        rom[roomPc + 6] = 0x70
        rom[roomPc + 7] = 0xA0.toByte()
        rom[roomPc + 8] = 0x00
        write16(rom, roomPc + 9, 0x9000)
        write16(rom, roomPc + 11, 0xE5E6)

        val statePc = roomPc + 13
        write24(rom, statePc, 0xE18000)
        rom[statePc + 3] = 0x04

        val levelDataPc = 0x308000
        rom[levelDataPc] = 0x01
        rom[levelDataPc + 1] = 0x00
        rom[levelDataPc + 2] = 0x00
        rom[levelDataPc + 3] = 0xFF.toByte()
    }

    private fun writeRelocatedTilesetTable(rom: ByteArray) {
        rom[0x78094 + 13 + 3] = 0
        val tablePc = 0x7A000
        val tileTableSnes = 0xE18020
        val gfxSnes = 0xE18080
        val paletteSnes = 0xE18120
        write24(rom, tablePc, tileTableSnes)
        write24(rom, tablePc + 3, gfxSnes)
        write24(rom, tablePc + 6, paletteSnes)

        val tileTable = ByteArray(8)
        write16(tileTable, 0, TileGraphics.encodeMetatileWord(tileNum = 0, palette = 1))
        write16(tileTable, 2, TileGraphics.encodeMetatileWord(tileNum = 0, palette = 1))
        write16(tileTable, 4, TileGraphics.encodeMetatileWord(tileNum = 0, palette = 1))
        write16(tileTable, 6, TileGraphics.encodeMetatileWord(tileNum = 0, palette = 1))
        writeBytesAtSnes(rom, tileTableSnes, lz5Direct(tileTable))

        val gfx = ByteArray(TileGraphics.BYTES_PER_TILE)
        for (row in 0 until 8) {
            gfx[row * 2] = 0xFF.toByte()
        }
        writeBytesAtSnes(rom, gfxSnes, lz5Direct(gfx))

        val palette = ByteArray(256)
        write16(palette, (1 * 16 + 1) * 2, 0x001F)
        writeBytesAtSnes(rom, paletteSnes, lz5Direct(palette))
    }

    private fun writeBytesAtSnes(rom: ByteArray, snesAddress: Int, bytes: ByteArray) {
        val pc = ((snesAddress ushr 16) and 0x7F) * 0x8000 + (snesAddress and 0x7FFF)
        bytes.copyInto(rom, pc)
    }

    private fun lz5Direct(data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        var cursor = 0
        while (cursor < data.size) {
            val count = minOf(32, data.size - cursor)
            out.add((count - 1).toByte())
            for (i in 0 until count) {
                out.add(data[cursor + i])
            }
            cursor += count
        }
        out.add(0xFF.toByte())
        return out.toByteArray()
    }

    private fun write16(rom: ByteArray, offset: Int, value: Int) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun write24(rom: ByteArray, offset: Int, value: Int) {
        rom[offset] = (value and 0xFF).toByte()
        rom[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        rom[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }
}
