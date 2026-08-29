package com.supermetroid.editor.rom

/**
 * The kind of mutation being made. This is diagnostic metadata; every kind is
 * subject to the same bounds, overlap, and precondition checks.
 */
enum class RomWriteKind {
    FIXED_PATCH,
    CONFIG,
    GENERATED,
    HOOK,
    ALLOCATION,
    ROOM,
    MUSIC,
    GRAPHICS,
    TEXT,
    MINIMAP,
    CUSTOM_ASM,
    RAW,
}

enum class RomOverlapPolicy {
    DENY,
    /** Multiple logical owners intentionally expose the same immutable bytes. */
    ALLOW_IDENTICAL,
    /** Complete a range already partially captured for this exact owner. */
    ALLOW_IDENTICAL_SAME_OWNER,
    /** A single stateful exporter intentionally evolves data it already owns. */
    ALLOW_SAME_OWNER,
}

data class RomWriteIntent(
    val owner: String,
    val label: String,
    val offset: Int,
    val bytes: List<Int>,
    val kind: RomWriteKind = RomWriteKind.GENERATED,
    /** Bytes that must be present immediately before this write is staged. */
    val expectedBefore: List<Int>? = null,
    /** Marks fixed writes that should eventually acquire an explicit precondition. */
    val preconditionRecommended: Boolean = false,
    val overlapPolicy: RomOverlapPolicy = RomOverlapPolicy.DENY,
) {
    val endInclusive: Int get() = offset + bytes.size - 1
}

enum class RomResourceAccess {
    EXCLUSIVE,
    SHARED,
}

/**
 * A non-ROM resource used by generated code. Namespaces keep unlike address
 * spaces separate (for example, WRAM and VRAM can both contain address $3800).
 */
data class RomResourceClaim(
    val owner: String,
    val namespace: String,
    val start: Int,
    val endInclusive: Int = start,
    val label: String,
    val access: RomResourceAccess = RomResourceAccess.EXCLUSIVE,
    /** Shared claims are compatible only when they use the same non-blank group. */
    val sharedGroup: String? = null,
)

data class RomWriteOwnerSummary(
    val owner: String,
    val writes: Int,
    val bytes: Int,
    val startOffset: Int,
    val endInclusive: Int,
)

data class RomWritePlanReport(
    val writes: List<RomWriteIntent>,
    val resources: List<RomResourceClaim>,
    val owners: List<RomWriteOwnerSummary>,
    val unverifiedFixedWrites: List<RomWriteIntent>,
) {
    val totalWrites: Int get() = writes.size
    val totalBytes: Int get() = writes.sumOf { it.bytes.size }

    fun logLines(): List<String> = buildList {
        add("[ROM-PLAN] Validated $totalWrites writes / $totalBytes bytes across ${owners.size} owners")
        for (owner in owners) {
            add(
                "[ROM-PLAN]   ${owner.owner}: ${owner.writes} writes, ${owner.bytes} bytes, " +
                    "PC ${owner.startOffset.hex6()}-${owner.endInclusive.hex6()}"
            )
        }
        if (resources.isNotEmpty()) {
            add("[ROM-PLAN]   ${resources.size} non-ROM resource claims validated")
            for (resource in resources) {
                add(
                    "[ROM-PLAN]     ${resource.namespace} ${resource.start.hex4()}-" +
                        "${resource.endInclusive.hex4()}: ${resource.owner} (${resource.label})"
                )
            }
        }
        if (unverifiedFixedWrites.isNotEmpty()) {
            add(
                "[ROM-PLAN] WARN: ${unverifiedFixedWrites.size} fixed writes have no expected-before bytes; " +
                    "overlaps are protected, but base-ROM compatibility is not fully proven"
            )
        }
    }
}

open class RomWritePlanException(message: String) : IllegalStateException(message)

class RomWriteBoundsException(message: String) : RomWritePlanException(message)

class RomWriteConflictException(message: String) : RomWritePlanException(message)

class RomWritePreconditionException(message: String) : RomWritePlanException(message)

class RomResourceConflictException(message: String) : RomWritePlanException(message)

/**
 * Transactional ROM mutation planner.
 *
 * [baseRom] is never mutated. Writes are validated and applied to [romData], an
 * isolated working copy. Callers emit [finalRom] only after all mutation paths
 * have completed successfully.
 *
 * All offsets supplied to this class are unheadered PC offsets. [headerSize]
 * maps them into headered SMC files while keeping IPS/report offsets canonical.
 */
class RomWritePlan(
    baseRom: ByteArray,
    val headerSize: Int = 0,
    private val verifyPreconditions: Boolean = true,
) {
    val romData: ByteArray = baseRom.copyOf()

    private val bodySize = romData.size - headerSize
    private val writes = mutableListOf<RomWriteIntent>()
    private val resources = mutableListOf<RomResourceClaim>()
    private val claimedByWriteIndex = IntArray(bodySize.coerceAtLeast(0)) { UNCLAIMED }

    init {
        require(headerSize >= 0 && headerSize <= baseRom.size) {
            "invalid ROM header size $headerSize for ${baseRom.size}-byte ROM"
        }
    }

    /** Validate and stage one write atomically. */
    fun add(intent: RomWriteIntent) {
        validateIntentShape(intent)
        val fileOffset = intent.offset + headerSize

        for (i in intent.bytes.indices) {
            val pc = intent.offset + i
            val priorIndex = claimedByWriteIndex[pc]
            if (priorIndex != UNCLAIMED) {
                val prior = writes[priorIndex]
                val priorByte = prior.bytes[pc - prior.offset]
                val identicalAllowed = when (intent.overlapPolicy) {
                    RomOverlapPolicy.DENY -> false
                    RomOverlapPolicy.ALLOW_IDENTICAL -> priorByte == intent.bytes[i]
                    RomOverlapPolicy.ALLOW_IDENTICAL_SAME_OWNER ->
                        prior.owner == intent.owner && priorByte == intent.bytes[i]
                    RomOverlapPolicy.ALLOW_SAME_OWNER -> prior.owner == intent.owner
                }
                if (!identicalAllowed) {
                    throw RomWriteConflictException(
                        "ROM write conflict at PC ${pc.hex6()}: '${intent.owner}' (${intent.label}) " +
                            "would write ${intent.bytes[i].hex2()} over '${prior.owner}' (${prior.label}) " +
                            "which owns ${priorByte.hex2()}; no write was applied"
                    )
                }
            }
        }

        intent.expectedBefore?.takeIf { verifyPreconditions }?.let { expected ->
            require(expected.size == intent.bytes.size) {
                "${intent.owner}/${intent.label} expected-before length ${expected.size} " +
                    "does not match write length ${intent.bytes.size}"
            }
            for (i in expected.indices) {
                val actual = romData[fileOffset + i].toInt() and 0xFF
                val identicalSharedResult = when (intent.overlapPolicy) {
                    RomOverlapPolicy.DENY -> false
                    RomOverlapPolicy.ALLOW_IDENTICAL -> actual == intent.bytes[i]
                    RomOverlapPolicy.ALLOW_IDENTICAL_SAME_OWNER ->
                        claimedByWriteIndex[intent.offset + i]
                            .takeIf { it != UNCLAIMED }
                            ?.let { writes[it].owner == intent.owner } == true && actual == intent.bytes[i]
                    // Same-owner evolution permits ownership replacement, not
                    // bypassing an explicitly supplied byte precondition.
                    RomOverlapPolicy.ALLOW_SAME_OWNER -> false
                }
                if (actual != expected[i] && !identicalSharedResult) {
                    throw RomWritePreconditionException(
                        "ROM precondition failed for '${intent.owner}' (${intent.label}) at PC " +
                            "${(intent.offset + i).hex6()}: expected ${expected[i].hex2()}, found ${actual.hex2()}"
                    )
                }
            }
        }

        val writeIndex = writes.size
        writes.add(intent.copy(bytes = intent.bytes.toList(), expectedBefore = intent.expectedBefore?.toList()))
        for (i in intent.bytes.indices) {
            romData[fileOffset + i] = intent.bytes[i].toByte()
            claimedByWriteIndex[intent.offset + i] = writeIndex
        }
    }

    fun add(
        owner: String,
        label: String,
        offset: Int,
        bytes: List<Int>,
        kind: RomWriteKind = RomWriteKind.GENERATED,
        expectedBefore: List<Int>? = null,
        preconditionRecommended: Boolean = false,
        overlapPolicy: RomOverlapPolicy = RomOverlapPolicy.DENY,
    ) = add(
        RomWriteIntent(
            owner = owner,
            label = label,
            offset = offset,
            bytes = bytes,
            kind = kind,
            expectedBefore = expectedBefore,
            preconditionRecommended = preconditionRecommended,
            overlapPolicy = overlapPolicy,
        )
    )

    /**
     * Records a legacy mutation block without weakening safety. The block runs
     * against the working copy, its diff is captured, the working copy is
     * restored, and the diff is replayed through normal validation as [owner].
     */
    fun <T> capture(
        owner: String,
        label: String,
        kind: RomWriteKind = RomWriteKind.GENERATED,
        overlapPolicy: RomOverlapPolicy = RomOverlapPolicy.DENY,
        block: (ByteArray) -> T,
    ): T {
        val before = romData.copyOf()
        val writeCheckpoint = writes.size
        val resourceCheckpoint = resources.size
        val result = try {
            block(romData)
        } catch (failure: Throwable) {
            restoreTransaction(before, writeCheckpoint, resourceCheckpoint)
            throw failure
        }

        if (writes.size != writeCheckpoint) {
            restoreTransaction(before, writeCheckpoint, resourceCheckpoint)
            throw RomWritePlanException(
                "ROM capture '$owner/$label' mixed direct mutations with planned writes; " +
                    "use one mutation mechanism per capture"
            )
        }

        val captured = diff(before, romData, owner, label, kind, overlapPolicy)
        restoreTransaction(before, writeCheckpoint, resourceCheckpoint)
        try {
            for (intent in captured) add(intent)
        } catch (failure: Throwable) {
            restoreTransaction(before, writeCheckpoint, resourceCheckpoint)
            throw failure
        }
        return result
    }

    /**
     * Adopts mutations already made to [romData] by a legacy exporter. [before]
     * must be a snapshot taken immediately before that exporter ran.
     */
    fun recordExternalMutation(
        owner: String,
        label: String,
        before: ByteArray,
        kind: RomWriteKind = RomWriteKind.GENERATED,
        overlapPolicy: RomOverlapPolicy = RomOverlapPolicy.DENY,
    ): List<RomWriteIntent> {
        require(before.size == romData.size) { "external mutation snapshot has the wrong ROM size" }
        val checkpoint = writes.size
        val resourceCheckpoint = resources.size
        val captured = diff(before, romData, owner, label, kind, overlapPolicy)
        restoreTransaction(before, checkpoint, resourceCheckpoint)
        try {
            for (intent in captured) add(intent)
        } catch (failure: Throwable) {
            restoreTransaction(before, checkpoint, resourceCheckpoint)
            throw failure
        }
        return captured
    }

    /**
     * Adopts a legacy mutation whose complete writes are known. Unlike a plain
     * diff, declared writes claim bytes whose final value happens to equal the
     * previous value (notably $FF bytes inside allocated payloads).
     */
    fun recordDeclaredMutation(
        before: ByteArray,
        declaredWrites: List<RomWriteIntent>,
    ) {
        require(before.size == romData.size) { "declared mutation snapshot has the wrong ROM size" }
        val checkpoint = writes.size
        val resourceCheckpoint = resources.size
        val after = romData.copyOf()
        val declaredByPc = mutableMapOf<Int, Int>()

        try {
            for (intent in declaredWrites) {
                validateIntentShape(intent)
                for (i in intent.bytes.indices) {
                    val pc = intent.offset + i
                    val previous = declaredByPc.put(pc, intent.bytes[i])
                    require(previous == null || previous == intent.bytes[i]) {
                        "declared mutation contains conflicting final bytes at PC ${pc.hex6()}"
                    }
                    val actual = after[headerSize + pc].toInt() and 0xFF
                    require(actual == intent.bytes[i]) {
                        "declared mutation '${intent.owner}/${intent.label}' says PC ${pc.hex6()} becomes " +
                            "${intent.bytes[i].hex2()}, but legacy writer produced ${actual.hex2()}"
                    }
                }
            }
            for (fileOffset in headerSize until after.size) {
                if (before[fileOffset] == after[fileOffset]) continue
                val pc = fileOffset - headerSize
                require(pc in declaredByPc) {
                    "legacy writer changed undeclared PC ${pc.hex6()}"
                }
            }
        } catch (failure: Throwable) {
            restoreTransaction(before, checkpoint, resourceCheckpoint)
            throw failure
        }

        restoreTransaction(before, checkpoint, resourceCheckpoint)
        try {
            for (intent in declaredWrites) add(intent)
        } catch (failure: Throwable) {
            restoreTransaction(before, checkpoint, resourceCheckpoint)
            throw failure
        }
    }

    fun claimResource(claim: RomResourceClaim) {
        require(claim.owner.isNotBlank()) { "resource owner must not be blank" }
        require(claim.namespace.isNotBlank()) { "resource namespace must not be blank" }
        require(claim.start >= 0 && claim.endInclusive >= claim.start) {
            "invalid ${claim.namespace} resource range ${claim.start}..${claim.endInclusive}"
        }
        if (claim.access == RomResourceAccess.SHARED) {
            require(!claim.sharedGroup.isNullOrBlank()) { "shared resource claims require a sharedGroup" }
        }

        for (prior in resources) {
            if (prior.namespace != claim.namespace || prior.owner == claim.owner) continue
            if (prior.endInclusive < claim.start || claim.endInclusive < prior.start) continue
            val compatibleShared =
                prior.access == RomResourceAccess.SHARED &&
                    claim.access == RomResourceAccess.SHARED &&
                    prior.sharedGroup == claim.sharedGroup
            if (!compatibleShared) {
                val overlapStart = maxOf(prior.start, claim.start)
                val overlapEnd = minOf(prior.endInclusive, claim.endInclusive)
                throw RomResourceConflictException(
                    "${claim.namespace} resource conflict at ${overlapStart.hex4()}-${overlapEnd.hex4()}: " +
                        "'${claim.owner}' (${claim.label}) overlaps '${prior.owner}' (${prior.label})"
                )
            }
        }
        resources.add(claim)
    }

    /**
     * Claims a range that has already been populated in the working ROM. This
     * is primarily for allocator results: a diff cannot see payload bytes that
     * legitimately remain $FF, but those bytes still belong to the allocation.
     */
    fun claimCurrentRange(
        owner: String,
        label: String,
        offset: Int,
        size: Int,
        kind: RomWriteKind = RomWriteKind.ALLOCATION,
    ) {
        require(size > 0) { "claimed ROM range must be non-empty" }
        val fileOffset = offset + headerSize
        if (offset < 0 || fileOffset < headerSize || fileOffset.toLong() + size > romData.size) {
            throw RomWriteBoundsException(
                "$owner/$label claim is outside ROM bounds: PC ${offset.hex6()}, $size bytes"
            )
        }
        val current = (fileOffset until fileOffset + size).map { romData[it].toInt() and 0xFF }
        add(
            owner = owner,
            label = label,
            offset = offset,
            bytes = current,
            kind = kind,
            expectedBefore = current,
            overlapPolicy = RomOverlapPolicy.ALLOW_IDENTICAL_SAME_OWNER,
        )
    }

    fun finalRom(): ByteArray = romData.copyOf()

    fun report(): RomWritePlanReport {
        val ownerSummaries = writes.groupBy { it.owner }.map { (owner, ownerWrites) ->
            RomWriteOwnerSummary(
                owner = owner,
                writes = ownerWrites.size,
                bytes = ownerWrites.sumOf { it.bytes.size },
                startOffset = ownerWrites.minOf { it.offset },
                endInclusive = ownerWrites.maxOf { it.endInclusive },
            )
        }.sortedBy { it.owner }
        return RomWritePlanReport(
            writes = writes.toList(),
            resources = resources.toList(),
            owners = ownerSummaries,
            unverifiedFixedWrites = writes.filter { it.preconditionRecommended && it.expectedBefore == null },
        )
    }

    private fun validateIntentShape(intent: RomWriteIntent) {
        require(intent.owner.isNotBlank()) { "ROM write owner must not be blank" }
        require(intent.label.isNotBlank()) { "ROM write label must not be blank" }
        if (intent.bytes.isEmpty()) {
            throw RomWriteBoundsException("${intent.owner}/${intent.label} contains no bytes")
        }
        val end = intent.offset.toLong() + intent.bytes.size
        if (intent.offset < 0 || end > bodySize || end - 1 > MAX_IPS_OFFSET) {
            throw RomWriteBoundsException(
                "${intent.owner}/${intent.label} is outside ROM bounds: PC ${intent.offset.hex6()}, " +
                    "${intent.bytes.size} bytes, body size ${bodySize.hex6()}"
            )
        }
        val invalid = intent.bytes.indexOfFirst { it !in 0..0xFF }
        if (invalid >= 0) {
            throw RomWriteBoundsException(
                "${intent.owner}/${intent.label} contains invalid byte ${intent.bytes[invalid]} at index $invalid"
            )
        }
        intent.expectedBefore?.let { expected ->
            val invalidExpected = expected.indexOfFirst { it !in 0..0xFF }
            require(invalidExpected < 0) {
                "${intent.owner}/${intent.label} contains invalid expected byte ${expected[invalidExpected]}"
            }
        }
    }

    private fun diff(
        before: ByteArray,
        after: ByteArray,
        owner: String,
        label: String,
        kind: RomWriteKind,
        overlapPolicy: RomOverlapPolicy,
    ): List<RomWriteIntent> {
        for (index in 0 until headerSize) {
            if (before[index] != after[index]) {
                throw RomWriteBoundsException("$owner/$label attempted to modify the copier header at file offset $index")
            }
        }

        val result = mutableListOf<RomWriteIntent>()
        var fileIndex = headerSize
        while (fileIndex < after.size) {
            if (before[fileIndex] == after[fileIndex]) {
                fileIndex++
                continue
            }
            val start = fileIndex
            while (
                fileIndex < after.size &&
                before[fileIndex] != after[fileIndex] &&
                fileIndex - start < MAX_CAPTURE_SIZE
            ) {
                fileIndex++
            }
            val bytes = (start until fileIndex).map { after[it].toInt() and 0xFF }
            val expected = (start until fileIndex).map { before[it].toInt() and 0xFF }
            result.add(
                RomWriteIntent(
                    owner = owner,
                    label = label,
                    offset = start - headerSize,
                    bytes = bytes,
                    kind = kind,
                    expectedBefore = expected,
                    overlapPolicy = overlapPolicy,
                )
            )
        }
        return result
    }

    private fun restoreTransaction(before: ByteArray, writeCheckpoint: Int, resourceCheckpoint: Int) {
        before.copyInto(romData)
        while (writes.size > writeCheckpoint) writes.removeAt(writes.lastIndex)
        while (resources.size > resourceCheckpoint) resources.removeAt(resources.lastIndex)
        claimedByWriteIndex.fill(UNCLAIMED)
        for ((writeIndex, intent) in writes.withIndex()) {
            for (pc in intent.offset..intent.endInclusive) claimedByWriteIndex[pc] = writeIndex
        }
    }

    private fun Int.hex2(): String = "0x" + toString(16).uppercase().padStart(2, '0')
    private fun Int.hex4(): String = "0x" + toString(16).uppercase().padStart(4, '0')
    private fun Int.hex6(): String = "0x" + toString(16).uppercase().padStart(6, '0')

    private companion object {
        const val UNCLAIMED = -1
        const val MAX_IPS_OFFSET = 0xFFFFFFL
        const val MAX_CAPTURE_SIZE = 0xFFFF
    }
}

private fun Int.hex4(): String = "0x" + toString(16).uppercase().padStart(4, '0')
private fun Int.hex6(): String = "0x" + toString(16).uppercase().padStart(6, '0')
