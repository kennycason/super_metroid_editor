package com.supermetroid.editor.ui

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.TestRomHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoorPlaceholderUiTest {
    @Test
    fun `Business Center exposes later BTS connections and keeps placeholder read only`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val roomId = 0xA7DE
        val room = requireNotNull(parser.readRoomHeader(roomId))
        val editor = EditorState()

        editor.loadRoom(roomId, parser, room)

        assertEquals(8, editor.doorEntries.size)
        assertTrue(editor.doorEntries[5].isElevatorPlaceholder)
        assertEquals(0xB167, editor.doorEntries[6].destRoomPtr)
        assertEquals(0xB0B4, editor.doorEntries[7].destRoomPtr)

        val placeholder = editor.doorEntries[5]
        editor.updateDoor(5, placeholder.copy(destRoomPtr = 0x91F8))
        assertEquals(placeholder, editor.doorEntries[5])
        assertNull(editor.project.rooms[editor.project.roomKey(roomId)])

        val frogSave = editor.doorEntries[6]
        editor.updateDoor(6, frogSave.copy(distFromDoor = frogSave.distFromDoor xor 1))
        assertEquals(frogSave.distFromDoor xor 1, editor.doorEntries[6].distFromDoor)
        assertEquals(6, editor.project.rooms.getValue(editor.project.roomKey(roomId)).doorChanges.single().doorIndex)

        val diagnostics = doorDiagnosticsForRoom(roomId, parser, editor, RoomRepository().getAllRooms())
        assertFalse(5 in diagnostics)
        assertTrue(6 in diagnostics)
        assertTrue(7 in diagnostics)
    }
}
