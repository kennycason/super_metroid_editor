package com.supermetroid.editor.rom

import com.supermetroid.editor.data.ProjectRoomStateCondition
import com.supermetroid.editor.data.ProjectSourceDoorReference

/** Only active declarations are linkable. A retained/retired macro is not a connection. */
fun ProjectSourceDoorReference.requireIncomingDoor(targetRoomId: Int, doors: Map<Int, SourceRoomDoors>): SourceRoomDoor {
    val door = requireNotNull(doors[roomId]?.entries?.singleOrNull { it.identity == identity }) {
        "$entryLabel is not an active source-owned connection. Restore or retarget this reference."
    }
    require(door.change.destRoomPtr == targetRoomId) {
        "$entryLabel does not lead to room $${targetRoomId.toString(16).uppercase()}. Retarget its incoming-door reference."
    }
    return door
}

fun ProjectSourceDoorReference.resolveIncomingDoor(targetRoomId: Int, doors: Map<Int, SourceRoomDoors>,
                                                   placed: Map<ProjectSourceDoorReference, Int>): Int {
    requireIncomingDoor(targetRoomId, doors)
    return requireNotNull(placed[this]) { "No allocated DoorDef for $entryLabel." }.also {
        require(it in 0x8000..0xFFF4) { "Invalid allocated bank $83 DoorDef." }
    }
}

fun validateSourceSelectorDoors(selectors: SourceRoomSelectors, targetRoomId: Int, doors: Map<Int, SourceRoomDoors>) {
    selectors.conditions.values.flatMap { it.flattened() }.mapNotNull { it.sourceDoor }.distinct()
        .forEach { it.requireIncomingDoor(targetRoomId, doors) }
}

/** Ephemeral build projection. The caller supplies actual allocations, never preview pointers. */
fun ProjectRoomStateCondition.resolveSourceDoors(
    targetRoomId: Int,
    doors: Map<Int, SourceRoomDoors>,
    placed: Map<ProjectSourceDoorReference, Int>,
): ProjectRoomStateCondition {
    val pointer = sourceDoor?.let { reference ->
        require(kind == com.supermetroid.editor.data.ProjectRoomStateConditionKind.INCOMING_DOOR && argument == null) {
            "A symbolic DoorDef requires an incoming-door condition without a numeric argument."
        }
        reference.resolveIncomingDoor(targetRoomId, doors, placed)
    }
    return copy(argument = pointer ?: argument, sourceDoor = null,
        children = children.map { it.resolveSourceDoors(targetRoomId, doors, placed) })
}
