package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.RomParser

private val areaInfo = mapOf(
    0 to ("Crateria" to Color(0xFF5078A0)),
    1 to ("Brinstar" to Color(0xFF50A058)),
    2 to ("Norfair" to Color(0xFFA05050)),
    3 to ("Wrecked Ship" to Color(0xFF7070A0)),
    4 to ("Maridia" to Color(0xFF5088B0)),
    5 to ("Tourian" to Color(0xFFA09050)),
    6 to ("Ceres" to Color(0xFF808080)),
)

enum class RoomSortMode(val label: String) {
    AREA("Area"),
    LAST_EDITED("Last Edited"),
    FIRST_EDITED("First Edited"),
    NAME_AZ("Name A-Z"),
    NAME_ZA("Name Z-A"),
}

@Composable
fun RoomListView(
    rooms: List<RoomInfo>,
    selectedRoom: RoomInfo?,
    romParser: RomParser?,
    editorState: EditorState?,
    onRoomSelected: (RoomInfo) -> Unit,
    modifier: Modifier = Modifier,
    onKeyboardNavigatorChanged: (((Int) -> Boolean)?) -> Unit = {},
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(RoomSortMode.AREA) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val roomAreas = remember(romParser, rooms) {
        if (romParser == null) return@remember emptyMap<String, Int>()
        rooms.associate { room ->
            val header = try { romParser.readRoomHeader(room.getRoomIdAsInt()) } catch (_: Exception) { null }
            room.handle to (header?.area ?: -1)
        }
    }

    val editOrder = editorState?.roomEditOrder ?: emptyMap()
    val editVersion = editorState?.editVersion ?: 0
    val editedRoomIds = remember(editOrder, editVersion) { editOrder.keys }

    val filteredSortedRooms = remember(rooms, roomAreas, searchQuery, sortMode, editOrder, editVersion) {
        val filtered = if (searchQuery.isBlank()) rooms
        else {
            val q = searchQuery.trim().lowercase()
            rooms.filter { room ->
                room.name.lowercase().contains(q) ||
                    room.id.lowercase().contains(q) ||
                    room.handle.lowercase().contains(q) ||
                    (areaInfo[roomAreas[room.handle]]?.first?.lowercase()?.contains(q) == true)
            }
        }

        when (sortMode) {
            RoomSortMode.AREA -> filtered.sortedWith(compareBy(
                { if (it.handle == "debugRoom") Int.MAX_VALUE else (roomAreas[it.handle] ?: 99) },
                { it.getRoomIdAsInt() }
            ))
            RoomSortMode.LAST_EDITED, RoomSortMode.FIRST_EDITED -> {
                val edited = filtered.filter { it.getRoomIdAsInt() in editedRoomIds }
                val unedited = filtered.filter { it.getRoomIdAsInt() !in editedRoomIds }
                val editedSorted = if (sortMode == RoomSortMode.LAST_EDITED)
                    edited.sortedByDescending { editOrder[it.getRoomIdAsInt()] ?: 0L }
                else
                    edited.sortedBy { editOrder[it.getRoomIdAsInt()] ?: 0L }
                editedSorted + unedited
            }
            RoomSortMode.NAME_AZ -> filtered.sortedBy { it.name.lowercase() }
            RoomSortMode.NAME_ZA -> filtered.sortedByDescending { it.name.lowercase() }
        }
    }

    val showAreaHeaders = sortMode == RoomSortMode.AREA && searchQuery.isBlank()
    val groupedRooms = remember(filteredSortedRooms, roomAreas, showAreaHeaders) {
        if (!showAreaHeaders) {
            emptyMap()
        } else {
            // Use LinkedHashMap to preserve the sorted order from filteredSortedRooms
            val map = LinkedHashMap<Int, MutableList<RoomInfo>>()
            for (room in filteredSortedRooms) {
                val area = roomAreas[room.handle] ?: -1
                map.getOrPut(area) { mutableListOf() }.add(room)
            }
            map
        }
    }
    val selectedRoomId = selectedRoom?.getRoomIdAsInt()
    val selectedIndex = remember(filteredSortedRooms, selectedRoomId) {
        filteredSortedRooms.indexOfFirst { it.getRoomIdAsInt() == selectedRoomId }
    }
    val roomListFocusRequester = rememberVerticalSelectionFocusRequester(
        enabled = filteredSortedRooms.isNotEmpty(),
        requestFocusOnReady = searchQuery.isBlank(),
        requestFocusKey = rooms,
    )
    val listModifier = Modifier
        .fillMaxSize()
        .verticalSelectionKeyNavigation(
            focusRequester = roomListFocusRequester,
            itemCount = filteredSortedRooms.size,
            selectedIndex = selectedIndex,
            enabled = filteredSortedRooms.isNotEmpty(),
            onSelectIndex = { nextIndex -> onRoomSelected(filteredSortedRooms[nextIndex]) },
        )
    DisposableEffect(filteredSortedRooms, selectedIndex) {
        if (filteredSortedRooms.isEmpty()) {
            onKeyboardNavigatorChanged(null)
            onDispose { onKeyboardNavigatorChanged(null) }
        } else {
            val navigator: (Int) -> Boolean = { delta ->
                val step = if (delta < 0) -1 else 1
                val nextIndex = if (selectedIndex in filteredSortedRooms.indices) {
                    (selectedIndex + step + filteredSortedRooms.size) % filteredSortedRooms.size
                } else if (step < 0) {
                    filteredSortedRooms.lastIndex
                } else {
                    0
                }
                onRoomSelected(filteredSortedRooms[nextIndex])
                true
            }
            onKeyboardNavigatorChanged(navigator)
            onDispose { onKeyboardNavigatorChanged(null) }
        }
    }
    LaunchedEffect(selectedRoomId, filteredSortedRooms, groupedRooms, showAreaHeaders) {
        val targetIndex = if (!showAreaHeaders) {
            selectedIndex
        } else {
            var lazyIndex = 0
            var foundIndex = -1
            for ((area, areaRooms) in groupedRooms) {
                if (area >= 0) lazyIndex += 1
                for (room in areaRooms) {
                    if (room.getRoomIdAsInt() == selectedRoomId) {
                        foundIndex = lazyIndex
                        break
                    }
                    lazyIndex += 1
                }
                if (foundIndex >= 0) break
            }
            foundIndex
        }
        if (targetIndex >= 0) {
            runCatching { listState.animateScrollToItem(targetIndex) }
        }
    }
    val totalRoomCount = rooms.size

    val fs = LocalEditorTheme.current.fontSize.value
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header: title + sort ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val countText = if (searchQuery.isNotBlank() && filteredSortedRooms.size != totalRoomCount)
                    "${filteredSortedRooms.size}/$totalRoomCount"
                else "$totalRoomCount"
                Text(
                    text = "Rooms ($countText)",
                    fontSize = fs.heading,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                // Sort dropdown with toggle: clicking the active sort's group swaps direction
                val menuEntries = listOf(
                    RoomSortMode.AREA,
                    when (sortMode) {
                        RoomSortMode.LAST_EDITED -> RoomSortMode.FIRST_EDITED
                        RoomSortMode.FIRST_EDITED -> RoomSortMode.LAST_EDITED
                        else -> RoomSortMode.LAST_EDITED
                    },
                    when (sortMode) {
                        RoomSortMode.NAME_AZ -> RoomSortMode.NAME_ZA
                        RoomSortMode.NAME_ZA -> RoomSortMode.NAME_AZ
                        else -> RoomSortMode.NAME_AZ
                    },
                )
                Box {
                    TextButton(
                        onClick = { sortMenuExpanded = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(sortMode.label, fontSize = fs.tabLabel)
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        for (mode in menuEntries) {
                            val isActive = mode == sortMode || when (mode) {
                                RoomSortMode.FIRST_EDITED -> sortMode == RoomSortMode.LAST_EDITED
                                RoomSortMode.LAST_EDITED -> sortMode == RoomSortMode.FIRST_EDITED
                                RoomSortMode.NAME_ZA -> sortMode == RoomSortMode.NAME_AZ
                                RoomSortMode.NAME_AZ -> sortMode == RoomSortMode.NAME_ZA
                                else -> false
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isActive) {
                                            Text("✓ ", fontSize = fs.body, fontWeight = FontWeight.Bold)
                                        }
                                        Text(mode.label, fontSize = fs.body)
                                    }
                                },
                                onClick = {
                                    sortMode = mode
                                    sortMenuExpanded = false
                                },
                                modifier = Modifier.height(32.dp)
                            )
                        }
                    }
                }
            }

            // ── Search field (BasicTextField so typed text is always visible) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = fs.body,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Divider()

            // ── Room list ──
            if (filteredSortedRooms.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (rooms.isEmpty()) "Open a ROM to see rooms"
                               else "No rooms match \"$searchQuery\"",
                        fontSize = fs.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (showAreaHeaders) {
                LazyColumn(modifier = listModifier, state = listState) {
                    groupedRooms.forEach { (area, areaRooms) ->
                        if (area >= 0) {
                            item(key = "header_$area") {
                                AreaHeader(area)
                            }
                        }
                        items(areaRooms, key = { it.handle }) { room ->
                            RoomListItem(
                                room = room,
                                area = roomAreas[room.handle] ?: -1,
                                isSelected = selectedRoomId == room.getRoomIdAsInt(),
                                isEdited = room.getRoomIdAsInt() in editedRoomIds,
                                onClick = { onRoomSelected(room) }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(modifier = listModifier, state = listState) {
                    items(filteredSortedRooms, key = { it.handle }) { room ->
                        RoomListItem(
                            room = room,
                            area = roomAreas[room.handle] ?: -1,
                            isSelected = selectedRoomId == room.getRoomIdAsInt(),
                            isEdited = room.getRoomIdAsInt() in editedRoomIds,
                            onClick = { onRoomSelected(room) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AreaHeader(area: Int) {
    val fs = LocalEditorTheme.current.fontSize.value
    val (areaName, areaColor) = areaInfo[area] ?: ("Unknown" to Color.Gray)
    Surface(
        color = areaColor.copy(alpha = 0.15f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = areaName,
            fontSize = fs.detail,
            fontWeight = FontWeight.Bold,
            color = areaColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun RoomListItem(
    room: RoomInfo,
    area: Int,
    isSelected: Boolean,
    isEdited: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val (_, areaColor) = areaInfo[area] ?: ("" to Color.Gray)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = areaColor,
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
            ) {}

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = room.name,
                    fontSize = fs.body,
                )
                Text(
                    text = "0x${room.getRoomIdAsInt().toString(16).lowercase().padStart(4, '0')}",
                    fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isEdited) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                )
            }
        }
    }
}
