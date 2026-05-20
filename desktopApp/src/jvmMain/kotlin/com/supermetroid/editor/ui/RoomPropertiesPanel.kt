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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import com.supermetroid.editor.data.FxChange
import com.supermetroid.editor.data.RoomHeaderChange
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.data.StateDataChange
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

private val AREA_NAMES = arrayOf("Crateria", "Brinstar", "Norfair", "Wrecked Ship", "Maridia", "Tourian", "Ceres")

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomPropertiesPanel(
    room: Room,
    romParser: RomParser,
    editorState: EditorState,
    modifier: Modifier = Modifier,
    onNavigateToMap: (() -> Unit)? = null,
) {
    val states = remember(room.roomId) { romParser.parseRoomStates(room.roomId) }
    var selectedStateIdx by remember(room.roomId) { mutableStateOf(states.size - 1) }
    val currentState = states.getOrNull(selectedStateIdx)
    val stateData = remember(currentState) {
        currentState?.let { romParser.readStateData(it.stateDataPcOffset) } ?: emptyMap()
    }
    val fxPtr = stateData["fxPtr"] ?: room.fxPtr
    val fxEntries = remember(fxPtr) { romParser.parseFxEntries(fxPtr) }
    val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }

    // Use working scrolls from EditorState (includes edits)
    val scrollVer = editorState.scrollVersion
    val scrollData = remember(scrollVer, room.roomId) { editorState.workingScrolls.copyOf() }

    // Track FX edit state locally, sync to EditorState
    val roomEdits = editorState.project.rooms[editorState.project.roomKey(room.roomId)]
    val savedFx = roomEdits?.fxChange
    val savedState = roomEdits?.stateDataChange

    // FX edit state — keyed by (roomId, stateIdx) so fields reset on state switch
    var editFxType by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.fxType ?: defaultFx?.fxType ?: 0) }
    var editLiquidStart by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.liquidSurfaceStart ?: defaultFx?.liquidSurfaceStart ?: 0xFFFF) }
    var editLiquidNew by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.liquidSurfaceNew ?: defaultFx?.liquidSurfaceNew ?: 0xFFFF) }
    var editLiquidSpeed by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.liquidSpeed ?: defaultFx?.liquidSpeed ?: 0) }
    var editLiquidDelay by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.liquidDelay ?: defaultFx?.liquidDelay ?: 0) }
    var editFxBitA by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.fxBitA ?: defaultFx?.fxBitA ?: 0x02) }
    var editFxBitB by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.fxBitB ?: defaultFx?.fxBitB ?: 0x02) }
    var editFxBitC by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.fxBitC ?: defaultFx?.fxBitC ?: 0) }
    var editPaletteFxBits by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.paletteFxBitflags ?: defaultFx?.paletteFxBitflags ?: 0) }
    var editTileAnimBits by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.tileAnimBitflags ?: defaultFx?.tileAnimBitflags ?: 0) }
    var editPaletteBlend by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedFx?.paletteBlend ?: defaultFx?.paletteBlend ?: 0) }

    // State data edit state — keyed by (roomId, stateIdx) so fields reset on state switch
    val origTileset = stateData["tileset"] ?: room.tileset
    val origMusicData = stateData["musicData"] ?: room.musicData
    val origMusicTrack = stateData["musicTrack"] ?: room.musicTrack
    val origBgScrolling = stateData["bgScrolling"] ?: room.bgScrolling
    var editTileset by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedState?.tileset ?: origTileset) }
    var editMusicData by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedState?.musicData ?: origMusicData) }
    var editMusicTrack by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedState?.musicTrack ?: origMusicTrack) }
    var editBgScrolling by remember(room.roomId, selectedStateIdx) { mutableStateOf(savedState?.bgScrolling ?: origBgScrolling) }

    fun syncFxToState() {
        val change = FxChange(
            fxType = editFxType.takeIf { it != (defaultFx?.fxType ?: 0) },
            liquidSurfaceStart = editLiquidStart.takeIf { it != (defaultFx?.liquidSurfaceStart ?: 0xFFFF) },
            liquidSurfaceNew = editLiquidNew.takeIf { it != (defaultFx?.liquidSurfaceNew ?: 0xFFFF) },
            liquidSpeed = editLiquidSpeed.takeIf { it != (defaultFx?.liquidSpeed ?: 0) },
            liquidDelay = editLiquidDelay.takeIf { it != (defaultFx?.liquidDelay ?: 0) },
            fxBitA = editFxBitA.takeIf { it != (defaultFx?.fxBitA ?: 0x02) },
            fxBitB = editFxBitB.takeIf { it != (defaultFx?.fxBitB ?: 0x02) },
            fxBitC = editFxBitC.takeIf { it != (defaultFx?.fxBitC ?: 0) },
            paletteFxBitflags = editPaletteFxBits.takeIf { it != (defaultFx?.paletteFxBitflags ?: 0) },
            tileAnimBitflags = editTileAnimBits.takeIf { it != (defaultFx?.tileAnimBitflags ?: 0) },
            paletteBlend = editPaletteBlend.takeIf { it != (defaultFx?.paletteBlend ?: 0) },
        )
        if (change == FxChange()) {
            editorState.project.getOrCreateRoom(room.roomId).fxChange = null
        } else {
            editorState.setFxChange(change)
        }
    }

    fun syncStateDataToState() {
        val change = StateDataChange(
            tileset = editTileset.takeIf { it != origTileset },
            musicData = editMusicData.takeIf { it != origMusicData },
            musicTrack = editMusicTrack.takeIf { it != origMusicTrack },
            bgScrolling = editBgScrolling.takeIf { it != origBgScrolling },
        )
        if (change == StateDataChange()) {
            editorState.project.getOrCreateRoom(room.roomId).stateDataChange = null
        } else {
            editorState.setStateDataChange(change)
        }
    }

    // Room header edit state — all 11 bytes
    val savedHeader = roomEdits?.roomHeaderChange
    var editArea by remember(room.roomId) { mutableStateOf(savedHeader?.area ?: room.area) }
    val displayMapX = savedHeader?.mapX ?: room.mapX
    val displayMapY = savedHeader?.mapY ?: room.mapY
    var editUpScroller by remember(room.roomId) { mutableStateOf(savedHeader?.upScroller ?: room.upScroller) }
    var editDownScroller by remember(room.roomId) { mutableStateOf(savedHeader?.downScroller ?: room.downScroller) }
    var editCreBitflag by remember(room.roomId) { mutableStateOf(savedHeader?.creBitflag ?: room.creBitflag) }

    fun syncHeaderToState() {
        val change = RoomHeaderChange(
            area = editArea.takeIf { it != room.area },
            mapX = savedHeader?.mapX,
            mapY = savedHeader?.mapY,
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
        EditableIntRow("Area", editArea, 0, 6) { editArea = it; syncHeaderToState() }
        PropertyRow("Area Name", AREA_NAMES.getOrElse(editArea) { "Unknown" })
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Map Position", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
            Text("($displayMapX, $displayMapY)", fontSize = 10.sp, modifier = Modifier.weight(1f))
            if (onNavigateToMap != null) {
                Text(
                    "Edit on Map",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onNavigateToMap() }.padding(horizontal = 4.dp),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )
            }
        }
        RoomResizeRow(room, romParser, editorState)
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
        SectionHeader("Room States (${states.size})")
        if (states.size > 1) {
            var stateDropExpanded by remember { mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(32.dp)
                        .clickable { stateDropExpanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            states.getOrNull(selectedStateIdx)?.conditionName ?: "?",
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text("▾", fontSize = 10.sp)
                    }
                }
                DropdownMenu(expanded = stateDropExpanded, onDismissRequest = { stateDropExpanded = false }) {
                    for ((idx, state) in states.withIndex()) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RadioButton(selected = selectedStateIdx == idx, onClick = null, modifier = Modifier.size(16.dp))
                                    Text(state.conditionName, fontSize = 11.sp)
                                }
                            },
                            onClick = { stateDropExpanded = false; selectedStateIdx = idx },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }
        } else if (states.size == 1) {
            Text(states[0].conditionName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("No states found", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── State Data (editable fields) ──
        SectionHeader("State Data")
        val levelDataPtr = stateData["levelDataPtr"] ?: room.levelDataPtr
        val mainAsmPtr = stateData["mainAsmPtr"] ?: room.mainAsmPtr
        val setupAsmPtr = stateData["setupAsmPtr"] ?: room.setupAsmPtr
        val bgDataPtr = stateData["bgDataPtr"] ?: room.bgDataPtr
        val enemySetPtr = stateData["enemySetPtr"] ?: room.enemySetPtr
        val enemyGfxPtr = stateData["enemyGfxPtr"] ?: room.enemyGfxPtr
        val plmSetPtr = stateData["plmSetPtr"] ?: room.plmSetPtr

        // Editable: Tileset
        EditableIntRow("Tileset", editTileset, 0, 29) { editTileset = it; syncStateDataToState() }

        // Editable: Music
        MusicDropdown(
            musicData = editMusicData,
            musicTrack = editMusicTrack,
            onMusicChange = { data, track ->
                editMusicData = data
                editMusicTrack = track
                syncStateDataToState()
            }
        )

        // Read-only pointers
        PropertyRow("Level Data", snesAddr24(levelDataPtr))

        // Editable: BG/Layer 2 Scrolling — dropdown with named modes
        BgScrollDropdown(editBgScrolling) { editBgScrolling = it; syncStateDataToState() }

        PropertyRow("BG Data Ptr", when (bgDataPtr) {
            0x0000 -> "None (layer 2 in level data)"
            else -> "\$8F:${bgDataPtr.toString(16).uppercase().padStart(4, '0')}"
        })
        val enemyCount = remember(enemySetPtr) { romParser.parseEnemyPopulation(enemySetPtr).size }
        val plmCount = remember(plmSetPtr) { romParser.parsePlmSet(plmSetPtr).size }
        PropertyRow("PLM Set", "\$8F:${plmSetPtr.toString(16).uppercase().padStart(4, '0')} ($plmCount PLMs)")
        PropertyRow("Enemy Set", "\$A1:${enemySetPtr.toString(16).uppercase().padStart(4, '0')} ($enemyCount enemies)")
        val gfxEntries = remember(enemyGfxPtr) { romParser.parseEnemyGfxSet(enemyGfxPtr) }
        val gfxCount = gfxEntries.size
        PropertyRow("Enemy GFX", "\$B4:${enemyGfxPtr.toString(16).uppercase().padStart(4, '0')} ($gfxCount slots)")
        if (gfxCount > 4) {
            Text(
                "\u26A0 GFX limit exceeded ($gfxCount/4) — SNES hardware supports max 4 enemy tilesets. " +
                "Excess species will have garbled sprites.",
                fontSize = 9.sp,
                color = Color(0xFFFF5722),
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        PropertyRow("Main ASM", if (mainAsmPtr == 0) "None" else "\$8F:${mainAsmPtr.toString(16).uppercase().padStart(4, '0')}")
        PropertyRow("Setup ASM", if (setupAsmPtr == 0) "None" else "\$8F:${setupAsmPtr.toString(16).uppercase().padStart(4, '0')}")

        Spacer(modifier = Modifier.height(4.dp))

        // ── FX Data (editable) ──
        SectionHeader("FX Data")
        if (defaultFx == null && fxEntries.isEmpty()) {
            Text("No FX data", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
        } else {
            // Show door-specific entries as read-only
            for (fx in fxEntries) {
                if (fx.doorSelect != 0) {
                    Text(
                        "Door-Specific FX (door \$${fx.doorSelect.toString(16).uppercase()})",
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    PropertyRow("FX Type", fx.fxTypeName)
                    if (fx.hasLiquid) {
                        PropertyRow("Liquid Start", hex16(fx.liquidSurfaceStart))
                        PropertyRow("Liquid Target", hex16(fx.liquidSurfaceNew))
                    }
                }
            }

            // Default FX — editable
            if (defaultFx != null) {
                if (fxEntries.size > 1) {
                    Text("Default FX", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }

                // FX Type dropdown
                FxTypeDropdown(editFxType) { editFxType = it; syncFxToState() }

                // Liquid properties
                val isLiquid = editFxType in listOf(0x02, 0x04, 0x06)
                if (isLiquid) {
                    EditableHexRow("Liquid Start", editLiquidStart, 2) { editLiquidStart = it; syncFxToState() }
                    EditableHexRow("Liquid Target", editLiquidNew, 2) { editLiquidNew = it; syncFxToState() }
                    EditableHexRow("Liquid Speed", editLiquidSpeed, 2) { editLiquidSpeed = it; syncFxToState() }
                    EditableHexRow("Liquid Delay", editLiquidDelay, 1) { editLiquidDelay = it; syncFxToState() }
                }

                // Transparency
                EditableHexRow("FX Trans. A", editFxBitA, 1) { editFxBitA = it; syncFxToState() }
                EditableHexRow("FX Trans. B", editFxBitB, 1) { editFxBitB = it; syncFxToState() }

                // Liquid options (fxBitC) — bitfield checkboxes (from SMILE SmileMod1.bas)
                BitfieldRow("Liquid Options", editFxBitC, listOf(
                    0x01 to "Small Tide",
                    0x02 to "Large Tide",
                    0x20 to "BG Warp-Line Shift",
                    0x40 to "BG Warp-Cascade Heat",
                    0x80 to "Flow Left",
                )) { editFxBitC = it; syncFxToState() }

                // Animated tiles — bitfield checkboxes
                BitfieldRow("Tile Anim", editTileAnimBits, listOf(
                    0x01 to "Spikes (H)",
                    0x02 to "Spikes (V)",
                    0x04 to "Ocean/Sand",
                    0x08 to "Lava/Sandfall",
                )) { editTileAnimBits = it; syncFxToState() }

                // Palette FX — which palettes glow
                BitfieldRow("Palette FX", editPaletteFxBits,
                    (0..7).map { (1 shl it) to "Pal ${it + 1}" }
                ) { editPaletteFxBits = it; syncFxToState() }

                // Palette blend
                EditableHexRow("Palette Blend", editPaletteBlend, 1) { editPaletteBlend = it; syncFxToState() }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Scroll Data (editable) ──
        SectionHeader("Room Scrolls")
        val scrollsPtr = stateData["roomScrollsPtr"] ?: room.roomScrollsPtr
        PropertyRow("Scrolls Ptr", when (scrollsPtr) {
            0x0000 -> "All Blue (\$0000)"
            0x0001 -> "All Green (\$0001)"
            else -> "\$8F:${scrollsPtr.toString(16).uppercase().padStart(4, '0')}"
        })

        if (scrollData.isNotEmpty()) {
            val scrollW = editorState.workingBlocksWide / 16
            val scrollH = editorState.workingBlocksTall / 16
            EditableScrollGrid(scrollData, scrollW, scrollH) { col, row, newVal ->
                editorState.setScroll(col, row, newVal, scrollW)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
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
        Text("Music", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
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
                        fontSize = 10.sp,
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
                                fontSize = 10.sp,
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

// ── BG Scroll Mode Dropdown ───────────────────────────────────────

private val BG_SCROLL_MODES = listOf(
    0x0000 to "No Layer 2",
    0x0001 to "Fixed (no scroll)",
    0x0002 to "Scroll with Layer 1",
    0x0004 to "Slow horizontal parallax",
    0x0006 to "Slow vertical parallax",
    0x0008 to "Slow H+V parallax",
    0x000A to "Fast horizontal parallax",
    0x000C to "Fast vertical parallax",
    0x000E to "Fast H+V parallax",
    0x0010 to "Very slow H parallax",
    0x0014 to "Inverse horizontal parallax",
    0x0016 to "Inverse H + slow V parallax",
    0x0024 to "Slow H parallax (alt)",
    0x002E to "No scroll (used w/ BG data)",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BgScrollDropdown(selectedValue: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val modeName = BG_SCROLL_MODES.firstOrNull { it.first == selectedValue }?.second
    val displayText = if (modeName != null) "$modeName (${hex16(selectedValue)})" else "Custom (${hex16(selectedValue)})"

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("BG Scroll", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
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
                    Text(displayText, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("▾", fontSize = 9.sp)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for ((code, name) in BG_SCROLL_MODES) {
                    val isSelected = code == selectedValue
                    DropdownMenuItem(
                        text = {
                            Text("${hex16(code)} — $name", fontSize = 10.sp,
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
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
    Divider()
}

@Composable
private fun RoomResizeRow(room: Room, romParser: RomParser, editorState: EditorState) {
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
            Text("Size", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
            Text("${currentWidth}\u00D7${currentHeight} screens", fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(
                "Resize",
                fontSize = 9.sp,
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
            Text("Resize Room", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Width", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("Height", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (newWidth < currentWidth || newHeight < currentHeight) {
                    Text(
                        "Tiles outside the new bounds will be removed",
                        fontSize = 9.sp,
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
                ) { Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Text("Apply", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (changed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) } }
                Surface(
                    modifier = Modifier.weight(1f).height(26.dp)
                        .clickable { editingSize = false },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) { Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Text("Cancel", fontSize = 10.sp) } }
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
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, fontSize = 10.sp, modifier = Modifier.weight(1f))
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
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        if (isEditing) {
            BasicTextField(
                value = text,
                onValueChange = { newText ->
                    val filtered = newText.uppercase().filter { it in "0123456789ABCDEF" }.take(hexDigits)
                    text = filtered
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface),
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
            ) { Text("OK", fontSize = 9.sp) }
        } else {
            Text(
                "0x${value.toString(16).uppercase().padStart(hexDigits, '0')}$suffix",
                fontSize = 10.sp,
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
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        if (isEditing) {
            BasicTextField(
                value = text,
                onValueChange = { newText -> text = newText.filter { it.isDigit() }.take(4) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface),
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
            ) { Text("OK", fontSize = 9.sp) }
        } else {
            Text(
                "$value$suffix",
                fontSize = 10.sp,
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
        Text("FX Type", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
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
                    Text(typeName, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("▾", fontSize = 9.sp)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for ((code, name) in FX_TYPE_OPTIONS) {
                    DropdownMenuItem(
                        text = { Text("${hex8(code)} — $name", fontSize = 10.sp) },
                        onClick = { expanded = false; onSelect(code) },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditableScrollGrid(
    scrollData: IntArray,
    width: Int,
    height: Int,
    onScrollChange: (col: Int, row: Int, newValue: Int) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text("Click to cycle: Blue → Green → Red → Blue", fontSize = 8.sp,
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
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((code, lbl) in listOf(0x00 to "Red (hidden)", 0x01 to "Blue (explorable)", 0x02 to "Green (PLM-gated)")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(SCROLL_COLORS[code]!!, MaterialTheme.shapes.extraSmall))
                    Text(lbl, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(name, fontSize = 9.sp, modifier = Modifier.padding(start = 6.dp, end = 2.dp))
                }
            }
        }
    }
}

private fun hex8(v: Int) = "0x${v.toString(16).uppercase().padStart(2, '0')}"
private fun hex16(v: Int) = "0x${v.toString(16).uppercase().padStart(4, '0')}"
private fun snesAddr24(v: Int): String {
    val bank = (v shr 16) and 0xFF
    val addr = v and 0xFFFF
    return "\$${bank.toString(16).uppercase().padStart(2, '0')}:${addr.toString(16).uppercase().padStart(4, '0')}"
}
