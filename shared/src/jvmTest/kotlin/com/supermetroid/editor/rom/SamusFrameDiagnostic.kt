package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Diagnostic test to identify frame loss and overcounting in Samus animations.
 * Not a regression test — used for analysis.
 */
class SamusFrameDiagnostic {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `diagnose all Samus animation frame counts`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)

        var totalAnims = 0
        var overcountAnims = 0
        var nullFrameAnims = 0

        for (group in SamusSpriteDecoder.ANIMATION_GROUPS) {
            println("\n=== ${group.name} (${group.description}) ===")
            for (animId in group.animationIds) {
                val claimed = decoder.getFrameCount(animId)
                var validPoses = 0
                var nullPoses = 0
                var emptyPoses = 0

                val palette = decoder.readPalette(SamusSpriteDecoder.SuitType.POWER)

                for (f in 0 until claimed) {
                    val pose = decoder.getPose(animId, f)
                    if (pose == null) {
                        nullPoses++
                    } else {
                        val pixels = decoder.renderPose(pose, palette, 64, 64)
                        val nonTrans = pixels.count { (it ushr 24) > 0 }
                        if (nonTrans == 0) {
                            emptyPoses++
                        } else {
                            validPoses++
                        }
                    }
                }

                val hexId = "0x${animId.toString(16).uppercase()}"
                val status = when {
                    nullPoses > 0 -> "FRAME_LOSS"
                    emptyPoses > 0 -> "EMPTY_FRAMES"
                    else -> "OK"
                }

                if (status != "OK" || claimed > 20) {
                    println("  Anim $hexId: claimed=$claimed valid=$validPoses null=$nullPoses empty=$emptyPoses [$status]")
                }

                totalAnims++
                if (nullPoses > 0) nullFrameAnims++
                if (claimed > validPoses + nullPoses) overcountAnims++
            }
        }

        println("\n=== SUMMARY ===")
        println("Total animations checked: $totalAnims")
        println("Animations with null frames (FRAME_LOSS): $nullFrameAnims")
        println("Animations with overcount: $overcountAnims")
    }

    @Test
    fun `diagnose Crystal Flash specifically`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)

        val animId = 0xDB
        val claimed = decoder.getFrameCount(animId)
        println("Crystal Flash (0xDB): getFrameCount = $claimed")

        val palette = decoder.readPalette(SamusSpriteDecoder.SuitType.POWER)

        for (f in 0 until claimed.coerceAtMost(20)) {
            val pose = decoder.getPose(animId, f)
            if (pose == null) {
                println("  Frame $f: NULL (getPose returned null)")
            } else {
                val pixels = decoder.renderPose(pose, palette, 64, 64)
                val nonTrans = pixels.count { (it ushr 24) > 0 }
                println("  Frame $f: ${pose.tilemaps.size} tilemaps, $nonTrans visible pixels")
            }
        }
    }

    @Test
    fun `compare buildAnimation frame count vs getFrameCount`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)

        println("Comparing getFrameCount() vs buildAnimation() actual frames:")
        println("%-30s %8s %8s %8s".format("Animation", "Claimed", "Built", "Delta"))
        println("-".repeat(60))

        for (group in SamusSpriteDecoder.ANIMATION_GROUPS) {
            for (animId in group.animationIds) {
                val claimed = decoder.getFrameCount(animId)
                val anim = decoder.buildAnimation(animId, renderSize = 32)
                val built = anim?.frames?.size ?: 0

                if (claimed != built) {
                    val hexId = "0x${animId.toString(16).uppercase()}"
                    val name = "${group.name} $hexId"
                    println("%-30s %8d %8d %8d".format(name, claimed, built, built - claimed))
                }
            }
        }
    }
}
