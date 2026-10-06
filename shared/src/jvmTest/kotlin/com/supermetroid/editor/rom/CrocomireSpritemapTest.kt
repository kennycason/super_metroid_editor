package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrocomireSpritemapTest {

    private fun load(): Pair<RomParser, CrocomireSpritemap>? {
        val parser = TestRomHelper.loadRomParser() ?: return null
        val tiles = EnemySpriteGraphics.loadEnemyTileData(parser, CrocomireSpritemap.SPECIES_ID)
            ?: return null
        val renderer = CrocomireSpritemap(parser)
        assertTrue(renderer.load(tiles))
        return parser to renderer
    }

    @Test
    fun `all active instruction lists decode exact source frame counts`() {
        val (_, renderer) = load() ?: return
        val frames = CrocomireSpritemap.INSTRUCTION_LISTS.associateWith(renderer::sourceFrames)

        assertEquals(36, frames.size)
        assertEquals(233, frames.values.sumOf { it.size })
        assertEquals(92, frames.values.flatten().map { it.snesAddress }.toSet().size)
        frames.forEach { (definition, decoded) ->
            assertEquals(
                definition.expectedFrameCount,
                decoded.size,
                "${definition.name} must stay byte-bounded to its exact source list",
            )
        }
    }

    @Test
    fun `every guided animation renders every source frame`() {
        val (_, renderer) = load() ?: return

        assertEquals(20, CrocomireSpritemap.ANIMATIONS.size)
        CrocomireSpritemap.ANIMATIONS.forEach { definition ->
            val animation = renderer.renderAnimation(definition)
            assertNotNull(animation, definition.name)
            val rendered = animation ?: error(definition.name)
            assertEquals(definition.expectedFrameCount, rendered.frames.size, definition.name)
            assertTrue(rendered.frames.all { frame -> frame.pixels.any { it ushr 24 != 0 } }, definition.name)
        }
    }

    @Test
    fun `living melting and skeleton compositions render from their distinct sources`() {
        val (_, renderer) = load() ?: return

        CrocomireSpritemap.COMPOSITIONS.forEach { definition ->
            val image = renderer.renderComposition(definition)
            assertNotNull(image, definition.name)
            val rendered = image ?: error(definition.name)
            assertTrue(rendered.width > 0 && rendered.height > 0, definition.name)
            assertTrue(rendered.pixels.any { it ushr 24 != 0 }, definition.name)
        }

        CrocomireSpritemap.COMPONENTS.forEach { definition ->
            val image = renderer.renderComponent(definition)
            assertNotNull(image, definition.name)
            val rendered = image ?: error(definition.name)
            assertTrue(rendered.pixels.any { it ushr 24 != 0 }, definition.name)
        }
    }

    @Test
    fun `melting and skeleton graphics do not depend on the editable living OBJ payload`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val renderer = CrocomireSpritemap(parser)
        assertTrue(renderer.load(ByteArray(CrocomireSpritemap.BASE_TILES_SIZE)))

        val livingObj = renderer.renderComponent(
            CrocomireSpritemap.COMPONENTS.first { it.key == "head-limbs" },
        )
        val melting = renderer.renderComponent(
            CrocomireSpritemap.COMPONENTS.first { it.key == "melting" },
        )
        val skeleton = renderer.renderComponent(
            CrocomireSpritemap.COMPONENTS.first { it.key == "skeleton" },
        )
        assertNotNull(livingObj)
        assertNotNull(melting)
        assertNotNull(skeleton)

        assertEquals(0, livingObj!!.pixels.count { it ushr 24 != 0 })
        assertTrue(melting!!.pixels.count { it ushr 24 != 0 } > 500)
        assertTrue(skeleton!!.pixels.count { it ushr 24 != 0 } > 500)
    }

}
