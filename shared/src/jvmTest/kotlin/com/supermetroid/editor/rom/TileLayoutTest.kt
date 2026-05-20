package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Tests whether SNES OAM 16x16 tile data is stored in ROM as:
 * A) 16-wide VRAM grid: sub-tiles [N, N+1, N+16, N+17]
 * B) Sequential 4-tile blocks: sub-tiles [N, N+1, N+2, N+3]
 *
 * If the DMA rearranges tiles from sequential to VRAM grid layout,
 * the raw ROM data would be in format B.
 */
class TileLayoutTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `compare 16-wide grid vs sequential tile layout for Sidehopper`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val speciesId = 0xD93F

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return

        // Use the AEE3 spritemap (known standing pose candidate)
        val spritemap = smap.parseSpritemap(0xA3AEE3) ?: return

        val outputDir = File("build/test-sprites")
        outputDir.mkdirs()

        // Render with standard 16-wide VRAM grid layout [N, N+1, N+16, N+17]
        val gridSprite = renderWithLayout(spritemap, tileData, palette, vramWidth = 16)
        if (gridSprite != null) {
            exportScaled(gridSprite, 8, File(outputDir, "D93F_layout_grid16.png"))
            println("Grid-16 layout: ${gridSprite.width}x${gridSprite.height}")
        }

        // Render with sequential block layout [N, N+1, N+2, N+3]
        val seqSprite = renderWithLayout(spritemap, tileData, palette, vramWidth = 2)
        if (seqSprite != null) {
            exportScaled(seqSprite, 8, File(outputDir, "D93F_layout_seq2.png"))
            println("Sequential-2 layout: ${seqSprite.width}x${seqSprite.height}")
        }

        // Also try width=4 and width=8
        for (w in listOf(4, 8)) {
            val sprite = renderWithLayout(spritemap, tileData, palette, vramWidth = w)
            if (sprite != null) {
                exportScaled(sprite, 8, File(outputDir, "D93F_layout_w${w}.png"))
                println("Width-$w layout: ${sprite.width}x${sprite.height}")
            }
        }

        // Render tile sheets at different widths for comparison
        for (w in listOf(2, 4, 8, 16)) {
            renderTileSheet(tileData, palette, w, File(outputDir, "D93F_sheet_w${w}_8x.png"))
        }
    }

    private fun renderWithLayout(
        spritemap: EnemySpritemap.Spritemap,
        tileData: ByteArray,
        palette: IntArray,
        vramWidth: Int
    ): SimpleSprite? {
        if (spritemap.entries.isEmpty()) return null

        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (entry in spritemap.entries) {
            val size = if (entry.is16x16) 16 else 8
            minX = minOf(minX, entry.xOffset)
            maxX = maxOf(maxX, entry.xOffset + size)
            minY = minOf(minY, entry.yOffset)
            maxY = maxOf(maxY, entry.yOffset + size)
        }
        val w = maxX - minX; val h = maxY - minY
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)

        for (entry in spritemap.entries) {
            val localTile = entry.tileNum and 0xFF
            if (entry.is16x16) {
                // Use configurable VRAM width for sub-tile layout
                val subTiles = intArrayOf(
                    localTile, localTile + 1,
                    localTile + vramWidth, localTile + vramWidth + 1
                )
                val subOffsets = arrayOf(0 to 0, 8 to 0, 0 to 8, 8 to 8)

                for (si in 0 until 4) {
                    val tileOffset = subTiles[si] * 32
                    if (tileOffset + 32 > tileData.size) continue
                    val (subDx, subDy) = subOffsets[si]
                    val adjDx = if (entry.hFlip) 8 - subDx else subDx
                    val adjDy = if (entry.vFlip) 8 - subDy else subDy

                    for (py in 0 until 8) {
                        for (px in 0 until 8) {
                            val srcX = if (entry.hFlip) 7 - px else px
                            val srcY = if (entry.vFlip) 7 - py else py
                            val ci = readPixel(tileData, tileOffset, srcX, srcY)
                            if (ci == 0) continue
                            val dx = entry.xOffset - minX + adjDx + px
                            val dy = entry.yOffset - minY + adjDy + py
                            if (dx in 0 until w && dy in 0 until h) {
                                pixels[dy * w + dx] = palette[ci.coerceIn(0, 15)] or (0xFF shl 24)
                            }
                        }
                    }
                }
            } else {
                val tileOffset = localTile * 32
                if (tileOffset + 32 > tileData.size) continue
                for (py in 0 until 8) {
                    for (px in 0 until 8) {
                        val srcX = if (entry.hFlip) 7 - px else px
                        val srcY = if (entry.vFlip) 7 - py else py
                        val ci = readPixel(tileData, tileOffset, srcX, srcY)
                        if (ci == 0) continue
                        val dx = entry.xOffset - minX + px
                        val dy = entry.yOffset - minY + py
                        if (dx in 0 until w && dy in 0 until h) {
                            pixels[dy * w + dx] = palette[ci.coerceIn(0, 15)] or (0xFF shl 24)
                        }
                    }
                }
            }
        }
        return SimpleSprite(w, h, pixels)
    }

    private fun renderTileSheet(tileData: ByteArray, palette: IntArray, cols: Int, outFile: File) {
        val tileCount = tileData.size / 32
        val rows = (tileCount + cols - 1) / cols
        val sw = cols * 8; val sh = rows * 8
        val scale = 8
        val img = BufferedImage(sw * scale, sh * scale, BufferedImage.TYPE_INT_ARGB)

        // Dark background
        for (y in 0 until sh * scale) for (x in 0 until sw * scale) {
            val isLight = ((x / (scale * 4)) + (y / (scale * 4))) % 2 == 0
            img.setRGB(x, y, if (isLight) 0xFF3A3A4A.toInt() else 0xFF2A2A3A.toInt())
        }

        for (t in 0 until tileCount) {
            val tx = (t % cols) * 8
            val ty = (t / cols) * 8
            val offset = t * 32
            for (py in 0 until 8) for (px in 0 until 8) {
                val ci = readPixel(tileData, offset, px, py)
                if (ci == 0) continue
                val color = palette[ci.coerceIn(0, 15)] or (0xFF shl 24)
                for (sy in 0 until scale) for (sx in 0 until scale)
                    img.setRGB((tx + px) * scale + sx, (ty + py) * scale + sy, color)
            }
        }
        ImageIO.write(img, "PNG", outFile)
        println("Tile sheet w=$cols: ${sw}x${sh} → ${outFile.name}")
    }

    private fun readPixel(tileData: ByteArray, offset: Int, px: Int, py: Int): Int {
        val bit = 7 - px
        val bp0 = (tileData[offset + py * 2].toInt() shr bit) and 1
        val bp1 = (tileData[offset + py * 2 + 1].toInt() shr bit) and 1
        val bp2 = (tileData[offset + py * 2 + 16].toInt() shr bit) and 1
        val bp3 = (tileData[offset + py * 2 + 17].toInt() shr bit) and 1
        return bp0 or (bp1 shl 1) or (bp2 shl 2) or (bp3 shl 3)
    }

    private fun exportScaled(sprite: SimpleSprite, scale: Int, outFile: File) {
        val sw = sprite.width * scale; val sh = sprite.height * scale
        val img = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until sh) for (x in 0 until sw) {
            val isLight = ((x / (scale * 4)) + (y / (scale * 4))) % 2 == 0
            img.setRGB(x, y, if (isLight) 0xFF3A3A4A.toInt() else 0xFF2A2A3A.toInt())
        }
        for (y in 0 until sprite.height) for (x in 0 until sprite.width) {
            val p = sprite.pixels[y * sprite.width + x]
            if ((p ushr 24) and 0xFF > 0) {
                for (sy in 0 until scale) for (sx in 0 until scale)
                    img.setRGB(x * scale + sx, y * scale + sy, p)
            }
        }
        ImageIO.write(img, "PNG", outFile)
    }

    private data class SimpleSprite(val width: Int, val height: Int, val pixels: IntArray)
}
