package com.supermetroid.editor.ui

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser

internal enum class DoorConnectionSeverity {
    WARNING,
    ERROR,
}

internal enum class DoorConnectionIssueKind {
    DESTINATION_MISSING,
    ENTRANCE_OUT_OF_BOUNDS,
    DESTINATION_OPENING_MISSING,
    RETURN_LINK_MISSING,
    RETURN_LINK_FACES_WRONG_WAY,
}

internal data class DoorConnectionIssue(
    val kind: DoorConnectionIssueKind,
    val severity: DoorConnectionSeverity,
    val message: String,
)

internal data class DoorConnectionDiagnostic(
    val issues: List<DoorConnectionIssue>,
    val returnDoorIndices: List<Int> = emptyList(),
) {
    val needsAttention: Boolean get() = issues.isNotEmpty()
    val hasError: Boolean get() = issues.any { it.severity == DoorConnectionSeverity.ERROR }
}

/**
 * Validate one semantic room connection without changing either side.
 *
 * A non-reciprocal door is legal in Super Metroid, so it is deliberately a
 * warning rather than an error. The editor never creates or rewrites the
 * return link behind the user's back.
 */
internal fun evaluateDoorConnection(
    sourceRoomId: Int,
    sourceRoomName: String,
    door: RomParser.DoorEntry,
    destinationRoom: Room?,
    destinationRoomName: String,
    destinationDoors: List<RomParser.DoorEntry>,
    destinationOpeningCap: Int?,
): DoorConnectionDiagnostic {
    if (destinationRoom == null) {
        return DoorConnectionDiagnostic(
            issues = listOf(
                DoorConnectionIssue(
                    kind = DoorConnectionIssueKind.DESTINATION_MISSING,
                    severity = DoorConnectionSeverity.ERROR,
                    message = "The destination room could not be found.",
                ),
            ),
        )
    }

    val issues = mutableListOf<DoorConnectionIssue>()
    val entranceInBounds = door.screenX in 0 until destinationRoom.width &&
        door.screenY in 0 until destinationRoom.height
    if (!entranceInBounds) {
        issues += DoorConnectionIssue(
            kind = DoorConnectionIssueKind.ENTRANCE_OUT_OF_BOUNDS,
            severity = DoorConnectionSeverity.ERROR,
            message = "The entrance screen is outside $destinationRoomName's " +
                "${destinationRoom.width} × ${destinationRoom.height} screen bounds.",
        )
    } else if (destinationOpeningCap == null) {
        val edge = when (door.direction and 0x03) {
            0 -> "left"
            1 -> "right"
            2 -> "top"
            else -> "bottom"
        }
        issues += DoorConnectionIssue(
            kind = DoorConnectionIssueKind.DESTINATION_OPENING_MISSING,
            severity = DoorConnectionSeverity.WARNING,
            message = "No compatible doorway tiles were found on the $edge edge of the destination.",
        )
    }

    val returnDoors = destinationDoors.mapIndexedNotNull { index, candidate ->
        index.takeIf { candidate.destRoomPtr == sourceRoomId }
    }
    if (returnDoors.isEmpty()) {
        issues += DoorConnectionIssue(
            kind = DoorConnectionIssueKind.RETURN_LINK_MISSING,
            severity = DoorConnectionSeverity.WARNING,
            message = "$destinationRoomName has no door returning to $sourceRoomName. " +
                "This one-way connection may be intentional.",
        )
    } else {
        val expectedReturnDirection = oppositeDoorDirection(door.direction)
        val hasFacingReturn = returnDoors.any { index ->
            (destinationDoors[index].direction and 0x03) == expectedReturnDirection
        }
        if (!hasFacingReturn) {
            val expectedName = doorDirectionName(expectedReturnDirection)
            issues += DoorConnectionIssue(
                kind = DoorConnectionIssueKind.RETURN_LINK_FACES_WRONG_WAY,
                severity = DoorConnectionSeverity.WARNING,
                message = "A return door exists, but none enters $sourceRoomName facing $expectedName.",
            )
        }
    }

    return DoorConnectionDiagnostic(issues = issues, returnDoorIndices = returnDoors)
}

internal fun doorDiagnosticsForRoom(
    sourceRoomId: Int,
    parser: RomParser,
    editorState: EditorState,
    rooms: List<RoomInfo>,
): Map<Int, DoorConnectionDiagnostic> {
    val names = rooms.associate { it.getRoomIdAsInt() to it.name }
    val sourceName = names[sourceRoomId] ?: "this room"
    val destinationRooms = mutableMapOf<Int, Pair<Room, Room>?>()
    val destinationDoors = mutableMapOf<Int, List<RomParser.DoorEntry>>()
    val openingCaps = mutableMapOf<List<Int>, Int?>()
    return editorState.effectiveDoorsForRoom(sourceRoomId, parser)
        .mapIndexed { index, door ->
            val roomPair = destinationRooms.getOrPut(door.destRoomPtr) {
                parser.readRoomHeader(door.destRoomPtr)?.let { it to editorState.applyHeaderChanges(it) }
            }
            val destinationBase = roomPair?.first
            val destination = roomPair?.second
            val destinationName = names[door.destRoomPtr] ?: destination?.name ?: "the destination room"
            val effectiveDestinationDoors = destinationDoors.getOrPut(door.destRoomPtr) {
                editorState.effectiveDoorsForRoom(door.destRoomPtr, parser)
            }
            val openingCap = if (destinationBase == null || destination == null) null else {
                val key = listOf(door.destRoomPtr, door.direction and 0x03, door.screenX, door.screenY)
                openingCaps.getOrPut(key) {
                    editorState.buildEffectiveRoomGrids(parser, destinationBase, destination)?.let { grids ->
                        validateDoorOpening(grids, door)
                    }
                }
            }
            index to evaluateDoorConnection(
                sourceRoomId = sourceRoomId,
                sourceRoomName = sourceName,
                door = door,
                destinationRoom = destination,
                destinationRoomName = destinationName,
                destinationDoors = effectiveDestinationDoors,
                destinationOpeningCap = openingCap,
            )
        }
        .toMap()
}

internal fun oppositeDoorDirection(direction: Int): Int = when (direction and 0x03) {
    0 -> 1
    1 -> 0
    2 -> 3
    else -> 2
}

private fun doorDirectionName(direction: Int): String = when (direction and 0x03) {
    0 -> "right"
    1 -> "left"
    2 -> "down"
    else -> "up"
}

/** Validate the declared cap against the project's effective (possibly edited) doorway tiles. */
private fun validateDoorOpening(
    grids: RoomGrids,
    door: RomParser.DoorEntry,
): Int? {
    if (door.screenX !in 0 until (grids.width / 16) || door.screenY !in 0 until (grids.height / 16)) return null
    // Zero explicitly disables the closing-cap spawn (common for elevators and
    // scripted transitions); it is not a broken pointer or missing doorway.
    if (door.doorCapCode == 0) return 0
    val dir = door.direction and 0x03
    val capX = door.doorCapCode and 0xFF
    val capY = (door.doorCapCode shr 8) and 0xFF
    if (capX !in 0 until grids.width || capY !in 0 until grids.height) return null
    fun isDoor(x: Int, y: Int): Boolean =
        x in 0 until grids.width && y in 0 until grids.height &&
            ((grids.words[y * grids.width + x] shr 12) and 0x0F) == 0x09
    val declaredCapTouchesDoor = when (dir) {
        // Horizontal caps sit one block inward; vertical caps sit two.
        0 -> isDoor(capX - 1, capY)
        1 -> isDoor(capX + 1, capY)
        2 -> isDoor(capX, capY - 2)
        else -> isDoor(capX, capY + 2)
    }
    // Scripted/boss transitions use unusual cap offsets. They are still sound
    // when the selected entrance edge contains a real type-9 doorway.
    val screenLeft = door.screenX * 16
    val screenTop = door.screenY * 16
    val edgeHasDoor = when (dir) {
        0 -> (screenTop until screenTop + 16).any { y -> isDoor(screenLeft, y) }
        1 -> (screenTop until screenTop + 16).any { y -> isDoor(screenLeft + 15, y) }
        2 -> (screenLeft until screenLeft + 16).any { x -> isDoor(x, screenTop) }
        else -> (screenLeft until screenLeft + 16).any { x -> isDoor(x, screenTop + 15) }
    }
    return door.doorCapCode.takeIf { declaredCapTouchesDoor || edgeHasDoor }
}
