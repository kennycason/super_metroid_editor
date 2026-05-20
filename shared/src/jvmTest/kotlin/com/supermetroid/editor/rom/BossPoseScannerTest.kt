package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for BossPoseScanner — verifies pose filtering, cross-contamination
 * prevention, and rendering quality across all bosses.
 */
class BossPoseScannerTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    // ─── Pose discovery tests ────────────────────────────────────────

    @Test
    fun `Ridley has compact poses with consistent palette`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val poses = scanner.scanPoses(0xE17F, minEntries = 4)

        assertTrue(poses.isNotEmpty(), "Ridley should have poses")
        assertTrue(poses.size >= 10, "Ridley should have 10+ poses (found ${poses.size})")

        // All poses should use at most 2 palette rows (single-enemy filter)
        for (pose in poses) {
            val palRows = pose.spritemap.entries.map { it.palRow }.toSet()
            assertTrue(palRows.size <= 2,
                "Pose '${pose.name}' has ${palRows.size} palette rows $palRows — should be <=2 (cross-contamination)")
        }
    }

    @Test
    fun `Ridley poses have no out-of-range tile indices`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val rom = rp.getRomData()

        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or 0xE17F)
        val rawTileSize = (rom[headerPc].toInt() and 0xFF) or ((rom[headerPc + 1].toInt() and 0xFF) shl 8)
        val tileCount = (rawTileSize and 0x7FFF) / 32

        val poses = scanner.scanPoses(0xE17F, minEntries = 3)
        for (pose in poses) {
            for (entry in pose.spritemap.entries) {
                val localTile = entry.tileNum and 0xFF
                assertTrue(localTile < tileCount,
                    "Pose '${pose.name}' entry tile=$localTile exceeds tileCount=$tileCount")
            }
        }
    }

    @Test
    fun `Ridley head pose renders with good fill`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xE17F) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xE17F) ?: return

        val poses = scanner.scanPoses(0xE17F, minEntries = 10)
        // Find the compact head pose (56x56, 15 entries)
        val headPose = poses.find { it.entryCount == 15 }
        assertTrue(headPose != null, "Should find Ridley head pose with 15 OAM entries")

        val rendered = scanner.renderPose(headPose!!, tileData, palette)
        assertTrue(rendered != null, "Head pose should render")
        assertTrue(rendered!!.width <= 60 && rendered.height <= 60,
            "Head pose should be compact (${rendered.width}x${rendered.height})")

        val fillPct = rendered.pixels.count { (it ushr 24) > 0 } * 100 / (rendered.width * rendered.height)
        assertTrue(fillPct >= 30, "Head should have good fill (${fillPct}%)")
    }

    // ─── Cross-contamination prevention ──────────────────────────────

    @Test
    fun `Mini Kraid poses are not contaminated by Ridley data`() {
        // Mini Kraid ($E0FF) shares AI bank $A6 with Ridley ($E17F)
        // Scanner must filter out Ridley's spritemaps
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)

        val poses = scanner.scanPoses(0xE0FF, minEntries = 3)
        // Mini Kraid has 128 tiles — no tile should exceed that
        for (pose in poses) {
            for (entry in pose.spritemap.entries) {
                val localTile = entry.tileNum and 0xFF
                assertTrue(localTile < 128,
                    "Mini Kraid pose '${pose.name}' tile=$localTile exceeds 128 tile limit")
            }
        }
    }

    @Test
    fun `Mother Brain P1 has good full-body pose`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xEC3F) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xEC3F) ?: return

        val poses = scanner.scanPoses(0xEC3F, minEntries = 3)
        assertTrue(poses.isNotEmpty(), "Mother Brain P1 should have poses")

        val best = poses.first()
        assertTrue(best.entryCount >= 10, "Best pose should have 10+ entries (has ${best.entryCount})")

        val rendered = scanner.renderPose(best, tileData, palette)
        assertTrue(rendered != null, "Should render")
        // Brain spritemaps are compact (~48x53) with good fill
        assertTrue(rendered!!.width <= 80, "Brain should be compact width (${rendered.width})")
        val fillPct = rendered.pixels.count { (it ushr 24) > 0 } * 100 / (rendered.width * rendered.height)
        assertTrue(fillPct >= 30, "Mother Brain brain should have good fill (${fillPct}%)")
    }

    @Test
    fun `Torizo has compact body poses`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xEEFF) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xEEFF) ?: return

        val poses = scanner.scanPoses(0xEEFF, minEntries = 8)
        assertTrue(poses.isNotEmpty(), "Torizo should have poses")

        // Find compact poses (body within 100x100)
        val compactPoses = poses.filter { pose ->
            val rendered = scanner.renderPose(pose, tileData, palette)
            rendered != null && rendered.width <= 100 && rendered.height <= 100
        }
        assertTrue(compactPoses.size >= 3,
            "Torizo should have at least 3 compact body poses (found ${compactPoses.size})")
    }

    @Test
    fun `Botwoon has renderable head poses`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xF293) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xF293) ?: return

        val poses = scanner.scanPoses(0xF293, minEntries = 3)
        assertTrue(poses.isNotEmpty(), "Botwoon should have poses")

        // Best pose should render with decent fill
        val best = poses.first()
        val rendered = scanner.renderPose(best, tileData, palette)
        assertTrue(rendered != null, "Best pose should render")
        val fillPct = rendered!!.pixels.count { (it ushr 24) > 0 } * 100 / (rendered.width * rendered.height)
        assertTrue(fillPct >= 20, "Botwoon best pose should have decent fill (${fillPct}%)")
    }

    @Test
    fun `Crocomire poses found`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val poses = scanner.scanPoses(0xDDBF, minEntries = 3)
        assertTrue(poses.isNotEmpty(), "Crocomire should have poses (mouth/claw fragments)")
    }

    // ─── Edge cases ──────────────────────────────────────────────────

    @Test
    fun `scanner handles invalid species ID gracefully`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val poses = scanner.scanPoses(0xFFFF, minEntries = 3)
        assertEquals(0, poses.size, "Invalid species should return empty list")
    }

    @Test
    fun `scanner with high minEntries reduces results`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val allPoses = scanner.scanPoses(0xE17F, minEntries = 3)
        val highPoses = scanner.scanPoses(0xE17F, minEntries = 15)
        assertTrue(highPoses.size < allPoses.size,
            "Higher minEntries should reduce results (all=${allPoses.size}, high=${highPoses.size})")
    }
}
