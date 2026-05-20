package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders ALL animation frames from ALL instruction list table entries
 * for the Sidehopper, to find the correct standing/idle frame.
 */
class SidehopperAllFramesTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `render all Sidehopper frames from all table entries`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)
        val speciesId = 0xD93F
        val aiBank = 0xA3

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return

        val outputDir = File("build/test-sprites/sidehopper-frames")
        outputDir.mkdirs()

        // Table at $AACA has 8 entries
        val tablePc = rp.snesToPc(0xA3AACA)
        println("Sidehopper tile data: ${tileData.size} bytes (${tileData.size / 32} tiles)")

        for (tableIdx in 0 until 8) {
            val instrListPtr = readU16(rom, tablePc + tableIdx * 2)
            val ilPc = rp.snesToPc((aiBank shl 16) or instrListPtr)
            if (ilPc < 0) continue

            println("\n=== Table[$tableIdx] → \$A3:${instrListPtr.toString(16)} ===")

            // Scan for valid animation frames in this instruction list
            var frameCount = 0
            var offset = 0
            val seenSmaps = mutableSetOf<Int>()

            for (entry in 0 until 64) {
                if (ilPc + offset + 3 >= rom.size) break
                val w0 = readU16(rom, ilPc + offset)
                val w1 = readU16(rom, ilPc + offset + 2)
                offset += 4

                if (w0 == 0 && w1 == 0) break
                if (w0 >= 0x8000) continue  // handler/control
                if (w0 == 0) continue
                if (w1 < 0x8000) continue   // must be in LoROM range

                if (w1 in seenSmaps) continue
                seenSmaps.add(w1)

                val parsed = smap.parseSpritemap((aiBank shl 16) or w1) ?: continue
                val assembled = smap.renderSpritemap(parsed, tileData, palette) ?: continue

                val nonTrans = assembled.pixels.count { (it ushr 24) and 0xFF > 0 }
                val fillRate = nonTrans * 100 / (assembled.width * assembled.height)
                println("  Frame $frameCount: smap=\$${w1.toString(16)} ${assembled.width}x${assembled.height} fill=$fillRate% entries=${parsed.entries.size}")

                if (fillRate > 5) {
                    exportScaled(assembled, 8, File(outputDir, "t${tableIdx}_f${frameCount}_${w1.toString(16)}.png"))
                    frameCount++
                }
            }

            // Also try handler params as secondary instruction lists
            offset = 0
            for (entry in 0 until 16) {
                if (ilPc + offset + 3 >= rom.size) break
                val w0 = readU16(rom, ilPc + offset)
                val w1 = readU16(rom, ilPc + offset + 2)
                offset += 4

                if (w0 == 0 && w1 == 0) break
                if (w0 < 0x8000) continue  // not a handler
                if (w1 < 0x8000 || w1 > 0xFFFF) continue

                // Try w1 as a secondary instruction list
                val subPc = rp.snesToPc((aiBank shl 16) or w1)
                if (subPc < 0) continue

                var subOffset = 0
                for (subEntry in 0 until 32) {
                    if (subPc + subOffset + 3 >= rom.size) break
                    val sw0 = readU16(rom, subPc + subOffset)
                    val sw1 = readU16(rom, subPc + subOffset + 2)
                    subOffset += 4

                    if (sw0 == 0 && sw1 == 0) break
                    if (sw0 >= 0x8000) continue
                    if (sw0 == 0) continue
                    if (sw1 < 0x8000) continue

                    if (sw1 in seenSmaps) continue
                    seenSmaps.add(sw1)

                    val parsed = smap.parseSpritemap((aiBank shl 16) or sw1) ?: continue
                    val assembled = smap.renderSpritemap(parsed, tileData, palette) ?: continue

                    val nonTrans = assembled.pixels.count { (it ushr 24) and 0xFF > 0 }
                    val fillRate = nonTrans * 100 / (assembled.width * assembled.height)
                    println("  Sub-frame: handler=\$${w1.toString(16)} smap=\$${sw1.toString(16)} ${assembled.width}x${assembled.height} fill=$fillRate%")

                    if (fillRate > 5) {
                        exportScaled(assembled, 8, File(outputDir, "t${tableIdx}_sub_${sw1.toString(16)}.png"))
                        frameCount++
                    }
                }
            }
        }
    }

    private fun exportScaled(sprite: EnemySpritemap.AssembledSprite, scale: Int, outFile: File) {
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

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
