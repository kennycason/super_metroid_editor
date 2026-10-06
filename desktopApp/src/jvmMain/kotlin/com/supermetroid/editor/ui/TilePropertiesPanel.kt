package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.data.ItemStateScope
import com.supermetroid.editor.rom.RomParser

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TilePropertiesPanel(
    blockX: Int,
    blockY: Int,
    metatile: Int,
    initialBlockType: Int?,
    initialBts: Int,
    initialBtsMixed: Boolean = false,
    selectionEndX: Int = blockX,
    selectionEndY: Int = blockY,
    selectionTileCount: Int = 1,
    editorState: EditorState,
    romParser: RomParser,
    rooms: List<RoomInfo>,
    roomHeader: com.supermetroid.editor.data.Room?,
    roomId: Int,
    emulatorConnected: Boolean,
    onMoveSamusHere: ((Int, Int) -> Unit)?,
    onWorkspaceChanged: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var propsBlockType by remember(blockX, blockY, selectionEndX, selectionEndY, initialBlockType) {
        mutableStateOf(initialBlockType)
    }
    var propsBts by remember(blockX, blockY, selectionEndX, selectionEndY, initialBts) {
        mutableStateOf(initialBts)
    }
    var propsBtsMixed by remember(blockX, blockY, selectionEndX, selectionEndY, initialBtsMixed) {
        mutableStateOf(initialBtsMixed)
    }
    var doorConnectionError by remember(blockX, blockY, roomId) { mutableStateOf<String?>(null) }
    val editableBlockTypes = listOf(
        0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Treadmill",
        0x4 to "Shootable Air", 0x5 to "H-Extend", 0x6 to "Unused",
        0x7 to "Air (Bomb)", 0x8 to "Solid", 0x9 to "Door", 0xA to "Spike",
        0xB to "Crumble", 0xC to "Shot Block", 0xD to "V-Extend",
        0xE to "Grapple", 0xF to "Bomb Block"
    )
    val selectedBlockType = propsBlockType
    val propsTypeName = selectedBlockType?.let(::blockTypeName)
    val btsOptions = selectedBlockType?.let(::btsOptionsForBlockType).orEmpty()

    fun applyProperties(blockType: Int, bts: Int) {
        if (selectionTileCount > 1) {
            editorState.setMapSelectionProperties(blockType, bts)
        } else {
            editorState.setTileProperties(blockX, blockY, blockType, bts)
        }
    }

    Card(
        modifier = modifier
            .padding(8.dp)
            .width(260.dp)
            .heightIn(max = 600.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (selectionTileCount > 1) {
                        "$selectionTileCount tiles · ${oneBasedRoomCoordinate(blockX, blockY)}–" +
                            oneBasedRoomCoordinate(selectionEndX, selectionEndY)
                    } else {
                        "Tile ${oneBasedRoomCoordinate(blockX, blockY)} · " +
                            "Screen ${roomScreenCoordinateForBlock(blockX, blockY)} · " +
                            "#$metatile" + (selectedBlockType?.let {
                                " 0x${it.toString(16).uppercase()} $propsTypeName"
                            } ?: "")
                    },
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    "✕",
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(4.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Block Type selector ──
            Text("Block Type", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            var btExpanded by remember { mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clickable { btExpanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            selectedBlockType?.let {
                                "0x${it.toString(16).uppercase()} $propsTypeName"
                            } ?: "Mixed block types — choose one",
                            fontSize = 11.sp,
                            color = if (selectedBlockType == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text("▾", fontSize = 10.sp)
                    }
                }
                DropdownMenu(expanded = btExpanded, onDismissRequest = { btExpanded = false }) {
                    for ((typeVal, typeName) in editableBlockTypes) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RadioButton(selected = selectedBlockType == typeVal, onClick = null, modifier = Modifier.size(16.dp))
                                    Text("0x${typeVal.toString(16).uppercase()} $typeName", fontSize = 11.sp)
                                }
                            },
                            onClick = {
                                btExpanded = false
                                if (typeVal != selectedBlockType) {
                                    propsBlockType = typeVal
                                    propsBts = 0
                                    propsBtsMixed = false
                                    applyProperties(typeVal, 0)
                                }
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            var hoveredSlopeBts by remember { mutableStateOf<Int?>(null) }
            if (selectedBlockType == 0x1) {
                val displayBts = hoveredSlopeBts ?: propsBts
                val displayName = SLOPE_BTS_NAMES[displayBts and 0x40.inv()]
                    ?: SLOPE_BTS_NAMES[displayBts]
                if (displayName != null && (!propsBtsMixed || hoveredSlopeBts != null)) {
                    val flipLabel = if (displayBts and 0x40 != 0) " [X-Flipped]" else ""
                    Text(
                        "0x${displayBts.toString(16).uppercase().padStart(2, '0')} $displayName$flipLabel",
                        fontSize = 9.sp,
                        color = if (hoveredSlopeBts != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // ── Door connection / block subtype ──
            val btsLabel = if (selectedBlockType == 0x9) "Connection" else if (selectedBlockType == 0x1) {
                "Slope Shape"
            } else {
                "Sub Type (BTS)"
            }
            if (selectedBlockType != null) {
                Text(btsLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(2.dp))
            }

            if (selectedBlockType == 0x9) {
                val doorConnections = remember(editorState.editVersion) {
                    editorState.doorEntries.toList()
                }
                var connectionDropExpanded by remember { mutableStateOf(false) }
                val connectionLabel = if (propsBtsMixed) {
                    "Mixed connections — choose one"
                } else doorConnections.getOrNull(propsBts)?.let { door ->
                    val destination = rooms.firstOrNull {
                        it.getRoomIdAsInt() == door.destRoomPtr
                    }?.name ?: "Unknown destination"
                    "Connection ${propsBts + 1} → $destination"
                } ?: "Unlinked doorway"
                Box {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(32.dp)
                            .clickable(enabled = doorConnections.isNotEmpty()) {
                                connectionDropExpanded = true
                            },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(connectionLabel, fontSize = 10.sp, modifier = Modifier.weight(1f))
                            if (doorConnections.isNotEmpty()) Text("▾", fontSize = 9.sp)
                        }
                    }
                    DropdownMenu(
                        expanded = connectionDropExpanded,
                        onDismissRequest = { connectionDropExpanded = false },
                    ) {
                        doorConnections.forEachIndexed { index, door ->
                            val destination = rooms.firstOrNull {
                                it.getRoomIdAsInt() == door.destRoomPtr
                            }?.name ?: "Unknown destination"
                            DropdownMenuItem(
                                text = { Text("Connection ${index + 1} → $destination", fontSize = 10.sp) },
                                onClick = {
                                    connectionDropExpanded = false
                                    if (index != propsBts || propsBtsMixed) {
                                        propsBts = index
                                        propsBtsMixed = false
                                        if (selectionTileCount > 1) {
                                            applyProperties(0x9, index)
                                        } else {
                                            editorState.linkDoorwayTilesToConnection(blockX, blockY, index)
                                        }
                                    }
                                },
                                modifier = Modifier.height(30.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            } else if (selectedBlockType == 0x1) {
                SlopeGridPicker(
                    selectedBts = if (propsBtsMixed) -1 else propsBts,
                    onSelect = { btsVal ->
                        if (btsVal != propsBts || propsBtsMixed) {
                            propsBts = btsVal
                            propsBtsMixed = false
                            applyProperties(0x1, btsVal)
                        }
                    },
                    onHoverBts = { hoveredSlopeBts = it }
                )
                Spacer(modifier = Modifier.height(4.dp))
            } else if (btsOptions.isNotEmpty()) {
                var btsDropExpanded by remember { mutableStateOf(false) }
                val btsName = if (propsBtsMixed) {
                    "Mixed BTS values — choose one"
                } else btsOptions.firstOrNull { it.first == propsBts }?.second
                    ?: "Custom (0x${propsBts.toString(16).uppercase().padStart(2, '0')})"
                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .clickable { btsDropExpanded = true },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(btsName, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("▾", fontSize = 10.sp)
                        }
                    }
                    DropdownMenu(expanded = btsDropExpanded, onDismissRequest = { btsDropExpanded = false }) {
                        for ((btsVal, btsOptName) in btsOptions) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        RadioButton(selected = !propsBtsMixed && propsBts == btsVal, onClick = null, modifier = Modifier.size(16.dp))
                                        Text("0x${btsVal.toString(16).uppercase().padStart(2, '0')} $btsOptName", fontSize = 11.sp)
                                    }
                                },
                                onClick = {
                                    btsDropExpanded = false
                                    if (btsVal != propsBts || propsBtsMixed) {
                                        propsBts = btsVal
                                        propsBtsMixed = false
                                        applyProperties(selectedBlockType!!, btsVal)
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (selectedBlockType != null && selectedBlockType != 0x9) {
                // Raw BTS hex input (BasicTextField so typed text is visible, same fix as room search)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(if (btsOptions.isNotEmpty()) "Raw:" else "BTS:", fontSize = 10.sp)
                    var rawText by remember(blockX, blockY, selectionEndX, selectionEndY, propsBts, propsBtsMixed) {
                        mutableStateOf(
                            if (propsBtsMixed) ""
                            else propsBts.toString(16).uppercase().padStart(2, '0')
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(32.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        if (rawText.isEmpty()) {
                            Text("Mixed", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = rawText,
                            onValueChange = { s ->
                                val filtered = s.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(2)
                                rawText = filtered
                                val v = filtered.toIntOrNull(16)
                                if (v != null && v in 0..255 && (v != propsBts || propsBtsMixed)) {
                                    propsBts = v
                                    propsBtsMixed = false
                                    applyProperties(selectedBlockType, v)
                                }
                            },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            // ── Door Connection Info (when block type = Door) ──
            if (selectedBlockType == 0x9 && selectionTileCount == 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(4.dp))
                Text("Door Connection", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(2.dp))

                val allDoors = remember(editorState.editVersion) { editorState.doorEntries.toList() }
                val currentDoor = allDoors.getOrNull(propsBts)
                val projectRoom = editorState.project.newRoomForPreviewId(roomId)
                val connectionSharedOutsideDoorway = remember(
                    blockX, blockY, propsBts, currentDoor, editorState.editVersion,
                ) {
                    currentDoor != null && editorState.isDoorConnectionSharedOutsideDoorway(
                        blockX, blockY, propsBts,
                    )
                }
                val pickerDirection = if (connectionSharedOutsideDoorway && projectRoom != null) {
                    editorState.suggestedDoorDirection(blockX, blockY)
                } else {
                    currentDoor?.direction?.and(0x03)
                        ?: editorState.suggestedDoorDirection(blockX, blockY)
                }

                fun createConnectionForDoorway(opening: DoorwayOpening) {
                    require(projectRoom != null) { "Only project-owned room door lists can currently grow" }
                    require(allDoors.size < 16) { "A room can currently have at most 16 connections" }
                    val addedIndex = editorState.addProjectRoomDoor(
                        roomId = roomId,
                        destinationRoomId = opening.roomId,
                        direction = opening.direction,
                        screenX = opening.screenX,
                        screenY = opening.screenY,
                        romParser = romParser,
                        doorCapCode = opening.doorCapCode,
                    )
                    try {
                        onWorkspaceChanged?.invoke()
                    } catch (failure: Throwable) {
                        editorState.rollbackAddedProjectRoomDoor(roomId, addedIndex)
                        throw failure
                    }
                    propsBts = addedIndex
                    editorState.linkDoorwayTilesToConnection(blockX, blockY, addedIndex)
                }

                fun selectDoorway(opening: DoorwayOpening) {
                    if (currentDoor == null || (connectionSharedOutsideDoorway && projectRoom != null)) {
                        createConnectionForDoorway(opening)
                        return
                    }
                    if (
                        opening.roomId == currentDoor.destRoomPtr &&
                        opening.screenX == currentDoor.screenX &&
                        opening.screenY == currentDoor.screenY &&
                        opening.doorCapCode == currentDoor.doorCapCode
                    ) return
                    val destination = romParser.readRoomHeader(opening.roomId)
                        ?.let(editorState::applyHeaderChanges)
                        ?: error("Destination room could not be read")
                    val match = romParser.findVanillaDoorMatch(
                        opening.roomId, opening.direction, opening.screenX, opening.screenY,
                    )
                    val sourceArea = romParser.readRoomHeader(roomId)?.let(editorState::applyHeaderChanges)?.area
                    val crossArea = sourceArea != null && sourceArea != destination.area
                    val bitflag = mergeDoorBitflagWithMatchedOrientation(
                        currentDoor.bitflag, match?.orientation, crossArea,
                    )
                    editorState.updateDoor(
                        propsBts,
                        currentDoor.copy(
                            destRoomPtr = opening.roomId,
                            bitflag = bitflag,
                            screenX = opening.screenX,
                            screenY = opening.screenY,
                            entryCode = match?.entryCode ?: 0,
                            doorCapCode = opening.doorCapCode,
                        ),
                    )
                }

                if (currentDoor == null) {
                    Text(
                        if (allDoors.isEmpty()) {
                            "This doorway has no connection yet."
                        } else {
                            "BTS ${propsBts.toString(16).uppercase().padStart(2, '0')} is not linked to a connection."
                        },
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (projectRoom != null && roomHeader != null) {
                        DoorDestinationPicker(
                            currentDoor = null,
                            sourceRoomId = roomId,
                            requiredDirection = pickerDirection,
                            rooms = rooms.filter { it.getRoomIdAsInt() != roomId },
                            romParser = romParser,
                            editorState = editorState,
                            enabled = allDoors.size < 16,
                            onDoorwaySelected = { opening ->
                                doorConnectionError = runCatching {
                                    selectDoorway(opening)
                                }.exceptionOrNull()?.message
                            },
                        )
                    } else {
                        Text(
                            "This source-ROM room's door list cannot currently grow; select an existing connection BTS instead.",
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    doorConnectionError?.let {
                        Text(it, fontSize = 8.sp, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    val connectionDiagnostic = remember(
                        roomId,
                        propsBts,
                        currentDoor,
                        editorState.editVersion,
                        rooms,
                    ) {
                        doorDiagnosticsForRoom(roomId, romParser, editorState, rooms)[propsBts]
                    }
                    if (connectionSharedOutsideDoorway) {
                        Text(
                            if (projectRoom != null) {
                                "Connection ${propsBts + 1} is also used by another doorway. Choosing a destination here will give this doorway its own connection."
                            } else {
                                "Connection ${propsBts + 1} is also used by another doorway; changes here affect both openings."
                            },
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Human-facing, one-based screen selector. ROM values stay zero-based internally.
                    @Composable
                    fun ScreenDropdown(label: String, value: Int, options: IntRange, onValueChange: (Int) -> Unit) {
                        var expanded by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(label, fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().height(28.dp)
                                        .clickable { expanded = true },
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text("${value + 1}", fontSize = 10.sp, modifier = Modifier.weight(1f))
                                        Text("▾", fontSize = 9.sp)
                                    }
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                    modifier = Modifier.requiredSizeIn(maxHeight = 300.dp)
                                ) {
                                    for (v in options) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "${v + 1}",
                                                    fontSize = 10.sp,
                                                    fontWeight = if (v == value) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                expanded = false
                                                if (v != value) onValueChange(v)
                                            },
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    DoorDestinationPicker(
                        currentDoor = currentDoor,
                        sourceRoomId = roomId,
                        requiredDirection = pickerDirection,
                        rooms = rooms.filter { it.getRoomIdAsInt() != roomId },
                        romParser = romParser,
                        editorState = editorState,
                        enabled = !connectionSharedOutsideDoorway || projectRoom == null || allDoors.size < 16,
                        onDoorwaySelected = { opening ->
                            doorConnectionError = runCatching {
                                selectDoorway(opening)
                            }.exceptionOrNull()?.message
                        },
                    )
                    doorConnectionError?.let {
                        Text(it, fontSize = 8.sp, color = MaterialTheme.colorScheme.error)
                    }
                    val effectiveDestinationRoom = remember(currentDoor.destRoomPtr, editorState.editVersion) {
                        romParser.readRoomHeader(currentDoor.destRoomPtr)?.let(editorState::applyHeaderChanges)
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    if (connectionDiagnostic?.needsAttention == true) {
                        DoorConnectionHealthCard(connectionDiagnostic)
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    val dirNames = listOf("Right", "Left", "Down", "Up")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Entrance:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                        Text(
                            "${incomingDoorEdgeName(currentDoor.direction).replaceFirstChar { it.uppercase() }} edge" +
                                " · screen ${oneBasedRoomCoordinate(currentDoor.screenX, currentDoor.screenY)}",
                            fontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    // Direction dropdown
                    var dirDropExpanded by remember { mutableStateOf(false) }
                    val currentDir = currentDoor.direction and 0x03
                    val isBubble = (currentDoor.direction and 0x04) != 0
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Travel:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                    .clickable { dirDropExpanded = true },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    val bubbleTag = if (isBubble) " (closing)" else ""
                                    Text("${dirNames.getOrElse(currentDir) { "?" }}$bubbleTag", fontSize = 9.sp, modifier = Modifier.weight(1f))
                                    Text("▾", fontSize = 9.sp)
                                }
                            }
                            DropdownMenu(
                                expanded = dirDropExpanded,
                                onDismissRequest = { dirDropExpanded = false }
                            ) {
                                for ((di, dn) in dirNames.withIndex()) {
                                    DropdownMenuItem(
                                        text = { Text(dn, fontSize = 10.sp) },
                                        onClick = {
                                            dirDropExpanded = false
                                            val newDir = di + (if (isBubble) 4 else 0)
                                            val newBitflag = (newDir shl 8) or (currentDoor.bitflag and 0xFF)
                                            val derivedCap = editorState.deriveEffectiveDoorCapPosition(
                                                romParser, currentDoor.destRoomPtr, newDir,
                                                currentDoor.screenX, currentDoor.screenY,
                                            )
                                            editorState.updateDoor(propsBts, currentDoor.copy(
                                                bitflag = newBitflag,
                                                doorCapCode = derivedCap ?: currentDoor.doorCapCode
                                            ))
                                        },
                                        modifier = Modifier.height(26.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    val destinationColumns = 0 until (effectiveDestinationRoom?.width ?: 1).coerceAtLeast(1)
                    val destinationRows = 0 until (effectiveDestinationRoom?.height ?: 1).coerceAtLeast(1)

                    ScreenDropdown("Screen col:", currentDoor.screenX, destinationColumns) { v ->
                        val derivedCap = editorState.deriveEffectiveDoorCapPosition(
                            romParser, currentDoor.destRoomPtr, currentDoor.direction, v, currentDoor.screenY)
                        val match = romParser.findVanillaDoorMatch(
                            currentDoor.destRoomPtr, currentDoor.direction, v, currentDoor.screenY)
                        val newBitflag = mergeDoorBitflagWithMatchedOrientation(
                            currentDoor.bitflag,
                            match?.orientation
                        )
                        editorState.updateDoor(propsBts, currentDoor.copy(
                            screenX = v,
                            bitflag = newBitflag,
                            entryCode = match?.entryCode ?: currentDoor.entryCode,
                            doorCapCode = derivedCap ?: match?.doorCapCode ?: currentDoor.doorCapCode
                        ))
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    ScreenDropdown("Screen row:", currentDoor.screenY, destinationRows) { v ->
                        val derivedCap = editorState.deriveEffectiveDoorCapPosition(
                            romParser, currentDoor.destRoomPtr, currentDoor.direction, currentDoor.screenX, v)
                        val match = romParser.findVanillaDoorMatch(
                            currentDoor.destRoomPtr, currentDoor.direction, currentDoor.screenX, v)
                        val newBitflag = mergeDoorBitflagWithMatchedOrientation(
                            currentDoor.bitflag,
                            match?.orientation
                        )
                        editorState.updateDoor(propsBts, currentDoor.copy(
                            screenY = v,
                            bitflag = newBitflag,
                            entryCode = match?.entryCode ?: currentDoor.entryCode,
                            doorCapCode = derivedCap ?: match?.doorCapCode ?: currentDoor.doorCapCode
                        ))
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    var advancedDoorFields by remember(roomId, propsBts) { mutableStateOf(false) }
                    TextButton(
                        onClick = { advancedDoorFields = !advancedDoorFields },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp),
                    ) {
                        Text(if (advancedDoorFields) "▾ Hide advanced" else "▸ Advanced", fontSize = 9.sp)
                    }
                    if (advancedDoorFields) {
                        // Distance from door (16-bit, keep as text)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Distance:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                            var distText by remember(currentDoor) {
                                mutableStateOf("0x${currentDoor.distFromDoor.toString(16).uppercase().padStart(4, '0')}")
                            }
                            AppTextInput(
                                value = distText,
                                onValueChange = { v ->
                                    distText = v
                                    v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.let {
                                        editorState.updateDoor(propsBts, currentDoor.copy(distFromDoor = it))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                fontSize = 10.sp, monospace = true
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        // Elevator + Closing door toggles
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = currentDoor.isElevator,
                                onCheckedChange = { checked ->
                                    val newFlags = if (checked) currentDoor.bitflag or 0x80 else currentDoor.bitflag and 0x7F
                                    editorState.updateDoor(propsBts, currentDoor.copy(bitflag = newFlags))
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Text("Elevator", fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Checkbox(
                                checked = isBubble,
                                onCheckedChange = { checked ->
                                    val dir = currentDoor.direction and 0x03
                                    val newDir = dir + (if (checked) 4 else 0)
                                    val newBitflag = (newDir shl 8) or (currentDoor.bitflag and 0xFF)
                                    editorState.updateDoor(propsBts, currentDoor.copy(bitflag = newBitflag))
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Text("Closing door", fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        // Door cap position with auto-derive
                        val autoCap = remember(
                            currentDoor.destRoomPtr,
                            currentDoor.direction,
                            currentDoor.screenX,
                            currentDoor.screenY,
                            editorState.editVersion,
                        ) {
                            editorState.deriveEffectiveDoorCapPosition(
                                romParser, currentDoor.destRoomPtr, currentDoor.direction,
                                currentDoor.screenX, currentDoor.screenY,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Door Cap:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                            var capText by remember(currentDoor) {
                                mutableStateOf("0x${currentDoor.doorCapCode.toString(16).uppercase().padStart(4, '0')}")
                            }
                            AppTextInput(
                                value = capText,
                                onValueChange = { v ->
                                    capText = v
                                    v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.let {
                                        editorState.updateDoor(propsBts, currentDoor.copy(doorCapCode = it))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                fontSize = 10.sp, monospace = true
                            )
                            if (autoCap != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    modifier = Modifier.height(20.dp)
                                        .clickable {
                                            editorState.updateDoor(propsBts, currentDoor.copy(doorCapCode = autoCap))
                                        },
                                    shape = MaterialTheme.shapes.small,
                                    color = if (currentDoor.doorCapCode == autoCap) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Auto", fontSize = 8.sp)
                                    }
                                }
                            }
                        }
                        if (autoCap != null && currentDoor.doorCapCode != autoCap) {
                            val capX = autoCap and 0xFF
                            val capY = (autoCap shr 8) and 0xFF
                            Text(
                                "Suggested: 0x${autoCap.toString(16).uppercase().padStart(4, '0')} ($capX, $capY)",
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(start = 72.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Entry ASM:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                            var asmText by remember(currentDoor) {
                                mutableStateOf("0x${currentDoor.entryCode.toString(16).uppercase().padStart(4, '0')}")
                            }
                            AppTextInput(
                                value = asmText,
                                onValueChange = { v ->
                                    asmText = v
                                    v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.let {
                                        editorState.updateDoor(propsBts, currentDoor.copy(entryCode = it))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                fontSize = 10.sp, monospace = true
                            )
                        }
                    }
                }
            }

            // ── Items / PLMs at this tile ──
            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(4.dp))
            Text("Items / PLMs", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Item add/remove scope", fontSize = 8.sp, color = MaterialTheme.colorScheme.outline)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = editorState.itemEditScope == ItemStateScope.THIS_STATE,
                    onClick = { editorState.selectItemEditScope(ItemStateScope.THIS_STATE) },
                    label = { Text("This state", fontSize = 9.sp) },
                    modifier = Modifier.height(26.dp),
                )
                FilterChip(
                    selected = editorState.itemEditScope == ItemStateScope.ALL_STATES,
                    onClick = { editorState.selectItemEditScope(ItemStateScope.ALL_STATES) },
                    label = { Text("All states", fontSize = 9.sp) },
                    modifier = Modifier.height(26.dp),
                )
            }
            Text(
                if (editorState.itemEditScope == ItemStateScope.ALL_STATES) {
                    "Includes room conditions added later"
                } else {
                    "Only the selected room-state branch"
                },
                fontSize = 8.sp,
                color = if (editorState.itemEditScope == ItemStateScope.ALL_STATES) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            val plmsHere = editorState.getPlmsAt(blockX, blockY)
            val itemPlms = plmsHere.filter { editorState.isEditorItemPlm(it.id) }
            val otherPlms = plmsHere.filter { !editorState.isEditorItemPlm(it.id) }

            if (itemPlms.isEmpty() && otherPlms.isEmpty()) {
                Text("None", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
            }
            for (plm in itemPlms) {
                val iName = editorState.customItemNameForPlm(plm.id)
                    ?: RomParser.itemNameForPlm(plm.id)
                    ?: "PLM 0x${plm.id.toString(16)}"
                val configuredScope = editorState.configuredItemScope(plm)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(iName, fontSize = 10.sp)
                        Text(
                            "bit: 0x${plm.param.toString(16).uppercase().padStart(2, '0')} · " +
                                when (configuredScope) {
                                    ItemStateScope.ALL_STATES -> "All states"
                                    ItemStateScope.THIS_STATE -> "This state"
                                    null -> "Original state data"
                                },
                            fontSize = 8.sp,
                            color = if (configuredScope == ItemStateScope.ALL_STATES) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (configuredScope != ItemStateScope.ALL_STATES) {
                            TextButton(
                                onClick = { editorState.applyItemToAllStates(plm) },
                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                modifier = Modifier.height(24.dp),
                            ) {
                                Text("Apply to all", fontSize = 8.sp)
                            }
                        }
                        Text(
                            "✕",
                            modifier = Modifier
                                .clickable { editorState.removePlm(plm.x, plm.y, plm.id) }
                                .padding(horizontal = 4.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            for (plm in otherPlms) {
                val pName = RomParser.plmDisplayName(plm.id, plm.param)
                val canRemove = RomParser.isStationPlm(plm.id) || RomParser.isGatePlm(plm.id)
                        || RomParser.doorCapColor(plm.id) != null || RomParser.isScrollPlm(plm.id)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Save station spawn details
                        if (plm.id == 0xB76F && romParser != null) {
                            val saveIdx = plm.param and 0xFF
                            val area = editorState.activeRoomAreaForEditing()
                            val saveEntry = editorState.effectiveSaveStationSpawn(area, saveIdx, romParser)
                            if (saveEntry != null) {
                                val detailColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                Text("Save #$saveIdx (Area $area, ${saveEntry.source})", fontSize = 9.sp, color = detailColor)
                                Row {
                                    Text("Spawn: ", fontSize = 9.sp, color = detailColor)
                                    Text("X=${saveEntry.samusXSigned} Y=${saveEntry.samusYSigned}", fontSize = 9.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                                Row {
                                    Text("Scroll: ", fontSize = 9.sp, color = detailColor)
                                    Text("X=${saveEntry.scrollX} Y=${saveEntry.scrollY}", fontSize = 9.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                                Row {
                                    Text("Door: ", fontSize = 9.sp, color = detailColor)
                                    Text("\$${saveEntry.doorPtr.toString(16).uppercase().padStart(4, '0')}", fontSize = 9.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                                var spawnXText by remember(area, saveIdx, saveEntry.samusX, saveEntry.source) {
                                    mutableStateOf(saveEntry.samusXSigned.toString())
                                }
                                var spawnYText by remember(area, saveIdx, saveEntry.samusY, saveEntry.source) {
                                    mutableStateOf(saveEntry.samusYSigned.toString())
                                }
                                var scrollXText by remember(area, saveIdx, saveEntry.scrollX, saveEntry.source) {
                                    mutableStateOf(saveEntry.scrollX.toString())
                                }
                                var scrollYText by remember(area, saveIdx, saveEntry.scrollY, saveEntry.source) {
                                    mutableStateOf(saveEntry.scrollY.toString())
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 3.dp)
                                ) {
                                    AppOutlinedTextField(
                                        value = spawnXText,
                                        onValueChange = { spawnXText = it },
                                        label = "Samus X",
                                        singleLine = true,
                                        fontSize = 9.sp,
                                        modifier = Modifier.width(66.dp)
                                    )
                                    AppOutlinedTextField(
                                        value = spawnYText,
                                        onValueChange = { spawnYText = it },
                                        label = "Y",
                                        singleLine = true,
                                        fontSize = 9.sp,
                                        modifier = Modifier.width(54.dp)
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 3.dp)
                                ) {
                                    AppOutlinedTextField(
                                        value = scrollXText,
                                        onValueChange = { scrollXText = it },
                                        label = "Scroll X",
                                        singleLine = true,
                                        fontSize = 9.sp,
                                        modifier = Modifier.width(66.dp)
                                    )
                                    AppOutlinedTextField(
                                        value = scrollYText,
                                        onValueChange = { scrollYText = it },
                                        label = "Y",
                                        singleLine = true,
                                        fontSize = 9.sp,
                                        modifier = Modifier.width(54.dp)
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                ) {
                                    TextButton(
                                        onClick = {
                                            val sx = parseFlexibleInt(spawnXText)
                                            val sy = parseFlexibleInt(spawnYText)
                                            val scx = parseFlexibleInt(scrollXText)
                                            val scy = parseFlexibleInt(scrollYText)
                                            if (sx != null && sy != null) {
                                                editorState.updateSaveStationSpawnPosition(area, saveIdx, sx, sy, romParser)
                                            }
                                            if (scx != null && scy != null) {
                                                editorState.updateSaveStationSpawnScroll(area, saveIdx, scx, scy, romParser)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.heightIn(min = 30.dp)
                                    ) { Text("Apply Spawn", fontSize = 9.sp) }
                                    TextButton(
                                        onClick = { editorState.resetSaveStationSpawnToAuto(plm) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.heightIn(min = 30.dp)
                                    ) { Text("Reset Auto", fontSize = 9.sp) }
                                }
                            }
                        }
                        if (RomParser.isScrollPlm(plm.id) && plm.id == 0xB703 && romParser != null) {
                            val rw = roomHeader?.width ?: 0
                            val rh = roomHeader?.height ?: 0
                            val isCustom = (plm.param and 0xFF00) == 0xCC00
                            Text("When crossed:", fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isCustom) {
                                val cmdIdx = plm.param and 0xFF
                                val cmdId = "cmd_$cmdIdx"
                                val cmds = editorState.getScrollCommand(cmdId)
                                if (cmds != null) {
                                    for (cmd in cmds) {
                                        val invalid = cmd.screenIndex !in 0 until (rw * rh) || cmd.scrollValue !in 0..2
                                        Text(
                                            "  ${RomParser.formatScrollCommand(cmd.screenIndex, cmd.scrollValue, rw)}",
                                            fontSize = 8.sp,
                                            color = if (invalid) MaterialTheme.colorScheme.error else Color(0xFFFF8040)
                                        )
                                    }
                                } else {
                                    Text("  Missing custom scroll behavior", fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                Text("Custom scroll behavior", fontSize = 7.sp, color = MaterialTheme.colorScheme.outline)
                            } else if (rw > 0) {
                                val cmds = RomParser.decodeScrollCommands(
                                    romParser,
                                    plm.param, rw
                                )
                                for ((screenIdx, _, scrollVal) in cmds) {
                                    val invalid = screenIdx !in 0 until (rw * rh) || scrollVal !in 0..2
                                    Text(
                                        "  ${RomParser.formatScrollCommand(screenIdx, scrollVal, rw)}",
                                        fontSize = 8.sp,
                                        color = if (invalid) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text("ROM scroll behavior \$${plm.param.toString(16).uppercase().padStart(4, '0')}",
                                    fontSize = 7.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        } else if (plm.id in setOf(0xB63B, 0xB63F, 0xB647, 0xB643)) {
                            Text("Extends an adjacent scroll trigger's activation zone.",
                                fontSize = 8.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if (canRemove) {
                        Text(
                            "✕",
                            modifier = Modifier
                                .clickable { editorState.removePlm(plm.x, plm.y, plm.id) }
                                .padding(horizontal = 4.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Add Item button + dropdown
            Spacer(modifier = Modifier.height(4.dp))
            var addItemExpanded by remember { mutableStateOf(false) }
            var addItemStyle by remember { mutableStateOf(0) }
            val placementCustomItems = remember(editorState.patchVersion, editorState.project.patches) {
                editorState.enabledCustomItems()
            }
            Box {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                        .clickable { addItemExpanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+ Add Item", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                DropdownMenu(
                    expanded = addItemExpanded,
                    onDismissRequest = { addItemExpanded = false }
                ) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Visible" to 0, "Chozo" to 1, "Hidden" to 2).forEach { (label, idx) ->
                            FilterChip(
                                selected = addItemStyle == idx,
                                onClick = { addItemStyle = idx },
                                label = { Text(label, fontSize = 9.sp) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                    Divider()
                    for (item in RomParser.ITEM_DEFS) {
                        val plmId = when (addItemStyle) {
                            1 -> item.chozoId
                            2 -> item.hiddenId
                            else -> item.visibleId
                        }
                        DropdownMenuItem(
                            text = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.shortLabel, fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Text(item.name, fontSize = 11.sp)
                                }
                            },
                            onClick = {
                                addItemExpanded = false
                                editorState.addPlm(plmId, blockX, blockY, 0)
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    for (item in placementCustomItems) {
                        val plmId = when (addItemStyle) {
                            1 -> item.chozoPlmId
                            2 -> item.hiddenPlmId
                            else -> item.visiblePlmId
                        } ?: continue
                        DropdownMenuItem(
                            text = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.shortLabel, fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Text(item.name, fontSize = 11.sp)
                                }
                            },
                            onClick = {
                                addItemExpanded = false
                                editorState.addPlm(plmId, blockX, blockY, 0)
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            // Add Station button + dropdown
            Spacer(modifier = Modifier.height(4.dp))
            var addStationExpanded by remember { mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                        .clickable { addStationExpanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+ Add Station / Gate", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                DropdownMenu(
                    expanded = addStationExpanded,
                    onDismissRequest = { addStationExpanded = false }
                ) {
                    Text("Stations", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    for (station in RomParser.STATION_PLMS) {
                        DropdownMenuItem(
                            text = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(station.shortLabel, fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Text(station.name, fontSize = 11.sp)
                                }
                            },
                            onClick = {
                                addStationExpanded = false
                                editorState.addPlm(station.plmId, blockX, blockY, station.defaultParam)
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    Divider()
                    Text("Gates", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    for (gate in RomParser.GATE_PLMS) {
                        DropdownMenuItem(
                            text = { Text(gate.name, fontSize = 11.sp) },
                            onClick = {
                                addStationExpanded = false
                                editorState.addPlm(gate.plmId, blockX, blockY, gate.param)
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            // Add Door Cap button + dropdown
            // Auto-detect direction from screen edge position
            val autoDir = when {
                blockX % 16 == 0 -> "Right"   // left edge of screen → door opens right
                blockX % 16 == 15 -> "Left"   // right edge → opens left
                blockY % 16 == 0 -> "Down"    // top edge → opens down
                blockY % 16 == 15 -> "Up"     // bottom edge → opens up
                else -> null
            }
            Spacer(modifier = Modifier.height(4.dp))
            var addDoorCapExpanded by remember { mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                        .clickable { addDoorCapExpanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+ Add Door Cap" + if (autoDir != null) " ($autoDir)" else "",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                DropdownMenu(
                    expanded = addDoorCapExpanded,
                    onDismissRequest = { addDoorCapExpanded = false }
                ) {
                    // If on screen edge, show auto-detected direction first
                    if (autoDir != null) {
                        Text("Auto: $autoDir", fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = Color(0xFF00CC66),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        val autoCaps = RomParser.DOOR_CAP_PLMS.filter { it.direction == autoDir }
                        for (cap in autoCaps) {
                            DropdownMenuItem(
                                text = { DoorCapLabel(cap) },
                                onClick = {
                                    addDoorCapExpanded = false
                                    editorState.addPlm(cap.plmId, blockX, blockY, 0x0000)
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                        Divider()
                    }
                    val doorColors = listOf("Blue", "Red", "Green", "Yellow", "Grey")
                    for (color in doorColors) {
                        val caps = RomParser.DOOR_CAP_PLMS.filter { it.color == color }
                        Text(color, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        for (cap in caps) {
                            DropdownMenuItem(
                                text = { DoorCapLabel(cap) },
                                onClick = {
                                    addDoorCapExpanded = false
                                    editorState.addPlm(cap.plmId, blockX, blockY, 0x0000)
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                        if (color != doorColors.last()) Divider()
                    }
                }
            }

            // Add scroll trigger button + dropdown
            Spacer(modifier = Modifier.height(4.dp))
            var addScrollExpanded by remember { mutableStateOf(false) }
            var showScrollEditor by remember { mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                        .clickable { addScrollExpanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = Color(0xFFFF8040).copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+ Add Scroll Trigger", fontSize = 10.sp,
                            color = Color(0xFFFF8040))
                    }
                }
                DropdownMenu(
                    expanded = addScrollExpanded,
                    onDismissRequest = { addScrollExpanded = false }
                ) {
                    val rw = roomHeader?.width ?: 1
                    val originalScrollTriggers = roomHeader
                        ?.let { romParser.parsePlmSet(it.plmSetPtr) }
                        ?: emptyList()
                    val originalHere = originalScrollTriggersAt(
                        originalScrollTriggers,
                        blockX,
                        blockY,
                    )
                    val reusableCommandPtrs = reusableScrollCommandPtrs(
                        originalScrollTriggers,
                        editorState.workingPlms,
                    )
                    fun commandLines(cmdPtr: Int): List<String> {
                        return if (rw > 0 && (cmdPtr and 0xFF00) != 0xCC00) {
                            RomParser.decodeScrollCommands(romParser, cmdPtr, rw)
                                .map { (sIdx, _, sv) -> RomParser.formatScrollCommand(sIdx, sv, rw) }
                        } else if ((cmdPtr and 0xFF00) == 0xCC00) {
                            val cmds = editorState.getScrollCommand("cmd_${cmdPtr and 0xFF}").orEmpty()
                            cmds.map { RomParser.formatScrollCommand(it.screenIndex, it.scrollValue, rw) }
                        } else emptyList()
                    }
                    if (originalHere.isNotEmpty()) {
                        Text("Restore original scroll trigger here:", fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold)
                        for (trigger in originalHere) {
                            val cmdLines = commandLines(trigger.param)
                            val itemHeight = (28 + cmdLines.size * 14).coerceAtMost(80)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        for (line in cmdLines) {
                                            Text(line, fontSize = 9.sp,
                                                color = Color.White)
                                        }
                                        Text("Original ROM scroll behavior \$${trigger.param.toString(16).uppercase().padStart(4, '0')}",
                                            fontSize = 7.sp,
                                            color = Color(0xFF99AABB))
                                    }
                                },
                                onClick = {
                                    addScrollExpanded = false
                                    editorState.addPlm(0xB703, blockX, blockY, trigger.param)
                                },
                                modifier = Modifier.heightIn(min = itemHeight.dp)
                            )
                        }
                        Divider()
                    }
                    if (reusableCommandPtrs.isNotEmpty()) {
                        Text("Use an existing scroll behavior:", fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold)
                        for (cmdPtr in reusableCommandPtrs) {
                            val cmdLines = commandLines(cmdPtr)
                            val itemHeight = (28 + cmdLines.size * 14).coerceAtMost(80)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        for (line in cmdLines) {
                                            Text(line, fontSize = 9.sp, color = Color.White)
                                        }
                                        val useCount = editorState.workingPlms.count {
                                            it.id == 0xB703 && it.param == cmdPtr
                                        }
                                        val useLabel = when (useCount) {
                                            0 -> "Not currently used"
                                            1 -> "Used by 1 trigger"
                                            else -> "Used by $useCount triggers"
                                        }
                                        Text("$useLabel · ROM \$${cmdPtr.toString(16).uppercase().padStart(4, '0')}",
                                            fontSize = 7.sp,
                                            color = Color(0xFF99AABB))
                                    }
                                },
                                onClick = {
                                    addScrollExpanded = false
                                    editorState.addPlm(0xB703, blockX, blockY, cmdPtr)
                                },
                                modifier = Modifier.heightIn(min = itemHeight.dp)
                            )
                        }
                        Divider()
                    }
                    DropdownMenuItem(
                        text = { Text("+ Create Scroll Behavior...", fontSize = 10.sp, color = Color.White) },
                        onClick = {
                            addScrollExpanded = false
                            showScrollEditor = true
                        },
                        modifier = Modifier.height(28.dp)
                    )
                    Divider()
                    Text("Extend the activation zone:", fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Color(0xFFFF8040),
                        fontWeight = FontWeight.Bold)
                    Text("Place next to a trigger or matching extension",
                        fontSize = 7.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 0.dp),
                        color = Color(0xFF99AABB))
                    for ((plmId, label) in listOf(
                        0xB63B to "→ One Tile Right",
                        0xB63F to "← One Tile Left",
                        0xB647 to "↑ One Tile Up",
                        0xB643 to "↓ One Tile Down"
                    )) {
                        val canPlace = canPlaceScrollExtension(
                            plmId,
                            blockX,
                            blockY,
                            editorState.workingPlms,
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    fontSize = 10.sp,
                                    color = if (canPlace) Color.White else Color(0xFF778899),
                                )
                            },
                            enabled = canPlace,
                            onClick = {
                                addScrollExpanded = false
                                editorState.addPlm(plmId, blockX, blockY, 0x8000)
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            // ─── New Custom Scroll Trigger (visual editor) ───
            if (!showScrollEditor) {
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                        .clickable { showScrollEditor = true },
                    shape = MaterialTheme.shapes.small,
                    color = Color(0xFFFF8040).copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+ Create Scroll Behavior...", fontSize = 10.sp,
                            color = Color(0xFFFF8040).copy(alpha = 0.7f))
                    }
                }
            } else {
                val rw = roomHeader?.width ?: 1
                val rh = roomHeader?.height ?: 1
                ScrollCommandEditor(
                    roomWidthScreens = rw,
                    roomHeightScreens = rh,
                    initialCommands = emptyList(),
                    onSave = { commands ->
                        editorState.addScrollTriggerWithCommands(
                            blockX, blockY, commands
                        )
                        showScrollEditor = false
                    },
                    onCancel = { showScrollEditor = false }
                )
            }

            // ─── Enemies at/near this tile ───
            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(4.dp))
            Text("Enemies", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val tileCenterX = blockX * 16 + 8
            val tileCenterY = blockY * 16 + 8
            val enemiesHere = editorState.getEnemiesNear(tileCenterX, tileCenterY, radius = 16)

            if (enemiesHere.isEmpty()) {
                Text("None", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
            }
            for (enemy in enemiesHere) {
                val eName = RomParser.enemyName(enemy.id)
                var editing by remember { mutableStateOf(false) }
                if (!editing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(eName, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "0x${enemy.id.toString(16).uppercase().padStart(4, '0')}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                "pos: (${enemy.x}, ${enemy.y})  prop: 0x${enemy.properties.toString(16).uppercase().padStart(4, '0')}",
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "✎",
                            modifier = Modifier
                                .clickable { editing = true }
                                .padding(horizontal = 4.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "✕",
                            modifier = Modifier
                                .clickable { editorState.removeEnemy(enemy) }
                                .padding(horizontal = 4.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    var editX by remember { mutableStateOf(enemy.x.toString()) }
                    var editY by remember { mutableStateOf(enemy.y.toString()) }
                    var editProps by remember { mutableStateOf(enemy.properties) }
                    var editInitParam by remember { mutableStateOf(enemy.initParam.toString(16).uppercase().padStart(4, '0')) }
                    var editExtra1 by remember { mutableStateOf(enemy.extra1.toString(16).uppercase().padStart(4, '0')) }
                    var editExtra2 by remember { mutableStateOf(enemy.extra2.toString(16).uppercase().padStart(4, '0')) }
                    var editExtra3 by remember { mutableStateOf(enemy.extra3.toString(16).uppercase().padStart(4, '0')) }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(eName, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("ID: 0x${enemy.id.toString(16).uppercase().padStart(4, '0')}",
                            fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))

                        // Position
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("X:", fontSize = 9.sp)
                            AppTextInput(
                                value = editX, onValueChange = { editX = it },
                                modifier = Modifier.width(60.dp),
                                fontSize = 10.sp, monospace = true
                            )
                            Text("Y:", fontSize = 9.sp)
                            AppTextInput(
                                value = editY, onValueChange = { editY = it },
                                modifier = Modifier.width(60.dp),
                                fontSize = 10.sp, monospace = true
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        // Property flag checkboxes (from SMILE enemy editor)
                        Text("Enemy Data Flags", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Per-room enemy population properties field (16-bit).
                        // These are PER-INSTANCE flags, not species-wide.
                        // From SM disassembly: stored in $7E:0F86,x at runtime.
                        val flagDefs = listOf(
                            0x0400 to "Platform (walkable)",
                            0x0001 to "Invisible (don't draw)",
                            0x0200 to "Persist Off-Screen",
                            0x0800 to "Non-Responsive (no dmg)",
                            0x2000 to "Solid to Beams",
                            0x1000 to "Extended Spritemap",
                        )
                        for ((bit, label) in flagDefs) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().height(22.dp)
                            ) {
                                Checkbox(
                                    checked = (editProps and bit) != 0,
                                    onCheckedChange = { checked ->
                                        editProps = if (checked) editProps or bit else editProps and bit.inv()
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(label, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        // Extended fields
                        @Composable
                        fun HexField(label: String, value: String, onValueChange: (String) -> Unit) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(68.dp))
                                AppTextInput(
                                    value = value, onValueChange = onValueChange,
                                    modifier = Modifier.weight(1f),
                                    fontSize = 9.sp, monospace = true, height = 28.dp
                                )
                            }
                        }
                        HexField("Tilemaps:", editInitParam) { editInitParam = it }
                        HexField("Graphics:", editExtra1) { editExtra1 = it }
                        HexField("Speed:", editExtra2) { editExtra2 = it }
                        HexField("Speed 2:", editExtra3) { editExtra3 = it }

                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                modifier = Modifier.height(24.dp).clickable {
                                    val nx = editX.toIntOrNull() ?: enemy.x
                                    val ny = editY.toIntOrNull() ?: enemy.y
                                    val nInit = editInitParam.removePrefix("0x").removePrefix("0X")
                                        .toIntOrNull(16) ?: enemy.initParam
                                    val nE1 = editExtra1.removePrefix("0x").removePrefix("0X")
                                        .toIntOrNull(16) ?: enemy.extra1
                                    val nE2 = editExtra2.removePrefix("0x").removePrefix("0X")
                                        .toIntOrNull(16) ?: enemy.extra2
                                    val nE3 = editExtra3.removePrefix("0x").removePrefix("0X")
                                        .toIntOrNull(16) ?: enemy.extra3
                                    editorState.updateEnemy(
                                        enemy,
                                        RomParser.EnemyEntry(enemy.id, nx, ny, nInit, editProps, nE1, nE2, nE3)
                                    )
                                    editing = false
                                },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text("Save", fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Surface(
                                modifier = Modifier.height(24.dp).clickable { editing = false },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text("Cancel", fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }

            // Add Enemy button + searchable dropdown
            Spacer(modifier = Modifier.height(4.dp))
            var addEnemyExpanded by remember { mutableStateOf(false) }
            var enemySearch by remember { mutableStateOf("") }
            Box {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                        .clickable { addEnemyExpanded = true; enemySearch = "" },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+ Add Enemy", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                DropdownMenu(
                    expanded = addEnemyExpanded,
                    onDismissRequest = { addEnemyExpanded = false },
                    modifier = Modifier.requiredSizeIn(maxHeight = 400.dp, maxWidth = 250.dp)
                ) {
                    AppTextInput(
                        value = enemySearch,
                        onValueChange = { enemySearch = it },
                        placeholder = "Search enemies…",
                        fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    val filtered = remember(enemySearch) {
                        val q = enemySearch.trim().lowercase()
                        if (q.isEmpty()) RomParser.ENEMY_CATALOG
                        else RomParser.ENEMY_CATALOG.filter { (id, name) ->
                            name.lowercase().contains(q) ||
                                id.toString(16).contains(q, ignoreCase = true)
                        }
                    }
                    for ((enemyId, enemyName) in filtered) {
                        DropdownMenuItem(
                            text = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        enemyId.toString(16).uppercase().padStart(4, '0'),
                                        fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(enemyName, fontSize = 11.sp)
                                }
                            },
                            onClick = {
                                addEnemyExpanded = false
                                val pixelX = blockX * 16
                                val pixelY = blockY * 16
                                editorState.addEnemy(enemyId, pixelX, pixelY)
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    if (filtered.isEmpty()) {
                        Text("No matches", fontSize = 10.sp,
                            modifier = Modifier.padding(8.dp),
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            // Move Samus Here (only when emulator is connected)
            if (emulatorConnected && onMoveSamusHere != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                        .clickable {
                            val px = blockX * 16 + 8
                            val py = blockY * 16 + 8
                            onMoveSamusHere(px, py)
                        },
                    shape = MaterialTheme.shapes.small,
                    color = Color(0xFF2196F3)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Move Samus Here · tile ${oneBasedRoomCoordinate(blockX, blockY)}", fontSize = 10.sp,
                            color = Color.White)
                    }
                }
            }
        }
    }

}

@Composable
private fun DoorDestinationPicker(
    currentDoor: RomParser.DoorEntry?,
    sourceRoomId: Int,
    requiredDirection: Int,
    rooms: List<RoomInfo>,
    romParser: RomParser,
    editorState: EditorState,
    enabled: Boolean,
    onDoorwaySelected: (DoorwayOpening) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selectedRoomId by remember(requiredDirection) { mutableStateOf<Int?>(null) }
    val roomOptions = remember(rooms, romParser, editorState.editVersion) {
        rooms.mapNotNull { info ->
            romParser.readRoomHeader(info.getRoomIdAsInt())
                ?.let(editorState::applyHeaderChanges)
                ?.let { header -> Triple(info, header.areaName, header.area) }
        }.sortedWith(compareBy({ it.third }, { it.first.name.lowercase() }))
    }
    val selectedName = currentDoor?.let { door ->
        roomOptions.firstOrNull { it.first.getRoomIdAsInt() == door.destRoomPtr }?.first?.name
            ?: "Unknown destination"
    } ?: "Choose destination…"
    val filteredRooms = if (search.isBlank()) roomOptions else {
        val query = search.trim()
        roomOptions.filter { (info, areaName, _) ->
            info.name.contains(query, ignoreCase = true) ||
                info.id.contains(query, ignoreCase = true) ||
                areaName.contains(query, ignoreCase = true)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Destination:",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(28.dp).clickable(enabled = enabled) { expanded = true },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedName,
                        fontSize = 9.sp,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    if (enabled) Text("▾", fontSize = 9.sp)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    search = ""
                    selectedRoomId = null
                },
                modifier = Modifier.width(340.dp).requiredSizeIn(maxHeight = 420.dp),
            ) {
                if (selectedRoomId == null) {
                    Text(
                        "Choose a room",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    AppTextInput(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = "Search room or area…",
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth(),
                    )
                    for ((info, areaName, _) in filteredRooms) {
                        val optionRoomId = info.getRoomIdAsInt()
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        info.name,
                                        fontSize = 10.sp,
                                        fontWeight = if (optionRoomId == currentDoor?.destRoomPtr) {
                                            FontWeight.Bold
                                        } else FontWeight.Normal,
                                    )
                                    Text(
                                        areaName,
                                        fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                selectedRoomId = optionRoomId
                                search = ""
                            },
                            modifier = Modifier.height(38.dp),
                        )
                    }
                    if (filteredRooms.isEmpty()) {
                        Text(
                            "No matching rooms",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                } else {
                    val destinationId = requireNotNull(selectedRoomId)
                    val destination = roomOptions.firstOrNull {
                        it.first.getRoomIdAsInt() == destinationId
                    }
                    val openings = editorState.effectiveDoorwayOpenings(
                        destinationId, requiredDirection, romParser,
                    )
                    val destinationDoors = editorState.effectiveDoorsForRoom(destinationId, romParser)
                    DropdownMenuItem(
                        text = { Text("‹ Back to rooms", fontSize = 10.sp) },
                        onClick = { selectedRoomId = null },
                        modifier = Modifier.height(30.dp),
                    )
                    Text(
                        destination?.first?.name ?: "Destination room",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                    Text(
                        "Source travels ${listOf("right", "left", "down", "up")[requiredDirection and 0x03]}; " +
                            "choose a ${incomingDoorEdgeName(requiredDirection)}-edge doorway.",
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                    for (opening in openings) {
                        val linkedDoor = destinationDoors.getOrNull(opening.connectionIndex)
                        val connectionTarget = linkedDoor?.let { linked ->
                            if (linked.destRoomPtr == sourceRoomId) {
                                "this room"
                            } else {
                                roomOptions.firstOrNull {
                                    it.first.getRoomIdAsInt() == linked.destRoomPtr
                                }?.first?.name ?: "another room"
                            }
                        }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        "${incomingDoorEdgeName(opening.direction).replaceFirstChar { it.uppercase() }} edge" +
                                            " · screen ${oneBasedRoomCoordinate(opening.screenX, opening.screenY)}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "Door tile ${oneBasedRoomCoordinate(opening.blockX, opening.blockY)}" +
                                            " · ${opening.tileCount} tile${if (opening.tileCount == 1) "" else "s"}" +
                                            if (linkedDoor != null) {
                                                " · connection ${opening.connectionIndex + 1} → $connectionTarget"
                                            } else {
                                                " · unlinked opening"
                                            },
                                        fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                selectedRoomId = null
                                onDoorwaySelected(opening)
                            },
                            modifier = Modifier.height(42.dp),
                        )
                    }
                    if (openings.isEmpty()) {
                        Text(
                            "No compatible type-9 doorway tiles were found on this room's " +
                                "${incomingDoorEdgeName(requiredDirection)} edge.",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun parseFlexibleInt(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return when {
        trimmed.startsWith("$") -> trimmed.drop(1).toIntOrNull(16)
        trimmed.startsWith("0x", ignoreCase = true) -> trimmed.drop(2).toIntOrNull(16)
        else -> trimmed.toIntOrNull()
    }
}

@Composable
private fun DoorConnectionHealthCard(diagnostic: DoorConnectionDiagnostic) {
    val accent = if (diagnostic.hasError) MaterialTheme.colorScheme.error else Color(0xFFE0A12B)
    Surface(
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(5.dp)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
            Text(
                text = if (diagnostic.hasError) "⚠ Connection error" else "⚠ Connection needs attention",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            for (issue in diagnostic.issues) {
                Text(
                    "• ${issue.message}",
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun DoorCapLabel(cap: RomParser.Companion.DoorCapDef) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val dotColor = when (cap.color) {
            "Blue" -> Color(0xFF3880D0); "Red" -> Color(0xFFD05050)
            "Green" -> Color(0xFF40C048); "Yellow" -> Color(0xFFD8C830)
            else -> Color(0xFF808088)
        }
        Box(Modifier.size(10.dp).background(dotColor, RoundedCornerShape(2.dp)))
        Text("${cap.color} ${cap.direction}", fontSize = 11.sp)
    }
}
