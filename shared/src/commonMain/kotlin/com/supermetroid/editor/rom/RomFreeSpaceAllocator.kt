package com.supermetroid.editor.rom

/**
 * Allocates contiguous bytes from the trailing $FF-filled free space in LoROM banks.
 *
 * The allocator scans the current ROM data, reserves bytes, and advances an in-memory cursor per bank.
 * Callers should write allocations immediately during export so later free-space scanners see them as used.
 */
class RomFreeSpaceAllocator(
    private val romData: ByteArray,
    private val snesToPc: (Int) -> Int,
    private val pcToSnes: (Int) -> Int,
    private val guardBytes: Int = 1,
    /** Vanilla banks use $FF; expanded formats may define a different erased byte. */
    private val freeFillByteForBank: (Int) -> Int = { 0xFF },
    private val onReserve: (RomAllocation) -> Unit = {},
    /** File offset corresponding to canonical headerless PC 0. Alignment is
     * defined in canonical ROM coordinates, never against a copier header. */
    private val pcAlignmentOrigin: Int = 0,
    /** Full live source ranges, including bytes which happen to equal erased fill. */
    private val excludedRanges: List<IntRange> = emptyList(),
    /** Audited interior ranges. When present for a bank, allocation is limited
     * to these ranges instead of guessing from the bank's trailing bytes. */
    private val verifiedFreeRanges: List<RomVerifiedFreeRange> = emptyList(),
) {
    private class SessionState {
        val nextFreeByBank = mutableMapOf<Int, Int>()
        val protectedRanges = mutableListOf<IntRange>()
        val reservations = mutableListOf<RomAllocation>()
    }

    private class SegmentScope(
        val owner: String,
        val kind: RomSegmentKind,
        val idPrefix: String,
        val constraint: String?,
    ) {
        private val counts = mutableMapOf<String, Int>()

        fun request(size: Int, banks: List<Int>, label: String, alignment: Int): RomSegmentRequest {
            val token = label.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
                .trim('-').replace(Regex("-+"), "-").take(80).ifBlank { "segment" }
            val base = "$idPrefix/$token"
            val occurrence = counts.getOrPut(base) { 0 }.also { counts[base] = it + 1 }
            return RomSegmentRequest(
                id = if (occurrence == 0) base else "$base-${occurrence + 1}",
                owner = owner,
                kind = kind,
                label = label,
                size = size,
                alignment = alignment,
                allowedBanks = banks.toList(),
                constraint = constraint,
            ).validate()
        }
    }

    private var sessionState = SessionState()
    private var segmentScope: SegmentScope? = null
    private val verifiedCatalogActive: Boolean get() = verifiedFreeRanges.isNotEmpty()

    private constructor(
        romData: ByteArray,
        snesToPc: (Int) -> Int,
        pcToSnes: (Int) -> Int,
        guardBytes: Int,
        freeFillByteForBank: (Int) -> Int,
        onReserve: (RomAllocation) -> Unit,
        pcAlignmentOrigin: Int,
        sessionState: SessionState,
        excludedRanges: List<IntRange>,
        verifiedFreeRanges: List<RomVerifiedFreeRange>,
        segmentScope: SegmentScope?,
    ) : this(
        romData, snesToPc, pcToSnes, guardBytes, freeFillByteForBank, onReserve, pcAlignmentOrigin,
        excludedRanges, verifiedFreeRanges,
    ) {
        this.sessionState = sessionState
        this.segmentScope = segmentScope
    }

    init {
        require(pcAlignmentOrigin in 0..romData.size) { "Invalid ROM alignment origin" }
        require(verifiedFreeRanges.all { range ->
            range.bank in 0x80..0xFF && range.pcStart >= 0 &&
                range.pcEndExclusive > range.pcStart && range.pcEndExclusive <= romData.size &&
                range.fillByte in 0..0xFF && range.label.isNotBlank()
        }) { "Invalid verified ROM free-space range" }
        require(verifiedFreeRanges.sortedBy(RomVerifiedFreeRange::pcStart).zipWithNext().all { (a, b) ->
            a.pcEndExclusive <= b.pcStart
        }) { "Verified ROM free-space ranges overlap" }
    }

    /**
     * Creates a view that shares this allocator's bank cursors while reporting
     * reservations to an additional consumer. This lets each exporter retain
     * precise ownership metadata without starting an independent free-space
     * scan that could select the same bytes.
     */
    fun observing(observer: (RomAllocation) -> Unit): RomFreeSpaceAllocator =
        RomFreeSpaceAllocator(
            romData = romData,
            snesToPc = snesToPc,
            pcToSnes = pcToSnes,
            guardBytes = guardBytes,
            freeFillByteForBank = freeFillByteForBank,
            onReserve = { allocation ->
                onReserve(allocation)
                observer(allocation)
            },
            pcAlignmentOrigin = pcAlignmentOrigin,
            sessionState = sessionState,
            excludedRanges = excludedRanges,
            verifiedFreeRanges = verifiedFreeRanges,
            segmentScope = segmentScope,
        )

    /**
     * Creates an isolated allocator for a private planning ROM. Existing
     * reservations and protected ranges are copied as blockers; new placements
     * are reported through [observer] and can be adopted into this session only
     * after the complete planned image validates.
     */
    fun forkForPlanning(
        planningRomData: ByteArray,
        observer: (RomAllocation) -> Unit = {},
    ): RomFreeSpaceAllocator {
        require(planningRomData.size == romData.size) { "Planning ROM has a different size" }
        val fork = RomFreeSpaceAllocator(
            romData = planningRomData,
            snesToPc = snesToPc,
            pcToSnes = pcToSnes,
            guardBytes = guardBytes,
            freeFillByteForBank = freeFillByteForBank,
            onReserve = observer,
            pcAlignmentOrigin = pcAlignmentOrigin,
            excludedRanges = excludedRanges,
            verifiedFreeRanges = verifiedFreeRanges,
        )
        fork.sessionState.protectedRanges += sessionState.protectedRanges
        sessionState.reservations.forEach(fork::adopt)
        return fork
    }

    /** Commits blockers and new typed placements from [planning] after its
     * private ROM image has been accepted by the write plan. */
    fun adoptPlanning(
        planning: RomFreeSpaceAllocator,
        allocations: List<RomAllocation>,
    ) {
        require(planning.romData.size == romData.size) { "Planning allocator belongs to a different ROM" }
        val protectionCheckpoint = sessionState.protectedRanges.size
        val reservationCheckpoint = sessionState.reservations.size
        val cursorCheckpoint = sessionState.nextFreeByBank.toMap()
        val newProtection = planning.sessionState.protectedRanges - sessionState.protectedRanges.toSet()
        try {
            require(tryProtectRanges(newProtection)) { "Planned protected ROM range overlaps an existing allocation" }
            allocations.forEach(::adopt)
        } catch (failure: Throwable) {
            while (sessionState.protectedRanges.size > protectionCheckpoint) sessionState.protectedRanges.removeAt(sessionState.protectedRanges.lastIndex)
            while (sessionState.reservations.size > reservationCheckpoint) sessionState.reservations.removeAt(sessionState.reservations.lastIndex)
            sessionState.nextFreeByBank.clear()
            sessionState.nextFreeByBank.putAll(cursorCheckpoint)
            throw failure
        }
    }

    /** Adds typed intent to legacy allocation calls without creating another
     * free-space session. Nested observers retain this scope. */
    fun scoped(
        owner: String,
        kind: RomSegmentKind,
        idPrefix: String = owner,
        constraint: String? = null,
    ): RomFreeSpaceAllocator {
        require(owner.isNotBlank() && idPrefix.isNotBlank()) { "Segment scope needs an owner and ID prefix" }
        return RomFreeSpaceAllocator(
            romData = romData,
            snesToPc = snesToPc,
            pcToSnes = pcToSnes,
            guardBytes = guardBytes,
            freeFillByteForBank = freeFillByteForBank,
            onReserve = onReserve,
            pcAlignmentOrigin = pcAlignmentOrigin,
            sessionState = sessionState,
            excludedRanges = excludedRanges,
            verifiedFreeRanges = verifiedFreeRanges,
            segmentScope = SegmentScope(owner, kind, idPrefix, constraint),
        )
    }

    /** Protect live source bytes for this entire export session, including existing observer views. */
    fun protectRanges(ranges: List<IntRange>) {
        require(tryProtectRanges(ranges)) { "Protected ROM range overlaps an existing allocation" }
    }

    /** Protect ranges only when no earlier reservation already owns any of their bytes. */
    fun tryProtectRanges(ranges: List<IntRange>): Boolean {
        val live = ranges.filterNot { it.isEmpty() }
        require(live.all { it.first >= 0 && it.last < romData.size }) { "Protected ROM range is outside ROM bounds" }
        if (sessionState.reservations.any { allocation -> live.any {
            allocation.pcOffset <= it.last && allocation.pcOffset + allocation.size > it.first
        } }) return false
        sessionState.protectedRanges += live
        return true
    }

    fun reserve(
        size: Int,
        banks: List<Int>,
        label: String,
        alignment: Int = 1,
    ): RomAllocation? {
        require(size > 0) { "allocation size must be positive" }
        require(alignment > 0) { "alignment must be positive" }

        val request = segmentScope?.request(size, banks, label, alignment)
        return reserve(size, banks, label, alignment, request)
    }

    /**
     * Reserves from a typed scope and preserves the registry's complete failure
     * evidence. Production exporters should use this instead of turning a null
     * result into a generic "no space" error.
     */
    fun reserveOrThrow(
        size: Int,
        banks: List<Int>,
        label: String,
        alignment: Int = 1,
    ): RomAllocation {
        require(size > 0) { "allocation size must be positive" }
        require(alignment > 0) { "alignment must be positive" }
        val request = requireNotNull(segmentScope) {
            "reserveOrThrow requires a typed allocator scope"
        }.request(size, banks, label, alignment)
        return reserve(size, banks, label, alignment, request)
            ?: throw RomSegmentAllocationException(RomSegmentAllocationRegistry(this).failure(request))
    }

    fun reserve(request: RomSegmentRequest): RomAllocation? {
        val checked = request.validate()
        return reserve(checked.size, checked.allowedBanks, checked.label, checked.alignment, checked)
    }

    private fun reserve(
        size: Int,
        banks: List<Int>,
        label: String,
        alignment: Int,
        request: RomSegmentRequest?,
    ): RomAllocation? {
        for (bank in banks) {
            val allocation = reserveInBank(bank, size, label, alignment, request)
            if (allocation != null) return allocation
        }
        if (request != null) {
            throw RomSegmentAllocationException(RomSegmentAllocationRegistry(this).failure(request))
        }
        return null
    }

    fun allocate(
        bytes: ByteArray,
        banks: List<Int>,
        label: String,
        alignment: Int = 1,
    ): RomAllocation? {
        val allocation = reserve(bytes.size, banks, label, alignment) ?: return null
        write(allocation, bytes)
        return allocation
    }

    /** Typed, diagnostic-preserving counterpart to [allocate]. */
    fun allocateOrThrow(
        bytes: ByteArray,
        banks: List<Int>,
        label: String,
        alignment: Int = 1,
    ): RomAllocation {
        val allocation = reserveOrThrow(bytes.size, banks, label, alignment)
        write(allocation, bytes)
        return allocation
    }

    fun allocate(request: RomSegmentRequest, bytes: ByteArray): RomAllocation? {
        require(bytes.size == request.size) { "${request.id} expected ${request.size} bytes, got ${bytes.size}" }
        val allocation = reserve(request) ?: return null
        write(allocation, bytes)
        return allocation
    }

    /** Adopts an already compiled placement into this exact session. */
    fun adopt(allocation: RomAllocation) {
        val request = requireNotNull(allocation.request) { "Adopted ROM allocations require typed segment intent" }.validate()
        require(allocation.size == request.size && allocation.bank in request.allowedBanks &&
            allocation.canonicalPcOffset % request.alignment == 0) {
            "${request.id} placement violates its canonical ROM alignment"
        }
        require(allocation.pcOffset >= 0 && allocation.pcOffset + allocation.size <= romData.size) {
            "${request.id} placement is outside ROM bounds"
        }
        require(sessionState.reservations.none { prior ->
            allocation.pcOffset < prior.pcOffset + prior.size && prior.pcOffset < allocation.pcOffset + allocation.size
        }) { "${request.id} overlaps another adopted or planned ROM segment" }
        if (verifiedCatalogActive) require(verifiedFreeRanges.any { range ->
            range.bank == allocation.bank && allocation.pcOffset >= range.pcStart &&
                allocation.pcOffset + allocation.size <= range.pcEndExclusive
        }) { "${request.id} is outside verified ROM space" }
        sessionState.reservations += allocation
        if (!verifiedCatalogActive) {
            sessionState.nextFreeByBank[allocation.bank] = maxOf(
                sessionState.nextFreeByBank[allocation.bank] ?: allocation.pcOffset,
                allocation.pcOffset + allocation.size,
            )
        }
    }

    fun write(allocation: RomAllocation, bytes: ByteArray) {
        validateWrite(allocation, bytes)
        bytes.copyInto(romData, allocation.pcOffset)
    }

    /** Read-only validation so a multi-allocation transaction can check every write before committing. */
    fun validateWrite(allocation: RomAllocation, bytes: ByteArray) {
        require(bytes.size == allocation.size) {
            "allocation ${allocation.label} expects ${allocation.size} bytes, got ${bytes.size}"
        }
        require(allocation.pcOffset >= 0 && allocation.pcOffset + bytes.size <= romData.size) {
            "allocation ${allocation.label} is outside ROM bounds"
        }
        val freeFillByte = (allocation.fillByte ?: freeFillByteForBank(allocation.bank)).also {
            require(it in 0..0xFF) { "free-space fill byte for bank must be 0..255, got $it" }
        }
        for (i in bytes.indices) {
            val existing = romData[allocation.pcOffset + i].toInt() and 0xFF
            require(existing == freeFillByte) {
                "allocation ${allocation.label} overlaps used ROM byte at PC ${allocation.pcOffset + i}"
            }
        }
    }

    /** A read-only view of the exact shared allocator session. Inspecting space
     * never initializes a bank cursor or reserves bytes. */
    fun snapshot(banks: Iterable<Int>): RomFreeSpaceSnapshot {
        val requested = banks.distinct()
        val bankSet = requested.toSet()
        return RomFreeSpaceSnapshot(
            banks = requested.mapNotNull(::inspectBank),
            allocations = sessionState.reservations.filter { it.bank in bankSet }.toList(),
        )
    }

    /** Capacity before this session's reservations. Already compiled segment
     * bytes are treated as their verified fill byte only within their exact
     * adopted ranges; unrelated authored bytes remain occupied. */
    fun baselineSnapshot(banks: Iterable<Int>): RomFreeSpaceSnapshot {
        val requested = banks.distinct()
        return RomFreeSpaceSnapshot(
            banks = requested.mapNotNull { inspectBank(it, beforeReservations = true) },
            allocations = emptyList(),
        )
    }

    /** Final production guard: no reservation may escape ownership reporting. */
    fun requireTypedReservations() {
        val anonymous = sessionState.reservations.filter { it.request == null }
        require(anonymous.isEmpty()) {
            "ROM allocation registry contains ${anonymous.size} untyped reservation(s): " +
                anonymous.joinToString { "${it.label} (${it.size} bytes in bank \$${it.bank.toString(16).uppercase()})" }
        }
    }

    private fun inspectBank(bank: Int, beforeReservations: Boolean = false): RomFreeSpaceBank? {
        val (bankStart, bankEndExclusive) = bankBounds(bank) ?: return null
        val verified = verifiedFreeRanges.filter { it.bank == bank }
        if (verified.isNotEmpty()) {
            val ranges = verifiedSegments(bank, beforeReservations)
            val first = ranges.firstOrNull()
            return RomFreeSpaceBank(
                bank = bank,
                pcStart = bankStart,
                pcEndExclusive = bankEndExclusive,
                nextFreePcOffset = first?.pcStart ?: bankEndExclusive,
                availableBytes = ranges.maxOfOrNull(RomFreeSpaceRange::length) ?: 0,
                fillByte = first?.fillByte ?: verified.first().fillByte,
                ranges = ranges,
            )
        }
        if (verifiedCatalogActive) {
            val fillByte = freeFillByteForBank(bank).also {
                require(it in 0..0xFF) { "free-space fill byte for bank must be 0..255, got $it" }
            }
            return RomFreeSpaceBank(
                bank = bank,
                pcStart = bankStart,
                pcEndExclusive = bankEndExclusive,
                nextFreePcOffset = bankEndExclusive,
                availableBytes = 0,
                fillByte = fillByte,
                ranges = emptyList(),
            )
        }
        val freeFillByte = freeFillByteForBank(bank).also {
            require(it in 0..0xFF) { "free-space fill byte for bank must be 0..255, got $it" }
        }
        val rawCursor = sessionState.nextFreeByBank[bank]
            ?: scanTrailingFreeStart(bankStart, bankEndExclusive, freeFillByte)
        val protectedEnd = (excludedRanges + sessionState.protectedRanges)
            .filter { !it.isEmpty() && it.first < bankEndExclusive && it.last >= bankStart }
            .maxOfOrNull { minOf(it.last + 1, bankEndExclusive) }
        val cursor = maxOf(
            rawCursor,
            protectedEnd?.let { (it + guardBytes).coerceAtMost(bankEndExclusive) } ?: bankStart,
        )
        var availableEnd = cursor
        while (
            availableEnd < bankEndExclusive &&
            (romData[availableEnd].toInt() and 0xFF) == freeFillByte &&
            !isProtected(availableEnd)
        ) {
            availableEnd++
        }
        val availableBytes = availableEnd - cursor
        return RomFreeSpaceBank(
            bank = bank,
            pcStart = bankStart,
            pcEndExclusive = bankEndExclusive,
            nextFreePcOffset = cursor,
            availableBytes = availableBytes,
            fillByte = freeFillByte,
            ranges = if (availableBytes > 0) {
                listOf(RomFreeSpaceRange(cursor, availableEnd, freeFillByte, "Trailing bank space"))
            } else emptyList(),
        )
    }

    private fun reserveInBank(
        bank: Int,
        size: Int,
        label: String,
        alignment: Int,
        request: RomSegmentRequest?,
    ): RomAllocation? {
        if (verifiedCatalogActive) {
            val segment = verifiedSegments(bank).firstOrNull { range ->
                val start = alignUp(range.pcStart, alignment)
                start + size <= range.pcEndExclusive
            } ?: return null
            val pcOffset = alignUp(segment.pcStart, alignment)
            return RomAllocation(
                bank = bank,
                pcOffset = pcOffset,
                snesAddress = pcToSnes(pcOffset),
                size = size,
                label = label,
                fillByte = segment.fillByte,
                request = request,
                canonicalPcOffset = pcOffset - pcAlignmentOrigin,
            ).also { sessionState.reservations += it; onReserve(it) }
        }
        val (bankStart, bankEndExclusive) = bankBounds(bank) ?: return null

        val freeFillByte = freeFillByteForBank(bank).also {
            require(it in 0..0xFF) { "free-space fill byte for bank must be 0..255, got $it" }
        }
        val rawCursor = sessionState.nextFreeByBank.getOrPut(bank) {
            scanTrailingFreeStart(bankStart, bankEndExclusive, freeFillByte)
        }
        // New protection may arrive after another exporter initialized this cursor.
        // Keep its reservations, but move beyond all newly discovered live bytes.
        val protectedEnd = (excludedRanges + sessionState.protectedRanges)
            .filter { !it.isEmpty() && it.first < bankEndExclusive && it.last >= bankStart }
            .maxOfOrNull { minOf(it.last + 1, bankEndExclusive) }
        val cursor = alignUp(maxOf(rawCursor,
            protectedEnd?.let { (it + guardBytes).coerceAtMost(bankEndExclusive) } ?: bankStart), alignment)
        if (cursor + size > bankEndExclusive) return null
        for (pc in cursor until cursor + size) {
            if ((romData[pc].toInt() and 0xFF) != freeFillByte || isProtected(pc)) return null
        }

        sessionState.nextFreeByBank[bank] = cursor + size
        return RomAllocation(
            bank = bank,
            pcOffset = cursor,
            snesAddress = pcToSnes(cursor),
            size = size,
            label = label,
            request = request,
            canonicalPcOffset = cursor - pcAlignmentOrigin,
        ).also { sessionState.reservations += it; onReserve(it) }
    }

    /** Every returned segment is both source-verified and currently writable.
     * Protection and unwritten reservations split ranges instead of causing the
     * whole bank to be discarded, exposing real fragmentation to the planner. */
    private fun verifiedSegments(bank: Int, beforeReservations: Boolean = false): List<RomFreeSpaceRange> = buildList {
        val sourceBlockers = excludedRanges + sessionState.protectedRanges
        val reservationBlockers = if (beforeReservations) emptyList() else sessionState.reservations.map {
            it.pcOffset until it.pcOffset + it.size
        }
        val blockers = sourceBlockers + reservationBlockers
        verifiedFreeRanges.asSequence().filter { it.bank == bank }.sortedBy { it.pcStart }.forEach { verified ->
            var cursor = verified.pcStart
            while (cursor < verified.pcEndExclusive) {
                while (cursor < verified.pcEndExclusive && !verifiedByteAvailable(cursor, verified.fillByte, blockers, beforeReservations)) cursor++
                val rawStart = cursor
                while (cursor < verified.pcEndExclusive && verifiedByteAvailable(cursor, verified.fillByte, blockers, beforeReservations)) cursor++
                if (cursor > rawStart) {
                    // Guard the source-declared edge and unknown/live source
                    // blockers. Session reservations already own an exact end;
                    // adding a fresh guard after every allocation would leak
                    // bytes and make repeated planning disagree with placement.
                    val needsGuard = rawStart == verified.pcStart || rawStart <= 0 ||
                        sourceBlockers.any { rawStart - 1 in it } ||
                        reservationBlockers.none { rawStart - 1 in it }
                    val guardedStart = (rawStart + if (needsGuard) guardBytes else 0).coerceAtMost(cursor)
                    if (guardedStart < cursor) add(
                        RomFreeSpaceRange(
                            pcStart = guardedStart,
                            pcEndExclusive = cursor,
                            fillByte = verified.fillByte,
                            label = verified.label,
                        ),
                    )
                }
            }
        }
    }

    private fun verifiedByteAvailable(
        pc: Int,
        fillByte: Int,
        blockers: List<IntRange>,
        beforeReservations: Boolean,
    ): Boolean = ((romData[pc].toInt() and 0xFF) == fillByte || beforeReservations &&
        sessionState.reservations.any { allocation ->
            pc in allocation.pcOffset until allocation.pcOffset + allocation.size &&
                (allocation.fillByte ?: fillByte) == fillByte
        }) && blockers.none { pc in it }

    private fun scanTrailingFreeStart(bankStart: Int, bankEndExclusive: Int, freeFillByte: Int): Int {
        var firstTrailingFree = bankEndExclusive
        while (
            firstTrailingFree > bankStart &&
            (romData[firstTrailingFree - 1].toInt() and 0xFF) == freeFillByte
            && !isProtected(firstTrailingFree - 1)
        ) {
            firstTrailingFree--
        }
        return (firstTrailingFree + guardBytes).coerceAtMost(bankEndExclusive)
    }

    private fun isProtected(pc: Int) = excludedRanges.any { pc in it } || sessionState.protectedRanges.any { pc in it }

    private fun bankBounds(bank: Int): Pair<Int, Int>? {
        val bankStart = snesToPc((bank shl 16) or 0x8000)
        val bankEndExclusive = snesToPc((bank shl 16) or 0xFFFF) + 1
        return if (
            bankStart in romData.indices && bankEndExclusive <= romData.size && bankStart < bankEndExclusive
        ) bankStart to bankEndExclusive else null
    }

    private fun alignUp(value: Int, alignment: Int): Int {
        val relative = value - pcAlignmentOrigin
        require(relative >= 0) { "ROM offset precedes its alignment origin" }
        val remainder = relative % alignment
        return if (remainder == 0) value else value + (alignment - remainder)
    }

    internal fun alignedCapacity(start: Int, endExclusive: Int, alignment: Int): Int =
        (endExclusive - alignUp(start, alignment)).coerceAtLeast(0)
}

data class RomAllocation(
    val bank: Int,
    val pcOffset: Int,
    val snesAddress: Int,
    val size: Int,
    val label: String,
    /** Exact erased byte for a verified interior range; null uses the bank policy. */
    val fillByte: Int? = null,
    val request: RomSegmentRequest? = null,
    /** Headerless PC coordinate used for typed alignment and durable reports. */
    val canonicalPcOffset: Int = pcOffset,
)

/** Source-audited allocation envelope, expressed as canonical headerless PC offsets. */
data class RomVerifiedFreeRange(
    val bank: Int,
    val pcStart: Int,
    val pcEndExclusive: Int,
    val fillByte: Int,
    val label: String,
) {
    val length: Int get() = pcEndExclusive - pcStart
}

/** One currently usable fragment inside a verified envelope. */
data class RomFreeSpaceRange(
    val pcStart: Int,
    val pcEndExclusive: Int,
    val fillByte: Int,
    val label: String,
) {
    val length: Int get() = pcEndExclusive - pcStart
}

data class RomFreeSpaceBank(
    val bank: Int,
    /** File offset, including a copier header when the allocator's ROM has one. */
    val pcStart: Int,
    val pcEndExclusive: Int,
    val nextFreePcOffset: Int,
    /** Contiguous bytes the next reservation can consume from [nextFreePcOffset]. */
    val availableBytes: Int,
    val fillByte: Int,
    val ranges: List<RomFreeSpaceRange> = if (availableBytes > 0) {
        listOf(RomFreeSpaceRange(nextFreePcOffset, nextFreePcOffset + availableBytes, fillByte, "Trailing bank space"))
    } else emptyList(),
) {
    val capacityBytes: Int get() = pcEndExclusive - pcStart
    val totalAvailableBytes: Int get() = ranges.sumOf(RomFreeSpaceRange::length)
    val largestAvailableBytes: Int get() = ranges.maxOfOrNull(RomFreeSpaceRange::length) ?: 0
}

data class RomFreeSpaceSnapshot(
    val banks: List<RomFreeSpaceBank>,
    val allocations: List<RomAllocation>,
)
