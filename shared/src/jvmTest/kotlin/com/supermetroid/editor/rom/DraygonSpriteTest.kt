package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DraygonSpriteTest {

    @Test
    fun `Draygon loads both graphics owners and every authored composition`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val draygon = DraygonSpritemap(parser)
        assertTrue(draygon.load())

        assertEquals(0x2000, assertNotNull(draygon.getRawEnemyTileData()).size)
        assertEquals(10, DraygonSpritemap.COMPOSITIONS.size)

        val renders = DraygonSpritemap.COMPOSITIONS.map { definition ->
            assertNotNull(draygon.renderComposition(definition), definition.name)
        }
        assertTrue(renders.all { sprite -> sprite.pixels.count { (it ushr 24) != 0 } > 1000 })
        assertTrue(renders.all { it.width > 100 && it.height > 100 })
        assertEquals(10, renders.map { it.pixels.contentHashCode() }.distinct().size)
    }

    @Test
    fun `runtime component selector renders each slot in isolation`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val draygon = DraygonSpritemap(parser)
        assertTrue(draygon.load())
        assertEquals(4, DraygonSpritemap.COMPONENTS.size)

        DraygonSpritemap.Side.values().forEach { side ->
            val components = DraygonSpritemap.COMPONENTS.map { definition ->
                assertNotNull(draygon.renderComponent(definition, side), "${side.displayName} ${definition.name}")
            }
            assertEquals(4, components.map { it.pixels.contentHashCode() }.distinct().size)
            assertTrue(components.all { sprite -> sprite.pixels.any { (it ushr 24) != 0 } })

            val composition = assertNotNull(
                draygon.renderComposition(DraygonSpritemap.COMPOSITIONS.first { it.side == side }),
            )
            assertTrue(components.none { it.pixels.contentEquals(composition.pixels) })
        }
    }

    @Test
    fun `all active source animations parse and render as complete four-slot poses`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val draygon = DraygonSpritemap(parser)
        assertTrue(draygon.load())
        assertEquals(39, DraygonSpritemap.ANIMATIONS.size)

        var sourceFrameCount = 0
        DraygonSpritemap.ANIMATIONS.forEach { definition ->
            val source = assertNotNull(draygon.loadAnimation(definition), definition.sourceLabel)
            assertTrue(source.frames.isNotEmpty(), definition.sourceLabel)
            sourceFrameCount += source.frames.size

            val rendered = assertNotNull(draygon.renderAnimation(definition), definition.sourceLabel)
            assertEquals(source.frames.size, rendered.frames.size, definition.sourceLabel)
            assertEquals(definition.loop, rendered.loop, definition.sourceLabel)
            assertTrue(rendered.frames.all { it.width == rendered.frames.first().width }, definition.sourceLabel)
            assertTrue(rendered.frames.all { it.height == rendered.frames.first().height }, definition.sourceLabel)
            assertTrue(
                rendered.frames.all { frame -> frame.pixels.count { (it ushr 24) != 0 } > 1000 },
                definition.sourceLabel,
            )
        }
        assertEquals(250, sourceFrameCount)
    }

    @Test
    fun `health stages and hurt flash expose all source palettes`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val draygon = DraygonSpritemap(parser)
        assertTrue(draygon.load())

        val health = DraygonSpritemap.PALETTE_STAGES.map { stage ->
            assertNotNull(draygon.readPalette(stage), stage.name)
        }
        assertEquals(8, health.map { it.contentHashCode() }.distinct().size)

        val whiteFlash = assertNotNull(draygon.readPalette(DraygonSpritemap.WHITE_FLASH))
        assertTrue(health.none { it.contentEquals(whiteFlash) })
    }

    @Test
    fun `full health palette round trips the editable OBJ sheet byte exactly`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val draygon = DraygonSpritemap(parser)
        assertTrue(draygon.load())
        val raw = assertNotNull(draygon.getRawEnemyTileData())
        val palette = assertNotNull(draygon.readPalette(DraygonSpritemap.PALETTE_STAGES.first()))
        val gfx = EnemySpriteGraphics(parser)
        gfx.loadFromRaw(listOf(raw))
        val (pixels, width, height) = assertNotNull(gfx.renderSheet(palette, cols = 16))

        gfx.importFromArgb(pixels, width, height, palette, cols = 16)

        assertTrue(raw.contentEquals(assertNotNull(gfx.getRawBlocks()).single()))
    }
}
