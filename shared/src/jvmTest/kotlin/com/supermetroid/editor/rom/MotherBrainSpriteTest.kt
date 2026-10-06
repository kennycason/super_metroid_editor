package com.supermetroid.editor.rom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotherBrainSpriteTest {

    @Test
    fun `body poses preserve the runtime BG2 center shifts`() {
        assertTrue(
            MotherBrainSpritemap.bodyRenderOptions().ignoreExtendedTilemapChildOffsets,
            "Mother Brain BG2 placement must use encoded destinations just like ProcessExtendedTilemap",
        )
        val centers = MotherBrainSpritemap.BODY_COMPONENTS.associate { it.key to it.bg2CenterX }
        assertEquals(0x20, centers["standing"])
        assertEquals(0x22, centers["walk-0"])
        assertEquals(0x26, centers["leaning"])
        assertEquals(0x20, centers["uncrouching"])
        assertEquals(0x20, centers["crouched"])

        val standUp = MotherBrainSpritemap.ANIMATIONS.first { it.key == "stand-up" }
        assertEquals(listOf(0x20, 0x26, 0x26, 0x20), standUp.bodyFrames.map { it.bg2CenterX })
        assertEquals(
            listOf(0 to 0, 0 to -10, 0 to -26, 0 to -38),
            standUp.bodyFrames.map { it.bodyOffsetX to it.bodyOffsetY },
            "stand-up should carry the source movement handlers into the whole assembly",
        )

        val walkForward = MotherBrainSpritemap.ANIMATIONS.first { it.key == "walk-forward" }
        assertEquals(
            listOf(0 to 0, 1 to -2, 3 to -2, 3 to -1, 6 to 0, 21 to -2, 27 to -6, 25 to -2, 24 to 0),
            walkForward.bodyFrames.map { it.bodyOffsetX to it.bodyOffsetY },
            "walking should move the torso, neck, and head with the body enemy origin",
        )
    }

    @Test
    fun `phase one and phase two compositions preserve their actual ownership split`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val motherBrain = MotherBrainSpritemap(parser)
        assertTrue(motherBrain.load())

        val phaseOne = assertNotNull(motherBrain.renderComposition(MotherBrainSpritemap.COMPOSITIONS[0]))
        val phaseTwo = assertNotNull(motherBrain.renderComposition(MotherBrainSpritemap.COMPOSITIONS[1]))
        assertTrue(phaseOne.pixels.count { (it ushr 24) != 0 } > 300)
        assertTrue(phaseTwo.width >= 90, "phase 2 should include the full torso, limbs, neck, and head")
        assertTrue(phaseTwo.height >= 100, "phase 2 should include the full torso, limbs, neck, and head")
        assertTrue(phaseTwo.pixels.count { (it ushr 24) != 0 } > 1200)
    }

    @Test
    fun `all named components and animations render`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val motherBrain = MotherBrainSpritemap(parser)
        assertTrue(motherBrain.load())

        MotherBrainSpritemap.HEAD_COMPONENTS.forEach { definition ->
            assertNotNull(motherBrain.renderHead(definition), definition.name)
        }
        MotherBrainSpritemap.BODY_COMPONENTS.forEach { definition ->
            assertNotNull(motherBrain.renderBody(definition), definition.name)
        }
        MotherBrainSpritemap.ANIMATIONS.forEach { definition ->
            val animation = assertNotNull(motherBrain.renderAnimation(definition), definition.name)
            assertEquals(definition.headFrames.size, animation.frames.size, definition.name)
            assertTrue(animation.frames.all { frame -> frame.pixels.any { (it ushr 24) != 0 } }, definition.name)
        }
    }
}
