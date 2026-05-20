package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders scaled-up sprite exports for visual debugging.
 * Also dumps per-pixel layout showing which tile contributes to each region.
 */
class SpriteRenderScaledTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `export scaled sprites for visual inspection`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        val enemies = mapOf(
            0xDCFF to "Zoomer",
            0xD93F to "Sidehopper",
            0xD7FF to "Skree",
            0xCFFF to "Cacatac",
        )

        val outputDir = File("build/test-sprites")
        outputDir.mkdirs()

        for ((speciesId, name) in enemies) {
            val defaultSmap = smap.findDefaultSpritemap(speciesId) ?: continue
            val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: continue
            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: continue

            val assembled = smap.renderSpritemap(defaultSmap, tileData, palette) ?: continue

            // Create 8x scaled version with grid overlay showing tile boundaries
            val scale = 8
            val sw = assembled.width * scale
            val sh = assembled.height * scale
            val scaledImg = BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB)

            // Dark checker background
            for (y in 0 until sh) {
                for (x in 0 until sw) {
                    val isLight = ((x / (scale * 4)) + (y / (scale * 4))) % 2 == 0
                    scaledImg.setRGB(x, y, if (isLight) 0xFF3A3A4A.toInt() else 0xFF2A2A3A.toInt())
                }
            }

            // Draw scaled pixels
            for (y in 0 until assembled.height) {
                for (x in 0 until assembled.width) {
                    val pixel = assembled.pixels[y * assembled.width + x]
                    if ((pixel ushr 24) and 0xFF > 0) {
                        for (sy in 0 until scale) {
                            for (sx in 0 until scale) {
                                scaledImg.setRGB(x * scale + sx, y * scale + sy, pixel)
                            }
                        }
                    }
                }
            }

            // Draw tile boundary grid (red lines every 8px, blue every 16px)
            for (entry in defaultSmap.entries) {
                val size = if (entry.is16x16) 16 else 8
                val ex = (entry.xOffset - defaultSmap.entries.minOf { it.xOffset }) * scale
                val ey = (entry.yOffset - defaultSmap.entries.minOf { it.yOffset }) * scale
                val ew = size * scale
                val eh = size * scale

                // Draw entry boundary in yellow
                for (i in 0 until ew) {
                    if (ey in 0 until sh) scaledImg.setRGB((ex + i).coerceIn(0, sw - 1), ey.coerceIn(0, sh - 1), 0xFFFFFF00.toInt())
                    if (ey + eh - 1 in 0 until sh) scaledImg.setRGB((ex + i).coerceIn(0, sw - 1), (ey + eh - 1).coerceIn(0, sh - 1), 0xFFFFFF00.toInt())
                }
                for (i in 0 until eh) {
                    if (ex in 0 until sw) scaledImg.setRGB(ex.coerceIn(0, sw - 1), (ey + i).coerceIn(0, sh - 1), 0xFFFFFF00.toInt())
                    if (ex + ew - 1 in 0 until sw) scaledImg.setRGB((ex + ew - 1).coerceIn(0, sw - 1), (ey + i).coerceIn(0, sh - 1), 0xFFFFFF00.toInt())
                }
            }

            val hexId = speciesId.toString(16).uppercase()
            ImageIO.write(scaledImg, "PNG", File(outputDir, "${hexId}_${name}_scaled8x.png"))
            println("Exported 8x scaled: ${hexId}_${name}_scaled8x.png (${sw}x${sh})")

            // Also check: what does direction table look like?
            // Dump the instruction list pointer and first few words
            val rom = rp.getRomData()
            val headerPc = rp.snesToPc(0xA00000 or speciesId)
            val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
            println("  $name: AI bank=\$${aiBank.toString(16).uppercase()}, smap SNES=\$${defaultSmap.snesAddress.toString(16).uppercase()}")
        }
    }

    @Test
    fun `check Zoomer all 4 direction spritemaps`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)

        // Zoomer init reads from direction table at $A3:E2CC
        // The init code: LDA $0F92,x; AND #$0003; ASL A; TAY; LDA $E2CC,y; STA $0F92,x
        val aiBank = 0xA3
        val tableSnes = (aiBank shl 16) or 0xE2CC
        val tablePc = rp.snesToPc(tableSnes)

        println("Zoomer direction table at \$A3:E2CC (PC 0x${tablePc.toString(16)}):")
        for (dir in 0 until 4) {
            val instrListPtr = readU16(rom, tablePc + dir * 2)
            println("  Direction $dir: instruction list at \$${aiBank.toString(16)}:${instrListPtr.toString(16).padStart(4, '0')}")

            // Parse the first spritemap from this instruction list
            val ilPc = rp.snesToPc((aiBank shl 16) or instrListPtr)
            if (ilPc < 0) continue

            // Read first frame: [timer(2), spritemap_ptr(2)]
            val timer = readU16(rom, ilPc)
            val smapPtr = readU16(rom, ilPc + 2)
            println("    First frame: timer=$timer, smap_ptr=\$${smapPtr.toString(16)}")

            val parsed = smap.parseSpritemap((aiBank shl 16) or smapPtr) ?: continue
            println("    Entries: ${parsed.entries.size}")

            val minX = parsed.entries.minOf { it.xOffset }
            val maxX = parsed.entries.maxOf { it.xOffset + (if (it.is16x16) 16 else 8) }
            val minY = parsed.entries.minOf { it.yOffset }
            val maxY = parsed.entries.maxOf { it.yOffset + (if (it.is16x16) 16 else 8) }
            println("    Bounds: ${maxX - minX}x${maxY - minY}")

            for ((j, entry) in parsed.entries.withIndex()) {
                println("      [$j] tile=0x${entry.tileNum.toString(16)} x=${entry.xOffset} y=${entry.yOffset} " +
                    "${if (entry.is16x16) "16x16" else "8x8"} hFlip=${entry.hFlip} vFlip=${entry.vFlip}")
            }
        }

        // Render all 4 directions
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xDCFF) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xDCFF) ?: return
        val outputDir = File("build/test-sprites")
        outputDir.mkdirs()

        for (dir in 0 until 4) {
            val instrListPtr = readU16(rom, tablePc + dir * 2)
            val ilPc = rp.snesToPc((aiBank shl 16) or instrListPtr)
            if (ilPc < 0) continue
            val smapPtr = readU16(rom, ilPc + 2)
            val timer = readU16(rom, ilPc)
            if (timer == 0 || timer >= 0x8000) continue
            val parsed = smap.parseSpritemap((aiBank shl 16) or smapPtr) ?: continue
            val assembled = smap.renderSpritemap(parsed, tileData, palette) ?: continue

            val scale = 8
            val img = BufferedImage(assembled.width * scale, assembled.height * scale, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until assembled.height) {
                for (x in 0 until assembled.width) {
                    val pixel = assembled.pixels[y * assembled.width + x]
                    val color = if ((pixel ushr 24) and 0xFF > 0) pixel
                        else if (((x / 4) + (y / 4)) % 2 == 0) 0xFF3A3A4A.toInt() else 0xFF2A2A3A.toInt()
                    for (sy in 0 until scale) for (sx in 0 until scale)
                        img.setRGB(x * scale + sx, y * scale + sy, color)
                }
            }
            ImageIO.write(img, "PNG", File(outputDir, "DCFF_Zoomer_dir${dir}_8x.png"))
            println("Exported dir $dir: ${assembled.width}x${assembled.height}")
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
