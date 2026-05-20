package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Deep tracing test to understand exactly what spritemaps we're finding
 * and compare instruction list entries against known good data.
 */
class SpritemapTracingTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `trace Zoomer instruction list and all spritemaps`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)

        // Zoomer species $DCFF
        val speciesId = 0xDCFF
        val headerPc = rp.snesToPc(0xA00000 or speciesId)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
        val initAiPtr = readU16(rom, headerPc + 0x12)

        println("=== Zoomer ($${speciesId.toString(16).uppercase()}) ===")
        println("AI Bank: \$${aiBank.toString(16).uppercase()}")
        println("Init AI Ptr: \$${initAiPtr.toString(16).uppercase()}")
        println("Init SNES: \$${aiBank.toString(16).uppercase()}:${initAiPtr.toString(16).uppercase()}")

        val initPc = rp.snesToPc((aiBank shl 16) or initAiPtr)
        println("Init PC: 0x${initPc.toString(16)}")

        // Dump first 40 bytes of init function as hex
        print("Init bytes: ")
        for (i in 0 until 40) {
            print("${(rom[initPc + i].toInt() and 0xFF).toString(16).padStart(2, '0')} ")
        }
        println()

        // Now find all animation frames
        val frames = smap.findAnimationFrames(speciesId)
        println("\nAnimation frames: ${frames.size}")
        for ((i, frame) in frames.withIndex()) {
            println("  Frame $i: duration=${frame.duration}, smap at \$${frame.spritemap.snesAddress.toString(16).uppercase()}")
            println("    Entries: ${frame.spritemap.entries.size}")
            for ((j, entry) in frame.spritemap.entries.withIndex()) {
                println("      [$j] tile=0x${entry.tileNum.toString(16)} x=${entry.xOffset} y=${entry.yOffset} " +
                    "${if (entry.is16x16) "16x16" else "8x8"} hFlip=${entry.hFlip} vFlip=${entry.vFlip}")
            }
        }

        // Also find the default spritemap
        val defaultSmap = smap.findDefaultSpritemap(speciesId)
        if (defaultSmap != null) {
            println("\nDefault spritemap: \$${defaultSmap.snesAddress.toString(16).uppercase()}")
            println("  Bounding box: x=[${defaultSmap.entries.minOf { it.xOffset }}, ${defaultSmap.entries.maxOf { it.xOffset + (if (it.is16x16) 16 else 8) }})")
            println("  Bounding box: y=[${defaultSmap.entries.minOf { it.yOffset }}, ${defaultSmap.entries.maxOf { it.yOffset + (if (it.is16x16) 16 else 8) }})")
        }
    }

    @Test
    fun `trace Sidehopper instruction list`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)

        val speciesId = 0xD93F
        val headerPc = rp.snesToPc(0xA00000 or speciesId)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
        val initAiPtr = readU16(rom, headerPc + 0x12)

        println("=== Sidehopper ($${speciesId.toString(16).uppercase()}) ===")
        println("AI Bank: \$${aiBank.toString(16).uppercase()}")
        println("Init AI Ptr: \$${initAiPtr.toString(16).uppercase()}")

        val initPc = rp.snesToPc((aiBank shl 16) or initAiPtr)
        print("Init bytes: ")
        for (i in 0 until 60) {
            print("${(rom[initPc + i].toInt() and 0xFF).toString(16).padStart(2, '0')} ")
        }
        println()

        val frames = smap.findAnimationFrames(speciesId)
        println("\nAnimation frames: ${frames.size}")
        for ((i, frame) in frames.withIndex()) {
            println("  Frame $i: duration=${frame.duration}, smap at \$${frame.spritemap.snesAddress.toString(16).uppercase()}")
            for ((j, entry) in frame.spritemap.entries.withIndex()) {
                println("      [$j] tile=0x${entry.tileNum.toString(16)} x=${entry.xOffset} y=${entry.yOffset} " +
                    "${if (entry.is16x16) "16x16" else "8x8"} hFlip=${entry.hFlip} vFlip=${entry.vFlip}")
            }
        }
    }

    @Test
    fun `look up known Zoomer spritemap from SM disassembly`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)

        // From SM disassembly, Zoomer (species $DCFF) is in bank $B3
        // Let's try some known addresses for Zoomer spritemaps
        // The Zoomer init function should set $0F92,x to point to the instruction list

        // Let's scan bank $B3 for valid spritemaps that look like a Zoomer
        // A Zoomer should be roughly 16x16 with ~4 tiles forming a round shape
        val rom = rp.getRomData()
        val headerPc = rp.snesToPc(0xA00000 or 0xDCFF)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
        println("Zoomer AI bank: \$${aiBank.toString(16).uppercase()}")

        // Scan for spritemaps in the AI bank that have reasonable dimensions
        // A Zoomer spritemap should have:
        // - 2-6 entries
        // - Mix of 16x16 and 8x8
        // - Bounding box roughly 16-24 wide, 12-20 tall
        var found = 0
        for (addr in 0x8000..0xFFFF step 2) {
            val smapSnes = (aiBank shl 16) or addr
            val parsed = smap.parseSpritemap(smapSnes) ?: continue
            if (parsed.entries.size !in 2..8) continue

            val minX = parsed.entries.minOf { it.xOffset }
            val maxX = parsed.entries.maxOf { it.xOffset + (if (it.is16x16) 16 else 8) }
            val minY = parsed.entries.minOf { it.yOffset }
            val maxY = parsed.entries.maxOf { it.yOffset + (if (it.is16x16) 16 else 8) }
            val w = maxX - minX
            val h = maxY - minY

            // Looking for roughly square or landscape spritemaps (like a Zoomer)
            if (w in 12..32 && h in 8..24 && w >= h * 0.7) {
                // Check if tile numbers are in reasonable range
                val tiles = parsed.entries.map { it.tileNum and 0xFF }
                if (tiles.all { it < 48 }) {
                    println("Candidate at \$${aiBank.toString(16)}:${addr.toString(16).padStart(4, '0')}: " +
                        "${parsed.entries.size} entries, ${w}x${h}, tiles=$tiles")
                    found++
                    if (found > 20) break
                }
            }
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
