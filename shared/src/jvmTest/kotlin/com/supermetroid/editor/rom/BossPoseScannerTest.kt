package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `Mother Brain P2 renders body with room tilemaps and runtime leg tiles`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xEC7F) ?: return
        val rawTiles = EnemySpriteGraphics.loadEnemyTileData(rp, 0xEC7F) ?: return
        val renderTiles = EnemySpriteGraphics.loadEnemyRenderTileData(rp, 0xEC7F, rawTiles) ?: return

        val poses = scanner.scanPoses(0xEC7F, minEntries = 3)
        val standing = poses.firstOrNull { it.frame.snesAddress == 0xA99FA0 }
        assertNotNull(standing, "Mother Brain P2 should expose the standing extended body frame")
        assertTrue(standing!!.usesMotherBrainBgTileData,
            "Mother Brain P2 torso tilemaps should render from the room BG tileset")
        assertEquals(EnemySpritemap.OamTileNumberMode.LOW_9, standing.renderOptions.oamTileNumberMode,
            "Mother Brain P2 limbs use physical low-9-bit OBJ tile IDs")
        assertEquals(0x10, standing.renderOptions.extendedTilemapOriginX,
            "Mother Brain P2 BG torso should be shifted forward relative to the legs")
        assertEquals(-0x10, standing.renderOptions.extendedTilemapOriginY,
            "Mother Brain P2 BG torso should be raised relative to the legs")
        assertEquals(0x31, standing.renderOptions.extendedOamOriginX,
            "Mother Brain P2 extended OAM limbs should align with the BG torso")
        assertEquals(0x15, standing.renderOptions.extendedOamOriginY,
            "Mother Brain P2 extended OAM limbs should attach at the lower torso")
        assertTrue(standing.renderOptions.preserveExtendedChildDrawOrder,
            "Mother Brain P2 should draw rear limbs, BG torso, then front limbs")
        assertEquals(9, standing.childCount,
            "Mother Brain P2 standing frame should preserve all multibox children")
        assertEquals(2, standing.tilemapCount,
            "Mother Brain P2 standing frame should include torso BG2 tilemaps")

        val sourceTiles = EnemySpriteGraphics.loadMotherBrainBodySourceTileData(rp, rawTiles)
        assertNotNull(sourceTiles, "Mother Brain P2 source sheet should include runtime body sources")
        val sourceTileCount = sourceTiles!!.size / EnemySpriteGraphics.BYTES_PER_TILE
        assertEquals(
            0xA0 + 0x80 + 0x80 + rawTiles.size / EnemySpriteGraphics.BYTES_PER_TILE,
            sourceTileCount,
            "Mother Brain P2 source sheet should include room body, head DMA, leg DMA, and raw supplement tiles"
        )

        val rendered = scanner.renderPose(standing, renderTiles, palette)
        assertNotNull(rendered, "Mother Brain P2 standing frame should render")
        assertTrue(rendered!!.width >= 90 && rendered.height >= 90,
            "Mother Brain P2 body should render as a large assembly (${rendered.width}x${rendered.height})")
        assertTrue(rendered.pixels.count { (it ushr 24) > 0 } > 1000,
            "Mother Brain P2 body should have substantial visible pixels")
    }

    @Test
    fun `Torizo has compact body poses`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        for (speciesId in listOf(0xEEFF, 0xEF7F)) {
            val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return

            val poses = scanner.scanPoses(speciesId, minEntries = 8)
            assertTrue(poses.isNotEmpty(), "Torizo species ${speciesId.toString(16)} should have poses")
            assertTrue(poses.first().frame is EnemySpritemap.RenderableFrame.Extended,
                "Torizo body poses should come from bank-AA extended spritemaps, not generic child OAM")
            assertEquals(0xAAA4F0, poses.first().frame.snesAddress,
                "First Torizo body pose should be the facing-screen extended spritemap")
            assertTrue(poses.first().renderOptions.oamPaletteRows.keys.containsAll(listOf(1, 2)),
                "Torizo body poses should render with both runtime sprite palette rows")

            val firstRendered = scanner.renderPose(poses.first(), tileData, palette)
            assertNotNull(firstRendered, "First Torizo body pose should render")
            assertTrue(firstRendered!!.width <= 120 && firstRendered.height <= 120,
                "First Torizo pose should be compact (${firstRendered.width}x${firstRendered.height})")

            val compactPoses = poses.filter { pose ->
                val rendered = scanner.renderPose(pose, tileData, palette)
                rendered != null && rendered.width <= 120 && rendered.height <= 120
            }
            assertTrue(compactPoses.size >= 3,
                "Torizo should have at least 3 compact body poses (found ${compactPoses.size})")
        }
    }

    @Test
    fun `Torizo orb variants render projectile spritemaps`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)

        for (speciesId in listOf(0xEF3F, 0xEFBF)) {
            val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return
            val poses = scanner.scanPoses(speciesId, minEntries = 3)

            assertTrue(poses.isNotEmpty(), "Torizo orb species ${speciesId.toString(16)} should have projectile poses")
            assertTrue(poses.first().entryCount <= 2,
                "First orb pose should be a compact projectile, not Torizo body OAM")

            val rendered = scanner.renderPose(poses.first(), tileData, palette)
            assertNotNull(rendered, "First orb pose should render")
            assertTrue(rendered!!.width <= 24 && rendered.height <= 24,
                "First orb pose should be orb-sized (${rendered.width}x${rendered.height})")
            assertTrue(rendered.pixels.count { (it ushr 24) > 0 } > 0,
                "Orb pose should have visible pixels")
        }
    }

    @Test
    fun `Torizo variants use display-specific palettes`() {
        val rp = loadTestRom() ?: return

        val body = EnemySpriteGraphics.readEnemyPalette(rp, 0xEEFF) ?: return
        val orbs = EnemySpriteGraphics.readEnemyPalette(rp, 0xEF3F) ?: return
        val goldBody = EnemySpriteGraphics.readEnemyPalette(rp, 0xEF7F) ?: return

        assertFalse(body.contentEquals(orbs),
            "Bomb Torizo body should not use the orb projectile palette")
        assertFalse(goldBody.contentEquals(body),
            "Gold Torizo should use the gold runtime palette")
        assertFalse(goldBody.contentEquals(orbs),
            "Gold Torizo body should not use the orb projectile palette")
    }

    @Test
    fun `Torizo corpse uses static assembled preview only`() {
        assertFalse(BossPoseScanner.hasKnownPoses(0xED3F),
            "Torizo corpse should not be sent through live Torizo pose scanning")
        assertTrue(BossPoseScanner.usesStaticAssembledPreviewOnly(0xED3F),
            "Torizo corpse should keep the good static default spritemap preview")
    }

    @Test
    fun `Botwoon has renderable head poses`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xF293) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xF293) ?: return

        val head = smap.findDefaultSpritemap(0xF293)
        assertNotNull(head, "Botwoon should still expose its normal head spritemap")

        val rendered = smap.renderSpritemap(head!!, tileData, palette)
        assertTrue(rendered != null, "Best pose should render")
        val fillPct = rendered!!.pixels.count { (it ushr 24) > 0 } * 100 / (rendered.width * rendered.height)
        assertTrue(fillPct >= 20, "Botwoon head should have decent fill (${fillPct}%)")
    }

    @Test
    fun `Botwoon body poses include projectile body and tail segments`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xF293) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xF293) ?: return

        val poses = scanner.scanPoses(0xF293, minEntries = 3)
        val right = poses.firstOrNull { it.name == "Full Body Right" }
        assertNotNull(right, "Botwoon should expose a full right-facing body pose")
        assertEquals(14, right!!.childCount,
            "Botwoon composite should include the head plus 13 body/tail projectile segments")
        assertTrue(right.entryCount >= 15,
            "Botwoon full pose should include head entries and all body/tail segment entries")
        assertTrue(right.spritemap.entries.any { (it.tileNum and 0x1FF) in 0x1A0..0x1A7 },
            "Botwoon full pose should include tail projectile tiles")

        val rendered = scanner.renderPose(right, tileData, palette)
        assertNotNull(rendered, "Botwoon full body pose should render")
        assertTrue(rendered!!.width >= 160 || rendered.height >= 160,
            "Botwoon full body should be substantially larger than the head (${rendered.width}x${rendered.height})")
        assertTrue(rendered.pixels.count { (it ushr 24) > 0 } > 900,
            "Botwoon full body should have substantial visible pixels")
    }

    @Test
    fun `Spore Spawn known poses include open and closed body frames`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xDF3F) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xDF3F) ?: return

        val poses = scanner.scanPoses(0xDF3F, minEntries = 3)
        assertTrue(poses.size >= 6, "Spore Spawn should expose closed/open/death poses")
        assertTrue(poses.any { it.frame.snesAddress == 0xA5EE6F },
            "Spore Spawn closed frame should be parsed from the known instruction lists")
        assertTrue(poses.any { it.frame.snesAddress == 0xA5EF3D },
            "Spore Spawn fully-open frame should be parsed from the known instruction lists")
        assertTrue(poses.any { it.childCount == 2 },
            "Spore Spawn opening/open frames should combine shell and mouth spritemaps")
        assertTrue(poses.filter { it.frame is EnemySpritemap.RenderableFrame.Extended }
            .all { it.renderOptions.reverseExtendedOamDrawOrder },
            "Spore Spawn should draw its middle/core child behind the shell plates")

        val open = poses.first { it.frame.snesAddress == 0xA5EF3D }
        val rendered = scanner.renderPose(open, tileData, palette)
        assertNotNull(rendered, "Spore Spawn open pose should render")
        assertTrue(rendered!!.width >= 64 && rendered.height >= 80,
            "Spore Spawn open body should render as a large boss pose (${rendered.width}x${rendered.height})")
        assertTrue(rendered.pixels.count { (it ushr 24) > 0 } > 1200,
            "Spore Spawn open body should have substantial visible pixels")
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

    @Test
    fun `Draygon body poses compose body eye tail and arms`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xDE3F) ?: return
        val rawTiles = EnemySpriteGraphics.loadEnemyTileData(rp, 0xDE3F) ?: return
        val renderTiles = EnemySpriteGraphics.loadEnemyRenderTileData(rp, 0xDE3F, rawTiles) ?: return

        val poses = scanner.scanPoses(0xDE3F, minEntries = 3)
        assertTrue(poses.size >= 12, "Draygon should expose composite idle poses")

        val idle = poses.firstOrNull { it.name == "Idle Left 1" }
        assertNotNull(idle, "Draygon should include a left-facing full idle pose")
        assertEquals(4, idle!!.compositeParts.size,
            "Idle Draygon should compose body, eye, tail, and arms")
        assertTrue(idle.tilemapCount >= 2,
            "Idle Draygon should include body and eye BG2 tilemaps")
        assertTrue(idle.childCount >= 5,
            "Idle Draygon should include multibox children from all parts")

        val rendered = scanner.renderPose(idle, renderTiles, palette)
        assertNotNull(rendered, "Composite Draygon pose should render")
        assertTrue(rendered!!.width >= 96 && rendered.height >= 96,
            "Composite Draygon should be a large boss assembly (${rendered.width}x${rendered.height})")
        assertTrue(rendered.pixels.count { (it ushr 24) > 0 } > 1000,
            "Composite Draygon should have substantial visible pixels")
    }

    @Test
    fun `Draygon composite poses use vanilla BG2 origin and low9 sprite tile numbers`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val pose = scanner.scanPoses(0xDE3F, minEntries = 3).firstOrNull { it.name == "Idle Left 1" }
        assertNotNull(pose, "Should find Draygon left idle pose")

        val bodyPart = pose!!.compositeParts.first()
        assertTrue(!bodyPart.renderOptions.normalizeExtendedTilemaps,
            "Draygon body tilemap should render in BG2 tilemap coordinates")
        assertEquals(-0x3E, bodyPart.renderOptions.extendedTilemapOriginX)
        assertEquals(-0x40, bodyPart.renderOptions.extendedTilemapOriginY)
        assertEquals(EnemySpritemap.OamTileNumberMode.LOW_9, bodyPart.renderOptions.oamTileNumberMode,
            "Draygon OAM children should use physical low-9-bit tile IDs")
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
