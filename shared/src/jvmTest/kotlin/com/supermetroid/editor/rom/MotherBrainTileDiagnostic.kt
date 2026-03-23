package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Pixel-level Mother Brain P1 tile diagnostic.
 * Renders each OAM entry individually to see exactly which tile is being used.
 */
class MotherBrainTileDiagnostic {

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
    fun `render MB1 pose entry by entry with tile sheet comparison`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val smap = EnemySpritemap(rp)
        val speciesId = 0xEC3F

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return

        val outDir = File("/tmp/mb1_tile_debug")
        outDir.mkdirs()

        // Render tile sheet at 16-wide
        val gfx = EnemySpriteGraphics(rp)
        gfx.loadFromRaw(listOf(tileData))
        val sheet = gfx.renderSheet(palette, 16)!!
        val (sheetPx, sheetW, sheetH) = sheet
        val sheetBi = BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_ARGB)
        sheetBi.setRGB(0, 0, sheetW, sheetH, sheetPx, 0, sheetW)
        ImageIO.write(sheetBi, "png", File(outDir, "tilesheet.png"))

        val poses = scanner.scanPoses(speciesId, minEntries = 20)
        if (poses.isEmpty()) { println("No poses!"); return }
        val pose = poses.first()

        println("MB1 best pose: ${pose.name}, ${pose.entryCount} entries")
        println("Tile data: ${tileData.size} bytes = ${tileData.size / 32} tiles")

        // Render each entry INDIVIDUALLY as a separate image
        // Also build a composite showing entries numbered
        for ((i, entry) in pose.spritemap.entries.withIndex()) {
            val localTile = entry.tileNum and 0xFF
            val nt = (entry.tileNum shr 8) and 1
            val size = if (entry.is16x16) 16 else 8
            val tsRow = localTile / 16
            val tsCol = localTile % 16

            // Render this single OAM entry
            val singleEntry = EnemySpritemap.OamEntry(
                0, 0, localTile, entry.palRow,
                entry.hFlip, entry.vFlip, entry.is16x16
            )
            val singleSmap = EnemySpritemap.Spritemap(listOf(singleEntry), 0)
            val rendered = smap.renderSpritemap(singleSmap, tileData, palette)

            if (rendered != null) {
                val bi = BufferedImage(rendered.width, rendered.height, BufferedImage.TYPE_INT_ARGB)
                bi.setRGB(0, 0, rendered.width, rendered.height, rendered.pixels, 0, rendered.width)
                ImageIO.write(bi, "png", File(outDir, String.format("entry_%02d_tile%03d.png", i, localTile)))
            }

            println("  [%2d] tile=%3d NT=%d %dx%d sheet[%d,%d] x=%4d y=%4d hf=%b vf=%b pal=%d".format(
                i, localTile, nt, size, size, tsRow, tsCol,
                entry.xOffset, entry.yOffset, entry.hFlip, entry.vFlip, entry.palRow))
        }

        // Render the full assembly
        val fullRendered = scanner.renderPose(pose, tileData, palette)
        if (fullRendered != null) {
            val bi = BufferedImage(fullRendered.width, fullRendered.height, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, fullRendered.width, fullRendered.height, fullRendered.pixels, 0, fullRendered.width)
            ImageIO.write(bi, "png", File(outDir, "full_assembly.png"))
            println("\nFull assembly: ${fullRendered.width}x${fullRendered.height}")
        }

        // Also render Mini Kraid for comparison (known working)
        val mkPalette = EnemySpriteGraphics.readEnemyPalette(rp, 0xE0FF) ?: return
        val mkTileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xE0FF) ?: return
        val mkSmap = smap.findDefaultSpritemap(0xE0FF)
        if (mkSmap != null) {
            println("\nMini Kraid comparison (working):")
            for ((i, entry) in mkSmap.entries.withIndex()) {
                val localTile = entry.tileNum and 0xFF
                val nt = (entry.tileNum shr 8) and 1
                println("  [%2d] tile=%3d NT=%d %dx%d x=%4d y=%4d".format(
                    i, localTile, nt,
                    if (entry.is16x16) 16 else 8, if (entry.is16x16) 16 else 8,
                    entry.xOffset, entry.yOffset))
            }
        }
    }
}
