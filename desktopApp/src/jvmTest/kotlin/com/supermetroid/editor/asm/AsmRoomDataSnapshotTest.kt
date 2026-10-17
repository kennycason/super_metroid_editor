package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SmEditRoomStateGraphFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsmRoomDataSnapshotTest {
    private val rooms = listOf(RoomInfo("0x91F8", "landing", "Landing Site"))

    @Test fun `project data uses durable names and semantic navigation rather than the built address`() {
        val id = "project room · é"
        val encoded = AsmProjectRoomLabels.encoded(id)
        assertEquals(id, AsmProjectRoomLabels.decode(encoded))
        assertEquals(id, AsmProjectRoomLabels.identity(AsmProjectRoomLabels.entry(id)))
        assertEquals(null, AsmProjectRoomLabels.decode("FF"))
        assertEquals(null, AsmProjectRoomLabels.decode("A"))
        val source = AsmRoomDataSnapshot.render(fixture(), rooms, mapOf(0x91F8 to id))
        val data = assertNotNull(AsmGeneratedRoomSource.parse(source, "test"))
        val header = data.sections.first()
        assertEquals(id, header.projectRoomId)
        assertEquals(0x91F8, header.roomId)
        assertEquals(AsmProjectRoomLabels.prefix(id) + "_Header", header.label)
        assertEquals("Landing Site", data.groups().single().title)
        val project = com.supermetroid.editor.data.SmEditProject("test")
        assertEquals(null, AsmEditorTarget.Room(0x91F8, projectRoomId = id).resolveRoomId(project), "A deleted project identity must not fall back to a stale address")
    }

    @Test
    fun `known room catalog retains Landing Site when its state graph is redirected`() {
        val rom = fixture().copyOf(0x400000)
        // Move the selector graph, leaving a recognized SMEDIT redirect in the header.
        rom.copyInto(rom, 0x7B000, 0x79203, 0x79224)
        SmEditRoomStateGraphFormat.redirectRoutineBytes.forEachIndexed { i, byte -> rom[0x7C000 + i] = byte.toByte() }
        rom[0x79203] = 0; rom[0x79204] = 0xC0.toByte()
        rom[0x79205] = 0; rom[0x79206] = 0xB0.toByte()
        assertTrue(RomParser(rom).inspectRoomStates(0x91F8).issues.isEmpty())
        val data = assertNotNull(AsmGeneratedRoomSource.parse(AsmRoomDataSnapshot.render(rom, rooms), "test"))
        assertEquals("Landing Site", data.groups().single().title)
        assertEquals(2, data.sections.count { it.title.endsWith("Objects (PLMs)") })
    }

    @Test
    fun `unchanged rooms expose named headers states annotated PLMs and shared pointers`() {
        val rom = fixture()
        val text = AsmRoomDataSnapshot.render(rom, rooms)
        val document = assertNotNull(AsmGeneratedRoomSource.parse(text, "test"))
        assertTrue(document.isRoomDataSnapshot)
        assertEquals("Landing Site", document.groups().single().title)
        assertEquals(5, document.sections.size)
        assertEquals(2, document.sections.count { it.title.endsWith("Objects (PLMs)") })
        assertTrue(text.contains("db ${'$'}03, ${'$'}05 ; Tile X, Y"))
        assertTrue(text.contains("dw ${'$'}0000 ; End of list"))
        assertTrue(text.contains("Shared address"))
        assertTrue(text.contains("dw SmeditRoom_91F8_State1_Plms&${'$'}FFFF"))
        assertEquals(text, AsmRoomDataSnapshot.render(ByteArray(512) + rom, rooms), "copier headers must not shift displayed SNES addresses")
    }

    @Test
    fun `bad selectors and unterminated lists are explained instead of invented`() {
        val badSelector = fixture().also { it[0x79203] = 0x11; it[0x79204] = 0x11 }
        val unknown = AsmRoomDataSnapshot.render(badSelector, rooms)
        assertTrue(unknown.contains("Unavailable: room selectors"))
        assertFalse(unknown.contains("State0_Data:"))
        val malformed = fixture().also { it.fill(1, 0x7A000, 0x7A800) }
        val text = AsmRoomDataSnapshot.render(malformed, rooms)
        assertTrue(text.contains("unsupported/truncated data"))
        assertFalse(text.contains("State0_Plms:"))
        assertFalse(text.contains("dw ${'$'}0000 ; End of list"))
    }

    private fun fixture(): ByteArray {
        val rom = ByteArray(0x300000)
        fun word(pc: Int, value: Int) { rom[pc] = value.toByte(); rom[pc + 1] = (value ushr 8).toByte() }
        rom[0x791FC] = 1
        rom[0x791FD] = 1
        word(0x79203, 0xE612)
        word(0x79206, 0x9230)
        word(0x79208, 0xE5E6)
        word(0x7920A + 20, 0xA000)
        word(0x79230 + 20, 0xA000)
        RomParser.serializePlmSet(listOf(RomParser.PlmEntry(0xEEDB, 3, 5, 0x100)))
            .forEachIndexed { i, value -> rom[0x7A000 + i] = value.toByte() }
        return rom
    }
}
