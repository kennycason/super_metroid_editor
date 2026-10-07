package com.supermetroid.editor.rom

data class DoorScrollWrite(
    val scrollValue: Int,
    val addressLowByte: Int,
) {
    val screenIndex: Int get() = addressLowByte - SCROLLS_LOW_BYTE
}

data class DoorAsmScrollAnalysis(
    val writes: List<DoorScrollWrite>,
    val terminatedWithRts: Boolean,
    val decodedCompletely: Boolean,
    val hasOtherEffects: Boolean,
) {
    /** Safe to replace with a generated scroll-only routine without dropping behavior. */
    val isPureScrollRoutine: Boolean
        get() = writes.isNotEmpty() && terminatedWithRts && decodedCompletely && !hasOtherEffects
}

private const val MOTHER_BRAIN_ROOM_ID = 0xDD58
private const val NMI_FLAG_BG2_ENEMY_VRAM_TRANSFER_ADDR = 0x0E1E
private const val ENEMY_BG2_TILEMAP_SIZE_ADDR = 0x179A
private const val SCROLLS_ADDRESS = 0x7ECD20
private const val SCROLLS_LOW_BYTE = 0x20
private const val LAST_SUPPORTED_SCROLL_LOW_BYTE = 0x7F

fun shouldClearEnemyBg2TransferOnDoor(sourceRoomId: Int, destRoomId: Int): Boolean =
    sourceRoomId == MOTHER_BRAIN_ROOM_ID && destRoomId != MOTHER_BRAIN_ROOM_ID

fun parseDoorScrollWrites(
    romParser: RomParser,
    entryCode: Int,
    maxBytes: Int = 80,
): List<DoorScrollWrite> = analyzeDoorScrollAsm(romParser, entryCode, maxBytes).writes

/**
 * Decode the deliberately small door-ASM subset used by vanilla scroll routines.
 *
 * Door ASM begins with 16-bit A/X. The decoder tracks REP/SEP, retains an immediate
 * accumulator value across repeated stores, expands 16-bit stores into two scroll
 * bytes, and fails closed on control flow or opcodes whose effects it cannot prove.
 */
fun analyzeDoorScrollAsm(
    romParser: RomParser,
    entryCode: Int,
    maxBytes: Int = 80,
): DoorAsmScrollAnalysis {
    if (entryCode == 0 || entryCode == 0xFFFF) {
        return DoorAsmScrollAnalysis(emptyList(), false, true, false)
    }
    val pc = runCatching { romParser.snesToPc(0x8F0000 or entryCode) }.getOrNull()
        ?: return DoorAsmScrollAnalysis(emptyList(), false, false, false)
    val writes = mutableListOf<DoorScrollWrite>()
    val accumulatorWidths = mutableListOf<Boolean>()
    var accumulator8Bit = false
    var accumulatorValue: Int? = null
    var accumulatorUsedForScroll = false
    var hasOtherEffects = false
    var decodedCompletely = false
    var terminatedWithRts = false
    var i = 0

    fun finishAccumulator() {
        if (accumulatorValue != null && !accumulatorUsedForScroll) hasOtherEffects = true
    }

    while (i < maxBytes) {
        val b = romParser.readByteAt(pc + i)
        when (b) {
            0x08 -> { // PHP
                accumulatorWidths.add(accumulator8Bit)
                i++
            }
            0x28 -> { // PLP
                accumulator8Bit = accumulatorWidths.removeLastOrNull() ?: run {
                    hasOtherEffects = true
                    accumulator8Bit
                }
                i++
            }
            0xC2, 0xE2 -> { // REP/SEP #imm8
                if (i + 1 >= maxBytes) break
                val mask = romParser.readByteAt(pc + i + 1)
                if ((mask and 0x20) != 0) accumulator8Bit = b == 0xE2
                i += 2
            }
            0xA9 -> { // LDA #immM
                finishAccumulator()
                val operandBytes = if (accumulator8Bit) 1 else 2
                if (i + operandBytes >= maxBytes) break
                accumulatorValue = romParser.readByteAt(pc + i + 1) or
                    (if (operandBytes == 2) romParser.readByteAt(pc + i + 2) shl 8 else 0)
                accumulatorUsedForScroll = false
                i += 1 + operandBytes
            }
            0x8F -> { // STA long
                if (i + 3 >= maxBytes) break
                val address = romParser.readByteAt(pc + i + 1) or
                    (romParser.readByteAt(pc + i + 2) shl 8) or
                    (romParser.readByteAt(pc + i + 3) shl 16)
                val value = accumulatorValue
                if (value != null && address in SCROLLS_ADDRESS..(SCROLLS_ADDRESS + 0x5F)) {
                    val lowByte = address and 0xFF
                    if (lowByte in SCROLLS_LOW_BYTE..LAST_SUPPORTED_SCROLL_LOW_BYTE) {
                        writes.add(DoorScrollWrite(value and 0xFF, lowByte))
                        if (!accumulator8Bit && lowByte < LAST_SUPPORTED_SCROLL_LOW_BYTE) {
                            writes.add(DoorScrollWrite((value shr 8) and 0xFF, lowByte + 1))
                        }
                        accumulatorUsedForScroll = true
                    } else {
                        hasOtherEffects = true
                    }
                } else {
                    hasOtherEffects = true
                }
                i += 4
            }
            0x60 -> { // RTS: the engine enters door ASM with a synthetic JSR return.
                finishAccumulator()
                terminatedWithRts = true
                decodedCompletely = true
                break
            }
            0x6B -> { // RTL is not a valid return for Execute_Door_ASM.
                finishAccumulator()
                hasOtherEffects = true
                decodedCompletely = true
                break
            }
            0x20, 0x4C -> { // JSR/JMP absolute: control flow is outside this straight-line proof.
                hasOtherEffects = true
                i += 3
                break
            }
            0x22, 0x5C -> { // JSL/JML absolute long.
                hasOtherEffects = true
                i += 4
                break
            }
            else -> {
                // Never byte-scan through an unknown instruction's operands. That was the
                // old parser's source of false stores and unsafe routine replacement.
                hasOtherEffects = true
                break
            }
        }
    }
    if (accumulatorWidths.isNotEmpty()) hasOtherEffects = true
    return DoorAsmScrollAnalysis(
        writes = writes,
        terminatedWithRts = terminatedWithRts,
        decodedCompletely = decodedCompletely,
        hasOtherEffects = hasOtherEffects,
    )
}

fun buildDoorScrollAsm(scrollWrites: List<DoorScrollWrite>): ByteArray {
    val bytes = mutableListOf(0x08, 0xE2, 0x20) // PHP; SEP #$20
    for (write in scrollWrites) {
        bytes.add(0xA9) // LDA #imm8
        bytes.add(write.scrollValue and 0xFF)
        bytes.add(0x8F) // STA long
        bytes.add(write.addressLowByte and 0xFF)
        bytes.add(0xCD)
        bytes.add(0x7E)
    }
    bytes.add(0x28) // PLP
    bytes.add(0x60) // RTS; Execute_Door_ASM uses a synthetic JSR-style return.
    return bytes.map { it.toByte() }.toByteArray()
}

fun buildDoorAsmClearingEnemyBg2Transfer(scrollWrites: List<DoorScrollWrite>): ByteArray {
    val bytes = mutableListOf<Int>()

    fun addWordAddress(address: Int) {
        bytes.add(address and 0xFF)
        bytes.add((address shr 8) and 0xFF)
        bytes.add(0x7E)
    }

    bytes.add(0x08) // PHP
    bytes.add(0xC2) // REP #$20
    bytes.add(0x20)
    bytes.add(0xA9) // LDA #$0000
    bytes.add(0x00)
    bytes.add(0x00)
    bytes.add(0x8F) // STA $7E0E1E
    addWordAddress(NMI_FLAG_BG2_ENEMY_VRAM_TRANSFER_ADDR)
    bytes.add(0x8F) // STA $7E179A
    addWordAddress(ENEMY_BG2_TILEMAP_SIZE_ADDR)
    bytes.add(0xE2) // SEP #$20
    bytes.add(0x20)
    for (write in scrollWrites) {
        bytes.add(0xA9) // LDA #imm8
        bytes.add(write.scrollValue and 0xFF)
        bytes.add(0x8F) // STA long
        bytes.add(write.addressLowByte and 0xFF)
        bytes.add(0xCD)
        bytes.add(0x7E)
    }
    bytes.add(0x28) // PLP
    bytes.add(0x60) // RTS
    return bytes.map { it.toByte() }.toByteArray()
}

data class DoorDependentBgTransfer(
    val doorDefPtr: Int,
    val srcAddr: Int,
    val vramDst: Int,
    val size: Int,
)

private fun bgDataCommandSize(command: Int): Int? = when (command) {
    0x0000 -> 2
    0x0002, 0x0008 -> 9
    0x0004 -> 7
    0x0006, 0x000A, 0x000C -> 2
    0x000E -> 11
    else -> null
}

fun readDoorEntryAtDoorDefPtr(
    romParser: RomParser,
    doorDefPtr: Int,
): RomParser.DoorEntry? {
    if (doorDefPtr < 0x8000 || doorDefPtr == 0xFFFF) return null
    val pc = runCatching { romParser.snesToPc(RomConstants.BANK_FX or doorDefPtr) }.getOrNull() ?: return null
    val destRoom = romParser.readUInt16At(pc)
    if (destRoom < 0x8000 || destRoom == 0xFFFF) return null
    return RomParser.DoorEntry(
        destRoomPtr = destRoom,
        bitflag = romParser.readUInt16At(pc + 2),
        doorCapCode = romParser.readUInt16At(pc + 4),
        screenX = romParser.readByteAt(pc + 6),
        screenY = romParser.readByteAt(pc + 7),
        distFromDoor = romParser.readUInt16At(pc + 8),
        entryCode = romParser.readUInt16At(pc + 10),
        doorDefPtr = doorDefPtr,
    )
}

fun parseDoorDependentBgTransfers(
    romParser: RomParser,
    bgDataPtr: Int,
    maxCommands: Int = 64,
): List<DoorDependentBgTransfer> {
    if (bgDataPtr == 0 || bgDataPtr == 0xFFFF) return emptyList()
    val pc = runCatching { romParser.snesToPc(RomConstants.BANK_ROOM_DATA or bgDataPtr) }.getOrNull()
        ?: return emptyList()
    val transfers = mutableListOf<DoorDependentBgTransfer>()
    var offset = pc
    repeat(maxCommands) {
        val command = romParser.readUInt16At(offset)
        if (command == 0x0000) return transfers
        val size = bgDataCommandSize(command) ?: return transfers
        if (command == 0x000E) {
            val srcAddr = romParser.readByteAt(offset + 4) or
                (romParser.readByteAt(offset + 5) shl 8) or
                (romParser.readByteAt(offset + 6) shl 16)
            transfers.add(
                DoorDependentBgTransfer(
                    doorDefPtr = romParser.readUInt16At(offset + 2),
                    srcAddr = srcAddr,
                    vramDst = romParser.readUInt16At(offset + 7),
                    size = romParser.readUInt16At(offset + 9),
                )
            )
        }
        offset += size
    }
    return transfers
}

private fun sameDoorDependentBgEntrance(a: RomParser.DoorEntry, b: RomParser.DoorEntry): Boolean =
    a.destRoomPtr == b.destRoomPtr &&
        (a.bitflag and 0xFF00) == (b.bitflag and 0xFF00) &&
        a.doorCapCode == b.doorCapCode &&
        a.screenX == b.screenX &&
        a.screenY == b.screenY

fun findMatchingDoorDependentBgTransfer(
    romParser: RomParser,
    bgDataPtr: Int,
    newDoor: RomParser.DoorEntry,
): DoorDependentBgTransfer? {
    val transfers = parseDoorDependentBgTransfers(romParser, bgDataPtr)
    if (transfers.any { it.doorDefPtr == newDoor.doorDefPtr }) return null
    return transfers.firstOrNull { transfer ->
        val templateDoor = readDoorEntryAtDoorDefPtr(romParser, transfer.doorDefPtr) ?: return@firstOrNull false
        sameDoorDependentBgEntrance(templateDoor, newDoor)
    }
}

fun buildBgDataWithClonedDoorDependentTransfer(
    romParser: RomParser,
    bgDataPtr: Int,
    newDoorDefPtr: Int,
    template: DoorDependentBgTransfer,
    maxCommands: Int = 64,
): ByteArray? {
    if (bgDataPtr == 0 || bgDataPtr == 0xFFFF) return null
    val pc = runCatching { romParser.snesToPc(RomConstants.BANK_ROOM_DATA or bgDataPtr) }.getOrNull()
        ?: return null
    var offset = pc
    repeat(maxCommands) {
        val command = romParser.readUInt16At(offset)
        val size = bgDataCommandSize(command) ?: return null
        if (command == 0x0000) {
            val bytes = mutableListOf<Int>()
            for (i in pc until offset) bytes.add(romParser.readByteAt(i))
            bytes.addAll(
                listOf(
                    0x0E, 0x00,
                    newDoorDefPtr and 0xFF, (newDoorDefPtr shr 8) and 0xFF,
                    template.srcAddr and 0xFF, (template.srcAddr shr 8) and 0xFF, (template.srcAddr shr 16) and 0xFF,
                    template.vramDst and 0xFF, (template.vramDst shr 8) and 0xFF,
                    template.size and 0xFF, (template.size shr 8) and 0xFF,
                    0x00, 0x00,
                )
            )
            return bytes.map { it.toByte() }.toByteArray()
        }
        offset += size
    }
    return null
}
