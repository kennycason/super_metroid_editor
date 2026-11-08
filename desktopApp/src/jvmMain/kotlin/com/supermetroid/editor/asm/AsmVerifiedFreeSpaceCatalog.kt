package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomVerifiedFreeRange
import com.supermetroid.editor.rom.RomConstants
import java.io.File

/**
 * Converts the disassembly's explicit Freespace_* declarations into allocator
 * envelopes. A suggestive run of $FF/$00 bytes is never enough: the source must
 * name the range and publish its byte-count ceiling. The immutable build then
 * proves the leading erased-byte span; inherited overstatements are clamped at
 * the first non-erased byte rather than trusted.
 */
internal object AsmVerifiedFreeSpaceCatalog {
    private val declaration = Regex(
        "(?mi)^Freespace_Bank([0-9A-F]{2})_([0-9A-F]{4}):[^\\r\\n]*(?:\\r?\\n)" +
            "[ \\t]*;[ \\t]*\\$([0-9A-F]+)(?:[ \\t]+zero)?[ \\t]+bytes\\b",
    )

    fun load(
        originalDirectory: File,
        immutableRomBody: ByteArray,
        originalRomBodySize: Int,
        expandedFillByte: Int,
    ): List<RomVerifiedFreeRange> {
        require(originalRomBodySize in 0..immutableRomBody.size && originalRomBodySize % 0x8000 == 0)
        require(expandedFillByte in 0..0xFF)
        val ranges = mutableListOf<RomVerifiedFreeRange>()
        val sourceRoot = File(originalDirectory, "src")
        sourceRoot.walkTopDown().filter { it.isFile && it.extension.equals("asm", true) }.forEach { file ->
            declaration.findAll(file.readText()).forEach { match ->
                val bank = match.groupValues[1].toInt(16)
                val offset = match.groupValues[2].toInt(16)
                val length = match.groupValues[3].toInt(16)
                val address = (bank shl 16) or offset
                val start = requireNotNull(snesLoRomToPc(address)) {
                    "Invalid documented free-space address ${formatSnesAddress(address)}"
                }
                require(length > 0 && start + length <= minOf(originalRomBodySize, immutableRomBody.size)) {
                    "Documented free-space range ${match.value.lineSequence().first()} is outside the immutable ROM"
                }
                val fill = immutableRomBody[start].toInt() and 0xFF
                require(fill == 0x00 || fill == 0xFF) {
                    "Documented free-space range ${match.value.lineSequence().first()} does not begin with erased bytes"
                }
                // A few inherited disassembly comments overstate the free byte
                // count across a later split source file (bank $94 is the known
                // example). Trust the declaration as an upper bound only, and
                // prove the usable prefix byte-for-byte against immutable ROM.
                var endExclusive = start
                val declaredEnd = start + length
                while (endExclusive < declaredEnd &&
                    (immutableRomBody[endExclusive].toInt() and 0xFF) == fill) endExclusive++
                require(endExclusive > start) {
                    "Documented free-space range ${match.value.lineSequence().first()} has no verified erased prefix"
                }
                ranges += RomVerifiedFreeRange(
                    bank = bank,
                    pcStart = start,
                    pcEndExclusive = endExclusive,
                    fillByte = fill,
                    label = match.value.lineSequence().first().substringBefore(':').trim(),
                )
            }
        }
        // The disassembly describes the canonical 3 MiB game even when the
        // project was reopened from an already-expanded ROM. Everything above
        // that boundary is SMEDIT-managed expansion, not immutable source.
        var pc = minOf(originalRomBodySize, RomConstants.ROM_SIZE)
        while (pc < immutableRomBody.size) {
            val bank = 0x80 + pc / 0x8000
            val end = pc + 0x8000
            require(end <= immutableRomBody.size && (pc until end).all {
                (immutableRomBody[it].toInt() and 0xFF) == expandedFillByte
            }) { "Expanded bank ${patchHex(bank, 1)} does not match its managed fill byte" }
            ranges += RomVerifiedFreeRange(bank, pc, end, expandedFillByte, "Expanded bank ${patchHex(bank, 1)}")
            pc = end
        }
        return ranges.sortedBy(RomVerifiedFreeRange::pcStart).also { sorted ->
            require(sorted.zipWithNext().all { (a, b) -> a.pcEndExclusive <= b.pcStart }) {
                "Documented ASM free-space ranges overlap"
            }
        }
    }
}
