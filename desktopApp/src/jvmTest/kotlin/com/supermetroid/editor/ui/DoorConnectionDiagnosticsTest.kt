package com.supermetroid.editor.ui

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.rom.RomParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoorConnectionDiagnosticsTest {
    private val sourceRoomId = 0x9000
    private val destinationRoomId = 0x9100

    @Test
    fun `opposite-facing reciprocal door is healthy`() {
        val diagnostic = evaluateDoorConnection(
            sourceRoomId = sourceRoomId,
            sourceRoomName = "Source",
            door = door(destinationRoomId, direction = 0),
            destinationRoom = room(destinationRoomId, width = 2, height = 1),
            destinationRoomName = "Destination",
            destinationDoors = listOf(door(sourceRoomId, direction = 1)),
            destinationOpeningCap = 0x0010,
        )

        assertFalse(diagnostic.needsAttention)
        assertEquals(listOf(0), diagnostic.returnDoorIndices)
    }

    @Test
    fun `one-way connection is a warning and is never treated as an error`() {
        val diagnostic = evaluateDoorConnection(
            sourceRoomId = sourceRoomId,
            sourceRoomName = "Source",
            door = door(destinationRoomId, direction = 0),
            destinationRoom = room(destinationRoomId, width = 1, height = 1),
            destinationRoomName = "Destination",
            destinationDoors = emptyList(),
            destinationOpeningCap = 0x0010,
        )

        assertTrue(diagnostic.needsAttention)
        assertFalse(diagnostic.hasError)
        assertEquals(
            listOf(DoorConnectionIssueKind.RETURN_LINK_MISSING),
            diagnostic.issues.map { it.kind },
        )
    }

    @Test
    fun `same-facing return link gets a direction warning`() {
        val diagnostic = evaluateDoorConnection(
            sourceRoomId = sourceRoomId,
            sourceRoomName = "Source",
            door = door(destinationRoomId, direction = 2),
            destinationRoom = room(destinationRoomId, width = 1, height = 1),
            destinationRoomName = "Destination",
            destinationDoors = listOf(door(sourceRoomId, direction = 2)),
            destinationOpeningCap = 0x1000,
        )

        assertEquals(
            listOf(DoorConnectionIssueKind.RETURN_LINK_FACES_WRONG_WAY),
            diagnostic.issues.map { it.kind },
        )
    }

    @Test
    fun `invalid destination geometry is an error without a duplicate opening warning`() {
        val diagnostic = evaluateDoorConnection(
            sourceRoomId = sourceRoomId,
            sourceRoomName = "Source",
            door = door(destinationRoomId, direction = 0, screenX = 2),
            destinationRoom = room(destinationRoomId, width = 1, height = 1),
            destinationRoomName = "Destination",
            destinationDoors = listOf(door(sourceRoomId, direction = 1)),
            destinationOpeningCap = null,
        )

        assertTrue(diagnostic.hasError)
        assertEquals(
            listOf(DoorConnectionIssueKind.ENTRANCE_OUT_OF_BOUNDS),
            diagnostic.issues.map { it.kind },
        )
    }

    @Test
    fun `missing room is a blocking error`() {
        val diagnostic = evaluateDoorConnection(
            sourceRoomId = sourceRoomId,
            sourceRoomName = "Source",
            door = door(destinationRoomId, direction = 0),
            destinationRoom = null,
            destinationRoomName = "Destination",
            destinationDoors = emptyList(),
            destinationOpeningCap = null,
        )

        assertTrue(diagnostic.hasError)
        assertEquals(DoorConnectionIssueKind.DESTINATION_MISSING, diagnostic.issues.single().kind)
    }

    private fun door(
        destination: Int,
        direction: Int,
        screenX: Int = 0,
        screenY: Int = 0,
    ) = RomParser.DoorEntry(
        destRoomPtr = destination,
        bitflag = direction shl 8,
        doorCapCode = 0,
        screenX = screenX,
        screenY = screenY,
        distFromDoor = 0x8000,
        entryCode = 0,
    )

    private fun room(roomId: Int, width: Int, height: Int) = Room(
        roomId = roomId,
        name = "Room",
        handle = "room",
        index = 0,
        area = 0,
        mapX = 0,
        mapY = 0,
        width = width,
        height = height,
        upScroller = 0x70,
        downScroller = 0xA0,
        creBitflag = 0,
        doorOut = 0,
    )
}
