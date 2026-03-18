package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

class KraidTileDataTest {
    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

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
    fun `check kraid tile data decompression`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val sb = StringBuilder()

        val tilePc = rp.snesToPc(0xB9FA38)
        sb.appendLine("Kraid tile data at PC: 0x${tilePc.toString(16)}")

        // Check first few raw bytes before decompression
        sb.appendLine("First 32 raw bytes:")
        for (i in 0 until 32) {
            sb.append("%02X ".format(rom[tilePc + i].toInt() and 0xFF))
        }
        sb.appendLine()

        // Decompress
        val decompressed = rp.decompressLZ5AtPc(tilePc)
        sb.appendLine("Decompressed size: ${decompressed.size} bytes (${decompressed.size / 32} tiles)")

        // Check first tile (tile 0x100 = first Kraid tile)
        sb.appendLine("\nFirst tile (0x100) raw bytes:")
        for (i in 0 until 32) {
            sb.append("%02X ".format(decompressed[i].toInt() and 0xFF))
        }
        sb.appendLine()

        // Check tile 0x10 (belly detail tile)
        val bellyOffset = 0x10 * 32 // tile 0x110 - 0x100 = 0x10
        sb.appendLine("\nBelly tile 0x110 (Kraid tile 0x10) raw bytes:")
        for (i in 0 until 32) {
            if (bellyOffset + i < decompressed.size) {
                sb.append("%02X ".format(decompressed[bellyOffset + i].toInt() and 0xFF))
            }
        }
        sb.appendLine()

        // Check if data looks like 4bpp tiles (should have varied byte values)
        val uniqueBytes = decompressed.toSet().size
        sb.appendLine("\nUnique byte values in decompressed data: $uniqueBytes")

        // Check if it might not be LZ5 - try reading raw
        sb.appendLine("\nRaw tile data at same offset (first tile, 32 bytes):")
        for (i in 0 until 32) {
            sb.append("%02X ".format(rom[tilePc + i].toInt() and 0xFF))
        }
        sb.appendLine()

        // Count non-zero tiles
        var nonZeroTiles = 0
        for (t in 0 until decompressed.size / 32) {
            var hasData = false
            for (b in 0 until 32) {
                if (decompressed[t * 32 + b].toInt() != 0) { hasData = true; break }
            }
            if (hasData) nonZeroTiles++
        }
        sb.appendLine("Non-zero tiles: $nonZeroTiles / ${decompressed.size / 32}")

        // Check which tile ranges are used by body tilemaps
        sb.appendLine("\n=== Body initial tilemap tile index ranges ===")
        val bodyPc = rp.snesToPc(0xA797C8)
        val kraidTileRefs = mutableSetOf<Int>()
        val roomTileRefs = mutableSetOf<Int>()
        for (r in 0 until 12) {
            for (c in 0 until 32) {
                val tw = readWord(rom, bodyPc + (r * 32 + c) * 2)
                val tileNum = tw and 0x03FF
                if (tileNum == 0x338 || tileNum == 0) continue // skip empty
                if (tileNum >= 0x100) kraidTileRefs.add(tileNum)
                else roomTileRefs.add(tileNum)
            }
        }
        sb.appendLine("Room tileset tiles used: ${roomTileRefs.size} (range ${roomTileRefs.minOrNull()?.toString(16)}-${roomTileRefs.maxOrNull()?.toString(16)})")
        sb.appendLine("Kraid tiles (0x100+) used: ${kraidTileRefs.size}")
        if (kraidTileRefs.isNotEmpty()) {
            sb.appendLine("  Kraid tile indices: ${kraidTileRefs.sorted().joinToString { it.toString(16) }}")
        }

        // Check ALL body tilemaps
        val bodyAddrs = listOf(0xA797C8 to "initial", 0xA79AC8 to "rising1", 0xA79DC8 to "rising2", 0xA7A0C8 to "fullheight")
        for ((addr, name) in bodyAddrs) {
            val pc = rp.snesToPc(addr)
            val kTiles = mutableSetOf<Int>()
            for (i in 0 until 32 * 12) {
                val tw = readWord(rom, pc + i * 2)
                val tn = tw and 0x03FF
                if (tn >= 0x100 && tn != 0x338) kTiles.add(tn)
            }
            sb.appendLine("Body $name: ${kTiles.size} Kraid-tile refs ${if (kTiles.isNotEmpty()) kTiles.sorted().joinToString { it.toString(16) } else "(none)"}")
        }

        File("/tmp/kraid_tile_data_check.txt").writeText(sb.toString())
    }
}
