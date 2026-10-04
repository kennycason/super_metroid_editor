package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhantoonSpriteTest {

    @Test
    fun `all active Phantoon BG2 tilemaps render from their owning species`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val phantoon = PhantoonSpritemap(parser)
        assertTrue(phantoon.load())
        assertEquals(PhantoonSpritemap.PHANTOON_TILESET_ID, phantoon.getTilesetId())
        assertEquals(22, PhantoonSpritemap.COMPONENT_TILEMAPS.size)
        assertEquals(
            mapOf(
                PhantoonSpritemap.ComponentGroup.BODY to 1,
                PhantoonSpritemap.ComponentGroup.EYE to 3,
                PhantoonSpritemap.ComponentGroup.EYEBALL to 9,
                PhantoonSpritemap.ComponentGroup.TENTACLE to 6,
                PhantoonSpritemap.ComponentGroup.MOUTH to 3,
            ),
            PhantoonSpritemap.COMPONENT_TILEMAPS.groupingBy { it.group }.eachCount(),
        )

        PhantoonSpritemap.COMPONENT_TILEMAPS.forEach { definition ->
            val rendered = assertNotNull(phantoon.renderComponent(definition), definition.name)
            assertTrue(rendered.pixels.any { (it ushr 24) != 0 }, definition.name)
            assertTrue(rendered.entries.all { it.paletteRow == PhantoonSpritemap.PALETTE_ROW }, definition.name)
        }
    }

    @Test
    fun `full body uses shared runtime coordinates and all gaze poses`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val phantoon = PhantoonSpritemap(parser)
        assertTrue(phantoon.load())

        val closed = assertNotNull(phantoon.renderFullBody())
        assertEquals(80, closed.width)
        assertEquals(112, closed.height)
        assertTrue(closed.pixels.count { (it ushr 24) != 0 } > 1000)

        val gazeHashes = PhantoonSpritemap.EYEBALL_TILEMAPS.map { eyeball ->
            val rendered = assertNotNull(phantoon.renderFullBody(eyeball = eyeball), eyeball.name)
            assertEquals(closed.width, rendered.width)
            assertEquals(closed.height, rendered.height)
            rendered.pixels.contentHashCode()
        }
        assertEquals(9, gazeHashes.distinct().size, "every gaze direction should produce a distinct composition")
        assertNotEquals(closed.pixels.contentHashCode(), gazeHashes.first())
    }

    @Test
    fun `all runtime health palettes and bounded part animations render`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val phantoon = PhantoonSpritemap(parser)
        assertTrue(phantoon.load())
        assertEquals(0xA7CC21, PhantoonSpritemap.PALETTE_SNES)

        val paletteHashes = PhantoonSpritemap.PALETTE_STAGES.map { stage ->
            assertNotNull(phantoon.readPalette(stage), stage.name).contentHashCode()
        }
        assertEquals(8, paletteHashes.distinct().size)

        val expectedFrames = mapOf(
            "eye-open" to 3,
            "eye-close-pattern" to 2,
            "eye-close" to 2,
            "tentacles" to 4,
            "mouth-flame" to 2,
        )
        PhantoonSpritemap.ANIMATIONS.forEach { definition ->
            val source = assertNotNull(phantoon.loadAnimation(definition), definition.name)
            assertEquals(expectedFrames.getValue(definition.key), source.frames.size, definition.name)
            val rendered = assertNotNull(phantoon.renderAnimation(definition), definition.name)
            assertEquals(source.frames.size, rendered.frames.size, definition.name)
            assertEquals(definition.loop, rendered.loop, definition.name)
            assertTrue(rendered.frames.all { it.width == 80 && it.height == 112 }, definition.name)
            assertTrue(rendered.frames.all { frame -> frame.pixels.count { (it ushr 24) != 0 } > 1000 }, definition.name)
        }
    }
}
