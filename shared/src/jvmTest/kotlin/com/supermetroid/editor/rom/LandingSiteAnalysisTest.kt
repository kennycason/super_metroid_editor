package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Deep analysis of Landing Site level data to verify correctness
 */
class LandingSiteAnalysisTest {
    
    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()
    
    @Test
    fun `analyze Landing Site block types`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val levelData = parser.decompressLZ2(room.levelDataPtr)
        
        val output = File("/tmp/landing_site_analysis.txt")
        val sb = StringBuilder()
        
        val blocksWide = room.width * 16  // 144
        val blocksTall = room.height * 16 // 80
        val tileDataStart = 2
        
        sb.appendLine("=== Landing Site (9×5, 144×80 blocks) ===")
        sb.appendLine("Decompressed: ${levelData.size} bytes")
        sb.appendLine()
        
        // Count block types
        val typeCounts = IntArray(16)
        val typeNames = arrayOf(
            "Air", "Slope", "Air(Xray)", "Treadmill", "Air(shot)", "H-ext", "Unused6", "Unused7",
            "Solid", "Door", "Spike", "Crumble", "Shot blk", "V-ext", "Grapple", "Bomb blk"
        )
        
        for (i in 0 until blocksWide * blocksTall) {
            val offset = tileDataStart + i * 2
            if (offset + 1 >= levelData.size) break
            val lo = levelData[offset].toInt() and 0xFF
            val hi = levelData[offset + 1].toInt() and 0xFF
            val blockType = (hi shr 4) and 0x0F
            typeCounts[blockType]++
        }
        
        sb.appendLine("Block type distribution:")
        for (t in 0..15) {
            if (typeCounts[t] > 0) {
                val pct = typeCounts[t] * 100.0 / (blocksWide * blocksTall)
                sb.appendLine("  Type 0x${t.toString(16)}: ${typeNames[t]} = ${typeCounts[t]} (${String.format("%.1f", pct)}%)")
            }
        }
        sb.appendLine()
        
        // Render ASCII map showing block types
        // Use simple chars: . = air, # = solid, / = slope, D = door, ! = spike, ? = other
        sb.appendLine("ASCII map (16x16 blocks = 1 screen, each char = 1 block):")
        sb.appendLine("Legend: . = air, # = solid, / = slope, D = door, ! = spike, B = bomb, S = shot, C = crumble, * = other")
        sb.appendLine()
        
        for (by in 0 until blocksTall) {
            if (by % 16 == 0) {
                sb.appendLine("--- Screen row ${by / 16} (y=$by-${by + 15}) ---")
            }
            val row = StringBuilder()
            for (bx in 0 until blocksWide) {
                if (bx % 16 == 0 && bx > 0) row.append('|')
                
                val tileIdx = by * blocksWide + bx
                val offset = tileDataStart + tileIdx * 2
                if (offset + 1 >= levelData.size) {
                    row.append('?')
                    continue
                }
                val lo = levelData[offset].toInt() and 0xFF
                val hi = levelData[offset + 1].toInt() and 0xFF
                val blockType = (hi shr 4) and 0x0F
                
                row.append(when (blockType) {
                    0x0 -> '.'  // Air
                    0x1 -> '/'  // Slope
                    0x2 -> ','  // Air (X-ray)
                    0x3 -> '~'  // Treadmill
                    0x4 -> ':'  // Air (shootable)
                    0x5 -> '-'  // H-extend
                    0x8 -> '#'  // Solid
                    0x9 -> 'D'  // Door
                    0xA -> '!'  // Spike
                    0xB -> 'C'  // Crumble
                    0xC -> 'S'  // Shot block
                    0xD -> '|'  // V-extend
                    0xE -> 'G'  // Grapple
                    0xF -> 'B'  // Bomb block
                    else -> '*'
                })
            }
            sb.appendLine(row.toString())
        }
        
        output.writeText(sb.toString())
        println("Analysis written to ${output.absolutePath}")
    }
}
