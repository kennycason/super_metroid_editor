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
        // Native payloads are exported without original blocks (the payload is a full
        // replacement and including both would double-count old/new sample regions).
        // So the relocated chain size is just the compacted payload writes chain size.
        return NativePayloadAssessment(
            originalChainBytes = originalChainBytes,
            payloadChainBytes = payloadChainBytes,
            relocatedChainBytes = payloadChainBytes
        )
    }

    fun serializedTransferChainSize(blocks: List<SpcData.TransferBlock>): Int =
        blocks.sumOf { 4 + it.data.size } + 2

    fun compactedNativePayloadWrites(
        payload: MusicNativePayloadEdit,
        targetPlayIndex: Int
    ): Map<Int, ByteArray> {
        val sourcePlayIndex = payload.sourcePlayIndex.takeIf { it >= 0 } ?: targetPlayIndex
        val sourceEntry = MusicSequenceBudget.SONG_TABLE_BASE + sourcePlayIndex * 2
        val targetEntry = MusicSequenceBudget.SONG_TABLE_BASE + targetPlayIndex * 2
        val bytesByAddr = java.util.TreeMap<Int, Int>()
        for (block in MusicEditConversion.toTransferBlocks(payload)) {
            val dest = block.destAddr and 0xFFFF
            for (i in block.data.indices) {
                val addr = dest + i
                val value = block.data[i].toInt() and 0xFF
                if (addr == sourceEntry || addr == sourceEntry + 1) {
                    bytesByAddr[targetEntry + (addr - sourceEntry)] = value
                } else {
                    bytesByAddr[addr] = value
                }
            }
        }
        return compactSpcBytes(bytesByAddr)
    }

    private fun compactSpcBytes(bytesByAddr: java.util.TreeMap<Int, Int>): Map<Int, ByteArray> {
        val writes = linkedMapOf<Int, ByteArray>()
        var runStart = -1
        val runBytes = mutableListOf<Int>()
        var previousAddr = -1

        fun flushRun() {
            if (runStart >= 0) {
                writes[runStart] = ByteArray(runBytes.size) { runBytes[it].toByte() }
                runBytes.clear()
                runStart = -1
            }
        }

        for ((addr, value) in bytesByAddr) {
            if (runStart < 0 || addr != previousAddr + 1) {
                flushRun()
                runStart = addr
            }
            runBytes += value
            previousAddr = addr
        }
        flushRun()
        return writes
    }
}
