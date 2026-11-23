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
    fun `Tourian Elevator keeps zero destination slot and later save-room door healthy`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val roomId = 0xDAAE
        val room = requireNotNull(parser.readRoomHeader(roomId))
        val editor = EditorState()
        editor.loadRoom(roomId, parser, room)

        assertEquals(4, editor.doorEntries.size)
        assertTrue(editor.doorEntries[2].isElevatorPlaceholder)
        assertEquals(0xDF1B, editor.doorEntries[3].destRoomPtr)

        val diagnostics = doorDiagnosticsForRoom(roomId, parser, editor, RoomRepository().getAllRooms())
        assertFalse(2 in diagnostics)
        assertFalse(requireNotNull(diagnostics[3]).hasError)
        val relevantBts = buildSet {
            for (y in 0 until editor.workingBlocksTall) {
                for (x in 0 until editor.workingBlocksWide) {
                    if (((editor.readBlockWord(x, y) ushr 12) and 0x0F) == 0x09) {
                        val bts = editor.readBts(x, y)
                        if ((bts and 0x7F) in 2..3) add(bts)
                    }
                }
            }
        }
        assertTrue(relevantBts.isNotEmpty())
        assertTrue(relevantBts.all {
            doorTileWarningSeverity(it, editor.doorEntries, diagnostics) != DoorConnectionSeverity.ERROR
        })
    }

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
