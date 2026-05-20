package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Debug tile matching: examine why pattern-independent matching fails.
 */
class SpritemapDerivationTest2 {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `debug pattern matching - compare one PNG block vs ROM tiles`() {
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

        val pngFile = File("../desktopApp/src/jvmMain/resources/enemies/E4BF.png")
        if (!pngFile.exists()) { println("E4BF.png not found"); return }
        val img = javax.imageio.ImageIO.read(pngFile)
        val w = img.width
        val h = img.height
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)

        // Pick a non-transparent block from the PNG (e.g., center area)
        // The body center should have content
        val testBlocks = listOf(
            32 to 40, // center-ish
            24 to 32,
            16 to 24,
            8 to 8,
            32 to 48
        )

        for ((testX, testY) in testBlocks) {
            if (testX + 8 > w || testY + 8 > h) continue
            println("\n=== PNG block at ($testX, $testY) ===")

            // Show PNG block as ARGB values
            val pngBlock = IntArray(64)
            var nonTransparent = 0
            for (py in 0 until 8) {
                val sb = StringBuilder("  ")
                for (px in 0 until 8) {
                    val argb = pixels[(testY + py) * w + (testX + px)]
                    pngBlock[py * 8 + px] = argb
                    val a = (argb ushr 24) and 0xFF
                    if (a > 128) {
                        nonTransparent++
                        sb.append("%08x ".format(argb.toLong() and 0xFFFFFFFFL))
                    } else {
                        sb.append("........ ")
                    }
                }
                println(sb)
            }
            println("  Non-transparent: $nonTransparent/64")
            if (nonTransparent == 0) continue

            // Normalize PNG block
            val pngNorm = normalizeBlock(pngBlock, true)
            println("  PNG normalized: ${pngNorm.toList()}")

            // Count unique colors in PNG block
            val uniqueColors = pngBlock.filter { ((it ushr 24) and 0xFF) > 128 }
                .map { it or (0xFF shl 24) }.toSet()
            println("  Unique opaque colors: ${uniqueColors.size}")

            // Compare against first few ROM tiles
            println("  Comparing against ROM tiles...")
            var bestTile = -1
            var bestDist = Int.MAX_VALUE
            for (t in 0 until gfx.getTileCount()) {
                val tileIndices = IntArray(64)
                for (py in 0 until 8) for (px in 0 until 8)
                    tileIndices[py * 8 + px] = gfx.readPixelIndex(t, px, py)

                val tileNorm = normalizeBlock(tileIndices, false)

                val dist = hammingDistance(pngNorm, tileNorm)
                if (dist < bestDist) {
                    bestDist = dist
                    bestTile = t
                }
                if (dist == 0) break
            }

            println("  Best match: tile $bestTile (hamming distance = $bestDist/64)")
            if (bestTile >= 0 && bestDist < 64) {
                // Show the matching tile
                println("  ROM tile $bestTile palette indices:")
                for (py in 0 until 8) {
                    val sb = StringBuilder("    ")
                    for (px in 0 until 8) {
                        sb.append("%x ".format(gfx.readPixelIndex(bestTile, px, py)))
                    }
                    println(sb)
                }
                val tileIndices = IntArray(64)
                for (py in 0 until 8) for (px in 0 until 8)
                    tileIndices[py * 8 + px] = gfx.readPixelIndex(bestTile, px, py)
                val tileNorm = normalizeBlock(tileIndices, false)
                println("  ROM tile normalized: ${tileNorm.toList()}")
            }
        }
    }

    @Test
    fun `investigate actual SNES palette from ROM for Phantoon`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        // From species header, palette pointer is at +$36 (3-byte long pointer)
        val headerPc = parser.snesToPc(0xA0E4BF)
        val palByte0 = rom[headerPc + 0x36].toInt() and 0xFF
        val palByte1 = rom[headerPc + 0x37].toInt() and 0xFF
        val palByte2 = rom[headerPc + 0x38].toInt() and 0xFF
        val palSnes = (palByte2 shl 16) or (palByte1 shl 8) or palByte0
        println("Palette long pointer: \$${palSnes.toString(16).uppercase()} (bytes: %02x %02x %02x)".format(palByte0, palByte1, palByte2))

        // Also check bytes 02-03 which Kejardon says is "palette pointer"
        val palPtr0203 = (rom[headerPc + 0x02].toInt() and 0xFF) or
            ((rom[headerPc + 0x03].toInt() and 0xFF) shl 8)
        println("Species header +02-03: \$${palPtr0203.toString(16).uppercase()}")

        // The palette at +$36 is a 3-byte SNES address
        try {
            val palPc = parser.snesToPc(palSnes)
            println("Palette PC: 0x${palPc.toString(16)}")

            // SNES palette is 16 colors x 2 bytes (BGR555) = 32 bytes
            println("\nSNES palette at \$${palSnes.toString(16).uppercase()}:")
            for (i in 0 until 16) {
                val lo = rom[palPc + i * 2].toInt() and 0xFF
                val hi = rom[palPc + i * 2 + 1].toInt() and 0xFF
                val bgr555 = (hi shl 8) or lo
                val argb = EnemySpriteGraphics.snesColorToArgb(bgr555)
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                println("  [$i] BGR555=0x${bgr555.toString(16).padStart(4, '0')} → ARGB=0x${(argb.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')} (R=$r G=$g B=$b)")
            }

            // Now try rendering tile 0 with this actual palette and see if it matches the PNG
            val gfx = EnemySpriteGraphics(parser)
            gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS)

            val actualPalette = IntArray(16)
            actualPalette[0] = 0x00000000
            for (i in 1 until 16) {
                val lo = rom[palPc + i * 2].toInt() and 0xFF
                val hi = rom[palPc + i * 2 + 1].toInt() and 0xFF
                val bgr555 = (hi shl 8) or lo
                actualPalette[i] = EnemySpriteGraphics.snesColorToArgb(bgr555)
            }

            println("\nActual SNES palette as ARGB:")
            for (i in actualPalette.indices) {
                println("  [$i] 0x${(actualPalette[i].toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
            }

            // Render tile 0 with actual palette
            println("\nTile 0 rendered with actual SNES palette:")
            for (py in 0 until 8) {
                val sb = StringBuilder("  ")
                for (px in 0 until 8) {
                    val ci = gfx.readPixelIndex(0, px, py)
                    if (ci == 0) sb.append("........ ")
                    else sb.append("%08x ".format(actualPalette[ci].toLong() and 0xFFFFFFFFL))
                }
                println(sb)
            }
        } catch (e: Exception) {
            println("Error reading palette: ${e.message}")
        }
    }

    private fun normalizeBlock(values: IntArray, isArgb: Boolean): IntArray {
        val result = IntArray(64)
        val colorMap = mutableMapOf<Int, Int>()
        var nextIdx = 0

        for (i in values.indices) {
            val v = values[i]
            if (isArgb) {
                val alpha = (v ushr 24) and 0xFF
                if (alpha < 128) { result[i] = -1; continue }
                val opaque = v or (0xFF shl 24)
                result[i] = colorMap.getOrPut(opaque) { nextIdx++ }
            } else {
                if (v == 0) { result[i] = -1; continue }
                result[i] = colorMap.getOrPut(v) { nextIdx++ }
            }
        }
        return result
    }

    private fun hammingDistance(a: IntArray, b: IntArray): Int {
        var dist = 0
        for (i in a.indices) if (a[i] != b[i]) dist++
        return dist
    }
}
