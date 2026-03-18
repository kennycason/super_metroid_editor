package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class KraidTileSourceTest {
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
    fun `render body fullheight WITHOUT injected tiles`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()

        // Load tileset 27 WITHOUT any tile injection
        val stateOffsets = rp.findAllStateDataOffsets(0x8FA59F)
        if (stateOffsets.isEmpty()) return
        val tilesetId = rom[stateOffsets.last() + 3].toInt() and 0xFF

        val tg = TileGraphics(rp)
        if (!tg.loadTileset(tilesetId)) return

        // Check if tile 0x110 has non-zero data in the raw tileset
        val tile0x110 = tg.readTileIndices(0x110)
        val hasData = tile0x110 != null && tile0x110.any { it != 0 }
        println("Tile 0x110 in tileset $tilesetId has data: $hasData")
        if (tile0x110 != null) {
            println("First row: ${tile0x110.take(8).joinToString()}")
        }

        // Render body full height using JUST the room tileset (no injection)
        val palettes = tg.getPalettes() ?: return
        val bodyPc = rp.snesToPc(0xA7A0C8)
        val cols = 32
        val rows = 12
        val w = cols * 8
        val h = rows * 8
        val pixels = IntArray(w * h)

        fun readWord(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val word = readWord(rom, bodyPc + (r * cols + c) * 2)
                val tileNum = word and 0x03FF
                val hFlip = (word shr 14) and 1 != 0
                val vFlip = (word shr 15) and 1 != 0
                val palRow = (word shr 10) and 7

                if (tileNum == 0 || tileNum == 0x338) continue
                val indices = tg.readTileIndices(tileNum) ?: continue
                val pal = palettes[palRow.coerceIn(0, palettes.size - 1)]

                for (py in 0 until 8) {
                    for (px in 0 until 8) {
                        val sx = if (hFlip) 7 - px else px
                        val sy = if (vFlip) 7 - py else py
                        val ci = indices[sy * 8 + sx]
                        if (ci == 0) continue
                        val argb = pal[ci.coerceIn(0, pal.size - 1)]
                        val dx = c * 8 + px
                        val dy = r * 8 + py
                        if (dx < w && dy < h) {
                            pixels[dy * w + dx] = argb
                        }
                    }
                }
            }
        }

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        ImageIO.write(img, "png", File("/tmp/kraid_fullheight_no_inject.png"))
        println("Wrote kraid_fullheight_no_inject.png")
    }
}
