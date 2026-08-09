package com.supermetroid.editor.rom

data class DoorScrollWrite(
    val scrollValue: Int,
    val addressLowByte: Int,
)

private const val MOTHER_BRAIN_ROOM_ID = 0xDD58
private const val NMI_FLAG_BG2_ENEMY_VRAM_TRANSFER_ADDR = 0x0E1E
private const val ENEMY_BG2_TILEMAP_SIZE_ADDR = 0x179A

fun shouldClearEnemyBg2TransferOnDoor(sourceRoomId: Int, destRoomId: Int): Boolean =
    sourceRoomId == MOTHER_BRAIN_ROOM_ID && destRoomId != MOTHER_BRAIN_ROOM_ID

fun parseDoorScrollWrites(
    romParser: RomParser,
    entryCode: Int,
    maxBytes: Int = 80,
): List<DoorScrollWrite> {
    if (entryCode == 0 || entryCode == 0xFFFF) return emptyList()
    val pc = runCatching { romParser.snesToPc(0x8F0000 or entryCode) }.getOrNull() ?: return emptyList()
    val writes = mutableListOf<DoorScrollWrite>()
    var i = 0
    while (i < maxBytes) {
        val b = romParser.readByteAt(pc + i)
        if (b == 0x60 || b == 0x6B) break
        if (b == 0xA9 && i + 5 < maxBytes) {
            val value = romParser.readByteAt(pc + i + 1)
            val next = romParser.readByteAt(pc + i + 2)
            if (next == 0x8F) {
                val lo = romParser.readByteAt(pc + i + 3)
                val hi = romParser.readByteAt(pc + i + 4)
                val bank = romParser.readByteAt(pc + i + 5)
                if (hi == 0xCD && bank == 0x7E && lo in 0x20..0x7F) {
                    writes.add(DoorScrollWrite(value, lo))
                }
                i += 6
                continue
            }
        }
        if ((b == 0x08 || b == 0x28) && i + 1 <= maxBytes) {
            i++
            continue
        }
        if ((b == 0xE2 || b == 0xC2) && i + 1 < maxBytes) {
            i += 2
            continue
        }
        i++
    }
    return writes
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
