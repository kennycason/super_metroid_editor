package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceEnemyAnimationsTest {

    @Test
    fun `helper routed ordinary enemy actions render complete normalized frames`() {
        val parser = TestRomHelper.requireRomParser()
        val renderer = EnemySpritemap(parser)
        val expectedDefinitions = mapOf(
            0xCFBF to 5,
            0xD03F to 2,
            0xD3BF to 3,
            0xD87F to 8,
            0xD8BF to 8,
        )

        expectedDefinitions.forEach { (speciesId, definitionCount) ->
            val definitions = SourceEnemyAnimations.forSpecies(speciesId)
            assertEquals(definitionCount, definitions.size)
            val tiles = assertNotNull(EnemySpriteGraphics.loadEnemyTileData(parser, speciesId))
            val palette = assertNotNull(EnemySpriteGraphics.readEnemyPalette(parser, speciesId))
            definitions.forEach { definition ->
                val animation = assertNotNull(
                    renderer.buildSourceAnimation(definition, tiles, palette),
                    definition.name,
                )
                assertEquals(definition.expectedFramesPerList.sum(), animation.frames.size, definition.name)
                assertEquals(1, animation.frames.map { it.width to it.height }.distinct().size, definition.name)
                assertTrue(animation.frames.all { frame -> frame.pixels.any { it ushr 24 != 0 } }, definition.name)
            }
        }
    }

    @Test
    fun `source verified defaults promote every routed header to assembled preview`() {
        val parser = TestRomHelper.requireRomParser()
        val renderer = EnemySpritemap(parser)
        mapOf(
            0xCFBF to 4,
            0xD03F to 3,
            0xD3BF to 1,
            0xD87F to 4,
            0xD8BF to 4,
        ).forEach { (speciesId, frameCount) ->
            val tiles = assertNotNull(EnemySpriteGraphics.loadEnemyTileData(parser, speciesId))
            val palette = assertNotNull(EnemySpriteGraphics.readEnemyPalette(parser, speciesId))
            assertNotNull(renderer.findDefaultSpritemap(speciesId))
            assertEquals(frameCount, renderer.findAnimationFrames(speciesId).size)
            assertNotNull(renderer.buildAnimation(speciesId, tiles, palette))
        }
    }
}
