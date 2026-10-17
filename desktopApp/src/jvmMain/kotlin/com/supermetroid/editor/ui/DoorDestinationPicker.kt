package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser

/** Room selection is local UI state. Only selecting an actual doorway invokes the edit callback. */
@Composable
internal fun DoorDestinationPicker(
    currentDoor: RomParser.DoorEntry?,
    sourceRoomId: Int,
    requiredDirection: Int,
    rooms: List<RoomInfo>,
    romParser: RomParser,
    editorState: EditorState,
    enabled: Boolean,
    selectionKey: Any? = null,
    onDoorwaySelected: (DoorwayOpening) -> Unit,
) {
    // A draft must never follow a different source doorway/state or outlive source edits/undo.
    val revision = editorState.editVersion
    val canvasRoomId = editorState.currentRoomId
    val stateId = editorState.currentStateId
    key(sourceRoomId, selectionKey, currentDoor, requiredDirection, canvasRoomId, stateId, revision, enabled) {
        var roomExpanded by remember { mutableStateOf(false) }
        var doorExpanded by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        var stagedRoomId by remember { mutableStateOf<Int?>(null) }
        var choiceProblem by remember { mutableStateOf<String?>(null) }
        val roomOptions = remember(rooms, romParser) {
            rooms.mapNotNull { info ->
                romParser.readRoomHeader(info.getRoomIdAsInt())?.let(editorState::applyHeaderChanges)
                    ?.let { header -> Triple(info, header.areaName, header.area) }
            }.sortedWith(compareBy({ it.third }, { it.first.name.lowercase() }))
        }
        val destinationId = stagedRoomId ?: currentDoor?.destRoomPtr
        val destinationName = roomOptions.firstOrNull { it.first.getRoomIdAsInt() == destinationId }?.first?.name
            ?: if (destinationId == null) "Choose room…" else "Room ${'$'}${destinationId.toString(16).uppercase()}"
        val openings = remember(destinationId, romParser) {
            destinationId?.let { editorState.effectiveDoorwayOpenings(it, requiredDirection, romParser) }.orEmpty()
        }
        val destinationDoors = remember(destinationId, romParser) {
            destinationId?.let { editorState.effectiveDoorsForRoom(it, romParser) }.orEmpty()
        }
        val doorLabel = when {
            stagedRoomId == null && currentDoor != null -> doorDestinationLabel(currentDoor.direction, currentDoor.screenX, currentDoor.screenY)
            destinationId == null -> "Choose room first"
            openings.isEmpty() -> "No compatible doors"
            else -> "Choose destination door…"
        }
        val filteredRooms = if (search.isBlank()) roomOptions else roomOptions.filter { (info, areaName, _) ->
            val query = search.trim()
            info.name.contains(query, true) || info.id.contains(query, true) || areaName.contains(query, true)
        }

        DoorPickerField("Room:", destinationName, "door-room-picker", enabled, { roomExpanded = true }) {
            DropdownMenu(expanded = roomExpanded, onDismissRequest = { roomExpanded = false; search = "" },
                modifier = Modifier.width(420.dp).heightIn(max = 420.dp)) {
                AppTextInput(value = search, onValueChange = { search = it }, placeholder = "Search room or area…",
                    fontSize = LocalEditorTheme.current.fontSize.value.body,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth())
                for ((info, areaName, _) in filteredRooms) {
                    val roomId = info.getRoomIdAsInt()
                    DropdownMenuItem(text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(info.name, fontSize = LocalEditorTheme.current.fontSize.value.body,
                                fontWeight = if (roomId == destinationId) FontWeight.Bold else FontWeight.Normal)
                            Text(areaName, fontSize = LocalEditorTheme.current.fontSize.value.statusBar,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, onClick = {
                        // No door data, source, history, or project changes while browsing rooms.
                        stagedRoomId = roomId.takeUnless { it == currentDoor?.destRoomPtr }
                        choiceProblem = null
                        roomExpanded = false
                        search = ""
                    })
                }
                if (filteredRooms.isEmpty()) Text("No matching rooms", modifier = Modifier.padding(8.dp),
                    fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.outline)
            }
        }
        DoorPickerField("Destination:", doorLabel, "door-destination-picker", enabled && openings.isNotEmpty(), { doorExpanded = true }) {
            DropdownMenu(expanded = doorExpanded, onDismissRequest = { doorExpanded = false },
                modifier = Modifier.width(420.dp).heightIn(max = 420.dp)) {
                Text(destinationName, fontSize = LocalEditorTheme.current.fontSize.value.body, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                for (opening in openings) {
                    val linkedDoor = destinationDoors.getOrNull(opening.connectionIndex)
                    val returnTarget = linkedDoor?.let { linked ->
                        if (linked.destRoomPtr == sourceRoomId) "this room"
                        else roomOptions.firstOrNull { it.first.getRoomIdAsInt() == linked.destRoomPtr }?.first?.name ?: "another room"
                    }
                    DropdownMenuItem(text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(doorDestinationLabel(opening.direction, opening.screenX, opening.screenY),
                                fontSize = LocalEditorTheme.current.fontSize.value.body, fontWeight = FontWeight.Bold)
                            Text("Door tile ${oneBasedRoomCoordinate(opening.blockX, opening.blockY)}" +
                                if (returnTarget != null) " · returns to $returnTarget" else " · unlinked opening",
                                fontSize = LocalEditorTheme.current.fontSize.value.statusBar,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, onClick = {
                        doorExpanded = false
                        // Recheck a live doorway, not an old popup snapshot. Parent handlers retain
                        // source identity/shared-record checks and atomic source/layout publication.
                        if (enabled && editorState.doorPreviewProblem == null && editorState.editVersion == revision &&
                            editorState.currentRoomId == canvasRoomId && editorState.currentStateId == stateId &&
                            opening in editorState.effectiveDoorwayOpenings(opening.roomId, requiredDirection, romParser)) {
                            onDoorwaySelected(opening)
                        } else choiceProblem = "Room data changed. Choose the destination door again."
                    })
                }
            }
        }
        if (destinationId != null && openings.isEmpty()) Text(
            "No compatible ${incomingDoorEdgeName(requiredDirection)}-edge doorways in this room. The connection is unchanged.",
            fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (stagedRoomId != null) {
            Text("Room choice is pending. Choose a destination door to apply it.",
                fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { stagedRoomId = null; choiceProblem = null; doorExpanded = false },
                contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("Cancel room choice", fontSize = LocalEditorTheme.current.fontSize.value.detail)
            }
        }
        choiceProblem?.let { Text(it, fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.error) }
    }
}

internal fun doorDestinationLabel(direction: Int, screenX: Int, screenY: Int): String =
    "${incomingDoorEdgeName(direction).replaceFirstChar { it.uppercase() }} edge · screen ${oneBasedRoomCoordinate(screenX, screenY)}"

@Composable
private fun DoorPickerField(label: String, value: String, tag: String, enabled: Boolean, onClick: () -> Unit,
    popup: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = LocalEditorTheme.current.fontSize.value.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(tilePropertiesLabelWidth()))
        Box(Modifier.weight(1f)) {
            Surface(Modifier.fillMaxWidth().heightIn(min = 28.dp).testTag(tag).clickable(enabled = enabled, onClick = onClick),
                shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, fontSize = LocalEditorTheme.current.fontSize.value.detail, modifier = Modifier.weight(1f),
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (enabled) Text("▾", fontSize = LocalEditorTheme.current.fontSize.value.detail)
                }
            }
            popup()
        }
    }
}
