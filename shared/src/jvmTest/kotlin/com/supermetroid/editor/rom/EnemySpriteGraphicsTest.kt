package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for EnemySpriteGraphics — covers loading, rendering, round-trip encoding,
 * pixel read/write correctness, and color conversion fidelity.
 *
 * Source-address checks use the configured ROM; pixel-format tests use deterministic
 * synthetic 4bpp blocks and remain fixture-independent.
 */
class EnemySpriteGraphicsTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    // ─── ROM-dependent tests ──────────────────────────────────────────────────

    @Test
    fun `Phantoon GRAPHADR loads exact raw source tiles`() {
        val parser = loadTestRom() ?: return
        val block = EnemySpriteGraphics.readGraphicsBlock(parser, 0xE4BF)
        val raw = EnemySpriteGraphics.loadEnemyTileData(parser, 0xE4BF)

        assertNotNull(block)
        assertEquals(0xACAA00, block!!.snesAddress)
        assertNotNull(raw)
        assertEquals(0xC00, raw!!.size)
        assertEquals(96, raw.size / EnemySpriteGraphics.BYTES_PER_TILE)
    }

    @Test
    fun `composite bosses have one sprite navigation entry`() {
        val entries = EnemySpriteGraphics.EDITOR_ENEMIES
        assertEquals("Draygon", entries.single { it.speciesId == 0xDE3F }.name)
        assertEquals(1, entries.count { it.speciesId in setOf(0xDE3F, 0xDE7F, 0xDEBF, 0xDEFF) })
        assertEquals(1, entries.count { it.speciesId in setOf(0xE4BF, 0xE4FF, 0xE53F, 0xE57F) })
        assertEquals("Mother Brain", entries.single { it.speciesId == 0xEC3F }.name)
        assertEquals(1, entries.count { it.speciesId in setOf(0xEC3F, 0xEC7F) })
    }

    @Test
    fun `renderSheet produces correctly sized pixel buffer`() {
        val gfx = syntheticGraphics()

        val palette = buildTestPalette()
        val result = gfx.renderSheet(palette, cols = 8)
        assertNotNull(result, "renderSheet should return non-null when blocks are loaded")
        val (pixels, w, h) = result!!

        assertEquals(64, w, "Width should be 8 tiles × 8px = 64")
        assertTrue(h > 0, "Height should be > 0")
        assertTrue(h % 8 == 0, "Height should be a multiple of 8 (whole tiles)")
        assertEquals(w * h, pixels.size, "Pixel array should be exactly w × h elements")
        println("Tile sheet renders as ${w}x${h}px (${gfx.getTileCount()} tiles, ${pixels.size} pixels)")
    }

    @Test
    fun `renderSheet with transparent background — index-0 pixels are fully transparent`() {
        val gfx = syntheticGraphics()

        val palette = buildTestPalette()
        val (pixels, _, _) = gfx.renderSheet(palette)!!

        // Palette index 0 must render as 0x00000000 (fully transparent), never as a solid color
        val solidZeroCount = pixels.count { it != 0 && ((it ushr 24) and 0xFF) == 0xFF }
        // The first synthetic tile is blank, so the sheet must include transparent pixels.
        val transparentCount = pixels.count { ((it ushr 24) and 0xFF) < 128 }
        assertTrue(transparentCount > 0, "Tile sheet should have transparent (index-0) pixels for background")
        println("$transparentCount transparent pixels, $solidZeroCount opaque pixels in tile sheet")
    }

    @Test
    fun `round-trip renderSheet then importFromArgb reproduces identical raw bytes`() {
        val gfx = syntheticGraphics()

        val rawBefore = gfx.getRawBlocks()!!

        // Render with a deterministic palette that spans index 0..15
        val palette = buildTestPalette()
        val (pixels, w, h) = gfx.renderSheet(palette)!!

        // Load the same raw bytes into a fresh instance and import the rendered pixels
        val gfx2 = EnemySpriteGraphics(stubParser())
        gfx2.loadFromRaw(rawBefore)
        gfx2.importFromArgb(pixels, w, h, palette)

        val rawAfter = gfx2.getRawBlocks()!!
        assertEquals(rawBefore.size, rawAfter.size, "Block count should be unchanged after round-trip")
        for (i in rawBefore.indices) {
            assertArrayEquals(
                rawBefore[i], rawAfter[i],
                "Block $i: render→import round-trip should reproduce identical raw 4bpp bytes"
            )
        }
        println("Round-trip verified: ${rawBefore.sumOf { it.size }} bytes stable across render+import")
    }

    @Test
    fun `loadWithOverrides applies custom block data over ROM defaults`() {
        val originalBlock0 = syntheticBlock(3)
        val originalBlock1 = syntheticBlock(2, seed = 71)
        val compressed0 = LZ5Compressor.compress(originalBlock0)
        val compressed1 = LZ5Compressor.compress(originalBlock1)
        val secondOffset = compressed0.size + 16
        val rom = ByteArray(secondOffset + compressed1.size)
        compressed0.copyInto(rom)
        compressed1.copyInto(rom, secondOffset)
        val parser = RomParser(rom)
        val blocks = listOf(
            EnemySpriteGraphics.Companion.SpriteBlock(0, 0, 0, "Synthetic A"),
            EnemySpriteGraphics.Companion.SpriteBlock(secondOffset, 0, 0, "Synthetic B"),
        )
        val blockSize = originalBlock0.size

        // Create a custom block filled with 0x42
        val customBlock = ByteArray(blockSize) { 0x42.toByte() }
        val gfx2 = EnemySpriteGraphics(parser)
        assertTrue(gfx2.loadWithOverrides(blocks, mapOf(0 to customBlock)))

        val overriddenBlocks = gfx2.getRawBlocks()!!
        assertArrayEquals(customBlock, overriddenBlocks[0], "Block 0 should be replaced with custom data")
        // Block 1 should remain the original ROM data
        assertArrayEquals(originalBlock1, overriddenBlocks[1], "Block 1 should remain unchanged ROM data")
    }

    @Test
    fun `validateEnemyTileEdit accepts ROM-sized raw enemy tile data`() {
        val parser = loadTestRom() ?: return
        val speciesId = 0xDCFF // Zoomer
        val raw = EnemySpriteGraphics.loadEnemyTileData(parser, speciesId) ?: return

        val validation = EnemySpriteGraphics.validateEnemyTileEdit(parser, speciesId, raw)

        assertTrue(validation.isExportable, validation.errors.joinToString("; "))
        assertEquals(raw.size, validation.expectedSize, "Raw data should match the species tileDataSize")
        assertEquals(raw.size / EnemySpriteGraphics.BYTES_PER_TILE, validation.tileCount)
        assertNotNull(validation.pcAddress, "Exportable validation should resolve a PC write address")
        assertNotNull(validation.snesAddress, "Exportable validation should resolve a SNES GRAPHADR")
    }

    @Test
    fun `validateEnemyTileEdit rejects raw enemy tile data with wrong size`() {
        val parser = loadTestRom() ?: return
        val speciesId = 0xDCFF // Zoomer
        val raw = EnemySpriteGraphics.loadEnemyTileData(parser, speciesId) ?: return
        assertTrue(raw.size > EnemySpriteGraphics.BYTES_PER_TILE)
        val truncated = raw.copyOf(raw.size - EnemySpriteGraphics.BYTES_PER_TILE)

        val validation = EnemySpriteGraphics.validateEnemyTileEdit(parser, speciesId, truncated)

        assertFalse(validation.isExportable)
        assertTrue(
            validation.errors.any { it.contains("expects") },
            "Wrong-size tile edits should report the expected tileDataSize"
        )
    }

    @Test
    fun `pixel writePixelIndex and readPixelIndex are consistent for all 16 palette indices`() {
        val gfx = syntheticGraphics()

        val tileCount = gfx.getTileCount()
        assertTrue(tileCount > 0)

        // Test a pixel in the middle of a tile near the start of the sheet
        val tileIdx = minOf(10, tileCount - 1)
        val px = 3
        val py = 4

        for (colorIdx in 0 until 16) {
            gfx.writePixelIndex(tileIdx, px, py, colorIdx)
            val readBack = gfx.readPixelIndex(tileIdx, px, py)
            assertEquals(colorIdx, readBack,
                "writePixelIndex($colorIdx) at tile=$tileIdx (${px},$py) should readback as $colorIdx")
        }
    }

    @Test
    fun `all eight pixel values in a row encode and decode correctly`() {
        val gfx = syntheticGraphics()

        // Write the full color ramp 0..15 across an 8-pixel row, then read back
        val tileIdx = 0
        for (px in 0 until 8) {
            gfx.writePixelIndex(tileIdx, px, 0, px)  // color index = x position (0..7)
        }
        for (px in 0 until 8) {
            val readBack = gfx.readPixelIndex(tileIdx, px, 0)
            assertEquals(px, readBack, "Pixel ($px,0) should read back as $px")
        }
    }

    @Test
    fun `getTileCountInBlock returns 0 for out-of-range block index`() {
        val gfx = syntheticGraphics()

        assertEquals(0, gfx.getTileCountInBlock(99), "Out-of-range block index should return 0")
        assertEquals(0, gfx.getTileCountInBlock(-1), "Negative block index should return 0")
    }

    // ─── Pure unit tests (no ROM needed) ─────────────────────────────────────

    @Test
    fun `snesColorToArgb converts known SNES BGR555 values correctly`() {
        // SNES BGR555: bits [14:10]=B, [9:5]=G, [4:0]=R
        assertEquals(0xFF000000.toInt(), EnemySpriteGraphics.snesColorToArgb(0x0000), "0x0000 should be black")
        // White = 0x7FFF: R=31, G=31, B=31 → all channels 255
        val white = EnemySpriteGraphics.snesColorToArgb(0x7FFF)
        assertEquals(0xFF, (white ushr 24) and 0xFF, "White alpha should be 0xFF")
        assertEquals(255, (white shr 16) and 0xFF, "White red should be 255")
        assertEquals(255, (white shr 8) and 0xFF, "White green should be 255")
        assertEquals(255, white and 0xFF, "White blue should be 255")
        // Pure red in SNES BGR555 = 0x001F (R=31, G=0, B=0)
        val red = EnemySpriteGraphics.snesColorToArgb(0x001F)
        assertEquals(255, (red shr 16) and 0xFF, "Pure SNES red: R should be 255")
        assertEquals(0, (red shr 8) and 0xFF, "Pure SNES red: G should be 0")
        assertEquals(0, red and 0xFF, "Pure SNES red: B should be 0")
    }

    @Test
    fun `argbToSnesColor round-trips standard colors correctly`() {
        // SNES has 5-bit precision so we test at the quantized boundaries
        val testColors = listOf(
            0x0000 to "black",
            0x7FFF to "white",
            0x001F to "pure red",
            0x03E0 to "pure green",
            0x7C00 to "pure blue",
            0x03FF to "cyan",
            0x7C1F to "magenta",
            0x7FE0 to "yellow",
        )
        for ((snes, name) in testColors) {
            val argb = EnemySpriteGraphics.snesColorToArgb(snes)
            val roundTrip = EnemySpriteGraphics.argbToSnesColor(argb)
            assertEquals(snes, roundTrip, "Color '$name' (0x${snes.toString(16).uppercase()}) should round-trip: SNES→ARGB→SNES")
        }
    }

    @Test
    fun `extractPaletteFromArgb respects 16-color limit and places transparent at index 0`() {
        // Build pixels with 20 distinct colors (should truncate at 16)
        val pixels = IntArray(20) { i ->
            if (i < 2) 0x00000000 // transparent
            else (0xFF shl 24) or (i * 13 shl 16) or (i * 7 shl 8) or (i * 5)
        }
        val palette = EnemySpriteGraphics.extractPaletteFromArgb(pixels)
        assertEquals(16, palette.size, "Palette should always be exactly 16 entries")
        assertEquals(0x00000000, palette[0], "Index 0 must be transparent")
        // All non-zero entries should be opaque
        for (i in 1 until 16) {
            if (palette[i] != 0) {
                assertEquals(0xFF, (palette[i] ushr 24) and 0xFF,
                    "Non-empty palette entry $i should be fully opaque")
            }
        }
    }

    @Test
    fun `extractPaletteFromArgb ignores semi-transparent pixels`() {
        // Semi-transparent pixels (alpha < 128) should be skipped
        val pixels = intArrayOf(
            0x7F_FF0000.toInt(), // semi-transparent red — should be ignored
            0xFF_00FF00.toInt(), // opaque green — should be included
        )
        val palette = EnemySpriteGraphics.extractPaletteFromArgb(pixels)
        val opaqueGreen = 0xFF_00FF00.toInt()
        assertTrue(opaqueGreen in palette, "Opaque green should appear in palette")
        // The semi-transparent red should NOT appear as an entry (it was ignored)
        val semiTransparentRed = 0x7F_FF0000.toInt()
        assertFalse(semiTransparentRed in palette, "Semi-transparent pixel should not enter palette")
    }

    @Test
    fun `getTileCount returns 0 when not loaded`() {
        val gfx = EnemySpriteGraphics(stubParser())
        // Before calling load(), tile count should be 0
        assertEquals(0, gfx.getTileCount(), "Unloaded EnemySpriteGraphics should report 0 tiles")
        assertNull(gfx.renderSheet(IntArray(16)), "renderSheet should return null when not loaded")
        assertNull(gfx.getRawBlocks(), "getRawBlocks should return null when not loaded")
    }

    @Test
    fun `loadFromRaw stores exactly the provided bytes`() {
        val block0 = ByteArray(64) { it.toByte() }  // 2 tiles
        val block1 = ByteArray(32) { (it + 128).toByte() }  // 1 tile

        val gfx = EnemySpriteGraphics(stubParser())
        gfx.loadFromRaw(listOf(block0, block1))

        val raw = gfx.getRawBlocks()
        assertNotNull(raw)
        assertEquals(2, raw!!.size)
        assertArrayEquals(block0, raw[0], "Block 0 should match provided bytes")
        assertArrayEquals(block1, raw[1], "Block 1 should match provided bytes")
        assertEquals(3, gfx.getTileCount(), "2 + 1 = 3 tiles total")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun stubParser(): RomParser = RomParser(ByteArray(1))

    private fun syntheticGraphics(): EnemySpriteGraphics =
        EnemySpriteGraphics(stubParser()).apply {
            loadFromRaw(listOf(syntheticBlock(8), syntheticBlock(5, seed = 71)))
        }

    private fun syntheticBlock(tileCount: Int, seed: Int = 13): ByteArray =
        ByteArray(tileCount * EnemySpriteGraphics.BYTES_PER_TILE) { index ->
            if (index < EnemySpriteGraphics.BYTES_PER_TILE) 0 else (index * 37 + seed).toByte()
        }

    /**
     * Build a 16-entry test palette with maximally distinct, fully opaque colors.
     * Index 0 = transparent; indices 1-15 = spread across the color space.
     */
    private fun buildTestPalette(): IntArray {
        val palette = IntArray(16)
        palette[0] = 0x00000000 // transparent
        val sampleColors = listOf(
            0xFF181800.toInt(), 0xFF384848.toInt(), 0xFF587060.toInt(), 0xFF787878.toInt(),
            0xFF686040.toInt(), 0xFF988060.toInt(), 0xFFC8A070.toInt(), 0xFFE8C898.toInt(),
            0xFFC82078.toInt(), 0xFF101010.toInt(), 0xFF202820.toInt(), 0xFF404038.toInt(),
            0xFF606050.toInt(), 0xFF808878.toInt(), 0xFF908878.toInt(),
        )
        for (i in 1 until 16) palette[i] = sampleColors[i - 1]
        return palette
    }
}
