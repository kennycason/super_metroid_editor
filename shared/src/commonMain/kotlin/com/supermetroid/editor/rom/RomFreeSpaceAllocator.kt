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
    /** Full live source ranges, including bytes which happen to equal erased fill. */
    private val excludedRanges: List<IntRange> = emptyList(),
) {
    private class SessionState {
        val nextFreeByBank = mutableMapOf<Int, Int>()
        val protectedRanges = mutableListOf<IntRange>()
        val reservations = mutableListOf<RomAllocation>()
    }

    private var sessionState = SessionState()

    private constructor(
        romData: ByteArray,
        snesToPc: (Int) -> Int,
        pcToSnes: (Int) -> Int,
        guardBytes: Int,
        freeFillByteForBank: (Int) -> Int,
        onReserve: (RomAllocation) -> Unit,
        sessionState: SessionState,
        excludedRanges: List<IntRange>,
    ) : this(romData, snesToPc, pcToSnes, guardBytes, freeFillByteForBank, onReserve, excludedRanges) {
        this.sessionState = sessionState
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
            sessionState = sessionState,
            excludedRanges = excludedRanges,
        )

    /** Protect live source bytes for this entire export session, including existing observer views. */
    fun protectRanges(ranges: List<IntRange>) {
        val live = ranges.filterNot { it.isEmpty() }
        require(live.all { it.first >= 0 && it.last < romData.size }) { "Protected ROM range is outside ROM bounds" }
        require(sessionState.reservations.none { allocation -> live.any {
            allocation.pcOffset <= it.last && allocation.pcOffset + allocation.size > it.first
        } }) { "Protected ROM range overlaps an existing allocation" }
        sessionState.protectedRanges += live
    }

    fun reserve(
        size: Int,
        banks: List<Int>,
        label: String,
        alignment: Int = 1,
    ): RomAllocation? {
        require(size > 0) { "allocation size must be positive" }
        require(alignment > 0) { "alignment must be positive" }

        for (bank in banks) {
            val allocation = reserveInBank(bank, size, label, alignment)
            if (allocation != null) return allocation
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

    fun write(allocation: RomAllocation, bytes: ByteArray) {
        require(bytes.size == allocation.size) {
            "allocation ${allocation.label} expects ${allocation.size} bytes, got ${bytes.size}"
        }
        require(allocation.pcOffset >= 0 && allocation.pcOffset + bytes.size <= romData.size) {
            "allocation ${allocation.label} is outside ROM bounds"
        }
        val freeFillByte = freeFillByteForBank(allocation.bank).also {
            require(it in 0..0xFF) { "free-space fill byte for bank must be 0..255, got $it" }
        }
        for (i in bytes.indices) {
            val existing = romData[allocation.pcOffset + i].toInt() and 0xFF
            require(existing == freeFillByte) {
                "allocation ${allocation.label} overlaps used ROM byte at PC ${allocation.pcOffset + i}"
            }
        }
        bytes.copyInto(romData, allocation.pcOffset)
    }

    private fun reserveInBank(
        bank: Int,
        size: Int,
        label: String,
        alignment: Int,
    ): RomAllocation? {
        val bankStart = snesToPc((bank shl 16) or 0x8000)
        val bankEndExclusive = snesToPc((bank shl 16) or 0xFFFF) + 1
        if (bankStart !in romData.indices || bankEndExclusive > romData.size || bankStart >= bankEndExclusive) {
            return null
        }

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
        ).also { sessionState.reservations += it; onReserve(it) }
    }

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

    private fun alignUp(value: Int, alignment: Int): Int {
        val remainder = value % alignment
        return if (remainder == 0) value else value + (alignment - remainder)
    }
}

data class RomAllocation(
    val bank: Int,
    val pcOffset: Int,
    val snesAddress: Int,
    val size: Int,
    val label: String,
)
