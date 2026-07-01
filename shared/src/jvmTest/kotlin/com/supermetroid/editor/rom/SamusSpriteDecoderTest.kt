package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SamusSpriteDecoderTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun assertPaletteMatchesRomRow(
        rom: ByteArray,
        palette: IntArray,
        rowPcOffset: Int,
        label: String,
    ) {
        assertEquals(0x00000000, palette[0], "$label color 0 should be transparent in rendered sprites")
        for (i in 1 until 16) {
            val bgr555 = readU16(rom, rowPcOffset + i * 2)
            assertEquals(
                EnemySpriteGraphics.snesColorToArgb(bgr555),
                palette[i],
                "$label color $i should match ROM row at 0x${rowPcOffset.toString(16)}",
            )
        }
    }

    @Test
    fun `animation count is 253`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        assertEquals(253, decoder.animationCount)
    }

    @Test
    fun `standing animation has at least 1 frame`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val frames = decoder.getFrameCount(0) // animation 0 = stand facing right
        assertTrue(frames >= 1, "Standing animation should have at least 1 frame, got $frames")
    }

    @Test
    fun `can extract standing pose`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val pose = decoder.getPose(0, 0)
        assertNotNull(pose, "Should be able to extract standing pose (anim=0, pose=0)")
        assertTrue(pose!!.tilemaps.isNotEmpty(), "Standing pose should have tilemap entries")
        assertTrue(pose.vram.size == 32 * 16 * 32, "VRAM should be 16384 bytes (512 tiles)")
    }

    @Test
    fun `power suit palette has 16 colors`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val palette = decoder.readPalette(SamusSpriteDecoder.SuitType.POWER)
        assertEquals(16, palette.size)
        assertEquals(0x00000000, palette[0], "Index 0 should be transparent")
        assertTrue(palette[1] != 0, "Non-zero colors expected after index 0")
    }

    @Test
    fun `rendered standing pose has non-transparent pixels`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val pose = decoder.getPose(0, 0) ?: return
        val palette = decoder.readPalette()
        val pixels = decoder.renderPose(pose, palette)
        val nonTransparent = pixels.count { it != 0 }
        assertTrue(nonTransparent > 50, "Rendered standing pose should have >50 non-transparent pixels, got $nonTransparent")
    }

    @Test
    fun `can extract multiple animation groups`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        var successCount = 0
        for (group in SamusSpriteDecoder.ANIMATION_GROUPS) {
            val animId = group.animationIds.first()
            val frames = decoder.getFrameCount(animId)
            if (frames > 0) {
                val pose = decoder.getPose(animId, 0)
                if (pose != null && pose.tilemaps.isNotEmpty()) {
                    successCount++
                }
            }
        }
        assertTrue(successCount >= 8, "Should extract at least 8 animation groups, got $successCount")
    }

    @Test
    fun `gravity suit palette differs from power suit`() {
        val rp = loadTestRom() ?: return
        val decoder = SamusSpriteDecoder(rp)
        val power = decoder.readPalette(SamusSpriteDecoder.SuitType.POWER)
        val gravity = decoder.readPalette(SamusSpriteDecoder.SuitType.GRAVITY)
        assertTrue(!power.contentEquals(gravity), "Power and Gravity palettes should differ")
    }

    @Test
    fun `suit palettes use gameplay base palette rows`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val decoder = SamusSpriteDecoder(rp)

        assertPaletteMatchesRomRow(
            rom = rom,
            palette = decoder.readPalette(SamusSpriteDecoder.SuitType.POWER),
            rowPcOffset = 0x0D9400,
            label = "Power suit",
        )
        assertPaletteMatchesRomRow(
            rom = rom,
            palette = decoder.readPalette(SamusSpriteDecoder.SuitType.VARIA),
            rowPcOffset = 0x0D9520,
            label = "Varia suit",
        )
        assertPaletteMatchesRomRow(
            rom = rom,
            palette = decoder.readPalette(SamusSpriteDecoder.SuitType.GRAVITY),
            rowPcOffset = 0x0D9800,
            label = "Gravity suit",
        )
    }
}
