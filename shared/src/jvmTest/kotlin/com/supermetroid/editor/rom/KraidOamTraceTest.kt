package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

class KraidOamTraceTest {
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
    fun `dump init AI code for all kraid sub-entities`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val sb = StringBuilder()

        val entities = listOf(
            "Kraid body" to 0xE2BF,
            "Kraid upper body" to 0xE2FF,
            "Belly Spike 1" to 0xE33F,
            "Belly Spike 2" to 0xE37F,
            "Belly Spike 3" to 0xE3BF,
            "Flying Claw 1" to 0xE3FF,
            "Flying Claw 2" to 0xE43F,
            "Flying Claw 3" to 0xE47F,
        )

        for ((name, speciesId) in entities) {
            val specPc = rp.snesToPc(0xA00000 + speciesId)
            sb.appendLine("=== $name (species \$${speciesId.toString(16).uppercase()}) ===")

            // Read AI bank and init pointer from species header
            // +0x12 = init AI pointer (2 bytes), bank is typically $A7
            val initPtr = readWord(rom, specPc + 0x12)
            // AI bank from +2
            val aiBankByte = readWord(rom, specPc + 2)
            sb.appendLine("  AI bank ptr: \$${aiBankByte.toString(16)}, init AI ptr: \$${initPtr.toString(16)}")

            // The AI bank for Kraid entities is $A7
            val aiBank = 0xA7
            val initSnes = (aiBank shl 16) or initPtr
            val initPc = rp.snesToPc(initSnes)
            sb.appendLine("  Init code at SNES \$${initSnes.toString(16).uppercase()}, PC 0x${initPc.toString(16)}")

            // Dump first 64 bytes of init code
            sb.append("  Code: ")
            for (i in 0 until 64) {
                if (initPc + i >= rom.size) break
                sb.append("%02X ".format(rom[initPc + i].toInt() and 0xFF))
            }
            sb.appendLine()

            // Look for STA $0F92 pattern (9D 92 0F)
            var found0F92 = false
            for (i in 0 until 120) {
                if (initPc + i + 2 >= rom.size) break
                val b0 = rom[initPc + i].toInt() and 0xFF
                val b1 = rom[initPc + i + 1].toInt() and 0xFF
                val b2 = rom[initPc + i + 2].toInt() and 0xFF
                if (b0 == 0x9D && b1 == 0x92 && b2 == 0x0F) {
                    sb.appendLine("  Found STA \$0F92,x at offset +$i")
                    found0F92 = true
                }
                // Also look for JSR (20 xx xx)
                if (b0 == 0x20) {
                    val jsrTarget = b1 or (b2 shl 8)
                    if (i < 30) {
                        sb.appendLine("  JSR \$${jsrTarget.toString(16).uppercase()} at offset +$i")
                    }
                }
            }
            if (!found0F92) {
                sb.appendLine("  ** No STA \$0F92,x found in first 120 bytes **")
            }

            // Try the EnemySpritemap finder
            val enemySpritemap = EnemySpritemap(rp)
            val spritemap = enemySpritemap.findDefaultSpritemap(speciesId)
            sb.appendLine("  findDefaultSpritemap result: ${if (spritemap != null) "FOUND" else "null (FAILED)"}")

            sb.appendLine()
        }

        File("/tmp/kraid_oam_trace.txt").writeText(sb.toString())
    }
}
