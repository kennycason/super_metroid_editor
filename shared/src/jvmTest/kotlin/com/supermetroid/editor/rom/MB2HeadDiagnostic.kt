package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class MB2HeadDiagnostic {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        return null
    }

    @Test
    fun `render Brain Idle 2 entry by entry`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val outDir = File("/tmp/mb2_head_debug")
        outDir.mkdirs()

        // MB P1 tile data and palette
        val speciesId = 0xEC3F
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return
        val tileCount = tileData.size / 32

        println("MB P1: $tileCount tiles (${tileData.size} bytes)")

        // Also load MB P2 tile data for comparison
        val p2SpeciesId = 0xEC7F
        val p2Palette = EnemySpriteGraphics.readEnemyPalette(rp, p2SpeciesId)
        val p2TileData = EnemySpriteGraphics.loadEnemyTileData(rp, p2SpeciesId)

        if (p2TileData != null) {
            val p2TileCount = p2TileData.size / 32
            println("MB P2: $p2TileCount tiles (${p2TileData.size} bytes)")

            // Compare: are P1 and P2 tile data the same?
            if (tileData.size == p2TileData.size) {
                var diffs = 0
                for (i in tileData.indices) {
                    if (tileData[i] != p2TileData[i]) diffs++
                }
                println("P1 vs P2 tile data: $diffs byte differences out of ${tileData.size}")
            } else {
                println("P1 and P2 have different tile data sizes!")
            }
        }

        // Parse Brain Idle 2 spritemap directly at $A9:A717
        val brainIdle2Snes = 0xA90000 or 0xA717
        val parsed = smap.parseSpritemap(brainIdle2Snes)
        if (parsed == null) { println("Failed to parse Brain Idle 2"); return }

        println("\nBrain Idle 2: ${parsed.entries.size} entries")

        // Render each entry individually with P1 tile data
        for ((i, entry) in parsed.entries.withIndex()) {
            val localTile = entry.tileNum and 0xFF
            val nt = (entry.tileNum shr 8) and 1
            val size = if (entry.is16x16) "16x16" else "8x8"

            // Check sub-tile range for 16x16
            var oobNote = ""
            if (entry.is16x16) {
                val subs = listOf(localTile, localTile + 1, localTile + 16, localTile + 17)
                val oob = subs.filter { it >= tileCount }
                if (oob.isNotEmpty()) oobNote = " OOB:${oob}"
            }

            println("  [$i] tile=$localTile NT=$nt $size x=${entry.xOffset} y=${entry.yOffset} " +
                "hf=${entry.hFlip} vf=${entry.vFlip} pal=${entry.palRow}$oobNote")

            // Render this entry alone
            val singleEntry = EnemySpritemap.OamEntry(
                0, 0, localTile, entry.palRow,
                entry.hFlip, entry.vFlip, entry.is16x16
            )
            val singleSmap = EnemySpritemap.Spritemap(listOf(singleEntry), 0)
            val rendered = smap.renderSpritemap(singleSmap, tileData, palette)
            if (rendered != null) {
                val bi = BufferedImage(rendered.width, rendered.height, BufferedImage.TYPE_INT_ARGB)
                bi.setRGB(0, 0, rendered.width, rendered.height, rendered.pixels, 0, rendered.width)
                ImageIO.write(bi, "png", File(outDir, "p1_entry${i}_tile${localTile}.png"))
            }

            // Also render with P2 tile data if available
            if (p2TileData != null && p2Palette != null) {
                val renderedP2 = smap.renderSpritemap(singleSmap, p2TileData, p2Palette)
                if (renderedP2 != null) {
                    val bi = BufferedImage(renderedP2.width, renderedP2.height, BufferedImage.TYPE_INT_ARGB)
                    bi.setRGB(0, 0, renderedP2.width, renderedP2.height, renderedP2.pixels, 0, renderedP2.width)
                    ImageIO.write(bi, "png", File(outDir, "p2_entry${i}_tile${localTile}.png"))
                }
            }
        }

        // Render full assembly with P1 tiles
        val fullP1 = smap.renderSpritemap(parsed, tileData, palette)
        if (fullP1 != null) {
            val bi = BufferedImage(fullP1.width, fullP1.height, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, fullP1.width, fullP1.height, fullP1.pixels, 0, fullP1.width)
            ImageIO.write(bi, "png", File(outDir, "full_p1_tiles.png"))
            println("\nFull with P1 tiles: ${fullP1.width}x${fullP1.height}")
        }

        // Render full assembly with P2 tiles
        if (p2TileData != null && p2Palette != null) {
            val fullP2 = smap.renderSpritemap(parsed, p2TileData, p2Palette)
            if (fullP2 != null) {
                val bi = BufferedImage(fullP2.width, fullP2.height, BufferedImage.TYPE_INT_ARGB)
                bi.setRGB(0, 0, fullP2.width, fullP2.height, fullP2.pixels, 0, fullP2.width)
                ImageIO.write(bi, "png", File(outDir, "full_p2_tiles.png"))
                println("Full with P2 tiles: ${fullP2.width}x${fullP2.height}")
            }
        }
    }
}
