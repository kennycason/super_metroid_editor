package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class EnemySpriteRenderTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `enemy palette auto-detect picks correct row for all editor enemies`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()

        for (entry in EnemySpriteGraphics.EDITOR_ENEMIES.filter { it.category != "Boss" }) {
            val palette = EnemySpriteGraphics.readEnemyPalette(rp, entry.speciesId)
            assertNotNull(palette, "${entry.name} palette should not be null")
            palette!!

            assertEquals(0x00000000, palette[0], "${entry.name} index 0 should be transparent")

            val nonTransparent = palette.count { it != 0 && it != 0x00000000.toInt() }
            assertTrue(nonTransparent >= 5,
                "${entry.name} should have at least 5 non-transparent colors, got $nonTransparent")

            for (i in 1 until 16) {
                val c = palette[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                assertTrue(r in 0..255 && g in 0..255 && b in 0..255,
                    "${entry.name} palette[$i] has invalid RGB: $r,$g,$b")
            }

            println("${entry.name}: ${nonTransparent} colors, palette OK")
        }
    }

    @Test
    fun `enemy tile data decompresses to correct size for all editor enemies`() {
        val rp = loadTestRom() ?: return

        for (entry in EnemySpriteGraphics.EDITOR_ENEMIES.filter { it.category != "Boss" }) {
            val stats = EnemySpriteGraphics.readSpeciesStats(rp, entry.speciesId)
            assertNotNull(stats, "${entry.name} stats should not be null")
            val (expectedSize, _, _) = stats!!

            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, entry.speciesId)
            assertNotNull(tileData, "${entry.name} tile data should not be null")
            tileData!!
            assertEquals(expectedSize, tileData.size,
                "${entry.name} tile data should be $expectedSize bytes")

            val nonZero = tileData.count { it.toInt() != 0 }
            assertTrue(nonZero > tileData.size / 10,
                "${entry.name} should have >10% non-zero bytes, got ${nonZero * 100 / tileData.size}%")

            println("${entry.name}: ${tileData.size} bytes, ${nonZero * 100 / tileData.size}% non-zero")
        }
    }

    @Test
    fun `enemy sprites render without crashing for all editor enemies`() {
        val rp = loadTestRom() ?: return

        for (entry in EnemySpriteGraphics.EDITOR_ENEMIES.filter { it.category != "Boss" }) {
            val palette = EnemySpriteGraphics.readEnemyPalette(rp, entry.speciesId)
            assertNotNull(palette, "${entry.name} palette")
            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, entry.speciesId)
            assertNotNull(tileData, "${entry.name} tile data")

            val gfx = EnemySpriteGraphics(rp)
            gfx.loadFromRaw(listOf(tileData!!))
            val result = gfx.renderSheet(palette!!)
            assertNotNull(result, "${entry.name} should render")

            val (pixels, w, h) = result!!
            assertTrue(w > 0 && h > 0, "${entry.name} render should have size")
            assertTrue(pixels.size == w * h, "${entry.name} pixel count should match dimensions")

            val uniqueColors = pixels.toSet().size
            assertTrue(uniqueColors >= 3,
                "${entry.name} should use at least 3 distinct colors, got $uniqueColors")

            println("${entry.name}: rendered ${w}x${h}, $uniqueColors unique colors")
        }
    }

    @Test
    fun `Phantoon palette is still correct via readEnemyPalette`() {
        val rp = loadTestRom() ?: return
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xE4BF)
        assertNotNull(palette)
        palette!!
        assertEquals(0x00000000, palette[0], "Index 0 should be transparent")

        val nonTransparent = palette.count { it != 0 && it != 0x00000000.toInt() }
        assertTrue(nonTransparent >= 10,
            "Phantoon should have at least 10 non-transparent colors, got $nonTransparent")
        println("Phantoon: $nonTransparent colors via readEnemyPalette")
    }

    @Test
    fun `known enemy stats match vanilla ROM values`() {
        val rp = loadTestRom() ?: return
        data class ExpectedStats(val name: String, val id: Int, val hp: Int, val damage: Int)
        val expected = listOf(
            ExpectedStats("Zoomer", 0xDCFF, 15, 5),
            ExpectedStats("Zeela", 0xDC7F, 30, 10),
            ExpectedStats("Sidehopper", 0xD93F, 60, 20),
            ExpectedStats("Skree", 0xD7FF, 20, 40),
            ExpectedStats("Cacatac", 0xCFFF, 60, 20),
        )
        for (e in expected) {
            val stats = EnemySpriteGraphics.readSpeciesStats(rp, e.id)
            assertNotNull(stats, "${e.name} stats")
            val (_, hp, damage) = stats!!
            assertEquals(e.hp, hp, "${e.name} HP")
            assertEquals(e.damage, damage, "${e.name} Damage")
        }
    }
}
