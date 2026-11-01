package com.supermetroid.editor.rom

/** Recognized expanded CPU music table. No loader opcodes or queue behavior are replaced. */
object SpcMusicBankTable {
    const val TABLE_BYTES = 255
    const val HEADER_BYTES = 8
    const val SIZE = HEADER_BYTES + TABLE_BYTES
    private val magic = "SMEDMUS1".encodeToByteArray()
    data class Table(val pointer: Int, val pc: Int, val expanded: Boolean)
    fun read(parser: RomParser): Table {
        val rom = parser.getRomData()
        val at = parser.snesToPc(0x808F72)
        require(at >= 0 && at + 12 <= rom.size && (rom[at].toInt() and 255) == 0xBF &&
            rom[at + 4] == 0x85.toByte() && rom[at + 5] == 0.toByte() &&
            rom[at + 6] == 0xBF.toByte() && rom[at + 10] == 0x85.toByte() && rom[at + 11] == 1.toByte()) {
            "Music bank creation requires the supported native music queue"
        }
        fun readPointer(i: Int) = (rom[i].toInt() and 255) or ((rom[i + 1].toInt() and 255) shl 8) or ((rom[i + 2].toInt() and 255) shl 16)
        val pointer = readPointer(at + 1)
        require(readPointer(at + 7) == pointer + 1 && pointer ushr 16 in 0x80..0xFF && pointer and 0xFFFF >= 0x8000) { "Invalid music pointer table" }
        val pc = parser.snesToPc(pointer)
        require(pc >= 0 && pc + 0x4B <= rom.size) { "Music pointer table exceeds ROM" }
        val expanded = pc >= HEADER_BYTES && pc + TABLE_BYTES <= rom.size && rom.copyOfRange(pc - HEADER_BYTES, pc).contentEquals(magic)
        require(!expanded || (pointer and 0xFFFF) + TABLE_BYTES <= 0x10000) { "Expanded music table crosses its ROM bank" }
        return Table(pointer, pc, expanded)
    }
    fun songSets(parser: RomParser): List<Int> {
        val table = runCatching { read(parser) }.getOrNull()
        if (table?.expanded != true) return SpcData.TRANSFER_SONG_SETS
        val rom = parser.getRomData()
        return (0..0xFC step 3).filter { at -> (0..2).any { rom[table.pc + at + it] != 0.toByte() } }
    }
    fun data(entries: Map<Int, Int>): ByteArray = ByteArray(SIZE).also { bytes ->
        magic.copyInto(bytes)
        for ((set, pointer) in entries) {
            require(set in 0..0xFC && set % 3 == 0 && pointer ushr 16 in 0x80..0xFF && pointer and 0xFFFF >= 0x8000)
            repeat(3) { bytes[HEADER_BYTES + set + it] = (pointer ushr (it * 8)).toByte() }
        }
    }
    fun patchWrites(parser: RomParser, tablePointer: Int): Map<Int, ByteArray> {
        val previous = read(parser)
        require(previous.expanded || previous.pointer == 0x8FE7E1) { "Cannot extend an unknown custom music table" }
        require(tablePointer and 0xFFFF >= 0x8000 && (tablePointer and 0xFFFF) + TABLE_BYTES <= 0x10000)
        fun pointer(value: Int) = ByteArray(3) { (value ushr (it * 8)).toByte() }
        return mapOf(parser.snesToPc(0x808F73) to pointer(tablePointer), parser.snesToPc(0x808F79) to pointer(tablePointer + 1))
    }
}
