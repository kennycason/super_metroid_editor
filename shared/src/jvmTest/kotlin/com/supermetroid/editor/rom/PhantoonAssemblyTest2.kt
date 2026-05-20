package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Deep investigation of Phantoon assembly: compare tileset palette vs SNES palette,
 * render body with both, and check positional alignment with E4BF.png.
 */
class PhantoonAssemblyTest2 {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readWord(rom: ByteArray, offset: Int): Int =
        (rom[offset].toInt() and 0xFF) or ((rom[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `compare tileset palette row 7 with A7-CA21 Phantoon palette`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val tileGfx = TileGraphics(parser)

        val stateOffsets = parser.findAllStateDataOffsets(0x8FCD13)
        val tilesetIdx = rom[stateOffsets.last() + 3].toInt() and 0xFF
        tileGfx.loadTileset(tilesetIdx)

        val palettes = tileGfx.getPalettes()!!
        println("Tileset $tilesetIdx palette row 7:")
        for (i in palettes[7].indices) {
            println("  [$i] 0x${(palettes[7][i].toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
        }

        val palPc = parser.snesToPc(0xA7CA21)
        println("\nSNES palette at \$A7:CA21:")
        for (i in 0 until 16) {
            val bgr555 = readWord(rom, palPc + i * 2)
            val argb = EnemySpriteGraphics.snesColorToArgb(bgr555)
            println("  [$i] 0x${(argb.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
        }

        // Also check what the tileset palette row 6 looks like (in case the palette row in tilemap is wrong)
        println("\nTileset palette row 6:")
        for (i in palettes[6].indices) {
            println("  [$i] 0x${(palettes[6][i].toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
        }
    }

    @Test
    fun `render body with tileset palette row 7 and save`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val tileGfx = TileGraphics(parser)

        val stateOffsets = parser.findAllStateDataOffsets(0x8FCD13)
        val tilesetIdx = rom[stateOffsets.last() + 3].toInt() and 0xFF
        tileGfx.loadTileset(tilesetIdx)

        val palettes = tileGfx.getPalettes()!!
        val pal7 = palettes[7]

        // Parse body tilemap
        val tilemapPc = parser.snesToPc(0xA7E0AA)
        val entries = parseTilemap(rom, tilemapPc)

        // Render with tileset palette
        val imgW = 80
        val imgH = 96
        val assembled = IntArray(imgW * imgH)

        for (entry in entries) {
            if (entry.tileNum == 0x338) continue
            val indices = tileGfx.readTileIndices(entry.tileNum) ?: continue
            for (py in 0 until 8) for (px in 0 until 8) {
                val sx = if (entry.hFlip) 7 - px else px
                val sy = if (entry.vFlip) 7 - py else py
                val ci = indices[sy * 8 + sx]
                val argb = if (ci == 0) 0x00000000 else pal7[ci]
                val dx = entry.gridX * 8 + px
                val dy = entry.gridY * 8 + py
                if (dx < imgW && dy < imgH) assembled[dy * imgW + dx] = argb
            }
        }

        val outDir = File("build/test-output")
        outDir.mkdirs()
        val img = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, imgW, imgH, assembled, 0, imgW)
        ImageIO.write(img, "png", File(outDir, "phantoon_body_tilesetpal.png"))

        // Also render with $CA21 palette for comparison
        val palPc = parser.snesToPc(0xA7CA21)
        val ca21 = IntArray(16)
        ca21[0] = 0x00000000
        for (i in 1 until 16) {
            val bgr555 = readWord(rom, palPc + i * 2)
            ca21[i] = EnemySpriteGraphics.snesColorToArgb(bgr555)
        }

        val assembled2 = IntArray(imgW * imgH)
        for (entry in entries) {
            if (entry.tileNum == 0x338) continue
            val indices = tileGfx.readTileIndices(entry.tileNum) ?: continue
            for (py in 0 until 8) for (px in 0 until 8) {
                val sx = if (entry.hFlip) 7 - px else px
                val sy = if (entry.vFlip) 7 - py else py
                val ci = indices[sy * 8 + sx]
                val argb = if (ci == 0) 0x00000000 else ca21[ci]
                val dx = entry.gridX * 8 + px
                val dy = entry.gridY * 8 + py
                if (dx < imgW && dy < imgH) assembled2[dy * imgW + dx] = argb
            }
        }

        val img2 = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB)
        img2.setRGB(0, 0, imgW, imgH, assembled2, 0, imgW)
        ImageIO.write(img2, "png", File(outDir, "phantoon_body_ca21pal.png"))

        // Show tile usage stats
        val usedTiles = entries.filter { it.tileNum != 0x338 }.map { it.tileNum }.toSet()
        println("Used tiles: ${usedTiles.sorted().joinToString(", ") { "0x${it.toString(16)}" }}")

        // Check a specific tile's raw indices to verify data is present
        for (t in listOf(0x132, 0x140, 0x150, 0x160, 0x170, 0x178)) {
            val indices = tileGfx.readTileIndices(t)
            if (indices == null) {
                println("Tile 0x${t.toString(16)}: NULL")
                continue
            }
            val nonZero = indices.count { it != 0 }
            val uniqueIndices = indices.filter { it != 0 }.toSet()
            println("Tile 0x${t.toString(16)}: $nonZero/64 non-zero pixels, unique indices: $uniqueIndices")
        }

        // Compare assembled (CA21 palette) vs PNG with positional offset search
        val pngFile = File("../desktopApp/src/jvmMain/resources/enemies/E4BF.png")
        if (!pngFile.exists()) return
        val refImg = ImageIO.read(pngFile)
        val refPixels = IntArray(refImg.width * refImg.height)
        refImg.getRGB(0, 0, refImg.width, refImg.height, refPixels, 0, refImg.width)

        println("\nSearching for best alignment offset...")
        var bestOffX = 0
        var bestOffY = 0
        var bestMatch = 0

        for (offY in -8..8) {
            for (offX in -16..16) {
                var matches = 0
                for (py in 0 until refImg.height) {
                    for (px in 0 until refImg.width) {
                        val ax = px + offX
                        val ay = py + offY
                        if (ax < 0 || ax >= imgW || ay < 0 || ay >= imgH) continue
                        val refArgb = refPixels[py * refImg.width + px]
                        val asmArgb = assembled2[ay * imgW + ax]
                        val refA = (refArgb ushr 24) and 0xFF
                        val asmA = (asmArgb ushr 24) and 0xFF
                        if (refA < 128 && asmA < 128) { matches++; continue }
                        if (refA < 128 || asmA < 128) continue

                        val dr = Math.abs(((refArgb shr 16) and 0xFF) - ((asmArgb shr 16) and 0xFF))
                        val dg = Math.abs(((refArgb shr 8) and 0xFF) - ((asmArgb shr 8) and 0xFF))
                        val db = Math.abs((refArgb and 0xFF) - (asmArgb and 0xFF))
                        if (dr <= 10 && dg <= 10 && db <= 10) matches++
                    }
                }
                if (matches > bestMatch) {
                    bestMatch = matches
                    bestOffX = offX
                    bestOffY = offY
                }
            }
        }

        val total = refImg.width * refImg.height
        println("Best offset: ($bestOffX, $bestOffY) — $bestMatch/$total matches (${bestMatch * 100 / total}%)")
    }

    data class TilemapEntry(
        val gridX: Int, val gridY: Int, val tileNum: Int,
        val hFlip: Boolean, val vFlip: Boolean, val paletteRow: Int
    )

    private fun parseTilemap(rom: ByteArray, tilemapPc: Int): List<TilemapEntry> {
        val entries = mutableListOf<TilemapEntry>()
        var offset = tilemapPc + 2 // skip FFFE header
        var rowIdx = 0
        while (true) {
            val dest = readWord(rom, offset)
            if (dest == 0xFFFF) break
            val count = readWord(rom, offset + 2)
            offset += 4
            for (i in 0 until count) {
                val tw = readWord(rom, offset)
                offset += 2
                entries.add(TilemapEntry(
                    i, rowIdx, tw and 0x03FF,
                    (tw shr 14) and 1 != 0,
                    (tw shr 15) and 1 != 0,
                    (tw shr 10) and 7
                ))
            }
            rowIdx++
        }
        return entries
    }
}
