package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteKind

internal enum class AsmCompiledOutputStatus {
    CURRENT,
    STALE,
    BUILD_REQUIRED,
}

internal data class AsmCompiledOutputRange(
    val snesStart: Int,
    val pcStart: Int,
    val length: Int,
    val baselineBytes: List<Int>,
    val loadedBytes: List<Int>,
    val resultBytes: List<Int>,
) {
    val snesEndInclusive: Int get() = snesStart + length - 1
    val pcEndInclusive: Int get() = pcStart + length - 1
    val changedFromBaseline: Boolean get() = baselineBytes != resultBytes
    val changedFromLoaded: Boolean get() = loadedBytes != resultBytes
}

internal data class AsmCompiledOutputOwner(
    val owner: String,
    val label: String,
    val kind: RomWriteKind,
)

internal data class AsmCompiledOutputReference(
    val name: String,
    val fileId: String,
    val lineIndex: Int,
    val sourceLine: String,
)

internal data class AsmCompiledOutputInspection(
    val fileId: String,
    val lineIndex: Int,
    val sourceLine: String,
    val status: AsmCompiledOutputStatus,
    val ranges: List<AsmCompiledOutputRange>,
    val symbols: List<Pair<String, Int>>,
    val owners: List<AsmCompiledOutputOwner>,
    val allocation: AsmRomSpacePlacement?,
    val bank: AsmRomSpaceBank?,
    val incomingReferences: List<AsmCompiledOutputReference>,
    val outgoingReferences: List<AsmCompiledOutputReference>,
    val diagnostics: List<AsmBuildDiagnostic>,
) {
    val emittedByteCount: Int get() = ranges.sumOf(AsmCompiledOutputRange::length)
    val changedFromBaseline: Boolean get() = ranges.any(AsmCompiledOutputRange::changedFromBaseline)
    val changedFromLoaded: Boolean get() = ranges.any(AsmCompiledOutputRange::changedFromLoaded)
}

internal data class AsmDiagnosticPresentation(
    val location: String?,
    val sourceLine: String?,
    val generated: Boolean,
    val authority: String?,
    val navigable: Boolean,
)

internal fun generatedAsmAuthority(fileId: String): String? = when (fileId.substringAfterLast('/')) {
    "__smedit_generated_rooms.asm" -> "Generated build input · room transaction — edit the owning room or project room source."
    "__smedit_generated_patches.asm" -> "Generated build input · patch transaction — edit the owning patch or its authored source."
    "__smedit_managed_bank8f.asm" -> "Generated build input · managed code — edit room_objects.asm, not this projection."
    "__smedit_managed_assets.asm" -> "Generated build input · asset placement — edit the source declaration or Visual Editor asset."
    else -> null
}

internal object AsmCompiledOutputResolver {
    fun resolve(
        fileId: String,
        lineIndex: Int,
        sourceText: String,
        index: AsmReferenceIndex,
        symbols: AsmCompiledSymbols,
        preview: AsmRomPreview?,
        spacePlan: AsmRomSpacePlan?,
        stale: Boolean,
        diagnostics: List<AsmBuildDiagnostic>,
    ): AsmCompiledOutputInspection {
        val sourceLine = sourceText.lineSequence().drop(lineIndex).firstOrNull().orEmpty()
        val exactAddresses = symbols.addressesAt(fileId, lineIndex)
            .filter { snesLoRomToPc(it) != null }
            .distinct()
            .sorted()
        val transactionBoundaries = buildSet {
            spacePlan?.placements.orEmpty().forEach { placement ->
                add(placement.snesAddress)
                add(placement.snesAddress + placement.size)
            }
            preview?.writeReport?.writes.orEmpty().forEach { write ->
                pcToSnesLoRom(write.offset)?.let(::add)
                pcToSnesLoRom(write.offset + write.bytes.size)?.let(::add)
            }
        }.filter { snesLoRomToPc(it) != null }.sorted()
        val ranges = if (preview == null) emptyList() else exactAddresses.mapNotNull { start ->
            val bank = start ushr 16
            val end = listOfNotNull(
                symbols.nextBoundaryInBank(start),
                transactionBoundaries.firstOrNull { candidate -> candidate > start && candidate ushr 16 == bank },
            ).minOrNull()
                ?: return@mapNotNull null
            val length = end - start
            val pc = snesLoRomToPc(start) ?: return@mapNotNull null
            if (length <= 0 || length > 0x8000) return@mapNotNull null
            AsmCompiledOutputRange(
                snesStart = start,
                pcStart = pc,
                length = length,
                baselineBytes = bytes(preview, pc, length, AsmRomPreview::baselineByte),
                loadedBytes = bytes(preview, pc, length, AsmRomPreview::loadedByte),
                resultBytes = bytes(preview, pc, length, AsmRomPreview::resultByte),
            )
        }.mergeAdjacent()
        val inspectedPc = ranges.firstOrNull()?.pcStart
            ?: exactAddresses.firstNotNullOfOrNull(::snesLoRomToPc)
        val allocation = inspectedPc?.let { pc ->
            spacePlan?.placements?.firstOrNull { pc >= it.pcOffset && pc < it.pcOffset + it.size }
        }
        val bankNumber = exactAddresses.firstOrNull()?.ushr(16)?.and(0xFF)
            ?: allocation?.bank
        val bank = bankNumber?.let { selected -> spacePlan?.banks?.firstOrNull { it.bank == selected } }
        val owners = preview?.writeReport?.writes.orEmpty().filter { write ->
            ranges.any { range -> write.offset < range.pcStart + range.length && range.pcStart <= write.endInclusive }
        }.map { AsmCompiledOutputOwner(it.owner, it.label, it.kind) }
            .distinct()
        val symbolsInRange = symbols.labels.asSequence().filter { (_, address) ->
            ranges.any { address in it.snesStart..it.snesEndInclusive } ||
                (ranges.isEmpty() && address in exactAddresses)
        }.sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
            .toList()

        val incoming = buildList {
            index.labelsAt(fileId, lineIndex).forEach { label ->
                index.usagesFor(label).forEach { usage ->
                    add(AsmCompiledOutputReference(label.name, usage.fileId, usage.lineIndex, usage.sourceLine))
                }
            }
            index.macrosAt(fileId, lineIndex).forEach { macro ->
                index.usagesFor(macro).forEach { usage ->
                    add(AsmCompiledOutputReference("%${macro.name}", usage.fileId, usage.lineIndex, usage.sourceLine))
                }
            }
        }.distinctBy { Triple(it.fileId, it.lineIndex, it.name) }
        val outgoing = buildList {
            AsmSourceLexing.references(asmCodeBeforeComment(sourceLine)).forEach { match ->
                val token = match.value
                val targetLabel = index.resolveLabel(fileId, lineIndex, token)
                if (targetLabel != null && !(targetLabel.fileId == fileId && targetLabel.lineIndex == lineIndex)) {
                    val line = index.file(targetLabel.fileId)?.lines?.getOrNull(targetLabel.lineIndex).orEmpty().trim()
                    add(AsmCompiledOutputReference(token, targetLabel.fileId, targetLabel.lineIndex, line))
                } else if (token.startsWith('%')) {
                    index.resolveMacro(token)?.let { macro ->
                        val line = index.file(macro.fileId)?.lines?.getOrNull(macro.lineIndex).orEmpty().trim()
                        add(AsmCompiledOutputReference(token, macro.fileId, macro.lineIndex, line))
                    }
                }
            }
        }.distinctBy { Triple(it.fileId, it.lineIndex, it.name) }

        return AsmCompiledOutputInspection(
            fileId = fileId,
            lineIndex = lineIndex,
            sourceLine = sourceLine,
            status = when {
                preview == null -> AsmCompiledOutputStatus.BUILD_REQUIRED
                stale -> AsmCompiledOutputStatus.STALE
                else -> AsmCompiledOutputStatus.CURRENT
            },
            ranges = ranges,
            symbols = symbolsInRange,
            owners = owners,
            allocation = allocation,
            bank = bank,
            incomingReferences = incoming,
            outgoingReferences = outgoing,
            diagnostics = diagnostics.filter { it.fileId == fileId && it.lineIndex == lineIndex },
        )
    }

    private fun bytes(
        preview: AsmRomPreview,
        pcStart: Int,
        length: Int,
        read: AsmRomPreview.(Int) -> Int?,
    ): List<Int> = (pcStart until pcStart + length).mapNotNull { preview.read(it) }

    private fun List<AsmCompiledOutputRange>.mergeAdjacent(): List<AsmCompiledOutputRange> {
        if (isEmpty()) return this
        val merged = mutableListOf<AsmCompiledOutputRange>()
        for (range in sortedBy(AsmCompiledOutputRange::pcStart)) {
            val previous = merged.lastOrNull()
            if (previous != null && previous.pcStart + previous.length == range.pcStart) {
                merged[merged.lastIndex] = previous.copy(
                    length = previous.length + range.length,
                    baselineBytes = previous.baselineBytes + range.baselineBytes,
                    loadedBytes = previous.loadedBytes + range.loadedBytes,
                    resultBytes = previous.resultBytes + range.resultBytes,
                )
            } else merged += range
        }
        return merged
    }
}

