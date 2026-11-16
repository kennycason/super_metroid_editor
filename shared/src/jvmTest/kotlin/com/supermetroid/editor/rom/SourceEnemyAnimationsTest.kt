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
            0xCEBF to 2,
            0xCFBF to 5,
            0xD03F to 2,
            0xD33F to 7,
            0xD3BF to 3,
            0xD4BF to 6,
            0xD87F to 8,
            0xD8BF to 8,
            0xD8FF to 2,
            0xD93F to 4,
            0xD97F to 4,
            0xD9BF to 4,
            0xD9FF to 4,
            0xDA3F to 4,
            0xE27F to 10,
            0xE63F to 5,
            0xE83F to 11,
            0xE87F to 8,
            0xE8FF to 2,
            0xE93F to 3,
            0xEABF to 13,
            0xEB3F to 13,
            0xEBBF to 13,
            0xED7F to 4,
            0xEDBF to 4,
        )

        expectedDefinitions.forEach { (speciesId, definitionCount) ->
            val definitions = SourceEnemyAnimations.forSpecies(speciesId)
            assertEquals(definitionCount, definitions.size)
            val tiles = assertNotNull(EnemySpriteGraphics.loadStandardOamRenderTileData(parser, speciesId))
            val palette = assertNotNull(
                EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, speciesId)?.colors,
            )
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
            0xCEBF to 4,
            0xCFBF to 4,
            0xD03F to 3,
            0xD33F to 4,
            0xD3BF to 1,
            0xD4BF to 1,
            0xD87F to 4,
            0xD8BF to 4,
            0xD8FF to 4,
            0xD93F to 4,
            0xD97F to 4,
            0xD9BF to 4,
            0xD9FF to 4,
            0xDA3F to 4,
            0xE27F to 1,
            0xE63F to 6,
            0xE67F to 1,
            0xE83F to 4,
            0xE87F to 4,
            0xEABF to 3,
            0xEAFF to 3,
            0xEB3F to 3,
            0xEB7F to 3,
            0xEBBF to 3,
            0xEBFF to 3,
            0xE8FF to 1,
            0xE93F to 1,
            0xED7F to 1,
            0xEDBF to 1,
        ).forEach { (speciesId, frameCount) ->
            val tiles = assertNotNull(EnemySpriteGraphics.loadStandardOamRenderTileData(parser, speciesId))
            val palette = assertNotNull(
                EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, speciesId)?.colors,
            )
            assertNotNull(renderer.findDefaultSpritemap(speciesId))
            assertEquals(frameCount, renderer.findAnimationFrames(speciesId).size)
            assertNotNull(renderer.buildAnimation(speciesId, tiles, palette))
        }
    }

    @Test
    fun `kihunter wing actions render on their full body context`() {
        val parser = TestRomHelper.requireRomParser()
        val renderer = EnemySpritemap(parser)
        val tiles = assertNotNull(EnemySpriteGraphics.loadStandardOamRenderTileData(parser, 0xEABF))
        val palette = assertNotNull(EnemySpriteGraphics.readEnemyPalette(parser, 0xEABF))

        SourceEnemyAnimations.forSpecies(0xEABF)
            .filter { it.key.startsWith("kihunter-wings-") }
            .forEach { definition ->
                assertNotNull(definition.contextInstructionList, definition.name)
                val rawWing = assertNotNull(
                    renderer.traceInstructionListAt(definition.instructionLists.first(), 1)
                        .frames.firstOrNull()
                        ?.let { renderer.renderRenderableFrame(it.renderableFrame, tiles, palette) },
                    definition.name,
                )
                val composite = assertNotNull(
                    renderer.buildSourceAnimation(definition, tiles, palette),
                    definition.name,
                ).frames.first()
                val rawBody = assertNotNull(
                    renderer.traceInstructionListAt(
                        assertNotNull(definition.contextInstructionList),
                        1,
                    ).frames.firstOrNull()
                        ?.let { renderer.renderRenderableFrame(it.renderableFrame, tiles, palette) },
                    definition.name,
                )
                val compositeOpaque = composite.pixels.count { it ushr 24 != 0 }
                assertTrue(
                    compositeOpaque > rawWing.pixels.count { it ushr 24 != 0 },
                    "${definition.name} should include visible body pixels",
                )
                assertTrue(
                    compositeOpaque > rawBody.pixels.count { it ushr 24 != 0 },
                    "${definition.name} should retain visible wing pixels",
                )
            }
    }

    @Test
    fun `reported multi-slot enemies retain every visible component`() {
        val parser = TestRomHelper.requireRomParser()
        val renderer = EnemySpritemap(parser)

        listOf("dragon-idle-left", "holtz-flames").forEach { key ->
            val definition = SourceEnemyAnimations.DEFINITIONS.single { it.key == key }
            val tiles = assertNotNull(
                EnemySpriteGraphics.loadStandardOamRenderTileData(parser, definition.speciesId),
            )
            val palette = assertNotNull(
                EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, definition.speciesId)?.colors,
            )
            val actionMap = renderer.traceInstructionListAt(definition.instructionLists.first(), 1)
                .frames.first().renderableFrame
            val contextMap = renderer.traceInstructionListAt(
                assertNotNull(definition.contextInstructionList),
                1,
            ).frames.first().renderableFrame
            val action = assertNotNull(renderer.renderRenderableFrame(actionMap, tiles, palette))
            val context = assertNotNull(renderer.renderRenderableFrame(contextMap, tiles, palette))
            val composite = assertNotNull(renderer.buildSourceAnimation(definition, tiles, palette)).frames.first()
            val opaque = composite.pixels.count { it ushr 24 != 0 }
            assertTrue(opaque > action.pixels.count { it ushr 24 != 0 }, "$key needs its context")
            assertTrue(opaque > context.pixels.count { it ushr 24 != 0 }, "$key needs its action layer")

            if (key == "dragon-idle-left") {
                val preview = assertNotNull(
                    renderer.renderSpecialEnemyPreview(definition.speciesId, tiles, palette),
                )
                val previewOpaque = preview.pixels.count { it ushr 24 != 0 }
                assertTrue(previewOpaque > action.pixels.count { it ushr 24 != 0 })
                assertTrue(previewOpaque > context.pixels.count { it ushr 24 != 0 })
            }
        }
    }

    @Test
    fun `boyon and work robots stay inside their native list boundaries`() {
        val parser = TestRomHelper.requireRomParser()
        val renderer = EnemySpritemap(parser)

        assertEquals(4, renderer.traceInstructionListAt(0xA286AB, 32).frames.size)
        assertEquals(6, renderer.traceInstructionListAt(0xA286C3, 32).frames.size)
        val robot = renderer.traceInstructionListAt(0xA8C6E5, 1).frames.single().spritemap
        assertEquals(12, robot.entries.size)
        val deactivated = renderer.traceInstructionListAt(0xA8C6D3, 1).frames.single().spritemap
        assertEquals(6, deactivated.entries.size)
        assertEquals(0xA8D7E1, deactivated.snesAddress)
    }
}
