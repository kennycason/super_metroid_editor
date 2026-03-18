package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File

class KraidSpriteTest {
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
    fun `kraid loads successfully from tileset 27`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        assertTrue(kraid.load(), "Kraid should load successfully")
        assertNotNull(kraid.getTileData(), "Tile data should be extracted from tileset")
        assertNotNull(kraid.getPalette(), "Palette should be loaded")
        assertEquals(128 * 32, kraid.getTileData()!!.size, "Should have 128 tiles × 32 bytes")
    }

    @Test
    fun `body initial renders with correct dimensions`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        val body = kraid.renderBodyTilemap(KraidSpritemap.BODY_TILEMAPS[0])
        assertNotNull(body, "Body initial should render")
        assertEquals(32 * 8, body!!.width, "Width should be 32 tiles × 8px")
        assertEquals(12 * 8, body.height, "Height should be 12 tiles × 8px")
        assertTrue(body.pixels.any { it != 0 }, "Should have non-transparent pixels")
    }

    @Test
    fun `all body tilemaps render with non-transparent pixels`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        for (def in KraidSpritemap.BODY_TILEMAPS) {
            val body = kraid.renderBodyTilemap(def)
            assertNotNull(body, "${def.name} should render")
            val nonTransparent = body!!.pixels.count { it != 0 }
            assertTrue(nonTransparent > 100, "${def.name} should have >100 non-transparent pixels, got $nonTransparent")
        }
    }

    @Test
    fun `belly details render at correct 16x16 size`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        for (i in 0 until 8) {
            val def = KraidSpritemap.BIGSPRMAP_COMPONENTS[i]
            val sprite = kraid.renderBigSprmap(def)
            assertNotNull(sprite, "${def.name} should render")
            assertEquals(16, sprite!!.width, "${def.name} width should be 16px")
            assertEquals(16, sprite.height, "${def.name} height should be 16px")
            assertTrue(sprite.pixels.any { it != 0 }, "${def.name} should have visible pixels")
        }
    }

    @Test
    fun `feet render at correct 32x16 size`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        for (i in 8..9) {
            val def = KraidSpritemap.BIGSPRMAP_COMPONENTS[i]
            val sprite = kraid.renderBigSprmap(def)
            assertNotNull(sprite, "${def.name} should render")
            assertEquals(32, sprite!!.width, "${def.name} width should be 32px")
            assertEquals(16, sprite.height, "${def.name} height should be 16px")
        }
    }

    @Test
    fun `tile data from tileset has actual graphics not tilemap data`() {
        val rp = loadTestRom() ?: return
        val kraid = KraidSpritemap(rp)
        if (!kraid.load()) return

        val tileData = kraid.getTileData()!!
        // Tile 0x10 (belly detail) should NOT contain repeating 0x0338 pattern
        // (which would indicate tilemap data was injected instead of tile graphics)
        val bellyTileOffset = 0x10 * 32
        val firstWord = (tileData[bellyTileOffset].toInt() and 0xFF) or
                ((tileData[bellyTileOffset + 1].toInt() and 0xFF) shl 8)
        assertTrue(firstWord != 0x0338, "Tile data should be graphics, not tilemap entries (got 0x${firstWord.toString(16)})")
    }

    @Test
    fun `OAM spritemap discovery works for all kraid sub-entities`() {
        val rp = loadTestRom() ?: return
        val enemySpritemap = EnemySpritemap(rp)

        val subEntities = mapOf(
            "Upper body" to 0xE2FF,
            "Belly Spike 1" to 0xE33F,
            "Belly Spike 2" to 0xE37F,
            "Belly Spike 3" to 0xE3BF,
            "Flying Claw 1" to 0xE3FF,
            "Flying Claw 2" to 0xE43F,
            "Flying Claw 3" to 0xE47F,
        )

        for ((name, speciesId) in subEntities) {
            val spritemap = enemySpritemap.findDefaultSpritemap(speciesId)
            assertNotNull(spritemap, "$name (species 0x${speciesId.toString(16)}) should find a spritemap")
        }
    }
}
