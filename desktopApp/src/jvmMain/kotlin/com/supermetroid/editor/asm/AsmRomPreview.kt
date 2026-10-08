package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteIntent
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport

internal enum class AsmRomPreviewView(val title: String) {
    LOADED_ROM("Loaded ROM"),
    SMEDIT_RESULT("SMEDIT Result"),
    DIFF("Diff"),
}

internal data class AsmRomDiffRange(
    val pcOffset: Int,
    val length: Int,
    val owners: List<String>,
    val labels: List<String>,
    val kinds: Set<RomWriteKind>,
) {
    val endInclusive: Int get() = pcOffset + length - 1
}

/** A non-writing snapshot produced by the real transactional exporter. */
internal class AsmRomPreview private constructor(
    loadedRom: ByteArray,
    resultRom: ByteArray,
    val headerSize: Int,
    val writeReport: RomWritePlanReport,
    val diffRanges: List<AsmRomDiffRange>,
    val changedByteCount: Int,
) {
    val loadedRom: ByteArray = loadedRom.copyOf()
    val resultRom: ByteArray = resultRom.copyOf()
    val loadedBodySize: Int get() = (loadedRom.size - headerSize).coerceAtLeast(0)
    val resultBodySize: Int get() = (resultRom.size - headerSize).coerceAtLeast(0)

    fun bytes(view: AsmRomPreviewView): ByteArray = when (view) {
        AsmRomPreviewView.LOADED_ROM -> loadedRom
        AsmRomPreviewView.SMEDIT_RESULT -> resultRom
        AsmRomPreviewView.DIFF -> resultRom
    }

    fun loadedByte(pcOffset: Int): Int? = byteAt(loadedRom, pcOffset)

    fun resultByte(pcOffset: Int): Int? = byteAt(resultRom, pcOffset)

    private fun byteAt(bytes: ByteArray, pcOffset: Int): Int? {
        val fileOffset = headerSize + pcOffset
        return bytes.getOrNull(fileOffset)?.toInt()?.and(0xFF)
    }

    companion object {
        fun create(
            loadedRom: ByteArray,
            resultRom: ByteArray,
            headerSize: Int,
            writeReport: RomWritePlanReport,
            maxRangeBytes: Int = Int.MAX_VALUE,
        ): AsmRomPreview {
            require(headerSize >= 0 && headerSize <= loadedRom.size && headerSize <= resultRom.size)
            require(maxRangeBytes > 0)

            val loadedBodySize = loadedRom.size - headerSize
            val resultBodySize = resultRom.size - headerSize
            val bodySize = maxOf(loadedBodySize, resultBodySize)
            val writes = writeReport.writes.sortedBy(RomWriteIntent::offset)
            val ownershipBoundaries = writes.asSequence()
                .flatMap { sequenceOf(it.offset, it.endInclusive + 1) }
                .filter { it in 1 until bodySize }
                .distinct()
                .sorted()
                .toList()
            val ranges = mutableListOf<AsmRomDiffRange>()
            var changedBytes = 0
            var pc = 0
            var boundaryIndex = 0
            var writeStartIndex = 0
            val activeWrites = mutableListOf<RomWriteIntent>()

            fun writesAt(position: Int): List<RomWriteIntent> {
                activeWrites.removeAll { it.endInclusive < position }
                while (writeStartIndex < writes.size && writes[writeStartIndex].offset <= position) {
                    val write = writes[writeStartIndex++]
                    if (write.endInclusive >= position) activeWrites += write
                }
                return activeWrites
            }

            while (pc < bodySize) {
                if (sameByte(loadedRom, resultRom, headerSize, pc)) {
                    pc++
                    continue
                }
                val start = pc
                while (boundaryIndex < ownershipBoundaries.size && ownershipBoundaries[boundaryIndex] <= start) {
                    boundaryIndex++
                }
                val ownershipEnd = ownershipBoundaries.getOrNull(boundaryIndex) ?: bodySize
                val rangeEndExclusive = minOf(
                    bodySize.toLong(),
                    ownershipEnd.toLong(),
                    start.toLong() + maxRangeBytes,
                ).toInt()
                while (
                    pc < rangeEndExclusive &&
                    !sameByte(loadedRom, resultRom, headerSize, pc)
                ) {
                    pc++
                }
                val length = pc - start
                changedBytes += length
                val owners = writesAt(start)
                ranges += AsmRomDiffRange(
                    pcOffset = start,
                    length = length,
                    owners = owners.map(RomWriteIntent::owner).distinct(),
                    labels = owners.map(RomWriteIntent::label).distinct(),
                    kinds = owners.mapTo(linkedSetOf(), RomWriteIntent::kind),
                )
            }

            return AsmRomPreview(
                loadedRom = loadedRom,
                resultRom = resultRom,
                headerSize = headerSize,
                writeReport = writeReport,
                diffRanges = ranges,
                changedByteCount = changedBytes,
            )
        }

        private fun sameByte(loaded: ByteArray, result: ByteArray, headerSize: Int, pcOffset: Int): Boolean {
            val fileOffset = headerSize + pcOffset
            if (fileOffset >= loaded.size || fileOffset >= result.size) return false
            return loaded[fileOffset] == result[fileOffset]
        }
    }
}
