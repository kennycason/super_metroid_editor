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
) {
    private val nextFreeByBank = mutableMapOf<Int, Int>()

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
        for (i in bytes.indices) {
            val existing = romData[allocation.pcOffset + i].toInt() and 0xFF
            require(existing == 0xFF) {
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

        val rawCursor = nextFreeByBank.getOrPut(bank) {
            scanTrailingFreeStart(bankStart, bankEndExclusive)
        }
        val cursor = alignUp(rawCursor, alignment)
        if (cursor + size > bankEndExclusive) return null
        for (pc in cursor until cursor + size) {
            if ((romData[pc].toInt() and 0xFF) != 0xFF) return null
        }

        nextFreeByBank[bank] = cursor + size
        return RomAllocation(
            bank = bank,
            pcOffset = cursor,
            snesAddress = pcToSnes(cursor),
            size = size,
            label = label,
        )
    }

    private fun scanTrailingFreeStart(bankStart: Int, bankEndExclusive: Int): Int {
        var firstTrailingFree = bankEndExclusive
        while (firstTrailingFree > bankStart && (romData[firstTrailingFree - 1].toInt() and 0xFF) == 0xFF) {
            firstTrailingFree--
        }
        return (firstTrailingFree + guardBytes).coerceAtMost(bankEndExclusive)
    }

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
