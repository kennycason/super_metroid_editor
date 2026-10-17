package com.supermetroid.editor.asm

import com.supermetroid.editor.data.EditOperation
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomWriteIntent
import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AsmRoomPlmSourcesTest {
    @Test
    fun `shared state pointers name one complete table including unchanged fields`() {
        for (header in listOf(0, 0x200)) {
            val rom = fixture(header)
            val result = AsmSourceRoomMaterializer().materialize(project(), report(), rom)
            val table = result.plmTables.single()
            assertEquals(2, table.pointerPcOffsets.size)
            assertEquals(listOf(RomParser.PlmEntry(0xEEDB, 1, 2, 0x51)), table.entries)
            assertEquals(0x79000, table.pcOffset)
            assertTrue(result.source.contains("dw ${table.label}&\$FFFF"))
            assertTrue(result.source.contains("db \$01, \$02"))
            val claimed = hashSetOf<Int>()
            result.claims.forEach { claim ->
                assertContentEquals(rom.copyOfRange(header + claim.pcOffset, header + claim.endExclusive), claim.bytes)
                (claim.pcOffset until claim.endExclusive).forEach { assertTrue(claimed.add(it), "duplicate byte claim") }
            }
        }
    }

    @Test
    fun `empty list keeps its terminator and symbolic consumers`() {
        val rom = fixture().also { it[0x79000] = 0; it[0x79001] = 0 }
        val result = AsmSourceRoomMaterializer().materialize(
            project(), report(write(0x79000, listOf(0, 0))), rom,
        )
        val table = result.plmTables.single()
        assertTrue(table.entries.isEmpty())
        assertContentEquals(byteArrayOf(0, 0), table.bytes)
        assertEquals(2, table.pointerPcOffsets.size)
    }

    @Test
    fun `promotion refuses a source-owned byte even if the room edit changed another field`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            AsmSourceRoomMaterializer().materialize(
                project(), report(write(0x79004, listOf(0x51)), write(0x79002, listOf(1), "asm-module:custom")), fixture(),
            )
        }
        assertTrue(failure.message.orEmpty().contains("asm-module:custom"))
    }

    @Test
    fun `different pointer identities are not merged merely because payloads match`() {
        val rom = fixture()
        val states = RomParser(rom).findAllStateDataOffsets(0x91F8)
        rom.copyInto(rom, 0x79020, 0x79000, 0x79008)
        word(rom, states.last() + 20, 0x9020)
        val tables = discoverAsmRoomPlmTables(
            project(), report(write(0x79004, listOf(0x51)), write(0x79024, listOf(0x51))), rom,
        )
        assertEquals(2, tables.size)
        assertTrue(tables.all { it.pointerPcOffsets.size == 1 })
        assertEquals(2, tables.map { it.label }.distinct().size)
    }

    @Test
    fun `generated room source never spills across LoROM bank boundaries`() {
        val result = AsmSourceRoomMaterializer().materialize(project(), report(write(0x7FFFF, listOf(1, 2))))
        assertEquals(listOf(0x7FFFF, 0x80000), result.claims.map { it.pcOffset })
        assertTrue(result.source.contains("org \$8FFFFF"))
        assertTrue(result.source.contains("org \$908000"))
    }

    @Test
    fun `expanded-ROM allocations retain the complete ROM backend`() {
        val result = AsmSourceRoomMaterializer().materialize(
            project(), report(write(0x79004, listOf(0x51)), write(0x300000, listOf(1))),
        )
        assertTrue(result.isEmpty)
        assertEquals(2, result.skippedWrites)
    }

    @Test
    fun `typed tables must fit the engine bank`() {
        assertFailsWith<IllegalArgumentException> {
            AsmRoomPlmTable("Test", 0x7FFFF, emptyList(), listOf(0x79100)).renderSource()
        }
    }

    @Test
    fun `a parser safety limit must not silently truncate a generated PLM list`() {
        val rom = fixture()
        RomParser(rom).findAllStateDataOffsets(0x91F8).forEach { word(rom, it + 20, 0xA000) }
        rom.fill(1, 0x7A000, 0x7A800)
        val failure = assertFailsWith<IllegalArgumentException> {
            discoverAsmRoomPlmTables(project(), report(write(0x7A000, listOf(1))), rom)
        }
        assertTrue(failure.message.orEmpty().contains("zero terminator"))
    }

    @Test
    fun `new room promotion requires complete checked placements and keeps stable project labels`() {
        val project = project()
        project.newRooms += com.supermetroid.editor.data.ProjectNewRoom(
            id = "new-room", name = "Fixture", handle = "fixture",
            origin = com.supermetroid.editor.data.ProjectNewRoomOrigin.BLANK,
            header = com.supermetroid.editor.data.ProjectNewRoomHeader(0, 0, 0, 0, 1, 1),
            initialState = com.supermetroid.editor.data.ProjectNewRoomState("", 0),
        )
        val rom = fixture().also { it.copyInto(it, 0x7A000, 0x791F8, 0x7920A + 26) }
        assertFailsWith<IllegalArgumentException> {
            AsmSourceRoomMaterializer().materialize(project, report(), rom)
        }
        assertFailsWith<IllegalArgumentException> {
            AsmSourceRoomMaterializer().materialize(project, report(), rom, newRoomIds = mapOf("other-room" to 0xA000))
        }
        val result = AsmSourceRoomMaterializer().materialize(project, report(), rom, newRoomIds = mapOf("new-room" to 0xA000))
        assertTrue(!result.isEmpty)
        assertEquals(mapOf("new-room" to 0xA000), result.newRoomIds)
        assertTrue("A000" in result.roomIds)
        assertTrue(result.source.contains("${AsmProjectRoomLabels.entry("new-room")}:"))
        assertTrue(result.source.contains("org \$8FA000"))
        assertEquals(0, result.skippedWrites)
    }

    private fun project() = SmEditProject("fixture.sfc", rooms = mutableMapOf(
        "91F8" to RoomEdits(0x91F8, operations = mutableListOf(EditOperation("fixture"))),
    ))

    private fun write(pc: Int, bytes: List<Int>, owner: String = ASM_ROOM_GRAPH_OWNER) =
        RomWriteIntent(owner, "fixture", pc, bytes, kind = RomWriteKind.ROOM)

    private fun report(vararg writes: RomWriteIntent = arrayOf(write(0x79004, listOf(0x51)))) =
        RomWritePlanReport(writes.toList(), emptyList(), emptyList(), emptyList())

    /** Synthetic room with one event state and one default state sharing a PLM list. */
    private fun fixture(header: Int = 0): ByteArray {
        val rom = ByteArray(0x300000)
        rom[0x791F8 + 4] = 1
        rom[0x791F8 + 5] = 1
        word(rom, 0x79203, 0xE612)
        word(rom, 0x79206, 0x9230)
        word(rom, 0x79208, 0xE5E6)
        word(rom, 0x7920A + 20, 0x9000)
        word(rom, 0x79230 + 20, 0x9000)
        val payload = RomParser.serializePlmSet(listOf(RomParser.PlmEntry(0xEEDB, 1, 2, 0x51)))
        payload.forEachIndexed { i, byte -> rom[0x79000 + i] = byte.toByte() }
        return ByteArray(header) + rom
    }

    private fun word(rom: ByteArray, pc: Int, value: Int) {
        rom[pc] = value.toByte()
        rom[pc + 1] = (value ushr 8).toByte()
    }
}
