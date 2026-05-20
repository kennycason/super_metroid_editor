package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

class MBFullInstrListDump {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `dump walk instruction list tables`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()

        // Forward walk table at $A9:C61E
        val fwdPc = rp.snesToPc(0xA90000.toInt() or 0xC61E)
        println("Forward walk body instruction lists (A9:C61E):")
        for (i in 0 until 17) {
            val ptr = readU16(rom, fwdPc + i * 2)
            println("  [$i] stride ${i * 2}: \$A9:${ptr.toString(16).uppercase().padStart(4, '0')}")
        }

        // Backward walk table at $A9:C664
        val bwdPc = rp.snesToPc(0xA90000.toInt() or 0xC664)
        println("\nBackward walk body instruction lists (A9:C664):")
        for (i in 0 until 17) {
            val ptr = readU16(rom, bwdPc + i * 2)
            println("  [$i] stride ${i * 2}: \$A9:${ptr.toString(16).uppercase().padStart(4, '0')}")
        }
    }

    @Test
    fun `dump ALL unique instruction list addresses with spritemap counts`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)

        // All unique instruction list addresses from the decompilation research
        val allAddrs = setOf(
            0x97A4, 0x98C6, 0x99AA, 0x99C6, 0x99E2, 0x99F2,
            0x9A02, 0x9A0A, 0x9A26, 0x9A42,
            0x9B7F, 0x9BB3, 0x9BE7,
            0x9C13, 0x9C29, 0x9C39, 0x9C77, 0x9C87,
            0x9D25, 0x9D3D, 0x9D7F, 0x9DB1, 0x9DBB,
            0x9ECC, 0x9F00, 0x9F34, 0x9F6C,
        )

        // Also read walk tables
        val fwdPc = rp.snesToPc(0xA90000.toInt() or 0xC61E)
        val bwdPc = rp.snesToPc(0xA90000.toInt() or 0xC664)
        val walkAddrs = mutableSetOf<Int>()
        for (i in 0 until 17) {
            walkAddrs.add(readU16(rom, fwdPc + i * 2))
            walkAddrs.add(readU16(rom, bwdPc + i * 2))
        }

        val combinedAddrs = (allAddrs + walkAddrs).sorted()

        println("All ${combinedAddrs.size} unique MB instruction lists:")
        println("%-8s %6s %s".format("Addr", "Smaps", "First frame sizes"))
        println("-".repeat(60))

        for (addr in combinedAddrs) {
            val ilPc = rp.snesToPc(0xA90000 or addr)
            if (ilPc < 0) continue

            var smapCount = 0
            val frameSizes = mutableListOf<Int>()
            var offset = 0

            for (f in 0 until 32) {
                if (ilPc + offset + 3 >= rom.size) break
                val word0 = readU16(rom, ilPc + offset)
                val word1 = readU16(rom, ilPc + offset + 2)
                offset += 4
                if (word0 == 0 && word1 == 0) break
                if (word0 == 0x8000) break
                if (word0 >= 0x8000) continue

                val parsed = smap.parseSpritemap(0xA90000.toInt() or word1)
                if (parsed != null) {
                    smapCount++
                    frameSizes.add(parsed.entries.size)
                }
            }

            val isWalk = addr in walkAddrs
            val tag = if (isWalk) " [WALK]" else ""
            println("\$${addr.toString(16).uppercase().padStart(4, '0')} %6d  %s$tag".format(
                smapCount, frameSizes.take(8).joinToString(",")
            ))
        }
    }
}
