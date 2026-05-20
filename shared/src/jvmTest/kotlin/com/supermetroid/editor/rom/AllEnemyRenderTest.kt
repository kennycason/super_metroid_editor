package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Test which enemies can be auto-rendered using the OAM spritemap system.
 * This helps identify which enemies to add to EDITOR_ENEMIES.
 */
class AllEnemyRenderTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `scan all enemies for renderable sprites`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)

        // Get all enemy IDs from the reference PNGs directory
        val pngDir = File("../desktopApp/src/jvmMain/resources/enemies")
        val pngIds = if (pngDir.exists()) {
            pngDir.listFiles()?.filter { it.extension == "png" && it.nameWithoutExtension != "unknown" }
                ?.mapNotNull { it.nameWithoutExtension.toIntOrNull(16) }
                ?.sorted() ?: emptyList()
        } else emptyList()

        println("Found ${pngIds.size} enemy PNGs")
        println()

        val renderable = mutableListOf<Int>()
        val failed = mutableListOf<Pair<Int, String>>()

        for (speciesId in pngIds) {
            val name = RomParser.enemyName(speciesId)
            val headerPc = rp.snesToPc(0xA00000 or speciesId)
            if (headerPc < 0 || headerPc + 0x3A > rom.size) {
                failed.add(speciesId to "Invalid header")
                continue
            }

            // Check if it has valid GRAPHADR (raw tile data)
            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId)
            if (tileData == null || tileData.isEmpty()) {
                failed.add(speciesId to "No tile data")
                continue
            }

            // Check if we can find a spritemap
            val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId)
            if (palette == null) {
                failed.add(speciesId to "No palette")
                continue
            }

            val defaultSmap = smap.findDefaultSpritemap(speciesId)
            if (defaultSmap == null) {
                // Can still show tile sheet even without spritemap
                renderable.add(speciesId)
                println("  TILE_ONLY \$${speciesId.toString(16).uppercase()} $name (${tileData.size / 32} tiles, no spritemap)")
                continue
            }

            val assembled = smap.renderSpritemap(defaultSmap, tileData, palette)
            if (assembled == null) {
                renderable.add(speciesId)
                println("  TILE_ONLY \$${speciesId.toString(16).uppercase()} $name (render failed)")
                continue
            }

            val nonTrans = assembled.pixels.count { (it ushr 24) and 0xFF > 0 }
            val fillRate = if (assembled.width * assembled.height > 0)
                nonTrans * 100 / (assembled.width * assembled.height) else 0

            renderable.add(speciesId)
            println("  OK \$${speciesId.toString(16).uppercase()} $name ${assembled.width}x${assembled.height} fill=$fillRate% entries=${defaultSmap.entries.size}")
        }

        println("\n=== Summary ===")
        println("Renderable: ${renderable.size}")
        println("Failed: ${failed.size}")
        for ((id, reason) in failed) {
            println("  FAIL \$${id.toString(16).uppercase()} ${RomParser.enemyName(id)}: $reason")
        }

        println("\n=== Kotlin EDITOR_ENEMIES entries ===")
        // Group by name pattern for categories
        for (id in renderable) {
            val name = RomParser.enemyName(id)
            val hex = "0x${id.toString(16).uppercase()}"
            println("    EnemySpriteEntry($hex, \"$name\"),")
        }
    }
}
