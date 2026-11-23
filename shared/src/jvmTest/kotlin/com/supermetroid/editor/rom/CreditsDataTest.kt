package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class CreditsDataTest {

    private fun loadParser(): RomParser? = try {
        TestRomHelper.loadRomParser()
    } catch (_: Exception) { null }

    @Test
    fun `small font tile encoding round-trips A through Z`() {
        for (ch in 'A'..'Z') {
            val tile = CreditsData.smallTile(ch)
            val decoded = CreditsData.smallTileToChar(tile)
            assertEquals(ch, decoded, "Small font round-trip failed for '$ch' (tile 0x${tile.toString(16)})")
        }
    }

    @Test
    fun `large font tile encoding round-trips A through Z`() {
        for (ch in 'A'..'Z') {
            val topTile = CreditsData.largeTileTop(ch)
            val decoded = CreditsData.largeTileTopToChar(topTile)
            assertEquals(ch, decoded, "Large font round-trip failed for '$ch' (tile 0x${topTile.toString(16)})")
        }
    }

    @Test
    fun `large font bottom tile is top plus 0x10`() {
        for (ch in 'A'..'Z') {
            val top = CreditsData.largeTileTop(ch)
            val bottom = CreditsData.largeTileBottom(ch)
            if (top != CreditsData.SPACE_TILE) {
                assertEquals(top + 0x10, bottom,
                    "Bottom tile should be top + 0x10 for '$ch'")
            }
        }
    }

    @Test
    fun `space maps to SPACE_TILE for all font variants`() {
        assertEquals(CreditsData.SPACE_TILE, CreditsData.smallTile(' '))
        assertEquals(CreditsData.SPACE_TILE, CreditsData.largeTileTop(' '))
        assertEquals(CreditsData.SPACE_TILE, CreditsData.largeTileBottom(' '))
    }

    @Test
    fun `encode header row produces 64 bytes with centered text`() {
        val row = CreditsData.encodeHeaderRow("TEST")
        assertEquals(64, row.size)
        // "TEST" = 4 chars centered in 32 tiles: starts at column 14
        val word14 = readWord(row, 14 * 2)
        val tile14 = word14 and 0x3FF
        assertEquals(CreditsData.smallTile('T'), tile14,
            "First char 'T' should be at column 14")
    }

    @Test
    fun `encode header row is decodable`() {
        val text = "SPECIAL THANKS"
        val row = CreditsData.encodeHeaderRow(text)
        val decoded = CreditsData.decodeHeaderRow(row)
        assertEquals(text, decoded)
    }

    @Test
    fun `encode name top row is decodable`() {
        val text = "JOHN SMITH"
        val topRow = CreditsData.encodeNameTopRow(text)
        val decoded = CreditsData.decodeNameRow(topRow)
        assertEquals(text, decoded)
    }

    @Test
    fun `blank row detection works`() {
        val blankRow = ByteArray(64)
        for (col in 0 until 32) {
            val off = col * 2
            blankRow[off] = CreditsData.MARGIN_TILE.toByte()
            blankRow[off + 1] = 0
        }
        assertTrue(CreditsData.isBlankRow(blankRow))
    }

    @Test
    fun `large font detection identifies name rows`() {
        val nameRow = CreditsData.encodeNameTopRow("HELLO")
        assertTrue(CreditsData.isLargeFont(nameRow))

        val headerRow = CreditsData.encodeHeaderRow("HELLO")
        assertTrue(!CreditsData.isLargeFont(headerRow),
            "Small font header row should not be detected as large font")
    }

    @Test
    fun `parse credits from ROM returns valid data`() {
        val parser = loadParser() ?: return
        val parsed = CreditsData.parseCredits(parser)
        assertNotNull(parsed, "Credits should parse from vanilla ROM")
        parsed!!

        assertTrue(parsed.tilemapRows.size == 128, "Should have 128 tilemap rows")
        assertTrue(parsed.sections.isNotEmpty(), "Should have at least one section")
        assertTrue(parsed.freeRowIndices.isNotEmpty(), "Should have free rows available")

        // The first section header should be "SUPER METROID STAFF"
        val firstSection = parsed.sections.first()
        assertEquals("SUPER METROID STAFF", firstSection.header,
            "First credits section should be 'SUPER METROID STAFF'")

        println("Parsed ${parsed.sections.size} credits sections:")
        for (section in parsed.sections) {
            println("  ${section.header}: ${section.names.joinToString(", ")}")
        }
        println("Free rows: ${parsed.freeRowIndices.size}")
        println("Used rows: ${parsed.usedRowIndices.size}")
    }

    @Test
    fun `build credits export with one group succeeds`() {
        val parser = loadParser() ?: return
        val groups = listOf(
            CreditsData.CreditGroup(
                header = "LEVEL DESIGN",
                names = listOf("JOHN DOE", "JANE SMITH"),
            )
        )
        val export = CreditsData.buildCreditsExport(parser, groups)
        assertNotNull(export, "Export should succeed for one group")
        export!!

        assertTrue(export.tilemapBytes.isNotEmpty(), "Tilemap should be non-empty")
        assertTrue(export.instListBytes.isNotEmpty(), "Instruction list should be non-empty")
        assertTrue(export.instListSnesAddr and 0xFF0000 == 0x8C0000,
            "Instruction list should be in bank \$8C")

        println("Compressed tilemap: ${export.tilemapBytes.size} bytes")
        println("Instruction list: ${export.instListBytes.size} bytes")
        println("Instruction list SNES: \$${export.instListSnesAddr.toString(16).uppercase()}")
    }

    @Test
    fun `relocated export reparses through its live pointer`() {
        val parser = loadParser() ?: return
        val groups = listOf(
            CreditsData.CreditGroup("SMEDIT TEAM", listOf("SAMUS ARAN", "BABY METROID")),
        )
        val exportValue = CreditsData.buildCreditsExport(parser, groups, 0x8CF3E9)
        assertNotNull(exportValue)
        val export = exportValue!!
        val rom = parser.copyRomData()
        export.tilemapBytes.copyInto(rom, parser.snesToPc(CreditsData.CREDITS_TILEMAP_SNES))
        export.instListBytes.copyInto(rom, parser.snesToPc(export.instListSnesAddr))
        writeWord(rom, parser.snesToPc(CreditsData.CREDITS_OBJ_DEF_INSTLIST_SNES), export.instListSnesAddr)

        val reparsedValue = CreditsData.parseCredits(RomParser(rom))
        assertNotNull(reparsedValue)
        val reparsed = reparsedValue!!
        assertEquals(export.instListSnesAddr, reparsed.instListSnesAddr)
        assertEquals("SMEDIT TEAM", reparsed.sections.last().header)
        assertEquals(listOf("SAMUS ARAN", "BABY METROID"), reparsed.sections.last().names)
    }

    @Test
    fun `headered ROM uses physical offsets for every credits write`() {
        val body = TestRomHelper.requireRomBytes()
        val headered = ByteArray(body.size + 0x200) { 0x5A.toByte() }
        body.copyInto(headered, 0x200)
        val parser = RomParser(headered)
        val exportValue = CreditsData.buildCreditsExport(
                parser,
                listOf(CreditsData.CreditGroup("HEADER TEST", listOf("SAMUS"))),
                0x8CF3E9,
            )
        assertNotNull(exportValue)
        val export = exportValue!!
        export.tilemapBytes.copyInto(headered, parser.snesToPc(CreditsData.CREDITS_TILEMAP_SNES))
        export.instListBytes.copyInto(headered, parser.snesToPc(export.instListSnesAddr))
        writeWord(headered, parser.snesToPc(CreditsData.CREDITS_OBJ_DEF_INSTLIST_SNES), export.instListSnesAddr)

        assertArrayEquals(ByteArray(0x200) { 0x5A.toByte() }, headered.copyOfRange(0, 0x200))
        val reparsedValue = CreditsData.parseCredits(RomParser(headered))
        assertNotNull(reparsedValue)
        val reparsed = reparsedValue!!
        assertEquals("HEADER TEST", reparsed.sections.last().header)
    }

    @Test
    fun `row budget and compressed source envelope fail closed`() {
        val parser = loadParser() ?: return
        val parsedValue = CreditsData.parseCredits(parser)
        assertNotNull(parsedValue)
        val parsed = parsedValue!!
        val tooManyRows = listOf(
            CreditsData.CreditGroup("TEAM", List(CreditsData.MAX_NAMES_PER_GROUP) { "NAME" }),
        )
        assertTrue(CreditsData.validateGroups(tooManyRows, 2).any { "tilemap rows" in it })

        val rom = parser.copyRomData()
        val tilemap = parser.decompressLZ2(CreditsData.CREDITS_TILEMAP_SNES).copyOf(CreditsData.TILEMAP_SIZE)
        var value = 0x13579BDF
        parsed.freeRowIndices.forEach { row ->
            repeat(CreditsData.ROW_BYTES) { byte ->
                value = value * 1103515245 + 12345
                tilemap[row * CreditsData.ROW_BYTES + byte] = (value ushr 16).toByte()
            }
        }
        val incompressible = LZ5Compressor.compress(tilemap)
        assertTrue(incompressible.size > CreditsData.CREDITS_TILEMAP_CAPACITY)
        incompressible.copyInto(rom, parser.snesToPc(CreditsData.CREDITS_TILEMAP_SNES))
        assertNull(
            CreditsData.buildCreditsExport(
                RomParser(rom),
                listOf(CreditsData.CreditGroup("TEAM", listOf("NAME"))),
                0x8CF3E9,
            ),
        )
    }

    @Test
    fun `validation rejects empty header`() {
        assertNotNull(CreditsData.validateHeader(""))
        assertNotNull(CreditsData.validateHeader("   "))
    }

    @Test
    fun `validation rejects oversized header`() {
        val long = "A".repeat(CreditsData.MAX_HEADER_LENGTH + 1)
        assertNotNull(CreditsData.validateHeader(long))
    }

    @Test
    fun `validation accepts valid header`() {
        assertNull(CreditsData.validateHeader("SPECIAL THANKS"))
    }

    @Test
    fun `validation rejects invalid characters`() {
        assertNotNull(CreditsData.validateHeader("TEST 123"))
        assertNotNull(CreditsData.validateName("JOHN.DOE"))
    }

    private fun readWord(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeWord(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }
}
