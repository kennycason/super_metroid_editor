package com.supermetroid.editor.api

import com.supermetroid.editor.asm.AsmWorkspaceState
import com.supermetroid.editor.asm.objectSourceTestRom
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.SourceFxResource
import com.supermetroid.editor.rom.emptyDefaultFxEntry
import com.supermetroid.editor.rom.ensureStateManifest
import com.supermetroid.editor.ui.EditorState
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DesktopSmeditWorkspaceAccessTest {
    @Test
    fun `room details expose every state and ordered effective FX records`() {
        val rom = objectSourceTestRom()
        fun word(pc: Int, value: Int) {
            rom[pc] = value.toByte()
            rom[pc + 1] = (value ushr 8).toByte()
        }
        word(0x7920A + 6, 0x8800)
        word(0x79300 + 6, 0x8800)
        SourceFxResource(
            "fixture",
            listOf(
                emptyDefaultFxEntry().copy(doorSelect = 0x9000, fxType = 4, liquidSurfaceStart = 0x120),
                emptyDefaultFxEntry().copy(fxType = 2, fxBitC = 2),
            ),
        ).bytes().copyInto(rom, 0x18800)
        val parser = RomParser(rom)
        val editor = EditorState()
        val edits = editor.project.getOrCreateRoom(0x91F8).also { it.ensureStateManifest(parser) }
        edits.states.last().fxChange = FxChange(paletteBlend = 0x20)
        val access = DesktopSmeditWorkspaceAccess(
            editorState = editor,
            asmWorkspaceState = AsmWorkspaceState(),
            parser = { parser },
            roomInfos = { listOf(RoomInfo("0x91F8", "landing-site", "Landing Site")) },
            selectedRoom = { null },
        )

        val room = access.room(0x91F8)!!

        assertEquals(2, room.states.size)
        assertTrue(room.states.first().conditionKind != "DEFAULT")
        assertTrue(room.states.last().defaultState)
        assertEquals(listOf("door_specific", "default"), room.states.last().fxEntries.map { it.selector })
        assertEquals(listOf("bg_animate"), room.states.last().fxEntries.last().liquidOptions)
        assertEquals(0x20, room.states.last().fxEntries.last().paletteBlend)
    }

    @Test
    fun `documentation retrieval is useful and cannot escape the docs root`() {
        val access = DesktopSmeditWorkspaceAccess(
            editorState = EditorState(),
            asmWorkspaceState = AsmWorkspaceState(),
            parser = { null },
            roomInfos = { emptyList() },
            selectedRoom = { null },
        )

        val matches = access.searchDocs("state-sensitive room music", limit = 10)
        assertTrue(matches.any { it.path == "ai/semantic_safety.md" })

        val excerpt = access.readDoc("ai/semantic_safety.md", startLine = 1, maxLines = 40)
        assertTrue(excerpt?.text?.contains("State-sensitive room music") == true)
        assertNull(access.readDoc("../README.md", startLine = 1, maxLines = 20))
        assertNull(access.readDoc("/etc/passwd", startLine = 1, maxLines = 20))
    }

    @Test
    fun `palette and item catalogs expose boss colors and state aware room items`() {
        val parser = RomParser(objectSourceTestRom())
        val access = DesktopSmeditWorkspaceAccess(
            editorState = EditorState(),
            asmWorkspaceState = AsmWorkspaceState(),
            parser = { parser },
            roomInfos = { listOf(RoomInfo("0x91F8", "landing-site", "Landing Site")) },
            selectedRoom = { null },
        )

        val kraid = access.palette("sprite:boss_kraid_health_bg")
        assertEquals(160, kraid?.colorCount)
        assertEquals(160, kraid?.colorsBgr555?.size)
        assertTrue(kraid?.usage?.contains("full-health assembled preview") == true)
        assertTrue("sprite:boss_kraid_health_oam" in kraid?.relatedPaletteIds.orEmpty())
        val phantoon = access.palette("sprite:boss_phantoon_health")
        assertEquals(128, phantoon?.colorCount)
        assertEquals(128, phantoon?.colorsBgr555?.size)
        assertTrue(phantoon?.usage?.contains("indices 112–127") == true)
        assertEquals(listOf("sprite:boss_phantoon"), phantoon?.relatedPaletteIds)
        assertTrue(access.itemTypes().any { it.name == "Missile" && it.variant == "Visible" })
        val roomItems = access.roomItems(0x91F8)
        assertEquals(2, roomItems?.states?.size)
        assertEquals(32, roomItems?.widthBlocks)
    }

    @Test
    fun `tile inspection decodes effective metatile palette colors and searchable coordinates`() {
        val parser = RomParser(tileInspectionRom())
        val editor = EditorState()
        val overridePalette = ByteArray(256)
        val greenOffset = (1 * 16 + 1) * 2
        overridePalette[greenOffset] = 0xE0.toByte()
        overridePalette[greenOffset + 1] = 0x03
        editor.project.customGfx.palettes[TileGraphics.KRAID_TILESET.toString()] =
            java.util.Base64.getEncoder().encodeToString(overridePalette)
        val access = DesktopSmeditWorkspaceAccess(
            editorState = editor,
            asmWorkspaceState = AsmWorkspaceState(),
            parser = { parser },
            roomInfos = { listOf(RoomInfo("0x91F8", "landing-site", "Landing Site")) },
            selectedRoom = { null },
        )

        val inspected = access.roomTiles(
            0x91F8,
            stateId = null,
            coordinates = listOf(SmeditRoomTileCoordinate(2, 3, pixelX = 4, pixelY = 5)),
        )!!
        val tile = inspected.tiles.single()
        assertEquals("state-2", inspected.stateId)
        assertEquals(0x8003, tile.blockWord)
        assertEquals(8, tile.blockType)
        assertEquals(3, tile.metatileId)
        assertEquals(listOf(1), tile.metatile.subtiles.first().usedColorIndices)
        assertEquals(1, tile.metatile.subtiles.first().paletteRow)
        assertEquals(0x03E0, tile.sampledPixel?.color?.bgr555)
        assertEquals("#00FF00", tile.sampledPixel?.color?.rgbHex)

        val found = access.findRoomTiles(
            roomId = 0x91F8,
            stateId = "state-1",
            layer = 1,
            metatileIds = emptySet(),
            paletteRows = setOf(1),
            colorIndices = setOf(1),
            colorsBgr555 = setOf(0x03E0),
            blockTypes = setOf(8),
            btsValues = setOf(0),
            limit = 3,
        )!!
        assertEquals(256, found.totalMatches)
        assertEquals(3, found.matches.size)
        assertTrue(found.truncated)
        assertEquals(listOf(17), found.matches.first().usedPaletteIndices)

        val definition = access.tilesetMetatile(TileGraphics.KRAID_TILESET, 3)!!
        assertEquals("Tileset-local variable metatile table", definition.tableSource)
        assertEquals(0x03E0, definition.subtiles.first().usedColors.single().bgr555)
    }

    private fun tileInspectionRom(): ByteArray = objectSourceTestRom().also { rom ->
        val roomHeader = 0x791F8
        rom[roomHeader + 4] = 1
        rom[roomHeader + 5] = 1
        val tileset = TileGraphics.KRAID_TILESET
        fun long(pc: Int, value: Int) {
            rom[pc] = value.toByte()
            rom[pc + 1] = (value ushr 8).toByte()
            rom[pc + 2] = (value ushr 16).toByte()
        }
        long(0x7920A, 0xA08000)
        long(0x79300, 0xA08000)
        rom[0x7920A + 3] = tileset.toByte()
        rom[0x79300 + 3] = tileset.toByte()

        val level = ByteArray(2 + 16 * 16 * 3)
        level[0] = 0x00
        level[1] = 0x02
        repeat(16 * 16) { index ->
            val offset = 2 + index * 2
            level[offset] = 0x03
            level[offset + 1] = 0x80.toByte()
        }
        LZ5Compressor.compress(level).copyInto(rom, 0x100000)

        val table = ByteArray(TileGraphics.METATILE_COUNT * 8)
        repeat(4) { quadrant ->
            val offset = 3 * 8 + quadrant * 2
            table[offset] = 0x01
            table[offset + 1] = 0x04
        }
        LZ5Compressor.compress(table).copyInto(rom, 0x108000)

        val graphics = ByteArray(TileGraphics.TOTAL_TILES * TileGraphics.BYTES_PER_TILE)
        repeat(8) { y -> graphics[TileGraphics.BYTES_PER_TILE + y * 2] = 0xFF.toByte() }
        LZ5Compressor.compress(graphics).copyInto(rom, 0x110000)

        val palette = ByteArray(256)
        val redOffset = (1 * 16 + 1) * 2
        palette[redOffset] = 0x1F
        LZ5Compressor.compress(palette).copyInto(rom, 0x118000)

        val entry = 0x7E6A2 + tileset * 9
        long(entry, 0xA18000)
        long(entry + 3, 0xA28000)
        long(entry + 6, 0xA38000)
    }
}
