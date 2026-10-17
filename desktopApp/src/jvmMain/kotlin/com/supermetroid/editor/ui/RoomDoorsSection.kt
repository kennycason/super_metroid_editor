package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser

/** The same overview in ROM and ASM projects; structural actions follow the active workflow. */
@Composable
internal fun RoomDoorsSection(
    roomId: Int,
    romParser: RomParser,
    editorState: EditorState,
    roomInfos: List<RoomInfo>,
    onNavigateToDoors: ((Int) -> Unit)?,
    onWorkspaceChanged: (() -> Unit)?,
) {
    var expanded by remember(roomId) { mutableStateOf(false) }
    var addingDoor by remember(roomId) { mutableStateOf(false) }
    var newDoorDirection by remember(roomId) { mutableStateOf(0) }
    var doorManagerError by remember(roomId) { mutableStateOf<String?>(null) }
    val projectRoom = editorState.project.newRoomForPreviewId(roomId)
    val canEditStructure = editorState.canEditDoorStructure(roomId)

    Box(Modifier.fillMaxWidth()
        .clickable(role = Role.Button) { expanded = !expanded }
        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
        SectionHeader("Doors", trailingContent = {
            Text("${editorState.doorEntries.size}  ${if (expanded) "▾" else "▸"}",
                fontSize = ROOM_INFO_COMPACT_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
        })
    }
    if (!expanded) return

    editorState.doorPreviewProblem?.let {
        Text("Read-only door preview: $it", fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
            color = MaterialTheme.colorScheme.error)
    }
    if (projectRoom != null && (editorState.project.rooms[editorState.project.roomKey(roomId)]?.asmLayoutSourceVersion ?: 0) != 0) {
        Text("Adding/removing doors needs joint layout/source support for this new room. Existing connection fields remain editable.",
            fontSize = ROOM_INFO_CAPTION_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (canEditStructure && projectRoom == null) TextButton(onClick = {
        addingDoor = !addingDoor
        doorManagerError = null
    }, enabled = editorState.doorEntries.size < 16 && editorState.doorPreviewProblem == null) {
        Text(if (addingDoor) "Cancel add" else "Add connection", fontSize = ROOM_INFO_COMPACT_FONT_SIZE)
    }
    if (addingDoor && canEditStructure && projectRoom == null) {
        Row {
            listOf("Right", "Left", "Down", "Up").forEachIndexed { direction, title ->
                TextButton(onClick = { newDoorDirection = direction }) {
                    Text(if (direction == newDoorDirection) "[$title]" else title, fontSize = ROOM_INFO_COMPACT_FONT_SIZE)
                }
            }
        }
        DoorDestinationPicker(null, roomId, newDoorDirection, roomInfos, romParser, editorState,
            editorState.doorPreviewProblem == null) { opening ->
            doorManagerError = runCatching {
                editorState.addProjectRoomDoor(roomId, opening.roomId, opening.direction, opening.screenX,
                    opening.screenY, romParser, opening.doorCapCode)
                addingDoor = false
            }.exceptionOrNull()?.message
        }
    }
    if (editorState.doorEntries.isEmpty()) {
        Text("No door connections.", fontSize = ROOM_INFO_BODY_FONT_SIZE,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("Choose a room and destination door in the tile inspector. Stored connection reuse is under Advanced.",
            fontSize = ROOM_INFO_CAPTION_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editorState.doorEntries.forEachIndexed { index, door ->
        val identity = editorState.doorSourceIdentities[index]
        val destinationName = roomInfos.firstOrNull { it.getRoomIdAsInt() == door.destRoomPtr }?.name
            ?: "Room 0x${door.destRoomPtr.toString(16).uppercase()}"
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. $destinationName", fontSize = ROOM_INFO_BODY_FONT_SIZE)
                    Text("Travels ${door.directionName.lowercase()} · destination screen " +
                        oneBasedRoomCoordinate(door.screenX, door.screenY),
                        fontSize = ROOM_INFO_CAPTION_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onNavigateToDoors != null && editorState.asmNavigationEnabled) TextButton(onClick = { onNavigateToDoors(index) }) {
                    Text("ASM", fontSize = ROOM_INFO_COMPACT_FONT_SIZE)
                }
                if (canEditStructure) TextButton(onClick = {
                    doorManagerError = runCatching {
                        editorState.removeProjectRoomDoor(roomId, index, identity)
                        if (projectRoom != null) onWorkspaceChanged?.invoke()
                    }.exceptionOrNull()?.message
                }, enabled = editorState.doorPreviewProblem == null) {
                    Text("Remove", fontSize = ROOM_INFO_COMPACT_FONT_SIZE)
                }
            }
        }
    }
    doorManagerError?.let {
        Text(it, fontSize = ROOM_INFO_CAPTION_FONT_SIZE, color = MaterialTheme.colorScheme.error)
    }
}
