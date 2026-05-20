package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Dumps raw instruction list bytes to understand the actual format
 * and why our spritemap finder picks up wrong data.
 */
class InstructionListDumpTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `dump Zoomer instruction list raw bytes`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()

        // Zoomer direction table at $A3:E2CC
        val aiBank = 0xA3
        val tablePc = rp.snesToPc((aiBank shl 16) or 0xE2CC)

        println("=== Direction Table at \$A3:E2CC ===")
        for (dir in 0 until 4) {
            val ptr = readU16(rom, tablePc + dir * 2)
            println("  Dir $dir: \$${ptr.toString(16).padStart(4, '0')}")
        }

        // Dump first 80 bytes of direction 0's instruction list
        val dir0Ptr = readU16(rom, tablePc)
        val dir0Pc = rp.snesToPc((aiBank shl 16) or dir0Ptr)
        println("\n=== Instruction List at \$A3:${dir0Ptr.toString(16)} (PC 0x${dir0Pc.toString(16)}) ===")
        println("Raw 4-byte entries:")
        for (i in 0 until 40) {
            val off = i * 4
            if (dir0Pc + off + 3 >= rom.size) break
            val w0 = readU16(rom, dir0Pc + off)
            val w1 = readU16(rom, dir0Pc + off + 2)
            val format = when {
                w0 == 0 && w1 == 0 -> "TERMINATOR"
                w0 == 0x8000 -> "GOTO → \$${w1.toString(16)}"
                w0 >= 0x8000 -> "HANDLER \$${w0.toString(16)}, param=\$${w1.toString(16)}"
                else -> "TIMER=$w0, smap=\$${w1.toString(16)}"
            }
            println("  [$i] off=${off}: w0=\$${w0.toString(16).padStart(4, '0')} w1=\$${w1.toString(16).padStart(4, '0')}  ($format)")
            if (w0 == 0 && w1 == 0) break
            if (w0 == 0x8000) break // GOTO stops list
        }

        // Check: where is $E50E relative to the instruction list?
        val smapAddr = 0xE50E
        println("\n=== Known good spritemap \$A3:E50E ===")
        println("Offset from list start: ${smapAddr - dir0Ptr} bytes (${(smapAddr - dir0Ptr) / 4} entries)")

        // Scan the instruction list looking for the address E50E as either word0 or word1
        for (i in 0 until 200) {
            val off = i * 4
            if (dir0Pc + off + 3 >= rom.size) break
            val w0 = readU16(rom, dir0Pc + off)
            val w1 = readU16(rom, dir0Pc + off + 2)
            if (w0 == smapAddr || w1 == smapAddr) {
                println("Found E50E at entry $i: w0=\$${w0.toString(16)} w1=\$${w1.toString(16)}")
            }
            if (w0 == 0 && w1 == 0) break
        }

        // Also: the findDefaultSpritemap found smap at $A3:E50E.
        // Let's check what's at E50E directly
        val smapPc = rp.snesToPc((aiBank shl 16) or smapAddr)
        val count = readU16(rom, smapPc)
        println("Spritemap at \$E50E: count=$count")
        for (j in 0 until count.coerceAtMost(8)) {
            val ePc = smapPc + 2 + j * 5
            val xWord = readU16(rom, ePc)
            val yByte = rom[ePc + 2].toInt() and 0xFF
            val attr = readU16(rom, ePc + 3)
            val is16 = (xWord and 0x8000) != 0
            val xRaw = xWord and 0x01FF
            val x = if ((xRaw and 0x100) != 0) xRaw or -256 else xRaw
            val y = if (yByte > 127) yByte - 256 else yByte
            val tile = attr and 0x01FF
            val hFlip = (attr shr 14) and 1 != 0
            val vFlip = (attr shr 15) and 1 != 0
            println("  [$j] x=$x y=$y tile=0x${tile.toString(16)} ${if (is16) "16x16" else "8x8"} hFlip=$hFlip vFlip=$vFlip")
        }
    }

    @Test
    fun `dump Sidehopper instruction list raw bytes`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)

        val speciesId = 0xD93F
        val headerPc = rp.snesToPc(0xA00000 or speciesId)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF

        // Find the instruction list pointer
        val defaultSmap = smap.findDefaultSpritemap(speciesId)
        println("=== Sidehopper ===")
        println("Default spritemap: ${defaultSmap?.snesAddress?.toString(16)}")

        // The Sidehopper uses cross-function trace via LDA abs,x pattern
        // Let's trace what instruction list we're finding
        val initAiPtr = readU16(rom, headerPc + 0x12)
        val initPc = rp.snesToPc((aiBank shl 16) or initAiPtr)

        // Scan for all STA $0F92,x in the init function
        println("Scanning init function at \$${aiBank.toString(16)}:${initAiPtr.toString(16)} for STA \$0F92,x:")
        for (i in 0 until 200) {
            val pc = initPc + i
            if (pc + 2 >= rom.size) break
            if (rom[pc].toInt() and 0xFF == 0x9D &&
                rom[pc + 1].toInt() and 0xFF == 0x92 &&
                rom[pc + 2].toInt() and 0xFF == 0x0F) {
                println("  Found at init+$i (PC 0x${pc.toString(16)})")
                // Look at preceding bytes
                print("    Preceding 10 bytes: ")
                for (b in 10 downTo 1) print("${(rom[pc - b].toInt() and 0xFF).toString(16).padStart(2, '0')} ")
                println()
            }
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
