package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MiniKraidSpritemapTest {

    @Test
    fun `six exact action lists contain four full-body frames each`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val miniKraid = MiniKraidSpritemap(parser)
        val animations = assertNotNull(miniKraid.loadAnimations())

        assertEquals(6, animations.size)
        assertTrue(animations.all { it.frames.size == 4 })
        assertEquals(24, animations.sumOf { it.frames.size })
        assertEquals(
            MiniKraidSpritemap.POSES.map { it.snesAddr }.toSet(),
            animations.flatMap { animation -> animation.frames.map { it.sourceSnes } }.toSet(),
        )
    }

    @Test
    fun `all Mini Kraid source actions render on stable canvases`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val tileData = assertNotNull(EnemySpriteGraphics.loadEnemyTileData(parser, MiniKraidSpritemap.SPECIES_ID))
        val palette = assertNotNull(EnemySpriteGraphics.readEnemyPalette(parser, MiniKraidSpritemap.SPECIES_ID))
        val miniKraid = MiniKraidSpritemap(parser)

        MiniKraidSpritemap.SEQUENCES.forEach { def ->
            val animation = assertNotNull(miniKraid.renderAnimation(def, tileData, palette), def.name)
            assertEquals(4, animation.frames.size, def.name)
            assertEquals(1, animation.frames.map { it.width to it.height }.distinct().size, def.name)
            assertTrue(animation.frames.all { frame -> frame.pixels.count { it != 0 } > 500 }, def.name)
        }
    }
}
