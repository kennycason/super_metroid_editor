package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.SpcData
import java.io.ByteArrayOutputStream

/** Complete uploads split only in ROM; each piece still targets the original SPC addresses. */
internal object MusicTransferChainLayout {
    private const val SINGLE_SEGMENT_BYTES = 0x4000 // Share free banks with code, palettes and other exporters.
    private const val SEGMENT_BYTES = 0x1000

    data class Segment(val body: ByteArray, val last: Boolean) {
        val size get() = body.size + if (last) 4 else 5
    }
    data class Layout(val segments: List<Segment>, val entryAddress: Int) {
        val size get() = segments.sumOf { it.size }
        val continued get() = segments.size > 1
        fun encode(index: Int, nextPointer: Int?): ByteArray {
            val segment = segments[index]
            require(segment.last == (nextPointer == null)) { "Missing or unexpected music continuation" }
            val tail = if (nextPointer == null) byteArrayOf(0, 0, entryAddress.toByte(), (entryAddress ushr 8).toByte()) else {
                require(nextPointer ushr 16 in 0x80..0xFF && nextPointer and 65535 in 0x8000..0xFFFB) { "Invalid music continuation ROM pointer" }
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), nextPointer.toByte(), (nextPointer ushr 8).toByte(), (nextPointer ushr 16).toByte())
            }
            return segment.body + tail
        }
    }

    fun plan(blocks: List<SpcData.TransferBlock>, entryAddress: Int): Layout {
        require(entryAddress in 0..65535) { "Invalid music entry address" }
        for (block in blocks) require(block.destAddr in 0..65535 && block.data.size <= 65536 - block.destAddr) { "Music block exceeds SPC RAM" }
        val nonEmpty = blocks.filter { it.data.isNotEmpty() }
        val logicalBytes = MusicTransferChainBudget.serializedTransferChainSize(nonEmpty)
        if (logicalBytes <= SINGLE_SEGMENT_BYTES) {
            require(nonEmpty.size < 4096) { "Music upload needs too many transfer blocks" }
            val single = ByteArrayOutputStream()
            fun word(value: Int) { single.write(value and 255); single.write(value ushr 8 and 255) }
            for (block in nonEmpty) { word(block.data.size); word(block.destAddr); single.write(block.data) }
            return Layout(listOf(Segment(single.toByteArray(), true)), entryAddress)
        }
        val limit = SEGMENT_BYTES
        val bodies = mutableListOf<ByteArray>()
        var out = ByteArrayOutputStream()
        var emitted = 0
        fun word(value: Int) { out.write(value and 255); out.write(value ushr 8 and 255) }
        for (block in nonEmpty) {
            var offset = 0
            while (offset < block.data.size) {
                // Leave a complete 5-byte continuation inside the same LoROM bank.
                var available = limit - out.size() - 4 - 5
                if (available <= 0) { bodies += out.toByteArray(); out = ByteArrayOutputStream(); available = limit - 9 }
                val count = minOf(available, block.data.size - offset)
                require(++emitted < 4096) { "Music upload needs too many transfer blocks" }
                word(count); word(block.destAddr + offset); out.write(block.data, offset, count); offset += count
            }
        }
        bodies += out.toByteArray()
        require(bodies.size <= 65) { "Music upload needs too many ROM continuations" }
        return Layout(bodies.mapIndexed { index, body -> Segment(body, index == bodies.lastIndex) }, entryAddress)
    }
}
