package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Exports rendered sprites as PNGs for visual inspection.
 * Also compares against reference PNGs where available.
 */
class SpriteRenderExportTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `export assembled sprites as PNGs for visual inspection`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val enemies = mapOf(
            0xDCFF to "Zoomer",
            0xDC7F to "Zeela",
            0xD93F to "Sidehopper",
            0xD7FF to "Skree",
            0xCFFF to "Cacatac",
        )

        val outputDir = File("build/test-sprites")
        outputDir.mkdirs()

        for ((speciesId, name) in enemies) {
            val defaultSmap = smap.findDefaultSpritemap(speciesId) ?: continue
            val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: continue
            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: continue

            val assembled = smap.renderSpritemap(defaultSmap, tileData, palette) ?: continue

            // Export assembled sprite
            val img = BufferedImage(assembled.width, assembled.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, assembled.width, assembled.height, assembled.pixels, 0, assembled.width)
            val hexId = speciesId.toString(16).uppercase()
            val outFile = File(outputDir, "${hexId}_${name}_assembled.png")
            ImageIO.write(img, "PNG", outFile)
            println("Exported: ${outFile.absolutePath} (${assembled.width}x${assembled.height})")

            // Also export the tile sheet in VRAM layout (16 cols) for comparison
            val gfx = EnemySpriteGraphics(rp)
            gfx.loadFromRaw(listOf(tileData))
            val sheet16 = gfx.renderSheet(palette, 16)
            if (sheet16 != null) {
                val (pixels, w, h) = sheet16
                val sheetImg = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                sheetImg.setRGB(0, 0, w, h, pixels, 0, w)
                val sheetFile = File(outputDir, "${hexId}_${name}_sheet_16col.png")
                ImageIO.write(sheetImg, "PNG", sheetFile)
                println("Exported sheet (16-col VRAM layout): ${sheetFile.absolutePath} (${w}x${h})")
            }

            // Compare against reference PNG if available
            val refFile = File("/Users/kenny/code/super_metroid_dev/desktopApp/src/jvmMain/resources/enemies/${hexId}.png")
            if (refFile.exists()) {
                val refImg = ImageIO.read(refFile)
                println("  Reference: ${refFile.name} (${refImg.width}x${refImg.height})")
                println("  Assembled: ${assembled.width}x${assembled.height}")
            } else {
                println("  No reference PNG found at enemies/${hexId}.png")
            }
        }
    }

    @Test
    fun `compare tile sheet 8-col vs 16-col layout`() {
        val rp = loadTestRom() ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xD93F) ?: return // Sidehopper
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xD93F) ?: return

        val gfx = EnemySpriteGraphics(rp)
        gfx.loadFromRaw(listOf(tileData))

        val outputDir = File("build/test-sprites")
        outputDir.mkdirs()

        // 8-column sheet (what the editor currently shows)
        val sheet8 = gfx.renderSheet(palette, 8)!!
        val (p8, w8, h8) = sheet8
        val img8 = BufferedImage(w8, h8, BufferedImage.TYPE_INT_ARGB)
        img8.setRGB(0, 0, w8, h8, p8, 0, w8)
        ImageIO.write(img8, "PNG", File(outputDir, "D93F_Sidehopper_sheet_8col.png"))
        println("8-col sheet: ${w8}x${h8}")

        // 16-column sheet (VRAM layout)
        val sheet16 = gfx.renderSheet(palette, 16)!!
        val (p16, w16, h16) = sheet16
        val img16 = BufferedImage(w16, h16, BufferedImage.TYPE_INT_ARGB)
        img16.setRGB(0, 0, w16, h16, p16, 0, w16)
        ImageIO.write(img16, "PNG", File(outputDir, "D93F_Sidehopper_sheet_16col.png"))
        println("16-col sheet: ${w16}x${h16}")

        println("\nIn VRAM (16-col) layout, a 16x16 sprite at tile 0 uses:")
        println("  Top row: tiles 0, 1 (adjacent)")
        println("  Bottom row: tiles 16, 17 (one row below)")
        println("In 8-col layout, tile 16 appears at row 2, column 0 - NOT adjacent to tile 0!")
    }
}
