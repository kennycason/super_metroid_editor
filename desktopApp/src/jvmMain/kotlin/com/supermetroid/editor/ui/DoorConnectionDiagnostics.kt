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

/** One contiguous type-9 opening on the edge used by an incoming door. */
internal data class DoorwayOpening(
    val roomId: Int,
    val direction: Int,
    val screenX: Int,
    val screenY: Int,
    val blockX: Int,
    val blockY: Int,
    val tileCount: Int,
    val doorCapCode: Int,
    val connectionIndex: Int,
)

internal fun incomingDoorEdgeName(direction: Int): String = when (direction and 0x03) {
    0 -> "left"
    1 -> "right"
    2 -> "top"
    else -> "bottom"
}

/**
 * Find real destination openings instead of inferring them from existing
 * DoorDefs. This deliberately includes unlinked type-9 openings in new rooms.
 */
internal fun doorwayOpenings(
    roomId: Int,
    grids: RoomGrids,
    direction: Int,
): List<DoorwayOpening> {
    val dir = direction and 0x03
    val screensWide = grids.width / 16
    val screensTall = grids.height / 16
    fun isDoor(x: Int, y: Int): Boolean =
        x in 0 until grids.width && y in 0 until grids.height &&
            ((grids.words[y * grids.width + x] ushr 12) and 0x0F) == 0x09

    val result = mutableListOf<DoorwayOpening>()
    for (screenY in 0 until screensTall) {
        for (screenX in 0 until screensWide) {
            val edgePositions = when (dir) {
                0 -> (0 until 16).map { screenX * 16 to screenY * 16 + it }
                1 -> (0 until 16).map { screenX * 16 + 15 to screenY * 16 + it }
                2 -> (0 until 16).map { screenX * 16 + it to screenY * 16 }
                else -> (0 until 16).map { screenX * 16 + it to screenY * 16 + 15 }
            }
            var runStart = -1
            for (position in 0..edgePositions.size) {
                val onDoor = position < edgePositions.size &&
                    edgePositions[position].let { (x, y) -> isDoor(x, y) }
                if (onDoor && runStart < 0) runStart = position
                if (!onDoor && runStart >= 0) {
                    val (blockX, blockY) = edgePositions[runStart]
                    val tileCount = position - runStart
                    val capX = when (dir) {
                        0 -> blockX + 1
                        1 -> blockX - 1
                        else -> blockX
                    }.coerceIn(0, grids.width - 1)
                    val capY = when (dir) {
                        2 -> blockY + 2
                        3 -> blockY - 2
                        else -> blockY
                    }.coerceIn(0, grids.height - 1)
                    result += DoorwayOpening(
                        roomId = roomId,
                        direction = dir,
                        screenX = screenX,
                        screenY = screenY,
                        blockX = blockX,
                        blockY = blockY,
                        tileCount = tileCount,
                        doorCapCode = (capY shl 8) or capX,
                        connectionIndex = grids.bts[blockY * grids.width + blockX],
                    )
                    runStart = -1
                }
            }
        }
    }
    return result
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
    destinationOpeningConnectionIndex: Int?,
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

    val returnDoors: List<Int>
    if (destinationOpeningConnectionIndex != null) {
        val returnDoor = destinationDoors.getOrNull(destinationOpeningConnectionIndex)
        returnDoors = if (returnDoor?.destRoomPtr == sourceRoomId) {
            listOf(destinationOpeningConnectionIndex)
        } else {
            emptyList()
        }
        if (returnDoor == null) {
            issues += DoorConnectionIssue(
                kind = DoorConnectionIssueKind.RETURN_LINK_MISSING,
                severity = DoorConnectionSeverity.WARNING,
                message = "The selected $destinationRoomName doorway stores Connection " +
                    "${destinationOpeningConnectionIndex + 1}, but that connection does not exist.",
            )
        } else if (returnDoor.destRoomPtr != sourceRoomId) {
            issues += DoorConnectionIssue(
                kind = DoorConnectionIssueKind.RETURN_LINK_MISSING,
                severity = DoorConnectionSeverity.WARNING,
                message = "The selected $destinationRoomName doorway's Connection " +
                    "${destinationOpeningConnectionIndex + 1} leads somewhere else instead of returning to $sourceRoomName.",
            )
        }
    } else {
        // Scripted/elevator transitions may not have a normal type-9 opening.
        // Retain the room-level fallback only when there is no physical opening
        // whose BTS can identify the exact return connection.
        returnDoors = destinationDoors.mapIndexedNotNull { index, candidate ->
            index.takeIf { candidate.destRoomPtr == sourceRoomId }
        }
        if (returnDoors.isEmpty()) {
            issues += DoorConnectionIssue(
                kind = DoorConnectionIssueKind.RETURN_LINK_MISSING,
                severity = DoorConnectionSeverity.WARNING,
                message = "$destinationRoomName has no door returning to $sourceRoomName. " +
                    "This one-way connection may be intentional.",
            )
        }
    }

    if (returnDoors.isNotEmpty()) {
        val expectedReturnDirection = oppositeDoorDirection(door.direction)
        val hasFacingReturn = returnDoors.any { index ->
            (destinationDoors[index].direction and 0x03) == expectedReturnDirection
        }
        if (!hasFacingReturn) {
            val expectedName = doorDirectionName(expectedReturnDirection)
            issues += DoorConnectionIssue(
                kind = DoorConnectionIssueKind.RETURN_LINK_FACES_WRONG_WAY,
                severity = DoorConnectionSeverity.WARNING,
                message = "The selected return connection enters $sourceRoomName facing the wrong way; " +
                    "expected $expectedName.",
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
    val destinationOpenings = mutableMapOf<List<Int>, DoorwayOpening?>()
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
            val opening = if (destinationBase == null || destination == null) null else {
                val key = listOf(
                    door.destRoomPtr,
                    door.direction and 0x03,
                    door.screenX,
                    door.screenY,
                    door.doorCapCode,
                )
                destinationOpenings.getOrPut(key) {
                    val choices = editorState.effectiveDoorwayOpenings(
                        door.destRoomPtr, door.direction, parser,
                    ).filter { it.screenX == door.screenX && it.screenY == door.screenY }
                    choices.firstOrNull { it.doorCapCode == door.doorCapCode }
                        ?: choices.firstOrNull()
                }
            }
            val openingCap = if (door.doorCapCode == 0) 0 else opening?.doorCapCode
            index to evaluateDoorConnection(
                sourceRoomId = sourceRoomId,
                sourceRoomName = sourceName,
                door = door,
                destinationRoom = destination,
                destinationRoomName = destinationName,
                destinationDoors = effectiveDestinationDoors,
                destinationOpeningCap = openingCap,
                destinationOpeningConnectionIndex = opening?.connectionIndex,
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
