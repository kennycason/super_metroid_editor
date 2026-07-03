package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Diagnostic test to investigate fragmented OAM sprite rendering.
 * Dumps tile numbers, VRAM offsets, and validates the base tile offset hypothesis.
 */
class SpriteRenderDiagnosticTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `dump spritemap tile numbers for all editor enemies`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val enemies = mapOf(
            0xDCFF to "Zoomer",
            0xDC7F to "Zeela",
            0xD93F to "Sidehopper",
            0xD7FF to "Tripper",
            0xCFFF to "Cacatac",
        )

        for ((speciesId, name) in enemies) {
            val defaultSmap = smap.findDefaultSpritemap(speciesId) ?: continue
            val stats = EnemySpriteGraphics.readSpeciesStats(rp, speciesId)
            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId)

            val tileCount = (tileData?.size ?: 0) / 32
            val localTiles = defaultSmap.entries.map { it.tileNum and 0xFF }
            val minLocal = localTiles.minOrNull() ?: 0
            val maxLocal = localTiles.maxOrNull() ?: 0

            println("=== $name (species \$${speciesId.toString(16).uppercase()}) ===")
            println("  tileDataSize: ${stats?.first} bytes (${tileCount} tiles)")
            println("  Raw tile numbers: ${defaultSmap.entries.map { "0x${it.tileNum.toString(16)}" }}")
            println("  Local tiles (& 0xFF): $localTiles")
            println("  Min local: $minLocal, Max local: $maxLocal")
            println("  Entries:")
            for ((i, entry) in defaultSmap.entries.withIndex()) {
                val lt = entry.tileNum and 0xFF
                println("    [$i] tile=0x${entry.tileNum.toString(16)} local=$lt " +
                    "x=${entry.xOffset} y=${entry.yOffset} " +
                    "${if (entry.is16x16) "16x16" else "8x8"} " +
                    "hFlip=${entry.hFlip} vFlip=${entry.vFlip}")
                if (entry.is16x16) {
                    // Check if the 16x16 sub-tiles would be in bounds
                    val subTiles = intArrayOf(lt, lt + 1, lt + 16, lt + 17)
                    val corrected = intArrayOf(lt - minLocal, lt - minLocal + 1, lt - minLocal + 16, lt - minLocal + 17)
                    val inBounds = subTiles.map { it * 32 + 32 <= (tileData?.size ?: 0) }
                    val correctedInBounds = corrected.map { it * 32 + 32 <= (tileData?.size ?: 0) }
                    println("      Sub-tiles (current): ${subTiles.toList()} inBounds=$inBounds")
                    println("      Sub-tiles (corrected with -$minLocal): ${corrected.toList()} inBounds=$correctedInBounds")
                }
            }
            println()
        }
    }

    @Test
    fun `verify base tile offset fixes rendering for all enemies`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val enemies = mapOf(
            0xDCFF to "Zoomer",
            0xDC7F to "Zeela",
            0xD93F to "Sidehopper",
            0xD7FF to "Tripper",
            0xCFFF to "Cacatac",
        )

        for ((speciesId, name) in enemies) {
            val defaultSmap = smap.findDefaultSpritemap(speciesId) ?: continue
            val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: continue
            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: continue

            // Find the minimum local tile index
            val minLocalTile = defaultSmap.entries.minOf { it.tileNum and 0xFF }
            println("$name: minLocalTile=$minLocalTile")

            // With correction, ALL sub-tiles for 16x16 entries should be in bounds
            var allInBounds = true
            for (entry in defaultSmap.entries) {
                val localTile = (entry.tileNum and 0xFF) - minLocalTile
                if (entry.is16x16) {
                    val subTiles = intArrayOf(localTile, localTile + 1, localTile + 16, localTile + 17)
                    for (st in subTiles) {
                        if (st * 32 + 32 > tileData.size) {
                            println("  OUT OF BOUNDS: tile $st for entry tile=0x${entry.tileNum.toString(16)}")
                            allInBounds = false
                        }
                    }
                } else {
                    if (localTile * 32 + 32 > tileData.size) {
                        println("  OUT OF BOUNDS: tile $localTile for entry tile=0x${entry.tileNum.toString(16)}")
                        allInBounds = false
                    }
                }
            }
            assertTrue(allInBounds, "$name: all tile indices should be in bounds after base offset correction")
        }
    }
}
