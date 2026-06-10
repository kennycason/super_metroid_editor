package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RidleySpritemapTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `Ridley loads multiple poses`() {
        val rp = loadTestRom() ?: return
        val ridley = RidleySpritemap(rp)
        val poses = ridley.loadPoses()

        assertTrue(poses.isNotEmpty(), "Should find at least one Ridley pose")
        assertTrue(poses.size >= 6, "Should find multiple extended poses (found ${poses.size})")

        val extendedPoses = poses.filter { it.frame is EnemySpritemap.RenderableFrame.Extended }
        assertTrue(extendedPoses.isNotEmpty(), "Should have extended body poses")
    }

    @Test
    fun `Ridley poses render with visible pixels`() {
        val rp = loadTestRom() ?: return
        val ridley = RidleySpritemap(rp)
        val speciesId = RidleySpritemap.RIDLEY_SPECIES_ID

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return
        val poses = ridley.loadPoses()

        for (pose in poses.take(10)) {
            val assembled = ridley.renderPose(pose, tileData, palette)
            assertNotNull(assembled, "${pose.name} should render")

            val filled = assembled!!.pixels.count { (it ushr 24) > 0 }
            assertTrue(filled > 0, "${pose.name} should have visible pixels")
            assertTrue(assembled.width > 0 && assembled.height > 0,
                "${pose.name} should have positive dimensions")
        }
    }

    @Test
    fun `Ridley auto-crop removes empty space`() {
        val rp = loadTestRom() ?: return
        val ridley = RidleySpritemap(rp)
        val speciesId = RidleySpritemap.RIDLEY_SPECIES_ID

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return
        val poses = ridley.loadPoses()

        val bodyPose = poses.firstOrNull { it.frame is EnemySpritemap.RenderableFrame.Extended } ?: return
        val assembled = ridley.renderPose(bodyPose, tileData, palette) ?: return

        assertTrue(assembled.width < 200,
            "Auto-cropped body pose should be < 200px wide (was ${assembled.width})")
        assertTrue(assembled.height < 200,
            "Auto-cropped body pose should be < 200px tall (was ${assembled.height})")

        val filled = assembled.pixels.count { (it ushr 24) > 0 }
        val fillPct = (filled * 100) / (assembled.width * assembled.height)
        assertTrue(fillPct > 10,
            "Auto-cropped pose should have > 10% fill (was $fillPct%)")
    }

    @Test
    fun `Ceres Ridley uses same poses as Ridley`() {
        val rp = loadTestRom() ?: return
        val ridley = RidleySpritemap(rp)

        val ridleyPoses = ridley.loadPoses(RidleySpritemap.RIDLEY_SPECIES_ID)
        val ceresPoses = ridley.loadPoses(RidleySpritemap.CERES_RIDLEY_SPECIES_ID)

        // Both should find poses (same AI bank)
        assertTrue(ridleyPoses.isNotEmpty(), "Ridley should have poses")
        assertTrue(ceresPoses.isNotEmpty(), "Ceres Ridley should have poses")
        // Same AI bank means same poses
        assertTrue(ceresPoses.size == ridleyPoses.size,
            "Ceres Ridley should have same pose count as Ridley")
    }
}
