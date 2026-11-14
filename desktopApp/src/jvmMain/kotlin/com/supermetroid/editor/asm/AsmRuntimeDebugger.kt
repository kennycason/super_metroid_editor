package com.supermetroid.editor.asm

import com.supermetroid.editor.emulator.CpuDebugState

internal data class AsmRuntimeSourceLocation(
    val fileId: String,
    val lineIndex: Int,
    val sourceLine: String?,
    val address: Int,
    val symbols: List<String>,
    val generated: Boolean,
)

internal data class AsmRuntimeStackCandidate(
    val stackOffset: Int,
    val returnAddress: Int,
    val symbol: String?,
    val source: AsmRuntimeSourceLocation?,
    val longReturn: Boolean,
)

internal data class AsmRuntimeInspection(
    val cpu: CpuDebugState,
    val currentSource: AsmRuntimeSourceLocation?,
    val currentSymbol: Pair<String, Int>?,
    val stackAddress: Int,
    val stackBytes: ByteArray,
    val stackCandidates: List<AsmRuntimeStackCandidate>,
    val mappingStale: Boolean,
    val executionConcern: String?,
)

internal fun isAsmRuntimeMappingFresh(
    sessionBuildIdentity: String?,
    reportBuildIdentity: String?,
    reportSucceeded: Boolean,
    sourceBuildRequired: Boolean,
): Boolean = sessionBuildIdentity != null && reportSucceeded &&
    sessionBuildIdentity == reportBuildIdentity && !sourceBuildRequired

internal object AsmRuntimeDebugResolver {
    fun resolve(
        cpu: CpuDebugState,
        symbols: AsmCompiledSymbols,
        stackAddress: Int,
        stackBytes: ByteArray,
        mappingStale: Boolean,
        sourceLine: (String, Int) -> String?,
        isGenerated: (String) -> Boolean,
    ): AsmRuntimeInspection {
        fun location(address: Int): AsmRuntimeSourceLocation? = symbols.locationsAt(address)
            .firstOrNull()
            ?.let { mapped ->
                AsmRuntimeSourceLocation(
                    fileId = mapped.fileId,
                    lineIndex = mapped.lineIndex,
                    sourceLine = sourceLine(mapped.fileId, mapped.lineIndex),
                    address = address,
                    symbols = symbols.labelsAt(address),
                    generated = isGenerated(mapped.fileId),
                )
            }

        val currentSource = location(cpu.pc)
        val currentSymbol = symbols.nearestLabelAtOrBefore(cpu.pc)
        val candidates = buildList {
            for (offset in stackBytes.indices) {
                if (offset + 2 < stackBytes.size) {
                    val raw = byte(stackBytes, offset) or
                        (byte(stackBytes, offset + 1) shl 8) or
                        (byte(stackBytes, offset + 2) shl 16)
                    val address = (raw + 1) and 0xFFFFFF
                    val mapped = location(address)
                    val label = symbols.labelsAt(address).firstOrNull()
                        ?: mapped?.let { symbols.nearestLabelAtOrBefore(address)?.first }
                    if (mapped != null || symbols.labelsAt(address).isNotEmpty()) {
                        add(AsmRuntimeStackCandidate(offset, address, label, mapped, longReturn = true))
                    }
                }
                if (offset + 1 < stackBytes.size) {
                    val raw = byte(stackBytes, offset) or (byte(stackBytes, offset + 1) shl 8)
                    val address = (cpu.pb shl 16) or ((raw + 1) and 0xFFFF)
                    val mapped = location(address)
                    val label = symbols.labelsAt(address).firstOrNull()
                        ?: mapped?.let { symbols.nearestLabelAtOrBefore(address)?.first }
                    if (mapped != null || symbols.labelsAt(address).isNotEmpty()) {
                        add(AsmRuntimeStackCandidate(offset, address, label, mapped, longReturn = false))
                    }
                }
            }
        }.distinctBy { Triple(it.stackOffset, it.returnAddress, it.longReturn) }.take(MAX_STACK_CANDIDATES)

        val concern = when {
            cpu.halted -> "The CPU reported a stopped/invalid execution state."
            cpu.pc ushr 16 in 0x7E..0x7F -> "Execution entered WRAM; this is usually data or generated runtime code and deserves inspection."
            currentSource == null && symbols.labelCount > 0 -> "The current PC is not present in the retained source map. The ROM may not match this build, or execution entered unmapped/generated code."
            else -> null
        }
        return AsmRuntimeInspection(
            cpu = cpu,
            currentSource = currentSource,
            currentSymbol = currentSymbol,
            stackAddress = stackAddress,
            stackBytes = stackBytes.copyOf(),
            stackCandidates = candidates,
            mappingStale = mappingStale,
            executionConcern = concern,
        )
    }

    private fun byte(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

    private const val MAX_STACK_CANDIDATES = 8
}
