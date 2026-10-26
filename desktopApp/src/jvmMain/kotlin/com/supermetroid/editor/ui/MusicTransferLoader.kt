package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Native continuation building block, deliberately not installed by MusicRomExporter yet.
 * A verified loader reads $FFFF + a ROM u24 as a continuation, without ending the APU
 * receive session. Current project/export readers must be integrated before enabling it.
 */
internal object MusicTransferLoader {
    const val PATCH_SNES = 0x8080B1
    private const val LOADER_START = 0x808024
    private const val LOADER_END = 0x808111
    private const val ORIGINAL_SHA256 = "992a752c868ebd31545a8d098f989565b8014a96f1270edf45beb1f17058c048"

    // Independently assembled by the runtime fixture from transfer-continuation.asm.
    // Entry: M=0, X=0, DB:Y=next size, 8-bit kick already on the caller's stack.
    // Return: A=size, Y advanced as native IncYTwice, DB/$02 track ROM crossings.
    val code: ByteArray get() = hex(
        "B90000 C9FFFF F011 C8 F004 C8 F001 6B E602 D401 AB AB A00080 6B " +
            "B90200 48 E220 B90400 8502 48 AB C220 7A 80D5")

    fun requireOriginal(parser: RomParser) {
        val rom = parser.getRomData()
        val start = parser.snesToPc(LOADER_START)
        val end = parser.snesToPc(LOADER_END)
        require(start >= 0 && end <= rom.size) { "Music continuation loader is outside ROM bounds" }
        val hash = MessageDigest.getInstance("SHA-256").digest(rom.copyOfRange(start, end))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == ORIGINAL_SHA256) { "Music continuation requires the verified native CPU upload routine; loader bytes differ" }
    }

    /** Concrete writes only. The caller must reserve code/prefixes before applying any bytes. */
    fun patchWrites(parser: RomParser, codePointer: Int): Map<Int, ByteArray> {
        requireOriginal(parser)
        requireRomPointer(codePointer)
        val body = code
        val offset = codePointer and 0xFFFF
        val pc = parser.snesToPc(codePointer)
        require(offset <= 0x10000 - body.size && pc <= parser.getRomData().size - body.size) {
            "Music continuation code must fit in one ROM bank"
        }
        val protectedStart = parser.snesToPc(LOADER_START)
        val protectedEnd = parser.snesToPc(LOADER_END)
        require(pc >= protectedEnd || pc + body.size <= protectedStart) { "Music continuation code overlaps its loader" }
        return linkedMapOf(pc to body, parser.snesToPc(PATCH_SNES) to byteArrayOf(
            0x22, codePointer.toByte(), (codePointer ushr 8).toByte(), (codePointer ushr 16).toByte(), 0xEA.toByte(), 0xEA.toByte()))
    }

    /** Instrument-only scope: no engine, DIR, BRR, scratch-RAM or inferred unused-slot writes. */
    fun instrumentPrefix(entries: Map<Int, ByteArray>, continuationPointer: Int): ByteArray {
        require(entries.isNotEmpty()) { "An instrument prefix needs at least one entry" }
        requireRomPointer(continuationPointer)
        require((continuationPointer and 0xFFFF) != 0xFFFF) { "Music continuation size word cannot straddle a ROM bank" }
        for ((at, data) in entries) require(at in 0x6C00 until 0x6CFC && (at - 0x6C00) % 6 == 0 && data.size == 6) {
            "A music restoration prefix can contain only complete native instrument entries"
        }
        val blocks = MusicTransferChainBudget.mergeBlocks(emptyList(), entries)
        val out = ByteArrayOutputStream()
        fun word(value: Int) { out.write(value and 255); out.write((value ushr 8) and 255) }
        for (block in blocks) { word(block.data.size); word(block.destAddr); out.write(block.data) }
        word(0xFFFF)
        out.write(continuationPointer and 255)
        out.write((continuationPointer ushr 8) and 255)
        out.write((continuationPointer ushr 16) and 255)
        return out.toByteArray()
    }

    private fun requireRomPointer(pointer: Int) {
        require((pointer ushr 16) in 0x80..0xFF && (pointer and 0xFFFF) >= 0x8000) { "Invalid music continuation ROM pointer" }
    }
    private fun hex(value: String) = value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
