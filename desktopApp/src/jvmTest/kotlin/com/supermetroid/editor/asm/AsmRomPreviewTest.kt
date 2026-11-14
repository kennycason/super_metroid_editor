package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlan
import com.supermetroid.editor.rom.RomWritePlanReport
import com.supermetroid.editor.rom.RomAllocation
import com.supermetroid.editor.rom.RomFreeSpaceBank
import com.supermetroid.editor.rom.RomFreeSpaceSnapshot
import com.supermetroid.editor.rom.RomSegmentKind
import com.supermetroid.editor.rom.RomSegmentRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AsmRomPreviewTest {
    @Test
    fun `preview compares canonical PC bytes and preserves write ownership`() {
        val loaded = ByteArray(512 + 32) { index -> (index and 0xFF).toByte() }
        val plan = RomWritePlan(loaded, headerSize = 512)
        plan.add(
            owner = "room-graph:project",
            label = "Room graph",
            offset = 2,
            bytes = listOf(0xAA, 0xBB),
            kind = RomWriteKind.ROOM,
        )
        plan.add(
            owner = "patch:test",
            label = "Test patch",
            offset = 7,
            bytes = listOf(0xCC),
            kind = RomWriteKind.FIXED_PATCH,
        )

        val preview = AsmRomPreview.create(loaded, plan.finalRom(), 512, plan.report())

        assertEquals(3, preview.changedByteCount)
        assertEquals(listOf(2, 7), preview.diffRanges.map { it.pcOffset })
        assertEquals(listOf("room-graph:project"), preview.diffRanges[0].owners)
        assertEquals(setOf(RomWriteKind.ROOM), preview.diffRanges[0].kinds)
        assertEquals(loaded[512].toInt() and 0xFF, preview.loadedByte(0))
        assertEquals(0xAA, preview.resultByte(2))
        assertContentEquals(loaded, preview.loadedRom)
    }

    @Test
    fun `preview retains immutable baseline separately from loaded and result bytes`() {
        val baseline = byteArrayOf(0x10, 0x20, 0x30)
        val loaded = byteArrayOf(0x10, 0x21, 0x30)
        val result = byteArrayOf(0x10, 0x22, 0x30)
        val preview = AsmRomPreview.create(
            loadedRom = loaded,
            baselineRom = baseline,
            resultRom = result,
            headerSize = 0,
            writeReport = RomWritePlanReport(emptyList(), emptyList(), emptyList(), emptyList()),
        )

        assertEquals(0x20, preview.baselineByte(1))
        assertEquals(0x21, preview.loadedByte(1))
        assertEquals(0x22, preview.resultByte(1))
        baseline[1] = 0
        assertEquals(0x20, preview.baselineByte(1), "the preview owns an immutable copy")
    }

    @Test
    fun `expanded result bytes appear as unowned structural ranges`() {
        val report = RomWritePlanReport(emptyList(), emptyList(), emptyList(), emptyList())
        val preview = AsmRomPreview.create(
            loadedRom = byteArrayOf(1, 2, 3, 4),
            resultRom = byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0),
            headerSize = 0,
            writeReport = report,
            maxRangeBytes = 2,
        )

        assertEquals(4, preview.changedByteCount)
        assertEquals(listOf(4, 6), preview.diffRanges.map { it.pcOffset })
        assertEquals(listOf(2, 2), preview.diffRanges.map { it.length })
        assertEquals(emptyList(), preview.diffRanges.first().owners)
        assertNull(preview.loadedByte(4))
        assertEquals(0, preview.resultByte(4))
    }

    @Test
    fun `adjacent changed bytes split at write ownership boundaries`() {
        val loaded = ByteArray(8)
        val plan = RomWritePlan(loaded)
        plan.add("patch:a", "A", 0, listOf(1, 1), RomWriteKind.FIXED_PATCH)
        plan.add("patch:b", "B", 2, listOf(2, 2), RomWriteKind.FIXED_PATCH)

        val preview = AsmRomPreview.create(loaded, plan.finalRom(), 0, plan.report())

        assertEquals(listOf(0, 2), preview.diffRanges.map { it.pcOffset })
        assertEquals(listOf(listOf("patch:a"), listOf("patch:b")), preview.diffRanges.map { it.owners })
    }

    @Test
    fun `large sparse write plans retain ownership without quadratic range scans`() {
        val writeCount = 10_000
        val loaded = ByteArray(writeCount * 2)
        val plan = RomWritePlan(loaded)
        repeat(writeCount) { index ->
            plan.add(
                owner = "graphics:community-samus",
                label = "Record $index",
                offset = index * 2,
                bytes = listOf(1),
                kind = RomWriteKind.GRAPHICS,
            )
        }

        val preview = AsmRomPreview.create(loaded, plan.finalRom(), 0, plan.report())

        assertEquals(writeCount, preview.changedByteCount)
        assertEquals(writeCount, preview.diffRanges.size)
        assertEquals(listOf("graphics:community-samus"), preview.diffRanges.last().owners)
    }

    @Test
    fun `space plan preserves exact before after placement and owner data`() {
        val header = 512
        val loaded = ByteArray(header + 0x8000) { -1 }
        val plan = RomWritePlan(loaded, header)
        plan.add("room-graph:project", "Room 91F8 level data", 0x7000, List(0x100) { 0x5A }, RomWriteKind.ALLOCATION)
        val before = RomFreeSpaceSnapshot(
            banks = listOf(RomFreeSpaceBank(0x80, header, header + 0x8000, header + 0x7000, 0x1000, 0xFF)),
            allocations = emptyList(),
        )
        val request = RomSegmentRequest(
            id = "room-graph:project/room-91f8-level-data",
            owner = "room-graph:project",
            kind = RomSegmentKind.ROOM_DATA,
            label = "Room 91F8 level data",
            size = 0x100,
            allowedBanks = listOf(0x80),
        )
        val allocation = RomAllocation(
            0x80, header + 0x7000, 0x80F000, 0x100, request.label, request = request,
        )
        val after = RomFreeSpaceSnapshot(
            banks = listOf(RomFreeSpaceBank(0x80, header, header + 0x8000, header + 0x7100, 0x0F00, 0xFF)),
            allocations = listOf(allocation),
        )

        val preview = AsmRomPreview.create(
            loadedRom = loaded,
            resultRom = plan.finalRom(),
            headerSize = header,
            writeReport = plan.report(),
            romSpaceBefore = before,
            romSpaceAfter = after,
        )

        val space = requireNotNull(preview.spacePlan)
        val bank = space.banks.single()
        assertEquals(0, bank.pcStart)
        assertEquals(0x1000, bank.availableBefore)
        assertEquals(0x0F00, bank.remainingAfter)
        assertEquals(0x100, bank.consumedBytes)
        assertEquals(0x100, space.allocationBytes)
        assertEquals("room-graph:project", bank.placements.single().owner)
        assertEquals(0x7000, bank.placements.single().pcOffset)
    }

    @Test
    fun `space plan refuses to infer an owner for an anonymous allocation`() {
        val bank = RomFreeSpaceBank(0x80, 0, 0x8000, 0x100, 0x7F00, 0xFF)
        val allocation = RomAllocation(0x80, 0x100, 0x808100, 4, "anonymous")

        val problem = assertFailsWith<IllegalArgumentException> {
            AsmRomSpacePlan.create(
                bodySize = 0x8000,
                headerSize = 0,
                report = RomWritePlanReport(emptyList(), emptyList(), emptyList(), emptyList()),
                before = RomFreeSpaceSnapshot(listOf(bank), emptyList()),
                after = RomFreeSpaceSnapshot(listOf(bank), listOf(allocation)),
            )
        }

        assertEquals("ROM Space Planner received an untyped allocation 'anonymous'", problem.message)
    }

    @Test
    fun `space plan lists compiler managed assets without double counting allocator availability`() {
        val loaded = ByteArray(0x8000) { -1 }
        val plan = RomWritePlan(loaded)
        plan.add(
            "asm-managed-asset:tiles-test",
            "Managed ASM asset · Tiles_Test",
            0x7000,
            List(0x180) { 0x5A },
            RomWriteKind.ASM_ASSET,
        )
        val bankSnapshot = RomFreeSpaceBank(0x80, 0, 0x8000, 0x7180, 0x0E80, 0xFF)
        val snapshot = RomFreeSpaceSnapshot(listOf(bankSnapshot), emptyList())

        val preview = AsmRomPreview.create(
            loadedRom = loaded,
            resultRom = plan.finalRom(),
            headerSize = 0,
            writeReport = plan.report(),
            romSpaceBefore = snapshot,
            romSpaceAfter = snapshot,
        )

        val space = requireNotNull(preview.spacePlan)
        val placement = space.placements.single()
        assertEquals("asm-managed-asset:tiles-test", placement.owner)
        assertEquals(0x80F000, placement.snesAddress)
        assertEquals(0x180, placement.size)
        assertEquals(0, space.consumedBytes, "The compiler placement was already removed from both allocator snapshots")
        assertEquals(0x0E80, space.remainingAfter)
    }

    @Test
    fun `blocked build keeps partial space plan and exact error in planner view`() = runBlocking {
        val plan = AsmRomSpacePlan(
            romBodySize = 0x300000,
            banks = listOf(
                AsmRomSpaceBank(0xC2, 0x210000, 0x8000, 3742, 3742, 0xFF, emptyList(), emptyList()),
            ),
        )
        val state = AsmWorkspaceState()
        state.selectRomPreviewView(AsmRomPreviewView.SPACE)
        state.buildRomPreview {
            AsmRomPreviewBuild(
                preview = null,
                spacePlan = plan,
                error = "Room 0x91F8 needs 3935 bytes; largest contiguous block is 3742 bytes.",
            )
        }

        assertNull(state.romPreview)
        assertSame(plan, state.romSpacePlan)
        assertEquals(AsmRomPreviewView.SPACE, state.romPreviewView)
        assertEquals("Room 0x91F8 needs 3935 bytes; largest contiguous block is 3742 bytes.", state.error)
    }

    @Test
    fun `source bank navigation opens and focuses the space planner`() {
        val state = AsmWorkspaceState()

        state.openRomSpaceBank(0x18F)

        assertEquals(AsmBrowserMode.ROM, state.browserMode)
        assertEquals(AsmRomPreviewView.SPACE, state.romPreviewView)
        assertEquals(0x8F, state.requestedRomSpaceBank)

        state.selectRomPreviewView(AsmRomPreviewView.DIFF)
        assertNull(state.requestedRomSpaceBank)
    }

    @Test
    fun `compiled output navigation focuses its ROM diff and requests project changes`() = runBlocking {
        val loaded = ByteArray(16)
        val writes = RomWritePlan(loaded).also {
            it.add("asm-source:project", "First", 2, listOf(1), RomWriteKind.ASM_SOURCE)
            it.add("asm-source:project", "Second", 8, listOf(2), RomWriteKind.ASM_SOURCE)
        }
        val preview = AsmRomPreview.create(loaded, writes.finalRom(), 0, writes.report())
        val state = AsmWorkspaceState()
        state.buildRomPreview { AsmRomPreviewBuild(preview, null, null) }

        state.openRomComparison(8)
        assertEquals(AsmBrowserMode.ROM, state.browserMode)
        assertEquals(AsmRomPreviewView.DIFF, state.romPreviewView)
        assertEquals(1, state.selectedRomDiffIndex)

        state.requestProjectChanges()
        state.requestProjectChanges()
        assertEquals(2L, state.projectChangesRequestSerial)
    }
}
