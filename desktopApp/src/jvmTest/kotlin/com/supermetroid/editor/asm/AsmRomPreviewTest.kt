package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlan
import com.supermetroid.editor.rom.RomWritePlanReport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
