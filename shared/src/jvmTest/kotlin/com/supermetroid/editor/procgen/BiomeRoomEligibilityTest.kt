package com.supermetroid.editor.procgen

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BiomeRoomEligibilityTest {
    @Test
    fun `bulk generation skips boss and special rooms by shared metadata`() {
        assertTrue(
            BiomeRoomEligibility.shouldSkipBulkBiomeRoom(
                RoomInfo("0xDD58", "motherBrain", "Mother Brain's Room"),
                room(0xDD58, "Mother Brain's Room", "motherBrain"),
            )
        )
        assertTrue(
            BiomeRoomEligibility.shouldSkipBulkBiomeRoom(
                RoomInfo("0xA98D", "saveStation", "Save Station"),
                room(0xA98D, "Save Station", "saveStation"),
            )
        )
    }

    @Test
    fun `bulk generation allows ordinary traversal rooms`() {
        assertFalse(
            BiomeRoomEligibility.shouldSkipBulkBiomeRoom(
                RoomInfo("0x96BA", "pitRoom", "Pit Room"),
                room(0x96BA, "Pit Room", "pitRoom"),
            )
        )
    }

    private fun room(roomId: Int, name: String, handle: String): Room =
        Room(
            roomId = roomId,
            name = name,
            handle = handle,
            index = 0,
            area = 0,
            mapX = 0,
            mapY = 0,
            width = 1,
            height = 1,
            upScroller = 0,
            downScroller = 0,
            creBitflag = 0,
            doorOut = 0,
            levelDataPtr = 0xC08000,
        )
}
