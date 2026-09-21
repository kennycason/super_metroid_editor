package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.ProjectRoomStateCondition
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.rom.projectRoomStateCondition
import com.supermetroid.editor.rom.isSmEditGeneratedPredicate

private enum class RoomInfoHelpTopic {
    STATES,
    STATE_DATA,
    COMPARISON,
    SCROLLS,
}

private val CRE_BITFLAG_NAMES = mapOf(
    0x00 to "Default",
    0x01 to "Black out during transition",
    0x02 to "Reload CRE tiles",
    0x05 to "Disable CRE tiles",
)


// Names from SMILE source (FX1_1.frx Layer3Type dropdown)
private val FX_TYPE_OPTIONS = listOf(
    0x00 to "None",
    0x02 to "Lava",
    0x04 to "Acid",
    0x06 to "Water",
    0x08 to "Spores",
    0x0A to "Rain",
    0x0C to "Fog",
    0x0E to "Haze",
    0x10 to "Dense Fog",
    0x16 to "Firefleas",
    0x18 to "Lightning",
    0x1A to "Smoke",
    0x1C to "Heat Shimmer",
    0x20 to "Sky Scrolling",
    0x24 to "Fireflea FX",
    0x26 to "4 Statues",
    0x28 to "Ceres Elevator",
    0x2A to "Ceres Ridley",
    0x2C to "Haze",
)

private val SCROLL_COLORS = mapOf(
    0x00 to Color(0xFFCC3030),
    0x01 to Color(0xFF3060CC),
    0x02 to Color(0xFF30AA40),
)
private val SCROLL_LABELS = mapOf(0x00 to "R", 0x01 to "B", 0x02 to "G")

internal data class RoomStateUiItem(
    val id: String,
    val baseSourceStateIndex: Int?,
    val condition: ProjectRoomStateCondition,
    val edits: RoomStateEdits?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomPropertiesPanel(
    room: Room,
    romParser: RomParser,
    editorState: EditorState,
    modifier: Modifier = Modifier,
    onNavigateToMap: (() -> Unit)? = null,
) {
    val stateInspection = remember(room.roomId, romParser) { romParser.inspectRoomStates(room.roomId) }
    // Track project-backed changes so authored state count/order is reflected immediately.
    @Suppress("UNUSED_VARIABLE") val headerEditVersion = editorState.editVersion
    val roomEdits = editorState.project.rooms[editorState.project.roomKey(room.roomId)]
    val roomInfos = remember { RoomRepository().getAllRooms() }
    val roomNames = remember(roomInfos) { roomInfos.associate { it.getRoomIdAsInt() to it.name } }
    val romItemPickupOptions = remember(romParser) {
        romParser.scanAllItemPlms(roomInfos.map { it.getRoomIdAsInt() }).map { item ->
            val itemName = RomParser.itemNameForPlm(item.plm.id) ?: "Item"
            ItemPickupConditionOption(
                id = item.plm.param,
                label = "$itemName — ${roomNames[item.roomId] ?: "Room ${item.roomId.toString(16)}"} " +
                    "(${item.plm.x}, ${item.plm.y}) — ID \$${item.plm.param.toString(16).uppercase().padStart(3, '0')}",
            )
        }
    }
    val projectItemPickupOptions = remember(editorState.editVersion) {
        editorState.project.rooms.flatMap { (roomKey, edits) ->
            val changes = edits.plmChanges + edits.states.flatMap { it.plmChanges }
            changes.asSequence()
                .filter { it.action == "add" && RomParser.isItemPlm(it.plmId) }
                .map { change ->
                    val roomId = roomKey.toIntOrNull(16)
                    val itemName = RomParser.itemNameForPlm(change.plmId) ?: "Item"
                    ItemPickupConditionOption(
                        id = change.param,
                        label = "$itemName — ${roomId?.let(roomNames::get) ?: "Room $roomKey"} " +
                            "(${change.x}, ${change.y}) — ID \$${change.param.toString(16).uppercase().padStart(3, '0')}",
                    )
                }.toList()
        }
    }
    val itemPickupOptions = remember(romItemPickupOptions, projectItemPickupOptions) {
        (romItemPickupOptions + projectItemPickupOptions)
            .filter { it.id in 0..0x1FF }
            .distinctBy { it.id }
            // Missile tanks are the most common exact-pickup condition; keep
            // them together at the top while still exposing every item ID.
            .sortedWith(compareBy<ItemPickupConditionOption>({ !it.label.startsWith("Missile ") }, { it.id }))
    }
    val states = if (roomEdits?.states?.isNotEmpty() == true) {
        roomEdits.states.map { state ->
            RoomStateUiItem(
                id = state.id,
                baseSourceStateIndex = state.baseSourceStateIndex(),
                condition = state.condition,
                edits = state,
            )
        }
    } else {
        stateInspection.states.mapIndexed { index, state ->
            RoomStateUiItem(
                id = "state-${index + 1}",
                baseSourceStateIndex = index,
                condition = projectRoomStateCondition(
                    ProjectRoomStateConditionKind.valueOf(state.condition.kind.name),
                    state.condition.argument,
                    state.condition.negated,
                ),
                edits = null,
            )
        }
    }
    var selectedStateId by remember(room.roomId) {
        mutableStateOf(
            editorState.currentStateId?.takeIf { id -> states.any { it.id == id } }
                ?: states.lastOrNull()?.id
        )
    }
    val selectedStateItem = states.firstOrNull { it.id == selectedStateId } ?: states.lastOrNull()
    val selectedStateIdx = states.indexOf(selectedStateItem).coerceAtLeast(0)
    var showRomAddresses by remember(room.roomId) { mutableStateOf(false) }
    var helpTopic by remember(room.roomId) { mutableStateOf<RoomInfoHelpTopic?>(null) }
    var showAddStateDialog by remember(room.roomId) { mutableStateOf(false) }
    var duplicateSelectedCondition by remember(room.roomId) { mutableStateOf(false) }
    var showDeleteStateDialog by remember(room.roomId) { mutableStateOf(false) }
    var showConditionBuilder by remember(room.roomId) { mutableStateOf(false) }
    var showStateSimulator by remember(room.roomId) { mutableStateOf(false) }
    val selectedSourceStateIndex = selectedStateItem?.baseSourceStateIndex ?: -1
    val currentState = stateInspection.states.getOrNull(selectedSourceStateIndex)
    val allStateData = states.map { state ->
        val data = state.baseSourceStateIndex
            ?.let(stateInspection.states::getOrNull)
            ?.stateDataPcOffset
            ?.let(romParser::readStateData)
            ?.toMutableMap()
            ?: mutableMapOf()
        fun apply(change: StateDataChange?) {
            change?.tileset?.let { data["tileset"] = it }
            change?.musicData?.let { data["musicData"] = it }
            change?.musicTrack?.let { data["musicTrack"] = it }
            change?.bgScrolling?.let { data["bgScrolling"] = it }
        }
        apply(roomEdits?.stateDataChange)
        apply(state.edits?.stateDataChange)
        data
    }
    val stateData = allStateData.getOrNull(selectedStateIdx).orEmpty()
    val fxPtr = stateData["fxPtr"] ?: room.fxPtr
    val fxEntries = remember(fxPtr) { romParser.parseFxEntries(fxPtr) }
    val hasFxTable = fxEntries.isNotEmpty()
    val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 } ?: RomParser.FxEntry(
        doorSelect = 0,
        liquidSurfaceStart = 0xFFFF,
        liquidSurfaceNew = 0xFFFF,
        liquidSpeed = 0,
        liquidDelay = 0,
        fxType = 0,
        fxBitA = 2,
        fxBitB = 2,
        fxBitC = 0,
        paletteFxBitflags = 0,
        tileAnimBitflags = 0,
        paletteBlend = 0,
    )

    // Use working scrolls from EditorState (includes edits)
    val scrollVer = editorState.scrollVersion
    val scrollData = remember(scrollVer, room.roomId) { editorState.workingScrolls.copyOf() }

    val selectedStateEdits = selectedStateItem?.edits
    val savedDoorFxChanges = selectedStateEdits?.doorFxChanges.orEmpty()
    val editableCondition = selectedStateItem?.condition
    val commonFx = roomEdits?.fxChange
    val stateFx = selectedStateEdits?.fxChange
    val savedFx = if (stateFx != null || commonFx != null) {
        FxChange(
            fxType = stateFx?.fxType ?: commonFx?.fxType,
            liquidSurfaceStart = stateFx?.liquidSurfaceStart ?: commonFx?.liquidSurfaceStart,
            liquidSurfaceNew = stateFx?.liquidSurfaceNew ?: commonFx?.liquidSurfaceNew,
            liquidSpeed = stateFx?.liquidSpeed ?: commonFx?.liquidSpeed,
            liquidDelay = stateFx?.liquidDelay ?: commonFx?.liquidDelay,
            fxBitA = stateFx?.fxBitA ?: commonFx?.fxBitA,
            fxBitB = stateFx?.fxBitB ?: commonFx?.fxBitB,
            fxBitC = stateFx?.fxBitC ?: commonFx?.fxBitC,
            paletteFxBitflags = stateFx?.paletteFxBitflags ?: commonFx?.paletteFxBitflags,
            tileAnimBitflags = stateFx?.tileAnimBitflags ?: commonFx?.tileAnimBitflags,
            paletteBlend = stateFx?.paletteBlend ?: commonFx?.paletteBlend,
        )
    } else null
    val commonState = roomEdits?.stateDataChange
    val stateState = selectedStateEdits?.stateDataChange
    val savedState = if (stateState != null || commonState != null) {
        StateDataChange(
            tileset = stateState?.tileset ?: commonState?.tileset,
            musicData = stateState?.musicData ?: commonState?.musicData,
            musicTrack = stateState?.musicTrack ?: commonState?.musicTrack,
            bgScrolling = stateState?.bgScrolling ?: commonState?.bgScrolling,
        )
    } else null

    // FX edit state — keyed by stable state ID so it survives reordering.
    var editFxType by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.fxType ?: defaultFx.fxType) }
    var editLiquidStart by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.liquidSurfaceStart ?: defaultFx.liquidSurfaceStart) }
    var editLiquidNew by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.liquidSurfaceNew ?: defaultFx.liquidSurfaceNew) }
    var editLiquidSpeed by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.liquidSpeed ?: defaultFx.liquidSpeed) }
    var editLiquidDelay by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.liquidDelay ?: defaultFx.liquidDelay) }
    var editFxBitA by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.fxBitA ?: defaultFx.fxBitA) }
    var editFxBitB by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.fxBitB ?: defaultFx.fxBitB) }
    var editFxBitC by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.fxBitC ?: defaultFx.fxBitC) }
    var editPaletteFxBits by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.paletteFxBitflags ?: defaultFx.paletteFxBitflags) }
    var editTileAnimBits by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.tileAnimBitflags ?: defaultFx.tileAnimBitflags) }
    var editPaletteBlend by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedFx?.paletteBlend ?: defaultFx.paletteBlend) }
    var editingDoorFx by remember(room.roomId, selectedStateItem?.id) { mutableStateOf<Int?>(null) }

    // State data edit state — keyed by (roomId, stateIdx) so fields reset on state switch
    val origTileset = stateData["tileset"] ?: room.tileset
    val origMusicData = stateData["musicData"] ?: room.musicData
    val origMusicTrack = stateData["musicTrack"] ?: room.musicTrack
    val origBgScrolling = stateData["bgScrolling"] ?: room.bgScrolling
    var editTileset by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedState?.tileset ?: origTileset) }
    var editMusicData by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedState?.musicData ?: origMusicData) }
    var editMusicTrack by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedState?.musicTrack ?: origMusicTrack) }
    var editBgScrolling by remember(room.roomId, selectedStateItem?.id) { mutableStateOf(savedState?.bgScrolling ?: origBgScrolling) }

    fun syncFxToState() {
        val change = FxChange(
            fxType = editFxType.takeIf { it != (commonFx?.fxType ?: defaultFx.fxType) },
            liquidSurfaceStart = editLiquidStart.takeIf { it != (commonFx?.liquidSurfaceStart ?: defaultFx.liquidSurfaceStart) },
            liquidSurfaceNew = editLiquidNew.takeIf { it != (commonFx?.liquidSurfaceNew ?: defaultFx.liquidSurfaceNew) },
            liquidSpeed = editLiquidSpeed.takeIf { it != (commonFx?.liquidSpeed ?: defaultFx.liquidSpeed) },
            liquidDelay = editLiquidDelay.takeIf { it != (commonFx?.liquidDelay ?: defaultFx.liquidDelay) },
            fxBitA = editFxBitA.takeIf { it != (commonFx?.fxBitA ?: defaultFx.fxBitA) },
            fxBitB = editFxBitB.takeIf { it != (commonFx?.fxBitB ?: defaultFx.fxBitB) },
            fxBitC = editFxBitC.takeIf { it != (commonFx?.fxBitC ?: defaultFx.fxBitC) },
            paletteFxBitflags = editPaletteFxBits.takeIf { it != (commonFx?.paletteFxBitflags ?: defaultFx.paletteFxBitflags) },
            tileAnimBitflags = editTileAnimBits.takeIf { it != (commonFx?.tileAnimBitflags ?: defaultFx.tileAnimBitflags) },
            paletteBlend = editPaletteBlend.takeIf { it != (commonFx?.paletteBlend ?: defaultFx.paletteBlend) },
        )
        val stateId = selectedStateItem?.id ?: return
        editorState.setRoomStateFxChange(
            stateId,
            change.takeIf { it != FxChange() },
            romParser,
        )
    }

    fun syncStateDataToState() {
        val change = StateDataChange(
            tileset = editTileset.takeIf { it != (commonState?.tileset ?: origTileset) },
            musicData = editMusicData.takeIf { it != (commonState?.musicData ?: origMusicData) },
            musicTrack = editMusicTrack.takeIf { it != (commonState?.musicTrack ?: origMusicTrack) },
            bgScrolling = editBgScrolling.takeIf { it != (commonState?.bgScrolling ?: origBgScrolling) },
        )
        val stateId = selectedStateItem?.id ?: return
        editorState.setRoomStateDataChange(
            stateId,
            change.takeIf { it != StateDataChange() },
            romParser,
        )
    }

    // Room header edit state — all 11 bytes
    val savedHeader = roomEdits?.roomHeaderChange
    val effectiveArea = savedHeader?.area ?: room.area
    val displayMapX = savedHeader?.mapX ?: room.mapX
    val displayMapY = savedHeader?.mapY ?: room.mapY
    var editUpScroller by remember(room.roomId) { mutableStateOf(savedHeader?.upScroller ?: room.upScroller) }
    var editDownScroller by remember(room.roomId) { mutableStateOf(savedHeader?.downScroller ?: room.downScroller) }
    var editCreBitflag by remember(room.roomId) { mutableStateOf(savedHeader?.creBitflag ?: room.creBitflag) }

    fun syncHeaderToState() {
        val change = (savedHeader ?: RoomHeaderChange()).copy(
            upScroller = editUpScroller.takeIf { it != room.upScroller },
            downScroller = editDownScroller.takeIf { it != room.downScroller },
            creBitflag = editCreBitflag.takeIf { it != room.creBitflag },
        )
        if (change == RoomHeaderChange()) {
            editorState.project.getOrCreateRoom(room.roomId).roomHeaderChange = null
        } else {
            editorState.setRoomHeaderChange(change)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Room Header (all 11 bytes editable) ──
        SectionHeader("Room Header")
        PropertyRow("Room ID", "0x${room.roomId.toString(16).uppercase().padStart(4, '0')}")
        PropertyRow("Room Index", "0x${room.index.toString(16).uppercase().padStart(2, '0')}")
        AreaDropdown(effectiveArea) { targetArea ->
            editorState.reassignRoomArea(room.roomId, targetArea, romParser)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Map Position", fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
            Text("($displayMapX, $displayMapY)", fontSize = ROOM_INFO_BODY_FONT_SIZE, modifier = Modifier.weight(1f))
            if (onNavigateToMap != null && effectiveArea in ROOM_AREA_NAMES.indices) {
                Text(
                    "Edit on Map",
                    fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onNavigateToMap() }.padding(horizontal = 4.dp),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )
            }
        }
        RoomResizeRow(room, editorState)
        EditableHexRow("Up Scroller", editUpScroller, 1,
            suffix = when (editUpScroller) { 0x70 -> " default"; 0x90 -> " grapple block"; 0x99 -> " fast ascent"; else -> "" }
        ) { editUpScroller = it; syncHeaderToState() }
        EditableHexRow("Down Scroller", editDownScroller, 1,
            suffix = when (editDownScroller) { 0xA0 -> " default"; 0xC0 -> " speed boost"; else -> "" }
        ) { editDownScroller = it; syncHeaderToState() }
        EditableHexRow("CRE Bitflag", editCreBitflag, 1,
            suffix = " ${CRE_BITFLAG_NAMES[editCreBitflag] ?: ""}"
        ) { editCreBitflag = it; syncHeaderToState() }
        PropertyRow("Door Out Ptr", "0x${room.doorOut.toString(16).uppercase().padStart(4, '0')} (\$8F)")

        Spacer(modifier = Modifier.height(4.dp))

        // ── Room States ──
        SectionHeader(
            title = "Room States (${states.size})",
            onHelp = { helpTopic = RoomInfoHelpTopic.STATES },
            trailingContent = {
                TextButton(
                    onClick = { showRomAddresses = !showRomAddresses },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(22.dp),
                ) {
                    Text(if (showRomAddresses) "Hide addresses" else "Show addresses", fontSize = ROOM_INFO_CAPTION_FONT_SIZE)
                }
            },
        )
        if (helpTopic == RoomInfoHelpTopic.STATES) {
            RoomStatesHelpDialog(onDismiss = { helpTopic = null })
        }
        if (states.isEmpty()) {
            Text("No readable states found", fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.error)
        } else {
            for ((idx, state) in states.withIndex()) {
                val branchLabel = when {
                    state.condition.kind == ProjectRoomStateConditionKind.DEFAULT -> "ELSE"
                    idx == 0 -> "IF"
                    else -> "ELSE IF"
                }
                val sourceState = state.baseSourceStateIndex?.let(stateInspection.states::getOrNull)
                val stateAddress = sourceState?.stateDataPointer?.let {
                    "\$8F:${it.toString(16).uppercase().padStart(4, '0')}"
                } ?: sourceState?.stateDataPcOffset?.let {
                    val snes = romParser.pcToSnes(it)
                    "inline \$${(snes ushr 16).toString(16).uppercase()}:" +
                        (snes and 0xFFFF).toString(16).uppercase().padStart(4, '0')
                } ?: "new state"
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = state.baseSourceStateIndex != null) {
                        selectedStateId = state.id
                        editorState.switchRoomState(state.id, romParser)
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (selectedStateIdx == idx) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                ) {
                    Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
                        Text(
                            "$branchLabel ${projectConditionLabel(state.condition, room.area)}",
                            fontSize = ROOM_INFO_BODY_FONT_SIZE,
                            fontWeight = if (selectedStateIdx == idx) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (state.baseSourceStateIndex == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        if (showRomAddresses) {
                            val engineCheck = if (state.condition.kind.isSmEditGeneratedPredicate()) {
                                "generated typed check"
                            } else {
                                "engine check \$${state.condition.routineCode.toString(16).uppercase().padStart(4, '0')}"
                            }
                            Text(
                                "$engineCheck  →  state record $stateAddress",
                                fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            val selectedIsDefault = selectedStateItem?.condition?.kind == ProjectRoomStateConditionKind.DEFAULT
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        duplicateSelectedCondition = false
                        showAddStateDialog = true
                    },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) { Text("+ Add", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
                TextButton(
                    onClick = {
                        duplicateSelectedCondition = true
                        showAddStateDialog = true
                    },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) { Text("Duplicate", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
                TextButton(
                    enabled = !selectedIsDefault && selectedStateIdx > 0,
                    onClick = {
                        selectedStateItem?.id?.let { editorState.moveRoomState(it, -1, romParser) }
                    },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) { Text("↑", fontSize = ROOM_INFO_BODY_FONT_SIZE) }
                TextButton(
                    enabled = !selectedIsDefault && selectedStateIdx in 0 until states.lastIndex - 1,
                    onClick = {
                        selectedStateItem?.id?.let { editorState.moveRoomState(it, 1, romParser) }
                    },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) { Text("↓", fontSize = ROOM_INFO_BODY_FONT_SIZE) }
                TextButton(
                    enabled = !selectedIsDefault,
                    onClick = { showDeleteStateDialog = true },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) { Text("Delete", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
                TextButton(
                    onClick = { showStateSimulator = true },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                ) { Text("Simulate", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
            }
        }

        if (showStateSimulator) {
            RoomStateSimulatorDialog(
                states = states.map { it.id to it.condition },
                area = room.area,
                itemPickupOptions = itemPickupOptions,
                onDismiss = { showStateSimulator = false },
            )
        }

        if (showAddStateDialog && selectedStateItem != null) {
            val initialCondition = if (duplicateSelectedCondition &&
                selectedStateItem.condition.kind != ProjectRoomStateConditionKind.DEFAULT
            ) {
                selectedStateItem.condition
            } else {
                projectRoomStateCondition(ProjectRoomStateConditionKind.EVENT_SET, 0)
            }
            AddRoomStateDialog(
                title = if (duplicateSelectedCondition) "Duplicate state" else "Add condition",
                templateName = projectConditionLabel(selectedStateItem.condition, room.area),
                initialCondition = initialCondition,
                area = room.area,
                incomingDoorPointers = remember(room.roomId, romParser) {
                    romParser.findDoorsLeadingTo(room.roomId).map { it.doorDefPtr }.filter { it != 0 }.distinct()
                },
                itemPickupOptions = itemPickupOptions,
                existingConditions = states.map { it.condition },
                onDismiss = { showAddStateDialog = false },
                onAdd = { condition ->
                    val newId = editorState.addRoomState(selectedStateItem.id, condition, romParser)
                    selectedStateId = newId
                    editorState.switchRoomState(newId, romParser)
                    showAddStateDialog = false
                },
            )
        }
        if (showDeleteStateDialog && selectedStateItem != null) {
            DeleteRoomStateDialog(
                stateName = projectConditionLabel(selectedStateItem.condition, room.area),
                onDismiss = { showDeleteStateDialog = false },
                onDelete = {
                    val nextId = editorState.deleteRoomState(selectedStateItem.id, romParser)
                    selectedStateId = nextId
                    editorState.switchRoomState(nextId, romParser)
                    showDeleteStateDialog = false
                },
            )
        }
        for (issue in stateInspection.issues) {
            Text(
                "⚠ ${issue.message} (PC \$${issue.pcOffset.toString(16).uppercase()})",
                fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        // ── State Data ──
        SectionHeader(
            title = "State Data",
            onHelp = { helpTopic = RoomInfoHelpTopic.STATE_DATA },
        )
        val levelDataPtr = stateData["levelDataPtr"] ?: room.levelDataPtr
        val mainAsmPtr = stateData["mainAsmPtr"] ?: room.mainAsmPtr
        val setupAsmPtr = stateData["setupAsmPtr"] ?: room.setupAsmPtr
        val bgDataPtr = stateData["bgDataPtr"] ?: room.bgDataPtr
        val enemySetPtr = stateData["enemySetPtr"] ?: room.enemySetPtr
        val enemyGfxPtr = stateData["enemyGfxPtr"] ?: room.enemyGfxPtr
        val plmSetPtr = stateData["plmSetPtr"] ?: room.plmSetPtr
        val xraySpecialCasingPtr = stateData["xraySpecialCasingPtr"] ?: room.xraySpecialCasingPtr

        val stateNames = states.map { projectConditionLabel(it.condition, room.area) }
        val defaultStateData = allStateData.lastOrNull().orEmpty()
        val stateDifferences = changedRoomStateSections(stateData, defaultStateData)

        fun sharingDescription(field: String, value: Int): String {
            if (states.size <= 1) return "Only state"
            val hasIndependentEdit = when (field) {
                "levelDataPtr" -> selectedStateEdits?.operations?.any { it.edits.isNotEmpty() } == true
                "fxPtr" -> selectedStateEdits?.fxChange != null ||
                    selectedStateEdits?.doorFxChanges?.isNotEmpty() == true
                "plmSetPtr" -> selectedStateEdits?.plmChanges?.isNotEmpty() == true
                "enemySetPtr", "enemyGfxPtr" -> selectedStateEdits?.enemyChanges?.isNotEmpty() == true
                "roomScrollsPtr" -> selectedStateEdits?.scrollChanges?.isNotEmpty() == true
                else -> false
            }
            if (hasIndependentEdit) return "Independent edit for this state"
            val linked = matchingStateIndices(allStateData, field, value)
            return describeStateResourceSharing(stateNames, linked)
        }

        SelectedStateSummary(
            stateName = stateNames.getOrNull(selectedStateIdx) ?: "Unknown",
            isDefault = selectedStateItem?.condition?.kind == ProjectRoomStateConditionKind.DEFAULT,
            differenceCount = stateDifferences.size,
            onShowDifferences = { helpTopic = RoomInfoHelpTopic.COMPARISON },
        )

        if (editableCondition != null && editableCondition.kind != ProjectRoomStateConditionKind.DEFAULT) {
            StateConditionEditor(
                condition = editableCondition,
                area = room.area,
                incomingDoorPointers = remember(room.roomId, romParser) {
                    romParser.findDoorsLeadingTo(room.roomId).map { it.doorDefPtr }.filter { it != 0 }.distinct()
                },
                itemPickupOptions = itemPickupOptions,
                onChange = { updated ->
                    editorState.setRoomStateCondition(selectedStateItem.id, updated, romParser)
                },
                onOpenBuilder = { showConditionBuilder = true },
            )
        }
        if (showConditionBuilder && editableCondition != null) {
            ConditionBuilderDialog(
                initialCondition = editableCondition,
                area = room.area,
                incomingDoorPointers = remember(room.roomId, romParser) {
                    romParser.findDoorsLeadingTo(room.roomId).map { it.doorDefPtr }.filter { it != 0 }.distinct()
                },
                itemPickupOptions = itemPickupOptions,
                onDismiss = { showConditionBuilder = false },
                onApply = { updated ->
                    editorState.setRoomStateCondition(selectedStateItem.id, updated, romParser)
                    showConditionBuilder = false
                },
            )
        }

        EditableIntRow("Tileset", editTileset, 0, 29) { editTileset = it; syncStateDataToState() }
        MusicDropdown(
            musicData = editMusicData,
            musicTrack = editMusicTrack,
            onMusicChange = { data, track ->
                editMusicData = data
                editMusicTrack = track
                syncStateDataToState()
            }
        )
        BgScrollDropdown(editBgScrolling) { editBgScrolling = it; syncStateDataToState() }

        val selectedIsActive = editorState.currentStateId?.let { it == selectedStateItem?.id }
            ?: (editorState.currentStateIndex == selectedSourceStateIndex)
        val enemyCount = if (selectedIsActive) editorState.workingEnemies.size else {
            remember(enemySetPtr) { romParser.parseEnemyPopulation(enemySetPtr).size }
        }
        val plmCount = if (selectedIsActive) editorState.workingPlms.size else {
            remember(plmSetPtr) { romParser.parsePlmSet(plmSetPtr).size }
        }
        val gfxEntries = remember(enemyGfxPtr) { romParser.parseEnemyGfxSet(enemyGfxPtr) }
        val gfxCount = gfxEntries.size
        if (helpTopic == RoomInfoHelpTopic.STATE_DATA) {
            StateDataHelpDialog(
                selectedStateName = stateNames.getOrNull(selectedStateIdx) ?: "Unknown",
                resourceStates = listOf(
                    "Layout" to describeStateResourceMembers(
                        stateNames,
                        matchingStateIndices(allStateData, "levelDataPtr", levelDataPtr),
                    ),
                    "Background" to describeStateResourceMembers(
                        stateNames,
                        matchingStateIndices(allStateData, "bgDataPtr", bgDataPtr),
                    ),
                    "Effects" to describeStateResourceMembers(
                        stateNames,
                        matchingStateIndices(allStateData, "fxPtr", fxPtr),
                    ),
                    "Placed Objects" to describeStateResourceMembers(
                        stateNames,
                        matchingStateIndices(allStateData, "plmSetPtr", plmSetPtr),
                    ),
                    "Enemy Actors" to describeStateResourceMembers(
                        stateNames,
                        matchingStateIndices(allStateData, "enemySetPtr", enemySetPtr),
                    ),
                    "Enemy Graphics" to describeStateResourceMembers(
                        stateNames,
                        matchingStateIndices(allStateData, "enemyGfxPtr", enemyGfxPtr),
                    ),
                    "Room Scrolls" to describeStateResourceMembers(
                        stateNames,
                        matchingStateIndices(
                            allStateData,
                            "roomScrollsPtr",
                            stateData["roomScrollsPtr"] ?: room.roomScrollsPtr,
                        ),
                    ),
                ),
                onDismiss = { helpTopic = null },
            )
        }
        val defaultFxPtr = defaultStateData["fxPtr"] ?: fxPtr
        val defaultEnemySetPtr = defaultStateData["enemySetPtr"] ?: enemySetPtr
        val defaultEnemyGfxPtr = defaultStateData["enemyGfxPtr"] ?: enemyGfxPtr
        val defaultPlmSetPtr = defaultStateData["plmSetPtr"] ?: plmSetPtr
        val defaultEnemyCount = remember(defaultEnemySetPtr) {
            romParser.parseEnemyPopulation(defaultEnemySetPtr).size
        }
        val defaultGfxCount = remember(defaultEnemyGfxPtr) {
            romParser.parseEnemyGfxSet(defaultEnemyGfxPtr).size
        }
        val defaultPlmCount = remember(defaultPlmSetPtr) { romParser.parsePlmSet(defaultPlmSetPtr).size }
        val defaultFxName = remember(defaultFxPtr) {
            romParser.parseFxEntries(defaultFxPtr).lastOrNull { it.doorSelect == 0 }?.fxTypeName ?: "None"
        }
        if (helpTopic == RoomInfoHelpTopic.COMPARISON) {
            RoomStateComparisonDialog(
                selectedStateName = stateNames.getOrNull(selectedStateIdx) ?: "Unknown",
                baselineStateName = stateNames.lastOrNull() ?: "Default",
                differences = stateDifferences.map { difference ->
                    RoomStateDifferenceDetail(
                        label = difference,
                        detail = stateDifferenceDetail(
                            difference = difference,
                            selected = stateData,
                            baseline = defaultStateData,
                            selectedFxName = defaultFx.fxTypeName,
                            baselineFxName = defaultFxName,
                            selectedEnemyCount = enemyCount,
                            baselineEnemyCount = defaultEnemyCount,
                            selectedGfxCount = gfxCount,
                            baselineGfxCount = defaultGfxCount,
                            selectedPlmCount = plmCount,
                            baselinePlmCount = defaultPlmCount,
                        ),
                    )
                },
                onDismiss = { helpTopic = null },
            )
        }
        PropertyRow("Layout", sharingDescription("levelDataPtr", levelDataPtr))
        PropertyRow(
            "Background",
            (if (bgDataPtr == 0) "Embedded in layout" else "Separate background") +
                " · ${sharingDescription("bgDataPtr", bgDataPtr)}",
        )
        PropertyRow(
            "Effects",
            "${FX_TYPE_OPTIONS.firstOrNull { it.first == editFxType }?.second ?: "Unknown (${hex8(editFxType)})"} · " +
                sharingDescription("fxPtr", fxPtr),
        )
        PropertyRow("Placed Objects", "$plmCount objects · ${sharingDescription("plmSetPtr", plmSetPtr)}")
        PropertyRow("Enemy Actors", "$enemyCount actors · ${sharingDescription("enemySetPtr", enemySetPtr)}")
        PropertyRow("Enemy Graphics", "$gfxCount slots · ${sharingDescription("enemyGfxPtr", enemyGfxPtr)}")
        val roomLogic = when {
            mainAsmPtr != 0 && setupAsmPtr != 0 -> "Setup + active room logic"
            mainAsmPtr != 0 -> "Active room logic"
            setupAsmPtr != 0 -> "Setup logic"
            else -> "None"
        }
        PropertyRow("Room Logic", roomLogic)
        if (xraySpecialCasingPtr != 0) {
            PropertyRow(
                "Special X-Ray",
                sharingDescription("xraySpecialCasingPtr", xraySpecialCasingPtr),
            )
        }
        if (gfxCount > 4) {
            Text(
                "\u26A0 GFX limit exceeded ($gfxCount/4) — SNES hardware supports max 4 enemy tilesets. " +
                "Excess species will have garbled sprites.",
                fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                color = Color(0xFFFF5722),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        if (showRomAddresses) {
            Text(
                "ROM addresses (advanced)",
                fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 3.dp),
            )
            PropertyRow("Layout Data", snesAddr24(levelDataPtr))
            PropertyRow("BG Data", if (bgDataPtr == 0) "None" else "\$8F:${hex16(bgDataPtr).removePrefix("0x")}")
            PropertyRow("Object Set", "\$8F:${hex16(plmSetPtr).removePrefix("0x")}")
            PropertyRow("Enemy Set", "\$A1:${hex16(enemySetPtr).removePrefix("0x")}")
            PropertyRow("Enemy GFX", "\$B4:${hex16(enemyGfxPtr).removePrefix("0x")}")
            PropertyRow(
                "Special X-Ray",
                if (xraySpecialCasingPtr == 0) "None" else "\$8F:${hex16(xraySpecialCasingPtr).removePrefix("0x")}",
            )
            PropertyRow("Main ASM", if (mainAsmPtr == 0) "None" else "\$8F:${hex16(mainAsmPtr).removePrefix("0x")}")
            PropertyRow("Setup ASM", if (setupAsmPtr == 0) "None" else "\$8F:${hex16(setupAsmPtr).removePrefix("0x")}")
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── FX Data (editable) ──
        SectionHeader("FX Data")
        if (!hasFxTable) {
            Text(
                "No effects yet. Choosing a type creates a private FX table for this state.",
                fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Door-routed entries override the default effect for one entrance.
        for (fx in fxEntries) {
            if (fx.doorSelect != 0) {
                val saved = savedDoorFxChanges.entries
                    .firstOrNull { it.key.toIntOrNull(16) == fx.doorSelect }
                    ?.value
                val effectiveType = saved?.fxType ?: fx.fxType
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 7.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Door \$${fx.doorSelect.toString(16).uppercase().padStart(4, '0')}",
                                fontSize = ROOM_INFO_BODY_FONT_SIZE,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                FX_TYPE_OPTIONS.firstOrNull { it.first == effectiveType }?.second
                                    ?: "Unknown (${hex8(effectiveType)})",
                                fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { editingDoorFx = fx.doorSelect },
                            modifier = Modifier.height(26.dp),
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        ) {
                            Text("Edit", fontSize = ROOM_INFO_COMPACT_FONT_SIZE)
                        }
                    }
                }
            }
        }

        val doorFxEntry = editingDoorFx?.let { door ->
            fxEntries.firstOrNull { it.doorSelect == door }
        }
        if (doorFxEntry != null) {
            val saved = savedDoorFxChanges.entries
                .firstOrNull { it.key.toIntOrNull(16) == doorFxEntry.doorSelect }
                ?.value
            DoorFxEditorDialog(
                entry = doorFxEntry,
                savedChange = saved,
                onDismiss = { editingDoorFx = null },
                onSave = { change ->
                    editorState.setRoomStateDoorFxChange(
                        stateId = selectedStateItem?.id ?: return@DoorFxEditorDialog,
                        doorSelect = doorFxEntry.doorSelect,
                        change = change,
                        romParser = romParser,
                    )
                    editingDoorFx = null
                },
            )
        }

        if (fxEntries.size > 1) {
            Text("Default FX", fontSize = ROOM_INFO_BODY_FONT_SIZE, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        }

        FxTypeDropdown(editFxType) { editFxType = it; syncFxToState() }

        val isLiquid = editFxType in listOf(0x02, 0x04, 0x06)
        if (isLiquid) {
            EditableHexRow("Liquid Start", editLiquidStart, 2) { editLiquidStart = it; syncFxToState() }
            EditableHexRow("Liquid Target", editLiquidNew, 2) { editLiquidNew = it; syncFxToState() }
            EditableHexRow("Liquid Speed", editLiquidSpeed, 2) { editLiquidSpeed = it; syncFxToState() }
            EditableHexRow("Liquid Delay", editLiquidDelay, 1) { editLiquidDelay = it; syncFxToState() }
        }

        EditableHexRow("FX Trans. A", editFxBitA, 1) { editFxBitA = it; syncFxToState() }
        EditableHexRow("FX Trans. B", editFxBitB, 1) { editFxBitB = it; syncFxToState() }

        BitfieldRow("Liquid Options", editFxBitC, listOf(
            0x01 to "Small Tide",
            0x02 to "Large Tide",
            0x20 to "BG Warp-Line Shift",
            0x40 to "BG Warp-Cascade Heat",
            0x80 to "Flow Left",
        )) { editFxBitC = it; syncFxToState() }

        BitfieldRow("Tile Anim", editTileAnimBits, listOf(
            0x01 to "Spikes (H)",
            0x02 to "Spikes (V)",
            0x04 to "Ocean/Sand",
            0x08 to "Lava/Sandfall",
        )) { editTileAnimBits = it; syncFxToState() }

        BitfieldRow("Palette FX", editPaletteFxBits,
            (0..7).map { (1 shl it) to "Pal ${it + 1}" }
        ) { editPaletteFxBits = it; syncFxToState() }

        EditableHexRow("Palette Blend", editPaletteBlend, 1) { editPaletteBlend = it; syncFxToState() }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Space Usage ──
        val spaceUsage = remember(room.roomId, currentState?.stateDataPcOffset, editorState.editVersion) {
            romParser.readRoomSpaceUsage(room.roomId, currentState?.stateDataPcOffset)
        }
        if (spaceUsage != null) {
            SectionHeader("Space Usage")
            SpaceUsageBar("Level Data", spaceUsage.levelDataCompressed, "compressed")
            SpaceUsageBar("PLMs", spaceUsage.plmBytes, "${spaceUsage.plmCount} entries")
            SpaceUsageBar("Enemies", spaceUsage.enemyBytes, "${spaceUsage.enemyCount} entries")
            SpaceUsageBar("Scrolls", spaceUsage.scrollBytes, "${room.width}×${room.height}")
            SpaceUsageBar("Doors", spaceUsage.doorBytes, "${spaceUsage.doorCount} entries")
            Spacer(modifier = Modifier.height(4.dp))
        }

        // ── Scroll Data (editable) ──
        SectionHeader(
            title = "Room Scrolls",
            onHelp = { helpTopic = RoomInfoHelpTopic.SCROLLS },
        )
        if (helpTopic == RoomInfoHelpTopic.SCROLLS) {
            RoomScrollsHelpDialog(
                editingAvailable = currentState?.stateDataPcOffset != null,
                onDismiss = { helpTopic = null },
            )
        }
        val scrollsPtr = stateData["roomScrollsPtr"] ?: room.roomScrollsPtr
        PropertyRow("Scrolls Ptr", when (scrollsPtr) {
            0x0000 -> "All Blue (\$0000)"
            0x0001 -> "All Green (\$0001)"
            else -> "\$8F:${scrollsPtr.toString(16).uppercase().padStart(4, '0')}"
        })

        if (scrollData.isNotEmpty() && currentState?.stateDataPcOffset != null) {
            val scrollW = editorState.workingBlocksWide / 16
            val scrollH = editorState.workingBlocksTall / 16
            EditableScrollGrid(scrollData, scrollW, scrollH) { col, row, newVal ->
                editorState.setScroll(col, row, newVal, scrollW)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun RoomResizeRow(room: Room, editorState: EditorState) {
    var editingSize by remember { mutableStateOf(false) }
    var newWidth by remember(room.roomId) { mutableStateOf(room.width) }
    var newHeight by remember(room.roomId) { mutableStateOf(room.height) }
    val currentWidth = editorState.project.rooms[
        room.roomId.toString(16).uppercase().padStart(4, '0')
    ]?.roomHeaderChange?.width ?: room.width
    val currentHeight = editorState.project.rooms[
        room.roomId.toString(16).uppercase().padStart(4, '0')
    ]?.roomHeaderChange?.height ?: room.height

    if (!editingSize) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Size", fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
            Text("${currentWidth}\u00D7${currentHeight} screens", fontSize = ROOM_INFO_BODY_FONT_SIZE, modifier = Modifier.weight(1f))
            Text(
                "Resize",
                fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    newWidth = currentWidth
                    newHeight = currentHeight
                    editingSize = true
                }.padding(horizontal = 4.dp),
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .padding(6.dp)
        ) {
            Text("Resize Room", fontSize = ROOM_INFO_BODY_FONT_SIZE, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Width", fontSize = ROOM_INFO_COMPACT_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Surface(
                            modifier = Modifier.size(24.dp).clickable { if (newWidth > 1) newWidth-- },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Text("\u2212", fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
                        Text("$newWidth", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Surface(
                            modifier = Modifier.size(24.dp).clickable { if (newWidth < 15) newWidth++ },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Text("+", fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
                    }
                }
                Text("\u00D7", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Height", fontSize = ROOM_INFO_COMPACT_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Surface(
                            modifier = Modifier.size(24.dp).clickable { if (newHeight > 1) newHeight-- },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Text("\u2212", fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
                        Text("$newHeight", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Surface(
                            modifier = Modifier.size(24.dp).clickable { if (newHeight < 15) newHeight++ },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Text("+", fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
                    }
                }
            }
            // Delta preview
            if (newWidth != currentWidth || newHeight != currentHeight) {
                Spacer(Modifier.height(4.dp))
                val dw = newWidth - currentWidth; val dh = newHeight - currentHeight
                val dwText = if (dw > 0) "+$dw" else "$dw"
                val dhText = if (dh > 0) "+$dh" else "$dh"
                val tileInfo = "${newWidth * 16}\u00D7${newHeight * 16} tiles"
                Text(
                    "${currentWidth}\u00D7${currentHeight} \u2192 ${newWidth}\u00D7${newHeight} ($dwText, $dhText) \u2014 $tileInfo",
                    fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (newWidth < currentWidth || newHeight < currentHeight) {
                    Text(
                        "Tiles outside the new bounds will be removed",
                        fontSize = ROOM_INFO_COMPACT_FONT_SIZE,
                        color = Color(0xFFCC8833)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val changed = newWidth != currentWidth || newHeight != currentHeight
                Surface(
                    modifier = Modifier.weight(1f).height(26.dp)
                        .clickable(enabled = changed) {
                            editorState.resizeRoom(currentWidth, currentHeight, newWidth, newHeight)
                            editingSize = false
                        },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ) { Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Text("Apply", fontSize = ROOM_INFO_BODY_FONT_SIZE, fontWeight = FontWeight.Bold, color = if (changed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) } }
                Surface(
                    modifier = Modifier.weight(1f).height(26.dp)
                        .clickable { editingSize = false },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) { Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Text("Cancel", fontSize = ROOM_INFO_BODY_FONT_SIZE) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FxTypeDropdown(selectedType: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val typeName = FX_TYPE_OPTIONS.firstOrNull { it.first == selectedType }?.second ?: "Unknown (${hex8(selectedType)})"

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("FX Type", fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
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
                    Text(typeName, fontSize = ROOM_INFO_BODY_FONT_SIZE, modifier = Modifier.weight(1f))
                    Text("▾", fontSize = ROOM_INFO_COMPACT_FONT_SIZE)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for ((code, name) in FX_TYPE_OPTIONS) {
                    DropdownMenuItem(
                        text = { Text("${hex8(code)} — $name", fontSize = ROOM_INFO_BODY_FONT_SIZE) },
                        onClick = { expanded = false; onSelect(code) },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DoorFxEditorDialog(
    entry: RomParser.FxEntry,
    savedChange: FxChange?,
    onDismiss: () -> Unit,
    onSave: (FxChange?) -> Unit,
) {
    var fxType by remember(entry.doorSelect, savedChange) { mutableStateOf(savedChange?.fxType ?: entry.fxType) }
    var liquidStart by remember(entry.doorSelect, savedChange) {
        mutableStateOf(savedChange?.liquidSurfaceStart ?: entry.liquidSurfaceStart)
    }
    var liquidTarget by remember(entry.doorSelect, savedChange) {
        mutableStateOf(savedChange?.liquidSurfaceNew ?: entry.liquidSurfaceNew)
    }
    var liquidSpeed by remember(entry.doorSelect, savedChange) {
        mutableStateOf(savedChange?.liquidSpeed ?: entry.liquidSpeed)
    }
    var liquidDelay by remember(entry.doorSelect, savedChange) {
        mutableStateOf(savedChange?.liquidDelay ?: entry.liquidDelay)
    }
    var fxBitA by remember(entry.doorSelect, savedChange) { mutableStateOf(savedChange?.fxBitA ?: entry.fxBitA) }
    var fxBitB by remember(entry.doorSelect, savedChange) { mutableStateOf(savedChange?.fxBitB ?: entry.fxBitB) }
    var fxBitC by remember(entry.doorSelect, savedChange) { mutableStateOf(savedChange?.fxBitC ?: entry.fxBitC) }
    var paletteFxBits by remember(entry.doorSelect, savedChange) {
        mutableStateOf(savedChange?.paletteFxBitflags ?: entry.paletteFxBitflags)
    }
    var tileAnimBits by remember(entry.doorSelect, savedChange) {
        mutableStateOf(savedChange?.tileAnimBitflags ?: entry.tileAnimBitflags)
    }
    var paletteBlend by remember(entry.doorSelect, savedChange) {
        mutableStateOf(savedChange?.paletteBlend ?: entry.paletteBlend)
    }

    fun currentChange(): FxChange = FxChange(
        fxType = fxType.takeIf { it != entry.fxType },
        liquidSurfaceStart = liquidStart.takeIf { it != entry.liquidSurfaceStart },
        liquidSurfaceNew = liquidTarget.takeIf { it != entry.liquidSurfaceNew },
        liquidSpeed = liquidSpeed.takeIf { it != entry.liquidSpeed },
        liquidDelay = liquidDelay.takeIf { it != entry.liquidDelay },
        fxBitA = fxBitA.takeIf { it != entry.fxBitA },
        fxBitB = fxBitB.takeIf { it != entry.fxBitB },
        fxBitC = fxBitC.takeIf { it != entry.fxBitC },
        paletteFxBitflags = paletteFxBits.takeIf { it != entry.paletteFxBitflags },
        tileAnimBitflags = tileAnimBits.takeIf { it != entry.tileAnimBitflags },
        paletteBlend = paletteBlend.takeIf { it != entry.paletteBlend },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Door \$${entry.doorSelect.toString(16).uppercase().padStart(4, '0')} effect")
        },
        text = {
            Column(
                modifier = Modifier.requiredSizeIn(maxHeight = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "Used instead of Default FX when the room is entered through this door.",
                    fontSize = ROOM_INFO_BODY_FONT_SIZE,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
                FxTypeDropdown(fxType) { fxType = it }
                if (fxType in listOf(0x02, 0x04, 0x06)) {
                    EditableHexRow("Liquid Start", liquidStart, 2) { liquidStart = it }
                    EditableHexRow("Liquid Target", liquidTarget, 2) { liquidTarget = it }
                    EditableHexRow("Liquid Speed", liquidSpeed, 2) { liquidSpeed = it }
                    EditableHexRow("Liquid Delay", liquidDelay, 1) { liquidDelay = it }
                }
                EditableHexRow("FX Trans. A", fxBitA, 1) { fxBitA = it }
                EditableHexRow("FX Trans. B", fxBitB, 1) { fxBitB = it }
                BitfieldRow(
                    "Liquid Options",
                    fxBitC,
                    listOf(
                        0x01 to "Small Tide",
                        0x02 to "Large Tide",
                        0x20 to "BG Warp-Line Shift",
                        0x40 to "BG Warp-Cascade Heat",
                        0x80 to "Flow Left",
                    ),
                ) { fxBitC = it }
                BitfieldRow(
                    "Tile Anim",
                    tileAnimBits,
                    listOf(
                        0x01 to "Spikes (H)",
                        0x02 to "Spikes (V)",
                        0x04 to "Ocean/Sand",
                        0x08 to "Lava/Sandfall",
                    ),
                ) { tileAnimBits = it }
                BitfieldRow(
                    "Palette FX",
                    paletteFxBits,
                    (0..7).map { (1 shl it) to "Pal ${it + 1}" },
                ) { paletteFxBits = it }
                EditableHexRow("Palette Blend", paletteBlend, 1) { paletteBlend = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val change = currentChange()
                onSave(change.takeIf { it != FxChange() })
            }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditableScrollGrid(
    scrollData: IntArray,
    width: Int,
    height: Int,
    onScrollChange: (col: Int, row: Int, newValue: Int) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text("Click to cycle: Blue → Green → Red → Blue", fontSize = ROOM_INFO_CAPTION_FONT_SIZE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp))
        for (row in 0 until height) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0 until width) {
                    val idx = row * width + col
                    val scrollVal = scrollData.getOrElse(idx) { 0x01 }
                    val bgColor = SCROLL_COLORS[scrollVal] ?: Color.Gray
                    val label = SCROLL_LABELS[scrollVal] ?: "?"
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(bgColor, MaterialTheme.shapes.extraSmall)
                            .clickable {
                                val next = when (scrollVal) {
                                    0x01 -> 0x02  // Blue → Green
                                    0x02 -> 0x00  // Green → Red
                                    else -> 0x01  // Red → Blue
                                }
                                onScrollChange(col, row, next)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = ROOM_INFO_BODY_FONT_SIZE, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((code, lbl) in listOf(0x00 to "Red (hidden)", 0x01 to "Blue (explorable)", 0x02 to "Green (PLM-gated)")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(SCROLL_COLORS[code]!!, MaterialTheme.shapes.extraSmall))
                    Text(lbl, fontSize = ROOM_INFO_CAPTION_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BitfieldRow(
    label: String,
    value: Int,
    bits: List<Pair<Int, String>>,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for ((mask, name) in bits) {
                val checked = (value and mask) != 0
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        onValueChange(if (checked) value and mask.inv() else value or mask)
                    }
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            onValueChange(if (it) value or mask else value and mask.inv())
                        },
                        modifier = Modifier.size(14.dp)
                    )
                    Text(name, fontSize = ROOM_INFO_COMPACT_FONT_SIZE, modifier = Modifier.padding(start = 6.dp, end = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SpaceUsageBar(label: String, bytes: Int, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(70.dp))
        Text("$bytes B", fontSize = ROOM_INFO_BODY_FONT_SIZE, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium, modifier = Modifier.width(54.dp))
        Text(detail, fontSize = ROOM_INFO_COMPACT_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
