package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectSourceDoorReference
import com.supermetroid.editor.rom.RomParser

/** Read-only live choices. No source adoption, source writes or cached native-only lists. */
internal fun EditorState.incomingDoorConditionOptions(targetRoomId: Int, parser: RomParser): List<IncomingDoorConditionOption> {
    val ids = (parser.roomCatalog.rooms.map { it.getRoomIdAsInt() } + project.rooms.values.map { it.roomId }).distinct()
    return ids.flatMap { id ->
        val name = parser.readRoomHeader(id)?.name ?: return@flatMap emptyList()
        if (hasManagedDoors(id)) {
            val view = asmPlmSourceBridge?.doors?.view(id) ?: return@flatMap emptyList()
            // A last-good preview is useful on the canvas, but is not a link target.
            if (view.problem != null) return@flatMap emptyList()
            view.resource?.entries.orEmpty().filter { it.change.destRoomPtr == targetRoomId }.map { door ->
                IncomingDoorConditionOption(pointer = door.doorDefPtr.takeIf { it != 0 },
                    sourceDoor = ProjectSourceDoorReference(id, door.identity),
                    label = "$name · connection ${door.change.doorIndex + 1} (ID ${door.identity})")
            }
        } else effectiveDoorsForRoom(id, parser).mapIndexedNotNull { index, door ->
            if (door.destRoomPtr != targetRoomId || door.doorDefPtr == 0) null else
                IncomingDoorConditionOption.native(door.doorDefPtr,
                    "$name · connection ${index + 1} ($${door.doorDefPtr.toString(16).uppercase()})")
        }
    }.distinctBy { it.sourceDoor ?: it.pointer }
}
