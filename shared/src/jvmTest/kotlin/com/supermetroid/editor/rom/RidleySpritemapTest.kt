package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RidleySpritemapTest {

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
    fun `Ridley loads multiple poses`() {
        val rp = loadTestRom() ?: return
        val ridley = RidleySpritemap(rp)
        val poses = ridley.loadPoses()

        assertTrue(poses.isNotEmpty(), "Should find at least one Ridley pose")
        assertTrue(poses.size >= 10, "Should find at least 10 poses (found ${poses.size})")

        // Should have at least one large pose (full body or body section)
        val largePoses = poses.filter { it.entryCount >= 10 }
        assertTrue(largePoses.isNotEmpty(), "Should have at least one pose with 10+ OAM entries")
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

        // A medium pose (body section, not full body) should auto-crop to reasonable sizes
        val medPose = poses.firstOrNull { it.entryCount in 10..16 } ?: return
        val assembled = ridley.renderPose(medPose, tileData, palette) ?: return

        // Body sections should be well under 200px after auto-crop
        assertTrue(assembled.width < 200,
            "Auto-cropped body section should be < 200px wide (was ${assembled.width})")
        assertTrue(assembled.height < 200,
            "Auto-cropped body section should be < 200px tall (was ${assembled.height})")

        // Fill percentage should be reasonable (> 10%)
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
