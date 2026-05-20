package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Diagnostic for reverse-engineering Ridley's full body sprite rendering.
 * Species $E17F (Ridley) and $E13F (Ceres Ridley).
 */
class RidleyDiagnostic {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU8(data: ByteArray, offset: Int): Int {
        return data[offset].toInt() and 0xFF
    }

    private fun hexDump(data: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder()
        val end = (offset + length).coerceAtMost(data.size)
        for (i in offset until end) {
            if ((i - offset) % 16 == 0 && i != offset) sb.append("\n")
            sb.append(String.format("%02X ", data[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun dumpSpeciesHeader(rp: RomParser, speciesId: Int, label: String) {
        val rom = rp.getRomData()
        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        println("=== $label (species \$${speciesId.toString(16).uppercase()}) ===")
        println("Header PC offset: $headerPc (0x${headerPc.toString(16).uppercase()})")

        val tileDataSize = readU16(rom, headerPc + 0x00)
        val vramFlag = (tileDataSize and 0x8000) != 0
        val actualSize = tileDataSize and 0x7FFF
        println("  +00 tileDataSize: \$${tileDataSize.toString(16).uppercase()} (size=$actualSize, vramFlag=$vramFlag)")

        val palettePtr = readU16(rom, headerPc + 0x02)
        println("  +02 palette ptr: \$${palettePtr.toString(16).uppercase()}")

        val hp = readU16(rom, headerPc + 0x04)
        println("  +04 HP: $hp")

        val damage = readU16(rom, headerPc + 0x06)
        println("  +06 Damage: $damage")

        val width = readU16(rom, headerPc + 0x08)
        val height = readU16(rom, headerPc + 0x0A)
        println("  +08 Width: $width, +0A Height: $height")

        val aiBank = readU8(rom, headerPc + 0x0C)
        println("  +0C AI bank: \$${aiBank.toString(16).uppercase()}")

        val unknown0D = readU8(rom, headerPc + 0x0D)
        println("  +0D unknown: \$${unknown0D.toString(16).uppercase()}")

        val hurtAI = readU16(rom, headerPc + 0x0E)
        val shotAI = readU16(rom, headerPc + 0x10)
        println("  +0E hurt AI: \$${hurtAI.toString(16).uppercase()}")
        println("  +10 shot AI: \$${shotAI.toString(16).uppercase()}")

        val initAI = readU16(rom, headerPc + 0x12)
        println("  +12 init AI: \$${initAI.toString(16).uppercase()}")

        val partCount = readU16(rom, headerPc + 0x14)
        println("  +14 part count: $partCount")

        // Bytes at +16..+35
        println("  +16..+35 raw bytes:")
        println("    " + hexDump(rom, headerPc + 0x16, 0x20))

        val graphAdr = readU16(rom, headerPc + 0x36)
        val graphBank = readU8(rom, headerPc + 0x38)
        println("  +36 GRAPHADR offset: \$${graphAdr.toString(16).uppercase()}")
        println("  +38 GRAPHADR bank: \$${graphBank.toString(16).uppercase()}")
        println("  => Full GRAPHADR: \$${graphBank.toString(16).uppercase()}:${graphAdr.toString(16).uppercase()}")

        // Try to read a few more trailing fields
        for (off in 0x39..0x3F) {
            val b = readU8(rom, headerPc + off)
            println("  +${off.toString(16).uppercase()} : \$${b.toString(16).uppercase()}")
        }
        println()
    }

    @Test
    fun `dump Ridley species headers`() {
        val rp = loadTestRom() ?: run { println("ROM not found"); return }
        dumpSpeciesHeader(rp, 0xE17F, "Ridley")
        dumpSpeciesHeader(rp, 0xE13F, "Ceres Ridley")
    }

    @Test
    fun `dump Ridley init function and scan for instruction lists`() {
        val rp = loadTestRom() ?: run { println("ROM not found"); return }
        val rom = rp.getRomData()
        val speciesId = 0xE17F

        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val aiBank = readU8(rom, headerPc + 0x0C)
        val initAiPtr = readU16(rom, headerPc + 0x12)
        val initSnes = (aiBank shl 16) or initAiPtr
        val initPc = rp.snesToPc(initSnes)

        println("Ridley AI bank: \$${aiBank.toString(16).uppercase()}")
        println("Init AI at \$${initSnes.toString(16).uppercase()} (PC: 0x${initPc.toString(16).uppercase()})")
        println()

        // Dump first 200 bytes of init function
        println("Init function first 200 bytes:")
        println(hexDump(rom, initPc, 200))
        println()

        // Scan wider area for STA $0F92,x patterns (9D 92 0F)
        // Also scan for STA $0F94,x (9D 94 0F) which is the extended spritemap pointer
        val scanSize = 8000  // Scan a large area - bosses have complex AI
        val scanStart = rp.snesToPc((aiBank shl 16) or 0x8000) // Start of AI bank code
        val scanEnd = (scanStart + 0x8000).coerceAtMost(rom.size - 6)

        println("Scanning AI bank \$${aiBank.toString(16).uppercase()} from PC 0x${scanStart.toString(16)} to 0x${scanEnd.toString(16)}")
        println()

        val instrListPtrs0F92 = mutableSetOf<Int>()
        val instrListPtrs0F94 = mutableSetOf<Int>()
        val allLdaImm16 = mutableListOf<Triple<Int, Int, Int>>() // pc, value, target register

        for (i in scanStart until scanEnd) {
            // LDA #imm16 (A9 xx xx) followed by STA $0F92,x (9D 92 0F)
            if (readU8(rom, i) == 0xA9 && i + 5 < rom.size) {
                val val16 = readU16(rom, i + 1)
                if (readU8(rom, i + 3) == 0x9D) {
                    val target = readU16(rom, i + 4)
                    if (target == 0x0F92) {
                        instrListPtrs0F92.add(val16)
                        val relAddr = i - scanStart + 0x8000
                        println("  STA \$0F92,x: instruction list ptr \$${val16.toString(16).uppercase()} at bank offset \$${relAddr.toString(16).uppercase()}")
                    }
                    if (target == 0x0F94) {
                        instrListPtrs0F94.add(val16)
                        val relAddr = i - scanStart + 0x8000
                        println("  STA \$0F94,x: value \$${val16.toString(16).uppercase()} at bank offset \$${relAddr.toString(16).uppercase()}")
                    }
                }
            }

            // Also look for LDA #imm16; STA $0FAE,x (extended spritemap pointer $0FAE)
            if (readU8(rom, i) == 0xA9 && i + 5 < rom.size) {
                if (readU8(rom, i + 3) == 0x9D) {
                    val target = readU16(rom, i + 4)
                    if (target == 0x0FAE) {
                        val val16 = readU16(rom, i + 1)
                        val relAddr = i - scanStart + 0x8000
                        println("  STA \$0FAE,x: extended spritemap ptr \$${val16.toString(16).uppercase()} at bank offset \$${relAddr.toString(16).uppercase()}")
                    }
                }
            }
        }

        println()
        println("Total instruction list ptrs (\$0F92): ${instrListPtrs0F92.size}")
        println("Total \$0F94 values: ${instrListPtrs0F94.size}")
        println()

        // Also scan for JSR/JSL to known spritemap-related routines
        // Search for FFFE tilemaps (like Kraid/Phantoon use)
        println("=== Scanning for FFFE (BG2 tilemap) headers in AI bank ===")
        var fffeCount = 0
        for (i in scanStart until scanEnd - 1) {
            if (readU16(rom, i) == 0xFFFE) {
                val relAddr = i - scanStart + 0x8000
                // Check if next word after FFFE looks like a valid BG2 destination
                val nextWord = readU16(rom, i + 2)
                println("  FFFE header at bank offset \$${relAddr.toString(16).uppercase()} (PC 0x${i.toString(16)}), next word=\$${nextWord.toString(16).uppercase()}")
                fffeCount++
            }
        }
        println("Total FFFE headers: $fffeCount")
    }

    @Test
    fun `parse Ridley instruction lists and find large spritemaps`() {
        val rp = loadTestRom() ?: run { println("ROM not found"); return }
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)
        val speciesId = 0xE17F

        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val aiBank = readU8(rom, headerPc + 0x0C)

        // Scan entire AI bank for instruction list pointers
        val scanStart = rp.snesToPc((aiBank shl 16) or 0x8000)
        val scanEnd = (scanStart + 0x8000).coerceAtMost(rom.size - 6)

        val instrListPtrs = mutableSetOf<Int>()
        for (i in scanStart until scanEnd) {
            if (readU8(rom, i) == 0xA9 && i + 5 < rom.size &&
                readU8(rom, i + 3) == 0x9D &&
                readU16(rom, i + 4) == 0x0F92) {
                instrListPtrs.add(readU16(rom, i + 1))
            }
        }

        println("Found ${instrListPtrs.size} instruction list pointers in bank \$${aiBank.toString(16).uppercase()}")
        println()

        var largestEntries = 0
        var largestAddr = 0
        val allSpritemapAddrs = mutableSetOf<Int>()

        for (ptr in instrListPtrs.sorted()) {
            val ilSnes = (aiBank shl 16) or ptr
            val ilPc = rp.snesToPc(ilSnes)
            if (ilPc < 0 || ilPc >= rom.size - 4) continue

            println("--- Instruction list at \$${aiBank.toString(16).uppercase()}:${ptr.toString(16).uppercase()} ---")

            var offset = 0
            var frameIdx = 0
            for (f in 0 until 64) {
                if (ilPc + offset + 3 >= rom.size) break
                val word0 = readU16(rom, ilPc + offset)
                val word1 = readU16(rom, ilPc + offset + 2)
                offset += 4

                if (word0 == 0 && word1 == 0) { println("  [End] null terminator"); break }

                if (word0 >= 0x8000) {
                    val opName = when (word0) {
                        0x8000 -> "GOTO"
                        0x800A -> "TIMER"
                        0x8004 -> "CLEAR_TIMER"
                        0x8006 -> "SLEEP"
                        else -> "CTRL_${word0.toString(16).uppercase()}"
                    }
                    println("  [$frameIdx] $opName \$${word0.toString(16).uppercase()} arg=\$${word1.toString(16).uppercase()}")
                    if (word0 == 0x8000) break
                    frameIdx++
                    continue
                }

                val smapSnes = (aiBank shl 16) or word1
                val parsedSmap = smap.parseSpritemap(smapSnes)
                val entries = parsedSmap?.entries?.size ?: 0
                allSpritemapAddrs.add(word1)

                val marker = if (entries >= 15) " ***LARGE***" else ""
                println("  [$frameIdx] duration=$word0 spritemap=\$${word1.toString(16).uppercase()} entries=$entries$marker")

                if (parsedSmap != null && entries > 5) {
                    val minTile = parsedSmap.entries.minOf { it.tileNum }
                    val maxTile = parsedSmap.entries.maxOf { it.tileNum }
                    val minX = parsedSmap.entries.minOf { it.xOffset }
                    val maxX = parsedSmap.entries.maxOf { it.xOffset + if (it.is16x16) 16 else 8 }
                    val minY = parsedSmap.entries.minOf { it.yOffset }
                    val maxY = parsedSmap.entries.maxOf { it.yOffset + if (it.is16x16) 16 else 8 }
                    println("        tiles=$minTile..$maxTile bounds=${maxX - minX}x${maxY - minY}px")
                }

                if (entries > largestEntries) {
                    largestEntries = entries
                    largestAddr = word1
                }

                frameIdx++
            }
            println()
        }

        println("=== SUMMARY ===")
        println("Total instruction lists: ${instrListPtrs.size}")
        println("Total unique spritemaps referenced: ${allSpritemapAddrs.size}")
        println("Largest spritemap: \$${largestAddr.toString(16).uppercase()} with $largestEntries entries")
        println()

        // List all spritemaps with 15+ entries
        println("=== Spritemaps with 15+ entries ===")
        for (addr in allSpritemapAddrs.sorted()) {
            val smapSnes = (aiBank shl 16) or addr
            val parsed = smap.parseSpritemap(smapSnes) ?: continue
            if (parsed.entries.size >= 15) {
                val minTile = parsed.entries.minOf { it.tileNum }
                val maxTile = parsed.entries.maxOf { it.tileNum }
                println("  \$${addr.toString(16).uppercase()}: ${parsed.entries.size} entries, tiles $minTile..$maxTile")
            }
        }
    }

    @Test
    fun `brute force scan for large spritemaps in AI bank`() {
        val rp = loadTestRom() ?: run { println("ROM not found"); return }
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)
        val speciesId = 0xE17F

        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val aiBank = readU8(rom, headerPc + 0x0C)

        println("Brute force scanning bank \$${aiBank.toString(16).uppercase()} for large spritemaps (count word 15-64)")
        println()

        val scanStart = rp.snesToPc((aiBank shl 16) or 0x8000)
        val scanEnd = (scanStart + 0x8000).coerceAtMost(rom.size - 2)

        val largeMaps = mutableListOf<Triple<Int, Int, Int>>() // bankOffset, count, pc

        for (i in scanStart until scanEnd) {
            val count = readU16(rom, i)
            if (count !in 15..64) continue
            // Check if data after count looks like valid OAM entries
            val dataNeeded = 2 + count * 5
            if (i + dataNeeded > rom.size) continue

            var valid = true
            var maxTile = 0
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE

            for (e in 0 until count) {
                val ePc = i + 2 + e * 5
                val xWord = readU16(rom, ePc)
                val yByte = rom[ePc + 2].toInt()
                val attr = readU16(rom, ePc + 3)

                val tileNum = attr and 0x01FF
                val palRow = (attr shr 9) and 7

                // Sanity checks: tile numbers should be in range for 256-tile sheets
                if (tileNum > 511) { valid = false; break }
                if (palRow > 7) { valid = false; break }

                val xRaw = xWord and 0x01FF
                val xSigned = if (xRaw >= 256) xRaw - 512 else xRaw
                if (xSigned < -256 || xSigned > 256) { valid = false; break }
                if (yByte.toByte().toInt() < -256 || yByte.toByte().toInt() > 256) { valid = false; break }

                maxTile = maxOf(maxTile, tileNum)
                minX = minOf(minX, xSigned)
                maxX = maxOf(maxX, xSigned)
            }

            if (!valid) continue

            // Additional check: the spritemap should cover a reasonable area
            val spread = maxX - minX
            if (spread < 16) continue // Too narrow for a large spritemap

            val bankOffset = (i - scanStart) + 0x8000
            largeMaps.add(Triple(bankOffset, count, i))
        }

        println("Found ${largeMaps.size} candidate large spritemaps")
        println()

        // Show top 30 by entry count
        val sorted = largeMaps.sortedByDescending { it.second }
        for ((idx, triple) in sorted.take(30).withIndex()) {
            val (bankOffset, count, pc) = triple
            val smapSnes = (aiBank shl 16) or bankOffset
            val parsed = smap.parseSpritemap(smapSnes)
            if (parsed == null) {
                println("  [$idx] \$${bankOffset.toString(16).uppercase()}: count=$count (failed to parse)")
                continue
            }
            val minTile = parsed.entries.minOf { it.tileNum }
            val maxTile = parsed.entries.maxOf { it.tileNum }
            val minX = parsed.entries.minOf { it.xOffset }
            val maxX = parsed.entries.maxOf { it.xOffset + if (it.is16x16) 16 else 8 }
            val minY = parsed.entries.minOf { it.yOffset }
            val maxY = parsed.entries.maxOf { it.yOffset + if (it.is16x16) 16 else 8 }
            println("  [$idx] \$${bankOffset.toString(16).uppercase()}: ${count} entries, tiles=$minTile..$maxTile, bounds=${maxX - minX}x${maxY - minY}px")
        }
    }

    @Test
    fun `check Ridley for extended spritemap and body part mechanisms`() {
        val rp = loadTestRom() ?: run { println("ROM not found"); return }
        val rom = rp.getRomData()
        val speciesId = 0xE17F

        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val aiBank = readU8(rom, headerPc + 0x0C)
        val partCount = readU16(rom, headerPc + 0x14)

        println("Ridley part count: $partCount")
        println()

        // Dump extended header fields that might define body parts
        println("Full header raw dump (64 bytes):")
        println(hexDump(rom, headerPc, 64))
        println()

        // Check for body part table pointers
        // At +$16 there might be pointers to body part definitions
        val ptr16 = readU16(rom, headerPc + 0x16)
        val ptr18 = readU16(rom, headerPc + 0x18)
        val ptr1A = readU16(rom, headerPc + 0x1A)
        val ptr1C = readU16(rom, headerPc + 0x1C)
        println("Potential body part pointers:")
        println("  +16: \$${ptr16.toString(16).uppercase()}")
        println("  +18: \$${ptr18.toString(16).uppercase()}")
        println("  +1A: \$${ptr1A.toString(16).uppercase()}")
        println("  +1C: \$${ptr1C.toString(16).uppercase()}")
        println()

        // If partCount > 0, there might be sub-enemy definitions
        // The body part init table is at +$1A in the species header
        if (partCount > 0) {
            val bodyPartTablePtr = ptr1A
            val bptSnes = (aiBank shl 16) or bodyPartTablePtr
            val bptPc = rp.snesToPc(bptSnes)
            println("Body part table at \$${bptSnes.toString(16).uppercase()} (PC 0x${bptPc.toString(16)}):")
            println(hexDump(rom, bptPc, partCount * 16))
            println()

            // Also try treating it as an enemy population pointer
            // Each body part entry could be: [speciesId(2)] [xOffset(2)] [yOffset(2)] [initParam(2)]
            for (p in 0 until partCount.coerceAtMost(20)) {
                val entryPc = bptPc + p * 2
                if (entryPc + 1 >= rom.size) break
                val subId = readU16(rom, entryPc)
                println("  Part $p: sub-speciesId=\$${subId.toString(16).uppercase()}")
            }
        }
        println()

        // Scan for Ridley-specific JSR patterns in the AI code
        // Look for calls that might set up multi-part sprites
        val initAiPtr = readU16(rom, headerPc + 0x12)
        val initSnes = (aiBank shl 16) or initAiPtr
        val initPc = rp.snesToPc(initSnes)

        println("=== Scanning init AI for JSR calls (first 500 bytes) ===")
        val scanEnd = (initPc + 500).coerceAtMost(rom.size - 3)
        for (i in initPc until scanEnd) {
            val opcode = readU8(rom, i)
            if (opcode == 0x20) { // JSR
                val target = readU16(rom, i + 1)
                val relOff = i - initPc
                println("  +$relOff: JSR \$${target.toString(16).uppercase()}")
            }
            if (opcode == 0x22) { // JSL (long call)
                if (i + 3 < rom.size) {
                    val targetLo = readU16(rom, i + 1)
                    val targetBank = readU8(rom, i + 3)
                    val relOff = i - initPc
                    println("  +$relOff: JSL \$${targetBank.toString(16).uppercase()}:${targetLo.toString(16).uppercase()}")
                }
            }
        }
        println()

        // Check Ceres Ridley too
        val ceresId = 0xE13F
        val ceresHeaderPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or ceresId)
        val ceresAiBank = readU8(rom, ceresHeaderPc + 0x0C)
        val ceresPartCount = readU16(rom, ceresHeaderPc + 0x14)
        val ceresInitAi = readU16(rom, ceresHeaderPc + 0x12)
        println("Ceres Ridley: AI bank=\$${ceresAiBank.toString(16).uppercase()}, partCount=$ceresPartCount, initAI=\$${ceresInitAi.toString(16).uppercase()}")

        if (ceresPartCount > 0) {
            val cPtrPc = rp.snesToPc((ceresAiBank shl 16) or readU16(rom, ceresHeaderPc + 0x1A))
            println("Ceres body part table:")
            println(hexDump(rom, cPtrPc, ceresPartCount * 16))
        }
    }

    @Test
    fun `scan for all instruction lists in Ceres Ridley bank too`() {
        val rp = loadTestRom() ?: run { println("ROM not found"); return }
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)

        for ((label, speciesId) in listOf("Ridley" to 0xE17F, "Ceres Ridley" to 0xE13F)) {
            val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            val aiBank = readU8(rom, headerPc + 0x0C)
            println("=== $label: AI bank \$${aiBank.toString(16).uppercase()} ===")

            val scanStart = rp.snesToPc((aiBank shl 16) or 0x8000)
            val scanEnd = (scanStart + 0x8000).coerceAtMost(rom.size - 6)

            val instrListPtrs = mutableSetOf<Int>()
            for (i in scanStart until scanEnd) {
                if (readU8(rom, i) == 0xA9 && i + 5 < rom.size &&
                    readU8(rom, i + 3) == 0x9D &&
                    readU16(rom, i + 4) == 0x0F92) {
                    instrListPtrs.add(readU16(rom, i + 1))
                }
            }

            println("  Found ${instrListPtrs.size} instruction list pointers")

            var maxEntries = 0
            var maxAddr = 0
            var totalSpritemaps = 0
            val largeMaps = mutableListOf<Pair<Int, Int>>() // addr, entries

            for (ptr in instrListPtrs.sorted()) {
                val ilPc = rp.snesToPc((aiBank shl 16) or ptr)
                if (ilPc < 0 || ilPc >= rom.size - 4) continue

                var offset = 0
                for (f in 0 until 64) {
                    if (ilPc + offset + 3 >= rom.size) break
                    val word0 = readU16(rom, ilPc + offset)
                    val word1 = readU16(rom, ilPc + offset + 2)
                    offset += 4
                    if (word0 == 0 && word1 == 0) break
                    if (word0 == 0x8000) break
                    if (word0 >= 0x8000) continue

                    val smapSnes = (aiBank shl 16) or word1
                    val parsed = smap.parseSpritemap(smapSnes) ?: continue
                    totalSpritemaps++
                    val ec = parsed.entries.size
                    if (ec > maxEntries) { maxEntries = ec; maxAddr = word1 }
                    if (ec >= 15) largeMaps.add(word1 to ec)
                }
            }

            println("  Total spritemaps parsed: $totalSpritemaps")
            println("  Largest: \$${maxAddr.toString(16).uppercase()} with $maxEntries entries")
            println("  Spritemaps with 15+ entries: ${largeMaps.size}")
            for ((addr, cnt) in largeMaps.sortedByDescending { it.second }) {
                println("    \$${addr.toString(16).uppercase()}: $cnt entries")
            }
            println()
        }
    }
}
