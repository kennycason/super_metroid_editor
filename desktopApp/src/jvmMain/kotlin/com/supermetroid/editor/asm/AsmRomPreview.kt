package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteIntent
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport
import com.supermetroid.editor.rom.RomFreeSpaceSnapshot

internal enum class AsmRomPreviewView(val title: String) {
    LOADED_ROM("Loaded ROM"),
    SMEDIT_RESULT("SMEDIT Result"),
    DIFF("Diff"),
    SPACE("Space Planner"),
}

internal data class AsmRomSpacePlacement(
    val bank: Int,
    val pcOffset: Int,
    val snesAddress: Int,
    val size: Int,
    val label: String,
    val owner: String?,
)

internal data class AsmRomSpaceOwner(
    val owner: String,
    val writes: Int,
    val kinds: Set<String>,
)

internal data class AsmRomSpaceRange(
    val pcStart: Int,
    val length: Int,
    val fillByte: Int,
    val label: String,
) {
    val endExclusive: Int get() = pcStart + length
}

internal data class AsmRomSpaceBank(
    val bank: Int,
    val pcStart: Int,
    val capacityBytes: Int,
    val availableBefore: Int,
    val remainingAfter: Int,
    val fillByte: Int,
    val placements: List<AsmRomSpacePlacement>,
    val owners: List<AsmRomSpaceOwner>,
    val largestBefore: Int = availableBefore,
    val largestAfter: Int = remainingAfter,
    val rangesAfter: List<AsmRomSpaceRange> = if (remainingAfter > 0) {
        listOf(AsmRomSpaceRange(pcStart + capacityBytes - remainingAfter, remainingAfter, fillByte, "Trailing bank space"))
    } else emptyList(),
) {
    val consumedBytes: Int get() = (availableBefore - remainingAfter).coerceAtLeast(0)
}

internal data class AsmRomSpacePlan(
    val romBodySize: Int,
    val banks: List<AsmRomSpaceBank>,
) {
    val placements: List<AsmRomSpacePlacement> get() = banks.flatMap(AsmRomSpaceBank::placements)
    val allocationBytes: Int get() = placements.sumOf(AsmRomSpacePlacement::size)
    val availableBefore: Int get() = banks.sumOf(AsmRomSpaceBank::availableBefore)
    val remainingAfter: Int get() = banks.sumOf(AsmRomSpaceBank::remainingAfter)
    val consumedBytes: Int get() = banks.sumOf(AsmRomSpaceBank::consumedBytes)
    val largestRemainingBank: AsmRomSpaceBank? get() = banks.maxByOrNull(AsmRomSpaceBank::largestAfter)

    companion object {
        fun create(
            bodySize: Int,
            headerSize: Int,
            report: RomWritePlanReport,
            before: RomFreeSpaceSnapshot?,
            after: RomFreeSpaceSnapshot?,
        ): AsmRomSpacePlan? {
            if (before == null || after == null || before.banks.isEmpty() || after.banks.isEmpty()) return null
            val beforeByBank = before.banks.associateBy { it.bank }
            val placementsByBank = after.allocations.map { allocation ->
                val pcOffset = allocation.pcOffset - headerSize
                val owner = report.writes.firstOrNull {
                    it.offset == pcOffset && it.bytes.size == allocation.size && it.label == allocation.label
                }?.owner ?: report.writes.firstOrNull {
                    it.offset == pcOffset && it.bytes.size == allocation.size
                }?.owner
                AsmRomSpacePlacement(
                    bank = allocation.bank,
                    pcOffset = pcOffset,
                    snesAddress = allocation.snesAddress,
                    size = allocation.size,
                    label = allocation.label,
                    owner = owner,
                )
            }.groupBy(AsmRomSpacePlacement::bank)
            val banks = after.banks.mapNotNull { finalBank ->
                val initialBank = beforeByBank[finalBank.bank] ?: return@mapNotNull null
                val pcStart = finalBank.pcStart - headerSize
                val pcEnd = pcStart + finalBank.capacityBytes
                val owners = report.writes
                    .filter { it.offset < pcEnd && it.endInclusive >= pcStart }
                    .groupBy(RomWriteIntent::owner)
                    .map { (owner, writes) ->
                        AsmRomSpaceOwner(
                            owner = owner,
                            writes = writes.size,
                            kinds = writes.mapTo(linkedSetOf()) { it.kind.name.lowercase().replace('_', ' ') },
                        )
                    }
                    .sortedBy(AsmRomSpaceOwner::owner)
                AsmRomSpaceBank(
                    bank = finalBank.bank,
                    pcStart = pcStart,
                    capacityBytes = finalBank.capacityBytes,
                    availableBefore = initialBank.totalAvailableBytes,
                    remainingAfter = finalBank.totalAvailableBytes,
                    fillByte = finalBank.fillByte,
                    placements = placementsByBank[finalBank.bank].orEmpty().sortedBy(AsmRomSpacePlacement::pcOffset),
                    owners = owners,
                    largestBefore = initialBank.largestAvailableBytes,
                    largestAfter = finalBank.largestAvailableBytes,
                    rangesAfter = finalBank.ranges.map { range ->
                        AsmRomSpaceRange(
                            pcStart = range.pcStart - headerSize,
                            length = range.length,
                            fillByte = range.fillByte,
                            label = range.label,
                        )
                    },
                )
            }.sortedBy(AsmRomSpaceBank::bank)
            return AsmRomSpacePlan(bodySize, banks)
        }
    }
}

internal data class AsmRomPreviewBuild(
    val preview: AsmRomPreview?,
    val spacePlan: AsmRomSpacePlan?,
    val error: String?,
)

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
    val spacePlan: AsmRomSpacePlan?,
) {
    val loadedRom: ByteArray = loadedRom.copyOf()
    val resultRom: ByteArray = resultRom.copyOf()
    val loadedBodySize: Int get() = (loadedRom.size - headerSize).coerceAtLeast(0)
    val resultBodySize: Int get() = (resultRom.size - headerSize).coerceAtLeast(0)

    fun bytes(view: AsmRomPreviewView): ByteArray = when (view) {
        AsmRomPreviewView.LOADED_ROM -> loadedRom
        AsmRomPreviewView.SMEDIT_RESULT -> resultRom
        AsmRomPreviewView.DIFF -> resultRom
        AsmRomPreviewView.SPACE -> resultRom
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
            romSpaceBefore: RomFreeSpaceSnapshot? = null,
            romSpaceAfter: RomFreeSpaceSnapshot? = null,
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
                spacePlan = AsmRomSpacePlan.create(
                    bodySize = resultBodySize,
                    headerSize = headerSize,
                    report = writeReport,
                    before = romSpaceBefore,
                    after = romSpaceAfter,
                ),
            )
        }

        private fun sameByte(loaded: ByteArray, result: ByteArray, headerSize: Int, pcOffset: Int): Boolean {
            val fileOffset = headerSize + pcOffset
            if (fileOffset >= loaded.size || fileOffset >= result.size) return false
            return loaded[fileOffset] == result[fileOffset]
        }
    }
}
