package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File

class KraidSpriteTest {
    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `kraid loads successfully from tileset 1A`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        assertTrue(kraid.load(), "Kraid should load successfully")
        assertEquals(0x1A, TileGraphics.KRAID_TILESET, "Kraid's exact room tileset ID")
        assertEquals(0x1A, kraid.getTilesetId(), "Kraid room state should select tileset \$1A")
        assertNotNull(kraid.getTileData(), "Tile data should be extracted from tileset")
        assertNotNull(kraid.getPalette(), "Palette should be loaded")
        assertEquals(1024 * 32, kraid.getTileData()!!.size, "Tileset \$1A owns all 1024 tiles")
    }

    @Test
    fun `head frame renders only the rows copied by the game`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        val head = kraid.renderHeadTilemap(KraidSpritemap.HEAD_TILEMAPS[0])
        assertNotNull(head, "Closed-mouth head should render")
        assertEquals(32 * 8, head!!.width, "Width should be 32 tiles × 8px")
        assertEquals(11 * 8, head.height, "The interpreter copies 11 of 12 stored rows")
        assertTrue(head.pixels.any { it != 0 }, "Should have non-transparent pixels")
    }

    @Test
    fun `all four head tilemaps render with non-transparent pixels`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        for (def in KraidSpritemap.HEAD_TILEMAPS) {
            val head = kraid.renderHeadTilemap(def)
            assertNotNull(head, "${def.name} should render")
            val nonTransparent = head!!.pixels.count { it != 0 }
            assertTrue(nonTransparent > 100, "${def.name} should have >100 non-transparent pixels, got $nonTransparent")
        }
    }

    @Test
    fun `all head sequences render as complete stable full-body animations`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        KraidSpritemap.HEAD_SEQUENCES.forEach { def ->
            val source = kraid.loadHeadAnimation(def)
            val rendered = kraid.renderFullBodyAnimation(def)
            assertNotNull(source, def.name)
            assertNotNull(rendered, def.name)
            val sourceData = source!!
            val renderedData = rendered!!
            assertEquals(sourceData.frames.size, renderedData.frames.size, def.name)
            assertTrue(renderedData.frames.isNotEmpty(), def.name)
            assertTrue(renderedData.frames.all { it.width == 512 && it.height == 512 }, def.name)
            assertTrue(renderedData.frames.all { frame -> frame.pixels.count { it != 0 } > 1000 }, def.name)
        }
    }

    @Test
    fun `all health palettes and linked OAM lists render from exact source bounds`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        val paletteHashes = KraidSpritemap.PALETTE_STAGES.map { stage ->
            val palette = kraid.readBgPalette(stage)
            assertNotNull(palette, stage.name)
            palette!!.contentHashCode()
        }
        assertEquals(KraidSpritemap.PALETTE_STAGES.size, paletteHashes.distinct().size)

        KraidSpritemap.OAM_SEQUENCES.forEach { def ->
            val source = kraid.loadOamAnimation(def)
            assertNotNull(source, def.name)
            val sourceData = source!!
            assertTrue(sourceData.frames.isNotEmpty(), def.name)
            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, def.speciesId)
            assertNotNull(tileData, def.name)
            val rendered = kraid.renderOamAnimation(def, tileData!!)
            assertNotNull(rendered, def.name)
            val renderedData = rendered!!
            assertEquals(sourceData.frames.size, renderedData.frames.size, def.name)
            assertTrue(renderedData.frames.all { it.width > 0 && it.height > 0 }, def.name)
            assertEquals(1, renderedData.frames.map { it.width to it.height }.distinct().size, def.name)
            assertTrue(renderedData.frames.all { frame -> frame.pixels.any { it != 0 } }, def.name)
        }
    }

    @Test
    fun `kraid exposes the four exact source head maps`() {
        assertEquals(
            listOf(0xA797C8, 0xA79AC8, 0xA79DC8, 0xA7A0C8),
            KraidSpritemap.HEAD_TILEMAPS.map { it.snesAddr },
        )
    }

    @Test
    fun `tile data from tileset has actual graphics not tilemap data`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        val tileData = kraid.getTileData()!!
        // Tile 0x110 should NOT contain repeating 0x0338 pattern
        // (which would indicate tilemap data was injected instead of tile graphics)
        val bellyTileOffset = 0x110 * 32
        val firstWord = (tileData[bellyTileOffset].toInt() and 0xFF) or
                ((tileData[bellyTileOffset + 1].toInt() and 0xFF) shl 8)
        assertTrue(firstWord != 0x0338, "Tile data should be graphics, not tilemap entries (got 0x${firstWord.toString(16)})")
    }

    @Test
    fun `OAM spritemap discovery works for all kraid sub-entities`() {
        val rp = loadTestRom() ?: return
        val enemySpritemap = EnemySpritemap(rp)

        val subEntities = mapOf(
            "Arm" to 0xE2FF,
            "Lint top" to 0xE33F,
            "Lint middle" to 0xE37F,
            "Lint bottom" to 0xE3BF,
            "Foot" to 0xE3FF,
            "Nail" to 0xE43F,
            "Nail bad" to 0xE47F,
        )

        for ((name, speciesId) in subEntities) {
            val spritemap = enemySpritemap.findDefaultSpritemap(speciesId)
            assertNotNull(spritemap, "$name (species 0x${speciesId.toString(16)}) should find a spritemap")
        }
    }
}
