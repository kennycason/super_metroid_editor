package com.supermetroid.editor.rom

import com.supermetroid.editor.data.RoomRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomStateInspectionTest {
    @Test
    fun `all ten known selector encodings advance by their verified sizes`() {
        val fixture = roomFixture(area = 2)
        var cursor = fixture.stateListPc
        var nextStatePtr = 0x9200

        fun noArg(code: Int) {
            write16(fixture.rom, cursor, code)
            write16(fixture.rom, cursor + 2, nextStatePtr)
            nextStatePtr += 0x20
            cursor += 4
        }

        write16(fixture.rom, cursor, 0xE5EB)
        write16(fixture.rom, cursor + 2, 0xA1C8)
        write16(fixture.rom, cursor + 4, nextStatePtr)
        nextStatePtr += 0x20
        cursor += 6

        noArg(0xE5FF)
        noArg(0xE60F)

        write16(fixture.rom, cursor, 0xE612)
        fixture.rom[cursor + 2] = 0x0E
        write16(fixture.rom, cursor + 3, nextStatePtr)
        nextStatePtr += 0x20
        cursor += 5

        write16(fixture.rom, cursor, 0xE629)
        fixture.rom[cursor + 2] = 0x04
        write16(fixture.rom, cursor + 3, nextStatePtr)
        nextStatePtr += 0x20
        cursor += 5

        noArg(0xE640)
        noArg(0xE652)
        noArg(0xE669)
        noArg(0xE678)
        write16(fixture.rom, cursor, 0xE5E6)

        val inspection = RomParser(fixture.rom).inspectRoomStates(fixture.roomId)

        assertTrue(inspection.isComplete)
        assertEquals(
            listOf(0xE5EB, 0xE5FF, 0xE60F, 0xE612, 0xE629, 0xE640, 0xE652, 0xE669, 0xE678, 0xE5E6),
            inspection.states.map { it.condition.code },
        )
        assertEquals(listOf(6, 4, 4, 5, 5, 4, 4, 4, 4, 2), inspection.states.map { it.condition.entrySizeBytes })
        assertEquals(0xA1C8, inspection.states[0].condition.argument)
        assertEquals(0x0E, inspection.states[3].condition.argument)
        assertEquals(0x04, inspection.states[4].condition.argument)
        assertEquals(cursor + 2, inspection.states.last().stateDataPcOffset)
    }

    @Test
    fun `inspection decodes ordered selectors without losing their arguments`() {
        val fixture = roomFixture(area = 1)
        var cursor = fixture.stateListPc

        write16(fixture.rom, cursor, 0xE612)
        fixture.rom[cursor + 2] = 0x0E
        write16(fixture.rom, cursor + 3, 0x9100)
        cursor += 5

        write16(fixture.rom, cursor, 0xE629)
        fixture.rom[cursor + 2] = 0x02
        write16(fixture.rom, cursor + 3, 0x9120)
        cursor += 5

        // $8F:E60F is the vanilla "skip the state pointer and return" entry
        // used by SMART as an explicit always-false condition.
        write16(fixture.rom, cursor, 0xE60F)
        write16(fixture.rom, cursor + 2, 0x9140)
        cursor += 4

        write16(fixture.rom, cursor, 0xE5E6)

        val inspection = RomParser(fixture.rom).inspectRoomStates(fixture.roomId)

        assertTrue(inspection.isComplete)
        assertEquals(4, inspection.states.size)
        assertEquals(
            listOf(
                RoomStateConditionKind.EVENT_SET,
                RoomStateConditionKind.AREA_BOSS_BIT_SET,
                RoomStateConditionKind.NEVER,
                RoomStateConditionKind.DEFAULT,
            ),
            inspection.states.map { it.condition.kind },
        )
        assertEquals(0x0E, inspection.states[0].condition.argument)
        assertEquals(0x02, inspection.states[1].condition.argument)
        assertTrue(inspection.states[0].condition.summary(1).contains("Zebes timebomb"))
        assertTrue(inspection.states[1].condition.summary(1).contains("Spore Spawn"))
        assertEquals(null, inspection.states.last().stateDataPointer)
        assertEquals(cursor + 2, inspection.states.last().stateDataPcOffset)
    }

    @Test
    fun `short selector summaries omit storage details`() {
        val event = RoomStateCondition(0xE612, RoomStateConditionKind.EVENT_SET,
            RoomStateConditionArgumentKind.EVENT_ID, 0x0E, 5)
        val boss = RoomStateCondition(0xE629, RoomStateConditionKind.AREA_BOSS_BIT_SET,
            RoomStateConditionArgumentKind.BOSS_BIT_MASK, 0x01, 5)
        val default = RoomStateCondition(0xE5E6, RoomStateConditionKind.DEFAULT,
            RoomStateConditionArgumentKind.NONE, entrySizeBytes = 2)

        assertEquals("Zebes timebomb is set", event.shortSummary(area = 0))
        assertEquals("Phantoon defeated", boss.shortSummary(area = 3))
        assertEquals("Default", default.shortSummary(area = 0))
    }

    @Test
    fun `unknown selector stops at the exact byte instead of guessing its layout`() {
        val fixture = roomFixture(area = 0)
        write16(fixture.rom, fixture.stateListPc, 0xDEAD)
        write16(fixture.rom, fixture.stateListPc + 2, 0xE5E6)

        val inspection = RomParser(fixture.rom).inspectRoomStates(fixture.roomId)

        assertFalse(inspection.hasDefault)
        assertTrue(inspection.states.isEmpty())
        assertEquals(1, inspection.issues.size)
        assertEquals(fixture.stateListPc, inspection.issues.single().pcOffset)
        assertTrue(inspection.issues.single().message.contains("Unknown state selector"))
    }

    @Test
    fun `malformed state pointer is reported while the remaining list stays inspectable`() {
        val fixture = roomFixture(area = 2)
        var cursor = fixture.stateListPc
        write16(fixture.rom, cursor, 0xE629)
        fixture.rom[cursor + 2] = 0x01
        write16(fixture.rom, cursor + 3, 0x1234)
        cursor += 5
        write16(fixture.rom, cursor, 0xE5E6)

        val inspection = RomParser(fixture.rom).inspectRoomStates(fixture.roomId)

        assertTrue(inspection.hasDefault)
        assertFalse(inspection.isComplete)
        assertEquals(2, inspection.states.size)
        assertEquals(null, inspection.states.first().stateDataPcOffset)
        assertNotNull(inspection.states.last().stateDataPcOffset)
        assertTrue(inspection.issues.single().message.contains("outside bank \$8F"))
    }

    @Test
    fun `area seven debug room is readable without treating it as a pause-map area`() {
        val fixture = roomFixture(area = 7)
        write16(fixture.rom, fixture.stateListPc, 0xE5E6)

        val parser = RomParser(fixture.rom)
        val room = parser.readRoomHeader(fixture.roomId)
        val inspection = parser.inspectRoomStates(fixture.roomId)

        assertNotNull(room)
        assertEquals(7, room!!.area)
        assertEquals("Debug/Unused", room.areaName)
        assertTrue(inspection.isComplete)
        assertEquals(7, inspection.area)
    }

    @Test
    fun `every cataloged vanilla room has a complete inspectable selector list`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val rooms = RoomRepository().getAllRooms()
        var totalStates = 0

        for (room in rooms) {
            val roomId = room.getRoomIdAsInt()
            val inspection = parser.inspectRoomStates(roomId)
            assertTrue(
                inspection.isComplete,
                "Room \$${roomId.toString(16).uppercase()} issues: ${inspection.issues.joinToString { it.message }}",
            )
            assertEquals(
                inspection.states.mapNotNull { it.stateDataPcOffset },
                parser.findAllStateDataOffsets(roomId),
            )
            totalStates += inspection.states.size
        }

        assertEquals(263, rooms.size)
        assertEquals(324, totalStates)
    }

    @Test
    fun `boss selectors use area-local boss masks rather than event names`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val expected = mapOf(
            0x9804 to "Bomb Torizo",
            0x9DC7 to "Spore Spawn",
            0xA59F to "Kraid",
            0xA98D to "Crocomire",
            0xB283 to "Golden Torizo",
            0xB32E to "Ridley",
            0xCD13 to "Phantoon",
            0xD95E to "Botwoon",
            0xDA60 to "Draygon",
            0xE0B5 to "Ceres Ridley",
        )

        for ((roomId, bossName) in expected) {
            val inspection = parser.inspectRoomStates(roomId)
            val bossState = inspection.states.firstOrNull {
                it.condition.kind == RoomStateConditionKind.AREA_BOSS_BIT_SET
            }
            val area = requireNotNull(inspection.area)
            assertNotNull(bossState, "Expected a boss state for room \$${roomId.toString(16)}")
            assertTrue(
                bossState!!.condition.summary(area).startsWith(bossName),
                "Wrong boss label for room \$${roomId.toString(16)}: " +
                    bossState.condition.summary(area),
            )
        }
    }

    private data class Fixture(
        val rom: ByteArray,
        val roomId: Int,
        val stateListPc: Int,
    )

    private fun roomFixture(area: Int): Fixture {
        val rom = ByteArray(RomConstants.ROM_SIZE)
        val roomId = 0x9000
        val parser = RomParser(rom)
        val roomPc = parser.roomIdToPc(roomId)
        rom[roomPc] = 0x01
        rom[roomPc + 1] = area.toByte()
        rom[roomPc + 4] = 0x01
        rom[roomPc + 5] = 0x01
        rom[roomPc + 6] = 0x70
        rom[roomPc + 7] = 0xA0.toByte()
        write16(rom, roomPc + 9, 0x8000)
        return Fixture(rom, roomId, roomPc + 11)
    }

    private fun write16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
