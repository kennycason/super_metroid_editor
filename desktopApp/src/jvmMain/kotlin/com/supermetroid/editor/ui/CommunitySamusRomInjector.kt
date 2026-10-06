package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.CommunitySamusSourceCodec
import com.supermetroid.editor.rom.RomResourceClaim
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlan
import com.supermetroid.editor.rom.RomWritePlanException

/** Applies a validated, project-embedded community Samus IPS as one owned ROM transaction. */
internal object CommunitySamusRomInjector {
    private const val OWNER = "graphics:community-samus"

    fun expandedBase(
        originalRom: ByteArray,
        headerSize: Int,
        inputRomHash: String,
        loaded: CommunitySamusSourceCodec.LoadedInjectionArtifact,
    ): ByteArray {
        val artifact = loaded.metadata
        val bodySize = originalRom.size - headerSize
        if (bodySize != artifact.baseRomSize) {
            throw RomWritePlanException(
                "Community Samus injection requires a ${artifact.baseRomSize}-byte base-ROM body; found $bodySize bytes"
            )
        }
        if (!inputRomHash.equals(artifact.baseRomSha256, ignoreCase = true)) {
            throw RomWritePlanException(
                "Community Samus injection requires base ROM SHA-256 ${artifact.baseRomSha256}; found $inputRomHash"
            )
        }
        return originalRom.copyOf(headerSize + artifact.outputRomSize)
    }

    /** Returns the number of original IPS records represented by the planned writes. */
    fun apply(
        writePlan: RomWritePlan,
        loaded: CommunitySamusSourceCodec.LoadedInjectionArtifact,
    ): Int {
        val bodySize = writePlan.romData.size - writePlan.headerSize
        if (bodySize != loaded.metadata.outputRomSize) {
            throw RomWritePlanException(
                "Community Samus injector expected a ${loaded.metadata.outputRomSize}-byte output ROM; found $bodySize bytes"
            )
        }

        val patched = writePlan.romData.copyOf()
        for (write in loaded.writes) {
            val start = writePlan.headerSize + write.offset.toInt()
            for (index in write.bytes.indices) patched[start + index] = write.bytes[index].toByte()
        }

        // IPS records may contain padding bytes that already match the clean
        // base ROM. They are transport details rather than Samus mutations, so
        // do not claim them against otherwise-compatible patches.
        val ranges = changedRanges(writePlan.romData, patched, writePlan.headerSize)
        for ((index, range) in ranges.withIndex()) {
            val bytes = (range.first..range.last).map { pc ->
                patched[writePlan.headerSize + pc].toInt() and 0xFF
            }
            writePlan.add(
                owner = OWNER,
                label = "Community Samus data ${index + 1}/${ranges.size}",
                offset = range.first,
                bytes = bytes,
                kind = RomWriteKind.GRAPHICS,
            )
            writePlan.claimResource(
                RomResourceClaim(
                    owner = OWNER,
                    namespace = "rom_region",
                    start = range.first,
                    endInclusive = range.last,
                    label = "Community Samus owned ROM data",
                )
            )
        }
        return loaded.writes.size
    }

    private fun changedRanges(before: ByteArray, after: ByteArray, headerSize: Int): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var pc = 0
        val bodySize = before.size - headerSize
        while (pc < bodySize) {
            if (before[headerSize + pc] == after[headerSize + pc]) {
                pc++
                continue
            }
            val start = pc
            do {
                pc++
            } while (pc < bodySize && before[headerSize + pc] != after[headerSize + pc])
            result += start until pc
        }
        return result
    }
}
