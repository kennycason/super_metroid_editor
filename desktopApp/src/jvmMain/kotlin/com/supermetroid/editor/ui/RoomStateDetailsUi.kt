package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.matches

// ── Music Dropdown ────────────────────────────────────────────────

private data class MusicOption(
    val songSet: Int,
    val playIndex: Int,
    val label: String
)

private val MUSIC_OPTIONS: List<MusicOption> by lazy {
    val options = mutableListOf(
        MusicOption(0x00, 0x00, "No change"),
        MusicOption(0x00, 0x03, "No music (silence)"),
    )
    for (track in SpcData.KNOWN_TRACKS) {
        val hexLabel = String.format("%02X:%02X", track.songSet, track.playIndex)
        val area = if (track.area.isNotEmpty()) " [${track.area}]" else ""
        options.add(MusicOption(track.songSet, track.playIndex, "${track.name}$area ($hexLabel)"))
    }
    options
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicDropdown(
    musicData: Int,
    musicTrack: Int,
    onMusicChange: (Int, Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = MUSIC_OPTIONS.firstOrNull { it.songSet == musicData && it.playIndex == musicTrack }
    val displayText = current?.label ?: String.format("Custom (%02X:%02X)", musicData, musicTrack)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Music", fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .clickable { expanded = true },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        displayText,
                        fontSize = ROOM_INFO_BODY_FONT_SIZE,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.requiredSizeIn(maxHeight = 400.dp)
            ) {
                for (option in MUSIC_OPTIONS) {
                    val isSelected = option.songSet == musicData && option.playIndex == musicTrack
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.label,
                                fontSize = ROOM_INFO_BODY_FONT_SIZE,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            onMusicChange(option.songSet, option.playIndex)
                            expanded = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }
    }
}

// ── Room State Sharing Labels ────────────────────────────────────

internal fun matchingStateIndices(
    allStateData: List<Map<String, Int>>,
    field: String,
    value: Int,
): List<Int> = allStateData.mapIndexedNotNull { index, data ->
    index.takeIf { data[field] == value }
}

internal fun describeStateResourceSharing(
    stateNames: List<String>,
    linkedStateIndices: List<Int>,
): String = when {
    linkedStateIndices.isEmpty() -> "Unavailable"
    linkedStateIndices.size == stateNames.size -> "Same in all ${stateNames.size} states"
    linkedStateIndices.size == 1 -> "Separate for this state"
    else -> "Same in ${linkedStateIndices.size} of ${stateNames.size} states"
}

internal fun describeStateResourceMembers(
    stateNames: List<String>,
    linkedStateIndices: List<Int>,
): String = when {
    linkedStateIndices.isEmpty() -> "Unavailable"
    linkedStateIndices.size == stateNames.size -> "All ${stateNames.size} states"
    else -> linkedStateIndices.joinToString { stateNames[it] }
}

internal fun changedRoomStateSections(
    selected: Map<String, Int>,
    baseline: Map<String, Int>,
): List<String> = buildList {
    if (selected["tileset"] != baseline["tileset"]) add("Tileset")
    if (selected["musicData"] != baseline["musicData"] ||
        selected["musicTrack"] != baseline["musicTrack"]
    ) add("Music")
    if (selected["fxPtr"] != baseline["fxPtr"]) add("Effects")
    if (selected["levelDataPtr"] != baseline["levelDataPtr"]) add("Layout")
    if (selected["bgDataPtr"] != baseline["bgDataPtr"]) add("Background")
    if (selected["plmSetPtr"] != baseline["plmSetPtr"]) add("Placed Objects")
    if (selected["enemySetPtr"] != baseline["enemySetPtr"]) add("Enemy Actors")
    if (selected["enemyGfxPtr"] != baseline["enemyGfxPtr"]) add("Enemy Graphics")
    if (selected["bgScrolling"] != baseline["bgScrolling"]) add("Layer 2 Motion")
    if (selected["roomScrollsPtr"] != baseline["roomScrollsPtr"]) add("Room Scrolls")
    if (selected["xraySpecialCasingPtr"] != baseline["xraySpecialCasingPtr"]) add("Special X-Ray")
    if (selected["mainAsmPtr"] != baseline["mainAsmPtr"] ||
        selected["setupAsmPtr"] != baseline["setupAsmPtr"]
    ) add("Room Logic")
}

internal data class RoomStateDifferenceDetail(
    val label: String,
    val detail: String,
)

internal fun stateDifferenceDetail(
    difference: String,
    selected: Map<String, Int>,
    baseline: Map<String, Int>,
    selectedFxName: String,
    baselineFxName: String,
    selectedEnemyCount: Int,
    baselineEnemyCount: Int,
    selectedGfxCount: Int,
    baselineGfxCount: Int,
    selectedPlmCount: Int,
    baselinePlmCount: Int,
): String = when (difference) {
    "Tileset" -> {
        val selectedTileset = selected["tileset"] ?: 0
        val baselineTileset = baseline["tileset"] ?: 0
        "${SpritePalettes.tilesetName(selectedTileset)}; Default: ${SpritePalettes.tilesetName(baselineTileset)}"
    }
    "Music" -> "${stateMusicName(selected)}; Default: ${stateMusicName(baseline)}"
    "Effects" -> "$selectedFxName; Default: $baselineFxName"
    "Layout" -> "Uses different tile and collision data"
    "Background" -> "Uses a different Layer 2 background"
    "Placed Objects" -> "$selectedPlmCount objects; Default: $baselinePlmCount"
    "Enemy Actors" -> "$selectedEnemyCount actors; Default: $baselineEnemyCount"
    "Enemy Graphics" -> "$selectedGfxCount slots; Default: $baselineGfxCount"
    "Layer 2 Motion" ->
        "${describeLayer2Scrolling(selected["bgScrolling"] ?: 0)}; " +
            "Default: ${describeLayer2Scrolling(baseline["bgScrolling"] ?: 0)}"
    "Room Scrolls" -> "Uses a different camera scroll map"
    "Special X-Ray" -> "Uses a different special X-Ray block table"
    "Room Logic" -> {
        val parts = buildList {
            if (selected["setupAsmPtr"] != baseline["setupAsmPtr"]) add("setup")
            if (selected["mainAsmPtr"] != baseline["mainAsmPtr"]) add("active")
        }
        "Different ${parts.joinToString(" and ")} room logic"
    }
    else -> "Different from Default"
}

private fun stateMusicName(data: Map<String, Int>): String {
    val musicData = data["musicData"] ?: 0
    val musicTrack = data["musicTrack"] ?: 0
    return MUSIC_OPTIONS.firstOrNull {
        it.songSet == musicData && it.playIndex == musicTrack
    }?.label ?: "Custom (${hex8(musicData)}:${hex8(musicTrack)})"
}

// ── Layer 2 Motion Dropdown ───────────────────────────────────────

/** Every X/Y pair used by the vanilla room-state table. Low byte is X; high byte is Y. */
private val VANILLA_LAYER_2_MOTION_VALUES = listOf(
    0xC1C1,
    0x0000,
    0x00C0,
    0x0101,
    0xC000,
    0x01C1,
    0xC101,
    0x0181,
    0xC0C0,
    0xFFC1,
    0x4101,
    0x00E0,
)

internal fun describeLayer2Scrolling(value: Int): String {
    val x = value and 0xFF
    val y = (value ushr 8) and 0xFF
    return "X: ${describeLayer2Axis(x)}; Y: ${describeLayer2Axis(y)}"
}

private fun describeLayer2Axis(value: Int): String = when (value) {
    0 -> "follows camera (1×)"
    1 -> "fixed"
    else -> {
        val numerator = value and 0xFE
        val divisor = 256
        val commonDivisor = greatestCommonDivisor(numerator, divisor)
        val rate = "${numerator / commonDivisor}/${divisor / commonDivisor}× camera speed"
        if ((value and 1) == 0) rate else "$rate, no tilemap streaming"
    }
}

private fun greatestCommonDivisor(first: Int, second: Int): Int {
    var a = first
    var b = second
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return a.coerceAtLeast(1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BgScrollDropdown(selectedValue: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = describeLayer2Scrolling(selectedValue)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Layer 2 Motion", fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(24.dp).clickable { expanded = true },
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(displayText, fontSize = ROOM_INFO_BODY_FONT_SIZE, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("▾", fontSize = ROOM_INFO_COMPACT_FONT_SIZE)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (code in VANILLA_LAYER_2_MOTION_VALUES) {
                    val isSelected = code == selectedValue
                    DropdownMenuItem(
                        text = {
                            Text("${hex16(code)} — ${describeLayer2Scrolling(code)}", fontSize = ROOM_INFO_BODY_FONT_SIZE,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = { expanded = false; onSelect(code) },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}

// ── Shared UI Components ──────────────────────────────────────────

@Composable
internal fun AreaDropdown(selectedArea: Int, onSelect: (Int) -> Unit) {
    var expanded by remember(selectedArea) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Area",
            fontSize = ROOM_INFO_BODY_FONT_SIZE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(24.dp).clickable { expanded = true },
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        when (selectedArea) {
                            7 -> "Debug/Unused (no pause map)"
                            else -> ROOM_AREA_NAMES.getOrElse(selectedArea) { "Invalid area $selectedArea" }
                        },
                        fontSize = ROOM_INFO_BODY_FONT_SIZE,
                    )
                    Text("▾", fontSize = ROOM_INFO_COMPACT_FONT_SIZE)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ROOM_AREA_NAMES.forEachIndexed { area, name ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "$area — $name",
                                fontSize = ROOM_INFO_BODY_FONT_SIZE,
                                fontWeight = if (area == selectedArea) FontWeight.Bold else FontWeight.Normal,
                                color = if (area == selectedArea) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        enabled = area != selectedArea,
                        onClick = {
                            expanded = false
                            onSelect(area)
                        },
                        modifier = Modifier.height(26.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    onHelp: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = ROOM_INFO_SECTION_FONT_SIZE,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailingContent?.invoke()
        if (onHelp != null) {
            Surface(
                modifier = Modifier.size(20.dp).clickable(onClick = onHelp),
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("?", fontSize = ROOM_INFO_BODY_FONT_SIZE, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    Divider()
}

@Composable
internal fun SelectedStateSummary(
    stateName: String,
    isDefault: Boolean,
    differenceCount: Int,
    onShowDifferences: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Selected state", fontSize = ROOM_INFO_CAPTION_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stateName, fontSize = ROOM_INFO_BODY_FONT_SIZE, fontWeight = FontWeight.SemiBold)
            }
            Surface(
                modifier = if (!isDefault && differenceCount > 0) {
                    Modifier.clickable(onClick = onShowDifferences)
                } else {
                    Modifier
                },
                shape = MaterialTheme.shapes.extraSmall,
                color = if (isDefault) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                },
            ) {
                Text(
                    when {
                        isDefault -> "DEFAULT"
                        differenceCount == 0 -> "Matches Default"
                        differenceCount == 1 -> "1 change"
                        else -> "$differenceCount changes"
                    },
                    fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDefault) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
internal fun RoomStatesHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How room states work") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A room state is a version of the room used under a particular game condition. " +
                        "It can select different tiles, objects, enemies, music, effects, and room logic.",
                )
                Text(
                    "The game checks IF and ELSE IF conditions from top to bottom. The first match " +
                        "wins; ELSE is the default when nothing above it matches.",
                )
                Text("Select a state to inspect the complete room version used by that branch.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
internal fun StateDataHelpDialog(
    selectedStateName: String,
    resourceStates: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("State data and sharing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Selected state: $selectedStateName", fontWeight = FontWeight.SemiBold)
                Text(
                    "Each state is assembled from several resources. Multiple states can point to " +
                        "the exact same resource instead of storing duplicate copies.",
                )
                for ((resource, members) in resourceStates) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            resource,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(120.dp),
                        )
                        Text(members, modifier = Modifier.weight(1f))
                    }
                }
                Text(
                    "Placed Objects are PLMs: doors, items, gates, stations, scroll triggers, " +
                        "and similar interactive objects.",
                )
                Text(
                    "Enemy actors include enemies, bosses, hazards, and some animated room effects. " +
                        "The Landing Site steam is implemented this way.",
                )
                Text(
                    "A name such as “Power Bombs collected” is another state condition. It does not " +
                        "mean that Power Bombs are the objects being shared.",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
internal fun RoomStateComparisonDialog(
    selectedStateName: String,
    baselineStateName: String,
    differences: List<RoomStateDifferenceDetail>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Changes from $baselineStateName") },
        text = {
            Column(
                modifier = Modifier.requiredSizeIn(maxHeight = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(selectedStateName, fontWeight = FontWeight.SemiBold)
                if (differences.isEmpty()) {
                    Text("This state uses the same state data as $baselineStateName.")
                } else {
                    for (difference in differences) {
                        Column {
                            Text(difference.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                difference.detail,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
internal fun RoomScrollsHelpDialog(editingAvailable: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Room scrolls") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Room scroll data controls how the camera may move through each screen: red " +
                        "blocks movement, blue allows normal movement, and green is used by special scrolling behavior.",
                )
                if (!editingAvailable) {
                    Text(
                        "This multi-state room currently shows the selected state's scroll source " +
                            "without editing controls.",
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
