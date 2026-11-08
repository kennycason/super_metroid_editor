package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.*

class ProjectileSpritesTest {
    @Test
    fun `combined beam masks share native Plasma resources and render two alternating lobes`() {
        assertEquals(16, ProjectileSprites.BEAMS.size)
        assertEquals(listOf(8, 9, 10, 11), (0xC..0xF).map(ProjectileSprites::nativeBeamMask))

        val renderer = ProjectileSprites(TestRomHelper.requireRomParser())
        val plasma = assertNotNull(renderer.render(ProjectileSprites.WEAPONS.single { it.name == "Plasma" }, 2))
        val combined = assertNotNull(renderer.render(ProjectileSprites.WEAPONS.single { it.name == "Spazer + Plasma" }, 2))
        assertEquals(plasma.totalTicks, combined.totalTicks)
        assertTrue(combined.frames.size > plasma.frames.size)
        assertEquals(plasma.frames.first().width, combined.frames.first().width)
        assertEquals(plasma.frames.first().height + 12, combined.frames.first().height)
        assertTrue(combined.frames.map { it.pixels.contentHashCode() }.distinct().size > 1)
    }

    @Test
    fun `all equipped beam combinations and weapon effects render in every direction`() {
        val renderer = ProjectileSprites(TestRomHelper.requireRomParser())
        for (weapon in ProjectileSprites.WEAPONS) for (direction in ProjectileSprites.DIRECTIONS.indices) {
            val animation = assertNotNull(renderer.render(weapon, direction), "${weapon.name} direction $direction")
            assertTrue(animation.frames.any { frame -> frame.pixels.any { it ushr 24 != 0 } }, weapon.name)
            assertTrue(animation.frames.all { it.durationTicks > 0 })
            assertEquals(1, animation.frames.map { it.width to it.height }.distinct().size)
        }
    }

    @Test
    fun `Power beam follows its eight byte frame and goto timer contract`() {
        val animation = assertNotNull(ProjectileSprites(TestRomHelper.requireRomParser()).render(ProjectileSprites.WEAPONS.first(), 2))
        assertEquals(1, animation.frames.size)
        assertEquals(15, animation.totalTicks)
        assertTrue(animation.loop)
        assertEquals(8 to 8, animation.frames.single().width to animation.frames.single().height)
    }
}
