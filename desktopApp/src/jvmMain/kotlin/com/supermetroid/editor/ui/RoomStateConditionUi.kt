package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.ProjectRoomStateCondition
import com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.rom.RoomStateCondition
import com.supermetroid.editor.rom.RoomStateSimulationContext
import com.supermetroid.editor.rom.displaySummary
import com.supermetroid.editor.rom.projectRoomStateCondition
import com.supermetroid.editor.rom.packedBossConditionArgument
import com.supermetroid.editor.rom.flattened
import com.supermetroid.editor.rom.matches

internal data class ItemPickupConditionOption(
    val id: Int,
    val label: String,
)

internal data class StateConditionKindPickerGroup(
    val title: String,
    val kinds: List<ProjectRoomStateConditionKind>,
)

internal val STATE_CONDITION_KIND_PICKER_GROUPS = listOf(
    StateConditionKindPickerGroup(
        "World & room",
        listOf(
            ProjectRoomStateConditionKind.INCOMING_DOOR,
            ProjectRoomStateConditionKind.EVENT_SET,
            ProjectRoomStateConditionKind.ESCAPE_ACTIVE,
            ProjectRoomStateConditionKind.DOOR_BIT_SET,
            ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED,
            ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED,
        ),
    ),
    StateConditionKindPickerGroup(
        "Bosses",
        listOf(
            ProjectRoomStateConditionKind.AREA_MAIN_BOSS_DEAD,
            ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET,
            ProjectRoomStateConditionKind.BOSS_DEFEATED,
        ),
    ),
    StateConditionKindPickerGroup(
        "Collected gear",
        listOf(
            ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED,
            ProjectRoomStateConditionKind.BEAM_COLLECTED,
            ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED,
            ProjectRoomStateConditionKind.MORPH_BALL_AND_MISSILES,
            ProjectRoomStateConditionKind.POWER_BOMBS_COLLECTED,
            ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED,
        ),
    ),
    StateConditionKindPickerGroup(
        "Equipped gear",
        listOf(
            ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED,
            ProjectRoomStateConditionKind.BEAM_EQUIPPED,
        ),
    ),
    StateConditionKindPickerGroup(
        "Maximum capacity",
        listOf(
            ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST,
            ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST,
            ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST,
            ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST,
            ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST,
        ),
    ),
    StateConditionKindPickerGroup(
        "Current health & ammo",
        listOf(
            ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST,
            ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST,
            ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST,
            ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST,
            ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST,
        ),
    ),
    StateConditionKindPickerGroup(
        "Advanced",
        listOf(ProjectRoomStateConditionKind.NEVER),
    ),
)

internal fun stateConditionKindPickerLabel(kind: ProjectRoomStateConditionKind): String = when (kind) {
    ProjectRoomStateConditionKind.DEFAULT -> "Default"
    ProjectRoomStateConditionKind.INCOMING_DOOR -> "Entered through a specific door"
    ProjectRoomStateConditionKind.AREA_MAIN_BOSS_DEAD -> "Primary boss defeated (this area)"
    ProjectRoomStateConditionKind.NEVER -> "Never (always false)"
    ProjectRoomStateConditionKind.EVENT_SET -> "Event is set"
    ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET -> "Boss flag set (this area)"
    ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED -> "Morph Ball collected"
    ProjectRoomStateConditionKind.MORPH_BALL_AND_MISSILES -> "Morph Ball + missiles collected"
    ProjectRoomStateConditionKind.POWER_BOMBS_COLLECTED -> "Power Bombs collected"
    ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED -> "Speed Booster collected"
    ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED -> "Equipment collected"
    ProjectRoomStateConditionKind.BEAM_COLLECTED -> "Beam collected"
    ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST -> "Missile capacity ≥ N"
    ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST -> "Super Missile capacity ≥ N"
    ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST -> "Power Bomb capacity ≥ N"
    ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST -> "Energy capacity ≥ N"
    ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST -> "Reserve capacity ≥ N"
    ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED -> "Specific item pickup collected"
    ProjectRoomStateConditionKind.BOSS_DEFEATED -> "Boss defeated (any area)"
    ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED -> "Equipment equipped"
    ProjectRoomStateConditionKind.BEAM_EQUIPPED -> "Beam equipped"
    ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST -> "Current energy ≥ N"
    ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST -> "Current missiles ≥ N"
    ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST -> "Current Super Missiles ≥ N"
    ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST -> "Current Power Bombs ≥ N"
    ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST -> "Current reserve energy ≥ N"
    ProjectRoomStateConditionKind.DOOR_BIT_SET -> "Persistent door flag set"
    ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED -> "Chozo block destroyed"
    ProjectRoomStateConditionKind.ESCAPE_ACTIVE -> "Escape active"
    ProjectRoomStateConditionKind.ALL_OF -> "All conditions (AND)"
    ProjectRoomStateConditionKind.ANY_OF -> "Any condition (OR)"
}

// ── State Condition Editor ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomStateSimulatorDialog(
    states: List<Pair<String, ProjectRoomStateCondition>>,
    area: Int,
    itemPickupOptions: List<ItemPickupConditionOption>,
    onDismiss: () -> Unit,
) {
    var context by remember(states, area) {
        mutableStateOf(RoomStateSimulationContext(area = area, maxEnergy = 99, currentEnergy = 99))
    }
    val leaves = states.flatMap { it.second.flattened() }
        .filter { it.kind != ProjectRoomStateConditionKind.ALL_OF && it.kind != ProjectRoomStateConditionKind.ANY_OF }
    val matches = states.map { it.second.matches(context) }
    val winner = matches.indexOfFirst { it }

    fun toggleSet(source: Set<Int>, value: Int, checked: Boolean): Set<Int> =
        if (checked) source + value else source - value

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Simulate room load") },
        text = {
            Column(
                modifier = Modifier.requiredSizeIn(maxHeight = 680.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    "Set the load-time values below. The first matching branch wins; later matching " +
                        "branches are intentionally skipped.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                states.forEachIndexed { index, (_, condition) ->
                    val status = when {
                        index == winner -> "SELECTED"
                        matches[index] -> "matches later"
                        else -> "does not match"
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (index == winner) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                            Text(
                                projectConditionLabel(condition, area),
                                fontSize = ROOM_INFO_BODY_FONT_SIZE,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                status,
                                fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                                fontWeight = if (index == winner) FontWeight.Bold else FontWeight.Normal,
                                color = if (index == winner) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Divider()
                Text("Inputs", fontWeight = FontWeight.SemiBold)

                val incomingDoors = leaves.filter { it.kind == ProjectRoomStateConditionKind.INCOMING_DOOR }
                    .mapNotNull { it.argument }.distinct()
                if (incomingDoors.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Incoming door", modifier = Modifier.width(130.dp), fontSize = ROOM_INFO_BODY_FONT_SIZE)
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            modifier = Modifier.weight(1f),
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().menuAnchor().clickable { expanded = true },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.extraSmall,
                            ) {
                                Text(
                                    context.incomingDoorPointer?.let {
                                        "Door \$${it.toString(16).uppercase().padStart(4, '0')}"
                                    } ?: "None / another door",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    fontSize = ROOM_INFO_BODY_FONT_SIZE,
                                )
                            }
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("None / another door") },
                                    onClick = { context = context.copy(incomingDoorPointer = null); expanded = false },
                                )
                                incomingDoors.forEach { door ->
                                    DropdownMenuItem(
                                        text = { Text("Door \$${door.toString(16).uppercase().padStart(4, '0')}") },
                                        onClick = {
                                            context = context.copy(incomingDoorPointer = door)
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                val eventIds = buildSet {
                    leaves.filter { it.kind == ProjectRoomStateConditionKind.EVENT_SET }
                        .mapNotNullTo(this) { it.argument }
                    if (leaves.any { it.kind == ProjectRoomStateConditionKind.ESCAPE_ACTIVE }) add(0x0E)
                }
                eventIds.sorted().forEach { event ->
                    SimulatorCheckboxRow(
                        label = RoomStateCondition.EVENT_NAMES[event] ?: "Event \$${event.toString(16)}",
                        checked = event in context.events,
                        onCheckedChange = { context = context.copy(events = toggleSet(context.events, event, it)) },
                    )
                }

                val collectedEquipmentMasks = buildSet {
                    leaves.filter { it.kind == ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED }
                        .mapNotNullTo(this) { it.argument }
                    if (leaves.any { it.kind == ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED ||
                            it.kind == ProjectRoomStateConditionKind.MORPH_BALL_AND_MISSILES }) add(0x0004)
                    if (leaves.any { it.kind == ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED }) add(0x2000)
                }
                collectedEquipmentMasks.forEach { mask ->
                    SimulatorMaskRow(
                        label = "${RoomStateCondition.EQUIPMENT_NAMES[mask] ?: "Equipment"} collected",
                        mask = mask,
                        value = context.collectedEquipment,
                        onValueChange = { context = context.copy(collectedEquipment = it) },
                    )
                }
                leaves.filter { it.kind == ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED }
                    .mapNotNull { it.argument }.distinct().forEach { mask ->
                        SimulatorMaskRow(
                            label = "${RoomStateCondition.EQUIPMENT_NAMES[mask] ?: "Equipment"} equipped",
                            mask = mask,
                            value = context.equippedEquipment,
                            onValueChange = { context = context.copy(equippedEquipment = it) },
                        )
                    }
                leaves.filter { it.kind == ProjectRoomStateConditionKind.BEAM_COLLECTED }
                    .mapNotNull { it.argument }.distinct().forEach { mask ->
                        SimulatorMaskRow(
                            label = "${RoomStateCondition.BEAM_NAMES[mask] ?: "Beam"} collected",
                            mask = mask,
                            value = context.collectedBeams,
                            onValueChange = { context = context.copy(collectedBeams = it) },
                        )
                    }
                leaves.filter { it.kind == ProjectRoomStateConditionKind.BEAM_EQUIPPED }
                    .mapNotNull { it.argument }.distinct().forEach { mask ->
                        SimulatorMaskRow(
                            label = "${RoomStateCondition.BEAM_NAMES[mask] ?: "Beam"} equipped",
                            mask = mask,
                            value = context.equippedBeams,
                            onValueChange = { context = context.copy(equippedBeams = it) },
                        )
                    }

                fun needs(kind: ProjectRoomStateConditionKind): Boolean = leaves.any { it.kind == kind }
                if (needs(ProjectRoomStateConditionKind.MORPH_BALL_AND_MISSILES) ||
                    needs(ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST)
                ) SimulatorValueRow("Maximum missiles", context.maxMissiles) {
                    context = context.copy(maxMissiles = it)
                }
                if (needs(ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST)) {
                    SimulatorValueRow("Current missiles", context.currentMissiles) {
                        context = context.copy(currentMissiles = it)
                    }
                }
                if (needs(ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST)) {
                    SimulatorValueRow("Maximum Supers", context.maxSuperMissiles) {
                        context = context.copy(maxSuperMissiles = it)
                    }
                }
                if (needs(ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST)) {
                    SimulatorValueRow("Current Supers", context.currentSuperMissiles) {
                        context = context.copy(currentSuperMissiles = it)
                    }
                }
                if (needs(ProjectRoomStateConditionKind.POWER_BOMBS_COLLECTED) ||
                    needs(ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST)
                ) SimulatorValueRow("Maximum Power Bombs", context.maxPowerBombs) {
                    context = context.copy(maxPowerBombs = it)
                }
                if (needs(ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST)) {
                    SimulatorValueRow("Current Power Bombs", context.currentPowerBombs) {
                        context = context.copy(currentPowerBombs = it)
                    }
                }
                if (needs(ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST)) {
                    SimulatorValueRow("Maximum energy", context.maxEnergy) { context = context.copy(maxEnergy = it) }
                }
                if (needs(ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST)) {
                    SimulatorValueRow("Current energy", context.currentEnergy) {
                        context = context.copy(currentEnergy = it)
                    }
                }
                if (needs(ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST)) {
                    SimulatorValueRow("Maximum reserve", context.maxReserveEnergy) {
                        context = context.copy(maxReserveEnergy = it)
                    }
                }
                if (needs(ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST)) {
                    SimulatorValueRow("Current reserve", context.currentReserveEnergy) {
                        context = context.copy(currentReserveEnergy = it)
                    }
                }

                val itemLabels = itemPickupOptions.associate { it.id to it.label }
                leaves.filter { it.kind == ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED }
                    .mapNotNull { it.argument }.distinct().forEach { id ->
                        SimulatorCheckboxRow(
                            label = itemLabels[id] ?: "Item pickup ID \$${id.toString(16).uppercase().padStart(3, '0')}",
                            checked = id in context.collectedItemPickupIds,
                            onCheckedChange = {
                                context = context.copy(
                                    collectedItemPickupIds = toggleSet(context.collectedItemPickupIds, id, it)
                                )
                            },
                        )
                    }
                leaves.filter { it.kind == ProjectRoomStateConditionKind.DOOR_BIT_SET }
                    .mapNotNull { it.argument }.distinct().forEach { id ->
                        SimulatorCheckboxRow(
                            label = "Door bit \$${id.toString(16).uppercase().padStart(3, '0')} set",
                            checked = id in context.openedDoorIds,
                            onCheckedChange = {
                                context = context.copy(openedDoorIds = toggleSet(context.openedDoorIds, id, it))
                            },
                        )
                    }
                leaves.filter { it.kind == ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED }
                    .mapNotNull { it.argument }.distinct().forEach { id ->
                        SimulatorCheckboxRow(
                            label = "Chozo block \$${id.toString(16).uppercase().padStart(3, '0')} destroyed",
                            checked = id in context.destroyedChozoBlockIds,
                            onCheckedChange = {
                                context = context.copy(
                                    destroyedChozoBlockIds = toggleSet(context.destroyedChozoBlockIds, id, it)
                                )
                            },
                        )
                    }

                val bossRequirements = leaves.mapNotNull { leaf ->
                    when (leaf.kind) {
                        ProjectRoomStateConditionKind.AREA_MAIN_BOSS_DEAD -> area to 0x01
                        ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET -> area to (leaf.argument ?: 0)
                        ProjectRoomStateConditionKind.BOSS_DEFEATED -> {
                            val packed = leaf.argument ?: 0
                            ((packed ushr 8) and 0xFF) to (packed and 0xFF)
                        }
                        else -> null
                    }
                }.distinct()
                bossRequirements.forEach { (bossArea, mask) ->
                    val currentBits = context.bossBitsByArea[bossArea] ?: 0
                    SimulatorCheckboxRow(
                        label = "${RoomStateCondition.BOSS_NAMES[bossArea to mask] ?: "Area $bossArea boss"} defeated",
                        checked = currentBits and mask != 0,
                        onCheckedChange = { checked ->
                            val updated = if (checked) currentBits or mask else currentBits and mask.inv()
                            context = context.copy(bossBitsByArea = context.bossBitsByArea + (bossArea to updated))
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SimulatorCheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.size(24.dp))
        Text(label, fontSize = ROOM_INFO_BODY_FONT_SIZE)
    }
}

@Composable
private fun SimulatorMaskRow(label: String, mask: Int, value: Int, onValueChange: (Int) -> Unit) {
    SimulatorCheckboxRow(
        label = label,
        checked = value and mask != 0,
        onCheckedChange = { checked -> onValueChange(if (checked) value or mask else value and mask.inv()) },
    )
}

@Composable
private fun SimulatorValueRow(label: String, value: Int, onValueChange: (Int) -> Unit) {
    EditableIntRow(label = label, value = value, min = 0, max = 0xFFFF, onValueChange = onValueChange)
}

internal fun projectConditionLabel(condition: ProjectRoomStateCondition, area: Int): String =
    condition.displaySummary(area)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StateConditionEditor(
    condition: ProjectRoomStateCondition,
    area: Int,
    incomingDoorPointers: List<Int>,
    itemPickupOptions: List<ItemPickupConditionOption>,
    onChange: (ProjectRoomStateCondition) -> Unit,
    onOpenBuilder: (() -> Unit)? = null,
) {
    if (condition.kind == ProjectRoomStateConditionKind.ALL_OF ||
        condition.kind == ProjectRoomStateConditionKind.ANY_OF
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
                Text(projectConditionLabel(condition, area), fontSize = ROOM_INFO_BODY_FONT_SIZE)
                if (onOpenBuilder != null) {
                    TextButton(
                        onClick = onOpenBuilder,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp),
                    ) { Text("Edit compound logic…", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
                }
            }
        }
        return
    }
    val availableGroups = STATE_CONDITION_KIND_PICKER_GROUPS.mapNotNull { group ->
        val kinds = group.kinds.filter { kind ->
            kind != ProjectRoomStateConditionKind.INCOMING_DOOR ||
                incomingDoorPointers.isNotEmpty() || condition.kind == kind
        }
        group.copy(kinds = kinds).takeIf { kinds.isNotEmpty() }
    }
    val availableKinds = availableGroups.flatMap { it.kinds }
    var kindExpanded by remember(condition.kind) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Condition",
            fontSize = ROOM_INFO_BODY_FONT_SIZE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        ExposedDropdownMenuBox(
            expanded = kindExpanded,
            onExpandedChange = { if (availableKinds.size > 1) kindExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().menuAnchor().clickable {
                    if (availableKinds.size > 1) kindExpanded = true
                },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    stateConditionKindPickerLabel(condition.kind),
                    fontSize = ROOM_INFO_BODY_FONT_SIZE,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            ExposedDropdownMenu(
                expanded = kindExpanded,
                onDismissRequest = { kindExpanded = false },
                modifier = Modifier.requiredSizeIn(maxHeight = 560.dp),
            ) {
                availableGroups.forEachIndexed { groupIndex, group ->
                    if (groupIndex > 0) Divider()
                    Text(
                        group.title,
                        fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 2.dp),
                    )
                    for (kind in group.kinds) {
                        val argument = when (kind) {
                            condition.kind -> condition.argument
                            ProjectRoomStateConditionKind.EVENT_SET -> 0
                            ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET ->
                                RoomStateCondition.BOSS_NAMES.keys.firstOrNull { it.first == area }?.second ?: 1
                            ProjectRoomStateConditionKind.INCOMING_DOOR -> incomingDoorPointers.firstOrNull() ?: 0
                            ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED ->
                                RoomStateCondition.EQUIPMENT_NAMES.keys.first()
                            ProjectRoomStateConditionKind.BEAM_COLLECTED ->
                                RoomStateCondition.BEAM_NAMES.keys.first()
                            ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED ->
                                RoomStateCondition.EQUIPMENT_NAMES.keys.first()
                            ProjectRoomStateConditionKind.BEAM_EQUIPPED ->
                                RoomStateCondition.BEAM_NAMES.keys.first()
                            ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED ->
                                itemPickupOptions.firstOrNull()?.id ?: 0
                            ProjectRoomStateConditionKind.BOSS_DEFEATED -> {
                                val first = RoomStateCondition.BOSS_NAMES.keys.first()
                                packedBossConditionArgument(first.first, first.second)
                            }
                            else -> null
                        }
                        val option = projectRoomStateCondition(kind, argument)
                        DropdownMenuItem(
                            text = { Text(stateConditionKindPickerLabel(kind), fontSize = ROOM_INFO_BODY_FONT_SIZE) },
                            onClick = { kindExpanded = false; onChange(option) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                        )
                    }
                }
            }
        }
    }

    val arguments: List<Pair<Int, String>> = when (condition.kind) {
        ProjectRoomStateConditionKind.EVENT_SET -> RoomStateCondition.EVENT_NAMES.entries
            .sortedBy { it.key }.map { it.key to it.value }
        ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET -> RoomStateCondition.BOSS_FLAG_MASKS.map { mask ->
            mask to (RoomStateCondition.BOSS_NAMES[area to mask]
                ?: "Custom boss flag \$${mask.toString(16).uppercase().padStart(2, '0')}")
        }
        ProjectRoomStateConditionKind.INCOMING_DOOR -> incomingDoorPointers.map {
            it to "Door \$${it.toString(16).uppercase().padStart(4, '0')}"
        }
        ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED ->
            RoomStateCondition.EQUIPMENT_NAMES.map { it.key to it.value }
        ProjectRoomStateConditionKind.BEAM_COLLECTED ->
            RoomStateCondition.BEAM_NAMES.map { it.key to it.value }
        ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED ->
            RoomStateCondition.EQUIPMENT_NAMES.map { it.key to it.value }
        ProjectRoomStateConditionKind.BEAM_EQUIPPED ->
            RoomStateCondition.BEAM_NAMES.map { it.key to it.value }
        ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED -> itemPickupOptions.map { it.id to it.label }
        ProjectRoomStateConditionKind.BOSS_DEFEATED -> ROOM_AREA_NAMES.indices.flatMap { bossArea ->
            RoomStateCondition.BOSS_FLAG_MASKS.map { mask ->
                val name = RoomStateCondition.BOSS_NAMES[bossArea to mask]
                    ?: "Custom boss flag \$${mask.toString(16).uppercase().padStart(2, '0')}"
                packedBossConditionArgument(bossArea, mask) to "${ROOM_AREA_NAMES[bossArea]} — $name"
            }
        }
        else -> emptyList()
    }
    if (arguments.isNotEmpty()) {
        var argumentExpanded by remember(condition.kind, condition.argument) { mutableStateOf(false) }
        val selectedLabel = arguments.firstOrNull { it.first == condition.argument }?.second
            ?: "Custom \$${(condition.argument ?: 0).toString(16).uppercase()}"
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (condition.kind) {
                    ProjectRoomStateConditionKind.EVENT_SET -> "Event"
                    ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET -> "Boss"
                    ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED -> "Equipment"
                    ProjectRoomStateConditionKind.BEAM_COLLECTED -> "Beam"
                    ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED -> "Equipment"
                    ProjectRoomStateConditionKind.BEAM_EQUIPPED -> "Beam"
                    ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED -> "Pickup"
                    ProjectRoomStateConditionKind.BOSS_DEFEATED -> "Boss"
                    else -> "Door"
                },
                fontSize = ROOM_INFO_BODY_FONT_SIZE,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(100.dp),
            )
            ExposedDropdownMenuBox(
                expanded = argumentExpanded,
                onExpandedChange = { argumentExpanded = it },
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().menuAnchor().clickable { argumentExpanded = true },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        selectedLabel,
                        fontSize = ROOM_INFO_BODY_FONT_SIZE,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                ExposedDropdownMenu(
                    expanded = argumentExpanded,
                    onDismissRequest = { argumentExpanded = false },
                    modifier = Modifier.requiredSizeIn(maxHeight = 520.dp),
                ) {
                    for ((index, argument) in arguments.withIndex()) {
                        val (value, label) = argument
                        val menuLabel = if (condition.kind == ProjectRoomStateConditionKind.BOSS_DEFEATED) {
                            label.substringAfter(" — ")
                        } else {
                            label
                        }
                        if (condition.kind == ProjectRoomStateConditionKind.BOSS_DEFEATED &&
                            index % RoomStateCondition.BOSS_FLAG_MASKS.size == 0
                        ) {
                            if (index > 0) Divider()
                            Text(
                                ROOM_AREA_NAMES[index / RoomStateCondition.BOSS_FLAG_MASKS.size],
                                fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 2.dp),
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(menuLabel, fontSize = ROOM_INFO_BODY_FONT_SIZE) },
                            onClick = {
                                argumentExpanded = false
                                onChange(projectRoomStateCondition(condition.kind, value, condition.negated))
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                        )
                    }
                }
            }
        }
    }

    if (condition.argumentKind == ProjectRoomStateConditionArgumentKind.CAPACITY) {
        EditableIntRow(
            label = "Minimum",
            value = condition.argument ?: 0,
            min = 0,
            max = 0xFFFF,
            onValueChange = { value ->
                onChange(projectRoomStateCondition(condition.kind, value, condition.negated))
            },
        )
    }

    if (condition.argumentKind == ProjectRoomStateConditionArgumentKind.EVENT_ID) {
        EditableIntRow(
            label = "Event ID",
            value = condition.argument ?: 0,
            min = 0,
            max = 0xFF,
            onValueChange = { value ->
                onChange(projectRoomStateCondition(condition.kind, value, condition.negated))
            },
        )
    }

    if (condition.argumentKind == ProjectRoomStateConditionArgumentKind.DOOR_BIT_INDEX ||
        condition.argumentKind == ProjectRoomStateConditionArgumentKind.CHOZO_BLOCK_BIT_INDEX
    ) {
        EditableIntRow(
            label = if (condition.argumentKind == ProjectRoomStateConditionArgumentKind.DOOR_BIT_INDEX) {
                "Door ID"
            } else {
                "Block ID"
            },
            value = condition.argument ?: 0,
            min = 0,
            max = 0x1FF,
            onValueChange = { value ->
                onChange(projectRoomStateCondition(condition.kind, value, condition.negated))
            },
        )
    }

    if (condition.kind == ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED) {
        Text(
            "Checks that exact pickup's save bit; it does not compare ammo capacity.",
            fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 100.dp, top = 1.dp),
        )
    }

    if (condition.kind == ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET ||
        condition.kind == ProjectRoomStateConditionKind.BOSS_DEFEATED
    ) {
        Text(
            "Named bosses are verified vanilla flags; custom flags are available for ASM hacks.",
            fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 100.dp, top = 1.dp),
        )
    }

    if (condition.kind != ProjectRoomStateConditionKind.DEFAULT) {
        val invertLabel = when (condition.kind) {
            ProjectRoomStateConditionKind.EVENT_SET -> "Event is not set"
            ProjectRoomStateConditionKind.INCOMING_DOOR -> "Entered through any other door"
            ProjectRoomStateConditionKind.AREA_MAIN_BOSS_DEAD,
            ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET,
            ProjectRoomStateConditionKind.BOSS_DEFEATED -> "Boss is not defeated"
            ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED,
            ProjectRoomStateConditionKind.BEAM_EQUIPPED -> "Not equipped instead"
            ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED,
            ProjectRoomStateConditionKind.BEAM_COLLECTED,
            ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED,
            ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED,
            ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED -> "Not collected instead"
            ProjectRoomStateConditionKind.DOOR_BIT_SET -> "Door bit is not set"
            ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED -> "Block is still intact"
            ProjectRoomStateConditionKind.ESCAPE_ACTIVE -> "Escape is not active"
            else -> if (condition.argumentKind == ProjectRoomStateConditionArgumentKind.CAPACITY) {
                "Below this value instead"
            } else {
                "Invert this condition"
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(100.dp))
            Checkbox(
                checked = condition.negated,
                onCheckedChange = { inverted ->
                    onChange(projectRoomStateCondition(condition.kind, condition.argument, inverted))
                },
                modifier = Modifier.size(24.dp),
            )
            Text(invertLabel, fontSize = ROOM_INFO_BODY_FONT_SIZE)
        }
    }
    if (onOpenBuilder != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = onOpenBuilder,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(24.dp),
            ) { Text("Build AND / OR logic…", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
        }
    }
}

@Composable
internal fun ConditionBuilderDialog(
    initialCondition: ProjectRoomStateCondition,
    area: Int,
    incomingDoorPointers: List<Int>,
    itemPickupOptions: List<ItemPickupConditionOption>,
    onDismiss: () -> Unit,
    onApply: (ProjectRoomStateCondition) -> Unit,
) {
    var condition by remember(initialCondition) { mutableStateOf(initialCondition) }
    val valid = com.supermetroid.editor.rom.SmEditCompiledRoomStateConditionFormat
        .conditionTreeIsValid(condition)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Condition logic") },
        text = {
            Column(
                modifier = Modifier.requiredSizeIn(maxHeight = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Build the load-time test as nested AND / OR groups. This entire expression " +
                        "is evaluated before the room graph moves to the next ELSE IF.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (condition.kind != ProjectRoomStateConditionKind.ALL_OF &&
                    condition.kind != ProjectRoomStateConditionKind.ANY_OF
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            condition = projectRoomStateCondition(
                                ProjectRoomStateConditionKind.ALL_OF,
                                children = listOf(
                                    condition,
                                    projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0),
                                ),
                            )
                        }) { Text("Add AND") }
                        TextButton(onClick = {
                            condition = projectRoomStateCondition(
                                ProjectRoomStateConditionKind.ANY_OF,
                                children = listOf(
                                    condition,
                                    projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0),
                                ),
                            )
                        }) { Text("Add OR") }
                    }
                }
                ConditionExpressionNodeEditor(
                    condition = condition,
                    area = area,
                    incomingDoorPointers = incomingDoorPointers,
                    itemPickupOptions = itemPickupOptions,
                    depth = 0,
                    onChange = { condition = it },
                )
                Text(
                    "Preview: ${projectConditionLabel(condition, area)}",
                    fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onApply(condition) }) { Text("Apply logic") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConditionExpressionNodeEditor(
    condition: ProjectRoomStateCondition,
    area: Int,
    incomingDoorPointers: List<Int>,
    itemPickupOptions: List<ItemPickupConditionOption>,
    depth: Int,
    onChange: (ProjectRoomStateCondition) -> Unit,
) {
    val isGroup = condition.kind == ProjectRoomStateConditionKind.ALL_OF ||
        condition.kind == ProjectRoomStateConditionKind.ANY_OF
    if (!isGroup) {
        StateConditionEditor(
            condition = condition,
            area = area,
            incomingDoorPointers = incomingDoorPointers,
            itemPickupOptions = itemPickupOptions,
            onChange = onChange,
        )
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (condition.kind == ProjectRoomStateConditionKind.ALL_OF) "ALL of (AND)" else "ANY of (OR)",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val newKind = if (condition.kind == ProjectRoomStateConditionKind.ALL_OF) {
                        ProjectRoomStateConditionKind.ANY_OF
                    } else {
                        ProjectRoomStateConditionKind.ALL_OF
                    }
                    onChange(
                        projectRoomStateCondition(
                            newKind,
                            negated = condition.negated,
                            children = condition.children,
                        )
                    )
                }) { Text(if (condition.kind == ProjectRoomStateConditionKind.ALL_OF) "Use OR" else "Use AND") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = condition.negated,
                    onCheckedChange = { onChange(condition.copy(negated = it, routineCode = 0)) },
                    modifier = Modifier.size(24.dp),
                )
                Text("Invert this whole group", fontSize = ROOM_INFO_BODY_FONT_SIZE)
            }
            condition.children.forEachIndexed { index, child ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(start = (depth * 4).dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Column(Modifier.padding(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                enabled = condition.children.size > 2,
                                onClick = {
                                    onChange(
                                        condition.copy(
                                            children = condition.children.filterIndexed { childIndex, _ ->
                                                childIndex != index
                                            },
                                            routineCode = 0,
                                        )
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.height(22.dp),
                            ) { Text("Remove", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
                        }
                        ConditionExpressionNodeEditor(
                            condition = child,
                            area = area,
                            incomingDoorPointers = incomingDoorPointers,
                            itemPickupOptions = itemPickupOptions,
                            depth = depth + 1,
                            onChange = { updated ->
                                onChange(
                                    condition.copy(
                                        children = condition.children.toMutableList().also { it[index] = updated },
                                        routineCode = 0,
                                    )
                                )
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    onChange(
                        condition.copy(
                            children = condition.children +
                                projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0),
                            routineCode = 0,
                        )
                    )
                }) { Text("+ Condition") }
                TextButton(
                    enabled = depth < 7,
                    onClick = {
                        val nested = projectRoomStateCondition(
                            ProjectRoomStateConditionKind.ALL_OF,
                            children = listOf(
                                projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0),
                                projectRoomStateCondition(ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED, 0x0004),
                            ),
                        )
                        onChange(condition.copy(children = condition.children + nested, routineCode = 0))
                    },
                ) { Text("+ Nested group") }
            }
        }
    }
}

@Composable
internal fun AddRoomStateDialog(
    title: String,
    templateName: String,
    initialCondition: ProjectRoomStateCondition,
    area: Int,
    incomingDoorPointers: List<Int>,
    itemPickupOptions: List<ItemPickupConditionOption>,
    existingConditions: List<ProjectRoomStateCondition>,
    onDismiss: () -> Unit,
    onAdd: (ProjectRoomStateCondition) -> Unit,
) {
    var condition by remember(initialCondition) { mutableStateOf(initialCondition) }
    var showBuilder by remember(initialCondition) { mutableStateOf(false) }
    val duplicatesExistingBranch = condition in existingConditions
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.requiredSizeIn(maxHeight = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Content starts as a copy of “$templateName”. Unedited ROM resources remain " +
                        "linked; existing state-specific changes are copied so the new state looks the same.",
                )
                Text(
                    "Branches are checked from top to bottom. The new branch is inserted after " +
                        "the selected state and before ELSE.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StateConditionEditor(
                    condition = condition,
                    area = area,
                    incomingDoorPointers = incomingDoorPointers,
                    itemPickupOptions = itemPickupOptions,
                    onChange = { condition = it },
                    onOpenBuilder = { showBuilder = true },
                )
                if (duplicatesExistingBranch) {
                    Text(
                        "Choose a different condition. An identical earlier branch would always " +
                            "win, so this one could never be selected.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !duplicatesExistingBranch,
                onClick = { onAdd(condition) },
            ) { Text("Add state") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (showBuilder) {
        ConditionBuilderDialog(
            initialCondition = condition,
            area = area,
            incomingDoorPointers = incomingDoorPointers,
            itemPickupOptions = itemPickupOptions,
            onDismiss = { showBuilder = false },
            onApply = {
                condition = it
                showBuilder = false
            },
        )
    }
}

@Composable
internal fun DeleteRoomStateDialog(
    stateName: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete room state?") },
        text = {
            Text(
                "Delete “$stateName” and its state-specific edits? Shared resources used by other " +
                    "states are kept. The mandatory ELSE state cannot be deleted.",
            )
        },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
