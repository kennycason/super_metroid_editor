package com.supermetroid.editor.rom

/** Strict physical ROM stream reader, shared by export, preview and source-byte mapping. */
object SpcTransferStream {
    const val PATCH_SNES = 0x8080B1
    private const val LOADER_START = 0x808024
    private const val LOADER_END = 0x808111
    private val original: ByteArray get() = hex(
        "2028806baf088080f00160088bc230a9ffff8f170600e220c210a9ff8f402100a400a50248abc230205980a900008f170600ab286008c230a900308f410600a9aabbcf402100f00daf4106003a8f410600d0ec80fee220a9cc802fb90000200381eba900800febb90000200381ebcf402100d0fa1ac2208f402100e220cad0e6cf402100d0fa6903f0fc48c220b90000200081aab900002000818f422100e220e00100a9002a8f412100697f688f402100daa20010caf016cf402100d0f7fa709ae2209c41219c42219c43212860e2209c41219c42219c4321fa2860c8f004c8f00160e602d401ababa0008060")
    val loaderCode: ByteArray get() = hex("b90000c9fffff011c8f004c8f0016be602d401ababa000806bb9020048e220b90400850248abc2207a80d5")

    data class SourceBlock(val destAddr: Int, val dataStartPc: Int, val data: ByteArray)
    data class Chain(val sourceBlocks: List<SourceBlock>, val romRanges: List<IntRange>, val entryAddress: Int) {
        val blocks: List<SpcData.TransferBlock> get() = sourceBlocks.map { SpcData.TransferBlock(it.destAddr, it.data) }
        fun covers(writes: Map<Int, ByteArray>): Boolean {
            val loaded = BooleanArray(65536)
            for (block in sourceBlocks) loaded.fill(true, block.destAddr, block.destAddr + block.data.size)
            return writes.all { (at, data) -> at in 0 until 65536 && data.size <= 65536 - at && data.indices.all { loaded[at + it] } }
        }
    }

    fun requireOriginalLoader(parser: RomParser) {
        val pc = parser.snesToPc(LOADER_START)
        val rom = parser.getRomData()
        require(pc >= 0 && pc <= rom.size - original.size && rom.copyOfRange(pc, pc + original.size).contentEquals(original)) {
            "Music continuation requires the verified native CPU upload routine; loader bytes differ"
        }
    }

    /** Recognize only our exact installed code and complete normalized native loader. */
    fun installedCodePointer(parser: RomParser): Int? {
        val rom = parser.getRomData()
        val patch = parser.snesToPc(PATCH_SNES)
        if (patch < 0 || patch > rom.size - 6) return null
        if (rom[patch] != 0x22.toByte() || rom[patch + 4] != 0xEA.toByte() || rom[patch + 5] != 0xEA.toByte()) return null
        val pointer = (rom[patch + 1].toInt() and 255) or ((rom[patch + 2].toInt() and 255) shl 8) or
            ((rom[patch + 3].toInt() and 255) shl 16)
        requirePointer(pointer)
        val codePc = parser.snesToPc(pointer)
        val start = parser.snesToPc(LOADER_START)
        val end = parser.snesToPc(LOADER_END)
        val body = loaderCode
        require((pointer and 65535) <= 65536 - body.size && codePc <= rom.size - body.size && start >= 0 && end <= rom.size &&
            (codePc >= end || codePc + body.size <= start)) { "Invalid installed music continuation code location" }
        val normalized = rom.copyOfRange(start, end)
        original.copyInto(normalized, PATCH_SNES - LOADER_START, PATCH_SNES - LOADER_START, PATCH_SNES - LOADER_START + 6)
        require(normalized.contentEquals(original) && rom.copyOfRange(codePc, codePc + body.size).contentEquals(body)) {
            "Installed music continuation loader bytes differ"
        }
        return pointer
    }

    fun requireSupportedLoader(parser: RomParser) {
        if (installedCodePointer(parser) == null) requireOriginalLoader(parser)
    }

    fun read(parser: RomParser, pointer: Int): Chain {
        requirePointer(pointer)
        val rom = parser.getRomData()
        val blocks = mutableListOf<SourceBlock>()
        val ranges = mutableListOf<IntRange>()
        var pos = parser.snesToPc(pointer)
        var hops = 0
        var verifiedContinuation = false
        fun word(at: Int) = (rom[at].toInt() and 255) or ((rom[at + 1].toInt() and 255) shl 8)
        fun consume(size: Int) {
            require(pos >= 0 && size <= rom.size - pos) { "Music transfer block exceeds ROM bounds" }
            val last = pos + size - 1
            require(ranges.none { pos <= it.last && last >= it.first }) { "Music transfer stream is cyclic or overlaps an earlier source span" }
            if (ranges.lastOrNull()?.last == pos - 1) ranges[ranges.lastIndex] = ranges.last().first..last
            else ranges += pos..last
        }
        while (true) {
            require(blocks.size < 4096) { "Music transfer stream has too many blocks" }
            require(pos >= 0 && pos <= rom.size - 4) { "Music transfer chain is missing its complete terminator" }
            require((parser.pcToSnes(pos) and 65535) != 65535) { "Music transfer size word straddles a ROM bank" }
            val size = word(pos)
            if (size == 0xFFFF) {
                if (!verifiedContinuation) {
                    require(installedCodePointer(parser) != null) { "Music continuation has no verified CPU loader" }
                    verifiedContinuation = true
                }
                require(++hops <= 64) { "Music transfer stream has too many continuations" }
                require((parser.pcToSnes(pos) and 65535) <= 0xFFFB) { "Music continuation marker straddles a ROM bank" }
                consume(5)
                val next = word(pos + 2) or ((rom[pos + 4].toInt() and 255) shl 16)
                requirePointer(next)
                pos = parser.snesToPc(next)
                continue
            }
            require((parser.pcToSnes(pos + 2) and 65535) != 65535) { "Music transfer destination word straddles a ROM bank" }
            val dest = word(pos + 2)
            if (size == 0) { consume(4); return Chain(blocks, ranges, dest) }
            require(size <= 65536 - dest) { "Music transfer block exceeds SPC RAM" }
            consume(4 + size)
            blocks += SourceBlock(dest, pos + 4, rom.copyOfRange(pos + 4, pos + 4 + size))
            pos += 4 + size
        }
    }

    private fun requirePointer(pointer: Int) {
        require((pointer ushr 16) in 0x80..0xFF && (pointer and 65535) >= 0x8000) { "Invalid music transfer ROM pointer" }
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
