package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicNativePayloadEdit
import com.supermetroid.editor.rom.SpcData

internal object MusicTransferChainBudget {
    const val MAX_SINGLE_LOROM_BANK_BYTES = 0x8000

    data class NativePayloadAssessment(
        val originalChainBytes: Int,
        val payloadChainBytes: Int,
        val relocatedChainBytes: Int,
        val maxChainBytes: Int = MAX_SINGLE_LOROM_BANK_BYTES
    ) {
        val canExportToRom: Boolean get() = relocatedChainBytes <= maxChainBytes
        val overByBytes: Int get() = (relocatedChainBytes - maxChainBytes).coerceAtLeast(0)
    }

    fun assessNativePayloadRomExport(
        originalBlocks: List<SpcData.TransferBlock>,
        payload: MusicNativePayloadEdit,
        targetPlayIndex: Int
    ): NativePayloadAssessment {
        val originalChainBytes = serializedTransferChainSize(originalBlocks)
        val payloadWrites = compactedNativePayloadWrites(payload, targetPlayIndex)
        val payloadChainBytes = serializedTransferChainSize(
            payloadWrites.map { (addr, data) -> SpcData.TransferBlock(addr, data) }
        )
        return NativePayloadAssessment(
            originalChainBytes = originalChainBytes,
            payloadChainBytes = payloadChainBytes,
            relocatedChainBytes = serializedTransferChainSize(mergeBlocks(originalBlocks, payloadWrites))
        )
    }

    fun serializedTransferChainSize(blocks: List<SpcData.TransferBlock>): Int =
        blocks.sumOf { 4 + it.data.size } + 2

    fun compactedNativePayloadWrites(
        payload: MusicNativePayloadEdit,
        targetPlayIndex: Int
    ): Map<Int, ByteArray> {
        val sourcePlayIndex = payload.sourcePlayIndex.takeIf { it >= 0 } ?: targetPlayIndex
        val maxPlayIndex = (0x10000 - MusicSequenceBudget.SONG_TABLE_BASE) / 2
        require(sourcePlayIndex in 0 until maxPlayIndex && targetPlayIndex in 0 until maxPlayIndex) { "Invalid native music play index" }
        val sourceEntry = MusicSequenceBudget.SONG_TABLE_BASE + sourcePlayIndex * 2
        val targetEntry = MusicSequenceBudget.SONG_TABLE_BASE + targetPlayIndex * 2
        val bytesByAddr = java.util.TreeMap<Int, Int>()
        val sourceBytes = mutableMapOf<Int, Int>()
        for (block in MusicEditConversion.toTransferBlocks(payload)) {
            val dest = block.destAddr
            for (i in block.data.indices) {
                val addr = dest + i
                val value = block.data[i].toInt() and 0xFF
                if (addr == sourceEntry || addr == sourceEntry + 1) {
                    sourceBytes[addr - sourceEntry] = value
                } else {
                    bytesByAddr[addr] = value
                }
            }
        }
        require(sourceBytes.size == 2) { "Native music payload is missing its source song-table pointer" }
        sourceBytes.forEach { (offset, value) -> bytesByAddr[targetEntry + offset] = value }
        return SpcData.packSpcBytesToWrites(bytesByAddr)
    }

    /** Same byte-level union used by preflight and ROM export; later writes win. */
    fun mergeBlocks(original: List<SpcData.TransferBlock>, writes: Map<Int, ByteArray>): List<SpcData.TransferBlock> {
        val bytes = java.util.TreeMap<Int, Int>()
        for (block in original + writes.map { (at, data) -> SpcData.TransferBlock(at, data) }) {
            require(block.destAddr in 0 until 0x10000 && block.data.size <= 0x10000 - block.destAddr) { "Music block exceeds SPC RAM" }
            block.data.forEachIndexed { i, value -> bytes[block.destAddr + i] = value.toInt() and 255 }
        }
        return SpcData.packSpcBytesToWrites(bytes).map { (at, data) -> SpcData.TransferBlock(at, data) }
    }
}
