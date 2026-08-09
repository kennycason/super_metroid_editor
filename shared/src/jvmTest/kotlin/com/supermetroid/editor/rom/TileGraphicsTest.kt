package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TileGraphicsTest {

    private var romParser: RomParser? = null
    private var gfx: TileGraphics? = null

    @BeforeAll
    fun setUp() {
        romParser = TestRomHelper.loadRomParser()
        romParser?.let { gfx = TileGraphics(it) }
    }

    // ── Tileset loading ──────────────────────────────────────────

    @Nested
    inner class TilesetLoading {
        @Test
        fun `loadTileset succeeds for tileset 0 (Crateria surface)`() {
            val g = gfx ?: return
            assertTrue(g.loadTileset(0))
            assertEquals(0, g.getCachedTilesetId())
        }

        @Test
        fun `loadTileset succeeds for all 29 tilesets`() {
            val g = gfx ?: return
            for (i in 0 until TileGraphics.NUM_TILESETS) {
                g.invalidateCache()
                assertTrue(g.loadTileset(i), "Tileset $i should load")
            }
        }

        @Test
        fun `loadTileset caches and reuses`() {
            val g = gfx ?: return
            g.invalidateCache()
            assertTrue(g.loadTileset(5))
            assertEquals(5, g.getCachedTilesetId())
            assertTrue(g.loadTileset(5))
        }

        @Test
        fun `invalidateCache forces reload`() {
            val g = gfx ?: return
            g.loadTileset(3)
            g.invalidateCache()
            assertEquals(-1, g.getCachedTilesetId())
        }
    }

    // ── Palette parsing ──────────────────────────────────────────

    @Nested
    inner class PaletteTests {
        @Test
        fun `getPalettes returns 8 sub-palettes of 16 colors`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val palettes = g.getPalettes()
            assertNotNull(palettes)
            assertEquals(8, palettes!!.size)
            for (pal in palettes) {
                assertEquals(16, pal.size)
            }
        }

        @Test
        fun `palette colors are opaque ARGB (alpha = 0xFF except index 0)`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val palettes = g.getPalettes()!!
            for (row in palettes.indices) {
                for (col in 1 until 16) {
                    val alpha = (palettes[row][col] ushr 24) and 0xFF
                    assertEquals(0xFF, alpha, "Pal[$row][$col] should be opaque")
                }
            }
        }

        @Test
        fun `getSnesBgr555 and setPaletteEntry round-trip`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val testBgr = 0x1234 // arbitrary BGR555 value
            g.setPaletteEntry(2, 5, testBgr)
            val readBack = g.getSnesBgr555(2, 5)
            assertEquals(testBgr, readBack)
        }

        @Test
        fun `getSnesBgr555 out of range returns -1`() {
            val g = gfx ?: return
            g.loadTileset(0)
            assertEquals(-1, g.getSnesBgr555(-1, 0))
            assertEquals(-1, g.getSnesBgr555(0, 16))
            assertEquals(-1, g.getSnesBgr555(8, 0))
        }

        @Test
        fun `getRawPaletteData returns 256 bytes`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val raw = g.getRawPaletteData()
            assertNotNull(raw)
            assertEquals(8 * 16 * 2, raw!!.size)
        }
    }

    // ── Pixel read/write ─────────────────────────────────────────

    @Nested
    inner class PixelReadWrite {
        @Test
        fun `readPixelIndex returns values 0-15`() {
            val g = gfx ?: return
            g.loadTileset(0)
            for (px in 0..7) for (py in 0..7) {
                val idx = g.readPixelIndex(0, px, py)
                assertTrue(idx in 0..15, "Pixel ($px,$py) of tile 0 should be 0-15, got $idx")
            }
        }

        @Test
        fun `writePixelIndex and readPixelIndex round-trip`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val origVal = g.readPixelIndex(1, 3, 5)
            val newVal = (origVal + 1) % 16
            g.writePixelIndex(1, 3, 5, newVal)
            assertEquals(newVal, g.readPixelIndex(1, 3, 5))
            g.writePixelIndex(1, 3, 5, origVal)
            assertEquals(origVal, g.readPixelIndex(1, 3, 5))
        }

        @Test
        fun `readPixelIndex out of range returns -1`() {
            val g = gfx ?: return
            g.loadTileset(0)
            assertEquals(-1, g.readPixelIndex(-1, 0, 0))
            assertEquals(-1, g.readPixelIndex(1024, 0, 0))
        }

        @Test
        fun `readTileIndices returns 64 values`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val indices = g.readTileIndices(100)
            assertNotNull(indices)
            assertEquals(64, indices!!.size)
            assertTrue(indices.all { it in 0..15 })
        }
    }

    // ── Metatile rendering ───────────────────────────────────────

    @Nested
    inner class MetatileRendering {
        @Test
        fun `renderMetatile returns 256 pixels`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val pixels = g.renderMetatile(0)
            assertNotNull(pixels)
            assertEquals(256, pixels!!.size)
        }

        @Test
        fun `renderMetatile out of range returns null`() {
            val g = gfx ?: return
            g.loadTileset(0)
            assertNull(g.renderMetatile(-1))
            assertNull(g.renderMetatile(1024))
        }

        @Test
        fun `renderMetatile returns different data for different metatiles`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val px0 = g.renderMetatile(0)!!
            val px100 = g.renderMetatile(100)!!
            assertFalse(px0.contentEquals(px100), "Metatile 0 and 100 should differ")
        }

        @Test
        fun `renderMetatileWords matches renderMetatile for current definition`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val words = g.getMetatileWords(100) ?: return

            assertArrayEquals(g.renderMetatile(100), g.renderMetatileWords(words))
        }

        @Test
        fun `getMetatileWords returns 4 sub-tile words`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val words = g.getMetatileWords(0)
            assertNotNull(words)
            assertEquals(4, words!!.size)
        }

        @Test
        fun `metatile word encode decode preserves tile palette priority and flips`() {
            val word = TileGraphics.encodeMetatileWord(
                tileNum = 0x123,
                palette = 5,
                priority = true,
                hFlip = true,
                vFlip = false,
            )
            val subtile = TileGraphics.decodeMetatileWord(word)

            assertEquals(0x123, subtile.tileNum)
            assertEquals(5, subtile.palette)
            assertTrue(subtile.priority)
            assertTrue(subtile.hFlip)
            assertFalse(subtile.vFlip)
        }

        @Test
        fun `metatile table source identifies CRE variable and invalid slots`() {
            val g = gfx ?: return
            g.loadTileset(0)

            assertEquals(TileGraphics.MetatileTableSource.CRE, g.metatileTableSource(0))
            assertEquals(TileGraphics.MetatileTableSource.VARIABLE, g.metatileTableSource(g.variableMetatileStart()))
            assertEquals(TileGraphics.MetatileTableSource.INVALID, g.metatileTableSource(TileGraphics.METATILE_COUNT))
            assertEquals("Shared CRE metatile table", g.metatileTableSourceLabel(0))
        }

        @Test
        fun `Crateria pipe metatiles are rendered art not placeholders`() {
            val g = gfx ?: return
            assertTrue(g.loadTileset(0))

            for (metatile in listOf(157, 158)) {
                assertFalse(g.isPlaceholderMetatile(metatile), "metatile $metatile should render as visible pipe art")
                val pixels = g.renderMetatile(metatile)
                assertNotNull(pixels)
                assertTrue(pixels!!.any { it != 0 }, "metatile $metatile should have visible pixels")
            }
        }

        @Test
        fun `Crateria air metatile is valid transparent art not a placeholder`() {
            val g = gfx ?: return
            assertTrue(g.loadTileset(0))

            assertFalse(g.isPlaceholderMetatile(TileGraphics.AIR_METATILE_INDEX))
            val pixels = g.renderMetatile(TileGraphics.AIR_METATILE_INDEX)
            assertNotNull(pixels)
            assertTrue(
                pixels!!.all { it == 0 },
                "metatile ${TileGraphics.AIR_METATILE_INDEX} should render transparent, not collision fallback",
            )
        }

        @Test
        fun `setMetatileWords updates raw variable tile table`() {
            val g = gfx ?: return
            g.invalidateCache()
            g.loadTileset(0)
            try {
                val metatileIndex = maxOf(g.variableMetatileStart(), 0)
                val edited = g.getMetatileWords(metatileIndex)!!.copyOf()
                edited[0] = TileGraphics.encodeMetatileWord(
                    tileNum = 0x22,
                    palette = 3,
                    priority = true,
                    hFlip = false,
                    vFlip = true,
                )

                assertTrue(g.setMetatileWords(metatileIndex, edited))
                assertArrayEquals(edited, g.getMetatileWords(metatileIndex))

                val rawTable = g.getRawVarTileTable()
                assertNotNull(rawTable)
                val lo = rawTable!![0].toInt() and 0xFF
                val hi = rawTable[1].toInt() and 0xFF
                assertEquals(edited[0], (hi shl 8) or lo)
            } finally {
                g.invalidateCache()
            }
        }

        @Test
        fun `applyCustomVarTileTable updates loaded metatile definitions`() {
            val g = gfx ?: return
            g.invalidateCache()
            g.loadTileset(0)
            try {
                val rawTable = g.getRawVarTileTable() ?: return
                val newWord = TileGraphics.encodeMetatileWord(
                    tileNum = 0x44,
                    palette = 2,
                    priority = false,
                    hFlip = true,
                    vFlip = true,
                )
                rawTable[0] = (newWord and 0xFF).toByte()
                rawTable[1] = ((newWord shr 8) and 0xFF).toByte()

                assertTrue(g.applyCustomVarTileTable(rawTable))
                val words = g.getMetatileWords(g.variableMetatileStart())
                assertNotNull(words)
                assertEquals(newWord, words!![0])
            } finally {
                g.invalidateCache()
            }
        }

        @Test
        fun `custom variable metatile table round trips through fresh loader`() {
            val rp = romParser ?: return
            val g = TileGraphics(rp)
            assertTrue(g.loadTileset(0))
            val metatileIndex = g.variableMetatileStart() + 3
            val edited = intArrayOf(
                TileGraphics.encodeMetatileWord(tileNum = 0x022, palette = 1, priority = false, hFlip = false, vFlip = false),
                TileGraphics.encodeMetatileWord(tileNum = 0x023, palette = 2, priority = true, hFlip = true, vFlip = false),
                TileGraphics.encodeMetatileWord(tileNum = 0x024, palette = 3, priority = false, hFlip = false, vFlip = true),
                TileGraphics.encodeMetatileWord(tileNum = 0x025, palette = 4, priority = true, hFlip = true, vFlip = true),
            )

            assertTrue(g.setMetatileWords(metatileIndex, edited))
            val rawTable = g.getRawVarTileTable()
            assertNotNull(rawTable)

            val fresh = TileGraphics(rp)
            assertTrue(fresh.loadTileset(0))
            assertTrue(fresh.applyCustomVarTileTable(rawTable!!))
            assertArrayEquals(edited, fresh.getMetatileWords(metatileIndex))
        }

        @Test
        fun `custom CRE metatile table round trips through fresh loader`() {
            val rp = romParser ?: return
            val g = TileGraphics(rp)
            assertTrue(g.loadTileset(0))
            val metatileIndex = 2
            assertTrue(g.isCreMetatileIndex(metatileIndex))
            val edited = intArrayOf(
                TileGraphics.encodeMetatileWord(tileNum = TileGraphics.CRE_TILE_START, palette = 0, priority = false, hFlip = false, vFlip = false),
                TileGraphics.encodeMetatileWord(tileNum = TileGraphics.CRE_TILE_START + 1, palette = 1, priority = true, hFlip = false, vFlip = true),
                TileGraphics.encodeMetatileWord(tileNum = TileGraphics.CRE_TILE_START + 2, palette = 2, priority = false, hFlip = true, vFlip = false),
                TileGraphics.encodeMetatileWord(tileNum = TileGraphics.CRE_TILE_START + 3, palette = 3, priority = true, hFlip = true, vFlip = true),
            )

            assertTrue(g.setMetatileWords(metatileIndex, edited))
            val rawTable = g.getRawCreTileTable()
            assertNotNull(rawTable)

            val fresh = TileGraphics(rp)
            assertTrue(fresh.loadTileset(0))
            assertTrue(fresh.applyCustomCreTileTable(rawTable!!))
            assertArrayEquals(edited, fresh.getMetatileWords(metatileIndex))
        }

        @Test
        fun `getMetatilePalettes returns non-empty set`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val pals = g.getMetatilePalettes(100)
            assertTrue(pals.isNotEmpty())
            assertTrue(pals.all { it in 0..7 })
        }
    }

    // ── Metatile pixel operations ────────────────────────────────

    @Nested
    inner class MetatilePixelOps {
        @Test
        fun `readMetatilePixel returns values 0-15`() {
            val g = gfx ?: return
            g.loadTileset(0)
            for (px in 0..15) for (py in 0..15) {
                val idx = g.readMetatilePixel(50, px, py)
                assertTrue(idx in 0..15, "metatile pixel ($px,$py)")
            }
        }

        @Test
        fun `writeMetatilePixel and readMetatilePixel round-trip`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val orig = g.readMetatilePixel(50, 4, 4)
            val newVal = (orig + 1) % 16
            g.writeMetatilePixel(50, 4, 4, newVal)
            assertEquals(newVal, g.readMetatilePixel(50, 4, 4))
            g.writeMetatilePixel(50, 4, 4, orig)
        }

        @Test
        fun `metatilePixelToTileCoords maps to valid tile`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val coords = g.metatilePixelToTileCoords(50, 10, 12)
            assertNotNull(coords)
            val (tileNum, tx, ty) = coords!!
            assertTrue(tileNum in 0 until TileGraphics.TOTAL_TILES)
            assertTrue(tx in 0..7)
            assertTrue(ty in 0..7)
        }

        @Test
        fun `getMetatilePixelPaletteRow returns 0-7`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val row = g.getMetatilePixelPaletteRow(50, 4, 4)
            assertTrue(row in 0..7)
        }
    }

    // ── Tileset grid rendering ───────────────────────────────────

    @Nested
    inner class GridRendering {
        @Test
        fun `renderTilesetGrid returns 512x512 grid`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val grid = g.renderTilesetGrid()
            assertNotNull(grid)
            assertEquals(512, grid!!.width)
            assertEquals(512, grid.height)
            assertEquals(32, grid.gridCols)
            assertEquals(32, grid.gridRows)
            assertEquals(512 * 512, grid.pixels.size)
        }

        @Test
        fun `renderTilesetGrid without load returns null`() {
            val parser = romParser ?: return
            val fresh = TileGraphics(parser)
            assertNull(fresh.renderTilesetGrid())
        }
    }

    // ── Tile sheet render/import round-trip ───────────────────────

    @Nested
    inner class TileSheetRoundTrip {
        @Test
        fun `renderTileSheet returns correct dimensions`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val result = g.renderTileSheet(0, 64, cols = 16)
            assertNotNull(result)
            val (pixels, w, h) = result!!
            assertEquals(128, w) // 16 * 8
            assertEquals(32, h) // 4 * 8
            assertEquals(w * h, pixels.size)
        }

        @Test
        fun `buildTilePaletteMap returns 1024 entries`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val map = g.buildTilePaletteMap()
            assertEquals(TileGraphics.TOTAL_TILES, map.size)
            assertTrue(map.all { it in 0..7 })
        }

        @Test
        fun `importTileSheet encodes pixels using exported tile palette row`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val paletteMap = g.buildTilePaletteMap()
            val palettes = g.getPalettes()!!
            val varTileCount = g.getVarTileCount()
            val originalVarGfx = g.getRawVarGfx()!!.copyOf()

            fun colorDistance(a: Int, b: Int): Int {
                val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
                val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
                val db = (a and 0xFF) - (b and 0xFF)
                return dr * dr + dg * dg + db * db
            }

            fun closestIndex(color: Int, palette: IntArray): Int =
                (1 until palette.size).minBy { colorDistance(color, palette[it]) }

            var chosenTile = -1
            var targetRow = -1
            var foreignColor = 0
            var expectedIndex = -1

            search@ for (tile in 0 until varTileCount) {
                val row = paletteMap[tile]
                for (otherRow in palettes.indices) {
                    if (otherRow == row) continue
                    for (colorIndex in 1 until 16) {
                        val color = palettes[otherRow][colorIndex]
                        val closestInTarget = closestIndex(color, palettes[row])
                        if (closestInTarget != colorIndex && colorDistance(color, palettes[row][closestInTarget]) > 0) {
                            chosenTile = tile
                            targetRow = row
                            foreignColor = color
                            expectedIndex = closestInTarget
                            break@search
                        }
                    }
                }
            }

            assertTrue(chosenTile >= 0, "Need a tile/color pair that distinguishes palette-row encoding")

            val width = 16 * 8
            val height = ((varTileCount + 15) / 16) * 8
            val pixels = IntArray(width * height)
            val tileX = (chosenTile % 16) * 8
            val tileY = (chosenTile / 16) * 8
            for (py in 0 until 8) for (px in 0 until 8) {
                pixels[(tileY + py) * width + tileX + px] = foreignColor
            }

            try {
                val imported = g.importTileSheet(pixels, width, 0, varTileCount)
                g.applyCustomVarGfx(imported)

                assertEquals(
                    expectedIndex,
                    g.readPixelIndex(chosenTile, 0, 0),
                    "Import must quantize against tile $chosenTile palette row $targetRow, not the source color's row"
                )
            } finally {
                g.applyCustomVarGfx(originalVarGfx)
            }
        }
    }

    // ── CRE / Variable split ─────────────────────────────────────

    @Nested
    inner class CreVariableSplit {
        @Test
        fun `getCreOffset returns 640 for normal tilesets`() {
            val g = gfx ?: return
            g.loadTileset(0)
            assertEquals(640, g.getCreOffset())
            assertEquals(640, g.getVarTileCount())
            assertEquals(384, g.getCreTileCount())
        }

        @Test
        fun `getCreOffset returns 1024 for Kraid tileset (no CRE overlay)`() {
            val g = gfx ?: return
            g.loadTileset(TileGraphics.KRAID_TILESET)
            // SMILE places CRE at 0x8000 for Kraid, effectively no overlay
            assertEquals(1024, g.getCreOffset())
            assertEquals(1024, g.getVarTileCount())
            assertEquals(0, g.getCreTileCount())
        }

        @Test
        fun `getCreOffset returns 640 for Ceres tileset (CRE overlays at 0x5000)`() {
            val g = gfx ?: return
            g.loadTileset(17) // Ceres tileset — CRE always overlays per SMILE behavior
            assertEquals(640, g.getCreOffset())
            assertEquals(640, g.getVarTileCount())
            assertEquals(384, g.getCreTileCount())
        }

        @Test
        fun `tile source classification respects normal and Kraid CRE split`() {
            val g = gfx ?: return
            g.loadTileset(0)
            assertEquals(TileGraphics.TileSource.AREA, g.tileSource(0))
            assertEquals(TileGraphics.TileSource.AREA, g.tileSource(g.getCreOffset() - 1))
            assertEquals(TileGraphics.TileSource.CRE, g.tileSource(g.getCreOffset()))
            assertEquals(TileGraphics.TileSource.INVALID, g.tileSource(TileGraphics.TOTAL_TILES))

            g.invalidateCache()
            g.loadTileset(TileGraphics.KRAID_TILESET)
            assertEquals(TileGraphics.TileSource.AREA, g.tileSource(TileGraphics.CRE_TILE_START))
            assertEquals(TileGraphics.TileSource.AREA, g.tileSource(TileGraphics.TOTAL_TILES - 1))
            assertEquals("Area", g.tileSourceLabel(TileGraphics.CRE_TILE_START))
        }

        @Test
        fun `getRawVarGfx and getRawCreGfx return valid data`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val varGfx = g.getRawVarGfx()
            val creGfx = g.getRawCreGfx()
            assertNotNull(varGfx)
            assertNotNull(creGfx)
            assertTrue(varGfx!!.isNotEmpty())
            assertTrue(creGfx!!.isNotEmpty())
        }

        @Test
        fun `applyCustomVarGfx modifies variable tiles`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val orig = g.getRawVarGfx()!!.copyOf()
            val custom = orig.copyOf()
            custom[0] = (custom[0].toInt() xor 0xFF).toByte()
            g.applyCustomVarGfx(custom)
            val after = g.getRawVarGfx()!!
            assertEquals(custom[0], after[0])
            g.applyCustomVarGfx(orig)
        }
    }

    // ── Palette image rendering ──────────────────────────────────

    @Nested
    inner class PaletteImage {
        @Test
        fun `renderPaletteImage returns correct dimensions`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val result = g.renderPaletteImage(cellSize = 8)
            assertNotNull(result)
            val (pixels, w, h) = result!!
            assertEquals(128, w)
            assertEquals(64, h)
            assertEquals(w * h, pixels.size)
        }
    }
}
