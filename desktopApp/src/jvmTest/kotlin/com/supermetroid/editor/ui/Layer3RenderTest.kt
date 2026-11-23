package com.supermetroid.editor.ui
import com.supermetroid.editor.rom.TestRomHelper

import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class Layer3RenderTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `lava Layer 3 source produces visible pixels`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0xA788)
        assertNotNull(room)
        val fxEntries = parser.parseFxEntries(room!!.fxPtr)
        val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
        assertNotNull(defaultFx, "Cathedral should have a default FX entry")
        assertTrue(defaultFx!!.fxType == 0x02, "Cathedral fxType should be 0x02 (Lava), got 0x${defaultFx.fxType.toString(16)}")

        val palette = layer3Palette(0x02)
        val pixels = parser.renderLayer3Image(0x02, palette)
        assertNotNull(pixels, "L3 render should produce pixels")
        val nonZero = pixels!!.count { it != 0 }
        assertTrue(nonZero > 60000, "Most pixels should be non-zero, got $nonZero")
    }

    @Test
    fun `Landing Site L3 rain produces visible pixels`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)
        assertNotNull(room)
        val fxEntries = parser.parseFxEntries(room!!.fxPtr)
        val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
        assertNotNull(defaultFx, "Landing Site should have a default FX entry")
        assertTrue(defaultFx!!.fxType == 0x0A, "Landing Site fxType should be 0x0A (rain), got 0x${defaultFx.fxType.toString(16)}")

        val palette = layer3Palette(0x0A)
        val pixels = parser.renderLayer3Image(0x0A, palette)
        assertNotNull(pixels, "L3 render should produce pixels")
        val nonZero = pixels!!.count { it != 0 }
        assertTrue(nonZero > 60000, "Most pixels should be non-zero, got $nonZero")
    }

    @Test
    fun `Fireflea room L3 darkening produces pixels`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x9C5E)
        assertNotNull(room)
        val fxEntries = parser.parseFxEntries(room!!.fxPtr)
        val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
        assertNotNull(defaultFx)
        assertTrue(defaultFx!!.fxType == 0x24, "Fireflea fxType should be 0x24, got 0x${defaultFx.fxType.toString(16)}")

        val palette = layer3Palette(0x24)
        val pixels = parser.renderLayer3Image(0x24, palette)
        assertNotNull(pixels)
    }

    @Test
    fun `L3 compositing over room image changes pixels`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0xA788) ?: return // Cathedral
        val fxEntries = parser.parseFxEntries(room.fxPtr)
        val fxType = fxEntries.lastOrNull { it.doorSelect == 0 }?.fxType ?: return
        val l3Pixels = parser.renderLayer3Image(fxType, layer3Palette(fxType)) ?: return

        // Create a small test image (256x264) with solid dark pixels
        val w = 256
        val h = 264
        val roomPixels = IntArray(w * h) { 0xFF200000.toInt() } // dark red room
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, roomPixels, 0, w)

        // Composite L3 over it
        val g = img.createGraphics()
        val l3Img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        l3Img.setRGB(0, 0, w, h, l3Pixels, 0, w)
        g.composite = java.awt.AlphaComposite.SrcOver
        g.drawImage(l3Img, 0, 0, null)
        g.dispose()

        // Read back composited pixels — they should differ from the original
        val composited = IntArray(w * h)
        img.getRGB(0, 0, w, h, composited, 0, w)
        val changed = composited.indices.count { composited[it] != roomPixels[it] }
        println("L3 compositing changed $changed / ${composited.size} pixels (${changed * 100 / composited.size}%)")
        assertTrue(changed > composited.size / 2, "L3 should change most pixels, only changed $changed")

        // Save for visual inspection
        val outDir = File("/tmp/smedit_l3_test")
        outDir.mkdirs()
        ImageIO.write(img, "PNG", File(outDir, "cathedral_with_fog.png"))
        println("Saved composited image to ${outDir.absolutePath}/cathedral_with_fog.png")
    }

    @Test
    fun `all layer3 palette entries have correct format`() {
        val testTypes = listOf(0x00, 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x16, 0x1C, 0x24, 0x26, 0x28, 0x2A, 0x2C, 0xFF)
        for (fxType in testTypes) {
            val palette = layer3Palette(fxType)
            assertTrue(palette.size == 4, "fxType 0x${fxType.toString(16)} palette should have 4 entries")
            assertTrue(palette[0] == 0, "fxType 0x${fxType.toString(16)} color 0 should be transparent")
        }
    }

    @Test
    fun `FX type names match SMILE source`() {
        // From SMILE FX1_1.frx Layer3Type dropdown
        assertEquals("Lava", RomParser.FxEntry.FX_TYPE_NAMES[0x02])
        assertEquals("Acid", RomParser.FxEntry.FX_TYPE_NAMES[0x04])
        assertEquals("Water", RomParser.FxEntry.FX_TYPE_NAMES[0x06])
        assertEquals("Spores", RomParser.FxEntry.FX_TYPE_NAMES[0x08])
        assertEquals("Rain", RomParser.FxEntry.FX_TYPE_NAMES[0x0A])
        assertEquals("Fog", RomParser.FxEntry.FX_TYPE_NAMES[0x0C])
    }

    @Test
    fun `liquid physics index maps fxType correctly`() {
        // liquidPhysicsIndex: (fxType & 0xF) >> 1
        // 0x02=Lava→1, 0x04=Acid→2, 0x06=Water→3
        assertEquals(1, liquidPhysicsIndex(0x02), "Lava (0x02) → physics 1")
        assertEquals(2, liquidPhysicsIndex(0x04), "Acid (0x04) → physics 2")
        assertEquals(3, liquidPhysicsIndex(0x06), "Water (0x06) → physics 3")
        assertEquals(0, liquidPhysicsIndex(0x00), "None (0x00) → physics 0")
    }

    @Test
    fun `offscreen liquid height hides only the Layer 3 liquid`() {
        for (fxType in listOf(0x02, 0x04, 0x06)) {
            assertFalse(shouldRenderLayer3Fx(fxType, 0xFFFF))
            assertFalse(shouldRenderLayer3Fx(fxType, 0x8000))
            assertTrue(shouldRenderLayer3Fx(fxType, 0x0200))
        }
        assertTrue(shouldRenderLayer3Fx(0x0A, 0xFFFF), "Rain is not positioned by liquid height")
    }

    @Test
    fun `native liquid tilemaps identify their surface row`() {
        assertEquals(0, nativeLiquidLayer3SurfaceRow(0x02))
        assertEquals(1, nativeLiquidLayer3SurfaceRow(0x04))
        assertEquals(1, nativeLiquidLayer3SurfaceRow(0x06))
        assertEquals(null, nativeLiquidLayer3SurfaceRow(0x0A))
    }

    @Test
    fun `liquid Layer 3 skips a surface below the room canvas`() {
        assertEquals(null, nativeLiquidLayer3ClipTop(1514.9062f, 1469f))
        assertEquals(null, nativeLiquidLayer3ClipTop(1469f, 1469f))
        assertEquals(433f, nativeLiquidLayer3ClipTop(433f, 1469f))
        assertEquals(0f, nativeLiquidLayer3ClipTop(-1f, 1469f))
    }

    @Test
    fun `animated FX frame zero targets native BG3 tile range`() {
        assertEquals(
            RomConstants.Layer3ReplacementGfx(0x3A564, 0x50, 4),
            RomConstants.L3_REPLACEMENT_GFX.getValue(0x02),
        )
        assertEquals(
            RomConstants.Layer3ReplacementGfx(0x3A874, 0x50, 5),
            RomConstants.L3_REPLACEMENT_GFX.getValue(0x0A),
        )
    }

    @Test
    fun `known rooms have correct FX types`() {
        val parser = loadTestRom() ?: return
        // Boulder Room (Blue Brinstar) has water
        val boulderRoom = parser.readRoomHeader(0xA1AD)!!
        val boulderFx = parser.parseFxEntries(boulderRoom.fxPtr).lastOrNull { it.doorSelect == 0 }
        assertNotNull(boulderFx)
        assertEquals("Water", boulderFx!!.fxTypeName, "Boulder Room should have Water FX")

        // Big Pink has Spores
        val bigPink = parser.readRoomHeader(0x9D19)!!
        val bigPinkFx = parser.parseFxEntries(bigPink.fxPtr).lastOrNull { it.doorSelect == 0 }
        assertNotNull(bigPinkFx)
        assertEquals("Spores", bigPinkFx!!.fxTypeName, "Big Pink should have Spores FX")
    }
}
