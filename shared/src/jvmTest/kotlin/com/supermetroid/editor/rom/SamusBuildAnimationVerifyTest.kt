package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies that buildAnimation() produces the exact same frame count
 * as the raw getPose() + renderPose() pipeline (the baseline truth).
 *
 * If this test fails, buildAnimation is either dropping valid frames
 * or including garbage frames.
 */
class SamusBuildAnimationVerifyTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `buildAnimation frame count matches raw valid frame count for every animation`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val palette = decoder.readPalette(SamusSpriteDecoder.SuitType.POWER)

        var mismatches = 0

        for (group in SamusSpriteDecoder.ANIMATION_GROUPS) {
            for (animId in group.animationIds) {
                // Count valid frames the raw way (same as baseline test)
                val claimed = decoder.getFrameCount(animId)
                var rawValid = 0
                for (f in 0 until claimed) {
                    val pose = decoder.getPose(animId, f) ?: continue
                    val pixels = decoder.renderPose(pose, palette, 64, 64)
                    val nonTrans = pixels.count { (it ushr 24) > 0 }
                    if (nonTrans > 0) rawValid++
                }

                // Count frames via buildAnimation
                val anim = decoder.buildAnimation(animId, SamusSpriteDecoder.SuitType.POWER, renderSize = 64)
                val builtCount = anim?.frames?.size ?: 0

                val hexId = "0x${animId.toString(16).uppercase()}"
                if (rawValid != builtCount) {
                    println("MISMATCH ${group.name} $hexId: raw=$rawValid built=$builtCount")
                    mismatches++
                }
            }
        }

        assertEquals(0, mismatches, "$mismatches animations have mismatched frame counts")
    }
}
