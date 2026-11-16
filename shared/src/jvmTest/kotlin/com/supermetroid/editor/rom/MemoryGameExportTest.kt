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

    // Converters for different AssembledSprite types
    private fun EnemySpritemap.AssembledSprite.toFrame() = RenderedFrame(pixels, width, height)
    private fun KraidSpritemap.AssembledSprite.toFrame() = RenderedFrame(pixels, width, height)
    private fun PhantoonSpritemap.AssembledSprite.toFrame() = RenderedFrame(pixels, width, height)

    /**
     * Try to render a boss using its dedicated spritemap renderer.
     * Returns null for non-boss species or if rendering fails.
     */
    private fun renderBossPreview(rp: RomParser, speciesId: Int): RenderedFrame? {
        return when (speciesId) {
            // Crocomire
            0xDDBF -> {
                val croc = CrocomireSpritemap(rp)
                val tiles = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return null
                if (!croc.load(tiles)) return null
                croc.renderComposition(CrocomireSpritemap.COMPOSITIONS.first())?.toFrame()
            }
            // Draygon
            0xDE3F, 0xDE7F, 0xDEBF, 0xDEFF -> {
                val drag = DraygonSpritemap(rp)
                val tiles = EnemySpriteGraphics.loadEnemyTileData(rp, 0xDE3F)
                if (!drag.load(tiles)) return null
                drag.renderComposition(DraygonSpritemap.COMPOSITIONS.first())?.toFrame()
            }
            // Kraid
            0xE2BF -> {
                val kraid = KraidSpritemap(rp)
                if (!kraid.load()) return null
                kraid.renderFullBody()?.toFrame()
            }
            // Phantoon
            0xE4BF, 0xE4FF, 0xE53F, 0xE57F -> {
                val phantoon = PhantoonSpritemap(rp)
                if (!phantoon.load()) return null
                phantoon.renderFullBody()?.toFrame()
            }
            // Ridley
            0xE17F -> {
                val ridley = RidleySpritemap(rp)
                val tiles = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId)
                if (!ridley.load(tiles)) return null
                ridley.renderComposition(RidleySpritemap.COMPOSITIONS.first())?.toFrame()
            }
            // Mother Brain
            0xEC3F, 0xEC7F -> {
                val mb = MotherBrainSpritemap(rp)
                if (!mb.load()) return null
                mb.renderComposition(MotherBrainSpritemap.COMPOSITIONS.first())?.toFrame()
            }
            // Torizo (bomb and gold)
            0xEEFF, 0xEF3F, 0xEF7F, 0xEFBF -> {
                val torizo = TorizoSpritemap(rp)
                val tiles = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return null
                if (!torizo.load(tiles)) return null
                val encounter = if (speciesId == 0xEF7F || speciesId == 0xEFBF) TorizoSpritemap.Encounter.GOLDEN else TorizoSpritemap.Encounter.BOMB
                val comp = TorizoSpritemap.COMPOSITIONS.firstOrNull { it.encounter == encounter } ?: return null
                val palette = TorizoSpritemap.PALETTE_STAGES.firstOrNull { it.encounter == encounter } ?: return null
                torizo.renderComposition(comp, palette)?.toFrame()
            }
            // Botwoon
            0xF293 -> {
                val botwoon = BotwoonSpritemap(rp)
                val tiles = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return null
                if (!botwoon.load(tiles)) return null
                botwoon.renderComposition(BotwoonSpritemap.COMPOSITIONS.first())?.toFrame()
            }
            // Spore Spawn
            0xDF3F -> {
                val spore = SporeSpawnSpritemap(rp)
                val tiles = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return null
                if (!spore.load(tiles)) return null
                spore.renderComposition(SporeSpawnSpritemap.COMPOSITIONS.first())?.toFrame()
            }
            else -> null
        }
    }

    @Test
    fun `export all enemy sprites for memory game`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val outputDir = File("build/memory-game-enemies")
        outputDir.mkdirs()

        // Index source animation definitions by species ID
        val sourceAnimsBySpecies = mutableMapOf<Int, SourceEnemyAnimations.Definition>()
        for (def in SourceEnemyAnimations.DEFINITIONS) {
            if (def.speciesId !in sourceAnimsBySpecies) sourceAnimsBySpecies[def.speciesId] = def
            for (alias in def.aliasSpeciesIds) {
                if (alias !in sourceAnimsBySpecies) sourceAnimsBySpecies[alias] = def
            }
        }

        var exported = 0
        var failed = 0
        val manifest = mutableListOf<String>()

        for (entry in EnemySpriteGraphics.EDITOR_ENEMIES) {
            val speciesId = entry.speciesId
            val name = entry.name
            val hexId = speciesId.toString(16).uppercase().padStart(4, '0')

            var result: RenderedFrame? = null
            var method = ""

            // Strategy 1: Dedicated boss renderer
            renderBossPreview(rp, speciesId)?.let {
                if (it.width > 8 && it.height > 8) { result = it; method = "boss" }
            }

            // Load common data for remaining strategies
            val palette = if (result == null) EnemySpriteGraphics.readEnemyPalette(rp, speciesId) else null
            val tileData = if (result == null) EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) else null

            if (result == null && (palette == null || tileData == null)) {
                if (result == null) {
                    println("SKIP (no palette/tiles): $hexId $name")
                    failed++
                    continue
                }
            }

            // Strategy 2: Special enemy preview (Metroid, Suspensor Platform)
            if (result == null && palette != null && tileData != null) {
                smap.renderSpecialEnemyPreview(speciesId, tileData, palette)?.let { sp ->
                    if (sp.width > 8 && sp.height > 8) { result = sp.toFrame(); method = "special" }
                }
            }

            // Strategy 3: Source-verified animation first frame
            if (result == null && palette != null && tileData != null) {
                sourceAnimsBySpecies[speciesId]?.let { sourceDef ->
                    smap.buildSourceAnimation(sourceDef, tileData, palette)?.let { anim ->
                        if (anim.frames.isNotEmpty()) {
                            val f = anim.frames.first()
                            if (f.width > 8 && f.height > 8) { result = RenderedFrame(f.pixels, f.width, f.height); method = "source-anim" }
                        }
                    }
                }
            }

            // Strategy 4: Animation system first frame
            if (result == null && palette != null && tileData != null) {
                val specialAnim = smap.buildSpecialEnemyAnimation(speciesId, tileData, palette, name)
                val anim = specialAnim ?: smap.buildAnimation(speciesId, tileData, palette, name)
                if (anim != null && anim.frames.isNotEmpty()) {
                    val f = anim.frames.first()
                    if (f.width > 8 && f.height > 8) { result = RenderedFrame(f.pixels, f.width, f.height); method = "animation" }
                }
            }

            // Strategy 5: Default spritemap (last resort)
            if (result == null && palette != null && tileData != null) {
                val paletteRows = EnemySpriteGraphics.readEnemyPaletteRows(rp, speciesId)
                smap.findDefaultSpritemap(speciesId)?.let { defaultSmap ->
                    smap.renderSpritemap(defaultSmap, tileData, palette, paletteRows)?.let { sp ->
                        result = sp.toFrame(); method = "default-smap"
                    }
                }
            }

            val r = result
            if (r == null || r.width == 0 || r.height == 0) {
                println("SKIP (all strategies failed): $hexId $name")
                failed++
                continue
            }

            val img = BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, r.width, r.height, r.pixels, 0, r.width)

            val outFile = File(outputDir, "${hexId}.png")
            ImageIO.write(img, "PNG", outFile)
            println("OK [$method]: $hexId $name (${r.width}x${r.height})")
            manifest.add("$hexId|$name|${entry.category}|${r.width}|${r.height}")
            exported++
        }

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
