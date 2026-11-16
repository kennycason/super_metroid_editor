package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Export enemy sprites and item-room data for the Super Metroid memory game.
 * Run: ./gradlew :shared:test --tests "*.MemoryGameExportTest"
 */
class MemoryGameExportTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `export all enemy sprites for memory game`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val outputDir = File("build/memory-game-enemies")
        outputDir.mkdirs()

        var exported = 0
        var failed = 0
        val manifest = mutableListOf<String>()

        for (entry in EnemySpriteGraphics.EDITOR_ENEMIES) {
            val speciesId = entry.speciesId
            val name = entry.name
            val hexId = speciesId.toString(16).uppercase().padStart(4, '0')

            val defaultSmap = smap.findDefaultSpritemap(speciesId)
            if (defaultSmap == null) {
                println("SKIP (no spritemap): $hexId $name")
                failed++
                continue
            }

            val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId)
            if (palette == null) {
                println("SKIP (no palette): $hexId $name")
                failed++
                continue
            }

            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId)
            if (tileData == null) {
                println("SKIP (no tile data): $hexId $name")
                failed++
                continue
            }

            val paletteRows = EnemySpriteGraphics.readEnemyPaletteRows(rp, speciesId)
            val assembled = smap.renderSpritemap(defaultSmap, tileData, palette, paletteRows)
            if (assembled == null || assembled.width == 0 || assembled.height == 0) {
                println("SKIP (render failed): $hexId $name")
                failed++
                continue
            }

            val img = BufferedImage(assembled.width, assembled.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, assembled.width, assembled.height, assembled.pixels, 0, assembled.width)

            val outFile = File(outputDir, "${hexId}.png")
            ImageIO.write(img, "PNG", outFile)
            println("OK: $hexId $name (${assembled.width}x${assembled.height})")
            manifest.add("$hexId|$name|${entry.category}|${assembled.width}|${assembled.height}")
            exported++
        }

        // Write manifest for easy TypeScript data generation
        File(outputDir, "manifest.txt").writeText(manifest.joinToString("\n"))
        println("\nExported: $exported, Failed: $failed")
    }

    @Test
    fun `export item locations per room as JSON`() {
        val rp = loadTestRom() ?: return
        val roomIds = rp.roomCatalog.rooms.map { it.getRoomIdAsInt() }
        val allItems = rp.scanAllItemPlms(roomIds)

        val lines = mutableListOf<String>()
        lines.add("[")

        for ((idx, info) in allItems.withIndex()) {
            val itemName = RomParser.itemNameForPlm(info.plm.id)
                ?.replace(" (Chozo)", "")
                ?.replace(" (Visible)", "")
                ?.replace(" (Hidden)", "")
                ?: continue
            val roomHex = "0x${info.roomId.toString(16).uppercase()}"
            val comma = if (idx < allItems.size - 1) "," else ""
            lines.add("""  {"roomId":${info.roomId},"roomIdHex":"$roomHex","area":${info.area},"item":"$itemName","tileX":${info.plm.x},"tileY":${info.plm.y}}$comma""")
        }
        lines.add("]")

        val outputDir = File("build/memory-game-enemies")
        outputDir.mkdirs()
        File(outputDir, "items.json").writeText(lines.joinToString("\n"))
        println("Exported ${allItems.size} item placements")
    }
}
