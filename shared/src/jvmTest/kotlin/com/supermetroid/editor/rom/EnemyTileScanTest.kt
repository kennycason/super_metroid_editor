package com.supermetroid.editor.rom

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Verifies enemy sprite data loading, palette reading, and spritemap
 * assembly against the vanilla Super Metroid ROM.
 */
class EnemyTileScanTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private val ALL_ENEMIES = listOf(
        0xDCFF to "Zoomer",
        0xDC7F to "Zeela",
        0xD93F to "Sidehopper",
        0xD7FF to "Skree",
        0xCFFF to "Cacatac",
    )

    @Nested
    inner class GraphicsAddress {

        @Test
        fun `GRAPHADR decompresses to at least tileDataSize bytes for all editor enemies`() {
            val parser = loadTestRom() ?: return

            for ((speciesId, name) in ALL_ENEMIES) {
                val stats = EnemySpriteGraphics.readSpeciesStats(parser, speciesId)
                assertNotNull(stats, "$name: stats should not be null")
                val tileDataSize = stats!!.first
                assertTrue(tileDataSize > 0, "$name: tileDataSize should be positive")

                val block = EnemySpriteGraphics.readGraphicsBlock(parser, speciesId)
                assertNotNull(block, "$name: GRAPHADR block should not be null")

                val data = parser.decompressLZ5AtPc(block!!.pcAddress)
                assertTrue(data.size >= tileDataSize,
                    "$name: GRAPHADR decompresses to ${data.size} bytes, need at least $tileDataSize")

                val truncated = data.copyOf(tileDataSize)
                val nonZero = truncated.count { it.toInt() != 0 }
                assertTrue(nonZero > tileDataSize / 10,
                    "$name: tile data should have significant non-zero content ($nonZero/$tileDataSize)")

                println("$name: tileDataSize=$tileDataSize, decompressed=${data.size}, nonZero=$nonZero")
            }
        }

        @Test
        fun `tileDataSize has bit 15 masked off`() {
            val parser = loadTestRom() ?: return
            val rom = parser.getRomData()

            for ((speciesId, name) in ALL_ENEMIES) {
                val stats = EnemySpriteGraphics.readSpeciesStats(parser, speciesId)!!
                val tileDataSize = stats.first
                assertTrue(tileDataSize < 0x8000,
                    "$name: tileDataSize ($tileDataSize) should have bit 15 masked (< 32768)")
                assertTrue(tileDataSize % 32 == 0,
                    "$name: tileDataSize ($tileDataSize) should be a multiple of 32 bytes (tile size)")

                val pc = parser.snesToPc(0xA00000 or speciesId)
                val rawWord = (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
                assertEquals(rawWord and 0x7FFF, tileDataSize,
                    "$name: readSpeciesStats should return raw & 0x7FFF")
            }
        }
    }

    @Nested
    inner class PaletteLoading {

        @Test
        fun `palette reads from row 0 matching game ProcessEnemyTilesets`() {
            val parser = loadTestRom() ?: return
            val rom = parser.getRomData()

            for ((speciesId, name) in ALL_ENEMIES) {
                val pal = EnemySpriteGraphics.readEnemyPalette(parser, speciesId)
                assertNotNull(pal, "$name: palette should not be null")
                assertEquals(16, pal!!.size, "$name: palette should have 16 entries")
                assertEquals(0x00000000, pal[0], "$name: palette[0] should be transparent")

                val headerPc = parser.snesToPc(0xA00000 or speciesId)
                val palPtr = (rom[headerPc + 2].toInt() and 0xFF) or
                    ((rom[headerPc + 3].toInt() and 0xFF) shl 8)
                val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
                val palPc = parser.snesToPc((aiBank shl 16) or palPtr)

                // Verify palette[1] matches the first color from row 0
                val bgr = (rom[palPc + 2].toInt() and 0xFF) or
                    ((rom[palPc + 3].toInt() and 0xFF) shl 8)
                val expected = EnemySpriteGraphics.snesColorToArgb(bgr)
                assertEquals(expected, pal[1],
                    "$name: palette[1] should match BGR555 at palPtr row 0")

                val uniqueColors = pal.drop(1).toSet().size
                assertTrue(uniqueColors >= 3,
                    "$name: palette should have at least 3 unique non-transparent colors (has $uniqueColors)")
                println("$name: palette OK, $uniqueColors unique colors, bank=\$${aiBank.toString(16).uppercase()}, palPtr=\$${palPtr.toString(16).uppercase()}")
            }
        }
    }

    @Nested
    inner class SpritemapAssembly {

        @Test
        fun `findDefaultSpritemap succeeds for all editor enemies including Sidehopper`() {
            val rp = loadTestRom() ?: return
            val smap = EnemySpritemap(rp)

            for ((speciesId, name) in ALL_ENEMIES) {
                val result = smap.findDefaultSpritemap(speciesId)
                assertNotNull(result, "$name (0x${speciesId.toString(16)}): findDefaultSpritemap should succeed")
                assertTrue(result!!.entries.isNotEmpty(),
                    "$name: spritemap should have OAM entries")
                println("$name: ${result.entries.size} OAM entries at \$${result.snesAddress.toString(16).uppercase()}")
            }
        }

        @Test
        fun `assembled sprites have reasonable dimensions and fill rate`() {
            val rp = loadTestRom() ?: return
            val smap = EnemySpritemap(rp)

            for ((speciesId, name) in ALL_ENEMIES) {
                val defaultSmap = smap.findDefaultSpritemap(speciesId) ?: continue
                val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: continue
                val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: continue

                val assembled = smap.renderSpritemap(defaultSmap, tileData, palette)
                assertNotNull(assembled, "$name: renderSpritemap should succeed")

                assertTrue(assembled!!.width in 8..128,
                    "$name: width ${assembled.width} should be in reasonable range")
                assertTrue(assembled.height in 8..128,
                    "$name: height ${assembled.height} should be in reasonable range")

                val nonTransparent = assembled.pixels.count { (it ushr 24) and 0xFF > 0 }
                val fillRate = nonTransparent.toFloat() / (assembled.width * assembled.height)
                assertTrue(fillRate > 0.1f,
                    "$name: fill rate ${(fillRate * 100).toInt()}% should be > 10%")
                println("$name: ${assembled.width}x${assembled.height}, ${assembled.spritemap.entries.size} OAM, fill=${(fillRate * 100).toInt()}%")
            }
        }

        @Test
        fun `Sidehopper spritemap found via cross-function LDA abs,x tracing`() {
            val rp = loadTestRom() ?: return
            val smap = EnemySpritemap(rp)

            val result = smap.findDefaultSpritemap(0xD93F)
            assertNotNull(result, "Sidehopper spritemap should be found via cross-function trace")
            assertTrue(result!!.entries.size >= 3,
                "Sidehopper should have at least 3 OAM entries (has ${result.entries.size})")

            val has16x16 = result.entries.any { it.is16x16 }
            assertTrue(has16x16, "Sidehopper should have 16x16 OAM entries")
        }
    }
}
