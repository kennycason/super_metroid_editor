package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `Bang preview and discharge assemble core shell and native electricity`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val tileData = EnemySpriteGraphics.loadStandardOamRenderTileData(rp, BANG) ?: return
        val palette = EnemySpriteGraphics.loadEnemyPreviewPaletteSource(rp, BANG)?.colors ?: return

        val core = smap.traceInstructionListAt(0xA3B85A, 1).frames.single()
            .let { smap.renderRenderableFrame(it.renderableFrame, tileData, palette) }
        val shell = smap.traceInstructionListAt(0xA3B9A8, 1).frames.single()
            .let { smap.renderRenderableFrame(it.renderableFrame, tileData, palette) }
        val electricity = smap.traceInstructionListAt(0xA3BA00, 14).frames
            .mapNotNull { smap.renderRenderableFrame(it.renderableFrame, tileData, palette) }
        val preview = smap.renderSpecialEnemyPreview(BANG, tileData, palette)
        val animation = smap.buildSpecialEnemyAnimation(BANG, tileData, palette, "Bang")

        assertNotNull(core)
        assertNotNull(shell)
        assertTrue(electricity.isNotEmpty(), "Bang should expose its electricity maps")
        assertNotNull(preview)
        assertNotNull(animation)
        core!!
        shell!!
        preview!!
        animation!!

        val previewOpaque = preview.pixels.count { it ushr 24 != 0 }
        assertTrue(previewOpaque > core.pixels.count { it ushr 24 != 0 }, "Preview needs the shell")
        assertTrue(
            preview.width != shell.width || preview.height != shell.height ||
                !preview.pixels.contentEquals(shell.pixels),
            "Preview must not collapse to the shell-only frame",
        )
        assertEquals(48, animation.totalTicks, "Use the complete native electricity timeline")
        assertTrue(animation.frames.size > 7, "Electricity must contribute independent frame boundaries")
        assertTrue(animation.frames.all { frame -> frame.pixels.any { it ushr 24 != 0 } })
    }

    @Test
    fun `enemy catalog uses native identities and does not duplicate Golden Four`() {
        assertEquals("Mochtroid", RomParser.enemyName(0xD8FF))
        assertEquals("Dessgeega (large)", RomParser.enemyName(0xDA3F))
        assertEquals("Sidehopper (large, Tourian)", RomParser.enemyName(0xD9FF))
        assertFalse(EnemySpriteGraphics.EDITOR_ENEMIES.any { it.speciesId == 0xEFFF })
    }

    @Test
    fun `Zebetite preview combines its runtime core palette and Mother Brain room casing`() {
        val rp = loadTestRom() ?: return
        val source = EnemySpriteGraphics.loadEnemyPreviewTileSource(rp, ZEBETITE) ?: return
        val rinka = EnemySpriteGraphics.loadEnemyTileData(rp, RINKA) ?: return
        val render = EnemySpriteGraphics.loadStandardOamRenderTileData(rp, ZEBETITE) ?: return
        val paletteSource = EnemySpriteGraphics.loadEnemyPreviewPaletteSource(rp, ZEBETITE) ?: return
        val rinkaPalette = EnemySpriteGraphics.readEnemyPalette(rp, RINKA) ?: return

        assertEquals(RINKA, source.sourceSpeciesId)
        assertTrue(source.bytes.contentEquals(rinka.copyOf(source.byteCount)))
        assertTrue(render.contentEquals(source.bytes))
        assertTrue(paletteSource.colors.contentEquals(rinkaPalette))
        assertEquals(EnemyPreviewAssetOwnership.SHARED_SPECIES, paletteSource.ownership)

        val smap = EnemySpritemap(rp)
        val palette = paletteSource.colors
        val map = smap.findDefaultSpritemap(ZEBETITE)
        val core = map?.let { smap.renderSpritemap(it, render, palette) }
        val sprite = smap.renderSpecialEnemyPreview(ZEBETITE, render, palette)
        assertNotNull(core, "Zebetite should render its Rinka/Polyp core at runtime offset $80")
        assertNotNull(sprite, "Zebetite should include the Mother Brain room casing")
        assertEquals(3, map?.entries?.size)
        assertEquals(16, core?.width)
        assertEquals(48, core?.height)
        assertEquals(16, sprite?.width)
        assertEquals(64, sprite?.height)
        sprite!!
        assertTrue(sprite.pixels.take(16 * 8).any { it ushr 24 != 0 }, "Top cap must be visible")
        assertTrue(sprite.pixels.takeLast(16 * 8).any { it ushr 24 != 0 }, "Bottom cap must be visible")
        assertTrue(sprite.pixels.slice(8 * 16 until 56 * 16).any(::isBrightZebetiteRed))
        assertEquals(0, sprite.pixels.first() ushr 24, "Casing corners should remain transparent")

        val healthStates = SourceEnemyAnimations.forSpecies(ZEBETITE)
        assertEquals(10, healthStates.size)
        healthStates.forEach { definition ->
            val animation = smap.buildSourceAnimation(definition, render, palette)
            assertNotNull(animation, definition.key)
            val frame = animation!!.frames.single()
            assertEquals(16, frame.width, definition.key)
            assertEquals(if ("-big-" in definition.key) 64 else 32, frame.height, definition.key)
            assertTrue(frame.pixels.take(16 * 8).any { it ushr 24 != 0 }, definition.key)
            assertTrue(frame.pixels.takeLast(16 * 8).any { it ushr 24 != 0 }, definition.key)
        }
    }

    private fun isBrightZebetiteRed(argb: Int): Boolean {
        if ((argb ushr 24) < 128) return false
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r >= 180 && g <= 80 && b <= 80
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
        const val BANG = 0xDB3F
        const val RINKA = 0xD23F
        const val ZEBETITE = 0xE27F
    }
}
