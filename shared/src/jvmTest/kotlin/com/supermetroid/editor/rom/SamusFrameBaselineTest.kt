package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Dumps every Samus animation frame as PNGs for cross-branch comparison.
 * Output goes to /tmp/samus_frames_{branch}/ with naming:
 *   group{G}_anim{A}_frame{F}.png
 *
 * Run on main, then on feature branch, then diff the directories.
 */
class SamusFrameBaselineTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        return null
    }

    @Test
    fun `dump all samus frames for baseline comparison`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val palette = decoder.readPalette(SamusSpriteDecoder.SuitType.POWER)
        val renderSize = 96

        val outDir = File("/tmp/samus_frames_current")
        outDir.mkdirs()

        var totalFrames = 0
        var emptyFrames = 0
        var nullFrames = 0

        val report = StringBuilder()
        report.appendLine("SAMUS FRAME BASELINE REPORT")
        report.appendLine("=" .repeat(80))

        for ((gIdx, group) in SamusSpriteDecoder.ANIMATION_GROUPS.withIndex()) {
            for ((aIdx, animId) in group.animationIds.withIndex()) {
                val frameCount = decoder.getFrameCount(animId)
                val hexId = "0x${animId.toString(16).uppercase().padStart(2, '0')}"

                var validCount = 0
                var emptyCount = 0
                var nullCount = 0

                for (f in 0 until frameCount) {
                    val pose = decoder.getPose(animId, f)
                    if (pose == null) {
                        nullCount++
                        nullFrames++
                        totalFrames++
                        continue
                    }

                    val pixels = decoder.renderPose(pose, palette, renderSize, renderSize)
                    val nonTrans = pixels.count { (it ushr 24) > 0 }

                    totalFrames++
                    if (nonTrans == 0) {
                        emptyCount++
                        emptyFrames++
                        continue
                    }

                    validCount++

                    // Save PNG
                    val fname = "g${gIdx}_a${aIdx}_anim${hexId}_f${f}.png"
                    val bi = BufferedImage(renderSize, renderSize, BufferedImage.TYPE_INT_ARGB)
                    bi.setRGB(0, 0, renderSize, renderSize, pixels, 0, renderSize)
                    ImageIO.write(bi, "png", File(outDir, fname))
                }

                report.appendLine("${group.name} anim=$hexId: getFrameCount=$frameCount valid=$validCount empty=$emptyCount null=$nullCount")
            }
        }

        report.appendLine()
        report.appendLine("TOTALS: total=$totalFrames valid=${totalFrames - emptyFrames - nullFrames} empty=$emptyFrames null=$nullFrames")

        val reportFile = File(outDir, "REPORT.txt")
        reportFile.writeText(report.toString())
        println(report)
    }
}
