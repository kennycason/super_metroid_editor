package com.supermetroid.editor.rom

/** Semantic role of a relocatable ROM segment. The kind is planning/reporting
 * metadata; every segment is still subject to the same allocation checks. */
enum class RomSegmentKind {
    ROOM_DATA,
    SOURCE_ASSET,
    GENERATED_PATCH,
    MANAGED_CODE,
    HOOK_HELPER,
    MUSIC,
    GRAPHICS,
    TEXT,
    CUSTOM_ASM,
    OTHER,
}

/** Durable placement intent. [id] identifies the logical segment and never
 * contains its compiled address. A build resolves the intent to a placement. */
data class RomSegmentRequest(
    val id: String,
    val owner: String,
    val kind: RomSegmentKind,
    val label: String,
    val size: Int,
    val alignment: Int = 1,
    val allowedBanks: List<Int>,
    /** Human-readable pointer/runtime restriction, retained by the planner. */
    val constraint: String? = null,
) {
    fun validate(): RomSegmentRequest = apply {
        require(id.isNotBlank() && id.length <= 240 && id.none { it == '\n' || it == '\r' }) {
            "ROM segment ID must be a non-blank single-line value"
        }
        require(owner.isNotBlank() && label.isNotBlank()) { "$id has a blank owner or label" }
        require(size > 0) { "$id allocation size must be positive" }
        require(alignment > 0) { "$id alignment must be positive" }
        require(allowedBanks.isNotEmpty() && allowedBanks.distinct().size == allowedBanks.size &&
            allowedBanks.all { it in 0x80..0xFF }) { "$id has invalid or duplicate LoROM banks" }
        require(constraint == null || constraint.isNotBlank()) { "$id has a blank constraint description" }
    }
}

data class RomSegmentPlacement(
    val request: RomSegmentRequest,
    val allocation: RomAllocation,
) {
    init {
        request.validate()
        require(allocation.request == request) { "${request.id} placement lost its allocation request" }
        require(allocation.size == request.size && allocation.bank in request.allowedBanks &&
            allocation.canonicalPcOffset % request.alignment == 0) { "${request.id} placement violates its request" }
    }
}

data class RomSegmentFailure(
    val request: RomSegmentRequest,
    val totalCompatibleBytes: Int,
    val largestCompatibleBytes: Int,
    val largestCompatibleBank: Int?,
    val blockingOwners: List<String>,
) {
    val fragmented: Boolean get() = totalCompatibleBytes >= request.size && largestCompatibleBytes < request.size
    val safeNextActions: List<String> get() = buildList {
        if (fragmented) {
            add("move or remove another planned segment in the ROM Space Planner so one compatible block is contiguous")
        } else {
            add("reduce this segment or release another allocation in one of its allowed banks")
        }
        if (request.allowedBanks.any { it in 0xE0..0xFF }) {
            add("use the project's supported 4 MiB capacity if it is not already enabled")
        }
        add("keep the declared pointer/bank constraint; do not hand-edit the generated address")
    }

    fun message(): String = buildString {
        append(request.label).append(" needs ").append(request.size).append(" contiguous bytes")
        append(" aligned to ").append(request.alignment)
        append(" in ").append(request.allowedBanks.joinToString { "\$" + it.toString(16).uppercase().padStart(2, '0') })
        if (largestCompatibleBank != null) {
            append("; largest compatible block is ").append(largestCompatibleBytes)
                .append(" bytes in bank \$").append(largestCompatibleBank.toString(16).uppercase().padStart(2, '0'))
            append(" (").append(totalCompatibleBytes).append(" total compatible bytes)")
        } else {
            append("; no verified compatible free range is available")
        }
        if (fragmented) append(". Compatible space is fragmented")
        if (blockingOwners.isNotEmpty()) append(". Planned owner(s) using those banks: ")
            .append(blockingOwners.joinToString())
        request.constraint?.let { append(". Constraint: ").append(it) }
        append(". Safe next steps: ").append(safeNextActions.joinToString("; "))
    }
}

class RomSegmentAllocationException(val failure: RomSegmentFailure) :
    IllegalStateException(failure.message())

/** Typed facade over one [RomFreeSpaceAllocator] session. All scopes and
 * observers share the same cursors and reservations. */
class RomSegmentAllocationRegistry(private val allocator: RomFreeSpaceAllocator) {
    fun reserve(request: RomSegmentRequest): RomSegmentPlacement {
        val checked = request.validate()
        val allocation = allocator.reserve(checked) ?: throw RomSegmentAllocationException(failure(checked))
        return RomSegmentPlacement(checked, allocation)
    }

    fun allocate(request: RomSegmentRequest, bytes: ByteArray): RomSegmentPlacement {
        require(bytes.size == request.size) {
            "${request.id} declares ${request.size} bytes but received ${bytes.size}"
        }
        val placement = reserve(request)
        allocator.write(placement.allocation, bytes)
        return placement
    }

    /** Registers a placement already emitted by a checked compile so later
     * exporters share its ownership and cannot allocate over it. */
    fun adopt(placement: RomSegmentPlacement, pcOffsetDelta: Int = 0) {
        require(pcOffsetDelta >= 0) { "Adopted ROM placement offset cannot be negative" }
        allocator.adopt(
            if (pcOffsetDelta == 0) placement.allocation
            else placement.allocation.copy(pcOffset = placement.allocation.pcOffset + pcOffsetDelta),
        )
    }

    fun scopedAllocator(
        owner: String,
        kind: RomSegmentKind,
        idPrefix: String = owner,
        constraint: String? = null,
    ): RomFreeSpaceAllocator = allocator.scoped(owner, kind, idPrefix, constraint)

    fun snapshot(banks: Iterable<Int>): RomFreeSpaceSnapshot = allocator.snapshot(banks)

    fun baselineSnapshot(banks: Iterable<Int>): RomFreeSpaceSnapshot = allocator.baselineSnapshot(banks)

    fun failure(request: RomSegmentRequest): RomSegmentFailure {
        val snapshot = allocator.snapshot(request.allowedBanks)
        val compatible = snapshot.banks.filter { it.bank in request.allowedBanks }
        val largest = compatible.maxByOrNull { bank ->
            bank.ranges.maxOfOrNull { range -> allocator.alignedCapacity(range.pcStart, range.pcEndExclusive, request.alignment) } ?: 0
        }
        val largestBytes = largest?.ranges?.maxOfOrNull { range ->
            allocator.alignedCapacity(range.pcStart, range.pcEndExclusive, request.alignment)
        } ?: 0
        val owners = snapshot.allocations.asSequence()
            .filter { it.bank in request.allowedBanks }
            .mapNotNull { it.request?.owner }
            .filter { it != request.owner }
            .distinct()
            .sorted()
            .toList()
        return RomSegmentFailure(
            request = request,
            totalCompatibleBytes = compatible.sumOf { bank ->
                bank.ranges.sumOf { range -> allocator.alignedCapacity(range.pcStart, range.pcEndExclusive, request.alignment) }
            },
            largestCompatibleBytes = largestBytes,
            largestCompatibleBank = largest?.bank,
            blockingOwners = owners,
        )
    }

}
