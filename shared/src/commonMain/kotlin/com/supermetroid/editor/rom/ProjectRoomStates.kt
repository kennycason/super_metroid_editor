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
        fun projectCondition(runtime: RoomStateCondition): ProjectRoomStateCondition =
            projectRoomStateCondition(
                kind = ProjectRoomStateConditionKind.valueOf(runtime.kind.name),
                argument = runtime.argument,
                negated = runtime.negated,
                children = runtime.children.map(::projectCondition),
            )
        val projectCondition = projectCondition(condition)
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

/**
 * Human-readable condition summary shared by the editor, diagnostics, and any
 * future non-Compose client. Keeping this beside the semantic model prevents
 * each view from maintaining its own condition-name switch.
 */
fun ProjectRoomStateCondition.displaySummary(area: Int): String =
    asRuntimeCondition().shortSummary(area)

private fun ProjectRoomStateCondition.asRuntimeCondition(): RoomStateCondition =
    RoomStateCondition(
        code = routineCode,
        kind = RoomStateConditionKind.valueOf(kind.name),
        argumentKind = RoomStateConditionArgumentKind.valueOf(argumentKind.name),
        argument = argument,
        entrySizeBytes = 0,
        negated = negated,
        children = children.map { it.asRuntimeCondition() },
    )

fun ProjectRoomStateCondition.encodedSizeBytes(): Int = when (kind) {
    ProjectRoomStateConditionKind.ALL_OF,
    ProjectRoomStateConditionKind.ANY_OF,
    ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED,
    ProjectRoomStateConditionKind.BEAM_EQUIPPED,
    ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST,
    ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST,
    ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST,
    ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST,
    ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST,
    ProjectRoomStateConditionKind.DOOR_BIT_SET,
    ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED,
    ProjectRoomStateConditionKind.ESCAPE_ACTIVE -> SmEditCompiledRoomStateConditionFormat.ENTRY_SIZE_BYTES
    ProjectRoomStateConditionKind.DEFAULT -> 2
    ProjectRoomStateConditionKind.INCOMING_DOOR -> 6
    ProjectRoomStateConditionKind.EVENT_SET,
    ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET -> 5
    ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED,
    ProjectRoomStateConditionKind.BEAM_COLLECTED,
    ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED,
    ProjectRoomStateConditionKind.BOSS_DEFEATED -> SmEditRoomStatePredicateFormat.ENTRY_SIZE_BYTES
    else -> if (requiresCompiledExpression()) SmEditCompiledRoomStateConditionFormat.ENTRY_SIZE_BYTES else 4
}

fun ProjectRoomStateConditionKind.isSmEditGeneratedPredicate(): Boolean = when (this) {
    ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED,
    ProjectRoomStateConditionKind.BEAM_COLLECTED,
    ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED,
    ProjectRoomStateConditionKind.BOSS_DEFEATED -> true
    else -> false
}

/** True when this condition uses SMEDIT's postfix expression interpreter rather than a fixed-width selector. */
fun ProjectRoomStateCondition.requiresCompiledExpression(): Boolean =
    kind in setOf(
        ProjectRoomStateConditionKind.ALL_OF,
        ProjectRoomStateConditionKind.ANY_OF,
        ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED,
        ProjectRoomStateConditionKind.BEAM_EQUIPPED,
        ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST,
        ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST,
        ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST,
        ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST,
        ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST,
        ProjectRoomStateConditionKind.DOOR_BIT_SET,
        ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED,
        ProjectRoomStateConditionKind.ESCAPE_ACTIVE,
    ) || (negated && !kind.isSmEditGeneratedPredicate()) || children.isNotEmpty()

fun ProjectRoomStateCondition.usesSmEditRuntime(): Boolean =
    kind.isSmEditGeneratedPredicate() || requiresCompiledExpression()

fun packedBossConditionArgument(area: Int, mask: Int): Int =
    ((area and 0xFF) shl 8) or (mask and 0xFF)

fun projectRoomStateCondition(
    kind: ProjectRoomStateConditionKind,
    argument: Int? = null,
    negated: Boolean = false,
    children: List<ProjectRoomStateCondition> = emptyList(),
): ProjectRoomStateCondition {
    val base = when (kind) {
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
    ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.EQUIPMENT_MASK, argument ?: 0x0004, 0,
    )
    ProjectRoomStateConditionKind.BEAM_COLLECTED -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.BEAM_MASK, argument ?: 0x1000, 0,
    )
    ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST,
    ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.CAPACITY, argument ?: 5, 0,
    )
    ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.CAPACITY, argument ?: 199, 0,
    )
    ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.CAPACITY, argument ?: 100, 0,
    )
    ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.ITEM_BIT_INDEX, argument ?: 0, 0,
    )
    ProjectRoomStateConditionKind.BOSS_DEFEATED -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.AREA_AND_BOSS_MASK,
        argument ?: packedBossConditionArgument(0, 0x04), 0,
    )
    ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.EQUIPMENT_MASK, argument ?: 0x0001, 0,
    )
    ProjectRoomStateConditionKind.BEAM_EQUIPPED -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.BEAM_MASK, argument ?: 0x1000, 0,
    )
    ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.CAPACITY, argument ?: 99, 0,
    )
    ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST,
    ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST,
    ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.CAPACITY, argument ?: 1, 0,
    )
    ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.CAPACITY, argument ?: 1, 0,
    )
    ProjectRoomStateConditionKind.DOOR_BIT_SET -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.DOOR_BIT_INDEX, argument ?: 0, 0,
    )
    ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.CHOZO_BLOCK_BIT_INDEX, argument ?: 0, 0,
    )
    ProjectRoomStateConditionKind.ESCAPE_ACTIVE -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.NONE, null, 0,
    )
    ProjectRoomStateConditionKind.ALL_OF,
    ProjectRoomStateConditionKind.ANY_OF -> ProjectRoomStateCondition(
        kind, ProjectRoomStateConditionArgumentKind.CHILDREN, null, 0,
    )
    }
    val compiled = kind in setOf(
        ProjectRoomStateConditionKind.ALL_OF,
        ProjectRoomStateConditionKind.ANY_OF,
        ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED,
        ProjectRoomStateConditionKind.BEAM_EQUIPPED,
        ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST,
        ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST,
        ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST,
        ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST,
        ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST,
        ProjectRoomStateConditionKind.DOOR_BIT_SET,
        ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED,
        ProjectRoomStateConditionKind.ESCAPE_ACTIVE,
    ) || children.isNotEmpty() || (negated && !kind.isSmEditGeneratedPredicate())
    return base.copy(
        routineCode = if (compiled) 0 else base.routineCode,
        negated = negated,
        children = children,
    )
}
