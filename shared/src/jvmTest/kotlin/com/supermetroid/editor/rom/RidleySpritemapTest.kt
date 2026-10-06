package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    @Test
    fun `complete Ridley compositions include independently drawn wings and tail`() {
        val rp = loadTestRom() ?: return
        val ridley = RidleySpritemap(rp)
        assertTrue(ridley.load())

        val idle = RidleySpritemap.COMPOSITIONS.first { it.key == "left-idle" }
        val rendered = requireNotNull(ridley.renderComposition(idle))
        val body = requireNotNull(ridley.renderBody(RidleySpritemap.BODY_COMPONENTS.first()))

        assertTrue(rendered.width > body.width, "Full composition should extend beyond the four-part body")
        assertTrue(rendered.height > body.height, "Articulated tail/wings should extend the complete silhouette")
        assertTrue(rendered.spritemap.entries.size > body.spritemap.entries.size)
    }

    @Test
    fun `facing-forward body uses the auxiliary low OBJ page instead of local tail tiles`() {
        val rp = loadTestRom() ?: return
        val ridley = RidleySpritemap(rp)
        assertTrue(ridley.load(ByteArray(RidleySpritemap.RAW_TILES_SIZE)))

        val forward = requireNotNull(
            ridley.renderBody(RidleySpritemap.BODY_COMPONENTS.single { it.key == "forward" })
        )
        val side = requireNotNull(
            ridley.renderBody(RidleySpritemap.BODY_COMPONENTS.single { it.key == "left-neutral" })
        )
        val sharedSource = RidleySpritemap.SHARED_VRAM_SOURCES.single()

        assertEquals(RidleySpritemap.FORWARD_TILES_SNES, sharedSource.snesAddress)
        assertEquals(RidleySpritemap.FORWARD_TILES_SIZE, requireNotNull(ridley.readRuntimeSource(sharedSource)).size)
        assertEquals(48, forward.width)
        assertEquals(88, forward.height)
        assertTrue(forward.pixels.count { (it ushr 24) != 0 } > 2_000,
            "Auxiliary tiles should assemble the dense forward-facing body")
        assertTrue(side.pixels.none { (it ushr 24) != 0 },
            "Blanking the species sheet should blank side poses but not the auxiliary forward pose")
    }

    @Test
    fun `Ridley runtime animations and palettes are source-bounded`() {
        val rp = loadTestRom() ?: return
        val ridley = RidleySpritemap(rp)
        assertTrue(ridley.load())

        assertEquals(12, RidleySpritemap.WING_COMPONENTS.size)
        assertEquals(11, RidleySpritemap.BODY_COMPONENTS.size)
        assertEquals(11, RidleySpritemap.ANIMATIONS.size)
        RidleySpritemap.ANIMATIONS.forEach { definition ->
            val animation = requireNotNull(ridley.renderAnimation(definition)) { definition.name }
            assertEquals(definition.frames.size, animation.frames.size)
            assertTrue(animation.frames.all { frame -> frame.pixels.any { (it ushr 24) != 0 } })
        }

        val paletteHashes = RidleySpritemap.PALETTE_STAGES.map { stage ->
            requireNotNull(ridley.readPalette(stage)).contentHashCode()
        }
        assertEquals(paletteHashes.size, paletteHashes.distinct().size)
    }

    @Test
    fun `ribs and claws DMA variants alter the composed pixels`() {
        val rp = loadTestRom() ?: return
        val ridley = RidleySpritemap(rp)
        assertTrue(ridley.load())

        val idle = requireNotNull(ridley.renderComposition(RidleySpritemap.COMPOSITIONS.first()))
        val clenched = requireNotNull(
            ridley.renderComposition(RidleySpritemap.COMPOSITIONS.first { it.key == "left-clenched" })
        )
        assertNotEquals(idle.pixels.contentHashCode(), clenched.pixels.contentHashCode())
        RidleySpritemap.RUNTIME_SOURCES.forEach { source ->
            assertEquals(source.byteCount, requireNotNull(ridley.readRuntimeSource(source)).size)
        }
    }
}
