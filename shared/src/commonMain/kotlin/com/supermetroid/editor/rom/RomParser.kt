package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.rom.RomConstants.BANK_ENEMY_AI
import com.supermetroid.editor.rom.RomConstants.BANK_ENEMY_GFX
import com.supermetroid.editor.rom.RomConstants.BANK_ENEMY_SET
import com.supermetroid.editor.rom.RomConstants.BANK_FX
import com.supermetroid.editor.rom.RomConstants.BANK_ROOM_DATA
import com.supermetroid.editor.rom.RomConstants.ROM_SIZE
import com.supermetroid.editor.rom.RomConstants.ROM_SIZE_WITH_HEADER
import com.supermetroid.editor.rom.RomConstants.SMC_HEADER_SIZE
import com.supermetroid.editor.rom.RomConstants.STATE_DATA_SIZE

/**
 * Parser for Super Metroid ROM files (.smc format)
 * 
 * Super Metroid ROM structure:
 * - Optional SMC header: 0x200 bytes (512 bytes) 
 * - ROM data: 3MB (0x300000 bytes)
 * - LoROM mapping (mode byte 0x30 at internal header offset $7FD5)
 * 
 * Room IDs (e.g., 0x91F8 for Landing Site) are 16-bit pointers within
 * SNES bank $8F. Each room header lives at SNES address $8F:<roomId>.
 */
class RomParser(internal val romData: ByteArray) {
    private val hasHeader: Boolean
        get() = romData.size == ROM_SIZE_WITH_HEADER

    private val romStartOffset: Int
        get() = if (hasHeader) SMC_HEADER_SIZE else 0x0
    
    /**
     * Convert SNES address to PC offset using LoROM mapping.
     * PC offset = ((bank & 0x7F) * 0x8000) + (address & 0x7FFF)
     */
    fun snesToPc(snesAddress: Int): Int {
        val bank = (snesAddress shr 16) and 0xFF
        val address = snesAddress and 0xFFFF
        val pcAddress = ((bank and 0x7F) * 0x8000) + (address and 0x7FFF)
        return romStartOffset + pcAddress
    }
    
    /**
     * Convert a 16-bit room ID to PC offset.
     * Room IDs are pointers within SNES bank $8F.
     */
    fun roomIdToPc(roomId: Int): Int {
        return snesToPc(BANK_ROOM_DATA or (roomId and 0xFFFF))
    }
    
    /**
     * Read a room header directly from ROM using the room ID.
     */
    fun readRoomHeader(roomId: Int): Room? {
        val pcOffset = roomIdToPc(roomId)
        
        if (pcOffset < 0 || pcOffset + 11 > romData.size) {
            return null
        }
        
        return try {
            val index = romData[pcOffset].toInt() and 0xFF
            val area = romData[pcOffset + 1].toInt() and 0xFF
            val mapX = romData[pcOffset + 2].toInt() and 0xFF
            val mapY = romData[pcOffset + 3].toInt() and 0xFF
            val width = romData[pcOffset + 4].toInt() and 0xFF
            val height = romData[pcOffset + 5].toInt() and 0xFF
            val upScroller = romData[pcOffset + 6].toInt() and 0xFF
            val downScroller = romData[pcOffset + 7].toInt() and 0xFF
            val creBitflag = romData[pcOffset + 8].toInt() and 0xFF
            val doorOut = readUInt16At(pcOffset + 9)
            
            if (width == 0 || height == 0 || width > 16 || height > 16) return null
            if (area > 6) return null
            
            // Find default state data
            val stateDataOffset = findDefaultStateData(pcOffset + 11)
            
            var levelDataPtr = 0
            var tileset = 0
            var musicData = 0
            var musicTrack = 0
            var fxPtr = 0
            var enemySetPtr = 0
            var enemyGfxPtr = 0
            var bgScrolling = 0
            var roomScrollsPtr = 0
            var mainAsmPtr = 0
            var plmSetPtr = 0
            var bgDataPtr = 0
            var setupAsmPtr = 0
            
            if (stateDataOffset != null && stateDataOffset + STATE_DATA_SIZE <= romData.size) {
                levelDataPtr = readUInt24At(stateDataOffset)
                tileset = romData[stateDataOffset + 3].toInt() and 0xFF
                musicData = romData[stateDataOffset + 4].toInt() and 0xFF
                musicTrack = romData[stateDataOffset + 5].toInt() and 0xFF
                fxPtr = readUInt16At(stateDataOffset + 6)
                enemySetPtr = readUInt16At(stateDataOffset + 8)
                enemyGfxPtr = readUInt16At(stateDataOffset + 10)
                bgScrolling = readUInt16At(stateDataOffset + 12)
                roomScrollsPtr = readUInt16At(stateDataOffset + 14)
                mainAsmPtr = readUInt16At(stateDataOffset + 18)
                plmSetPtr = readUInt16At(stateDataOffset + 20)
                bgDataPtr = readUInt16At(stateDataOffset + 22)
                setupAsmPtr = readUInt16At(stateDataOffset + 24)
            }
            
            Room(
                roomId = roomId,
                name = "Room 0x${roomId.toString(16)}",
                handle = "room_${roomId.toString(16)}",
                index = index,
                area = area,
                mapX = mapX,
                mapY = mapY,
                width = width,
                height = height,
                upScroller = upScroller,
                downScroller = downScroller,
                creBitflag = creBitflag,
                doorOut = doorOut,
                levelDataPtr = levelDataPtr,
                tileset = tileset,
                musicData = musicData,
                musicTrack = musicTrack,
                fxPtr = fxPtr,
                enemySetPtr = enemySetPtr,
                enemyGfxPtr = enemyGfxPtr,
                bgScrolling = bgScrolling,
                roomScrollsPtr = roomScrollsPtr,
                mainAsmPtr = mainAsmPtr,
                plmSetPtr = plmSetPtr,
                bgDataPtr = bgDataPtr,
                setupAsmPtr = setupAsmPtr
            )
        } catch (e: Exception) {
            println("Room 0x${roomId.toString(16)}: exception ${e.message}")
            null
        }
    }
    
    /**
     * Find room state data. Uses the first E629 (morph ball) conditional state
     * if available, otherwise falls back to the default E5E6 state.
     * 
     * E629 represents "normal gameplay" (Wrecked Ship powered on, etc.).
     * We use a byte-scan for E5E6 as the reliable way to find the default,
     * and specifically look for E629 as the first condition.
     * 
     * E629 entry format: condition(2) + arg(1) + statePtr(2) = 5 bytes.
     * The statePtr points to 26 bytes of state data in bank $8F.
     */
    private fun findDefaultStateData(stateListOffset: Int): Int? {
        // Check if first condition is E629 (morph ball check)
        if (stateListOffset + 5 <= romData.size) {
            val firstCondition = readUInt16At(stateListOffset)
            if (firstCondition == 0xE629) {
                // E629: condition(2) + arg(1) + ptr(2) = 5 bytes
                val statePtr = readUInt16At(stateListOffset + 3)
                val statePc = snesToPc(BANK_ROOM_DATA or statePtr)
                if (statePc + STATE_DATA_SIZE <= romData.size) {
                    return statePc
                }
            }
        }
        
        // Byte-scan for E5E6 default state marker
        val maxScan = 200
        val endOffset = minOf(stateListOffset + maxScan, romData.size - 1)
        for (offset in stateListOffset until endOffset) {
            if (readUInt16At(offset) == 0xE5E6) {
                return offset + 2  // 26-byte state data follows
            }
        }
        
        return null
    }
    
    // ─── LZ5 Decompression ──────────────────────────────────────────────
    //
    // Ported from the verified working Python implementation:
    //   https://github.com/aremath/sm_rando/blob/master/rom_tools/compress/decompress.py
    // Algorithm spec: https://sneslab.net/wiki/LZ5
    //
    // Commands 0-6 are standard, command 7 is extended (2-byte header).
    // 0xFF terminates decompression.
    //
    // CRITICAL differences from our old broken implementation:
    //   1. 0xFF IS the end marker (not a no-op)
    //   2. Command 6 (Negative Repeat) takes 1 byte (relative offset), not 2
    //   3. Dictionary copies wrap around when referencing past current output end
    
    fun decompressLZ2(snesAddress: Int): ByteArray {
        val startPc = snesToPc(snesAddress)
        return decompressLZ5AtPc(startPc)
    }
    
    /**
     * Decompress and return both the data and the number of ROM bytes consumed.
     * Used by the export system to know how much space is available for recompression.
     */
    fun decompressLZ2WithSize(snesAddress: Int): Pair<ByteArray, Int> {
        val startPc = snesToPc(snesAddress)
        return decompressLZ5AtPcWithSize(startPc)
    }
    
    /** Decompress LZ5 and return (decompressed data, ROM bytes consumed). */
    fun decompressLZ5AtPcWithSize(startPc: Int): Pair<ByteArray, Int> {
        val result = decompressLZ5AtPc(startPc)
        // Re-scan to find end position (where 0xFF terminator is)
        var pos = startPc
        while (pos < romData.size) {
            val cmd = romData[pos].toInt() and 0xFF
            if (cmd == 0xFF) { pos++; break }
            val topBits = (cmd shr 5) and 7
            val length: Int
            if (topBits == 7) {
                val cmdCode = (cmd shr 2) and 7
                length = ((cmd and 0x03) shl 8 or (romData[pos + 1].toInt() and 0xFF)) + 1
                pos += 2
            } else {
                val cmdCode = topBits
                length = (cmd and 0x1F) + 1
                pos += 1
            }
            val cmdCode = if (topBits == 7) (cmd shr 2) and 7 else topBits
            when (cmdCode) {
                0 -> pos += length       // direct copy: skip length data bytes
                1 -> pos += 1            // byte fill: 1 byte
                2 -> pos += 2            // word fill: 2 bytes
                3 -> pos += 1            // increasing fill: 1 byte
                4, 5 -> pos += 2         // absolute copy: 2-byte address
                6, 7 -> pos += 1         // relative copy: 1 byte offset
            }
        }
        return Pair(result, pos - startPc)
    }
    
    /**
     * Decompress LZ5 data starting at the given PC offset.
     * Ported directly from aremath/sm_rando decompress.py
     */
    fun decompressLZ5AtPc(startPc: Int): ByteArray {
        val dst = ByteArray(0x20000) // 128KB max output (some rooms are very large)
        var dstPos = 0
        var pos = startPc
        
        while (pos < romData.size) {
            val nextCmd = romData[pos].toInt() and 0xFF
            
            // 0xFF = end of compressed data
            if (nextCmd == 0xFF) {
                pos++
                break
            }
            
            val cmdCode: Int
            val length: Int
            
            val topBits = (nextCmd shr 5) and 7
            if (topBits == 7) {
                // Extended command: 2-byte header
                // Bits 5-3 of first byte = actual command
                // Last 2 bits of first byte + all 8 bits of second byte = 10-bit length
                cmdCode = (nextCmd shr 2) and 7
                val highBits = nextCmd and 0x03
                val lowBits = romData[pos + 1].toInt() and 0xFF
                length = ((highBits shl 8) or lowBits) + 1
                pos += 2
            } else {
                // Standard command: 1-byte header
                cmdCode = topBits
                length = (nextCmd and 0x1F) + 1
                pos += 1
            }
            
            when (cmdCode) {
                0 -> {
                    // Direct copy: copy next `length` bytes from source
                    for (i in 0 until length) {
                        if (pos >= romData.size) break
                        dst[dstPos++] = romData[pos++]
                    }
                }
                1 -> {
                    // Byte fill: repeat one byte `length` times
                    val fillByte = romData[pos++]
                    for (i in 0 until length) {
                        dst[dstPos++] = fillByte
                    }
                }
                2 -> {
                    // Word fill: alternate two bytes for `length` bytes
                    val b1 = romData[pos++]
                    val b2 = romData[pos++]
                    for (i in 0 until length) {
                        dst[dstPos++] = if (i % 2 == 0) b1 else b2
                    }
                }
                3 -> {
                    // Increasing fill: write byte, increment by 1, `length` times
                    var b = romData[pos++].toInt() and 0xFF
                    for (i in 0 until length) {
                        dst[dstPos++] = (b and 0xFF).toByte()
                        b++
                    }
                }
                4 -> {
                    // Repeat (absolute address copy): copy `length` bytes from
                    // absolute position in output buffer. Wraps if past current end.
                    val addr = (romData[pos].toInt() and 0xFF) or
                        ((romData[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    copyFromOutput(dst, dstPos, addr, length) { it }
                    dstPos += length
                }
                5 -> {
                    // XOR Repeat: same as cmd 4 but XOR each byte with 0xFF
                    val addr = (romData[pos].toInt() and 0xFF) or
                        ((romData[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    copyFromOutput(dst, dstPos, addr, length) { (it.toInt() xor 0xFF).toByte() }
                    dstPos += length
                }
                6 -> {
                    // Negative Repeat (relative address copy): copy `length` bytes
                    // from (current_position - offset) in output buffer.
                    // Takes only 1 byte for the relative offset!
                    val relOffset = romData[pos++].toInt() and 0xFF
                    val srcAddr = dstPos - relOffset
                    copyFromOutput(dst, dstPos, srcAddr, length) { it }
                    dstPos += length
                }
                7 -> {
                    // Extended cmd 7 = Negative XOR Repeat (relative + XOR 0xFF)
                    val relOffset = romData[pos++].toInt() and 0xFF
                    val srcAddr = dstPos - relOffset
                    copyFromOutput(dst, dstPos, srcAddr, length) { (it.toInt() xor 0xFF).toByte() }
                    dstPos += length
                }
            }
            
            if (dstPos >= dst.size) break // Safety
        }
        
        return dst.copyOf(dstPos)
    }
    
    /**
     * Copy bytes from output buffer with wrap-around support.
     * When the copy range extends past what has been written, bytes wrap
     * (repeat from the start of the copied portion).
     * Ported from aremath/sm_rando get_copy_bytes().
     */
    private fun copyFromOutput(
        dst: ByteArray, dstPos: Int, srcAddr: Int, length: Int, 
        transform: (Byte) -> Byte
    ) {
        // First pass: copy bytes that already exist in the output
        var srcIdx = srcAddr
        var written = 0
        while (written < length && srcIdx < dstPos) {
            if (srcIdx >= 0) {
                dst[dstPos + written] = transform(dst[srcIdx])
            } else {
                dst[dstPos + written] = transform(0)
            }
            written++
            srcIdx++
        }
        // Second pass: wrap-around — copy from what we just wrote
        var wrapIdx = 0
        while (written < length) {
            dst[dstPos + written] = transform(dst[dstPos + wrapIdx])
            written++
            wrapIdx++
        }
    }
    
    // ─── FX data parsing ──────────────────────────────────────────────

    /**
     * 16-byte FX entry in bank $83.
     * See SMILE documentation for full field descriptions.
     */
    data class FxEntry(
        val doorSelect: Int,       // +0: door address for door-specific FX (0x0000 = default)
        val liquidSurfaceStart: Int,// +2: starting liquid height (0xFFFF = none)
        val liquidSurfaceNew: Int,  // +4: target liquid height for rising/lowering
        val liquidSpeed: Int,       // +6: vertical speed of liquid
        val liquidDelay: Int,       // +8: delay before liquid moves (1 byte)
        val fxType: Int,            // +9: effect type (1 byte)
        val fxBitA: Int,            // +10: lighting/transparency (1 byte)
        val fxBitB: Int,            // +11: layer 3 draw priority (1 byte)
        val fxBitC: Int,            // +12: liquid options bitfield (1 byte)
        val paletteFxBitflags: Int, // +13: palette glow toggles (1 byte)
        val tileAnimBitflags: Int,  // +14: animated tile toggles (1 byte)
        val paletteBlend: Int       // +15: palette blend index (1 byte)
    ) {
        val fxTypeName: String get() = FX_TYPE_NAMES[fxType] ?: "Unknown (0x${fxType.toString(16).uppercase().padStart(2, '0')})"
        val hasLiquid: Boolean get() = liquidSurfaceStart != 0xFFFF

        companion object {
            // Names from SMILE source (FX1_1.frx Layer3Type dropdown)
            val FX_TYPE_NAMES = mapOf(
                0x00 to "None",
                0x02 to "Lava",
                0x04 to "Acid",
                0x06 to "Water",
                0x08 to "Spores",
                0x0A to "Rain",
                0x0C to "Fog",
                0x0E to "Haze",
                0x10 to "Dense Fog",
                0x16 to "Firefleas",
                0x18 to "Lightning",
                0x1A to "Smoke",
                0x1C to "Heat Shimmer",
                0x20 to "Sky Scrolling",
                0x24 to "Fireflea FX",
                0x26 to "4 Statues",
                0x28 to "Ceres Elevator",
                0x2A to "Ceres Ridley",
                0x2C to "Haze",
            )

            // C byte bitfield (from SMILE SmileMod1.bas)
            val LIQUID_OPTION_NAMES = mapOf(
                0x01 to "Small Tide",
                0x02 to "Large Tide",
                0x20 to "BG Warp-Line Shift",
                0x40 to "BG Warp-Cascade Heat",
                0x80 to "Flow Left",
            )
        }
    }

    /**
     * Parse FX data for a room. fxPtr is a 16-bit pointer in bank $83.
     * Returns the default FX entry (door select = 0x0000) plus any door-specific entries.
     */
    fun parseFxEntries(fxPtr: Int): List<FxEntry> {
        if (fxPtr == 0 || fxPtr == 0xFFFF) return emptyList()
        val snesAddr = BANK_FX or fxPtr
        var pc = snesToPc(snesAddr)
        val entries = mutableListOf<FxEntry>()
        var safety = 0
        while (pc + 15 < romData.size && safety < 16) {
            val entry = FxEntry(
                doorSelect = readUInt16At(pc),
                liquidSurfaceStart = readUInt16At(pc + 2),
                liquidSurfaceNew = readUInt16At(pc + 4),
                liquidSpeed = readUInt16At(pc + 6),
                liquidDelay = romData[pc + 8].toInt() and 0xFF,
                fxType = romData[pc + 9].toInt() and 0xFF,
                fxBitA = romData[pc + 10].toInt() and 0xFF,
                fxBitB = romData[pc + 11].toInt() and 0xFF,
                fxBitC = romData[pc + 12].toInt() and 0xFF,
                paletteFxBitflags = romData[pc + 13].toInt() and 0xFF,
                tileAnimBitflags = romData[pc + 14].toInt() and 0xFF,
                paletteBlend = romData[pc + 15].toInt() and 0xFF
            )
            entries.add(entry)
            if (entry.doorSelect == 0) break
            pc += 16
            safety++
        }
        return entries
    }

    // ─── Scroll data parsing ──────────────────────────────────────────

    /**
     * Parse per-screen scroll data for a room.
     * Each byte = scroll color: 0x00=Red, 0x01=Blue, 0x02=Green.
     * Special pointers: 0x0000 = all blue, 0x0001 = all green.
     */
    fun parseScrollData(scrollsPtr: Int, width: Int, height: Int): IntArray {
        val totalScreens = width * height
        if (totalScreens <= 0) return IntArray(0)

        when (scrollsPtr) {
            0x0000 -> return IntArray(totalScreens) { 0x01 }
            0x0001 -> return IntArray(totalScreens) { 0x02 }
        }

        val snesAddr = BANK_ROOM_DATA or scrollsPtr
        val pc = snesToPc(snesAddr)
        if (pc + totalScreens > romData.size) return IntArray(totalScreens) { 0x01 }

        return IntArray(totalScreens) { i -> romData[pc + i].toInt() and 0xFF }
    }

    // ─── Room state info ──────────────────────────────────────────────

    /**
     * Parsed info about a room state condition.
     */
    data class RoomStateInfo(
        val conditionCode: Int,    // E5E6=default, E612=event, E629=boss, etc.
        val conditionArg: Int,     // event/boss flag byte (0 for no-arg conditions)
        val stateDataPcOffset: Int,
        val conditionName: String
    ) {
        companion object {
            val STATE_CONDITION_NAMES = mapOf(
                0xE5E6 to "Standard (default)",
                0xE5EB to "Door Event",
                0xE5FF to "Event Check",
                0xE612 to "Boss Check",
                0xE629 to "Morph Ball Check",
                0xE640 to "Power Bombs",
                0xE652 to "Speed Booster",
                0xE669 to "Landing Site Wake",
                0xE678 to "Tourian Access",
            )

            val EVENT_NAMES = mapOf(
                0x00 to "Zebes is awake",
                0x01 to "Giant metroid ate sidehopper",
                0x02 to "Mother Brain glass broken",
                0x03 to "Zebetite 1 destroyed",
                0x04 to "Zebetite 2 destroyed",
                0x05 to "Zebetite 3 destroyed",
                0x06 to "Phantoon statue grey",
                0x07 to "Ridley statue grey",
                0x08 to "Draygon statue grey",
                0x09 to "Kraid statue grey",
                0x0A to "Path to Tourian open",
                0x0B to "Maridia tube broken",
                0x0C to "LN Chozo lowered acid",
                0x0D to "Shaktool cleared path",
                0x0E to "Zebes timebomb set",
                0x0F to "Animals saved",
            )
        }
    }

    /**
     * Parse all room states with descriptive info.
     */
    fun parseRoomStates(roomId: Int): List<RoomStateInfo> {
        val pcOffset = roomIdToPc(roomId)
        if (pcOffset < 0 || pcOffset + 11 > romData.size) return emptyList()

        val stateListOffset = pcOffset + 11
        val results = mutableListOf<RoomStateInfo>()
        var pos = stateListOffset
        val maxPos = minOf(stateListOffset + 200, romData.size - 1)

        while (pos + 1 < maxPos) {
            val code = readUInt16At(pos)
            when (code) {
                0xE5E6 -> {
                    val statePc = pos + 2
                    if (statePc + STATE_DATA_SIZE <= romData.size) {
                        results.add(RoomStateInfo(code, 0, statePc,
                            RoomStateInfo.STATE_CONDITION_NAMES[code] ?: "Default"))
                    }
                    return results
                }
                0xE5EB -> {
                    if (pos + 5 < romData.size) {
                        val arg = readUInt16At(pos + 2)
                        val statePtr = readUInt16At(pos + 4)
                        val statePc = snesToPc(BANK_ROOM_DATA or statePtr)
                        val argName = RoomStateInfo.EVENT_NAMES[arg] ?: "Event 0x${arg.toString(16).uppercase()}"
                        if (statePc + STATE_DATA_SIZE <= romData.size) {
                            results.add(RoomStateInfo(code, arg, statePc,
                                "${RoomStateInfo.STATE_CONDITION_NAMES[code] ?: "Event"}: $argName"))
                        }
                    }
                    pos += 6
                }
                0xE612, 0xE629 -> {
                    if (pos + 4 < romData.size) {
                        val arg = romData[pos + 2].toInt() and 0xFF
                        val statePtr = readUInt16At(pos + 3)
                        val statePc = snesToPc(BANK_ROOM_DATA or statePtr)
                        val argName = RoomStateInfo.EVENT_NAMES[arg] ?: "Flag 0x${arg.toString(16).uppercase()}"
                        if (statePc + STATE_DATA_SIZE <= romData.size) {
                            results.add(RoomStateInfo(code, arg, statePc,
                                "${RoomStateInfo.STATE_CONDITION_NAMES[code] ?: "Check"}: $argName"))
                        }
                    }
                    pos += 5
                }
                0xE5FF, 0xE640, 0xE652, 0xE669, 0xE678 -> {
                    if (pos + 3 < romData.size) {
                        val statePtr = readUInt16At(pos + 2)
                        val statePc = snesToPc(BANK_ROOM_DATA or statePtr)
                        if (statePc + STATE_DATA_SIZE <= romData.size) {
                            results.add(RoomStateInfo(code, 0, statePc,
                                RoomStateInfo.STATE_CONDITION_NAMES[code] ?: "Check"))
                        }
                    }
                    pos += 4
                }
                else -> return results
            }
        }
        return results
    }

    /**
     * Read a 26-byte state data block from a given PC offset.
     * Returns a map of field names to values for display/editing.
     */
    fun readStateData(stateDataPcOffset: Int): Map<String, Int> {
        if (stateDataPcOffset + 26 > romData.size) return emptyMap()
        return mapOf(
            "levelDataPtr" to readUInt24At(stateDataPcOffset),
            "tileset" to (romData[stateDataPcOffset + 3].toInt() and 0xFF),
            "musicData" to (romData[stateDataPcOffset + 4].toInt() and 0xFF),
            "musicTrack" to (romData[stateDataPcOffset + 5].toInt() and 0xFF),
            "fxPtr" to readUInt16At(stateDataPcOffset + 6),
            "enemySetPtr" to readUInt16At(stateDataPcOffset + 8),
            "enemyGfxPtr" to readUInt16At(stateDataPcOffset + 10),
            "bgScrolling" to readUInt16At(stateDataPcOffset + 12),
            "roomScrollsPtr" to readUInt16At(stateDataPcOffset + 14),
            "mainAsmPtr" to readUInt16At(stateDataPcOffset + 18),
            "plmSetPtr" to readUInt16At(stateDataPcOffset + 20),
            "bgDataPtr" to readUInt16At(stateDataPcOffset + 22),
            "setupAsmPtr" to readUInt16At(stateDataPcOffset + 24),
        )
    }

    // ─── Utility ──────────────────────────────────────────────────────

    private fun readUInt16At(offset: Int): Int = readU16(romData, offset)

    private fun readUInt24At(offset: Int): Int = readU24(romData, offset)
    
    fun getRomData(): ByteArray = romData

    /** Convert PC offset back to SNES address (LoROM). */
    fun pcToSnes(pcOffset: Int): Int {
        val adjusted = pcOffset - romStartOffset
        val bank = (adjusted / 0x8000) or 0x80
        val offset = (adjusted % 0x8000) + 0x8000
        return (bank shl 16) or offset
    }

    /** Get the PC offset of the default state data block for a room.
     *  The PLM set pointer is at stateDataPcOffset + 20. */
    fun getStateDataPcOffset(roomId: Int): Int? {
        val pcOffset = roomIdToPc(roomId)
        if (pcOffset < 0 || pcOffset + 11 > romData.size) return null
        return findDefaultStateData(pcOffset + 11)
    }

    /**
     * Find ALL state data PC offsets for a room by parsing the state condition list.
     * Each room can have multiple state conditions (E629 boss check, E612 event, etc.)
     * plus the E5E6 default. Returns PC offsets of every 26-byte state data block.
     *
     * State condition entry sizes (verified against snesrev/sm $8F functions):
     *   E5E6: default (terminates list), 26-byte state data follows inline
     *   E5EB: code(2)+doorPtr(2)+statePtr(2) = 6 bytes  (RoomDefStateSelect_Door)
     *   E5FF: code(2)+statePtr(2)            = 4 bytes  (TourianBoss01: hardcoded boss check)
     *   E612: code(2)+eventFlag(1)+statePtr(2) = 5 bytes (IsEventSet)
     *   E629: code(2)+bossFlag(1)+statePtr(2)  = 5 bytes (IsBossDead)
     *   E640/E652/E669/E678: code(2)+statePtr(2) = 4 bytes
     */
    fun findAllStateDataOffsets(roomId: Int): List<Int> {
        val pcOffset = roomIdToPc(roomId)
        if (pcOffset < 0 || pcOffset + 11 > romData.size) return emptyList()

        val stateListOffset = pcOffset + 11
        val results = mutableListOf<Int>()
        var pos = stateListOffset
        val maxPos = minOf(stateListOffset + 200, romData.size - 1)

        while (pos + 1 < maxPos) {
            val code = readUInt16At(pos)
            when (code) {
                0xE5E6 -> {
                    val statePc = pos + 2
                    if (statePc + STATE_DATA_SIZE <= romData.size) results.add(statePc)
                    return results
                }
                0xE5EB -> {
                    // door_ptr(2) + state_ptr(2) = 6 bytes total
                    if (pos + 5 < romData.size) {
                        val statePtr = readUInt16At(pos + 4)
                        val statePc = snesToPc(BANK_ROOM_DATA or statePtr)
                        if (statePc + STATE_DATA_SIZE <= romData.size) results.add(statePc)
                    }
                    pos += 6
                }
                0xE612, 0xE629 -> {
                    // 1-byte flag + 2-byte state pointer = 5 bytes total
                    if (pos + 4 < romData.size) {
                        val statePtr = readUInt16At(pos + 3)
                        val statePc = snesToPc(BANK_ROOM_DATA or statePtr)
                        if (statePc + STATE_DATA_SIZE <= romData.size) results.add(statePc)
                    }
                    pos += 5
                }
                0xE5FF, 0xE640, 0xE652, 0xE669, 0xE678 -> {
                    // 2-byte state pointer only = 4 bytes total
                    if (pos + 3 < romData.size) {
                        val statePtr = readUInt16At(pos + 2)
                        val statePc = snesToPc(BANK_ROOM_DATA or statePtr)
                        if (statePc + STATE_DATA_SIZE <= romData.size) results.add(statePc)
                    }
                    pos += 4
                }
                else -> return results
            }
        }
        return results
    }
    
    // ─── PLM (Post Load Modification) parsing ───────────────────────
    
    data class PlmEntry(val id: Int, val x: Int, val y: Int, val param: Int)
    data class RoomItemInfo(val roomId: Int, val area: Int, val plm: PlmEntry)

    /**
     * Scan all rooms for item PLMs and return a list with room/area metadata.
     * Useful for finding used collection bit parameters per area.
     */
    fun scanAllItemPlms(roomIds: List<Int>): List<RoomItemInfo> {
        val result = mutableListOf<RoomItemInfo>()
        for (rid in roomIds) {
            val room = readRoomHeader(rid) ?: continue
            if (room.plmSetPtr == 0 || room.plmSetPtr == 0xFFFF) continue
            val plms = parsePlmSet(room.plmSetPtr)
            for (plm in plms) {
                if (isItemPlm(plm.id)) {
                    result.add(RoomItemInfo(rid, room.area, plm))
                }
            }
        }
        return result
    }
    
    /**
     * Parse the PLM set for a room. plmSetPtr is a 16-bit pointer in bank $8F.
     * Each PLM entry is 6 bytes: 2-byte ID, 1-byte X, 1-byte Y, 2-byte param.
     * Terminated by ID == 0x0000.
     */
    fun parsePlmSet(plmSetPtr: Int): List<PlmEntry> {
        if (plmSetPtr == 0 || plmSetPtr == 0xFFFF) return emptyList()
        val snesAddr = BANK_ROOM_DATA or plmSetPtr
        var pc = snesToPc(snesAddr)
        val entries = mutableListOf<PlmEntry>()
        var safety = 0
        while (pc + 5 < romData.size && safety < 256) {
            val id = readUInt16At(pc)
            if (id == 0) break
            val x = romData[pc + 2].toInt() and 0xFF
            val y = romData[pc + 3].toInt() and 0xFF
            val param = readUInt16At(pc + 4)
            entries.add(PlmEntry(id, x, y, param))
            pc += 6
            safety++
        }
        return entries
    }

    /**
     * Return all PLM entries for a room from every state (E629, E612, E5E6, etc.).
     * Merged and deduplicated by (id, x, y) so the editor shows every PLM that exists
     * in any state, including rogue door caps that only appear in non-default states.
     */
    fun getAllPlmEntriesForRoom(roomId: Int): List<PlmEntry> {
        val stateOffsets = findAllStateDataOffsets(roomId)
        val distinctPtrs = mutableSetOf<Int>()
        for (off in stateOffsets) {
            if (off + 21 < romData.size) {
                val ptr = readUInt16At(off + 20)
                if (ptr != 0 && ptr != 0xFFFF) distinctPtrs.add(ptr)
            }
        }
        val result = mutableListOf<PlmEntry>()
        val seen = mutableSetOf<Triple<Int, Int, Int>>()
        for (ptr in distinctPtrs) {
            for (plm in parsePlmSet(ptr)) {
                val key = Triple(plm.id, plm.x, plm.y)
                if (key !in seen) {
                    seen.add(key)
                    result.add(plm)
                }
            }
        }
        return result
    }
    
    // ─── Enemy population parsing ─────────────────────────────────────

    /**
     * Full 16-byte enemy population entry matching SMILE's "Type Enemy".
     * Fields: Species(2), X(2), Y(2), Orientation(2), Special/PropX(2),
     *         GfxExtra(2), Speed(2), Speed2(2).
     * The last 3 words (extra1/extra2/extra3) are enemy-specific and MUST be
     * preserved during round-tripping — zeroing them can crash the game.
     */
    data class EnemyEntry(
        val id: Int, val x: Int, val y: Int,
        val initParam: Int, val properties: Int,
        val extra1: Int = 0, val extra2: Int = 0, val extra3: Int = 0
    )

    /**
     * Parse enemy population data. enemySetPtr is a 16-bit pointer in bank $A1.
     * Each entry is 16 bytes. Terminated by ID=0xFFFF.
     */
    fun parseEnemyPopulation(enemySetPtr: Int): List<EnemyEntry> {
        if (enemySetPtr == 0 || enemySetPtr == 0xFFFF) return emptyList()
        val snesAddr = BANK_ENEMY_SET or enemySetPtr
        var pc = snesToPc(snesAddr)
        val entries = mutableListOf<EnemyEntry>()
        var safety = 0
        while (pc + 15 < romData.size && safety < 64) {
            val id = readUInt16At(pc)
            if (id == 0xFFFF || id == 0) break
            entries.add(EnemyEntry(
                id = id,
                x = readUInt16At(pc + 2),
                y = readUInt16At(pc + 4),
                initParam = readUInt16At(pc + 6),
                properties = readUInt16At(pc + 8),
                extra1 = readUInt16At(pc + 10),
                extra2 = readUInt16At(pc + 12),
                extra3 = readUInt16At(pc + 14)
            ))
            pc += 16
            safety++
        }
        return entries
    }

    // ─── Enemy GFX set parsing (bank $B4) ───────────────────────────

    data class EnemyGfxEntry(val speciesId: Int, val paletteIndex: Int)

    /**
     * Parse the enemy GFX set. The enemyGfxPtr (from state data offset +10)
     * points directly to the first entry (past the 7-byte debug name).
     * Each entry is 4 bytes: species ID (2) + palette index (2).
     * Terminated by species ID = 0xFFFF.
     */
    fun parseEnemyGfxSet(enemyGfxPtr: Int): List<EnemyGfxEntry> {
        if (enemyGfxPtr == 0 || enemyGfxPtr == 0xFFFF) return emptyList()
        val snesAddr = BANK_ENEMY_GFX or enemyGfxPtr
        var pc = snesToPc(snesAddr)
        val entries = mutableListOf<EnemyGfxEntry>()
        var safety = 0
        while (pc + 3 < romData.size && safety < 16) {
            val id = readUInt16At(pc)
            if (id == 0xFFFF) break
            entries.add(EnemyGfxEntry(id, readUInt16At(pc + 2)))
            pc += 4
            safety++
        }
        return entries
    }

    // ─── Door entry parsing ──────────────────────────────────────────

    /**
     * 12-byte door entry in bank $83. Format (LE):
     *   +0  destRoomPtr  (2) destination room ID within bank $8F
     *   +2  bitflag      (2) direction (high byte) + flags (low byte: bit7=elevator)
     *   +4  doorCapCode  (2) ASM pointer ($8F) for scroll changes on entry
     *   +6  screenX      (1) spawn screen X
     *   +7  screenY      (1) spawn screen Y
     *   +8  distFromDoor (2) Samus distance from door edge (0x8000 = default)
     *   +10 entryCode    (2) ASM pointer ($8F) to run on arrival
     */
    data class DoorEntry(
        val destRoomPtr: Int,
        val bitflag: Int,
        val doorCapCode: Int,
        val screenX: Int,
        val screenY: Int,
        val distFromDoor: Int,
        val entryCode: Int,
        /** Raw 16-bit pointer into bank $83 — this is the DoorDef address for WRAM 0x078D. */
        val doorDefPtr: Int = 0
    ) {
        val direction: Int get() = (bitflag shr 8) and 0xFF
        val directionName: String get() = when (direction and 0x03) {
            0 -> "Right"
            1 -> "Left"
            2 -> "Down"
            3 -> "Up"
            else -> "?"
        }
        val isElevator: Boolean get() = (bitflag and 0x80) != 0
    }

    /**
     * Read a single door entry by index from the room's door-out list.
     * doorOutPtr is within bank $8F; each list slot is a 2-byte pointer into bank $83.
     */
    fun parseDoorEntry(doorOutPtr: Int, doorIndex: Int): DoorEntry? {
        if (doorOutPtr == 0 || doorOutPtr == 0xFFFF) return null
        val listPc = snesToPc(BANK_ROOM_DATA or doorOutPtr)
        val ptrOff = listPc + doorIndex * 2
        if (ptrOff + 1 >= romData.size) return null
        val entryPtr = readUInt16At(ptrOff)
        if (entryPtr < 0x8000) return null
        val entryPc = snesToPc(BANK_FX or entryPtr)
        if (entryPc + 11 >= romData.size) return null
        val destRoom = readUInt16At(entryPc)
        if (destRoom < 0x8000 || destRoom == 0xFFFF) return null
        return DoorEntry(
            destRoomPtr = destRoom,
            bitflag = readUInt16At(entryPc + 2),
            doorCapCode = readUInt16At(entryPc + 4),
            screenX = romData[entryPc + 6].toInt() and 0xFF,
            screenY = romData[entryPc + 7].toInt() and 0xFF,
            distFromDoor = readUInt16At(entryPc + 8),
            entryCode = readUInt16At(entryPc + 10),
            doorDefPtr = entryPtr
        )
    }

    /**
     * Parse all door entries for a room. Reads the door-out list until an
     * invalid pointer is encountered, up to [maxDoors].
     */
    fun parseDoorList(doorOutPtr: Int, maxDoors: Int = 16): List<DoorEntry> {
        val entries = mutableListOf<DoorEntry>()
        for (i in 0 until maxDoors) {
            entries.add(parseDoorEntry(doorOutPtr, i) ?: break)
        }
        return entries
    }

    /**
     * Return the block type (0–15) at (bx, by) in decompressed level data.
     * Level data layout: bytes 0–1 = layer1 size, then 2-byte words per block (type in high nibble of word).
     * Returns null if out of bounds or level data too short.
     */
    fun blockTypeAt(levelData: ByteArray, blocksWide: Int, blocksTall: Int, bx: Int, by: Int): Int? {
        if (levelData.size < 2 || bx < 0 || bx >= blocksWide || by < 0 || by >= blocksTall) return null
        val tileDataStart = 2
        val offset = tileDataStart + (by * blocksWide + bx) * 2
        if (offset + 1 >= levelData.size) return null
        val word = (levelData[offset].toInt() and 0xFF) or ((levelData[offset + 1].toInt() and 0xFF) shl 8)
        return (word shr 12) and 0x0F
    }

    /**
     * Return the BTS byte at (bx, by) in decompressed level data.
     * BTS data starts immediately after the layer1 tile words.
     */
    fun btsAt(levelData: ByteArray, blocksWide: Int, blocksTall: Int, bx: Int, by: Int): Int? {
        if (levelData.size < 2 || bx < 0 || bx >= blocksWide || by < 0 || by >= blocksTall) return null
        val layer1Size = (levelData[0].toInt() and 0xFF) or ((levelData[1].toInt() and 0xFF) shl 8)
        val btsStart = 2 + layer1Size
        val idx = by * blocksWide + bx
        val offset = btsStart + idx
        if (offset >= levelData.size) return null
        return levelData[offset].toInt() and 0xFF
    }

    /**
     * Auto-derive the closing door cap position for a door entering [destRoomId]
     * from direction [direction] (0=Right,1=Left,2=Down,3=Up) at entry screen
     * ([screenX],[screenY]).
     *
     * Scans the destination room's level data for type-9 (door) blocks on the
     * entry edge, then picks the topmost/leftmost block on the matching screen.
     * The cap position is 1 tile INWARD from the door block (the game's
     * SpawnDoorClosingPLM places the cap adjacent to the door opening, not on it).
     * Returns (capY shl 8) or capX as a doorCapCode, or null if no door blocks found.
     */
    fun deriveDoorCapPosition(destRoomId: Int, direction: Int, screenX: Int, screenY: Int): Int? {
        val room = readRoomHeader(destRoomId) ?: return null
        if (room.levelDataPtr == 0) return null
        val levelData = try { decompressLZ2(room.levelDataPtr) } catch (_: Exception) { return null }

        val blocksWide = room.width * 16
        val blocksTall = room.height * 16
        val dir = direction and 0x03

        data class DoorBlock(val bx: Int, val by: Int)
        val doorBlocks = mutableListOf<DoorBlock>()

        when (dir) {
            0 -> { // Right: entering from left edge (x=0)
                val screenStartY = screenY * 16
                val screenEndY = screenStartY + 16
                for (by in screenStartY until minOf(screenEndY, blocksTall)) {
                    if (blockTypeAt(levelData, blocksWide, blocksTall, 0, by) == 0x9)
                        doorBlocks.add(DoorBlock(0, by))
                }
            }
            1 -> { // Left: entering from right edge (x=blocksWide-1)
                val edgeX = blocksWide - 1
                val screenStartY = screenY * 16
                val screenEndY = screenStartY + 16
                for (by in screenStartY until minOf(screenEndY, blocksTall)) {
                    if (blockTypeAt(levelData, blocksWide, blocksTall, edgeX, by) == 0x9)
                        doorBlocks.add(DoorBlock(edgeX, by))
                }
            }
            2 -> { // Down: entering from top edge (y=0)
                val screenStartX = screenX * 16
                val screenEndX = screenStartX + 16
                for (bx in screenStartX until minOf(screenEndX, blocksWide)) {
                    if (blockTypeAt(levelData, blocksWide, blocksTall, bx, 0) == 0x9)
                        doorBlocks.add(DoorBlock(bx, 0))
                }
            }
            3 -> { // Up: entering from bottom edge (y=blocksTall-1)
                val edgeY = blocksTall - 1
                val screenStartX = screenX * 16
                val screenEndX = screenStartX + 16
                for (bx in screenStartX until minOf(screenEndX, blocksWide)) {
                    if (blockTypeAt(levelData, blocksWide, blocksTall, bx, edgeY) == 0x9)
                        doorBlocks.add(DoorBlock(bx, edgeY))
                }
            }
        }

        if (doorBlocks.isEmpty()) return null
        val best = doorBlocks.first()
        val capX: Int
        val capY: Int
        when (dir) {
            0 -> { capX = (best.bx + 1).coerceAtMost(blocksWide - 1); capY = best.by }
            1 -> { capX = (best.bx - 1).coerceAtLeast(0);             capY = best.by }
            2 -> { capX = best.bx; capY = (best.by + 1).coerceAtMost(blocksTall - 1) }
            3 -> { capX = best.bx; capY = (best.by - 1).coerceAtLeast(0) }
            else -> { capX = best.bx; capY = best.by }
        }
        return (capY shl 8) or capX
    }

    /**
     * Return the PC offset of a door entry in bank $83, for use when patching.
     * Returns null if the door index is invalid.
     */
    fun doorEntryPcOffset(doorOutPtr: Int, doorIndex: Int): Int? {
        if (doorOutPtr == 0 || doorOutPtr == 0xFFFF) return null
        val listPc = snesToPc(BANK_ROOM_DATA or doorOutPtr)
        val ptrOff = listPc + doorIndex * 2
        if (ptrOff + 1 >= romData.size) return null
        val entryPtr = readUInt16At(ptrOff)
        if (entryPtr < 0x8000) return null
        val entryPc = snesToPc(BANK_FX or entryPtr)
        if (entryPc + 11 >= romData.size) return null
        return entryPc
    }

    data class VanillaDoorMatch(val entryCode: Int, val doorCapCode: Int, val orientation: Int)

    /**
     * Search all vanilla doors across all rooms to find the best match
     * for entering [destRoomId] from direction [direction] (0=R,1=L,2=D,3=U).
     * Optionally prefer a match near ([screenX],[screenY]).
     * Returns entryCode, doorCapCode, and orientation from the best matching vanilla door.
     */
    fun findVanillaDoorMatch(destRoomId: Int, direction: Int, screenX: Int = -1, screenY: Int = -1): VanillaDoorMatch? {
        data class Candidate(val door: DoorEntry, val srcRoom: Int)
        val candidates = mutableListOf<Candidate>()

        for (roomId in 0x91F8..0x9FFF) {
            val room = readRoomHeader(roomId) ?: continue
            if (room.width !in 1..16 || room.height !in 1..16) continue
            val doors = parseDoorList(room.doorOut)
            for (door in doors) {
                if (door.destRoomPtr == destRoomId && (door.direction and 0x03) == (direction and 0x03)) {
                    candidates.add(Candidate(door, roomId))
                }
            }
        }

        if (candidates.isEmpty()) return null

        val best = if (screenX < 0 || screenY < 0) candidates.first()
        else candidates.sortedBy {
            kotlin.math.abs(it.door.screenX - screenX) + kotlin.math.abs(it.door.screenY - screenY)
        }.first()

        return VanillaDoorMatch(best.door.entryCode, best.door.doorCapCode, best.door.direction)
    }

    fun findVanillaEntryCode(destRoomId: Int, direction: Int, screenX: Int = -1, screenY: Int = -1): Int {
        return findVanillaDoorMatch(destRoomId, direction, screenX, screenY)?.entryCode ?: 0x0000
    }

    /**
     * Door cap colors from PLM type IDs.
     * Door caps in SM are PLMs placed at door positions. The PLM ID determines color:
     *   $C842/$C848 = Blue (beam)
     *   $C85A/$C860 = Red/Pink (5 missiles)
     *   $C866/$C86C = Green (super missile)
     *   $C872/$C878 = Yellow (power bomb)
     * Returns ARGB color or null if not a door cap PLM.
     */

    // ─── Minimap / Pause Screen Map ──────────────────────────────────

    /**
     * Read the minimap tile data for a given area (0-6).
     *
     * ROM layout: each area has 0x1000 bytes stored as two 32×32 halves.
     * Left half (x=0..31) at offset base, right half (x=32..63) at base+0x800.
     * Each tile is a 16-bit LE word at: halfBase + ((y+1) * 32 + (x % 32)) * 2
     */
    fun readMinimapTiles(area: Int): MinimapData {
        require(area in 0 until MinimapData.NUM_AREAS) { "Invalid area: $area" }
        val basePc = snesToPc(MinimapData.AREA_MAP_ADDRESSES[area])
        val tiles = IntArray(MinimapData.TILE_COUNT)

        for (y in 0 until MinimapData.MAP_HEIGHT) {
            for (x in 0 until MinimapData.MAP_WIDTH) {
                val halfBase = if (x < 32) basePc else basePc + 0x800
                val localX = x % 32
                // Row 0 in the ROM is a header row; room mapY=0 maps to ROM row 1
                val offset = halfBase + ((y + 1) * 32 + localX) * 2
                if (offset + 1 < romData.size) {
                    tiles[y * MinimapData.MAP_WIDTH + x] = readU16(romData, offset)
                }
            }
        }
        return MinimapData(area, tiles)
    }

    /**
     * Read the 256 minimap 2bpp tile graphics from ROM PC $D3200.
     * Returns a 256-element array where each element is an IntArray of 64 pixel values (0-3).
     * Pixel layout is row-major: pixels[row * 8 + col].
     */
    fun readMinimapTileGraphics(): Array<IntArray> {
        val base = 0xD3200
        return Array(256) { tileIdx ->
            val offset = base + tileIdx * 16
            val pixels = IntArray(64)
            for (row in 0 until 8) {
                val bp0 = if (offset + row * 2 < romData.size) romData[offset + row * 2].toInt() and 0xFF else 0
                val bp1 = if (offset + row * 2 + 1 < romData.size) romData[offset + row * 2 + 1].toInt() and 0xFF else 0
                for (col in 0 until 8) {
                    val bit = 7 - col
                    pixels[row * 8 + col] = ((bp0 shr bit) and 1) or (((bp1 shr bit) and 1) shl 1)
                }
            }
            pixels
        }
    }

    // ─── Layer 2 / BG Data ─────────────────────────────────────────────

    /**
     * Read BG data (Layer 2 background tilemap) for a room.
     *
     * BG data pointer (in bank $8F) points to a structure with a 2-byte header:
     *   0x0004 = real data — next 3 bytes are pointer to compressed nametable
     *   Other  = unsupported format
     *
     * The decompressed nametable is 32×32 SNES BG tilemap words (1024 words).
     * Each word: bits 0-9 = 8x8 tile number, bits 10-12 = palette,
     * bit 13 = priority, bit 14 = H-flip, bit 15 = V-flip.
     *
     * For rooms with bgScrolling == 0x0000 (embedded Layer 2), the level data
     * itself contains a second layer after Layer 1, in the same metatile format.
     *
     * Returns an IntArray of tilemap words (1024 entries for scrolling BG),
     * or null if the format is unrecognized or the pointer is invalid.
     */
    fun readBgTilemap(bgDataPtr: Int): IntArray? {
        if (bgDataPtr == 0) return null
        val headerPc = snesToPc(BANK_ROOM_DATA or bgDataPtr)
        if (headerPc < 0 || headerPc + 4 >= romData.size) return null

        val header = readUInt16At(headerPc)
        // Header 0x0004 = real data: next 3 bytes are SNES pointer to compressed tilemap
        val dataAddr: Int = when (header) {
            0x0004 -> readUInt24At(headerPc + 2)
            else -> return null // unsupported format (boss BG, RAM-loaded, etc.)
        }
        if (dataAddr == 0) return null

        val decompressed = try { decompressLZ2(dataAddr) } catch (_: Exception) { return null }
        // Expect at least 1024 words = 2048 bytes (32×32 nametable)
        val wordCount = minOf(decompressed.size / 2, 1024)
        if (wordCount == 0) return null

        val words = IntArray(wordCount)
        for (i in 0 until wordCount) {
            val lo = decompressed[i * 2].toInt() and 0xFF
            val hi = decompressed[i * 2 + 1].toInt() and 0xFF
            words[i] = (hi shl 8) or lo
        }
        return words
    }

    /**
     * Read embedded Layer 2 tile data from decompressed level data.
     * For rooms with bgScrolling == 0x0000, Layer 2 data is stored after Layer 1
     * in the same metatile word format. Returns an IntArray of tile words covering
     * the full room, or null if no embedded Layer 2 data is present.
     */
    fun readEmbeddedLayer2(levelData: ByteArray, blocksWide: Int, blocksTall: Int): IntArray? {
        if (levelData.size < 2) return null
        val layer1Size = (levelData[0].toInt() and 0xFF) or ((levelData[1].toInt() and 0xFF) shl 8)
        val totalBlocks = blocksWide * blocksTall
        val layer2Start = 2 + layer1Size
        val layer2End = layer2Start + totalBlocks * 2
        val btsExpected = layer2End + totalBlocks
        // Only valid if the data is large enough to contain L1 + L2 + BTS
        if (levelData.size < btsExpected) return null

        val words = IntArray(totalBlocks)
        for (i in 0 until totalBlocks) {
            val offset = layer2Start + i * 2
            val lo = levelData[offset].toInt() and 0xFF
            val hi = levelData[offset + 1].toInt() and 0xFF
            words[i] = (hi shl 8) or lo
        }
        return words
    }

    // ─── Layer 3 FX ──────────────────────────────────────────────────

    /**
     * Layer 3 tilemap entry: tile index (0-255) + draw method (flip/palette).
     */
    data class L3TilemapEntry(val tile: Int, val hFlip: Boolean, val vFlip: Boolean, val palette: Int)

    /**
     * Read the Layer 3 tilemap for a given fxType.
     * The pointer table at ROM PC 0x1ABF0, indexed by fxType, gives a 16-bit
     * pointer in bank $8A to the tilemap data. Each entry is 2 bytes:
     * [tile_index, draw_method] where draw_method bit 6=hFlip, bit 7=vFlip,
     * bits 2-4=palette.
     */
    fun readLayer3Tilemap(fxType: Int): List<L3TilemapEntry>? {
        if (fxType < 0 || fxType >= 0x30) return null
        val tableBase = RomConstants.L3_POINTER_TABLE_PC
        if (tableBase + fxType + 1 >= romData.size) return null
        val lo = romData[romStartOffset + tableBase + fxType].toInt() and 0xFF
        val hi = romData[romStartOffset + tableBase + fxType + 1].toInt() and 0xFF
        val snesAddr = 0x8A0000 or (hi shl 8) or lo
        val pc = snesToPc(snesAddr)
        val dataSize = RomConstants.L3_TILEMAP_SIZE * 2
        if (pc + dataSize > romData.size) return null

        return List(RomConstants.L3_TILEMAP_SIZE) { i ->
            val tile = romData[pc + i * 2].toInt() and 0xFF
            val dm = romData[pc + i * 2 + 1].toInt() and 0xFF
            L3TilemapEntry(
                tile = tile,
                hFlip = (dm and 0x40) != 0,
                vFlip = (dm and 0x80) != 0,
                palette = (dm shr 2) and 0x07
            )
        }
    }

    /**
     * Decode a single 2bpp tile from ROM. Returns 64 pixel values (0-3), row-major.
     */
    private fun decode2bppTile(pc: Int): IntArray {
        val pixels = IntArray(64)
        for (row in 0 until 8) {
            val off = pc + row * 2
            if (off + 1 >= romData.size) break
            val bp0 = romData[off].toInt() and 0xFF
            val bp1 = romData[off + 1].toInt() and 0xFF
            for (col in 0 until 8) {
                val bit = 7 - col
                pixels[row * 8 + col] = ((bp0 shr bit) and 1) or (((bp1 shr bit) and 1) shl 1)
            }
        }
        return pixels
    }

    /**
     * Render a Layer 3 image for a given fxType.
     * Returns an ARGB pixel array (width × height) where width=256, height=264
     * (32 tiles × 8px = 256, 33 tiles × 8px = 264).
     * Color 0 is transparent; colors 1-3 use the provided 3-color palette.
     * If palette is null, a default white gradient is used.
     */
    fun renderLayer3Image(fxType: Int, palette: IntArray? = null): IntArray? {
        val tilemap = readLayer3Tilemap(fxType) ?: return null
        val width = RomConstants.L3_TILEMAP_COLS * 8   // 256
        val height = RomConstants.L3_TILEMAP_ROWS * 8  // 264

        // Load base 2bpp tiles (256 tiles at D3200)
        val baseTiles = Array(256) { decode2bppTile(romStartOffset + RomConstants.L3_BASE_GFX_PC + it * 16) }

        // Apply fxType-specific replacement tiles (overwrite first 4)
        val replacementAddr = RomConstants.L3_REPLACEMENT_GFX[fxType]
        if (replacementAddr != null) {
            for (t in 0 until 4) {
                baseTiles[t] = decode2bppTile(romStartOffset + replacementAddr + t * 16)
            }
        }

        // Default palette: transparent + 3 levels of white
        val pal = palette ?: intArrayOf(0x00000000, 0x40FFFFFF.toInt(), 0x60FFFFFF, 0x80FFFFFF.toInt())

        val pixels = IntArray(width * height)
        for (i in tilemap.indices) {
            val entry = tilemap[i]
            val tileX = (i % RomConstants.L3_TILEMAP_COLS) * 8
            val tileY = (i / RomConstants.L3_TILEMAP_COLS) * 8
            val tilePixels = baseTiles[entry.tile]

            for (py in 0 until 8) {
                for (px in 0 until 8) {
                    val srcX = if (entry.hFlip) 7 - px else px
                    val srcY = if (entry.vFlip) 7 - py else py
                    val colorIdx = tilePixels[srcY * 8 + srcX]
                    val color = pal[colorIdx]
                    if (color != 0) {
                        val destX = tileX + px
                        val destY = tileY + py
                        if (destX < width && destY < height) {
                            pixels[destY * width + destX] = color
                        }
                    }
                }
            }
        }
        return pixels
    }

    /**
     * Read the map station reveal data for a given area.
     * 256 bytes, each byte = 8 tiles' reveal flags (LSB first).
     */
    fun readMapStationData(area: Int): MapStationData {
        require(area in 0 until MinimapData.NUM_AREAS) { "Invalid area: $area" }
        val basePc = snesToPc(MinimapData.MAP_STATION_ADDRESSES[area])
        val revealed = BooleanArray(MinimapData.TILE_COUNT)

        for (i in 0 until MinimapData.MAP_STATION_DATA_SIZE) {
            val offset = basePc + i
            if (offset >= romData.size) break
            val byte = romData[offset].toInt() and 0xFF
            for (bit in 0 until 8) {
                val tileIdx = i * 8 + bit
                if (tileIdx < MinimapData.TILE_COUNT) {
                    revealed[tileIdx] = (byte and (1 shl bit)) != 0
                }
            }
        }
        return MapStationData(area, revealed)
    }

    /**
     * Write minimap tile data back into ROM bytes.
     * Returns a list of (pcOffset, byte) pairs for the changed bytes.
     */
    fun writeMinimapTiles(data: MinimapData): List<Pair<Int, Byte>> {
        val basePc = snesToPc(MinimapData.AREA_MAP_ADDRESSES[data.area])
        val patches = mutableListOf<Pair<Int, Byte>>()

        for (y in 0 until MinimapData.MAP_HEIGHT) {
            for (x in 0 until MinimapData.MAP_WIDTH) {
                val halfBase = if (x < 32) basePc else basePc + 0x800
                val localX = x % 32
                val offset = halfBase + ((y + 1) * 32 + localX) * 2
                val word = data.getTile(x, y)
                patches.add(offset to (word and 0xFF).toByte())
                patches.add((offset + 1) to ((word shr 8) and 0xFF).toByte())
            }
        }
        return patches
    }

    /**
     * Write map station reveal data back into ROM bytes.
     */
    fun writeMapStationData(data: MapStationData): List<Pair<Int, Byte>> {
        val basePc = snesToPc(MinimapData.MAP_STATION_ADDRESSES[data.area])
        val patches = mutableListOf<Pair<Int, Byte>>()

        for (i in 0 until MinimapData.MAP_STATION_DATA_SIZE) {
            var byte = 0
            for (bit in 0 until 8) {
                val tileIdx = i * 8 + bit
                if (tileIdx < MinimapData.TILE_COUNT && data.revealed[tileIdx]) {
                    byte = byte or (1 shl bit)
                }
            }
            patches.add((basePc + i) to byte.toByte())
        }
        return patches
    }

    companion object {
        // ─── Item PLM catalog ──────────────────────────────────────
        data class ItemDef(val name: String, val shortLabel: String, val chozoId: Int, val visibleId: Int, val hiddenId: Int)

        val ITEM_DEFS = listOf(
            //                               chozoId  visibleId hiddenId
            ItemDef("Energy Tank",   "ET", 0xEF2B, 0xEED7, 0xEF7F),
            ItemDef("Missile",       "Mi", 0xEF2F, 0xEEDB, 0xEF83),
            ItemDef("Super Missile", "Su", 0xEF33, 0xEEDF, 0xEF87),
            ItemDef("Power Bomb",    "PB", 0xEF37, 0xEEE3, 0xEF8B),
            ItemDef("Bomb",          "Bo", 0xEF3B, 0xEEE7, 0xEF8F),
            ItemDef("Charge Beam",   "Ch", 0xEF3F, 0xEEEB, 0xEF93),
            ItemDef("Ice Beam",      "Ic", 0xEF43, 0xEEEF, 0xEF97),
            ItemDef("Hi-Jump Boots", "HJ", 0xEF47, 0xEEF3, 0xEF9B),
            ItemDef("Speed Booster", "Sp", 0xEF4B, 0xEEF7, 0xEF9F),
            ItemDef("Wave Beam",     "Wa", 0xEF4F, 0xEEFB, 0xEFA3),
            ItemDef("Spazer",        "Sz", 0xEF53, 0xEEFF, 0xEFA7),
            ItemDef("Spring Ball",   "SB", 0xEF57, 0xEF03, 0xEFAB),
            ItemDef("Varia Suit",    "Va", 0xEF5B, 0xEF07, 0xEFAF),
            ItemDef("Gravity Suit",  "Gr", 0xEF5F, 0xEF0B, 0xEFB3),
            ItemDef("X-Ray Scope",   "XR", 0xEF63, 0xEF0F, 0xEFB7),
            ItemDef("Plasma Beam",   "Pl", 0xEF67, 0xEF13, 0xEFBB),
            ItemDef("Grapple Beam",  "Gp", 0xEF6B, 0xEF17, 0xEFBF),
            ItemDef("Space Jump",    "SJ", 0xEF6F, 0xEF1B, 0xEFC3),
            ItemDef("Screw Attack",  "SA", 0xEF73, 0xEF1F, 0xEFC7),
            ItemDef("Morph Ball",    "MB", 0xEF77, 0xEF23, 0xEFCB),
            ItemDef("Reserve Tank",  "RT", 0xEF7B, 0xEF27, 0xEFCF),
        )

        private val plmToItemName: Map<Int, String> = buildMap {
            for (item in ITEM_DEFS) {
                put(item.chozoId,  "${item.name} (Chozo)")
                put(item.visibleId, "${item.name} (Visible)")
                put(item.hiddenId, "${item.name} (Hidden)")
            }
        }

        fun itemNameForPlm(plmId: Int): String? = plmToItemName[plmId]
        fun isItemPlm(plmId: Int): Boolean = plmId in plmToItemName

        private val EXPANSION_ITEM_NAMES = setOf("Energy Tank", "Missile", "Super Missile", "Power Bomb")

        private val upgradeItemPlmIds: Set<Int> = buildSet {
            for (item in ITEM_DEFS) {
                if (item.name !in EXPANSION_ITEM_NAMES) {
                    add(item.chozoId); add(item.visibleId); add(item.hiddenId)
                }
            }
        }

        /**
         * Upgrade items (Bombs through Reserve Tank) use instruction $8764 to dynamically
         * load graphics into one of 4 CRE VRAM slots (metatiles 0x8E-0x95). The slot
         * counter at $7E:1C2D cycles 0→2→4→6 via AND #$0006, so only 4 unique upgrade
         * item graphics can coexist per room. The 5th overwrites the 1st.
         *
         * Expansion items (ETank, Missile, Super, PBomb) use hardcoded CRE metatiles
         * (0x4A-0x51) and do NOT consume these slots.
         */
        fun isUpgradeItemPlm(plmId: Int): Boolean = plmId in upgradeItemPlmIds

        // ─── Station / special PLM catalog ──────────────────────────
        data class StationPlmDef(val name: String, val shortLabel: String, val plmId: Int, val defaultParam: Int)

        val STATION_PLMS = listOf(
            StationPlmDef("Save Point",            "Sv", 0xB76F, 0x8000),
            StationPlmDef("Energy Refill",          "ER", 0xB6DF, 0x0000),
            StationPlmDef("Missile Refill",         "MR", 0xB6EB, 0x0000),
            StationPlmDef("Mapping Station",        "Mp", 0xB6D3, 0x0000),
            StationPlmDef("Elevator Base",          "El", 0xB70B, 0x0000),
        )

        // ─── Gate PLM catalog ───────────────────────────────────────
        data class GatePlmDef(val name: String, val plmId: Int, val param: Int)

        val GATE_PLMS = listOf(
            GatePlmDef("Gate: Blue (left)",    0xC836, 0x00),
            GatePlmDef("Gate: Blue (right)",   0xC836, 0x02),
            GatePlmDef("Gate: Pink (left)",    0xC836, 0x04),
            GatePlmDef("Gate: Pink (right)",   0xC836, 0x06),
            GatePlmDef("Gate: Green (left)",   0xC836, 0x08),
            GatePlmDef("Gate: Green (right)",  0xC836, 0x0A),
            GatePlmDef("Gate: Yellow (left)",  0xC836, 0x0C),
            GatePlmDef("Gate: Yellow (right)", 0xC836, 0x0E),
            GatePlmDef("Gate Connector",       0xC82A, 0x8000),
        )

        fun stationNameForPlm(plmId: Int): String? =
            STATION_PLMS.find { it.plmId == plmId }?.name

        fun gateNameForPlm(plmId: Int, param: Int): String? {
            if (plmId == 0xC836) return GATE_PLMS.find { it.param == (param and 0xFF) }?.name
            if (plmId == 0xC82A) return "Gate Connector"
            return null
        }

        fun isStationPlm(plmId: Int): Boolean = STATION_PLMS.any { it.plmId == plmId }
        fun isGatePlm(plmId: Int): Boolean = plmId == 0xC836 || plmId == 0xC82A
        fun isDoorCapPlm(plmId: Int): Boolean = doorCapColor(plmId) != null
        fun isScrollPlm(plmId: Int): Boolean =
            plmId == 0xB703 || plmId == 0xB63B || plmId == 0xB647 ||
            plmId == 0xB63F || plmId == 0xB643

        // ─── Door Cap PLM catalog ─────────────────────────────────
        data class DoorCapDef(val name: String, val color: String, val direction: String, val plmId: Int)

        val DOOR_CAP_PLMS = listOf(
            DoorCapDef("Blue Left",    "Blue",   "Left",  0xC8A2),
            DoorCapDef("Blue Right",   "Blue",   "Right", 0xC8A8),
            DoorCapDef("Blue Up",      "Blue",   "Up",    0xC8AE),
            DoorCapDef("Blue Down",    "Blue",   "Down",  0xC8B4),
            DoorCapDef("Red Left",     "Red",    "Left",  0xC88A),
            DoorCapDef("Red Right",    "Red",    "Right", 0xC890),
            DoorCapDef("Red Up",       "Red",    "Up",    0xC896),
            DoorCapDef("Red Down",     "Red",    "Down",  0xC89C),
            DoorCapDef("Green Left",   "Green",  "Left",  0xC872),
            DoorCapDef("Green Right",  "Green",  "Right", 0xC878),
            DoorCapDef("Green Up",     "Green",  "Up",    0xC87E),
            DoorCapDef("Green Down",   "Green",  "Down",  0xC884),
            DoorCapDef("Yellow Left",  "Yellow", "Left",  0xC85A),
            DoorCapDef("Yellow Right", "Yellow", "Right", 0xC860),
            DoorCapDef("Yellow Up",    "Yellow", "Up",    0xC866),
            DoorCapDef("Yellow Down",  "Yellow", "Down",  0xC86C),
            DoorCapDef("Grey Left",    "Grey",   "Left",  0xC842),
            DoorCapDef("Grey Right",   "Grey",   "Right", 0xC848),
            DoorCapDef("Grey Up",      "Grey",   "Up",    0xC84E),
            DoorCapDef("Grey Down",    "Grey",   "Down",  0xC854),
        )

        fun doorCapDefFor(plmId: Int): DoorCapDef? = DOOR_CAP_PLMS.find { it.plmId == plmId }

        fun doorCapNameForPlm(plmId: Int): String? = doorCapDefFor(plmId)?.let { "Door Cap: ${it.name}" }

        fun plmDisplayName(plmId: Int, param: Int = 0): String {
            itemNameForPlm(plmId)?.let { return it }
            stationNameForPlm(plmId)?.let { return it }
            gateNameForPlm(plmId, param)?.let { return it }
            doorCapNameForPlm(plmId)?.let { return it }
            scrollPlmName(plmId)?.let { return it }
            return "PLM 0x${plmId.toString(16).uppercase().padStart(4, '0')}"
        }

        fun scrollPlmName(plmId: Int): String? = when (plmId) {
            0xB703 -> "Scroll trigger"
            0xB63B -> "Scroll ext →"
            0xB647 -> "Scroll ext ↑"
            0xB63F -> "Scroll ext ←"
            0xB643 -> "Scroll ext ↓"
            else -> null
        }

        fun decodeScrollCommands(parser: RomParser, paramPtr: Int, roomWidth: Int): List<Triple<Int, Int, Int>> {
            val snesAddr = BANK_ROOM_DATA or paramPtr
            val pc = parser.snesToPc(snesAddr)
            val commands = mutableListOf<Triple<Int, Int, Int>>()
            var offset = 0
            while (offset < 256 && pc + offset + 1 < parser.romData.size) {
                val screenIdx = parser.romData[pc + offset].toInt() and 0xFF
                if (screenIdx >= 0x80) break
                val scrollVal = parser.romData[pc + offset + 1].toInt() and 0xFF
                commands.add(Triple(screenIdx, screenIdx % roomWidth, scrollVal))
                offset += 2
            }
            return commands
        }

        fun scrollValueLabel(v: Int): String = when (v) {
            0x00 -> "Red (lock)"
            0x01 -> "Blue (unlock)"
            0x02 -> "Green (gate)"
            else -> "?$v"
        }

        fun formatScrollCommand(screenIdx: Int, scrollVal: Int, roomWidth: Int): String {
            val col = screenIdx % roomWidth
            val row = screenIdx / roomWidth
            return "Screen ($col,$row) → ${scrollValueLabel(scrollVal)}"
        }

        // Door cap colors matching the in-game door shield appearance
        val DOOR_CAP_BLUE   = 0xFF3880D0.toInt()   // Blue: opens with any weapon
        val DOOR_CAP_RED    = 0xFFD05050.toInt()    // Red/Pink: 5 missiles or 1 super
        val DOOR_CAP_GREEN  = 0xFF40C048.toInt()    // Green: super missile
        val DOOR_CAP_YELLOW = 0xFFD8C830.toInt()    // Yellow/Orange: power bomb
        val DOOR_CAP_GREY   = 0xFF808088.toInt()    // Grey: boss/event dependent
        
        /**
         * Returns ARGB door cap color for a PLM type ID, or null if not a door cap.
         * Based on Kejardon's PLM documentation:
         *   $C842-$C855 = Grey (boss/event)   facing L/R/U/D
         *   $C85A-$C86D = Orange/Yellow (PB)   facing L/R/U/D
         *   $C872-$C885 = Green (super)        facing L/R/U/D
         *   $C88A-$C89D = Red (missile)        facing L/R/U/D
         *   $C8A2-$C8B5 = Blue (beam) opening  facing L/R/U/D
         *   $C8BA-$C8CD = Blue (beam) closing   facing L/R/U/D
         */
        fun doorCapColor(plmId: Int): Int? = when (plmId) {
            in 0xC842..0xC859 -> DOOR_CAP_GREY      // Grey door caps (boss/event)
            in 0xC85A..0xC871 -> DOOR_CAP_YELLOW     // Orange/Yellow (power bomb)
            in 0xC872..0xC889 -> DOOR_CAP_GREEN      // Green (super missile)
            in 0xC88A..0xC8A1 -> DOOR_CAP_RED        // Red (5 missiles)
            in 0xC8A2..0xC8B9 -> DOOR_CAP_BLUE       // Blue (beam/anything)
            else -> null
        }

        enum class DoorCapDir { LEFT, RIGHT, DOWN, UP }

        /**
         * Each color group spans 24 bytes with 4 directions in order:
         * left (+0), right (+6), down (+12), up (+18).
         */
        fun doorCapDirection(plmId: Int): DoorCapDir? {
            val bases = intArrayOf(0xC842, 0xC85A, 0xC872, 0xC88A, 0xC8A2)
            for (base in bases) {
                val off = plmId - base
                if (off in 0..23) return when (off / 6) {
                    0 -> DoorCapDir.LEFT
                    1 -> DoorCapDir.RIGHT
                    2 -> DoorCapDir.DOWN
                    3 -> DoorCapDir.UP
                    else -> null
                }
            }
            return null
        }

        fun doorCapIsHorizontal(plmId: Int): Boolean {
            val dir = doorCapDirection(plmId) ?: return false
            return dir == DoorCapDir.DOWN || dir == DoorCapDir.UP
        }

        fun doorCapDisplayName(plmId: Int): String? {
            val color = when {
                plmId in 0xC842..0xC859 -> "Grey"
                plmId in 0xC85A..0xC871 -> "Yellow"
                plmId in 0xC872..0xC889 -> "Green"
                plmId in 0xC88A..0xC8A1 -> "Red"
                plmId in 0xC8A2..0xC8B9 -> "Blue"
                else -> return null
            }
            val dir = when (doorCapDirection(plmId)) {
                DoorCapDir.LEFT -> "Left"
                DoorCapDir.RIGHT -> "Right"
                DoorCapDir.DOWN -> "Down"
                DoorCapDir.UP -> "Up"
                else -> "?"
            }
            return "$color $dir Door"
        }
        
        /**
         * Comprehensive enemy name map by species ID (bank $A0 pointer).
         * Sourced from SMILE editor data + community English names.
         */
        private val ENEMY_NAMES = mapOf(
            // ── Projectiles / Effects ──
            0xCEBF to "Boyon",
            0xCEFF to "Stoke",
            0xCF3F to "Kame",
            0xCF7F to "Tatori",
            0xCFBF to "Puyo",
            0xCFFF to "Cacatac",
            0xD03F to "Owtch",
            0xD07F to "Samus' Ship",
            0xD0BF to "Samus' Ship (firing)",
            // ── Chozo / Statues ──
            0xD0FF to "Mellow",
            0xD13F to "Mella",
            0xD17F to "Menu",
            0xD1BF to "Multiviola",
            0xD1FF to "Polyp",
            // ── Rinka / Norfair fire enemies ──
            0xD23F to "Rinka",
            0xD27F to "Reo",
            0xD2BF to "Squeept",
            0xD2FF to "Geruta",
            0xD33F to "Holtz",
            0xD37F to "Oum (baby)",
            0xD3BF to "Choot",
            // ── Rippers ──
            0xD3FF to "Ripper II",
            0xD43F to "Ripper II (variant)",
            0xD47F to "Ripper",
            // ── Dragons / Shutters ──
            0xD4BF to "Dragon",
            0xD4FF to "Door Shutter",
            0xD53F to "Door Shutter 2",
            0xD57F to "Door Shutter 2 (variant)",
            0xD5BF to "Door Shutter 2 (variant 2)",
            0xD5FF to "Door Shutter 2 (variant 3)",
            // ── Common enemies ──
            0xD63F to "Waver",
            0xD67F to "Metaree",
            0xD6BF to "Fireflea",
            0xD6FF to "Skultera",
            0xD73F to "Elevator",
            0xD75F to "Zoomer (grey)",
            0xD77F to "Sciser",
            0xD7BF to "Oum",
            0xD7DF to "Ripper II",
            0xD7FF to "Tripper",
            0xD83F to "Skree (variant)",
            0xD87F to "Reo",
            0xD89F to "Waver",
            0xD8BF to "Reo (variant)",
            0xD91F to "Geemer",
            0xD93F to "Sidehopper",
            0xD8FF to "Metroid (modified)",
            0xD97F to "Dessgeega",
            0xD99F to "Dessgeega (big)",
            0xD9BF to "Sidehopper (big)",
            0xD9DF to "Sidehopper (big, variant)",
            0xD9FF to "Sidehopper (invincible)",
            // ── Flyers / Misc ──
            0xDA3F to "Dessgeega",
            0xDA7F to "Zoa",
            0xDABF to "Viola",
            0xDB3F to "Bang",
            0xDB4F to "Ship",
            0xDB7F to "Skree (Norfair)",
            0xDBBF to "Yard",
            0xDBCF to "Kago",
            0xDBFF to "Reflec",
            // ── Wall-crawlers ──
            0xDC3F to "Geemer (horizontal)",
            0xDC7F to "Zeela",
            0xDCBF to "Sova",
            0xDCFF to "Zoomer",
            0xDD3F to "Sova (grey)",
            0xDD7F to "Metroid",
            // ── Bosses ──
            0xDDBF to "Crocomire",
            0xDE3F to "Draygon (body)",
            0xDE7F to "Draygon (eye)",
            0xDEBF to "Draygon (tail)",
            0xDEFF to "Draygon (arms)",
            0xDF3F to "Spore Spawn",
            // ── Boulder / Kzan ──
            0xDFBF to "Boulder",
            0xDFFF to "Kzan",
            0xE03F to "Kihunter",
            0xE07F to "Hibashi",
            0xE0BF to "Puromi",
            0xE0FF to "Mini Kraid (belly spike)",
            // ── Ridley / Puyo ──
            0xE13F to "Ceres Ridley",
            0xE17F to "Ridley",
            0xE1BF to "Puyo",
            0xE27F to "Zebetite",
            // ── Kraid (species verified from room $A1:9EB5) ──
            0xE2BF to "Kraid",
            0xE2FF to "Kraid (upper body)",
            0xE33F to "Kraid (belly spike 1)",
            0xE37F to "Kraid (belly spike 2)",
            0xE3BF to "Kraid (belly spike 3)",
            0xE3FF to "Kraid (flying claw 1)",
            0xE43F to "Kraid (flying claw 2)",
            0xE47F to "Kraid (flying claw 3)",
            // ── Phantoon ──
            0xE4BF to "Phantoon",
            0xE4FF to "Phantoon (piece)",
            0xE53F to "Phantoon (piece 2)",
            0xE57F to "Phantoon (piece 3)",
            // ── Friendly / Misc ──
            0xE5BF to "Etecoon",
            0xE5FF to "Dachora",
            0xE63F to "Evir",
            0xE67F to "Zero",
            0xE6BF to "Eye",
            0xE6FF to "Fune",
            0xE73F to "Namihe",
            0xE7BF to "Yapping Maw",
            0xE7FF to "Kago",
            // ── Norfair / Maridia ──
            0xE83F to "Lavaman",
            0xE87F to "Beetom",
            0xE8BF to "Puu",
            0xE8FF to "Work Robot",
            0xE93F to "Work Robot (broken)",
            0xE97F to "Zeb Spawner (pipe)",
            0xE9BF to "Alcoon",
            0xE9FF to "Atomic",
            0xEA3F to "Spa (variant)",
            0xEA7F to "Koma",
            // ── Kihunter variants (SMILE: HACHI = bee) ──
            0xEABF to "Kihunter (green)",
            0xEAFF to "Kihunter (green, wings)",
            0xEB3F to "Kihunter (red)",
            0xEB7F to "Kihunter (red, wings)",
            0xEBBF to "Kihunter (gold)",
            0xEBFF to "Kihunter (gold, wings)",
            // ── Mother Brain ──
            0xEC3F to "Mother Brain (phase 1)",
            0xEC7F to "Mother Brain (phase 2)",
            // ── Special / Remains ──
            0xED3F to "Torizo Corpse",
            0xED7F to "Hopper (remains)",
            0xEEBF to "Big Metroid",
            0xEEFF to "Torizo",
            0xEF3F to "Torizo (orbs)",
            0xEF7F to "Torizo (gold)",
            0xEFBF to "Torizo (gold orbs)",
            // ── Spawners / Misc ──
            0xF07F to "Shaktool",
            0xF0BF to "Shattered Glass",
            0xF193 to "Zeb",
            0xF1D3 to "Zebbo",
            0xF213 to "Gamet",
            0xF253 to "Geega",
            0xF293 to "Botwoon",
            // ── Space Pirates (BATTA variants by area) ──
            0xF353 to "Space Pirate",
            0xF413 to "Space Pirate (Norfair)",
            0xF453 to "Space Pirate (Maridia)",
            0xF493 to "Space Pirate (Tourian)",
            0xF593 to "Space Pirate Mk.II (Norfair)",
            0xF613 to "Space Pirate Mk.II (Tourian)",
            0xF653 to "Space Pirate Mk.III",
            0xF693 to "Space Pirate Mk.III (Brinstar)",
            0xF6D3 to "Space Pirate Mk.III (Norfair)",
            0xF713 to "Space Pirate Mk.III (Norfair alt)",
            0xF753 to "Space Pirate Mk.III (Maridia)",
            0xF793 to "Space Pirate Mk.III (Tourian)",

            // ── Torizo sub-parts / Wrecked Ship / Misc ──
            0xEDBF to "Torizo Corpse (helper)",
            0xEDFF to "Torizo Corpse (ceiling)",
            0xEE3F to "Wrecked Ship Robot",
            0xEE7F to "Wrecked Ship Robot (piece)",
            0xEFFF to "Golden Torizo (piece)",
            0xF0FF to "Draygon (hand)",
            0xF2D3 to "Tourian Escape Pirate",
            0xF313 to "Tourian Escape Pirate (runner)",

            // Ceres-only species (shared IDs like E0BF/E0FF/E17F/E27F
            // are already mapped above as their main-game names)
            0xE1FF to "Ceres Smoke/Steam",
            0xE23F to "Ceres Door FX",
        )

        fun enemyName(id: Int): String = ENEMY_NAMES[id] ?: "${id.toString(16).uppercase().padStart(4, '0')}"

        val ENEMY_CATALOG: List<Pair<Int, String>> by lazy {
            ENEMY_NAMES.entries.sortedBy { it.value }.map { it.key to it.value }
        }

        fun loadRom(filePath: String): RomParser {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("ROM file not found: $filePath")
            }
            val romData = file.readBytes()
            if (romData.size != ROM_SIZE && romData.size != ROM_SIZE_WITH_HEADER) {
                throw IllegalArgumentException("Invalid ROM size: ${romData.size} bytes")
            }
            return RomParser(romData)
        }
    }
}
