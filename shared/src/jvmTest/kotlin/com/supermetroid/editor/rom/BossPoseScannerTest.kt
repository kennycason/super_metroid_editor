package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `Ridley has extended body poses`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val poses = scanner.scanPoses(0xE17F, minEntries = 4)

        assertTrue(poses.isNotEmpty(), "Ridley should have poses")
        assertTrue(poses.size >= 6, "Ridley should have multiple extended poses (found ${poses.size})")
        assertTrue(
            poses.any { it.frame is EnemySpritemap.RenderableFrame.Extended && it.childCount == 4 },
            "Ridley should expose real 4-child extended body frames"
        )
        assertTrue(poses.all { it.spritemap.entries.isNotEmpty() },
            "Ridley extended frames should flatten to renderable OAM entries")
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
    fun `Ridley extended body pose renders with good fill`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xE17F) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xE17F) ?: return

        val poses = scanner.scanPoses(0xE17F, minEntries = 4)
        val bodyPose = poses.find {
            it.frame is EnemySpritemap.RenderableFrame.Extended && it.childCount == 4
        }
        assertTrue(bodyPose != null, "Should find Ridley 4-part extended body pose")

        val rendered = scanner.renderPose(bodyPose!!, tileData, palette)
        assertTrue(rendered != null, "Extended body pose should render")
        assertTrue(rendered!!.width in 32..160 && rendered.height in 32..160,
            "Extended body should be reasonably bounded (${rendered.width}x${rendered.height})")

        val fillPct = rendered.pixels.count { (it ushr 24) > 0 } * 100 / (rendered.width * rendered.height)
        assertTrue(fillPct >= 10, "Extended body should have visible fill (${fillPct}%)")
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

    @Test
    fun `Crocomire extended poses use BG2 tilemap placement`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val poses = scanner.scanPoses(0xDDBF, minEntries = 3)
        val initialPose = poses.find { it.frame.snesAddress == 0xA4C2EC }
        assertTrue(initialPose != null, "Should find Crocomire initial extended pose")

        assertTrue(!initialPose!!.renderOptions.normalizeExtendedTilemaps,
            "Crocomire BG2 body should render in screen-relative tilemap coordinates")
        assertEquals(-0x33, initialPose.renderOptions.extendedTilemapOriginX)
        assertEquals(-0x43, initialPose.renderOptions.extendedTilemapOriginY)
        assertTrue(!initialPose.renderOptions.wrapExtendedTilemapTilePage,
            "Crocomire BG2 body should use room/enemy VRAM tile indices directly")
        assertEquals(EnemySpritemap.OamTileNumberMode.LOW_9, initialPose.renderOptions.oamTileNumberMode,
            "Crocomire OAM should use physical room VRAM tile IDs shared with the BG2 body")
        assertTrue(initialPose.usesCrocomireBgTileData,
            "Crocomire BG2 tilemaps should render from the room BG tileset, not the injected OAM tile buffer")
        assertTrue(initialPose.durationTicks > 0,
            "Known Crocomire poses should preserve instruction-list timing for animation playback")

        val stepBackPose = poses.find { it.frame.snesAddress == 0xA4BFF6 }
        assertTrue(stepBackPose != null, "Should find Crocomire step-back extended pose")
        assertEquals(-0x43 + 2, stepBackPose!!.renderOptions.extendedTilemapOriginY,
            "Step-back frames should apply Crocomire's per-frame BG2 Y adjustment")
    }

    @Test
    fun `Crocomire skeleton poses are discovered and render with skeleton tile data`() {
        val rp = loadTestRom() ?: return
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xDDBF) ?: return
        val rawTiles = EnemySpriteGraphics.loadEnemyTileData(rp, 0xDDBF) ?: return
        val renderTiles = EnemySpriteGraphics.loadEnemyRenderTileData(rp, 0xDDBF, rawTiles) ?: return
        val scanner = BossPoseScanner(rp)
        val poses = scanner.scanPoses(0xDDBF, minEntries = 3)

        val skeletonPoses = poses.filter { it.tileDataVariant == BossPoseScanner.TileDataVariant.CROCOMIRE_SKELETON }
        assertTrue(skeletonPoses.isNotEmpty(), "Crocomire corpse/skeleton instruction lists should be included")
        assertTrue(skeletonPoses.any { it.frame.snesAddress == 0xA4E1FE },
            "Skeleton falling frame should be parsed from InstList_CrocomireCorpse_Skeleton_Falling")

        val rendered = scanner.renderPose(skeletonPoses.first(), renderTiles, palette)
        assertNotNull(rendered, "Skeleton pose should render with the death-sequence tile overlay")
        assertTrue(rendered!!.width >= 48 && rendered.height >= 48,
            "Skeleton pose should render as a substantial corpse/skeleton assembly")
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
        val highPoses = scanner.scanPoses(0xE17F, minEntries = 1000)
        assertTrue(highPoses.size < allPoses.size,
            "Higher minEntries should reduce results (all=${allPoses.size}, high=${highPoses.size})")
    }
}
