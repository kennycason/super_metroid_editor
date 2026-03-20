package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SpriteAnimationTest {

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

    // ─── SpriteAnimationFrame tests ─────────────────────────────────

    @Test
    fun `frame duration conversion ticks to ms`() {
        val frame = SpriteAnimationFrame(IntArray(1), 1, 1, durationTicks = 6)
        // 6 ticks * 1000 / 60 = 100ms
        assertEquals(100, frame.durationMs)
    }

    @Test
    fun `frame duration zero returns zero ms`() {
        val frame = SpriteAnimationFrame(IntArray(1), 1, 1, durationTicks = 0)
        assertEquals(0, frame.durationMs)
    }

    @Test
    fun `frame equality compares pixels`() {
        val px1 = intArrayOf(0xFF0000FF.toInt(), 0xFF00FF00.toInt())
        val px2 = intArrayOf(0xFF0000FF.toInt(), 0xFF00FF00.toInt())
        val px3 = intArrayOf(0xFF0000FF.toInt(), 0xFFFF0000.toInt())

        val f1 = SpriteAnimationFrame(px1, 2, 1, 4)
        val f2 = SpriteAnimationFrame(px2, 2, 1, 4)
        val f3 = SpriteAnimationFrame(px3, 2, 1, 4)

        assertEquals(f1, f2, "Frames with same pixels should be equal")
        assertTrue(f1 != f3, "Frames with different pixels should not be equal")
    }

    // ─── SpriteAnimation tests ──────────────────────────────────────

    @Test
    fun `animation total ticks sums frame durations`() {
        val frames = listOf(
            SpriteAnimationFrame(IntArray(1), 1, 1, durationTicks = 4),
            SpriteAnimationFrame(IntArray(1), 1, 1, durationTicks = 6),
            SpriteAnimationFrame(IntArray(1), 1, 1, durationTicks = 3),
        )
        val anim = SpriteAnimation("test", frames)
        assertEquals(13, anim.totalTicks)
        // 13 * 1000 / 60 = 216ms
        assertEquals(216, anim.totalMs)
    }

    @Test
    fun `animation hasTiming detects timed frames`() {
        val timed = SpriteAnimation("a", listOf(
            SpriteAnimationFrame(IntArray(1), 1, 1, durationTicks = 4)
        ))
        val untimed = SpriteAnimation("b", listOf(
            SpriteAnimationFrame(IntArray(1), 1, 1, durationTicks = 0)
        ))
        assertTrue(timed.hasTiming)
        assertTrue(!untimed.hasTiming)
    }

    // ─── Sprite sheet rendering tests ────────────────────────────────

    @Test
    fun `renderSpriteSheet produces correct dimensions`() {
        val frames = (0 until 5).map {
            SpriteAnimationFrame(IntArray(16 * 16), 16, 16, 4)
        }
        val (pixels, w, h) = renderSpriteSheet(frames, columns = 3, padding = 0)

        // 3 columns, 2 rows, each cell 16x16
        assertEquals(48, w) // 3 * 16
        assertEquals(32, h) // 2 * 16
        assertEquals(w * h, pixels.size)
    }

    @Test
    fun `renderSpriteSheet with padding increases cell size`() {
        val frames = listOf(
            SpriteAnimationFrame(IntArray(8 * 8), 8, 8, 4),
            SpriteAnimationFrame(IntArray(8 * 8), 8, 8, 4),
        )
        val (_, w, h) = renderSpriteSheet(frames, columns = 2, padding = 2)

        // Each cell = 8 + 2*2 = 12, 2 columns = 24, 1 row = 12
        assertEquals(24, w)
        assertEquals(12, h)
    }

    @Test
    fun `renderSpriteSheet preserves pixel data`() {
        val red = 0xFFFF0000.toInt()
        val pixels = IntArray(4) { red } // 2x2 red square
        val frames = listOf(SpriteAnimationFrame(pixels, 2, 2, 4))

        val (sheetPixels, w, _) = renderSpriteSheet(frames, columns = 1, padding = 0)

        // The 2x2 frame should be at top-left of a 2x2 sheet
        assertEquals(red, sheetPixels[0])
        assertEquals(red, sheetPixels[1])
        assertEquals(red, sheetPixels[w])
        assertEquals(red, sheetPixels[w + 1])
    }

    @Test
    fun `renderSpriteSheet empty input returns empty`() {
        val (pixels, w, h) = renderSpriteSheet(emptyList())
        assertEquals(0, w)
        assertEquals(0, h)
        assertEquals(0, pixels.size)
    }

    @Test
    fun `renderMultiAnimationSheet lays out rows correctly`() {
        val anim1 = SpriteAnimation("a", (0 until 3).map {
            SpriteAnimationFrame(IntArray(8 * 8), 8, 8, 4)
        })
        val anim2 = SpriteAnimation("b", (0 until 5).map {
            SpriteAnimationFrame(IntArray(8 * 8), 8, 8, 4)
        })

        val (pixels, w, h) = renderMultiAnimationSheet(listOf(anim1, anim2), padding = 0)

        // Max columns = 5 (from anim2), each cell = 8x8
        assertEquals(40, w) // 5 * 8
        assertEquals(16, h) // 2 * 8
        assertEquals(w * h, pixels.size)
    }

    // ─── Enemy animation extraction tests ────────────────────────────

    @Test
    fun `build Zoomer animation from ROM`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xDCFF) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xDCFF) ?: return

        val anim = smap.buildAnimation(0xDCFF, tileData, palette, "Zoomer")
        assertNotNull(anim, "Zoomer should produce an animation")
        assertTrue(anim!!.frames.size >= 3, "Zoomer should have at least 3 frames (has ${anim.frames.size})")
        assertTrue(anim.hasTiming, "Zoomer animation should have timing data")
        assertTrue(anim.loop, "Zoomer animation should loop")

        for ((i, frame) in anim.frames.withIndex()) {
            assertTrue(frame.width > 0, "Frame $i width should be > 0")
            assertTrue(frame.height > 0, "Frame $i height should be > 0")
            assertTrue(frame.durationTicks in 1..120, "Frame $i duration ${frame.durationTicks} should be reasonable")
            // Should have some visible pixels
            val nonTransparent = frame.pixels.count { (it ushr 24) > 0 }
            assertTrue(nonTransparent > 0, "Frame $i should have visible pixels")
        }
    }

    @Test
    fun `build Sidehopper animation from ROM`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val speciesId = 0xDD3F // Sidehopper
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return

        val anim = smap.buildAnimation(speciesId, tileData, palette, "Sidehopper")
        assertNotNull(anim, "Sidehopper should produce an animation")
        assertTrue(anim!!.frames.isNotEmpty(), "Sidehopper should have at least 1 frame")
    }

    // ─── Samus animation extraction tests ────────────────────────────

    @Test
    fun `build Samus run animation`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)

        // Animation ID 9 = Run right
        val anim = decoder.buildAnimation(9, SamusSpriteDecoder.SuitType.POWER, renderSize = 64)
        assertNotNull(anim, "Run animation should be built")
        assertTrue(anim!!.frames.size >= 4, "Run should have at least 4 frames (has ${anim.frames.size})")
        assertTrue(anim.name.contains("Run"), "Animation name should contain Run")

        for (frame in anim.frames) {
            assertEquals(64, frame.width)
            assertEquals(64, frame.height)
            assertEquals(64 * 64, frame.pixels.size)
        }
    }

    @Test
    fun `build Samus standing animation has frames`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)

        val anim = decoder.buildAnimation(0, SamusSpriteDecoder.SuitType.POWER, renderSize = 64)
        assertNotNull(anim, "Standing animation should be built")
        assertTrue(anim!!.frames.isNotEmpty(), "Standing should have at least 1 frame")
        // Standing frames should all have visible pixels
        val nonTrans = anim.frames.first().pixels.count { (it ushr 24) > 0 }
        assertTrue(nonTrans > 10, "Standing frame should have visible Samus pixels")
    }

    @Test
    fun `build Samus spin jump animation has multiple frames`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)

        // 0x25 = spin jump right
        val anim = decoder.buildAnimation(0x25, SamusSpriteDecoder.SuitType.POWER, renderSize = 64)
        assertNotNull(anim)
        assertTrue(anim!!.frames.size >= 2, "Spin jump should have at least 2 frames (has ${anim.frames.size})")
    }

    @Test
    fun `build all Samus animations produces results for each group`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)

        val all = decoder.buildAllAnimations(SamusSpriteDecoder.SuitType.POWER, renderSize = 32)
        assertTrue(all.size >= 14, "Should build at least 14 animations (one per major group), got ${all.size}")

        // Verify names cover major groups
        val names = all.map { it.name }
        assertTrue(names.any { it.contains("Stand") }, "Should have standing animation")
        assertTrue(names.any { it.contains("Run") }, "Should have run animation")
        assertTrue(names.any { it.contains("Jump") }, "Should have jump animation")
    }

    @Test
    fun `Samus group animations builds all variants`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)

        // Group 1 = Run (9 animation IDs)
        val runAnims = decoder.buildGroupAnimations(1, SamusSpriteDecoder.SuitType.POWER, renderSize = 32)
        assertTrue(runAnims.size >= 5, "Run group should have at least 5 direction variants (has ${runAnims.size})")
    }

    @Test
    fun `Samus different suits have different palettes`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)

        val powerPal = decoder.readPalette(SamusSpriteDecoder.SuitType.POWER)
        val gravPal = decoder.readPalette(SamusSpriteDecoder.SuitType.GRAVITY)

        // Power and Gravity palettes should differ (matches existing SamusSpriteDecoderTest)
        assertTrue(!powerPal.contentEquals(gravPal), "Power and Gravity palettes should differ")
    }
}
