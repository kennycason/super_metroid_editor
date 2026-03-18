package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class SamusRenderDiagnostic {
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
    fun `render standing pose to file`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val pose = decoder.getPose(0, 0) ?: return
        val palette = decoder.readPalette()
        val size = 96
        val pixels = decoder.renderPose(pose, palette, size, size)
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, size, size, pixels, 0, size)
        ImageIO.write(img, "png", File("/Users/kenny/code/super_metroid_dev/test-resources/samus_test_pose.png"))
    }

    @Test
    fun `render pose sheet to file`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val palette = decoder.readPalette()

        // Render one frame from each animation group
        val groups = SamusSpriteDecoder.ANIMATION_GROUPS
        val tileSize = 64
        val cols = groups.size.coerceAtMost(14)
        val rows = (groups.size + cols - 1) / cols
        val sheetW = cols * tileSize
        val sheetH = rows * tileSize
        val sheet = BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_ARGB)

        for ((gIdx, group) in groups.withIndex()) {
            val animId = group.animationIds.first()
            val pose = decoder.getPose(animId, 0) ?: continue
            val pixels = decoder.renderPose(pose, palette, tileSize, tileSize)
            val col = gIdx % cols
            val row = gIdx / cols
            sheet.setRGB(col * tileSize, row * tileSize, tileSize, tileSize, pixels, 0, tileSize)
        }

        ImageIO.write(sheet, "png", File("/Users/kenny/code/super_metroid_dev/test-resources/samus_poses_sheet.png"))
    }

    @Test
    fun `render standing variants to verify flip correctness`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val palette = decoder.readPalette()

        // Render all 9 standing variants (anim 0-8) frame 0 as a strip
        val tileSize = 64
        val standAnims = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
        val sheetW = standAnims.size * tileSize
        val sheet = BufferedImage(sheetW, tileSize, BufferedImage.TYPE_INT_ARGB)

        for ((idx, animId) in standAnims.withIndex()) {
            val pose = decoder.getPose(animId, 0) ?: continue
            val pixels = decoder.renderPose(pose, palette, tileSize, tileSize)
            sheet.setRGB(idx * tileSize, 0, tileSize, tileSize, pixels, 0, tileSize)
        }

        ImageIO.write(sheet, "png", File("/Users/kenny/code/super_metroid_dev/test-resources/samus_stand_variants.png"))
    }

    @Test
    fun `render run animation frames`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val palette = decoder.readPalette()

        // Render all frames of run animation 9 (first run variant)
        val animId = 9
        val frameCount = decoder.getFrameCount(animId)
        val tileSize = 48
        val cols = frameCount.coerceAtMost(10)
        val rows = (frameCount + cols - 1) / cols
        val sheet = BufferedImage(cols * tileSize, rows * tileSize, BufferedImage.TYPE_INT_ARGB)

        for (frame in 0 until frameCount) {
            val pose = decoder.getPose(animId, frame) ?: continue
            val pixels = decoder.renderPose(pose, palette, tileSize, tileSize)
            val col = frame % cols
            val row = frame / cols
            sheet.setRGB(col * tileSize, row * tileSize, tileSize, tileSize, pixels, 0, tileSize)
        }

        ImageIO.write(sheet, "png", File("/Users/kenny/code/super_metroid_dev/test-resources/samus_run_frames.png"))
    }
}
