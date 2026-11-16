package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private data class RenderedFrame(val pixels: IntArray, val width: Int, val height: Int)

/**
 * Export enemy sprites and item-room data for the Super Metroid memory game.
 * Run with SMEDIT_TEST_ROM and JDK 17:
 *   JAVA_HOME=... SMEDIT_TEST_ROM=... ./gradlew :shared:cleanJvmTest :shared:jvmTest --tests "*.MemoryGameExportTest"
 */
class MemoryGameExportTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    /**
     * Export the best available preview sprite for each enemy. Uses a fallback chain:
     * 1. Special enemy preview (Metroid, Suspensor Platform) via renderSpecialEnemyPreview
     * 2. Source-verified animation first frame via buildSourceAnimation (SourceEnemyAnimations)
     * 3. Animation system first frame via buildAnimation (instruction list tracer)
     * 4. Default spritemap via findDefaultSpritemap (original approach, last resort)
     */
    @Test
    fun `export all enemy sprites for memory game`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val outputDir = File("build/memory-game-enemies")
        outputDir.mkdirs()

        // Index source animation definitions by species ID for quick lookup
        val sourceAnimsBySpecies = mutableMapOf<Int, SourceEnemyAnimations.Definition>()
        for (def in SourceEnemyAnimations.DEFINITIONS) {
            // Use the first definition for each species as the preview
            if (def.speciesId !in sourceAnimsBySpecies) {
                sourceAnimsBySpecies[def.speciesId] = def
            }
            for (alias in def.aliasSpeciesIds) {
                if (alias !in sourceAnimsBySpecies) {
                    sourceAnimsBySpecies[alias] = def
                }
            }
        }

        var exported = 0
        var failed = 0
        val manifest = mutableListOf<String>()

        for (entry in EnemySpriteGraphics.EDITOR_ENEMIES) {
            val speciesId = entry.speciesId
            val name = entry.name
            val hexId = speciesId.toString(16).uppercase().padStart(4, '0')

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

            // Strategy 1: Special enemy preview (Metroid, Suspensor Platform)
            var assembled = smap.renderSpecialEnemyPreview(speciesId, tileData, palette)
            var method = "special"

            // Strategy 2: Source-verified animation first frame
            if (assembled == null || assembled.width <= 8 || assembled.height <= 8) {
                val sourceDef = sourceAnimsBySpecies[speciesId]
                if (sourceDef != null) {
                    val anim = smap.buildSourceAnimation(sourceDef, tileData, palette)
                    if (anim != null && anim.frames.isNotEmpty()) {
                        val frame = anim.frames.first()
                        assembled = EnemySpritemap.AssembledSprite(frame.width, frame.height, frame.pixels)
                        method = "source-anim"
                    }
                }
            }

            // Strategy 3: Animation system first frame (instruction list tracer)
            if (assembled == null || assembled.width <= 8 || assembled.height <= 8) {
                val specialAnim = smap.buildSpecialEnemyAnimation(speciesId, tileData, palette, name)
                val anim = specialAnim ?: smap.buildAnimation(speciesId, tileData, palette, name)
                if (anim != null && anim.frames.isNotEmpty()) {
                    val frame = anim.frames.first()
                    assembled = EnemySpritemap.AssembledSprite(frame.pixels, frame.width, frame.height)
                    method = "animation"
                }
            }

            // Strategy 4: Default spritemap (original fallback)
            if (assembled == null || assembled.width <= 8 || assembled.height <= 8) {
                val defaultSmap = smap.findDefaultSpritemap(speciesId)
                if (defaultSmap != null) {
                    assembled = smap.renderSpritemap(defaultSmap, tileData, palette, paletteRows)
                    method = "default-smap"
                }
            }

            if (assembled == null || assembled.width == 0 || assembled.height == 0) {
                println("SKIP (all strategies failed): $hexId $name")
                failed++
                continue
            }

            val img = BufferedImage(assembled.width, assembled.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, assembled.width, assembled.height, assembled.pixels, 0, assembled.width)

            val outFile = File(outputDir, "${hexId}.png")
            ImageIO.write(img, "PNG", outFile)
            println("OK [$method]: $hexId $name (${assembled.width}x${assembled.height})")
            manifest.add("$hexId|$name|${entry.category}|${assembled.width}|${assembled.height}")
            exported++
        }

        // Write manifest
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
