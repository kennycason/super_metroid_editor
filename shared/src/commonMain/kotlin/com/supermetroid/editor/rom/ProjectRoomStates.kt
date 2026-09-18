package com.supermetroid.editor.rom

import com.supermetroid.editor.data.ProjectRoomStateCondition
import com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.RoomEdits
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.data.RoomStateResourceLinks

/**
 * Materialize the ROM's ordered state graph into stable project identities.
 * Merely materializing a graph is not an edit; [RoomStateEdits.hasEdits]
 * remains false until a field, condition, or resource link changes.
 */
fun RoomEdits.ensureStateManifest(parser: RomParser): List<RoomStateEdits> {
    if (states.isNotEmpty()) return states

    val inspection = parser.inspectRoomStates(roomId)
    check(inspection.isComplete) {
        val detail = inspection.issues.joinToString { it.message }.ifBlank { "missing default state" }
        "Room 0x${roomId.toString(16).uppercase()} state graph cannot be edited safely: $detail"
    }

    val resourceIds = mutableMapOf<String, MutableMap<Int, String>>()
    fun resourceId(kind: String, pointer: Int): String {
        val byPointer = resourceIds.getOrPut(kind) { linkedMapOf() }
        return byPointer.getOrPut(pointer) { "$kind-${byPointer.size + 1}" }
    }

    for ((sourceIndex, inspected) in inspection.states.withIndex()) {
        val stateOffset = checkNotNull(inspected.stateDataPcOffset) {
            "Room 0x${roomId.toString(16).uppercase()} state $sourceIndex has no readable state record"
        }
        val data = parser.readStateData(stateOffset)
        val condition = inspected.condition
        val projectCondition = ProjectRoomStateCondition(
            kind = ProjectRoomStateConditionKind.valueOf(condition.kind.name),
            argumentKind = ProjectRoomStateConditionArgumentKind.valueOf(condition.argumentKind.name),
            argument = condition.argument,
            routineCode = condition.code,
        )
        states += RoomStateEdits(
            id = "state-${sourceIndex + 1}",
            sourceStateIndex = sourceIndex,
            sourceCondition = projectCondition,
            condition = projectCondition,
            resources = RoomStateResourceLinks(
                level = resourceId("level", data.getValue("levelDataPtr")),
                effects = resourceId("effects", data.getValue("fxPtr")),
                enemies = resourceId("enemies", data.getValue("enemySetPtr")),
                enemyGraphics = resourceId("enemy-gfx", data.getValue("enemyGfxPtr")),
                scrolling = resourceId("scrolls", data.getValue("roomScrollsPtr")),
                placedObjects = resourceId("plms", data.getValue("plmSetPtr")),
                background = resourceId("background", data.getValue("bgDataPtr")),
                specialXray = resourceId("xray", data.getValue("xraySpecialCasingPtr")),
            ),
        )
    }
    return states
}

fun RoomEdits.stateEditsForSourceIndex(sourceStateIndex: Int): RoomStateEdits? =
    states.firstOrNull { it.sourceStateIndex == sourceStateIndex }

fun RoomEdits.stateEditsForId(stateId: String?): RoomStateEdits? =
    stateId?.let { id -> states.firstOrNull { it.id == id } }

fun RoomStateEdits.baseSourceStateIndex(): Int? = sourceStateIndex ?: templateSourceStateIndex

fun ProjectRoomStateCondition.encodedSizeBytes(): Int = when (kind) {
    ProjectRoomStateConditionKind.DEFAULT -> 2
    ProjectRoomStateConditionKind.INCOMING_DOOR -> 6
    ProjectRoomStateConditionKind.EVENT_SET,
    ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET -> 5
    else -> 4
}

fun projectRoomStateCondition(
    kind: ProjectRoomStateConditionKind,
    argument: Int? = null,
): ProjectRoomStateCondition = when (kind) {
    ProjectRoomStateConditionKind.DEFAULT -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.NONE, null, 0xE5E6,
    )
    ProjectRoomStateConditionKind.INCOMING_DOOR -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.DOOR_POINTER, argument ?: 0, 0xE5EB,
    )
    ProjectRoomStateConditionKind.AREA_MAIN_BOSS_DEAD -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.NONE, null, 0xE5FF,
    )
    ProjectRoomStateConditionKind.NEVER -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.NONE, null, 0xE60F,
    )
    ProjectRoomStateConditionKind.EVENT_SET -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.EVENT_ID, argument ?: 0, 0xE612,
    )
    ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.BOSS_BIT_MASK, argument ?: 1, 0xE629,
    )
    ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.NONE, null, 0xE640,
    )
    ProjectRoomStateConditionKind.MORPH_BALL_AND_MISSILES -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.NONE, null, 0xE652,
    )
    ProjectRoomStateConditionKind.POWER_BOMBS_COLLECTED -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.NONE, null, 0xE669,
    )
    ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.NONE, null, 0xE678,
    )
}
