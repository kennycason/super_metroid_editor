package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
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
            assertEquals("Gauntlet Entrance", catalog.rooms.single().name)
            assertContains(catalog.loadNotice(file.name).orEmpty(), "Read-only expanded ROM layout loaded")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `expanded room headers use first readable state`() {
        val rom = syntheticRom(0x400000)
        writeCandidateRoomHeaderWithConditionalState(rom)

        val parser = RomParser(rom)
        val room = requireNotNull(parser.readRoomHeader(0x8094))

        assertEquals(0xE18000, room.levelDataPtr)
        assertEquals(2, room.tileset)
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
    fun `tile graphics loads from relocated bank 8F indirect tileset entry table`() {
        val rom = syntheticRom(0x400000)
        writeCandidateRoomHeaderWithExpandedLevelData(rom)
        writeIndirectTilesetEntryTable(rom)

        val parser = RomParser(rom)
        val catalog = parser.graphicsCatalog
        val tileGraphics = TileGraphics(parser)

        assertEquals(RomGraphicsCatalogSource.DISCOVERED_BANK_8F_INDIRECT, catalog.source)
        assertEquals(0x8F8E24, catalog.tableSnesAddress)
        assertEquals(0xE18020, catalog.entry(0)?.tileTablePtr)
        assertTrue(tileGraphics.loadTileset(0))
        val pixels = assertNotNull(tileGraphics.renderMetatile(TileGraphics.CRE_METATILE_COUNT))
        assertTrue(pixels.any { (it ushr 24) != 0 })
    }

    @Test
    fun `tile graphics loads relocated CRE data from expanded rom loader references`() {
        val rom = syntheticRom(0x400000)
        writeCandidateRoomHeaderWithExpandedLevelData(rom)
        writeRelocatedTilesetTable(rom)
        val creTileTableSnes = 0xE19000
        val creGfxSnes = 0xE1A000
        writeRelocatedCreData(rom, creTileTableSnes, creGfxSnes)
        writeDecompressionPointerReference(rom, 0x1000, creGfxSnes)
        writeDecompressionPointerReference(rom, 0x1020, creTileTableSnes)

        val parser = RomParser(rom)
        val tileGraphics = TileGraphics(parser)

        assertEquals(creTileTableSnes, parser.graphicsCatalog.creTileTablePtr)
        assertEquals(creGfxSnes, parser.graphicsCatalog.creGfxPtr)
        assertTrue(tileGraphics.loadTileset(0))
        val pixels = assertNotNull(tileGraphics.renderMetatile(0))
        assertTrue(pixels.any { (it ushr 24) != 0 })
    }

    @Test
    fun `map renderer uses collision fallback for placeholder metatiles`() {
        val rom = syntheticRom(0x400000)
        writeCandidateRoomHeaderWithExpandedLevelData(rom)
        writeRelocatedTilesetTable(rom)
        val creTileTableSnes = 0xE19000
        val creGfxSnes = 0xE1A000
        writeRelocatedCreData(rom, creTileTableSnes, creGfxSnes, placeholderFirstMetatile = true)
        writeDecompressionPointerReference(rom, 0x1000, creGfxSnes)
        writeDecompressionPointerReference(rom, 0x1020, creTileTableSnes)
        val parser = RomParser(rom)
        val room = syntheticRoom(area = 1, tileset = 0)
        val levelData = singleScreenLevelData(firstBlockWord = 0x8000)

        val render = requireNotNull(
            MapRenderer(parser).renderRoomFromLevelData(
                room,
                levelData,
                plmOverrides = emptyList(),
                enemyOverrides = emptyList(),
            )
        )

        assertEquals(0xFF4A6848.toInt(), render.pixels[0])
    }

    @Test
    fun `map renderer uses collision fallback for transparent metatiles`() {
        val rom = syntheticRom(0x400000)
        writeCandidateRoomHeaderWithExpandedLevelData(rom)
        writeRelocatedTilesetTable(rom, blankGfx = true)
        val parser = RomParser(rom)
        val room = syntheticRoom(area = 1, tileset = 0)
        val levelData = singleScreenLevelData(firstBlockWord = 0x8100)

        val render = requireNotNull(
            MapRenderer(parser).renderRoomFromLevelData(
                room,
                levelData,
                plmOverrides = emptyList(),
                enemyOverrides = emptyList(),
            )
        )

        assertEquals(0xFF4A6848.toInt(), render.pixels[0])
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
    fun `expanded rom fixture reports room state graphics diagnostics`() {
        val path = System.getProperty("smedit.expandedRomFixture").orEmpty()
        if (path.isBlank()) return
        val parser = RomParser.loadRom(path)
        val renderOut = System.getProperty("smedit.expandedRomRenderOut").orEmpty()
        println(
            "graphicsCatalog source=${parser.graphicsCatalog.source} " +
                "table=${hex24(parser.graphicsCatalog.tableSnesAddress)} " +
                "creTable=${hex24(parser.graphicsCatalog.creTileTablePtr)} " +
                "creGfx=${hex24(parser.graphicsCatalog.creGfxPtr)}"
        )

        val fixedCreTableSize = runCatching { parser.decompressLZ2(TileGraphics.CRE_TILE_TABLE_SNES).size }.getOrNull()
        val fixedCreGfxSize = runCatching { parser.decompressLZ2(TileGraphics.CRE_GFX_SNES).size }.getOrNull()
        if (fixedCreTableSize != TileGraphics.CRE_METATILE_COUNT * 8) {
            assertTrue(parser.graphicsCatalog.creTileTablePtr != TileGraphics.CRE_TILE_TABLE_SNES)
        }
        if (fixedCreGfxSize != (TileGraphics.TOTAL_TILES - TileGraphics.CRE_TILE_START) * TileGraphics.BYTES_PER_TILE) {
            assertTrue(parser.graphicsCatalog.creGfxPtr != TileGraphics.CRE_GFX_SNES)
        }

        for (roomId in listOf(0x8120, 0x89E0, 0x8BCA, 0xB2EE)) {
            val room = parser.readRoomHeader(roomId) ?: continue
            val states = parser.parseRoomStatesWithData(roomId)
            println(
                "room ${hex16(roomId)} selected=level=${hex24(room.levelDataPtr)} " +
                    "tileset=${room.tileset} states=${states.size}"
            )
            assertTrue(TileGraphics(parser).loadTileset(room.tileset))
            val render = requireNotNull(MapRenderer(parser).renderRoom(room))
            assertTrue(render.pixels.toSet().size > 1)
            if (renderOut.isNotBlank()) {
                writeRoomRenderPng(parser, room, File(renderOut, "room_${hex16(roomId)}.png"))
            }
        }
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

    private fun writeCandidateRoomHeaderWithConditionalState(rom: ByteArray) {
        val roomPc = 0x78094
        rom[roomPc] = 0x01
        rom[roomPc + 1] = 0x00
        rom[roomPc + 2] = 0x12
        rom[roomPc + 3] = 0x03
        rom[roomPc + 4] = 0x01
        rom[roomPc + 5] = 0x01
        rom[roomPc + 6] = 0x70
        rom[roomPc + 7] = 0xA0.toByte()
        rom[roomPc + 8] = 0x00
        write16(rom, roomPc + 9, 0x9000)

        write16(rom, roomPc + 11, 0xE612)
        rom[roomPc + 13] = 0x00
        write16(rom, roomPc + 14, 0x9000)
        write16(rom, roomPc + 16, 0xE5E6)
        writeStateData(rom, roomPc + 18, levelDataPtr = 0xE18020, tileset = 3)

        val conditionalStatePc = 0x79000
        writeStateData(rom, conditionalStatePc, levelDataPtr = 0xE18000, tileset = 2)
        writeBytesAtSnes(rom, 0xE18000, lz5Direct(byteArrayOf(0x00, 0x00)))
        writeBytesAtSnes(rom, 0xE18020, lz5Direct(byteArrayOf(0x00, 0x00)))
    }

    private fun writeStateData(rom: ByteArray, pc: Int, levelDataPtr: Int, tileset: Int) {
        write24(rom, pc, levelDataPtr)
        rom[pc + 3] = tileset.toByte()
    }

    private fun writeRelocatedTilesetTable(rom: ByteArray, blankGfx: Boolean = false) {
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
        if (!blankGfx) {
            for (row in 0 until 8) {
                gfx[row * 2] = 0xFF.toByte()
            }
        }
        writeBytesAtSnes(rom, gfxSnes, lz5Direct(gfx))

        val palette = ByteArray(256)
        write16(palette, (1 * 16 + 1) * 2, 0x001F)
        writeBytesAtSnes(rom, paletteSnes, lz5Direct(palette))
    }

    private fun writeIndirectTilesetEntryTable(rom: ByteArray) {
        rom[0x78094 + 13 + 3] = 0
        val pointerTablePc = 0x78E24
        val firstEntryPc = 0x7A2C8
        val firstEntryOffset = 0xA2C8
        val tileTableSnes = 0xE18020
        val gfxSnes = 0xE18080
        val paletteSnes = 0xE18120

        // SMART-style layouts keep a 29-entry pointer table in bank $8F;
        // each 16-bit offset points to the 9-byte graphics entry record.
        for (tilesetId in 0 until TileGraphics.NUM_TILESETS) {
            val entryPc = firstEntryPc + tilesetId * 9
            write16(rom, pointerTablePc + tilesetId * 2, firstEntryOffset + tilesetId * 9)
            write24(rom, entryPc, tileTableSnes)
            write24(rom, entryPc + 3, gfxSnes)
            write24(rom, entryPc + 6, paletteSnes)
        }

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

    private fun writeRelocatedCreData(
        rom: ByteArray,
        tileTableSnes: Int,
        gfxSnes: Int,
        placeholderFirstMetatile: Boolean = false,
    ) {
        val tileTable = ByteArray(TileGraphics.CRE_METATILE_COUNT * 8)
        for (wordIndex in 0 until TileGraphics.CRE_METATILE_COUNT * 4) {
            val tile = if (placeholderFirstMetatile && wordIndex < 4) {
                0x3FF
            } else {
                TileGraphics.CRE_TILE_START +
                    (wordIndex % (TileGraphics.TOTAL_TILES - TileGraphics.CRE_TILE_START))
            }
            write16(tileTable, wordIndex * 2, TileGraphics.encodeMetatileWord(tileNum = tile, palette = 1))
        }
        writeBytesAtSnes(rom, tileTableSnes, lz5Direct(tileTable))

        val gfx = ByteArray((TileGraphics.TOTAL_TILES - TileGraphics.CRE_TILE_START) * TileGraphics.BYTES_PER_TILE)
        for (i in gfx.indices) {
            gfx[i] = ((i * 37 + 11) and 0xFF).toByte()
        }
        for (row in 0 until 8) {
            gfx[row * 2] = 0xFF.toByte()
        }
        writeBytesAtSnes(rom, gfxSnes, lz5Direct(gfx))
    }

    private fun writeDecompressionPointerReference(rom: ByteArray, pc: Int, snesAddress: Int) {
        val bank = (snesAddress ushr 16) and 0xFF
        val lowWord = snesAddress and 0xFFFF
        rom[pc] = 0xA9.toByte()
        write16(rom, pc + 1, bank shl 8)
        rom[pc + 3] = 0x85.toByte()
        rom[pc + 4] = 0x48
        rom[pc + 5] = 0xA9.toByte()
        write16(rom, pc + 6, lowWord)
        rom[pc + 8] = 0x85.toByte()
        rom[pc + 9] = 0x47
        rom[pc + 10] = 0x22
        rom[pc + 11] = 0xFF.toByte()
        rom[pc + 12] = 0xB0.toByte()
        rom[pc + 13] = 0x80.toByte()
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

    private fun syntheticRoom(area: Int, tileset: Int): Room =
        Room(
            roomId = 0x8094,
            name = "Synthetic Room",
            handle = "syntheticRoom",
            index = 0,
            area = area,
            mapX = 0,
            mapY = 0,
            width = 1,
            height = 1,
            upScroller = 0,
            downScroller = 0,
            creBitflag = 0,
            doorOut = 0,
            levelDataPtr = 0,
            tileset = tileset,
            plmSetPtr = 0,
            enemySetPtr = 0,
        )

    private fun singleScreenLevelData(firstBlockWord: Int): ByteArray {
        val totalBlocks = 16 * 16
        val layer1Size = totalBlocks * 2
        val data = ByteArray(2 + layer1Size + totalBlocks)
        write16(data, 0, layer1Size)
        write16(data, 2, firstBlockWord)
        return data
    }

    private fun hex16(value: Int): String =
        "0x${(value and 0xFFFF).toString(16).uppercase().padStart(4, '0')}"

    private fun hex24(value: Int): String =
        "0x${(value and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')}"

    private fun writeRoomRenderPng(parser: RomParser, room: Room, file: File) {
        val render = MapRenderer(parser).renderRoom(room) ?: return
        file.parentFile.mkdirs()
        val image = java.awt.image.BufferedImage(render.width, render.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, render.width, render.height, render.pixels, 0, render.width)
        javax.imageio.ImageIO.write(image, "png", file)
        println("wrote render ${file.absolutePath}")
    }
}
