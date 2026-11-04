package com.supermetroid.editor.asm

import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomWriteIntent
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsmSourceRoomMaterializerTest {
    @Test
    fun `existing room graph becomes one atomic generated ASM input`() {
        val project = editedProject()
        val report = report(
            RomWriteIntent(
                owner = ASM_ROOM_GRAPH_OWNER,
                label = "Project room graph",
                offset = 0x100,
                bytes = listOf(0x00, 0x82, 0x80),
                kind = RomWriteKind.ROOM,
            ),
            RomWriteIntent(
                owner = ASM_ROOM_GRAPH_OWNER,
                label = "Project room graph",
                offset = 0x200,
                bytes = listOf(0xAA, 0xBB),
                kind = RomWriteKind.ROOM,
            ),
            RomWriteIntent(
                owner = ASM_ROOM_GRAPH_OWNER,
                label = "room 0x91F8 level data",
                offset = 0x200,
                bytes = listOf(0xAA, 0xBB, 0xFF),
                kind = RomWriteKind.ALLOCATION,
            ),
        )

        val result = AsmSourceRoomMaterializer().materialize(project, report)

        assertFalse(result.isEmpty)
        assertEquals(3, result.eligibleWrites)
        assertEquals(0, result.skippedWrites)
        assertEquals(setOf("91F8"), result.roomIds)
        assertEquals(2, result.claims.size)
        assertContentEquals(byteArrayOf(0x00, 0x82.toByte(), 0x80.toByte()), result.claims[0].bytes)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xFF.toByte()), result.claims[1].bytes)
        assertTrue(result.claims[1].label.contains("room 0x91F8 level data"))
        assertTrue(result.source.contains("org \$808100"))
        assertTrue(result.source.contains("SmeditRoomGraph_001:"))
        assertTrue(result.source.contains("db \$AA,\$BB,\$FF"))
    }

    @Test
    fun `room source backend is atomic when a write kind is unsupported`() {
        val report = report(
            RomWriteIntent(
                owner = ASM_ROOM_GRAPH_OWNER,
                label = "Project room graph",
                offset = 0x100,
                bytes = listOf(1),
                kind = RomWriteKind.ROOM,
            ),
            RomWriteIntent(
                owner = ASM_ROOM_GRAPH_OWNER,
                label = "Unexpected hook",
                offset = 0x200,
                bytes = listOf(2),
                kind = RomWriteKind.HOOK,
            ),
        )

        val result = AsmSourceRoomMaterializer().materialize(editedProject(), report)

        assertTrue(result.isEmpty)
        assertEquals(2, result.eligibleWrites)
        assertEquals(2, result.skippedWrites)
    }

    @Test
    fun `expanded room claims materialize only when the compiler owns four MiB`() {
        val report = report(
            RomWriteIntent(
                owner = ASM_ROOM_GRAPH_OWNER,
                label = "Expanded level data",
                offset = 0x300100,
                bytes = listOf(0x12, 0x34),
                kind = RomWriteKind.ALLOCATION,
            ),
        )

        val standard = AsmSourceRoomMaterializer().materialize(editedProject(), report)
        val expanded = AsmSourceRoomMaterializer().materialize(
            editedProject(),
            report,
            compiledRomSize = 0x400000,
        )

        assertTrue(standard.isEmpty)
        assertFalse(expanded.isEmpty)
        assertTrue(expanded.source.contains("org \$E08100"), expanded.source)
        assertContentEquals(byteArrayOf(0x12, 0x34), expanded.claims.single().bytes)
    }

    private fun editedProject() = SmEditProject(
        romPath = "/tmp/Super Metroid.sfc",
        rooms = mutableMapOf(
            "91f8" to RoomEdits(
                roomId = 0x91F8,
                operations = mutableListOf(EditOperation("test edit")),
            )
        ),
    )

    private fun report(vararg writes: RomWriteIntent) = RomWritePlanReport(
        writes = writes.toList(),
        resources = emptyList(),
        owners = emptyList(),
        unverifiedFixedWrites = emptyList(),
    )
}
