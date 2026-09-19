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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.ProjectRoomStateCondition
import com.supermetroid.editor.data.ProjectRoomStateConditionArgumentKind
import com.supermetroid.editor.data.ProjectRoomStateConditionKind
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.data.RoomStateEdits
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomStateCondition
import com.supermetroid.editor.rom.RoomStateSimulationContext
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.baseSourceStateIndex
import com.supermetroid.editor.rom.projectRoomStateCondition
import com.supermetroid.editor.rom.isSmEditGeneratedPredicate
import com.supermetroid.editor.rom.packedBossConditionArgument
import com.supermetroid.editor.rom.flattened
import com.supermetroid.editor.rom.matches

private val AREA_NAMES = arrayOf(
    "Crateria", "Brinstar", "Norfair", "Wrecked Ship", "Maridia", "Tourian", "Ceres", "Debug / Unused",
)

// The Room Info panel used to sit at 8–10sp, noticeably below the room-name
// typography beside it. Keep one compact, readable scale across its controls.
private val ROOM_INFO_BODY_FONT_SIZE = 11.sp
private val ROOM_INFO_COMPACT_FONT_SIZE = 10.sp
private val ROOM_INFO_CAPTION_FONT_SIZE = 9.sp
private val ROOM_INFO_SECTION_FONT_SIZE = 13.sp

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

private data class RoomStateUiItem(
    val id: String,
    val baseSourceStateIndex: Int?,
    val condition: ProjectRoomStateCondition,
    val edits: RoomStateEdits?,
)

private data class ItemPickupConditionOption(
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
            if (onNavigateToMap != null && effectiveArea in AREA_NAMES.indices) {
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

// ── State Condition Editor ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomStateSimulatorDialog(
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

private fun projectConditionLabel(condition: ProjectRoomStateCondition, area: Int): String =
    when (condition.kind) {
        ProjectRoomStateConditionKind.DEFAULT -> "Default"
        ProjectRoomStateConditionKind.INCOMING_DOOR -> {
            val door = "\$${(condition.argument ?: 0).toString(16).uppercase().padStart(4, '0')}"
            if (condition.negated) "Not entered through door $door" else "Entered through door $door"
        }
        ProjectRoomStateConditionKind.AREA_MAIN_BOSS_DEAD ->
            if (condition.negated) "${AREA_NAMES.getOrNull(area) ?: "Area $area"} main boss not defeated"
            else "${AREA_NAMES.getOrNull(area) ?: "Area $area"} main boss defeated"
        ProjectRoomStateConditionKind.NEVER -> if (condition.negated) "Always" else "Never"
        ProjectRoomStateConditionKind.EVENT_SET -> {
            val event = RoomStateCondition.EVENT_NAMES[condition.argument ?: 0]
            if (event != null) {
                if (condition.negated) "NOT ($event)" else event
            } else {
                val id = "\$${(condition.argument ?: 0).toString(16).uppercase().padStart(2, '0')}"
                if (condition.negated) "Event $id not set" else "Event $id set"
            }
        }
        ProjectRoomStateConditionKind.AREA_BOSS_BIT_SET -> {
            val mask = condition.argument ?: 0
            val boss = RoomStateCondition.BOSS_NAMES[area to mask]
            if (boss != null) {
                if (condition.negated) "$boss not defeated" else "$boss defeated"
            } else {
                val flag = "Custom boss flag \$${mask.toString(16).uppercase().padStart(2, '0')}"
                if (condition.negated) "$flag not set" else "$flag set"
            }
        }
        ProjectRoomStateConditionKind.MORPH_BALL_COLLECTED ->
            if (condition.negated) "Morph Ball not collected" else "Morph Ball collected"
        ProjectRoomStateConditionKind.MORPH_BALL_AND_MISSILES ->
            if (condition.negated) "Not (Morph Ball + missiles collected)" else "Morph Ball + missiles collected"
        ProjectRoomStateConditionKind.POWER_BOMBS_COLLECTED ->
            if (condition.negated) "No Power Bomb capacity" else "Power Bombs collected"
        ProjectRoomStateConditionKind.SPEED_BOOSTER_COLLECTED ->
            if (condition.negated) "Speed Booster not collected" else "Speed Booster collected"
        ProjectRoomStateConditionKind.EQUIPMENT_COLLECTED -> {
            val name = RoomStateCondition.EQUIPMENT_NAMES[condition.argument ?: 0] ?: "Equipment"
            if (condition.negated) "$name not collected" else "$name collected"
        }
        ProjectRoomStateConditionKind.BEAM_COLLECTED -> {
            val name = RoomStateCondition.BEAM_NAMES[condition.argument ?: 0] ?: "Beam"
            if (condition.negated) "$name not collected" else "$name collected"
        }
        ProjectRoomStateConditionKind.MISSILE_CAPACITY_AT_LEAST ->
            if (condition.negated) "Missile capacity below ${condition.argument ?: 0}"
            else "Missile capacity ≥ ${condition.argument ?: 0}"
        ProjectRoomStateConditionKind.SUPER_MISSILE_CAPACITY_AT_LEAST ->
            if (condition.negated) "Super Missile capacity below ${condition.argument ?: 0}"
            else "Super Missile capacity ≥ ${condition.argument ?: 0}"
        ProjectRoomStateConditionKind.POWER_BOMB_CAPACITY_AT_LEAST ->
            if (condition.negated) "Power Bomb capacity below ${condition.argument ?: 0}"
            else "Power Bomb capacity ≥ ${condition.argument ?: 0}"
        ProjectRoomStateConditionKind.ENERGY_CAPACITY_AT_LEAST ->
            if (condition.negated) "Energy capacity below ${condition.argument ?: 0}"
            else "Energy capacity ≥ ${condition.argument ?: 0}"
        ProjectRoomStateConditionKind.RESERVE_CAPACITY_AT_LEAST ->
            if (condition.negated) "Reserve capacity below ${condition.argument ?: 0}"
            else "Reserve capacity ≥ ${condition.argument ?: 0}"
        ProjectRoomStateConditionKind.ITEM_PICKUP_COLLECTED -> {
            val id = (condition.argument ?: 0).toString(16).uppercase().padStart(3, '0')
            if (condition.negated) "Specific item ID \$$id not collected" else "Specific item ID \$$id collected"
        }
        ProjectRoomStateConditionKind.BOSS_DEFEATED -> {
            val packed = condition.argument ?: 0
            val bossArea = (packed ushr 8) and 0xFF
            val mask = packed and 0xFF
            val boss = RoomStateCondition.BOSS_NAMES[bossArea to mask]
            if (boss != null) {
                if (condition.negated) "$boss not defeated" else "$boss defeated"
            } else {
                val areaName = AREA_NAMES.getOrNull(bossArea) ?: "Area $bossArea"
                val flag = "$areaName custom boss flag \$${mask.toString(16).uppercase().padStart(2, '0')}"
                if (condition.negated) "$flag not set" else "$flag set"
            }
        }
        ProjectRoomStateConditionKind.EQUIPMENT_EQUIPPED -> {
            val name = RoomStateCondition.EQUIPMENT_NAMES[condition.argument ?: 0] ?: "Equipment"
            if (condition.negated) "$name not equipped" else "$name equipped"
        }
        ProjectRoomStateConditionKind.BEAM_EQUIPPED -> {
            val name = RoomStateCondition.BEAM_NAMES[condition.argument ?: 0] ?: "Beam"
            if (condition.negated) "$name not equipped" else "$name equipped"
        }
        ProjectRoomStateConditionKind.CURRENT_ENERGY_AT_LEAST ->
            if (condition.negated) "Current energy below ${condition.argument ?: 0}"
            else "Current energy ≥ ${condition.argument ?: 0}"
        ProjectRoomStateConditionKind.CURRENT_MISSILES_AT_LEAST ->
            if (condition.negated) "Current missiles below ${condition.argument ?: 0}"
            else "Current missiles ≥ ${condition.argument ?: 0}"
        ProjectRoomStateConditionKind.CURRENT_SUPER_MISSILES_AT_LEAST ->
            if (condition.negated) "Current Super Missiles below ${condition.argument ?: 0}"
            else "Current Super Missiles ≥ ${condition.argument ?: 0}"
        ProjectRoomStateConditionKind.CURRENT_POWER_BOMBS_AT_LEAST ->
            if (condition.negated) "Current Power Bombs below ${condition.argument ?: 0}"
            else "Current Power Bombs ≥ ${condition.argument ?: 0}"
        ProjectRoomStateConditionKind.CURRENT_RESERVE_ENERGY_AT_LEAST ->
            if (condition.negated) "Current reserve below ${condition.argument ?: 0}"
            else "Current reserve ≥ ${condition.argument ?: 0}"
        ProjectRoomStateConditionKind.DOOR_BIT_SET -> {
            val id = (condition.argument ?: 0).toString(16).uppercase().padStart(3, '0')
            if (condition.negated) "Door bit \$$id not set" else "Door bit \$$id set"
        }
        ProjectRoomStateConditionKind.CHOZO_BLOCK_DESTROYED -> {
            val id = (condition.argument ?: 0).toString(16).uppercase().padStart(3, '0')
            if (condition.negated) "Chozo block \$$id intact" else "Chozo block \$$id destroyed"
        }
        ProjectRoomStateConditionKind.ESCAPE_ACTIVE ->
            if (condition.negated) "Escape not active" else "Escape active"
        ProjectRoomStateConditionKind.ALL_OF,
        ProjectRoomStateConditionKind.ANY_OF -> {
            val operator = if (condition.kind == ProjectRoomStateConditionKind.ALL_OF) " AND " else " OR "
            val body = condition.children.joinToString(operator) { child ->
                val label = projectConditionLabel(child, area)
                if (child.kind == ProjectRoomStateConditionKind.ALL_OF ||
                    child.kind == ProjectRoomStateConditionKind.ANY_OF
                ) "($label)" else label
            }.ifBlank { "Empty condition group" }
            if (condition.negated) "NOT ($body)" else body
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateConditionEditor(
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
        ProjectRoomStateConditionKind.BOSS_DEFEATED -> AREA_NAMES.indices.flatMap { bossArea ->
            RoomStateCondition.BOSS_FLAG_MASKS.map { mask ->
                val name = RoomStateCondition.BOSS_NAMES[bossArea to mask]
                    ?: "Custom boss flag \$${mask.toString(16).uppercase().padStart(2, '0')}"
                packedBossConditionArgument(bossArea, mask) to "${AREA_NAMES[bossArea]} — $name"
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
                                AREA_NAMES[index / RoomStateCondition.BOSS_FLAG_MASKS.size],
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
private fun ConditionBuilderDialog(
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
private fun AddRoomStateDialog(
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
private fun DeleteRoomStateDialog(
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
private fun MusicDropdown(
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

private data class RoomStateDifferenceDetail(
    val label: String,
    val detail: String,
)

private fun stateDifferenceDetail(
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
private fun BgScrollDropdown(selectedValue: Int, onSelect: (Int) -> Unit) {
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
private fun AreaDropdown(selectedArea: Int, onSelect: (Int) -> Unit) {
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
                            else -> AREA_NAMES.getOrElse(selectedArea) { "Invalid area $selectedArea" }
                        },
                        fontSize = ROOM_INFO_BODY_FONT_SIZE,
                    )
                    Text("▾", fontSize = ROOM_INFO_COMPACT_FONT_SIZE)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AREA_NAMES.forEachIndexed { area, name ->
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
private fun SectionHeader(
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
private fun SelectedStateSummary(
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
private fun RoomStatesHelpDialog(onDismiss: () -> Unit) {
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
private fun StateDataHelpDialog(
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
private fun RoomStateComparisonDialog(
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
private fun RoomScrollsHelpDialog(editingAvailable: Boolean, onDismiss: () -> Unit) {
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

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, fontSize = ROOM_INFO_BODY_FONT_SIZE, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EditableHexRow(
    label: String,
    value: Int,
    byteCount: Int,
    suffix: String = "",
    onValueChange: (Int) -> Unit
) {
    val hexDigits = byteCount * 2
    val maxVal = (1 shl (byteCount * 8)) - 1
    var text by remember(value) { mutableStateOf(value.toString(16).uppercase().padStart(hexDigits, '0')) }
    var isEditing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        if (isEditing) {
            BasicTextField(
                value = text,
                onValueChange = { newText ->
                    val filtered = newText.uppercase().filter { it in "0123456789ABCDEF" }.take(hexDigits)
                    text = filtered
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            TextButton(
                onClick = {
                    val parsed = text.toIntOrNull(16) ?: value
                    onValueChange(parsed.coerceIn(0, maxVal))
                    isEditing = false
                },
                modifier = Modifier.height(20.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) { Text("OK", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
        } else {
            Text(
                "0x${value.toString(16).uppercase().padStart(hexDigits, '0')}$suffix",
                fontSize = ROOM_INFO_BODY_FONT_SIZE,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).clickable { isEditing = true }
            )
        }
    }
}

@Composable
private fun EditableIntRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String = "",
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var isEditing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        if (isEditing) {
            BasicTextField(
                value = text,
                onValueChange = { newText -> text = newText.filter { it.isDigit() }.take(5) },
                singleLine = true,
                textStyle = TextStyle(fontSize = ROOM_INFO_BODY_FONT_SIZE, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            TextButton(
                onClick = {
                    val parsed = text.toIntOrNull() ?: value
                    onValueChange(parsed.coerceIn(min, max))
                    isEditing = false
                },
                modifier = Modifier.height(20.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) { Text("OK", fontSize = ROOM_INFO_COMPACT_FONT_SIZE) }
        } else {
            Text(
                "$value$suffix",
                fontSize = ROOM_INFO_BODY_FONT_SIZE,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).clickable { isEditing = true }
            )
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

private fun hex8(v: Int) = "0x${v.toString(16).uppercase().padStart(2, '0')}"
private fun hex16(v: Int) = "0x${v.toString(16).uppercase().padStart(4, '0')}"
private fun snesAddr24(v: Int): String {
    val bank = (v shr 16) and 0xFF
    val addr = v and 0xFFFF
    return "\$${bank.toString(16).uppercase().padStart(2, '0')}:${addr.toString(16).uppercase().padStart(4, '0')}"
}
