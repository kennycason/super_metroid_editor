package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

class SpritePalettesTest {

    private fun loadRom(): ByteArray? = TestRomHelper.loadRomBytes()

    @Test
    fun `region definitions are valid`() {
        for (region in SpritePalettes.REGIONS) {
            assert(region.colorCount > 0) { "${region.id} has zero colors" }
            assert(region.offset > 0) { "${region.id} has zero offset" }
            assert(region.colorCount % 16 == 0) { "${region.id} color count not multiple of 16" }
            assertEquals(region.colorCount * 2, region.byteSize)
        }
    }

    @Test
    fun `findRegion returns correct regions`() {
        assertNotNull(SpritePalettes.findRegion("samus_power"))
        assertNotNull(SpritePalettes.findRegion("samus_varia"))
        assertNotNull(SpritePalettes.findRegion("beam_standard"))
        assertEquals(null, SpritePalettes.findRegion("nonexistent"))
    }

    @Test
    fun `read Samus Power Suit palette from ROM`() {
        val rom = loadRom() ?: run { assumeTrue(false, "ROM not found"); return }
        val colors = SpritePalettes.readColors(rom, SpritePalettes.SAMUS_POWER)
        assertNotNull(colors)
        assertEquals(528, colors!!.size)
        // Verify some colors are non-zero (actual palette data)
        val nonZero = colors.count { it != 0 }
        assert(nonZero > 100) { "Expected many non-zero colors, got $nonZero/528" }
    }

    @Test
    fun `read beam palette from ROM`() {
        val rom = loadRom() ?: run { assumeTrue(false, "ROM not found"); return }
        val colors = SpritePalettes.readColors(rom, SpritePalettes.BEAM_STANDARD)
        assertNotNull(colors)
        assertEquals(16, colors!!.size)
    }

    @Test
    fun `readRow returns single 16-color row`() {
        val rom = loadRom() ?: run { assumeTrue(false, "ROM not found"); return }
        val row0 = SpritePalettes.readRow(rom, SpritePalettes.SAMUS_POWER, 0)
        assertNotNull(row0)
        assertEquals(16, row0!!.size)
        // Row 0 should match first 16 colors of full read
        val full = SpritePalettes.readColors(rom, SpritePalettes.SAMUS_POWER)!!
        for (i in 0 until 16) {
            assertEquals(full[i], row0[i], "Row 0 color $i mismatch")
        }
    }

    @Test
    fun `colors to bytes round trip`() {
        val colors = IntArray(16) { PaletteEffects.rgb5ToBgr555(it * 2, it, 31 - it) }
        val bytes = SpritePalettes.colorsToBytes(colors)
        assertEquals(32, bytes.size)
        val back = SpritePalettes.bytesToColors(bytes)
        for (i in colors.indices) {
            assertEquals(colors[i], back[i], "Round-trip mismatch at $i")
        }
    }

    @Test
    fun `write and read back colors`() {
        val rom = loadRom() ?: run { assumeTrue(false, "ROM not found"); return }
        val copy = rom.copyOf()
        val region = SpritePalettes.BEAM_STANDARD
        val original = SpritePalettes.readColors(copy, region)!!

        // Modify colors
        val modified = original.copyOf()
        modified[1] = PaletteEffects.rgb5ToBgr555(31, 0, 0)
        SpritePalettes.writeColors(copy, region, modified)

        // Read back
        val readBack = SpritePalettes.readColors(copy, region)!!
        assertEquals(modified[1], readBack[1])
        assertEquals(PaletteEffects.rgb5ToBgr555(31, 0, 0), readBack[1])
    }

    @Test
    fun `bgr555ToArgb converts correctly`() {
        // Pure white (31,31,31)
        val white = PaletteEffects.rgb5ToBgr555(31, 31, 31)
        val argb = SpritePalettes.bgr555ToArgb(white)
        assertEquals(0xFF.toByte(), ((argb shr 24) and 0xFF).toByte()) // alpha
        assert(((argb shr 16) and 0xFF) >= 248) // R close to 255
        assert(((argb shr 8) and 0xFF) >= 248)  // G close to 255
        assert((argb and 0xFF) >= 248)            // B close to 255

        // Pure black (0,0,0)
        val black = PaletteEffects.rgb5ToBgr555(0, 0, 0)
        val argbBlack = SpritePalettes.bgr555ToArgb(black)
        assertEquals(0, (argbBlack shr 16) and 0xFF) // R
        assertEquals(0, (argbBlack shr 8) and 0xFF)  // G
        assertEquals(0, argbBlack and 0xFF)            // B
    }
}
