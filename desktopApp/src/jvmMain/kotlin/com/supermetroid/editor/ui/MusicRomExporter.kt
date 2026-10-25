package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

/** Handles SPC music edit export — writing relocated transfer chains to the ROM. */
internal class MusicRomExporter(
    private val project: SmEditProject,
    private val onLog: (String) -> Unit,
) {
    internal fun applyMusicEditsToRom(
        romData: ByteArray,
        freeSpaceAllocator: RomFreeSpaceAllocator? = null,
    ): Int {
        val songSets = (project.musicEdits.values.map { it.songSet } +
            project.musicInstrumentBanks.keys.map { it.toInt(16) }).distinct().sorted()
        // Validate every bank before writing any transfer chains into the export copy.
        val banks = songSets.associateWith { MusicBankResolver.resolve(RomParser(romData), project, it) }
        var patched = 0
        for ((songSet, bank) in banks) {
            if (MusicInstrumentBanks.instruments(project, songSet).keys.any { it < 24 }) {
                onLog("[EXPORT] Warning: common instrument slots (0–23) in bank ${MusicInstrumentBanks.key(songSet)} are not reset by vanilla bank loads; edits can carry into later banks.")
            }
            if (bank.writes.isEmpty()) continue
            val chainBytes = writeRelocatedSongSetTransferChain(
                romData, songSet, bank.writes, bank.hasNativePayload, freeSpaceAllocator)
            patched += bank.patchedTracks
            onLog("[EXPORT] SongSet 0x${songSet.toString(16).padStart(2, '0')}: ${bank.patchedTracks} music/bank edit(s), $chainBytes-byte transfer chain")
        }
        return patched
    }

    private fun writeRelocatedSongSetTransferChain(
        romData: ByteArray,
        songSet: Int,
        spcWrites: Map<Int, ByteArray>,
        hasNativePayload: Boolean = false,
        freeSpaceAllocator: RomFreeSpaceAllocator? = null,
    ): Int {
        require(spcWrites.isNotEmpty()) { "music export has no SPC writes for songSet 0x${songSet.toString(16)}" }

        val parser = RomParser(romData)
        val pointerEntryPc = SpcData.findSongSetPointerEntryPc(parser, songSet)
        require(pointerEntryPc >= 0) {
            "songSet 0x${songSet.toString(16)} has no writable transfer pointer table entry"
        }

        val originalPointer = SpcData.readSongSetPointer(parser, songSet)
        val originalBlocks = SpcData.findSongSetTransferData(parser, songSet)
        require(originalBlocks.isNotEmpty()) {
            "songSet 0x${songSet.toString(16)} has no transfer blocks to relocate"
        }

        // Merge at the SPC RAM byte level so arrangements not being edited retain
        // their instruments, samples, and sequence data.
        val mergedBlocks = MusicTransferChainBudget.mergeBlocks(originalBlocks, spcWrites)
        val mergedChain = serializeTransferChain(mergedBlocks)
        val maxBank = MusicTransferChainBudget.MAX_SINGLE_LOROM_BANK_BYTES
        require(mergedChain.size <= maxBank) {
            val fallbackRisk = if (hasNativePayload) {
                " A payload-only fallback was refused because it could break other arrangements in the same song set."
            } else {
                ""
            }
            "SongSet 0x${songSet.toString(16).padStart(2, '0')} needs ${mergedChain.size} bytes, " +
                "which exceeds the $maxBank-byte single-bank limit.$fallbackRisk"
        }
        val relocatedChain = mergedChain
        val allocation = (freeSpaceAllocator ?: RomFreeSpaceAllocator(
            romData = romData,
            snesToPc = parser::snesToPc,
            pcToSnes = parser::pcToSnes,
            guardBytes = 2,
        )).allocate(
            bytes = relocatedChain,
            banks = musicTransferBanks(parser, romData),
            label = "songSet 0x${songSet.toString(16).padStart(2, '0')} transfer chain",
            alignment = 0x10,
        ) ?: error("not enough contiguous free ROM space for ${relocatedChain.size}-byte relocated SPC transfer chain")
        val writePc = allocation.pcOffset

        val relocatedPointer = parser.pcToSnes(writePc)
        writeRomU24(romData, pointerEntryPc, relocatedPointer)

        onLog(
            "[EXPORT] Relocated songSet 0x${songSet.toString(16).padStart(2, '0')} transfer chain " +
                "\$${originalPointer.toString(16).uppercase().padStart(6, '0')} -> " +
                "\$${relocatedPointer.toString(16).uppercase().padStart(6, '0')} " +
                "(merged, ${relocatedChain.size} bytes)"
        )
        return relocatedChain.size
    }

    private fun serializeTransferChain(blocks: List<SpcData.TransferBlock>): ByteArray {
        val out = java.io.ByteArrayOutputStream(blocks.sumOf { 4 + it.data.size } + 2)
        for (block in blocks) {
            require(block.data.size <= 0xFFFF) {
                "transfer block at 0x${block.destAddr.toString(16)} is too large (${block.data.size} bytes)"
            }
            writeStreamU16(out, block.data.size)
            writeStreamU16(out, block.destAddr and 0xFFFF)
            out.write(block.data)
        }
        writeStreamU16(out, 0)
        return out.toByteArray()
    }

    private fun writeStreamU16(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeRomU24(romData: ByteArray, offset: Int, value: Int) {
        require(offset >= 0 && offset + 2 < romData.size) {
            "out-of-bounds ROM pointer write at 0x${offset.toString(16)}"
        }
        romData[offset] = (value and 0xFF).toByte()
        romData[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        romData[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun musicTransferBanks(
        parser: RomParser,
        romData: ByteArray,
    ): List<Int> {
        val preferredBanks = listOf(0xB8, 0xCE, 0x85, 0x83, 0x89)
        val fallbackBanks = (0x80..0xFF).filterNot { it in preferredBanks }
        return (preferredBanks + fallbackBanks).filter { bank ->
            val bankStart = runCatching { parser.snesToPc((bank shl 16) or 0x8000) }.getOrNull()
            val bankEndExclusive = runCatching { parser.snesToPc((bank shl 16) or 0xFFFF) + 1 }.getOrNull()
            bankStart != null && bankEndExclusive != null && bankStart >= 0 &&
                bankEndExclusive <= romData.size && bankStart < bankEndExclusive
        }
    }
}
