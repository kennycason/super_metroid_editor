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
        val songSets = MusicBankTransitions.editedSongSets(project)
        // Validate every bank before writing any transfer chains into the export copy.
        val parser = RomParser(romData)
        val banks = songSets.associateWith { MusicBankResolver.resolve(parser, project, it) }.toMutableMap()
        val transitions = MusicBankTransitions.inspect(parser, project, banks)
        transitions.requireSafe()
        for ((songSet, restorations) in transitions.instrumentRestorations) {
            val bank = banks[songSet] ?: MusicBankResolver.resolve(parser, project, songSet)
            val writes = MusicTransferChainBudget.mergeBlocks(bank.blocks, restorations).associate { it.destAddr to it.data }
            banks[songSet] = bank.copy(writes = writes)
            onLog("[EXPORT] Bank ${MusicInstrumentBanks.key(songSet)} will restore instrument slot(s) ${restorations.keys.joinToString { ((it - 0x6C00) / 6).toString() }} on load.")
        }
        // Validate the concrete reset uploads too, rather than assuming a plan makes the bank safe.
        val restored = MusicBankTransitions.inspect(parser, project, banks)
        restored.requireSafe()
        check(restored.instrumentRestorations.isEmpty()) { "Instrument bank restoration plan is incomplete" }
        val chains = banks.filterValues { it.writes.isNotEmpty() }.map { (songSet, bank) ->
            prepareTransferChain(parser, songSet, bank.writes, bank.hasNativePayload)
        }
        val allocator = freeSpaceAllocator ?: RomFreeSpaceAllocator(romData, parser::snesToPc, parser::pcToSnes, guardBytes = 2)
        val transferBanks = musicTransferBanks(parser, romData)
        // Reserve the entire plan before the first ROM write. A reset chain that cannot
        // fit must not leave a partly relocated set of music pointers in the caller's copy.
        val allocations = chains.map { chain ->
            allocator.reserve(chain.bytes.size, transferBanks,
                "songSet ${MusicInstrumentBanks.key(chain.songSet)} transfer chain", alignment = 0x10)
                ?: error("not enough contiguous free ROM space for ${chain.bytes.size}-byte relocated SPC transfer chain")
        }
        for ((chain, allocation) in chains.zip(allocations)) {
            allocator.write(allocation, chain.bytes)
            writeRomU24(romData, chain.pointerEntryPc, allocation.snesAddress)
            onLog("[EXPORT] Relocated bank ${MusicInstrumentBanks.key(chain.songSet)} " +
                "\$${chain.originalPointer.toString(16).uppercase().padStart(6, '0')} -> " +
                "\$${allocation.snesAddress.toString(16).uppercase().padStart(6, '0')} (${chain.bytes.size} bytes)")
        }
        return banks.values.sumOf { it.patchedTracks }
    }

    private data class TransferChain(val songSet: Int, val pointerEntryPc: Int, val originalPointer: Int, val bytes: ByteArray)

    private fun prepareTransferChain(
        parser: RomParser,
        songSet: Int,
        spcWrites: Map<Int, ByteArray>,
        hasNativePayload: Boolean = false,
    ): TransferChain {
        require(spcWrites.isNotEmpty()) { "music export has no SPC writes for songSet 0x${songSet.toString(16)}" }

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
        return TransferChain(songSet, pointerEntryPc, originalPointer, mergedChain)
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
