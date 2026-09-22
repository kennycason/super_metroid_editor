package com.supermetroid.editor.ui

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoorTemplateAutofillTest {

    @Test
    fun `door cap derivation uses selected screen edge and vanilla offsets`() {
        val romParser = TestRomHelper.loadRomParser() ?: return

        assertEquals(
            0x064E,
            romParser.deriveDoorCapPosition(
                destRoomId = 0x92FD,
                direction = 1,
                screenX = 4,
                screenY = 0,
            ),
            "Horizontal entrance cap should be derived from screen 5, not the room's outer-left edge",
        )
        assertEquals(
            0x0216,
            romParser.deriveDoorCapPosition(
                destRoomId = 0x96BA,
                direction = 2,
                screenX = 1,
                screenY = 0,
            ),
            "Vertical closing caps sit two blocks inside the doorway",
        )
    }

    @Test
    fun `Landing Site offers existing entrance templates`() {
        val romParser = TestRomHelper.loadRomParser() ?: return
        val choices = doorTemplateChoicesForDestination(
            romParser,
            RoomRepository().getAllRooms(),
            0x91F8,
        )

        assertTrue(
            choices.any { it.door.doorDefPtr == 0x8946 && it.door.screenX == 0 && it.door.screenY == 2 },
            "Landing Site should expose the vanilla top-left entrance as a template",
        )
    }

    @Test
    fun `door template copies entry geometry and recalculates cross-area flag`() {
        val currentDoor = RomParser.DoorEntry(
            destRoomPtr = 0x91F8,
            bitflag = 0x0500,
            doorCapCode = 0x0000,
            screenX = 3,
            screenY = 1,
            distFromDoor = 0x8000,
            entryCode = 0x0000,
            doorDefPtr = 0xAA80,
        )
        val templateDoor = RomParser.DoorEntry(
            destRoomPtr = 0x91F8,
            bitflag = 0x0400,
            doorCapCode = 0x2601,
            screenX = 0,
            screenY = 2,
            distFromDoor = 0x8000,
            entryCode = 0xB997,
            doorDefPtr = 0x8946,
        )

        val filled = doorWithTemplateValues(currentDoor, templateDoor, crossArea = true)

        assertEquals(0x91F8, filled.destRoomPtr)
        assertEquals(0x0440, filled.bitflag)
        assertEquals(0x2601, filled.doorCapCode)
        assertEquals(0, filled.screenX)
        assertEquals(2, filled.screenY)
        assertEquals(0x8000, filled.distFromDoor)
        assertEquals(0xB997, filled.entryCode)
    }

    @Test
    fun `changing destination clamps entrance to destination dimensions`() {
        val currentDoor = RomParser.DoorEntry(
            destRoomPtr = 0x91F8,
            bitflag = 0,
            doorCapCode = 0,
            screenX = 8,
            screenY = 3,
            distFromDoor = 0x8000,
            entryCode = 0,
        )

        val clamped = doorWithEntranceClampedToRoom(currentDoor, roomWidth = 2, roomHeight = 1)

        assertEquals(1, clamped.screenX)
        assertEquals(0, clamped.screenY)
    }
}
