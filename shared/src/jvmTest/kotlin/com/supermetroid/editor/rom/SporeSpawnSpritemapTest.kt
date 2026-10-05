package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SporeSpawnSpritemapTest {

    private fun load(): SporeSpawnSpritemap? {
        val parser = TestRomHelper.loadRomParser() ?: return null
        val tiles = EnemySpriteGraphics.loadEnemyTileData(parser, SporeSpawnSpritemap.SPECIES_ID)
            ?: return null
        return SporeSpawnSpritemap(parser).also { assertTrue(it.load(tiles)) }
    }

    @Test
    fun `all active instruction lists decode exact byte bounded source frames`() {
        val renderer = load() ?: return
        val frames = SporeSpawnSpritemap.INSTRUCTION_LISTS.associateWith(renderer::sourceFrames)

        assertEquals(9, frames.size)
        assertEquals(41, frames.values.sumOf { it.size })
        frames.forEach { (definition, decoded) ->
            assertEquals(definition.expectedFrameCount, decoded.size, definition.name)
        }
        assertEquals(
            (0..6).toList(),
            frames.entries.single { it.key.key == "death-harden" }.value.map { it.deathPaletteIndex },
        )
    }

    @Test
    fun `stalk segment positions reproduce signed runtime interpolation`() {
        val renderer = load() ?: return

        assertEquals(
            listOf(0x80 to 0x230, 0x80 to 0x210, 0x80 to 0x1F8, 0x80 to 0x1E0),
            renderer.stalkPositions(0x80, 0x1F0),
        )
        assertEquals(
            listOf(0x80 to 0x230, 0x70 to 0x230, 0x60 to 0x238, 0x50 to 0x240),
            renderer.stalkPositions(0x40, 0x270),
        )
        assertEquals(
            listOf(0x80 to 0x230, 0x90 to 0x230, 0xA0 to 0x238, 0xB0 to 0x240),
            renderer.stalkPositions(0xC0, 0x270),
        )
    }

    @Test
    fun `full compositions combine body and all four projectile stalk segments`() {
        val renderer = load() ?: return
        val stalkEntries = 4

        SporeSpawnSpritemap.COMPOSITIONS.forEach { definition ->
            val image = renderer.renderComposition(definition)
            assertNotNull(image, definition.name)
            val rendered = image ?: error(definition.name)
            assertTrue(rendered.width > 0 && rendered.height > 0, definition.name)
            assertTrue(rendered.pixels.any { it ushr 24 != 0 }, definition.name)
            val body = SporeSpawnSpritemap.COMPONENTS.firstOrNull {
                it.kind == SporeSpawnSpritemap.ComponentKind.BODY && it.snesAddress == definition.snesAddress
            }?.let(renderer::renderComponent)
            if (body != null) {
                assertEquals(body.spritemap.entries.size + stalkEntries, rendered.spritemap.entries.size, definition.name)
            }
        }
    }

    @Test
    fun `guided boss and projectile animations render every source frame`() {
        val renderer = load() ?: return

        SporeSpawnSpritemap.ANIMATIONS.forEach { definition ->
            val animation = renderer.renderAnimation(definition)
            assertNotNull(animation, definition.name)
            animation ?: error(definition.name)
            assertEquals(definition.expectedFrameCount, animation.frames.size, definition.name)
            assertTrue(animation.frames.all { frame -> frame.pixels.any { it ushr 24 != 0 } }, definition.name)
        }
        assertEquals(5, renderer.renderSpawnerAnimation()?.frames?.size)
        assertEquals(3, renderer.renderSporeAnimation()?.frames?.size)
    }

    @Test
    fun `every independently owned component is visible`() {
        val renderer = load() ?: return

        SporeSpawnSpritemap.COMPONENTS.forEach { definition ->
            val image = renderer.renderComponent(definition)
            assertNotNull(image, definition.name)
            image ?: error(definition.name)
            assertTrue(image.pixels.any { it ushr 24 != 0 }, definition.name)
        }
    }
}
