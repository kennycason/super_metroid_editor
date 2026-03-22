package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Diagnostic for understanding Crocomire's full sprite rendering system.
 */
class CrocomireDiagnostic {

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
    fun `dump Crocomire animation frames`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val speciesId = 0xDDBF

        // Dump default spritemap
        val defaultSmap = smap.findDefaultSpritemap(speciesId)
        if (defaultSmap != null) {
            println("Default spritemap: ${defaultSmap.entries.size} OAM entries at \$${defaultSmap.snesAddress.toString(16).uppercase()}")
            for ((i, e) in defaultSmap.entries.withIndex()) {
                val size = if (e.is16x16) "16x16" else "8x8"
                println("  [$i] tile=${e.tileNum} x=${e.xOffset} y=${e.yOffset} $size hFlip=${e.hFlip} vFlip=${e.vFlip} pal=${e.palRow}")
            }
        } else {
            println("No default spritemap found")
        }

        println()

        // Dump all animation frames
        val frames = smap.findAnimationFrames(speciesId, maxFrames = 64)
        println("Animation frames: ${frames.size}")
        for ((i, frame) in frames.withIndex()) {
            println("  Frame $i: duration=${frame.duration} entries=${frame.spritemap.entries.size} addr=\$${frame.spritemap.snesAddress.toString(16).uppercase()}")
            for ((j, e) in frame.spritemap.entries.withIndex()) {
                val size = if (e.is16x16) "16x16" else "8x8"
                println("    [$j] tile=${e.tileNum} x=${e.xOffset} y=${e.yOffset} $size")
            }
        }
    }

    @Test
    fun `scan all Crocomire instruction lists`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val speciesId = 0xDDBF
        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF

        println("Crocomire AI bank: \$${aiBank.toString(16).uppercase()}")

        // Scan the entire init function area for all STA $0F92,x patterns
        val initAiPtr = readU16(rom, headerPc + 0x12)
        val initPc = rp.snesToPc((aiBank shl 16) or initAiPtr)
        println("Init function at \$${(aiBank shl 16 or initAiPtr).toString(16).uppercase()} (PC: $initPc)")

        // Scan a wide area around the init function for instruction list pointers
        // Look for LDA #imm; STA $0F92,x patterns
        val scanStart = initPc
        val scanEnd = (initPc + 2000).coerceAtMost(rom.size - 6)

        val instrListPtrs = mutableSetOf<Int>()
        for (i in scanStart until scanEnd) {
            // LDA #imm16 (A9 xx xx) followed by STA $0F92,x (9D 92 0F)
            if (rom[i].toInt() and 0xFF == 0xA9 &&
                i + 5 < rom.size &&
                rom[i + 3].toInt() and 0xFF == 0x9D &&
                rom[i + 4].toInt() and 0xFF == 0x92 &&
                rom[i + 5].toInt() and 0xFF == 0x0F) {
                val ptr = readU16(rom, i + 1)
                instrListPtrs.add(ptr)
                println("  Found instruction list pointer \$${ptr.toString(16).uppercase()} at PC ${i - scanStart}")
            }
        }

        // For each instruction list, try to parse spritemaps
        val smap = EnemySpritemap(rp)
        for (ptr in instrListPtrs.sorted()) {
            println("\n--- Instruction list at \$${aiBank.toString(16).uppercase()}:${ptr.toString(16).uppercase()} ---")
            val ilPc = rp.snesToPc((aiBank shl 16) or ptr)
            if (ilPc < 0) { println("  Invalid address"); continue }

            var offset = 0
            var frameIdx = 0
            for (f in 0 until 32) {
                if (ilPc + offset + 3 >= rom.size) break
                val word0 = readU16(rom, ilPc + offset)
                val word1 = readU16(rom, ilPc + offset + 2)
                offset += 4

                if (word0 == 0 && word1 == 0) { println("  [End] null terminator"); break }

                if (word0 >= 0x8000) {
                    val opName = when (word0) {
                        0x8000 -> "GOTO"
                        0x800A -> "TIMER"
                        else -> "CTRL"
                    }
                    println("  [$frameIdx] $opName \$${word0.toString(16).uppercase()} arg=\$${word1.toString(16).uppercase()}")
                    if (word0 == 0x8000) break
                    frameIdx++
                    continue
                }

                val smapSnes = (aiBank shl 16) or word1
                val parsedSmap = smap.parseSpritemap(smapSnes)
                val entries = parsedSmap?.entries?.size ?: 0
                println("  [$frameIdx] duration=$word0 spritemap=\$${word1.toString(16).uppercase()} entries=$entries")

                // Show max tile range for large spritemaps
                if (parsedSmap != null && entries > 10) {
                    val minTile = parsedSmap.entries.minOf { it.tileNum and 0xFF }
                    val maxTile = parsedSmap.entries.maxOf { it.tileNum and 0xFF }
                    val minX = parsedSmap.entries.minOf { it.xOffset }
                    val maxX = parsedSmap.entries.maxOf { it.xOffset + if (it.is16x16) 16 else 8 }
                    val minY = parsedSmap.entries.minOf { it.yOffset }
                    val maxY = parsedSmap.entries.maxOf { it.yOffset + if (it.is16x16) 16 else 8 }
                    println("        tiles=$minTile..$maxTile bounds=${maxX-minX}x${maxY-minY}px")
                }

                frameIdx++
            }
        }
    }

    @Test
    fun `render all Crocomire spritemaps found`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)
        val speciesId = 0xDDBF

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return
        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF

        // Find ALL instruction list pointers
        val initAiPtr = readU16(rom, headerPc + 0x12)
        val initPc = rp.snesToPc((aiBank shl 16) or initAiPtr)
        val scanEnd = (initPc + 2000).coerceAtMost(rom.size - 6)

        val allSpritemaps = mutableListOf<EnemySpritemap.Spritemap>()
        val instrListPtrs = mutableSetOf<Int>()

        for (i in initPc until scanEnd) {
            if (rom[i].toInt() and 0xFF == 0xA9 &&
                i + 5 < rom.size &&
                rom[i + 3].toInt() and 0xFF == 0x9D &&
                rom[i + 4].toInt() and 0xFF == 0x92 &&
                rom[i + 5].toInt() and 0xFF == 0x0F) {
                instrListPtrs.add(readU16(rom, i + 1))
            }
        }

        // Parse all unique spritemaps from all instruction lists
        val seenAddrs = mutableSetOf<Int>()
        for (ptr in instrListPtrs) {
            val ilPc = rp.snesToPc((aiBank shl 16) or ptr)
            if (ilPc < 0) continue

            var offset = 0
            for (f in 0 until 64) {
                if (ilPc + offset + 3 >= rom.size) break
                val word0 = readU16(rom, ilPc + offset)
                val word1 = readU16(rom, ilPc + offset + 2)
                offset += 4
                if (word0 == 0 && word1 == 0) break
                if (word0 == 0x8000) break
                if (word0 >= 0x8000) continue

                if (word1 !in seenAddrs) {
                    seenAddrs.add(word1)
                    val smapSnes = (aiBank shl 16) or word1
                    val parsed = smap.parseSpritemap(smapSnes)
                    if (parsed != null) {
                        allSpritemaps.add(parsed)
                    }
                }
            }
        }

        println("Found ${allSpritemaps.size} unique spritemaps across ${instrListPtrs.size} instruction lists")

        // Render each and save
        val outDir = File("/tmp/crocomire_spritemaps")
        outDir.mkdirs()

        for ((i, sm) in allSpritemaps.withIndex()) {
            val assembled = smap.renderSpritemap(sm, tileData, palette) ?: continue
            val bi = BufferedImage(assembled.width, assembled.height, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, assembled.width, assembled.height, assembled.pixels, 0, assembled.width)
            val file = File(outDir, "smap_${i}_${sm.entries.size}entries_${assembled.width}x${assembled.height}.png")
            ImageIO.write(bi, "png", file)
            println("  Saved $file (${sm.entries.size} entries, ${assembled.width}x${assembled.height})")
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
