package com.supermetroid.editor.rom

/** The complete bank-$8F library-background command set dispatched by $82:E5C7. */
enum class LibraryBackgroundCommandType(val opcode: Int, val byteCount: Int) {
    TERMINATE(0x0000, 2),
    TRANSFER(0x0002, 9),
    DECOMPRESS(0x0004, 7),
    CLEAR_FX_TILEMAP(0x0006, 2),
    TRANSFER_AND_SET_BG3_BASE(0x0008, 9),
    CLEAR_BG2_TILEMAP(0x000A, 2),
    CLEAR_KRAID_LAYER2(0x000C, 2),
    DOOR_DEPENDENT_TRANSFER(0x000E, 11),
    ;

    companion object {
        fun fromOpcode(opcode: Int): LibraryBackgroundCommandType? =
            entries.firstOrNull { it.opcode == opcode }
    }
}

data class LibraryBackgroundCommand(
    val type: LibraryBackgroundCommandType,
    val offset: Int,
    val sourceAddress: Int? = null,
    val vramDestination: Int? = null,
    val size: Int? = null,
    val wramDestination: Int? = null,
    val doorDefPtr: Int? = null,
)

data class LibraryBackgroundProgram(
    val pointer: Int,
    val commands: List<LibraryBackgroundCommand>,
    val byteCount: Int,
) {
    val isComplete: Boolean get() = commands.lastOrNull()?.type == LibraryBackgroundCommandType.TERMINATE
}

data class BackgroundTilemap(
    /** Row-major SNES tilemap words, normalized from the engine's 32×32 screen blocks. */
    val words: IntArray,
    val widthTiles: Int,
    val heightTiles: Int,
)

/**
 * Decode one library-background program without executing it.
 *
 * Unknown commands, bank overflow, missing terminators, and out-of-ROM reads fail closed.
 */
fun RomParser.parseLibraryBackground(
    bgDataPtr: Int,
    maxCommands: Int = 128,
): LibraryBackgroundProgram? {
    if (bgDataPtr !in 0x8000..0xFFFF) return null
    val start = runCatching { snesToPc(RomConstants.BANK_ROOM_DATA or bgDataPtr) }.getOrNull()
        ?: return null
    val bankEnd = runCatching { snesToPc(RomConstants.BANK_ROOM_DATA or 0xFFFF) + 1 }.getOrNull()
        ?: return null
    var offset = start
    val commands = mutableListOf<LibraryBackgroundCommand>()
    repeat(maxCommands) {
        if (offset < start || offset + 2 > bankEnd || offset + 2 > romData.size) return null
        val commandOffset = offset - start
        val type = LibraryBackgroundCommandType.fromOpcode(readUInt16At(offset)) ?: return null
        if (offset + type.byteCount > bankEnd || offset + type.byteCount > romData.size) return null
        val command = when (type) {
            LibraryBackgroundCommandType.TRANSFER,
            LibraryBackgroundCommandType.TRANSFER_AND_SET_BG3_BASE,
            -> LibraryBackgroundCommand(
                type = type,
                offset = commandOffset,
                sourceAddress = readU24(romData, offset + 2),
                vramDestination = readUInt16At(offset + 5),
                size = readUInt16At(offset + 7),
            )

            LibraryBackgroundCommandType.DECOMPRESS -> LibraryBackgroundCommand(
                type = type,
                offset = commandOffset,
                sourceAddress = readU24(romData, offset + 2),
                wramDestination = readUInt16At(offset + 5),
            )

            LibraryBackgroundCommandType.DOOR_DEPENDENT_TRANSFER -> LibraryBackgroundCommand(
                type = type,
                offset = commandOffset,
                doorDefPtr = readUInt16At(offset + 2),
                sourceAddress = readU24(romData, offset + 4),
                vramDestination = readUInt16At(offset + 7),
                size = readUInt16At(offset + 9),
            )

            else -> LibraryBackgroundCommand(type = type, offset = commandOffset)
        }
        commands += command
        offset += type.byteCount
        if (type == LibraryBackgroundCommandType.TERMINATE) {
            return LibraryBackgroundProgram(bgDataPtr, commands, offset - start)
        }
    }
    return null
}

