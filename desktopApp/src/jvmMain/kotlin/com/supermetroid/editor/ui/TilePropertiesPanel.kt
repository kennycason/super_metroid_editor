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
import com.supermetroid.editor.rom.RomParser

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TilePropertiesPanel(
    blockX: Int,
    blockY: Int,
    metatile: Int,
    initialBlockType: Int,
    initialBts: Int,
    editorState: EditorState,
    romParser: RomParser,
    rooms: List<RoomInfo>,
    roomHeader: com.supermetroid.editor.data.Room?,
    roomId: Int,
    emulatorConnected: Boolean,
    onMoveSamusHere: ((Int, Int) -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var propsBlockType by remember(blockX, blockY) { mutableStateOf(initialBlockType) }
    var propsBts by remember(blockX, blockY) { mutableStateOf(initialBts) }
    val editableBlockTypes = listOf(
        0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Treadmill",
        0x4 to "Shootable Air", 0x5 to "H-Extend", 0x6 to "Unused",
        0x7 to "Air (Bomb)", 0x8 to "Solid", 0x9 to "Door", 0xA to "Spike",
        0xB to "Crumble", 0xC to "Shot Block", 0xD to "V-Extend",
        0xE to "Grapple", 0xF to "Bomb Block"
    )
    val propsTypeName = blockTypeName(propsBlockType)
    val btsOptions = btsOptionsForBlockType(propsBlockType)

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
                    "($blockX, $blockY) #$metatile 0x${propsBlockType.toString(16).uppercase()} $propsTypeName",
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
                            "0x${propsBlockType.toString(16).uppercase()} $propsTypeName",
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text("▾", fontSize = 10.sp)
                    }
                }
                DropdownMenu(expanded = btExpanded, onDismissRequest = { btExpanded = false }) {
                    for ((typeVal, typeName) in editableBlockTypes) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RadioButton(selected = propsBlockType == typeVal, onClick = null, modifier = Modifier.size(16.dp))
                                    Text("0x${typeVal.toString(16).uppercase()} $typeName", fontSize = 11.sp)
                                }
                            },
                            onClick = {
                                btExpanded = false
                                if (typeVal != propsBlockType) {
                                    propsBlockType = typeVal
                                    propsBts = 0
                                    editorState.setTileProperties(blockX, blockY, typeVal, 0)
                                }
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            var hoveredSlopeBts by remember { mutableStateOf<Int?>(null) }
            if (propsBlockType == 0x1) {
                val displayBts = hoveredSlopeBts ?: propsBts
                val displayName = SLOPE_BTS_NAMES[displayBts and 0x40.inv()]
                    ?: SLOPE_BTS_NAMES[displayBts]
                if (displayName != null) {
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

            // ── Sub Type (BTS) ──
            val btsLabel = when (propsBlockType) {
                0x9 -> "Door Connection Index"
                0x1 -> "Slope Shape"
                else -> "Sub Type (BTS)"
            }
            Text(btsLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))

            if (propsBlockType == 0x1) {
                SlopeGridPicker(
                    selectedBts = propsBts,
                    onSelect = { btsVal ->
                        if (btsVal != propsBts) {
                            propsBts = btsVal
                            editorState.setTileProperties(blockX, blockY, propsBlockType, btsVal)
                        }
                    },
                    onHoverBts = { hoveredSlopeBts = it }
                )
                Spacer(modifier = Modifier.height(4.dp))
            } else if (btsOptions.isNotEmpty()) {
                var btsDropExpanded by remember { mutableStateOf(false) }
                val btsName = btsOptions.firstOrNull { it.first == propsBts }?.second
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
                                        RadioButton(selected = propsBts == btsVal, onClick = null, modifier = Modifier.size(16.dp))
                                        Text("0x${btsVal.toString(16).uppercase().padStart(2, '0')} $btsOptName", fontSize = 11.sp)
                                    }
                                },
                                onClick = {
                                    btsDropExpanded = false
                                    if (btsVal != propsBts) {
                                        propsBts = btsVal
                                        editorState.setTileProperties(blockX, blockY, propsBlockType, btsVal)
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Raw BTS hex input (BasicTextField so typed text is visible, same fix as room search)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(if (btsOptions.isNotEmpty()) "Raw:" else "BTS:", fontSize = 10.sp)
                var rawText by remember(blockX, blockY, propsBts) {
                    mutableStateOf(propsBts.toString(16).uppercase().padStart(2, '0'))
                }
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    BasicTextField(
                        value = rawText,
                        onValueChange = { s ->
                            val filtered = s.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(2)
                            rawText = filtered
                            val v = filtered.toIntOrNull(16)
                            if (v != null && v in 0..255 && v != propsBts) {
                                editorState.setTileProperties(blockX, blockY, propsBlockType, v)
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            // ── Door Connection Info (when block type = Door) ──
            if (propsBlockType == 0x9) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(4.dp))
                Text("Door Connection", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(2.dp))

                val allDoors = remember(editorState.editVersion) { editorState.doorEntries.toList() }
                val roomIdToName = remember(rooms) {
                    rooms.associate {
                        it.getRoomIdAsInt() to it.name
                    }
                }
                val currentDoor = allDoors.getOrNull(propsBts)

                if (allDoors.isEmpty()) {
                    Text("No door entries found", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (currentDoor == null) {
                    Text("Door index #$propsBts not found (${allDoors.size} doors available)",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
                } else {
                    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

                    // Helper: 0x00–0xFF dropdown
                    @Composable
                    fun ByteDropdown(label: String, value: Int, onValueChange: (Int) -> Unit) {
                        var expanded by remember { mutableStateOf(false) }
                        val hexStr = "0x${value.toString(16).uppercase().padStart(2, '0')} ($value)"
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
                                        Text(hexStr, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                        Text("▾", fontSize = 9.sp)
                                    }
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                    modifier = Modifier.requiredSizeIn(maxHeight = 300.dp)
                                ) {
                                    for (v in 0..0xFF) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "0x${v.toString(16).uppercase().padStart(2, '0')} ($v)",
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

                    // Destination room dropdown
                    var destDropExpanded by remember { mutableStateOf(false) }
                    var destRoomSearch by remember { mutableStateOf("") }
                    val destHex = "0x${currentDoor.destRoomPtr.toString(16).uppercase()}"
                    val destName = roomIdToName[currentDoor.destRoomPtr]?.let { "$destHex $it" } ?: destHex
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Destination:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                    .clickable { destDropExpanded = true },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(destName, fontSize = 9.sp, modifier = Modifier.weight(1f))
                                    Text("▾", fontSize = 9.sp)
                                }
                            }
                            DropdownMenu(
                                expanded = destDropExpanded,
                                onDismissRequest = { destDropExpanded = false; destRoomSearch = "" },
                                modifier = Modifier.width(300.dp).requiredSizeIn(maxHeight = 400.dp)
                            ) {
                AppTextInput(
                    value = destRoomSearch,
                    onValueChange = { destRoomSearch = it },
                    placeholder = "Search…",
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        .fillMaxWidth()
                )
                                val filteredRooms = if (destRoomSearch.isBlank()) rooms
                                    else rooms.filter { it.name.contains(destRoomSearch, ignoreCase = true) ||
                                        it.id.contains(destRoomSearch, ignoreCase = true) }
                                for (r in filteredRooms) {
                                    val rid = r.getRoomIdAsInt()
                                    DropdownMenuItem(
                                        text = { Text("${r.id} ${r.name}", fontSize = 10.sp,
                                            fontWeight = if (rid == currentDoor.destRoomPtr) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            destDropExpanded = false
                                            destRoomSearch = ""
                                            if (rid != currentDoor.destRoomPtr) {
                                                val derivedCap = romParser.deriveDoorCapPosition(
                                                    rid, currentDoor.direction,
                                                    currentDoor.screenX, currentDoor.screenY
                                                )
                                                val match = romParser.findVanillaDoorMatch(
                                                    rid, currentDoor.direction,
                                                    currentDoor.screenX, currentDoor.screenY
                                                )
                                                // Auto-set cross-area flag when dest is in a different area
                                                val srcArea = romParser.readRoomHeader(roomId)?.let(editorState::applyHeaderChanges)?.area
                                                val destArea = romParser.readRoomHeader(rid)?.let(editorState::applyHeaderChanges)?.area
                                                val crossAreaBit = if (srcArea != null && destArea != null && srcArea != destArea) 0x40 else 0
                                                val newBitflag = mergeDoorBitflagWithMatchedOrientation(
                                                    currentDoor.bitflag,
                                                    match?.orientation,
                                                    crossAreaBit != 0
                                                )
                                                editorState.updateDoor(propsBts,
                                                    currentDoor.copy(
                                                        destRoomPtr = rid,
                                                        bitflag = newBitflag,
                                                        entryCode = match?.entryCode ?: 0,
                                                        doorCapCode = derivedCap ?: match?.doorCapCode ?: currentDoor.doorCapCode
                                                    ))
                                            }
                                        },
                                        modifier = Modifier.height(26.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    val dirNames = listOf("Right", "Left", "Down", "Up")
                    val entranceTemplates = remember(currentDoor.destRoomPtr, rooms, romParser) {
                        doorTemplateChoicesForDestination(romParser, rooms, currentDoor.destRoomPtr)
                    }
                    var entranceDropExpanded by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Entrance:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                    .clickable(enabled = entranceTemplates.isNotEmpty()) {
                                        entranceDropExpanded = true
                                    },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (entranceTemplates.isEmpty()) "No existing entrances found"
                                        else "Copy from existing",
                                        fontSize = 9.sp,
                                        color = if (entranceTemplates.isEmpty()) MaterialTheme.colorScheme.outline
                                            else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (entranceTemplates.isNotEmpty()) Text("▾", fontSize = 9.sp)
                                }
                            }
                            DropdownMenu(
                                expanded = entranceDropExpanded,
                                onDismissRequest = { entranceDropExpanded = false },
                                modifier = Modifier.width(360.dp).requiredSizeIn(maxHeight = 360.dp)
                            ) {
                                for (choice in entranceTemplates) {
                                    val d = choice.door
                                    val dir = dirNames.getOrElse(d.direction and 0x03) { "?" }
                                    val capX = d.doorCapCode and 0xFF
                                    val capY = (d.doorCapCode shr 8) and 0xFF
                                    val entry = "\$${d.entryCode.toString(16).uppercase().padStart(4, '0')}"
                                    val defPtr = "\$${d.doorDefPtr.toString(16).uppercase().padStart(4, '0')}"
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    "${choice.sourceRoomName} door ${choice.doorIndex} ($defPtr)",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    "$dir  screen=(${d.screenX},${d.screenY})  cap=($capX,$capY)  entry=$entry",
                                                    fontSize = 8.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            entranceDropExpanded = false
                                            val srcArea = romParser.readRoomHeader(roomId)?.let(editorState::applyHeaderChanges)?.area
                                            val destArea = romParser.readRoomHeader(currentDoor.destRoomPtr)?.let(editorState::applyHeaderChanges)?.area
                                            val crossArea = srcArea != null && destArea != null && srcArea != destArea
                                            editorState.updateDoor(
                                                propsBts,
                                                doorWithTemplateValues(currentDoor, d, crossArea)
                                            )
                                        },
                                        modifier = Modifier.height(42.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    // Direction dropdown
                    var dirDropExpanded by remember { mutableStateOf(false) }
                    val currentDir = currentDoor.direction and 0x03
                    val isBubble = (currentDoor.direction and 0x04) != 0
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Direction:", fontSize = 9.sp, color = labelColor, modifier = Modifier.width(72.dp))
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
                                            val derivedCap = romParser.deriveDoorCapPosition(
                                                currentDoor.destRoomPtr, newDir,
                                                currentDoor.screenX, currentDoor.screenY
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

                    // Screen X (0x00–0xFF)
                    ByteDropdown("Screen X:", currentDoor.screenX) { v ->
                        val derivedCap = romParser.deriveDoorCapPosition(
                            currentDoor.destRoomPtr, currentDoor.direction, v, currentDoor.screenY)
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

                    // Screen Y (0x00–0xFF)
                    ByteDropdown("Screen Y:", currentDoor.screenY) { v ->
                        val derivedCap = romParser.deriveDoorCapPosition(
                            currentDoor.destRoomPtr, currentDoor.direction, currentDoor.screenX, v)
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
                    val autoCap = remember(currentDoor.destRoomPtr, currentDoor.direction, currentDoor.screenX, currentDoor.screenY) {
                        romParser.deriveDoorCapPosition(
                            currentDoor.destRoomPtr, currentDoor.direction,
                            currentDoor.screenX, currentDoor.screenY
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

            // ── Items / PLMs at this tile ──
            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(4.dp))
            Text("Items / PLMs", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(iName, fontSize = 10.sp)
                        Text(
                            "bit: 0x${plm.param.toString(16).uppercase().padStart(2, '0')}",
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                            val isCustom = (plm.param and 0xFF00) == 0xCC00
                            if (isCustom) {
                                val cmdIdx = plm.param and 0xFF
                                val cmdId = "cmd_$cmdIdx"
                                val cmds = editorState.getScrollCommand(cmdId)
                                if (cmds != null) {
                                    for (cmd in cmds) {
                                        Text(
                                            "  ${RomParser.formatScrollCommand(cmd.screenIndex, cmd.scrollValue, rw)}",
                                            fontSize = 8.sp,
                                            color = Color(0xFFFF8040)
                                        )
                                    }
                                }
                                Text("  (custom)", fontSize = 7.sp, color = MaterialTheme.colorScheme.outline)
                            } else if (rw > 0) {
                                val cmds = RomParser.decodeScrollCommands(
                                    romParser,
                                    plm.param, rw
                                )
                                for ((screenIdx, _, scrollVal) in cmds) {
                                    Text(
                                        "  ${RomParser.formatScrollCommand(screenIdx, scrollVal, rw)}",
                                        fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
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

            // Add Scroll Trigger button + dropdown
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
                        Text("Restore original trigger here:", fontSize = 9.sp,
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
                                        Text("original ptr \$${trigger.param.toString(16).uppercase().padStart(4, '0')}",
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
                        Text("Reuse command:", fontSize = 9.sp,
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
                                        Text("ptr \$${cmdPtr.toString(16).uppercase().padStart(4, '0')}",
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
                        text = { Text("+ New Custom Trigger...", fontSize = 10.sp, color = Color.White) },
                        onClick = {
                            addScrollExpanded = false
                            showScrollEditor = true
                        },
                        modifier = Modifier.height(28.dp)
                    )
                    Divider()
                    Text("Treadmill extensions:", fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Color(0xFFFF8040),
                        fontWeight = FontWeight.Bold)
                    Text("Widens an adjacent trigger's hitbox",
                        fontSize = 7.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 0.dp),
                        color = Color(0xFF99AABB))
                    for ((plmId, label) in listOf(
                        0xB63B to "→ Extend Right",
                        0xB63F to "← Extend Left",
                        0xB647 to "↑ Extend Up",
                        0xB643 to "↓ Extend Down"
                    )) {
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 10.sp, color = Color.White) },
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
                        Text("+ New Custom Trigger...", fontSize = 10.sp,
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
                        Text("Move Samus Here ($blockX, $blockY)", fontSize = 10.sp,
                            color = Color.White)
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
