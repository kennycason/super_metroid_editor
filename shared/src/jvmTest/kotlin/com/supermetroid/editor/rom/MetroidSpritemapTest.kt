package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetroidSpritemapTest {

    private fun load(): MetroidSpritemap? {
        val parser = TestRomHelper.loadRomParser() ?: return null
        val tiles = EnemySpriteGraphics.loadEnemyTileData(parser, MetroidSpritemap.SPECIES_ID) ?: return null
        return MetroidSpritemap(parser).also { assertTrue(it.load(tiles)) }
    }

    @Test
    fun `all three independently timed runtime tracks match their source boundaries`() {
        val renderer = load() ?: return

        assertTrack(renderer.chasingFrames(), 20, 0x100)
        assertTrack(renderer.drainingFrames(), 5, 0x40)
        assertTrack(renderer.electricityIntroFrames(), 31, 93)
        assertTrack(renderer.electricitySteadyFrames(), 31, 115)
        assertTrack(renderer.shellIntroFrames(), 32, 32)
        assertTrack(renderer.shellSteadyFrames(), 30, 30)

        assertTrue(renderer.shellIntroFrames().all { it.snesAddress == null || it.snesAddress in setOf(0xB4D5B7, 0xB4D5EB) })
        assertTrue(renderer.shellSteadyFrames().any { it.snesAddress == 0xB4D61F },
            "The object-35 fallthrough supplies the third shell map at runtime")
    }

    @Test
    fun `compositions and every independently addressable layer render`() {
        val renderer = load() ?: return

        MetroidSpritemap.COMPOSITIONS.forEach { definition ->
            val rendered = assertNotNull(renderer.renderComposition(definition), definition.name)
            assertTrue(rendered.pixels.any { it ushr 24 != 0 }, definition.name)
        }
        MetroidSpritemap.COMPONENTS.forEach { definition ->
            val rendered = assertNotNull(renderer.renderComponent(definition), definition.name)
            assertTrue(rendered.pixels.any { it ushr 24 != 0 }, definition.name)
        }
    }

    @Test
    fun `guided animations preserve independent companion timing`() {
        val renderer = load() ?: return
        val expectedFrames = mapOf(
            "runtime-intro" to 93,
            "runtime-steady" to 115,
            "draining" to 64,
            "shell-intro" to 32,
            "shell-steady" to 30,
            "electricity-intro" to 31,
            "electricity-steady" to 31,
        )

        MetroidSpritemap.ANIMATIONS.forEach { definition ->
            val animation = assertNotNull(renderer.renderAnimation(definition), definition.name)
            assertEquals(expectedFrames.getValue(definition.key), animation.frames.size, definition.name)
            assertTrue(animation.frames.any { frame -> frame.pixels.any { it ushr 24 != 0 } }, definition.name)
        }
    }

    private fun assertTrack(frames: List<MetroidSpritemap.TimedMap>, count: Int, ticks: Int) {
        assertEquals(count, frames.size)
        assertEquals(ticks, frames.sumOf { it.durationTicks })
    }
}
