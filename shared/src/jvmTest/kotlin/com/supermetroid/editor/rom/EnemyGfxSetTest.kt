package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class EnemyGfxSetTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `readEnemyPalette returns correct palette for Phantoon`() {
        val parser = loadTestRom() ?: return
        val palette = EnemySpriteGraphics.readEnemyPalette(parser, 0xE4BF)
        assertNotNull(palette)
        palette!!
        assertEquals(0x00000000, palette[0], "Index 0 should be transparent")
        assertTrue(palette.any { it != 0 && it != 0x00000000.toInt() },
            "Palette should have non-transparent colors")

        // Verify matches the known Phantoon palette at $A7:CA21
        val r1 = (palette[1] shr 16) and 0xFF
        val g1 = (palette[1] shr 8) and 0xFF
        val b1 = palette[1] and 0xFF
        assertTrue(r1 > 100 && g1 > 100, "Palette[1] should be a light olive/yellow color")
        println("Phantoon palette[1]: R=$r1, G=$g1, B=$b1")
    }

    @Test
    fun `readEnemyPalette returns palette for common enemies`() {
        val parser = loadTestRom() ?: return

        val enemies = listOf(
            0xDCFF to "Zoomer",
            0xD93F to "Sidehopper",
            0xD7FF to "Tripper",
            0xDC7F to "Zeela",
        )

        for ((speciesId, name) in enemies) {
            val palette = EnemySpriteGraphics.readEnemyPalette(parser, speciesId)
            assertNotNull(palette, "$name palette should not be null")
            palette!!
            assertEquals(0x00000000, palette[0], "$name palette[0] should be transparent")
            val nonTransparent = palette.count { it != 0x00000000 }
            assertTrue(nonTransparent >= 5, "$name should have at least 5 non-transparent colors, got $nonTransparent")
            println("$name: $nonTransparent non-transparent colors")
        }
    }

    @Test
    fun `readSpeciesStats returns correct HP for known enemies`() {
        val parser = loadTestRom() ?: return

        val expected = listOf(
            Triple(0xE4BF, "Phantoon", 2500),
            Triple(0xDCFF, "Zoomer", 15),
            Triple(0xD93F, "Sidehopper", 60),
            Triple(0xD7FF, "Tripper", 20),
        )

        for ((speciesId, name, expectedHp) in expected) {
            val stats = EnemySpriteGraphics.readSpeciesStats(parser, speciesId)
            assertNotNull(stats, "$name stats should not be null")
            val (_, hp, _) = stats!!
            assertEquals(expectedHp, hp, "$name HP should be $expectedHp, got $hp")
        }
    }

    @Test
    fun `loadEnemyTileData returns correct sized tile data from GRAPHADR`() {
        val parser = loadTestRom() ?: return

        val enemies = listOf(
            0xDCFF to "Zoomer",
            0xDC7F to "Zeela",
            0xD93F to "Sidehopper",
            0xD7FF to "Tripper",
            0xCFFF to "Cacatac",
        )

        for ((speciesId, name) in enemies) {
            val stats = EnemySpriteGraphics.readSpeciesStats(parser, speciesId)
            assertNotNull(stats, "$name stats should not be null")
            val expectedSize = stats!!.first

            val tileData = EnemySpriteGraphics.loadEnemyTileData(parser, speciesId)
            assertNotNull(tileData, "$name tile data should not be null")
            assertEquals(expectedSize, tileData!!.size,
                "$name tile data should be $expectedSize bytes, got ${tileData.size}")

            val nonZero = tileData.count { it.toInt() != 0 }
            assertTrue(nonZero > expectedSize / 10,
                "$name tile data should have substantial non-zero content, got $nonZero/$expectedSize")
        }
    }

    @Test
    fun `B4 GFX set format is 4-byte entries terminated by FFFF`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        // Phantoon room — known GFX set at $B4:8D1D
        val stateOffsets = parser.findAllStateDataOffsets(0x8FCD13)
        assertTrue(stateOffsets.isNotEmpty())
        val stateOff = stateOffsets.last()
        val enemyGfxPtr = (rom[stateOff + 10].toInt() and 0xFF) or
            ((rom[stateOff + 11].toInt() and 0xFF) shl 8)

        val b4Pc = parser.snesToPc(0xB40000 or enemyGfxPtr)
        val species1 = (rom[b4Pc].toInt() and 0xFF) or ((rom[b4Pc + 1].toInt() and 0xFF) shl 8)
        assertEquals(0xE4BF, species1, "First B4 GFX entry should be Phantoon species ID")
        val terminator = (rom[b4Pc + 4].toInt() and 0xFF) or ((rom[b4Pc + 5].toInt() and 0xFF) shl 8)
        assertEquals(0xFFFF, terminator, "Second entry should be terminator (FFFF)")
    }
}
