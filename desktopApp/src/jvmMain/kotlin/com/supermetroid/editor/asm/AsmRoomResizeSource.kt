package com.supermetroid.editor.asm

import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.RomParser
import kotlinx.serialization.json.Json

/** Private transaction snapshots, never an additional persisted copy of authored values. */
internal fun snapshotRoomForResize(room: RoomEdits): RoomEdits =
    Json.decodeFromString(RoomEdits.serializer(), Json.encodeToString(RoomEdits.serializer(), room)).also { copy ->
        copy.asmHeaderPropertiesProjection = room.asmHeaderPropertiesProjection
        copy.asmSelectorOrderProjection = room.asmSelectorOrderProjection
        copy.asmDoorProjection = room.asmDoorProjection
        copy.states.zip(room.states).forEach { (target, source) ->
            target.asmStatePropertiesProjection = source.asmStatePropertiesProjection
            target.asmConditionProjection = source.asmConditionProjection
        }
    }

internal data class AsmRoomResizePlan(
    val owner: SmEditProject,
    val room: RoomEdits,
    val wasPresent: Boolean,
    val beforeModel: RoomEdits,
    val afterModel: RoomEdits,
    val sourceRevision: Long,
    val before: String,
    val after: String,
    val actorSource: String?,
    val gridsBefore: Map<String, List<Int>>,
    val gridsAfter: Map<String, List<Int>>,
    val objectsAfter: Map<String, List<RomParser.PlmEntry>>,
    val doors: AsmResizeDoorPlan,
    val externalReferences: Set<String>,
)

/** Screen indices encode coordinates using the old row stride. Cropped screens have no new index. */
internal fun resizeScrollCommands(commands: List<ScrollCommand>, oldWidth: Int, newWidth: Int, newHeight: Int): List<ScrollCommand> =
    commands.mapNotNull { command ->
        val x = command.screenIndex % oldWidth
        val y = command.screenIndex / oldWidth
        if (x >= newWidth || y >= newHeight) null else command.copy(screenIndex = y * newWidth + x)
    }

internal fun applyRoomResizeModel(room: RoomEdits, snapshot: RoomEdits) {
    AsmRoomBank8FMetadata.capture(snapshot).applyTo(room)
    room.roomHeaderChange = snapshot.roomHeaderChange
    room.asmHeaderPropertiesProjection = snapshot.asmHeaderPropertiesProjection
    room.asmDoorSourceVersion = snapshot.asmDoorSourceVersion
    room.asmDoorProjection = snapshot.asmDoorProjection
    room.doorChanges.clear(); room.doorChanges.addAll(snapshot.doorChanges)
    room.operations.clear(); room.operations.addAll(snapshot.operations)
    room.levelResourceOperations.clear()
    snapshot.levelResourceOperations.forEach { (id, operations) -> room.levelResourceOperations[id] = operations.toMutableList() }
    room.states.zip(snapshot.states).forEach { (state, saved) ->
        state.operations.clear(); state.operations.addAll(saved.operations)
    }
}
