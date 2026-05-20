package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

class PhantoonSpritemapRoundtripTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `render body and verify 100 pct match with PNG`() {
        val parser = loadTestRom() ?: return
        val sm = PhantoonSpritemap(parser)
        assertTrue(sm.load(), "Spritemap should load successfully")

        val body = sm.renderComponent(PhantoonSpritemap.COMPONENT_TILEMAPS[0])
        assertNotNull(body)
        body!!
        println("Body: ${body.width}x${body.height} (${body.tilesCols}x${body.tilesRows} tiles)")
        assertEquals(80, body.width)
        assertEquals(96, body.height)

        val pngFile = File("../desktopApp/src/jvmMain/resources/enemies/E4BF.png")
        if (!pngFile.exists()) return
        val refImg = ImageIO.read(pngFile)
        val refPixels = IntArray(refImg.width * refImg.height)
        refImg.getRGB(0, 0, refImg.width, refImg.height, refPixels, 0, refImg.width)

        var matches = 0
        val offX = PhantoonSpritemap.BODY_PNG_X_OFFSET
        for (py in 0 until refImg.height) {
            for (px in 0 until refImg.width) {
                val ax = px + offX
                val ay = py
                if (ax >= body.width || ay >= body.height) continue
                val refA = refPixels[py * refImg.width + px]
                val asmA = body.pixels[ay * body.width + ax]
                val ra = (refA ushr 24) and 0xFF
                val aa = (asmA ushr 24) and 0xFF
                if (ra < 128 && aa < 128) { matches++; continue }
                if (ra < 128 || aa < 128) continue
                val dr = Math.abs(((refA shr 16) and 0xFF) - ((asmA shr 16) and 0xFF))
                val dg = Math.abs(((refA shr 8) and 0xFF) - ((asmA shr 8) and 0xFF))
                val db = Math.abs((refA and 0xFF) - (asmA and 0xFF))
                if (dr <= 10 && dg <= 10 && db <= 10) matches++
            }
        }
        val total = refImg.width * refImg.height
        val pct = matches * 100.0 / total
        println("Match: $matches/$total (${String.format("%.1f", pct)}%%)")
        assertTrue(pct > 99.0, "Should match >99%")
    }

    @Test
    fun `pixel-to-tile mapping works correctly`() {
        val parser = loadTestRom() ?: return
        val sm = PhantoonSpritemap(parser)
        assertTrue(sm.load())

        val body = sm.renderComponent(PhantoonSpritemap.COMPONENT_TILEMAPS[0])!!

        // Empty tile area (row 0, col 0) should return null
        assertNull(body.pixelToTile(0, 0))

        // Row 0, col 3 should be tile 0x132
        val m1 = body.pixelToTile(24, 0)
        assertNotNull(m1)
        assertEquals(0x132, m1!!.first)

        // Row 0, col 6 should be tile 0x132 H-flipped (from 0x7D32)
        val m2 = body.pixelToTile(48, 0)
        assertNotNull(m2)
        assertEquals(0x132, m2!!.first)

        // Check that pixel-to-tile gives correct local coordinates
        val m3 = body.pixelToTile(27, 3) // col=3, row=0; localPx=3, localPy=3
        assertNotNull(m3)
        assertEquals(0x132, m3!!.first)
        assertEquals(3, m3.second) // tpx
        assertEquals(3, m3.third)  // tpy

        println("pixel-to-tile mapping verified")
    }

    @Test
    fun `edit roundtrip - modify tile pixel directly and verify propagation`() {
        val parser = loadTestRom() ?: return
        val sm = PhantoonSpritemap(parser)
        assertTrue(sm.load())

        val tg = sm.getTileGraphics()
        tg.loadTileset(sm.getTilesetId())

        val body = sm.renderComponent(PhantoonSpritemap.COMPONENT_TILEMAPS[0])!!
        val pal = sm.getPalette()!!

        // Pick palette index 15 (bright pink 0xff520000 -> actually 13 is 0xffef0073)
        val redIdx = 13
        println("Using palette index $redIdx: 0x${(pal[redIdx].toLong() and 0xFFFFFFFFL).toString(16)}")

        // Directly write a single pixel to tile 0x132 at position (0,0)
        val mapping = body.pixelToTile(24, 0)!!
        assertEquals(0x132, mapping.first)
        tg.writePixelIndex(mapping.first, mapping.second, mapping.third, redIdx)

        // Re-render and verify the change is visible
        val body2 = sm.renderComponent(PhantoonSpritemap.COMPONENT_TILEMAPS[0])!!
        val px24 = body2.pixels[0 * body2.width + 24]
        println("Pixel at (24,0) after edit: 0x${(px24.toLong() and 0xFFFFFFFFL).toString(16)}")

        val dr = Math.abs(((px24 shr 16) and 0xFF) - ((pal[redIdx] shr 16) and 0xFF))
        val dg = Math.abs(((px24 shr 8) and 0xFF) - ((pal[redIdx] shr 8) and 0xFF))
        val db = Math.abs((px24 and 0xFF) - (pal[redIdx] and 0xFF))
        assertTrue(dr <= 1 && dg <= 1 && db <= 1,
            "Edited pixel should match target color, got dr=$dr dg=$dg db=$db")

        // Verify: tile 0x132 is also used H-flipped at (55, 0) — row 0, col 6
        // pixel (24,0) → tile (0,0), so H-flipped → tile pixel (7,0) → assembled pixel (55,0)? 
        // Actually col 6 = pixel 48. Entry at col 6 is 0x7D32 (tile 0x132, H-flip).
        // Tile pixel (0,0) in H-flip → assembled pixel (48+7, 0) = (55, 0)
        val mirroredPx = body2.pixels[0 * body2.width + 55]
        println("Mirrored pixel at (55,0): 0x${(mirroredPx.toLong() and 0xFFFFFFFFL).toString(16)}")

        val dr2 = Math.abs(((mirroredPx shr 16) and 0xFF) - ((pal[redIdx] shr 16) and 0xFF))
        val dg2 = Math.abs(((mirroredPx shr 8) and 0xFF) - ((pal[redIdx] shr 8) and 0xFF))
        val db2 = Math.abs((mirroredPx and 0xFF) - (pal[redIdx] and 0xFF))
        assertTrue(dr2 <= 1 && dg2 <= 1 && db2 <= 1,
            "Mirrored instance of tile should also show the edit, got dr=$dr2 dg=$dg2 db=$db2")

        println("Edit roundtrip verified - edits propagate to all tile instances")
    }

    @Test
    fun `applyEdits full pipeline - paint on assembled sprite and extract modified tileset`() {
        val parser = loadTestRom() ?: return
        val sm = PhantoonSpritemap(parser)
        assertTrue(sm.load())

        val tg = sm.getTileGraphics()
        tg.loadTileset(sm.getTilesetId())
        val origVarGfx = tg.getRawVarGfx()!!.copyOf()

        val body = sm.renderComponent(PhantoonSpritemap.COMPONENT_TILEMAPS[0])!!
        val pal = sm.getPalette()!!

        val editedPixels = body.pixels.copyOf()
        editedPixels[0 * body.width + 24] = pal[13]
        editedPixels[0 * body.width + 25] = pal[13]
        editedPixels[0 * body.width + 26] = pal[13]

        val modified = sm.applyEdits(body, editedPixels, tg)
        assertTrue(modified.isNotEmpty(), "Should have modified tiles")
        assertTrue(0x132 in modified, "Tile 0x132 should be modified")

        val body2 = sm.renderComponent(PhantoonSpritemap.COMPONENT_TILEMAPS[0])!!
        for (x in 24..26) {
            val px = body2.pixels[0 * body2.width + x]
            val dr = Math.abs(((px shr 16) and 0xFF) - ((pal[13] shr 16) and 0xFF))
            val dg = Math.abs(((px shr 8) and 0xFF) - ((pal[13] shr 8) and 0xFF))
            val db = Math.abs((px and 0xFF) - (pal[13] and 0xFF))
            assertTrue(dr <= 1 && dg <= 1 && db <= 1,
                "Pixel ($x,0) should match palette 13 after applyEdits")
        }

        val modifiedVarGfx = tg.getRawVarGfx()!!
        assertEquals(origVarGfx.size, modifiedVarGfx.size, "Var GFX size should be preserved")
        assertFalse(origVarGfx.contentEquals(modifiedVarGfx),
            "Modified var GFX should differ from original")

        val tileOffset = 0x132 * TileGraphics.BYTES_PER_TILE
        var tileChanged = false
        for (i in tileOffset until tileOffset + TileGraphics.BYTES_PER_TILE) {
            if (origVarGfx[i] != modifiedVarGfx[i]) { tileChanged = true; break }
        }
        assertTrue(tileChanged, "Tile 0x132 bytes should differ in modified var GFX")

        println("Full pipeline verified: paint → applyEdits → re-render → extract var GFX")
    }
}
