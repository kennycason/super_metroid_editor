package com.supermetroid.editor.rom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RomSegmentAllocationTest {
    @Test
    fun `scoped allocators retain typed intent in one shared session`() {
        val rom = ByteArray(0x10000) { 0xFF.toByte() }
        val root = RomFreeSpaceAllocator(
            rom,
            ::snesToPc,
            ::pcToSnes,
            guardBytes = 0,
            verifiedFreeRanges = listOf(RomVerifiedFreeRange(0x80, 0x100, 0x200, 0xFF, "test")),
        )
        val rooms = root.scoped("room-graph:project", RomSegmentKind.ROOM_DATA, "room-graph")
        val first = rooms.reserve(16, listOf(0x80), "Room 91F8 level data", alignment = 4)!!
        val second = rooms.observing {}.reserve(8, listOf(0x80), "Room 91F8 PLMs")!!

        assertEquals("room-graph:project", first.request?.owner)
        assertEquals(RomSegmentKind.ROOM_DATA, first.request?.kind)
        assertEquals(4, first.request?.alignment)
        assertEquals(first.pcOffset + first.size, second.pcOffset)
        assertEquals(listOf(first, second), root.snapshot(listOf(0x80)).allocations)
    }

    @Test
    fun `registry reports fragmentation and blocking typed owners`() {
        val rom = ByteArray(0x10000) { 0xFF.toByte() }
        val root = RomFreeSpaceAllocator(
            rom,
            ::snesToPc,
            ::pcToSnes,
            guardBytes = 0,
            verifiedFreeRanges = listOf(
                RomVerifiedFreeRange(0x80, 0x100, 0x110, 0xFF, "a"),
                RomVerifiedFreeRange(0x80, 0x120, 0x130, 0xFF, "b"),
            ),
        )
        root.scoped("other-owner", RomSegmentKind.OTHER).reserve(4, listOf(0x80), "blocker")
        val registry = RomSegmentAllocationRegistry(root)
        val problem = assertFailsWith<RomSegmentAllocationException> {
            registry.reserve(
                RomSegmentRequest(
                    id = "asset:test",
                    owner = "asset:test",
                    kind = RomSegmentKind.SOURCE_ASSET,
                    label = "Large asset",
                    size = 20,
                    allowedBanks = listOf(0x80),
                ),
            )
        }

        assertTrue(problem.failure.fragmented)
        assertEquals(16, problem.failure.largestCompatibleBytes)
        assertEquals(listOf("other-owner"), problem.failure.blockingOwners)
        assertTrue(problem.message.orEmpty().contains("Safe next steps"))
        assertTrue(problem.message.orEmpty().contains("ROM Space Planner"))
    }

    @Test
    fun `scoped legacy call throws structured failure instead of returning null`() {
        val rom = ByteArray(0x8000) { 0x00 }
        val allocator = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes, guardBytes = 0)
            .scoped("graphics:test", RomSegmentKind.GRAPHICS)

        val failure = assertFailsWith<RomSegmentAllocationException> {
            allocator.reserve(16, listOf(0x80), "Test graphics", alignment = 4)
        }.failure

        assertEquals(16, failure.request.size)
        assertEquals(4, failure.request.alignment)
        assertEquals(listOf(0x80), failure.request.allowedBanks)
        assertEquals(0, failure.largestCompatibleBytes)
    }

    @Test
    fun `final registry guard rejects anonymous reservations`() {
        val rom = ByteArray(0x8000) { 0xFF.toByte() }
        val allocator = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes, guardBytes = 0)
        allocator.reserve(4, listOf(0x80), "legacy allocation")

        val problem = assertFailsWith<IllegalArgumentException> { allocator.requireTypedReservations() }
        assertTrue(problem.message.orEmpty().contains("untyped reservation"))
    }

    @Test
    fun `planning fork commits typed placements without sharing partial state`() {
        val rom = ByteArray(0x8000) { 0xFF.toByte() }
        val root = RomFreeSpaceAllocator(rom, ::snesToPc, ::pcToSnes, guardBytes = 0)
        val plannedRom = rom.copyOf()
        val fork = root.forkForPlanning(plannedRom).scoped("room:test", RomSegmentKind.ROOM_DATA)
        val allocation = fork.reserveOrThrow(8, listOf(0x80), "Room test data")

        assertTrue(root.snapshot(listOf(0x80)).allocations.isEmpty())
        root.adoptPlanning(fork, listOf(allocation))

        assertEquals(listOf(allocation), root.snapshot(listOf(0x80)).allocations)
        val later = root.scoped("later", RomSegmentKind.OTHER).reserveOrThrow(4, listOf(0x80), "Later data")
        assertTrue(later.pcOffset >= allocation.pcOffset + allocation.size)
        root.requireTypedReservations()
    }

    @Test
    fun `adopted compiled placement consumes final space but remains visible in baseline capacity`() {
        val rom = ByteArray(0x10000) { 0xFF.toByte() }
        rom.fill(0x5A, 0x110, 0x120)
        val allocator = RomFreeSpaceAllocator(
            rom,
            ::snesToPc,
            ::pcToSnes,
            guardBytes = 0,
            verifiedFreeRanges = listOf(RomVerifiedFreeRange(0x80, 0x100, 0x140, 0xFF, "test")),
        )
        val request = RomSegmentRequest(
            id = "managed:compiled",
            owner = "managed:compiled",
            kind = RomSegmentKind.MANAGED_CODE,
            label = "Compiled helper",
            size = 16,
            allowedBanks = listOf(0x80),
        )
        val allocation = RomAllocation(0x80, 0x110, pcToSnes(0x110), 16, request.label, 0xFF, request)
        val registry = RomSegmentAllocationRegistry(allocator)
        registry.adopt(RomSegmentPlacement(request, allocation))

        assertEquals(64, registry.baselineSnapshot(listOf(0x80)).banks.single().totalAvailableBytes)
        assertEquals(48, registry.snapshot(listOf(0x80)).banks.single().totalAvailableBytes)
        assertEquals(listOf(allocation), registry.snapshot(listOf(0x80)).allocations)
    }

    @Test
    fun `typed alignment remains canonical when allocator ROM has a copier header`() {
        val headerSize = 0x200
        val rom = ByteArray(headerSize + 0x8000) { 0xFF.toByte() }
        fun headeredSnesToPc(address: Int) = headerSize + snesToPc(address)
        fun headeredPcToSnes(offset: Int) = pcToSnes(offset - headerSize)
        val allocator = RomFreeSpaceAllocator(
            romData = rom,
            snesToPc = ::headeredSnesToPc,
            pcToSnes = ::headeredPcToSnes,
            guardBytes = 0,
            pcAlignmentOrigin = headerSize,
            verifiedFreeRanges = listOf(
                RomVerifiedFreeRange(0x80, headerSize + 0x101, headerSize + 0x1000, 0xFF, "headered"),
            ),
        )
        val request = RomSegmentRequest(
            id = "headered:aligned",
            owner = "headered:test",
            kind = RomSegmentKind.OTHER,
            label = "Aligned segment",
            size = 16,
            alignment = 0x400,
            allowedBanks = listOf(0x80),
        )
        val placed = RomSegmentAllocationRegistry(allocator).reserve(request)

        assertEquals(headerSize + 0x400, placed.allocation.pcOffset)
        assertEquals(0x400, placed.allocation.canonicalPcOffset)
    }

    private fun snesToPc(snesAddress: Int): Int {
        val bank = snesAddress ushr 16 and 0xFF
        val address = snesAddress and 0xFFFF
        return (bank and 0x7F) * 0x8000 + (address and 0x7FFF)
    }

    private fun pcToSnes(pcOffset: Int): Int {
        val bank = pcOffset / 0x8000 or 0x80
        return bank shl 16 or (pcOffset % 0x8000 + 0x8000)
    }
}
