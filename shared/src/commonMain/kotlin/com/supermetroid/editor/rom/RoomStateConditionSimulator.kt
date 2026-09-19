package com.supermetroid.editor.rom

import com.supermetroid.editor.data.ProjectRoomStateCondition
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.RoomStateEdits

/** User-controlled snapshot evaluated exactly like the load-time room selector graph. */
data class RoomStateSimulationContext(
    val area: Int,
    val incomingDoorPointer: Int? = null,
    val events: Set<Int> = emptySet(),
    val bossBitsByArea: Map<Int, Int> = emptyMap(),
    val collectedEquipment: Int = 0,
    val equippedEquipment: Int = 0,
    val collectedBeams: Int = 0,
    val equippedBeams: Int = 0,
    val maxEnergy: Int = 99,
    val currentEnergy: Int = 99,
    val maxMissiles: Int = 0,
    val currentMissiles: Int = 0,
    val maxSuperMissiles: Int = 0,
    val currentSuperMissiles: Int = 0,
    val maxPowerBombs: Int = 0,
    val currentPowerBombs: Int = 0,
    val maxReserveEnergy: Int = 0,
    val currentReserveEnergy: Int = 0,
    val collectedItemPickupIds: Set<Int> = emptySet(),
    val openedDoorIds: Set<Int> = emptySet(),
    val destroyedChozoBlockIds: Set<Int> = emptySet(),
)

data class RoomStateSimulationBranch(
    val stateId: String,
    val condition: ProjectRoomStateCondition,
    val matches: Boolean,
    val selected: Boolean,
)

fun ProjectRoomStateCondition.matches(context: RoomStateSimulationContext): Boolean {
    val argumentValue = argument ?: 0
    val raw = when (kind) {
        ProjectRoomStateConditionKind.DEFAULT -> true
        ProjectRoomStateConditionKind.INCOMING_DOOR -> context.incomingDoorPointer == argument
        ProjectRoomStateConditionKind.AREA_MAIN_BOSS_DEAD ->
            (context.bossBitsByArea[context.area] ?: 0) and 0x01 != 0
        ProjectRoomStateConditionKind.NEVER -> false
        ProjectRoomStateConditionKind.EVENT_SET -> argumentValue in context.events
        ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET ->
            (context.bossBitsByArea[context.area] ?: 0) and argumentValue != 0
        ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED -> context.collectedEquipment and 0x0004 != 0
        ProjectRoomStateConditionKind.MORPH_BALL_AND_MISSILES ->
            context.collectedEquipment and 0x0004 != 0 && context.maxMissiles != 0
        ProjectRoomStateConditionKind.POWER_BOMBS_COLLECTED -> context.maxPowerBombs != 0
        ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED -> context.collectedEquipment and 0x2000 != 0
        ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED -> context.collectedEquipment and argumentValue != 0
        ProjectRoomStateConditionKind.BEAM_COLLECTED -> context.collectedBeams and argumentValue != 0
        ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST -> context.maxMissiles >= argumentValue
        ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST -> context.maxSuperMissiles >= argumentValue
        ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST -> context.maxPowerBombs >= argumentValue
        ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST -> context.maxEnergy >= argumentValue
        ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST -> context.maxReserveEnergy >= argumentValue
        ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED -> argumentValue in context.collectedItemPickupIds
        ProjectRoomStateConditionKind.BOSS_DEFEATED -> {
            val area = (argumentValue ushr 8) and 0xFF
            val mask = argumentValue and 0xFF
            (context.bossBitsByArea[area] ?: 0) and mask != 0
        }
        ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED -> context.equippedEquipment and argumentValue != 0
        ProjectRoomStateConditionKind.BEAM_EQUIPPED -> context.equippedBeams and argumentValue != 0
        ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST -> context.currentEnergy >= argumentValue
        ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST -> context.currentMissiles >= argumentValue
        ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST ->
            context.currentSuperMissiles >= argumentValue
        ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST -> context.currentPowerBombs >= argumentValue
        ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST ->
            context.currentReserveEnergy >= argumentValue
        ProjectRoomStateConditionKind.DOOR_BIT_SET -> argumentValue in context.openedDoorIds
        ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED -> argumentValue in context.destroyedChozoBlockIds
        ProjectRoomStateConditionKind.ESCAPE_ACTIVE -> 0x0E in context.events
        ProjectRoomStateConditionKind.ALL_OF -> children.isNotEmpty() && children.all { it.matches(context) }
        ProjectRoomStateConditionKind.ANY_OF -> children.any { it.matches(context) }
    }
    return if (negated) !raw else raw
}

fun simulateRoomStateGraph(
    states: List<RoomStateEdits>,
    context: RoomStateSimulationContext,
): List<RoomStateSimulationBranch> {
    var winnerFound = false
    return states.map { state ->
        val matches = state.condition.matches(context)
        val selected = matches && !winnerFound
        if (selected) winnerFound = true
        RoomStateSimulationBranch(state.id, state.condition, matches, selected)
    }
}

fun ProjectRoomStateCondition.flattened(): List<ProjectRoomStateCondition> =
    listOf(this) + children.flatMap { it.flattened() }
