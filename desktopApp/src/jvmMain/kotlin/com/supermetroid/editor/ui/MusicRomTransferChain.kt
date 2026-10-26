package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

/** Strict export-side source inventory. Unlike a display parser, it must not accept a partial chain. */
internal data class MusicRomTransferChain(
    val romRange: IntRange,
    val blocks: List<SpcData.TransferBlock>,
    val entryAddress: Int,
) {
    fun covers(writes: Map<Int, ByteArray>): Boolean {
        val loaded = BooleanArray(0x10000)
        for (block in blocks) loaded.fill(true, block.destAddr, block.destAddr + block.data.size)
        return writes.all { (at, data) ->
            at in 0 until 0x10000 && data.size <= 0x10000 - at && data.indices.all { loaded[at + it] }
        }
    }

    companion object {
        fun read(parser: RomParser, pointer: Int): MusicRomTransferChain {
            val rom = parser.getRomData()
            require((pointer ushr 16) in 0x80..0xFF && (pointer and 0xFFFF) >= 0x8000) { "Invalid music transfer pointer" }
            val start = parser.snesToPc(pointer)
            val blocks = mutableListOf<SpcData.TransferBlock>()
            var pos = start
            fun word(at: Int) = (rom[at].toInt() and 255) or ((rom[at + 1].toInt() and 255) shl 8)
            while (true) {
                require(pos >= 0 && pos <= rom.size - 4) { "Music transfer chain is missing its complete terminator" }
                val size = word(pos)
                val dest = word(pos + 2)
                if (size == 0) return MusicRomTransferChain(start until pos + 4, blocks, dest)
                require(size <= 0x10000 - dest) { "Music transfer block exceeds SPC RAM" }
                require(size <= rom.size - pos - 4) { "Music transfer block exceeds ROM bounds" }
                blocks += SpcData.TransferBlock(dest, rom.copyOfRange(pos + 4, pos + 4 + size))
                pos += 4 + size
            }
        }
    }
}
