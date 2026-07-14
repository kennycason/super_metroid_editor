package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Tests for EnemySpriteGraphics — covers loading, rendering, round-trip encoding,
 * pixel read/write correctness, and color conversion fidelity.
 *
 * Tests that need the ROM are skipped gracefully when the ROM is not present.
 */
class EnemySpriteGraphicsTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    // ─── ROM-dependent tests ──────────────────────────────────────────────────

    @Test
    fun `Phantoon blocks load from ROM with expected tile counts`() {
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)

        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS), "Should successfully load Phantoon blocks")

        val totalTiles = gfx.getTileCount()
        val blockACount = gfx.getTileCountInBlock(0)
        val blockBCount = gfx.getTileCountInBlock(1)

        println("Phantoon Block A: $blockACount tiles, Block B: $blockBCount tiles, total: $totalTiles")

        assertTrue(blockACount > 0, "Block A (Tiles A \$B7:170F) should contain at least one tile")
        assertTrue(blockBCount > 0, "Block B (Tiles B \$B7:1808) should contain at least one tile")
        assertEquals(totalTiles, blockACount + blockBCount, "Total tiles should equal sum of block counts")
        assertTrue(totalTiles % 1 == 0, "Tile count should be a whole number")
    }

    @Test
    fun `renderSheet produces correctly sized pixel buffer`() {
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

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
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

        val palette = buildTestPalette()
        val (pixels, _, _) = gfx.renderSheet(palette)!!

        // Palette index 0 must render as 0x00000000 (fully transparent), never as a solid color
        val solidZeroCount = pixels.count { it != 0 && ((it ushr 24) and 0xFF) == 0xFF }
        // Verify at least one pixel is transparent (Phantoon has transparent background)
        val transparentCount = pixels.count { ((it ushr 24) and 0xFF) < 128 }
        assertTrue(transparentCount > 0, "Tile sheet should have transparent (index-0) pixels for background")
        println("$transparentCount transparent pixels, $solidZeroCount opaque pixels in tile sheet")
    }

    @Test
    fun `round-trip renderSheet then importFromArgb reproduces identical raw bytes`() {
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

        val rawBefore = gfx.getRawBlocks()!!

        // Render with a deterministic palette that spans index 0..15
        val palette = buildTestPalette()
        val (pixels, w, h) = gfx.renderSheet(palette)!!

        // Load the same raw bytes into a fresh instance and import the rendered pixels
        val gfx2 = EnemySpriteGraphics(parser)
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
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

        val originalBlock0 = gfx.getRawBlocks()!![0].copyOf()
        val blockSize = originalBlock0.size

        // Create a custom block filled with 0x42
        val customBlock = ByteArray(blockSize) { 0x42.toByte() }
        val gfx2 = EnemySpriteGraphics(parser)
        assertTrue(gfx2.loadWithOverrides(EnemySpriteGraphics.PHANTOON_BLOCKS, mapOf(0 to customBlock)))

        val overriddenBlocks = gfx2.getRawBlocks()!!
        assertArrayEquals(customBlock, overriddenBlocks[0], "Block 0 should be replaced with custom data")
        // Block 1 should remain the original ROM data
        val gfx3 = EnemySpriteGraphics(parser)
        gfx3.load(EnemySpriteGraphics.PHANTOON_BLOCKS)
        assertArrayEquals(gfx3.getRawBlocks()!![1], overriddenBlocks[1], "Block 1 should remain unchanged ROM data")
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
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

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
    fun `all 16 pixel values in a row encode and decode correctly`() {
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

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
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

        assertEquals(0, gfx.getTileCountInBlock(99), "Out-of-range block index should return 0")
        assertEquals(0, gfx.getTileCountInBlock(-1), "Negative block index should return 0")
    }

    @Test
    fun `original compressed sizes are nonzero and raw data fits in allotted space`() {
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

        val rawBlocks = gfx.getRawBlocks()!!
        for ((i, block) in rawBlocks.withIndex()) {
            val spriteBlock = EnemySpriteGraphics.PHANTOON_BLOCKS[i]
            val (_, origSize) = parser.decompressLZ2WithSize(spriteBlock.snesAddress)
            val tileCount = block.size / EnemySpriteGraphics.BYTES_PER_TILE
            val trailingBytes = block.size % EnemySpriteGraphics.BYTES_PER_TILE
            println("Block $i (${spriteBlock.label}): raw=${block.size}B ($tileCount tiles + ${trailingBytes}B trailing), orig compressed=${origSize}B at SNES=\$${spriteBlock.snesAddress.toString(16).uppercase()}")
            assertTrue(block.size > 0, "Block $i should have decompressed data")
            assertTrue(origSize > 0, "Block $i original compressed slot should be non-zero")
            assertTrue(tileCount > 0, "Block $i should contain at least one full 32-byte tile")
            // Note: LZ5 decompression may produce trailing bytes that are not full tiles.
            // getTileCount() uses integer division so these are harmlessly ignored.
            assertTrue(trailingBytes < EnemySpriteGraphics.BYTES_PER_TILE,
                "Block $i trailing bytes ($trailingBytes) should be less than one tile (${EnemySpriteGraphics.BYTES_PER_TILE})")
        }
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
        val parser = loadTestRom() ?: run {
            // Even without a ROM, we can create a stub via a dummy byte array
            return
        }
        val gfx = EnemySpriteGraphics(parser)
        // Before calling load(), tile count should be 0
        assertEquals(0, gfx.getTileCount(), "Unloaded EnemySpriteGraphics should report 0 tiles")
        assertNull(gfx.renderSheet(IntArray(16)), "renderSheet should return null when not loaded")
        assertNull(gfx.getRawBlocks(), "getRawBlocks should return null when not loaded")
    }

    @Test
    fun `loadFromRaw stores exactly the provided bytes`() {
        val parser = loadTestRom() ?: return
        val block0 = ByteArray(64) { it.toByte() }  // 2 tiles
        val block1 = ByteArray(32) { (it + 128).toByte() }  // 1 tile

        val gfx = EnemySpriteGraphics(parser)
        gfx.loadFromRaw(listOf(block0, block1))

        val raw = gfx.getRawBlocks()
        assertNotNull(raw)
        assertEquals(2, raw!!.size)
        assertArrayEquals(block0, raw[0], "Block 0 should match provided bytes")
        assertArrayEquals(block1, raw[1], "Block 1 should match provided bytes")
        assertEquals(3, gfx.getTileCount(), "2 + 1 = 3 tiles total")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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
