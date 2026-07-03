package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnemySpriteRegressionTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `Metroid preview composites shell sprite object over insides`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val rawTileData = EnemySpriteGraphics.loadEnemyTileData(rp, METROID) ?: return
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, METROID)
        val spritemap = smap.findDefaultSpritemap(METROID)

        assertNotNull(palette, "Metroid palette should load")
        assertNotNull(spritemap, "Metroid spritemap should load")
        palette!!
        spritemap!!

        val rawSprite = smap.renderSpritemap(spritemap, rawTileData, palette)
        val composedSprite = smap.renderSpecialEnemyPreview(METROID, rawTileData, palette)
        assertNotNull(rawSprite, "Raw Metroid sprite should render")
        assertNotNull(composedSprite, "Composed Metroid preview should render")
        rawSprite!!
        composedSprite!!

        val rawGreenPixels = rawSprite.pixels.count(::isMetroidShellGreen)
        val composedGreenPixels = composedSprite.pixels.count(::isMetroidShellGreen)
        val composedRedPixels = composedSprite.pixels.count(::isMetroidUndersideRed)

        assertTrue(
            composedGreenPixels > rawGreenPixels + 200,
            "Composed Metroid should restore the green casing over the raw red underside"
        )
        assertTrue(
            composedRedPixels > 40,
            "Composed Metroid should preserve red underside/claw pixels through transparent shell holes"
        )
    }

    @Test
    fun `D83F is suspensor platform and renders both mirrored halves`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, SUSPENSOR_PLATFORM) ?: return
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, SUSPENSOR_PLATFORM) ?: return

        assertEquals("Tripper", RomParser.enemyName(TRIPPER))
        assertEquals("Suspensor Platform", RomParser.enemyName(SUSPENSOR_PLATFORM))

        val platformSpritemap = smap.findSpecialPreviewSpritemap(SUSPENSOR_PLATFORM)
        val platformSprite = smap.renderSpecialEnemyPreview(SUSPENSOR_PLATFORM, tileData, palette)
        assertNotNull(platformSpritemap, "Suspensor platform spritemap should load")
        assertNotNull(platformSprite, "Suspensor platform preview should render")
        platformSpritemap!!
        platformSprite!!

        assertEquals(0xA3A021, platformSpritemap.snesAddress)
        assertTrue(platformSprite.width >= 32, "Platform preview should include both 16px halves")

        val leftPixels = countOpaquePixels(platformSprite, xRange = 0 until platformSprite.width / 2)
        val rightPixels = countOpaquePixels(platformSprite, xRange = platformSprite.width / 2 until platformSprite.width)
        assertTrue(leftPixels > 20, "Left half should contain rendered platform pixels")
        assertTrue(rightPixels > 20, "Right half should contain rendered platform pixels")
    }

    private fun isMetroidShellGreen(argb: Int): Boolean {
        if ((argb ushr 24) < 128) return false
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return g >= 90 && r <= 40 && b <= 40
    }

    private fun isMetroidUndersideRed(argb: Int): Boolean {
        if ((argb ushr 24) < 128) return false
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r >= 150 && g <= 100 && b <= 140
    }

    private fun countOpaquePixels(sprite: EnemySpritemap.AssembledSprite, xRange: IntRange): Int {
        var count = 0
        for (y in 0 until sprite.height) {
            for (x in xRange) {
                if ((sprite.pixels[y * sprite.width + x] ushr 24) > 0) count++
            }
        }
        return count
    }

    private companion object {
        const val TRIPPER = 0xD7FF
        const val SUSPENSOR_PLATFORM = 0xD83F
        const val METROID = 0xDD7F
    }
}
