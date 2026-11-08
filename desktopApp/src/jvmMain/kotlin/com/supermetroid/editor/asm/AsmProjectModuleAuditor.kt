package com.supermetroid.editor.asm

internal data class AsmAuditedModuleOutput(
    val fileId: String,
    val claims: List<AsmSourceOwnedRange>,
    val expectedBytes: List<ByteArray>,
)

/** Output-level module ownership derived from one isolated Asar build per
 * enabled module. Comparing every module to the same module-free source base
 * detects overlaps even when two modules emit identical replacement bytes. */
internal object AsmProjectModuleAuditor {
    fun claims(fileId: String, baseline: ByteArray, isolated: ByteArray): List<AsmSourceOwnedRange> =
        AsmProjectCompiler.changedRanges(baseline, isolated, listOf(0x7FDC..0x7FDF))
            .map { it.copy(sourceFileId = fileId) }

    fun capture(
        fileId: String,
        claims: List<AsmSourceOwnedRange>,
        isolated: ByteArray,
    ): AsmAuditedModuleOutput = AsmAuditedModuleOutput(
        fileId = fileId,
        claims = claims,
        expectedBytes = claims.map { claim ->
            require(claim.pcOffset >= 0 && claim.pcOffset + claim.length <= isolated.size)
            isolated.copyOfRange(claim.pcOffset, claim.pcOffset + claim.length)
        },
    )

    fun requireNoCollisions(claims: List<AsmSourceOwnedRange>) {
        val sorted = claims.sortedWith(compareBy(AsmSourceOwnedRange::pcOffset, AsmSourceOwnedRange::sourceFileId))
        for (index in sorted.indices) {
            val first = sorted[index]
            var other = index + 1
            while (other < sorted.size && sorted[other].pcOffset < first.pcOffset + first.length) {
                val second = sorted[other]
                if (first.sourceFileId != second.sourceFileId && overlaps(first, second)) {
                    val start = maxOf(first.pcOffset, second.pcOffset)
                    val address = pcToSnesLoRom(start)?.let(::formatSnesAddress) ?: formatPcOffset(start)
                    throw AsmCompilationException(
                        "Project modules ${first.sourceFileId} and ${second.sourceFileId} both write $address. " +
                            "Give that ROM span one owner before building.",
                    )
                }
                other++
            }
        }
    }

    fun requireNoGeneratedCollisions(
        moduleClaims: List<AsmSourceOwnedRange>,
        generated: List<Pair<IntRange, String>>,
    ) {
        moduleClaims.forEach { module ->
            generated.firstOrNull { (range, _) ->
                module.pcOffset <= range.last && range.first < module.pcOffset + module.length
            }?.let { (_, label) ->
                val address = pcToSnesLoRom(module.pcOffset)?.let(::formatSnesAddress)
                    ?: formatPcOffset(module.pcOffset)
                throw AsmCompilationException(
                    "${module.sourceFileId} writes $address, which overlaps editor-generated $label. " +
                        "Relocate one owner before building.",
                )
            }
        }
    }

    /** A module may replace immutable game bytes, but it must not silently win
     * over a bank override made by the project source layer. Checked hooks are
     * therefore deterministic instead of depending on include order. */
    fun requireNoProjectSourceCollisions(
        moduleClaims: List<AsmSourceOwnedRange>,
        immutable: ByteArray,
        moduleFree: ByteArray,
    ) {
        require(immutable.size == moduleFree.size)
        moduleClaims.forEach { module ->
            val end = (module.pcOffset + module.length).coerceAtMost(moduleFree.size)
            val collision = (module.pcOffset.coerceAtLeast(0) until end).firstOrNull { pc ->
                immutable[pc] != moduleFree[pc]
            } ?: return@forEach
            val address = pcToSnesLoRom(collision)?.let(::formatSnesAddress) ?: formatPcOffset(collision)
            throw AsmCompilationException(
                "${module.sourceFileId} writes $address, which is already changed by project bank source. " +
                    "Give that ROM span one owner before building.",
            )
        }
    }

    /** Independent compilation establishes both ownership and bytes. The full
     * ordered build must reproduce those isolated outputs exactly and may not
     * acquire a new write through read-dependent or conditional interaction
     * with another module. */
    fun requireDeterministicComposition(
        moduleFree: ByteArray,
        combined: ByteArray,
        modules: List<AsmAuditedModuleOutput>,
    ) {
        require(moduleFree.size == combined.size)
        modules.forEach { module ->
            module.claims.zip(module.expectedBytes).forEach { (claim, expected) ->
                val actual = combined.copyOfRange(claim.pcOffset, claim.pcOffset + claim.length)
                if (!actual.contentEquals(expected)) {
                    val mismatch = expected.indices.firstOrNull { expected[it] != actual[it] } ?: 0
                    val pc = claim.pcOffset + mismatch
                    val address = pcToSnesLoRom(pc)?.let(::formatSnesAddress) ?: formatPcOffset(pc)
                    throw AsmCompilationException(
                        "${module.fileId} produces different bytes at $address when combined with other modules. " +
                            "Remove the order-dependent module interaction before building.",
                    )
                }
            }
        }
        val claims = modules.flatMap(AsmAuditedModuleOutput::claims).sortedBy(AsmSourceOwnedRange::pcOffset)
        fun fullyCovered(changed: AsmSourceOwnedRange): Boolean {
            var cursor = changed.pcOffset
            val end = changed.pcOffset + changed.length
            for (claim in claims) {
                val claimEnd = claim.pcOffset + claim.length
                if (claimEnd <= cursor) continue
                if (claim.pcOffset > cursor) return false
                cursor = maxOf(cursor, claimEnd)
                if (cursor >= end) return true
            }
            return cursor >= end
        }
        val uncovered = AsmProjectCompiler.changedRanges(moduleFree, combined, listOf(0x7FDC..0x7FDF))
            .firstOrNull { changed -> !fullyCovered(changed) }
        if (uncovered != null) {
            val address = pcToSnesLoRom(uncovered.pcOffset)?.let(::formatSnesAddress)
                ?: formatPcOffset(uncovered.pcOffset)
            throw AsmCompilationException(
                "Combined Project Modules write $address even though no independently audited module owns it. " +
                    "Remove order-dependent reads or conditional assembly between modules.",
            )
        }
    }

    fun mergeOwnership(
        existing: List<AsmSourceOwnedRange>,
        moduleClaims: List<AsmSourceOwnedRange>,
    ): List<AsmSourceOwnedRange> {
        val retained = existing.flatMap { old ->
            var pieces = listOf(old)
            moduleClaims.forEach { claim ->
                pieces = pieces.flatMap { part -> subtract(part, claim) }
            }
            pieces
        }
        return retained + moduleClaims
    }

    private fun overlaps(first: AsmSourceOwnedRange, second: AsmSourceOwnedRange): Boolean =
        first.pcOffset < second.pcOffset + second.length && second.pcOffset < first.pcOffset + first.length

    private fun subtract(part: AsmSourceOwnedRange, claim: AsmSourceOwnedRange): List<AsmSourceOwnedRange> {
        val start = maxOf(part.pcOffset, claim.pcOffset)
        val end = minOf(part.pcOffset + part.length, claim.pcOffset + claim.length)
        if (start >= end) return listOf(part)
        return listOfNotNull(
            part.copy(length = start - part.pcOffset).takeIf { it.length > 0 },
            part.copy(
                pcOffset = end,
                length = part.pcOffset + part.length - end,
            ).takeIf { it.length > 0 },
        )
    }
}
