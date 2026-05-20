package com.supermetroid.editor.rom

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.jupiter.api.Test

class SidehopperVisualTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun checkerBackground(img: BufferedImage) {
        val dark = 0xFF202020.toInt()
        val light = 0xFF2A2A2A.toInt()
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val c = if (((x / 8) + (y / 8)) % 2 == 0) dark else light
                img.setRGB(x, y, c)
            }
        }
    }

    private fun drawScaled(dest: BufferedImage, pixels: IntArray, srcW: Int, srcH: Int, scale: Int, offsetX: Int = 0, offsetY: Int = 0) {
        for (sy in 0 until srcH) {
            for (sx in 0 until srcW) {
                val argb = pixels[sy * srcW + sx]
                if ((argb ushr 24) and 0xFF == 0) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        val px = offsetX + sx * scale + dx
                        val py = offsetY + sy * scale + dy
                        if (px in 0 until dest.width && py in 0 until dest.height) {
                            dest.setRGB(px, py, argb)
                        }
                    }
                }
            }
        }
    }

    private fun countNonTransparent(img: BufferedImage): Int {
        var count = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if ((img.getRGB(x, y) ushr 24) and 0xFF > 0) count++
            }
        }
        return count
    }

    private fun readPixelFromTile(tileData: ByteArray, tileIndex: Int, px: Int, py: Int): Int {
        val bytesPerTile = 32
        val offset = tileIndex * bytesPerTile
        if (offset + 31 >= tileData.size) return 0
        val bit = 7 - px
        val bp0 = (tileData[offset + py * 2].toInt() shr bit) and 1
        val bp1 = (tileData[offset + py * 2 + 1].toInt() shr bit) and 1
        val bp2 = (tileData[offset + py * 2 + 16].toInt() shr bit) and 1
        val bp3 = (tileData[offset + py * 2 + 17].toInt() shr bit) and 1
        return bp0 or (bp1 shl 1) or (bp2 shl 2) or (bp3 shl 3)
    }

    @Test
    fun `export Sidehopper diagnostic images`() {
        val rp = loadTestRom() ?: return
        val speciesId = 0xD93F
        val rom = rp.getRomData()
        val headerPc = rp.snesToPc(0xA00000 or speciesId)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId)!!
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId)!!
        val smap = EnemySpritemap(rp)

        val outDir = File("build/test-sprites")
        outDir.mkdirs()

        val scale = 8

        // --- (a) Default spritemap ---
        val defaultSmap = smap.findDefaultSpritemap(speciesId)!!
        println("=== Default Spritemap ===")
        println("SNES address: \$${defaultSmap.snesAddress.toString(16).uppercase()}")
        for ((i, e) in defaultSmap.entries.withIndex()) {
            println("  Entry $i: tile=0x${e.tileNum.toString(16).uppercase()}, x=${e.xOffset}, y=${e.yOffset}, hFlip=${e.hFlip}, vFlip=${e.vFlip}, 16x16=${e.is16x16}")
        }

        val defaultAssembled = smap.renderSpritemap(defaultSmap, tileData, palette)!!
        val dw = defaultAssembled.width * scale
        val dh = defaultAssembled.height * scale
        val defaultImg = BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB)
        checkerBackground(defaultImg)
        drawScaled(defaultImg, defaultAssembled.pixels, defaultAssembled.width, defaultAssembled.height, scale)
        val defaultFile = File(outDir, "D93F_default_8x.png")
        ImageIO.write(defaultImg, "PNG", defaultFile)
        println("Exported ${defaultFile.name}: ${dw}x${dh}, non-transparent=${countNonTransparent(defaultImg)}")

        // --- (b) Specific spritemaps ---
        val specificAddresses = listOf(
            0xA3AF19 to "AF19",
            0xA3AEE3 to "AEE3",
            0xA3AEFE to "AEFE"
        )
        for ((snesAddr, label) in specificAddresses) {
            println("\n=== Spritemap \$A3:$label ===")
            val parsed = smap.parseSpritemap(snesAddr)
            if (parsed == null) {
                println("  Could not parse spritemap at \$A3:$label")
                continue
            }
            for ((i, e) in parsed.entries.withIndex()) {
                println("  Entry $i: tile=0x${e.tileNum.toString(16).uppercase()}, x=${e.xOffset}, y=${e.yOffset}, hFlip=${e.hFlip}, vFlip=${e.vFlip}, 16x16=${e.is16x16}")
            }

            val assembled = smap.renderSpritemap(parsed, tileData, palette)
            if (assembled == null) {
                println("  Could not render spritemap at \$A3:$label")
                continue
            }
            val sw = assembled.width * scale
            val sh = assembled.height * scale
            val img = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB)
            checkerBackground(img)
            drawScaled(img, assembled.pixels, assembled.width, assembled.height, scale)
            val file = File(outDir, "D93F_${label}_8x.png")
            ImageIO.write(img, "PNG", file)
            println("Exported ${file.name}: ${sw}x${sh}, non-transparent=${countNonTransparent(img)}")
        }

        // --- (c) Individual 16x16 tiles ---
        val localTiles = listOf(0, 2, 4, 6, 8, 10, 12, 14)
        val tileSize = 16
        val totalWidth = localTiles.size * tileSize * scale
        val totalHeight = tileSize * scale
        val tilesImg = BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB)
        checkerBackground(tilesImg)

        for ((idx, localTile) in localTiles.withIndex()) {
            // A 16x16 tile is composed of 4 sub-tiles arranged as:
            // [N,   N+1 ]
            // [N+16, N+17]
            val subTiles = listOf(localTile, localTile + 1, localTile + 16, localTile + 17)
            val pixels = IntArray(tileSize * tileSize)

            for (subIdx in 0 until 4) {
                val subTileIndex = subTiles[subIdx]
                val subOffX = (subIdx % 2) * 8
                val subOffY = (subIdx / 2) * 8
                for (py in 0 until 8) {
                    for (px in 0 until 8) {
                        val colorIndex = readPixelFromTile(tileData, subTileIndex, px, py)
                        if (colorIndex == 0) continue
                        val argb = palette[colorIndex] or 0xFF000000.toInt()
                        pixels[(subOffY + py) * tileSize + (subOffX + px)] = argb
                    }
                }
            }

            val offsetX = idx * tileSize * scale
            drawScaled(tilesImg, pixels, tileSize, tileSize, scale, offsetX, 0)
        }

        val tilesFile = File(outDir, "D93F_tiles_16x16_8x.png")
        ImageIO.write(tilesImg, "PNG", tilesFile)
        println("\n=== 16x16 Tiles ===")
        println("Exported ${tilesFile.name}: ${totalWidth}x${totalHeight}, non-transparent=${countNonTransparent(tilesImg)}")
        println("AI bank: 0x${aiBank.toString(16).uppercase()}")
        println("Tile data size: ${tileData.size} bytes (${tileData.size / 32} tiles)")
    }
}
