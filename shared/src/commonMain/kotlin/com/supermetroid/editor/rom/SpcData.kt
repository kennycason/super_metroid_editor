package com.supermetroid.editor.rom

import com.supermetroid.editor.util.EditorLog

/**
 * Super Metroid SPC700 music data parser.
 *
 * Music in SM is stored as transfer blocks that get DMA'd to the SPC700's 64KB RAM.
 * Each "song set" loads a different instrument/sample set. The "play index" selects
 * the sequence to play within that set.
 *
 * ROM banks $CF (setup) and $D0-$DF contain all music data.
 * Transfer block format: [size:u16] [spcDest:u16] [data:size bytes]...  [0x0000 = end]
 */
object SpcData {

    // ─── Known SM tracks (song set, play index) → name ──────────────

    data class TrackInfo(
        val songSet: Int,
        val playIndex: Int,
        val name: String,
        val area: String = ""
    ) {
        val id get() = (songSet shl 8) or playIndex
    }

    val KNOWN_TRACKS = listOf(
        TrackInfo(0x03, 0x05, "Title Screen", "Menu"),
        TrackInfo(0x03, 0x06, "Title Screen (After Button)", "Menu"),
        TrackInfo(0x36, 0x05, "Intro Story", "Intro"),
        TrackInfo(0x2D, 0x05, "Flying to Ceres / Zebes", "Intro"),
        TrackInfo(0x2D, 0x06, "Ceres Station", "Ceres"),
        TrackInfo(0x06, 0x05, "Empty Crateria (Rain + Thunder)", "Crateria"),
        TrackInfo(0x06, 0x06, "Empty Crateria (Rain Only)", "Crateria"),
        TrackInfo(0x06, 0x07, "Empty Crateria (Silent)", "Crateria"),
        TrackInfo(0x0C, 0x05, "Crateria Surface", "Crateria"),
        TrackInfo(0x09, 0x05, "Space Pirates", "Crateria"),
        TrackInfo(0x09, 0x06, "Golden Statues Room", "Crateria"),
        TrackInfo(0x0F, 0x05, "Green Brinstar", "Brinstar"),
        TrackInfo(0x12, 0x05, "Red Brinstar / Kraid's Lair", "Brinstar"),
        TrackInfo(0x15, 0x05, "Upper Norfair", "Norfair"),
        TrackInfo(0x18, 0x05, "Lower Norfair", "Norfair"),
        TrackInfo(0x30, 0x05, "Wrecked Ship (Boss Alive)", "Wrecked Ship"),
        TrackInfo(0x30, 0x06, "Wrecked Ship (Boss Dead)", "Wrecked Ship"),
        TrackInfo(0x1B, 0x05, "Eastern Maridia", "Maridia"),
        TrackInfo(0x1B, 0x06, "Western Maridia", "Maridia"),
        TrackInfo(0x1E, 0x05, "Tourian", "Tourian"),
        TrackInfo(0x2A, 0x05, "Mini-Boss Fight", "Boss"),
        TrackInfo(0x27, 0x05, "Boss Fight (Roaring)", "Boss"),
        TrackInfo(0x27, 0x06, "Pre-Boss Tension (Roaring)", "Boss"),
        TrackInfo(0x45, 0x05, "Boss Fight (Metroid Sounds)", "Boss"),
        TrackInfo(0x45, 0x06, "Pre-Boss Tension (Metroid)", "Boss"),
        TrackInfo(0x24, 0x05, "Boss Fight (Draygon / Ridley)", "Boss"),
        TrackInfo(0x24, 0x06, "Bomb Torizo Awakening", "Boss"),
        TrackInfo(0x24, 0x07, "Escape Music", "Escape"),
        TrackInfo(0x21, 0x05, "Mother Brain Fight", "Tourian"),
        TrackInfo(0x3C, 0x05, "Credits", "Ending"),
        TrackInfo(0x3F, 0x05, "\"The Last Metroid is in Captivity\"", "Intro"),
        TrackInfo(0x42, 0x05, "\"The Galaxy is at Peace\"", "Intro"),
        TrackInfo(0x33, 0x05, "Zebes Exploding", "Ending"),
        TrackInfo(0x39, 0x05, "Samus Dying", "Game Over"),
    )

    /** Unique song sets referenced by tracks. */
    val SONG_SETS: List<Int> get() = KNOWN_TRACKS.map { it.songSet }.distinct().sorted()

    // ─── SPC transfer block parsing ─────────────────────────────────

    /**
     * A single SPC transfer block: [size] bytes to be written at [destAddr] in SPC RAM.
     */
    data class TransferBlock(
        val destAddr: Int,
        val data: ByteArray,
    )

    /**
     * Parse a chain of SPC transfer blocks starting at a PC offset in the ROM.
     * Returns the list of blocks and the total ROM bytes consumed.
     */
    fun parseTransferBlocks(romData: ByteArray, startPc: Int): List<TransferBlock> {
        val blocks = mutableListOf<TransferBlock>()
        var pos = startPc
        while (pos + 4 <= romData.size) {
            val size = (romData[pos].toInt() and 0xFF) or
                ((romData[pos + 1].toInt() and 0xFF) shl 8)
            if (size == 0) break
            val dest = (romData[pos + 2].toInt() and 0xFF) or
                ((romData[pos + 3].toInt() and 0xFF) shl 8)
            pos += 4
            if (pos + size > romData.size) break
            val data = romData.copyOfRange(pos, pos + size)
            blocks.add(TransferBlock(dest, data))
            pos += size
        }
        return blocks
    }

    /**
     * Apply transfer blocks to a 64KB SPC RAM image.
     */
    fun applyTransferBlocks(spcRam: ByteArray, blocks: List<TransferBlock>) {
        for (block in blocks) {
            val dest = block.destAddr and 0xFFFF
            val len = minOf(block.data.size, RomConstants.SPC_RAM_SIZE - dest)
            System.arraycopy(block.data, 0, spcRam, dest, len)
        }
    }

    /**
     * Build the initial SPC RAM image by parsing transfer blocks from $CF:8000.
     * This loads the SPC engine code and common sample data.
     */
    fun buildInitialSpcRam(romParser: RomParser): ByteArray {
        val spcRam = ByteArray(RomConstants.SPC_RAM_SIZE)
        val startPc = romParser.snesToPc(0xCF8000)
        val blocks = parseTransferBlocks(romParser.romData, startPc)
        applyTransferBlocks(spcRam, blocks)
        return spcRam
    }

    // ─── Sample directory parsing ───────────────────────────────────

    /**
     * BRR sample directory entry: start address and loop address in SPC RAM.
     */
    data class SampleDirEntry(
        val index: Int,
        val startAddr: Int,
        val loopAddr: Int,
    )

    /**
     * Find the sample directory in SPC RAM.
     * The DSP DIR register ($5D) sets the page: directory is at page * 0x100.
     * We try common DIR values used by SM's SPC engine.
     */
    fun findSampleDirectory(spcRam: ByteArray): List<SampleDirEntry> {
        // SM typically uses DIR page at $6C (directory at $6C00)
        // Also try other common values
        for (dirPage in listOf(0x6C, 0x6D, 0x04, 0x02, 0x1A)) {
            val dirAddr = dirPage * 0x100
            val entries = parseSampleDirectory(spcRam, dirAddr)
            if (entries.size >= 4) return entries
        }

        // Fallback: scan for a plausible directory
        for (dirPage in 0x01..0xFF) {
            val dirAddr = dirPage * 0x100
            val entries = parseSampleDirectory(spcRam, dirAddr)
            if (entries.size >= 4) return entries
        }
        return emptyList()
    }

    private fun parseSampleDirectory(spcRam: ByteArray, dirAddr: Int): List<SampleDirEntry> {
        val entries = mutableListOf<SampleDirEntry>()
        var idx = 0
        while (true) {
            val off = dirAddr + idx * 4
            if (off + 3 >= spcRam.size) break
            val startAddr = (spcRam[off].toInt() and 0xFF) or
                ((spcRam[off + 1].toInt() and 0xFF) shl 8)
            val loopAddr = (spcRam[off + 2].toInt() and 0xFF) or
                ((spcRam[off + 3].toInt() and 0xFF) shl 8)

            if (startAddr == 0 && loopAddr == 0 && idx > 0) break
            if (startAddr >= 0xFFF0) break

            // Validate: start address should point to valid SPC RAM with BRR data
            if (startAddr < 0x0200 || startAddr >= 0xFF00) {
                if (idx > 0) break else { idx++; continue }
            }

            // Check if there's a valid BRR header at the start address
            if (startAddr < spcRam.size) {
                val hdr = spcRam[startAddr].toInt() and 0xFF
                val shift = hdr shr 4
                if (shift > 12 && shift != 13) {
                    if (idx > 0) break else { idx++; continue }
                }
            }

            entries.add(SampleDirEntry(idx, startAddr, loopAddr))
            idx++
            if (idx > 64) break // SM never has more than ~30 samples
        }
        return entries
    }

    // ─── BRR decoding ───────────────────────────────────────────────

    /**
     * Decode a BRR sample from SPC RAM starting at [startAddr].
     * Returns signed 16-bit PCM samples at the SPC's native ~32kHz rate.
     *
     * BRR format: 9-byte blocks (1 header + 8 data bytes = 16 PCM samples/block)
     * Header: SSSSFFLE  (S=shift, F=filter, L=loop, E=end)
     */
    fun decodeBrr(spcRam: ByteArray, startAddr: Int, maxSamples: Int = RomConstants.SPC_RAM_SIZE): ShortArray {
        val samples = mutableListOf<Short>()
        var prev1 = 0
        var prev2 = 0
        var addr = startAddr

        while (addr + 8 < spcRam.size && samples.size < maxSamples) {
            val header = spcRam[addr].toInt() and 0xFF
            val shift = header shr 4
            val filter = (header shr 2) and 0x3
            val endFlag = header and 1

            for (byteIdx in 1..8) {
                if (addr + byteIdx >= spcRam.size) break
                val b = spcRam[addr + byteIdx].toInt() and 0xFF
                for (nibbleIdx in 0..1) {
                    var nibble = if (nibbleIdx == 0) (b shr 4) else (b and 0x0F)
                    // Sign-extend nibble from 4 bits
                    if (nibble >= 8) nibble -= 16

                    val s: Int = if (shift <= 12) {
                        (nibble shl shift) shr 1
                    } else {
                        // Shift > 12: weird hardware behavior, clamp to 0 or -2048
                        if (nibble < 0) -2048 else 0
                    }

                    val filtered = when (filter) {
                        0 -> s
                        1 -> s + prev1 + ((-prev1) shr 4)
                        2 -> s + (prev1 shl 1) + ((-prev1 * 3) shr 5) - prev2 + (prev2 shr 4)
                        3 -> s + (prev1 shl 1) + ((-prev1 * 13) shr 6) - prev2 + ((prev2 * 3) shr 4)
                        else -> s
                    }

                    val clamped = filtered.coerceIn(-32768, 32767)
                    val clipped = (clamped shl 1).toShort().toInt() shr 1
                    prev2 = prev1
                    prev1 = clipped
                    samples.add(clipped.toShort())
                }
            }

            addr += 9
            if (endFlag != 0) break
        }

        return samples.toShortArray()
    }

    /**
     * Decode a BRR sample with loop support. Returns (pcmSamples, loopStartSample).
     * If the sample loops, unfolds one iteration for preview purposes.
     */
    fun decodeBrrWithLoop(
        spcRam: ByteArray,
        entry: SampleDirEntry,
        maxBlocks: Int = 2048
    ): Pair<ShortArray, Int> {
        val samples = mutableListOf<Short>()
        var prev1 = 0
        var prev2 = 0
        var addr = entry.startAddr
        var loopSample = -1
        var blockCount = 0

        while (addr + 8 < spcRam.size && blockCount < maxBlocks) {
            if (addr == entry.loopAddr && loopSample < 0) {
                loopSample = samples.size
            }

            val header = spcRam[addr].toInt() and 0xFF
            val shift = header shr 4
            val filter = (header shr 2) and 0x3
            @Suppress("UNUSED_VARIABLE") val loopFlag = (header shr 1) and 1
            val endFlag = header and 1

            for (byteIdx in 1..8) {
                if (addr + byteIdx >= spcRam.size) break
                val b = spcRam[addr + byteIdx].toInt() and 0xFF
                for (nibbleIdx in 0..1) {
                    var nibble = if (nibbleIdx == 0) (b shr 4) else (b and 0x0F)
                    if (nibble >= 8) nibble -= 16

                    val s: Int = if (shift <= 12) {
                        (nibble shl shift) shr 1
                    } else {
                        if (nibble < 0) -2048 else 0
                    }

                    val filtered = when (filter) {
                        0 -> s
                        1 -> s + prev1 + ((-prev1) shr 4)
                        2 -> s + (prev1 shl 1) + ((-prev1 * 3) shr 5) - prev2 + (prev2 shr 4)
                        3 -> s + (prev1 shl 1) + ((-prev1 * 13) shr 6) - prev2 + ((prev2 * 3) shr 4)
                        else -> s
                    }

                    val clamped = filtered.coerceIn(-32768, 32767)
                    val clipped = (clamped shl 1).toShort().toInt() shr 1
                    prev2 = prev1
                    prev1 = clipped
                    samples.add(clipped.toShort())
                }
            }

            addr += 9
            blockCount++
            if (endFlag != 0) break
        }

        return Pair(samples.toShortArray(), if (loopSample >= 0) loopSample else -1)
    }

    // ─── BRR encoding ─────────────────────────────────────────────────

    /**
     * Encode 16-bit PCM samples into BRR format.
     * Returns the raw BRR byte array (9 bytes per block, 16 samples per block).
     *
     * @param pcm Signed 16-bit PCM samples
     * @param loopBlock If >= 0, the block index where looping starts (sets loop flag)
     * @return BRR encoded bytes
     */
    fun encodeBrr(pcm: ShortArray, loopBlock: Int = -1): ByteArray {
        val blockCount = (pcm.size + 15) / 16
        if (blockCount == 0) return ByteArray(0)
        val output = ByteArray(blockCount * 9)

        var prev1 = 0
        var prev2 = 0

        for (block in 0 until blockCount) {
            val sampleOffset = block * 16
            val blockSamples = IntArray(16) { i ->
                val idx = sampleOffset + i
                if (idx < pcm.size) pcm[idx].toInt() else 0
            }

            // Try all filter/shift combos, pick the one with lowest error
            var bestShift = 0
            var bestFilter = 0
            var bestError = Long.MAX_VALUE
            var bestNibbles = IntArray(16)
            var bestP1 = prev1
            var bestP2 = prev2

            for (filter in 0..3) {
                for (shift in 0..12) {
                    var p1 = prev1
                    var p2 = prev2
                    val nibbles = IntArray(16)
                    var totalError = 0L

                    for (i in 0 until 16) {
                        val predicted = when (filter) {
                            0 -> 0
                            1 -> p1 + ((-p1) shr 4)
                            2 -> (p1 shl 1) + ((-p1 * 3) shr 5) - p2 + (p2 shr 4)
                            3 -> (p1 shl 1) + ((-p1 * 13) shr 6) - p2 + ((p2 * 3) shr 4)
                            else -> 0
                        }
                        val residual = blockSamples[i] - predicted

                        // Encode: residual = (nibble << shift) >> 1
                        // So nibble = (residual << 1) >> shift
                        val raw = if (shift > 0) {
                            ((residual shl 1) + (1 shl (shift - 1))) shr shift
                        } else {
                            residual shl 1
                        }
                        val nibble = raw.coerceIn(-8, 7)
                        nibbles[i] = nibble

                        // Decode to compute actual output (matching decoder exactly)
                        val s = if (shift <= 12) (nibble shl shift) shr 1 else 0
                        val filtered = when (filter) {
                            0 -> s
                            1 -> s + p1 + ((-p1) shr 4)
                            2 -> s + (p1 shl 1) + ((-p1 * 3) shr 5) - p2 + (p2 shr 4)
                            3 -> s + (p1 shl 1) + ((-p1 * 13) shr 6) - p2 + ((p2 * 3) shr 4)
                            else -> s
                        }
                        val clamped = filtered.coerceIn(-32768, 32767)
                        val clipped = (clamped shl 1).toShort().toInt() shr 1

                        val err = (clipped - blockSamples[i]).toLong()
                        totalError += err * err

                        p2 = p1
                        p1 = clipped
                    }

                    if (totalError < bestError) {
                        bestError = totalError
                        bestShift = shift
                        bestFilter = filter
                        bestNibbles = nibbles
                        bestP1 = p1
                        bestP2 = p2
                    }
                }
            }

            prev1 = bestP1
            prev2 = bestP2

            // Build header: SSSSFFLE
            val isEnd = block == blockCount - 1
            val isLoop = loopBlock >= 0 && block >= loopBlock
            val header = (bestShift shl 4) or (bestFilter shl 2) or
                (if (isLoop) 0x02 else 0x00) or (if (isEnd) 0x01 else 0x00)

            val outOff = block * 9
            output[outOff] = header.toByte()

            // Pack nibbles into bytes (high nibble first)
            for (i in 0 until 8) {
                val hi = bestNibbles[i * 2] and 0x0F
                val lo = bestNibbles[i * 2 + 1] and 0x0F
                output[outOff + 1 + i] = ((hi shl 4) or lo).toByte()
            }
        }

        return output
    }

    /**
     * Resample PCM from one sample rate to another using linear interpolation.
     */
    fun resamplePcm(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || pcm.isEmpty()) return pcm
        val ratio = fromRate.toDouble() / toRate
        val outLen = (pcm.size / ratio).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val s = if (idx + 1 < pcm.size) {
                (pcm[idx] * (1.0 - frac) + pcm[idx + 1] * frac).toInt()
            } else if (idx < pcm.size) {
                pcm[idx].toInt()
            } else break
            out[i] = s.coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // ─── Song set pointer table ─────────────────────────────────────

    /**
     * Vanilla table address: $8F:E7E1.
     *
     * The music loading routine at $80:8F62 does:
     *   LDA $8FE7E1,X  ; X = songSet value (0x00, 0x03, 0x06, ...)
     *   STA $00
     *   LDA $8FE7E2,X
     *   STA $01         ; 3-byte pointer now in $00-$02
     *   JSL $80:8024    ; call SPC upload routine
     *
     * The table is a packed array of 3-byte SNES pointers. Each song set
     * value (a multiple of 3) is used DIRECTLY as a byte offset into the
     * table, so songSet 0x03 reads bytes at table+3..table+5, etc.
     * Song set 0x00 points to the base SPC data at $CF:8000.
     */
    private const val VANILLA_TABLE_SNES = 0x8FE7E1

    fun findSongSetTransferData(
        romParser: RomParser,
        songSet: Int
    ): List<TransferBlock> {
        val ptr = readSongSetPointer(romParser, songSet)
        if (ptr <= 0) return emptyList()
        val pc = romParser.snesToPc(ptr)
        if (pc < 0 || pc + 4 >= romParser.romData.size) return emptyList()
        return parseTransferBlocks(romParser.romData, pc)
    }

    /**
     * Read the 3-byte pointer for a given song set from the pointer table.
     * Prefers a repointed loader table when present, then falls back to vanilla $8F:E7E1.
     */
    fun readSongSetPointer(romParser: RomParser, songSet: Int): Int {
        val rom = romParser.romData

        // If the loader has been repointed, prefer the table it actually reads.
        val tableAddr = findRelocatedTable(rom, romParser)
        if (tableAddr >= 0) {
            val relocPtr = readPointerAt(rom, tableAddr + songSet)
            if (relocPtr > 0 && isValidTransferBlockPointer(rom, romParser, relocPtr)) {
                return relocPtr
            }
        }

        // Try vanilla table location for unmodified ROMs.
        val vanillaPc = romParser.snesToPc(VANILLA_TABLE_SNES)
        val ptr = readPointerAt(rom, vanillaPc + songSet)
        if (ptr > 0 && isValidTransferBlockPointer(rom, romParser, ptr)) {
            return ptr
        }

        EditorLog.warn("[SPC] no valid pointer for songSet 0x${songSet.toString(16).padStart(2, '0')}")
        return 0
    }

    /**
     * Find the ROM PC offset of the 3-byte pointer table entry for [songSet].
     * Song set values are direct byte offsets into the packed table.
     */
    fun findSongSetPointerEntryPc(romParser: RomParser, songSet: Int): Int {
        val rom = romParser.romData

        val tableAddr = findRelocatedTable(rom, romParser)
        if (tableAddr >= 0 && tableAddr + songSet + 2 < rom.size) {
            val ptr = readPointerAt(rom, tableAddr + songSet)
            if (ptr > 0 && isValidTransferBlockPointer(rom, romParser, ptr)) {
                return tableAddr + songSet
            }
        }

        val vanillaPc = romParser.snesToPc(VANILLA_TABLE_SNES)
        if (vanillaPc >= 0 && vanillaPc + songSet + 2 < rom.size) {
            val ptr = readPointerAt(rom, vanillaPc + songSet)
            if (ptr > 0 && isValidTransferBlockPointer(rom, romParser, ptr)) {
                return vanillaPc + songSet
            }
        }

        return -1
    }

    /**
     * Build the full map of songSet -> SNES pointer for all known song sets.
     * Used for diagnostics and testing.
     */
    fun findSongSetPointerTable(romParser: RomParser): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        val knownSets = listOf(0x00, 0x03, 0x06, 0x09, 0x0C, 0x0F, 0x12, 0x15,
            0x18, 0x1B, 0x1E, 0x21, 0x24, 0x27, 0x2A, 0x2D,
            0x30, 0x33, 0x36, 0x39, 0x3C, 0x3F, 0x42, 0x45)
        for (ss in knownSets) {
            val ptr = readSongSetPointer(romParser, ss)
            if (ptr > 0) result[ss] = ptr
        }
        if (result.isNotEmpty()) {
            EditorLog.info("[SPC] Found ${result.size} song set pointers")
            for ((ss, addr) in result.entries.sortedBy { it.key }) {
                EditorLog.info("[SPC]   songSet 0x${ss.toString(16).padStart(2, '0')} -> \$${addr.toString(16).uppercase().padStart(6, '0')}")
            }
        }
        return result
    }

    private fun readPointerAt(rom: ByteArray, pc: Int): Int {
        if (pc < 0 || pc + 2 >= rom.size) return 0
        val lo = rom[pc].toInt() and 0xFF
        val mid = rom[pc + 1].toInt() and 0xFF
        val hi = rom[pc + 2].toInt() and 0xFF
        return (hi shl 16) or (mid shl 8) or lo
    }

    private fun isValidTransferBlockPointer(rom: ByteArray, romParser: RomParser, snesPtr: Int): Boolean {
        val bank = (snesPtr ushr 16) and 0xFF
        val address = snesPtr and 0xFFFF
        if (bank < 0x80 || address < 0x8000) return false
        val pc = romParser.snesToPc(snesPtr)
        if (pc < 0 || pc + 4 >= rom.size) return false
        val blkSize = (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        val blkDest = (rom[pc + 2].toInt() and 0xFF) or ((rom[pc + 3].toInt() and 0xFF) shl 8)
        return blkSize in 1..0xF000 && blkDest < RomConstants.SPC_RAM_SIZE && pc + 4 + blkSize <= rom.size
    }

    /**
     * Scan $80:8F60-$80:8F90 for the BF opcode pattern that reads the table,
     * in case a ROM hack relocated the table address.
     * Pattern: BF [lo] [mid] [hi] 85 00 BF [lo+1] [mid'] [hi'] 85 01
     */
    private fun findRelocatedTable(rom: ByteArray, romParser: RomParser): Int {
        val searchStart = romParser.snesToPc(0x808F50)
        val searchEnd = minOf(romParser.snesToPc(0x808FA0), rom.size - 12)
        if (searchStart < 0) return -1
        for (i in searchStart until searchEnd) {
            if (rom[i].toInt() and 0xFF != 0xBF) continue
            if (i + 11 >= rom.size) break
            if ((rom[i + 4].toInt() and 0xFF) != 0x85) continue
            if ((rom[i + 5].toInt() and 0xFF) != 0x00) continue
            if ((rom[i + 6].toInt() and 0xFF) != 0xBF) continue
            if ((rom[i + 10].toInt() and 0xFF) != 0x85) continue
            if ((rom[i + 11].toInt() and 0xFF) != 0x01) continue

            val lo1 = rom[i + 1].toInt() and 0xFF
            val mid1 = rom[i + 2].toInt() and 0xFF
            val hi1 = rom[i + 3].toInt() and 0xFF
            val lo2 = rom[i + 7].toInt() and 0xFF
            val mid2 = rom[i + 8].toInt() and 0xFF
            val hi2 = rom[i + 9].toInt() and 0xFF

            val addr1 = (hi1 shl 16) or (mid1 shl 8) or lo1
            val addr2 = (hi2 shl 16) or (mid2 shl 8) or lo2
            if (addr2 == addr1 + 1) {
                return romParser.snesToPc(addr1)
            }
        }
        return -1
    }

    // ─── Sample replacement (in-place ROM overwrite) ──────────────

    /**
     * Info about where a sample's BRR data lives in both SPC RAM and ROM.
     */
    data class SampleRomLocation(
        val romPcOffset: Int,      // exact ROM file offset where BRR bytes start
        val brrSize: Int,          // size of original BRR data in bytes
        val spcStartAddr: Int,     // SPC RAM address
        val maxBrrSize: Int        // max bytes before hitting next sample or ARAM end
    )

    /**
     * Measure BRR sample size by scanning for the end flag in SPC RAM.
     */
    fun measureBrrSize(spcRam: ByteArray, startAddr: Int): Int {
        var size = 0
        var addr = startAddr
        while (addr + 8 < spcRam.size) {
            val header = spcRam[addr].toInt() and 0xFF
            size += 9
            addr += 9
            if (header and 1 != 0) break
        }
        return size
    }

    /**
     * Walk a transfer block chain in ROM to find the exact ROM PC offset
     * where a given SPC RAM address lands. The chain at `chainStartPc` is:
     *   [u16 size][u16 dest][size bytes of data] ... [u16 0x0000]
     *
     * Returns the ROM PC offset of the byte at `spcAddr`, or -1 if not found.
     */
    fun findRomOffsetForSpcAddr(romData: ByteArray, chainStartPc: Int, spcAddr: Int): Int {
        var pos = chainStartPc
        while (pos + 4 <= romData.size) {
            val size = (romData[pos].toInt() and 0xFF) or
                ((romData[pos + 1].toInt() and 0xFF) shl 8)
            if (size == 0) break
            val dest = (romData[pos + 2].toInt() and 0xFF) or
                ((romData[pos + 3].toInt() and 0xFF) shl 8)
            val dataStart = pos + 4
            if (spcAddr >= dest && spcAddr < dest + size) {
                return dataStart + (spcAddr - dest)
            }
            pos = dataStart + size
        }
        return -1
    }

    private data class TransferBlockRef(
        val destAddr: Int,
        val size: Int,
        val dataStartPc: Int,
        val order: Int,
    ) {
        fun contains(spcAddr: Int): Boolean = spcAddr >= destAddr && spcAddr < destAddr + size
        fun romOffsetFor(spcAddr: Int): Int = dataStartPc + (spcAddr - destAddr)
    }

    private fun parseTransferBlockRefs(romData: ByteArray, chainStartPc: Int): List<TransferBlockRef> {
        val refs = mutableListOf<TransferBlockRef>()
        var pos = chainStartPc
        var order = 0
        while (pos + 4 <= romData.size) {
            val size = (romData[pos].toInt() and 0xFF) or
                ((romData[pos + 1].toInt() and 0xFF) shl 8)
            if (size == 0) break
            val dest = (romData[pos + 2].toInt() and 0xFF) or
                ((romData[pos + 3].toInt() and 0xFF) shl 8)
            val dataStart = pos + 4
            if (dataStart + size > romData.size) break
            refs += TransferBlockRef(dest, size, dataStart, order++)
            pos = dataStart + size
        }
        return refs
    }

    /**
     * Convert SPC RAM writes back into ROM PC writes inside a song-set transfer
     * block chain. This is used for persistent music edits: the editor works in
     * SPC RAM addresses, but the exported ROM needs PC-offset byte patches.
     *
     * If transfer blocks overlap, the latest block in the chain wins, matching
     * how the game uploads blocks into SPC RAM.
     */
    fun buildRomWritesForSpcRamWrites(
        romParser: RomParser,
        songSet: Int,
        spcWrites: Map<Int, ByteArray>,
        fallbackToBaseSongSet: Boolean = false
    ): List<Pair<Int, ByteArray>> {
        if (spcWrites.isEmpty()) return emptyList()

        val ptr = readSongSetPointer(romParser, songSet)
        require(ptr > 0) { "songSet 0x${songSet.toString(16)} has no valid transfer block pointer" }
        val chainStartPc = romParser.snesToPc(ptr)
        val refs = parseTransferBlockRefs(romParser.romData, chainStartPc)
        require(refs.isNotEmpty()) { "songSet 0x${songSet.toString(16)} has no transfer blocks" }
        val fallbackRefs = if (fallbackToBaseSongSet && songSet != 0) {
            val basePtr = readSongSetPointer(romParser, 0)
            if (basePtr > 0) parseTransferBlockRefs(romParser.romData, romParser.snesToPc(basePtr)) else emptyList()
        } else {
            emptyList()
        }

        val bytesByRomOffset = sortedMapOf<Int, Int>()
        for ((startAddr, data) in spcWrites) {
            for (i in data.indices) {
                val spcAddr = (startAddr + i) and 0xFFFF
                val ref = refs.lastOrNull { it.contains(spcAddr) }
                    ?: fallbackRefs.lastOrNull { it.contains(spcAddr) }
                    ?: error(
                        "SPC write 0x${spcAddr.toString(16).padStart(4, '0')} is not covered by " +
                            "songSet 0x${songSet.toString(16)} transfer blocks" +
                            if (fallbackToBaseSongSet) " or base SPC transfer blocks" else ""
                    )
                bytesByRomOffset[ref.romOffsetFor(spcAddr)] = data[i].toInt() and 0xFF
            }
        }

        return packSpcBytesToWrites(bytesByRomOffset).map { (offset, data) -> offset to data }
    }

    /**
     * Find the exact ROM location of a sample's BRR data.
     * Searches both base blocks (song set 0x00 at $CF:8000) and the
     * song-set-specific blocks.
     */
    fun findSampleRomLocation(
        romParser: RomParser,
        songSet: Int,
        spcRam: ByteArray,
        dirEntry: SampleDirEntry,
        allDirEntries: List<SampleDirEntry>
    ): SampleRomLocation? {
        val rom = romParser.romData
        val brrSize = measureBrrSize(spcRam, dirEntry.startAddr)
        if (brrSize == 0) return null

        // Calculate max size: distance to next sample or ARAM ceiling
        val nextSample = allDirEntries
            .filter { it.startAddr > dirEntry.startAddr }
            .minByOrNull { it.startAddr }
        val maxBrrSize = if (nextSample != null) {
            nextSample.startAddr - dirEntry.startAddr
        } else {
            0x10000 - dirEntry.startAddr // up to ARAM end
        }

        // Search song-set-specific chain first (it loads after base, so it wins)
        val songSetPtr = readSongSetPointer(romParser, songSet)
        if (songSetPtr > 0) {
            val songSetPc = romParser.snesToPc(songSetPtr)
            val romOffset = findRomOffsetForSpcAddr(rom, songSetPc, dirEntry.startAddr)
            if (romOffset >= 0) {
                return SampleRomLocation(romOffset, brrSize, dirEntry.startAddr, maxBrrSize)
            }
        }

        // Search base chain ($CF:8000)
        val basePc = romParser.snesToPc(0xCF8000)
        val romOffset = findRomOffsetForSpcAddr(rom, basePc, dirEntry.startAddr)
        if (romOffset >= 0) {
            return SampleRomLocation(romOffset, brrSize, dirEntry.startAddr, maxBrrSize)
        }

        return null
    }

    /**
     * Build ROM writes to replace a sample's BRR data in-place.
     *
     * Strategy: find the exact ROM bytes where the BRR lives, overwrite them directly.
     * - If new BRR is shorter: write new BRR with end flag, leave remaining old bytes
     *   (they won't be played since the end flag stops decoding)
     * - If new BRR is longer: trim to fit the original slot size
     * - No transfer block chain modification, no pointer updates, no relocation needed
     *
     * @return Pair of (list of ROM writes, was BRR trimmed to fit)
     */
    fun buildSampleReplacementWrites(
        romParser: RomParser,
        songSet: Int,
        sampleDirIndex: Int,
        newBrr: ByteArray
    ): Pair<List<Pair<Int, ByteArray>>, Boolean>? {
        // 1. Build SPC RAM to find sample locations
        val baseRam = buildInitialSpcRam(romParser)
        val spcRam = baseRam.copyOf()
        val blocks = findSongSetTransferData(romParser, songSet)
        if (blocks.isEmpty()) {
            EditorLog.warn("[SPC-REPLACE] Song set 0x${songSet.toString(16)} has no transfer blocks")
            return null
        }
        applyTransferBlocks(spcRam, blocks)

        // 2. Find the sample in the directory
        val dir = findSampleDirectory(spcRam)
        val entry = dir.find { it.index == sampleDirIndex }
        if (entry == null) {
            EditorLog.warn("[SPC-REPLACE] Sample #$sampleDirIndex not found in directory")
            return null
        }

        // 3. Find exact ROM offset
        val location = findSampleRomLocation(romParser, songSet, spcRam, entry, dir)
        if (location == null) {
            EditorLog.warn("[SPC-REPLACE] Could not find ROM offset for sample #$sampleDirIndex " +
                "at SPC 0x${entry.startAddr.toString(16)}")
            return null
        }

        EditorLog.info("[SPC-REPLACE] Sample #$sampleDirIndex: SPC 0x${entry.startAddr.toString(16)}, " +
            "ROM PC 0x${location.romPcOffset.toString(16)}, old=${location.brrSize}B, " +
            "new=${newBrr.size}B, max=${location.maxBrrSize}B")

        // 4. Fit new BRR into available space
        var finalBrr = newBrr
        var wasTrimmed = false
        val slotSize = location.brrSize  // stay within original BRR footprint

        if (finalBrr.size > slotSize) {
            // Trim to fit: truncate to max whole BRR blocks that fit
            val maxBlocks = slotSize / 9
            if (maxBlocks == 0) {
                EditorLog.warn("[SPC-REPLACE] Original slot too small (${slotSize}B)")
                return null
            }
            finalBrr = newBrr.copyOf(maxBlocks * 9)
            // Set end flag on last block
            finalBrr[finalBrr.size - 9] = (finalBrr[finalBrr.size - 9].toInt() or 0x01).toByte()
            // Clear loop flag (bit 1) on last block so it doesn't loop
            finalBrr[finalBrr.size - 9] = (finalBrr[finalBrr.size - 9].toInt() and 0x02.inv()).toByte()
            wasTrimmed = true
            EditorLog.warn("[SPC-REPLACE] Trimmed to ${finalBrr.size}B ($maxBlocks BRR blocks) to fit slot")
        } else if (finalBrr.size < slotSize) {
            // Shorter: the end flag in the new BRR is already set by encodeBrr(),
            // remaining old bytes won't be played. Just write what we have.
            EditorLog.info("[SPC-REPLACE] New BRR (${finalBrr.size}B) shorter than slot (${slotSize}B) - OK")
        }

        // 5. Write the BRR data at the exact ROM offset
        val writes = mutableListOf<Pair<Int, ByteArray>>()
        writes.add(Pair(location.romPcOffset, finalBrr))

        EditorLog.info("[SPC-REPLACE] Writing ${finalBrr.size}B at ROM PC 0x${location.romPcOffset.toString(16)}")
        return Pair(writes, wasTrimmed)
    }

    /**
     * Packs a sorted map of (address → byte value) into contiguous-run write records.
     *
     * Consecutive addresses are merged into a single ByteArray entry; gaps produce
     * separate entries. The map **must** be sorted by key — callers should use
     * [sortedMapOf] or [java.util.TreeMap] to guarantee this.
     *
     * Used by SPC-write accumulation in the exporter, the chain-budget assessment,
     * and by [buildRomWritesForSpcRamWrites] for ROM-level writes.
     */
    fun packSpcBytesToWrites(bytesByAddr: Map<Int, Int>): Map<Int, ByteArray> {
        val writes = linkedMapOf<Int, ByteArray>()
        var runStart = -1
        val runBytes = mutableListOf<Int>()
        var prevAddr = -1
        for ((addr, value) in bytesByAddr) {
            if (runStart < 0 || addr != prevAddr + 1) {
                if (runStart >= 0) {
                    writes[runStart] = ByteArray(runBytes.size) { runBytes[it].toByte() }
                    runBytes.clear()
                }
                runStart = addr
            }
            runBytes += value
            prevAddr = addr
        }
        if (runStart >= 0) {
            writes[runStart] = ByteArray(runBytes.size) { runBytes[it].toByte() }
        }
        return writes
    }
}
