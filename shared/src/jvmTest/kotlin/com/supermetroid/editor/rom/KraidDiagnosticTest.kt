package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

class KraidDiagnosticTest {
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

    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `dump FFFE tilemap entries for belly details`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val sb = StringBuilder()

        val addrs = listOf(
            "Belly detail 0" to 0xA7E27E,
            "Belly detail 1" to 0xA7E292,
            "Foot (left)" to 0xA7E39A,
        )

        for ((name, snesAddr) in addrs) {
            val pc = rp.snesToPc(snesAddr)
            sb.appendLine("$name (SNES: \$${snesAddr.toString(16).uppercase()}, PC: 0x${pc.toString(16)}):")

            val header = readWord(rom, pc)
            sb.appendLine("  Header: 0x${header.toString(16)} ${if (header == 0xFFFE) "(OK)" else "(WRONG!)"}")

            var offset = pc + 2
            var entryIdx = 0
            while (true) {
                val dest = readWord(rom, offset)
                if (dest == 0xFFFF) {
                    sb.appendLine("  Entry $entryIdx: FFFF (terminator)")
                    break
                }
                val count = readWord(rom, offset + 2)
                val destRow = dest / 32
                val destCol = dest % 32
                sb.appendLine("  Entry $entryIdx: dest=0x${dest.toString(16)} (row=$destRow, col=$destCol), count=$count")
                offset += 4

                for (col in 0 until count) {
                    val tw = readWord(rom, offset)
                    offset += 2
                    val tileNum = tw and 0x03FF
                    val hFlip = (tw shr 14) and 1
                    val vFlip = (tw shr 15) and 1
                    val palRow = (tw shr 10) and 7
                    sb.appendLine("    tile $col: num=0x${tileNum.toString(16)}, pal=$palRow, hFlip=$hFlip, vFlip=$vFlip (raw=0x${tw.toString(16)})")
                }
                entryIdx++
            }
            sb.appendLine()
        }

        // Also dump first body tilemap to check tile indices
        sb.appendLine("Body (initial) - first 2 rows of tile indices:")
        val bodyPc = rp.snesToPc(0xA797C8)
        for (r in 0 until 2) {
            sb.append("  Row $r: ")
            for (c in 0 until 32) {
                val tw = readWord(rom, bodyPc + (r * 32 + c) * 2)
                val tileNum = tw and 0x03FF
                sb.append("${tileNum.toString(16).padStart(3, '0')} ")
            }
            sb.appendLine()
        }

        // Check nametable decompression
        sb.appendLine("\nNametable at \$B9:FE3E:")
        val nmPc = rp.snesToPc(0xB9FE3E)
        val nmData = rp.decompressLZ5AtPc(nmPc)
        sb.appendLine("  Decompressed size: ${nmData.size} bytes (${nmData.size / 2} words)")
        val nmCols = 32
        val nmRows = nmData.size / 2 / nmCols
        sb.appendLine("  Grid: ${nmCols}x${nmRows}")
        sb.appendLine("  First 2 rows of tile indices:")
        for (r in 0 until 2.coerceAtMost(nmRows)) {
            sb.append("  Row $r: ")
            for (c in 0 until nmCols) {
                val tw = readWord(nmData, (r * nmCols + c) * 2)
                val tileNum = tw and 0x03FF
                sb.append("${tileNum.toString(16).padStart(3, '0')} ")
            }
            sb.appendLine()
        }

        File("/tmp/kraid_diagnostic.txt").writeText(sb.toString())
    }
}
