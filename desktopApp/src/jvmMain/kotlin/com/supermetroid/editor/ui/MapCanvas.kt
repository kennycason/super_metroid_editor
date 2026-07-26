package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.event.MouseEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.supermetroid.editor.data.RoomInfo
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomRenderData
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.image.BufferedImage
import java.awt.RenderingHints
import javax.imageio.ImageIO

private val mapCanvasLog = KotlinLogging.logger {}
private const val ROOM_MIN_ZOOM = 0.25f
private const val ROOM_MAX_ZOOM = 4f
private const val ROOM_ZOOM_EPSILON = 0.0005f
private val inMemoryRoomZooms = mutableStateMapOf<Int, Float>()

private fun mapCanvasLogLine(message: Any? = "") {
    val text = message?.toString() ?: ""
    when {
        text.startsWith("ERROR") || text.contains(" ERROR:") -> mapCanvasLog.error { text }
        text.startsWith("WARN") || text.contains(" WARN:") -> mapCanvasLog.warn { text }
        else -> mapCanvasLog.info { text }
    }
}

internal fun fitRoomZoomForViewport(
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    density: Float,
    contentWidthPx: Int,
    contentHeightPx: Int,
    minZoom: Float = ROOM_MIN_ZOOM,
    maxZoom: Float = ROOM_MAX_ZOOM,
): Float? {
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || density <= 0f ||
        contentWidthPx <= 0 || contentHeightPx <= 0
    ) {
        return null
    }
    val viewportWidthDp = viewportWidthPx / density
    val viewportHeightDp = viewportHeightPx / density
    val fitWidth = viewportWidthDp / contentWidthPx
    val fitHeight = viewportHeightDp / contentHeightPx
    return minOf(fitWidth, fitHeight).coerceIn(minZoom, maxZoom)
}

internal fun shouldSaveRoomZoom(
    hasSavedZoom: Boolean,
    zoomLevel: Float,
    fitZoom: Float,
): Boolean =
    hasSavedZoom || kotlin.math.abs(zoomLevel - fitZoom) > ROOM_ZOOM_EPSILON

internal fun originalScrollTriggersAt(
    originalPlms: List<RomParser.PlmEntry>,
    x: Int,
    y: Int,
): List<RomParser.PlmEntry> =
    originalPlms.filter { it.id == 0xB703 && it.x == x && it.y == y }

internal fun reusableScrollCommandPtrs(
    originalPlms: List<RomParser.PlmEntry>,
    currentPlms: List<RomParser.PlmEntry>,
): List<Int> =
    (originalPlms + currentPlms)
        .asSequence()
        .filter { it.id == 0xB703 }
        .map { it.param }
        .distinct()
        .sorted()
        .toList()

internal data class DoorTemplateChoice(
    val sourceRoomId: Int,
    val sourceRoomName: String,
    val doorIndex: Int,
    val door: RomParser.DoorEntry,
)

internal fun doorTemplateChoicesForDestination(
    romParser: RomParser,
    rooms: List<RoomInfo>,
    destRoomId: Int,
): List<DoorTemplateChoice> {
    return rooms.flatMap { roomInfo ->
        val sourceRoomId = roomInfo.getRoomIdAsInt()
        val sourceRoom = romParser.readRoomHeader(sourceRoomId) ?: return@flatMap emptyList()
        romParser.parseDoorList(sourceRoom.doorOut).mapIndexedNotNull { doorIndex, door ->
            if (door.destRoomPtr == destRoomId) {
                DoorTemplateChoice(sourceRoomId, roomInfo.name, doorIndex, door)
            } else {
                null
            }
        }
    }
}

internal fun doorWithTemplateValues(
    currentDoor: RomParser.DoorEntry,
    templateDoor: RomParser.DoorEntry,
    crossArea: Boolean,
): RomParser.DoorEntry {
    val templateLowFlagsWithoutCrossArea = templateDoor.bitflag and 0xBF
    val finalLowFlags = if (crossArea) {
        templateLowFlagsWithoutCrossArea or 0x40
    } else {
        templateLowFlagsWithoutCrossArea and 0x40.inv()
    }
    return currentDoor.copy(
        destRoomPtr = templateDoor.destRoomPtr,
        bitflag = ((templateDoor.direction and 0xFF) shl 8) or (finalLowFlags and 0xFF),
        doorCapCode = templateDoor.doorCapCode,
        screenX = templateDoor.screenX,
        screenY = templateDoor.screenY,
        distFromDoor = templateDoor.distFromDoor,
        entryCode = templateDoor.entryCode,
    )
}

/**
 * Shot block (type 0xC) BTS classification.
 * BTS is an index into the PLM table at $94:9EA6. Each entry selects a PLM
 * whose setup routine determines which weapons can break the block.
 *
 * Vanilla SM supports BTS 0x00-0x0B only:
 *   BTS 0x00-0x03: Any weapon breakable (beam/missile/bomb), visible, various sizes
 *   BTS 0x04-0x07: Hidden shot blocks (look solid, any-weapon-breakable when revealed)
 *   BTS 0x08-0x09: Power bomb only (reform / permanent)
 *   BTS 0x0A-0x0B: Super missile only (reform / permanent)
 *   BTS 0x0C-0x0F: Map to PLM $B62F (no-op stub) — NOT functional in vanilla SM.
 *                   Some ROM hacks patch the table to add missile-only blocks here.
 *   BTS 0x40-0x4F: Door cap PLMs (managed by door PLMs, not user-editable)
 */
internal fun mergeDoorBitflagWithMatchedOrientation(
    currentBitflag: Int,
    matchedOrientation: Int?,
    crossArea: Boolean? = null,
): Int {
    val currentLowFlags = currentBitflag and 0xFF
    val lowFlags = when (crossArea) {
        null -> currentLowFlags
        true -> currentLowFlags or 0x40
        false -> currentLowFlags and 0x40.inv()
    }
    val orientation = matchedOrientation ?: ((currentBitflag shr 8) and 0xFF)
    return ((orientation and 0xFF) shl 8) or (lowFlags and 0xFF)
}

/** Shared tile-meta icon: black fill, colored 2px border, centered white letter (matches map). */
@Composable
private fun TileMetaIcon(
    overlay: TileOverlay,
    sizeDp: Dp,
    borderDp: Dp,
    fontSize: TextUnit
) {
    Box(
        modifier = Modifier
            .size(sizeDp)
            .background(Color.Black, RectangleShape)
            .border(borderDp, Color(overlay.color.toInt()), RectangleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = overlay.shortLabel,
            fontSize = fontSize,
            lineHeight = fontSize,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MapCanvas(
    room: RoomInfo?,
    romParser: RomParser?,
    editorState: EditorState? = null,
    rooms: List<RoomInfo> = emptyList(),
    samusPosition: Pair<Float, Float>? = null,
    emulatorConnected: Boolean = false,
    onMoveSamusHere: ((x: Int, y: Int) -> Unit)? = null,
    onRoomSelected: ((RoomInfo) -> Unit)? = null,
    roomKeyboardNavigationEnabled: Boolean = true,
    showItemNames: Boolean = true,
    showEnemyNames: Boolean = true,
    showFlatSlopeSurfaces: Boolean = true,
    modifier: Modifier = Modifier
) {
    val zoomState = remember { mutableStateOf(1f) }
    val zoomLevel = zoomState.value
    AttachMacPinchZoom(LocalSwingWindow.current, zoomState, minZoom = ROOM_MIN_ZOOM, maxZoom = ROOM_MAX_ZOOM)
    var zoomInitializedRoomId by remember { mutableStateOf<Int?>(null) }
    var showGrid by remember { mutableStateOf(true) }
    var showShortChargeRuler by remember { mutableStateOf(false) }
    var shortChargeStutters by remember { mutableStateOf(0) }
    var shortChargeTaps by remember { mutableStateOf(0) }
    var tileMetaExpanded by remember { mutableStateOf(false) }
    val overlayToggles = remember { mutableStateMapOf<TileOverlay, Boolean>(
        TileOverlay.ITEMS to true,
        TileOverlay.ENEMIES to true,
        TileOverlay.LAYER2 to true,
        TileOverlay.LIQUID to true,
    ) }
    val overlayCount = overlayToggles.values.count { it }

    val mapFocusReq = remember { FocusRequester() }
    Card(
        modifier = modifier,
        shape = RectangleShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()
            .focusRequester(mapFocusReq)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.key) {
                    Key.Equals -> {
                        zoomState.value = (zoomLevel * EditorColors.ZOOM_FACTOR).coerceIn(ROOM_MIN_ZOOM, ROOM_MAX_ZOOM)
                        true
                    }
                    Key.Minus -> {
                        zoomState.value = (zoomLevel / EditorColors.ZOOM_FACTOR).coerceIn(ROOM_MIN_ZOOM, ROOM_MAX_ZOOM)
                        true
                    }
                    else -> {
                        val es = editorState ?: return@onPreviewKeyEvent false
                        when (keyEvent.key) {
                            Key.H -> { es.flipOrCaptureH(); true }
                            Key.C -> {
                                if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                    es.copyMapSelectionToBrush(); true
                                } else false
                            }
                            Key.V -> {
                                if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                    val bx = es.hoverBlockX.takeIf { it >= 0 } ?: es.floatingSelection?.x ?: 0
                                    val by = es.hoverBlockY.takeIf { it >= 0 } ?: es.floatingSelection?.y ?: 0
                                    es.beginFloatingSelectionFromBrushAt(bx, by); true
                                } else {
                                    es.flipOrCaptureV(); true
                                }
                            }
                            Key.R -> { es.rotateOrCapture(); true }
                            Key.DirectionUp -> {
                                if (es.floatingSelection != null || (es.mapSelStart != null && es.mapSelEnd != null)) {
                                    val step = if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) 16 else 1
                                    es.shiftSelection(0, -step); true
                                } else if (roomKeyboardNavigationEnabled && onRoomSelected != null && rooms.isNotEmpty()) {
                                    val currentIdx = rooms.indexOfFirst { it.handle == room?.handle }
                                    val newIdx = if (currentIdx > 0) currentIdx - 1 else rooms.lastIndex
                                    if (newIdx in rooms.indices) onRoomSelected(rooms[newIdx])
                                    true
                                } else false
                            }
                            Key.DirectionDown -> {
                                if (es.floatingSelection != null || (es.mapSelStart != null && es.mapSelEnd != null)) {
                                    val step = if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) 16 else 1
                                    es.shiftSelection(0, step); true
                                } else if (roomKeyboardNavigationEnabled && onRoomSelected != null && rooms.isNotEmpty()) {
                                    val currentIdx = rooms.indexOfFirst { it.handle == room?.handle }
                                    val newIdx = if (currentIdx < rooms.lastIndex) currentIdx + 1 else 0
                                    if (newIdx in rooms.indices) onRoomSelected(rooms[newIdx])
                                    true
                                } else false
                            }
                            Key.DirectionLeft -> {
                                if (es.floatingSelection != null || (es.mapSelStart != null && es.mapSelEnd != null)) {
                                    val step = if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) 16 else 1
                                    es.shiftSelection(-step, 0); true
                                } else false
                            }
                            Key.DirectionRight -> {
                                if (es.floatingSelection != null || (es.mapSelStart != null && es.mapSelEnd != null)) {
                                    val step = if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) 16 else 1
                                    es.shiftSelection(step, 0); true
                                } else false
                            }
                            Key.Z -> {
                                if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                    if (keyEvent.isShiftPressed) es.redo() else es.undo()
                                    true
                                } else false
                            }
                            Key.Y -> {
                                if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                    es.redo(); true
                                } else false
                            }
                            Key.S -> {
                                if (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) {
                                    es.saveProject(romParser); true
                                } else {
                                    es.activeTool = EditorTool.SELECT; true
                                }
                            }
                            Key.P -> {
                                if (es.mapSelStart != null && es.mapSelEnd != null) {
                                    es.beginFloatingSelectionFromMapSelection()
                                } else {
                                    es.cancelFloatingSelection()
                                    es.activeTool = EditorTool.PAINT
                                }; true
                            }
                            Key.F -> { es.cancelFloatingSelection(); es.activeTool = EditorTool.FILL; true }
                            Key.E -> { es.cancelFloatingSelection(); es.activeTool = EditorTool.ERASE; true }
                            Key.I -> { es.cancelFloatingSelection(); es.activeTool = EditorTool.SAMPLE; true }
                            Key.Enter -> {
                                if (es.floatingSelection != null) {
                                    es.commitFloatingSelection(); true
                                } else if (es.activeTool == EditorTool.SELECT && es.mapSelStart != null) {
                                    es.beginFloatingSelectionFromMapSelection(); true
                                } else false
                            }
                            Key.Escape -> {
                                if (es.cancelFloatingSelection()) {
                                    true
                                } else if (es.activeTool == EditorTool.SELECT && es.mapSelStart != null) {
                                    es.mapSelStart = null; es.mapSelEnd = null; true
                                } else false
                            }
                            else -> false
                        }
                    }
                }
            }
        ) {
            if (room != null && romParser != null) {
                val rv = editorState?.romVersion ?: 0
                var isLoading by remember(room.id, romParser, rv) { mutableStateOf(true) }
                var errorMessage by remember(room.id, romParser, rv) { mutableStateOf<String?>(null) }
                // Include working dimensions in keys so resize triggers a full re-render
                val dimKey = (editorState?.workingBlocksWide ?: 0) to (editorState?.workingBlocksTall ?: 0)
                var renderData by remember(room.id, romParser, rv, dimKey) { mutableStateOf<RoomRenderData?>(null) }

                LaunchedEffect(room.id, romParser, rv, dimKey) {
                    isLoading = true
                    errorMessage = null
                    renderData = null
                    try {
                        val roomId = room.getRoomIdAsInt()
                        val romHeader = romParser.readRoomHeader(roomId)
                        if (romHeader != null) {
                            // Apply any project header changes (e.g. resize) before loading/rendering
                            val roomHeader = editorState?.applyHeaderChanges(romHeader) ?: romHeader
                            // Load working level data for editing
                            editorState?.loadRoom(roomId, romParser, romHeader)
                            // Render using effective dimensions and resized level data
                            val es = editorState
                            renderData = if (es?.workingLevelData != null) {
                                MapRenderer(romParser, es.tileGraphics).renderRoomFromLevelData(
                                    roomHeader, es.workingLevelData!!, es.workingPlms, es.workingEnemies)
                            } else {
                                MapRenderer(romParser).renderRoom(roomHeader)
                            }
                            if (renderData == null) errorMessage = "Failed to render"
                        } else {
                            errorMessage = "Room header not found"
                        }
                    } catch (e: Exception) {
                        errorMessage = "Error: ${e.message}"
                        mapCanvasLog.error(e) { "Room render/load failed: ${e.message}" }
                    } finally {
                        isLoading = false
                    }
                }

                val editVersion = editorState?.editVersion ?: 0

                // ─── Compact toolbar ─────────────────────────────
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Zoom
                    Text("${(zoomLevel * 100).toInt()}%", fontSize = 10.sp,
                        modifier = Modifier.width(32.dp).alignByBaseline())
                    Slider(
                        value = zoomLevel,
                        onValueChange = { zoomState.value = it; mapFocusReq.requestFocus() },
                        valueRange = ROOM_MIN_ZOOM..ROOM_MAX_ZOOM,
                        steps = 14,
                        modifier = Modifier.width(80.dp).height(28.dp)
                    )

                    // Grid toggle
                    FilterChip(
                        selected = showGrid,
                        onClick = { showGrid = !showGrid; mapFocusReq.requestFocus() },
                        label = { Text("Grid", fontSize = 9.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    // Short Charge Ruler toggle
                    FilterChip(
                        selected = showShortChargeRuler,
                        onClick = { showShortChargeRuler = !showShortChargeRuler; mapFocusReq.requestFocus() },
                        label = { Text("Ruler", fontSize = 9.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    if (showShortChargeRuler) {
                        StutterSelector(
                            selectedStutters = shortChargeStutters,
                            onStuttersChanged = { shortChargeStutters = it; mapFocusReq.requestFocus() }
                        )
                        TapSelector(
                            selectedTaps = shortChargeTaps,
                            onTapsChanged = { shortChargeTaps = it; mapFocusReq.requestFocus() }
                        )
                    }

                    Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                    // Tile Meta multi-select dropdown (trigger: icon like map + label, same color as map square)
                    val firstOverlay = overlayToggles.entries.firstOrNull { it.value }?.key
                    val triggerBg = firstOverlay?.let { Color(it.color.toInt()) } ?: MaterialTheme.colorScheme.surfaceVariant
                    val triggerFg = if (overlayCount > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    Box {
                        Surface(
                            modifier = Modifier
                                .height(28.dp)
                                .clickable { tileMetaExpanded = true },
                            shape = MaterialTheme.shapes.small,
                            color = triggerBg
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (firstOverlay != null) {
                                    TileMetaIcon(overlay = firstOverlay, sizeDp = 16.dp, borderDp = 2.dp, fontSize = 9.sp)
                                }
                                Text(
                                    text = if (overlayCount > 0) "Tile Meta ($overlayCount)" else "Tile Meta",
                                    fontSize = 12.sp,
                                    color = triggerFg
                                )
                                Text(
                                    text = "▼",
                                    fontSize = 8.sp,
                                    color = triggerFg.copy(alpha = 0.8f)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = tileMetaExpanded,
                            onDismissRequest = { tileMetaExpanded = false; mapFocusReq.requestFocus() }
                        ) {
                            TileOverlay.values().forEach { overlay ->
                                val isOn = overlayToggles[overlay] ?: false
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Checkbox(
                                                checked = isOn,
                                                onCheckedChange = null
                                            )
                                            TileMetaIcon(overlay = overlay, sizeDp = 14.dp, borderDp = 2.dp, fontSize = 7.sp)
                                            Text(overlay.label, fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        overlayToggles[overlay] = !isOn
                                    }
                                )
                            }
                        }
                    }

                    // ─── Editor tools ─────────────────────────────
                    if (editorState != null) {
                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                        val canEditLayer2 = editorState.canEditEmbeddedLayer2()
                        FilterChip(
                            selected = editorState.activeRoomLayer == RoomEditLayer.LAYER1,
                            onClick = {
                                editorState.activeRoomLayer = RoomEditLayer.LAYER1
                                mapFocusReq.requestFocus()
                            },
                            label = { Text("L1", fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = editorState.activeRoomLayer == RoomEditLayer.LAYER2,
                            enabled = canEditLayer2,
                            onClick = {
                                if (canEditLayer2) editorState.activeRoomLayer = RoomEditLayer.LAYER2
                                mapFocusReq.requestFocus()
                            },
                            label = { Text("L2", fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                        // Tool selection with icons
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.SELECT,
                            onClick = { editorState.activeTool = EditorTool.SELECT; mapFocusReq.requestFocus() },
                            label = { Icon(Icons.Default.SelectAll, contentDescription = "Select (Q)", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.PAINT,
                            onClick = {
                                if (editorState.mapSelStart != null && editorState.mapSelEnd != null) {
                                    editorState.beginFloatingSelectionFromMapSelection()
                                } else {
                                    editorState.cancelFloatingSelection()
                                    editorState.activeTool = EditorTool.PAINT
                                }
                                mapFocusReq.requestFocus()
                            },
                            label = { Icon(Icons.Default.Brush, contentDescription = "Paint (P)", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.FILL,
                            onClick = { editorState.cancelFloatingSelection(); editorState.activeTool = EditorTool.FILL; mapFocusReq.requestFocus() },
                            label = { Icon(Icons.Default.FormatColorFill, contentDescription = "Fill (G)", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.ERASE,
                            onClick = { editorState.cancelFloatingSelection(); editorState.activeTool = EditorTool.ERASE; mapFocusReq.requestFocus() },
                            label = { Icon(Icons.Outlined.Delete, contentDescription = "Erase (E)", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = editorState.activeTool == EditorTool.SAMPLE,
                            onClick = { editorState.cancelFloatingSelection(); editorState.activeTool = EditorTool.SAMPLE; mapFocusReq.requestFocus() },
                            label = { Icon(Icons.Default.Colorize, contentDescription = "Sample (I)", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )

                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                        // Undo / Redo with icons
                        @Suppress("UNUSED_VARIABLE") val uv = editorState.undoVersion
                        IconButton(
                            onClick = { editorState.undo(); mapFocusReq.requestFocus() },
                            enabled = editorState.undoStack.isNotEmpty(),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.undoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        IconButton(
                            onClick = { editorState.redo(); mapFocusReq.requestFocus() },
                            enabled = editorState.redoStack.isNotEmpty(),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Redo, contentDescription = "Redo",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.redoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }


                        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                        // Flip / Rotate buttons
                        val canTransformBrushOrSelection = editorState.brush != null ||
                            (editorState.mapSelStart != null && editorState.mapSelEnd != null)
                        IconButton(
                            onClick = { editorState.flipOrCaptureH(); mapFocusReq.requestFocus() },
                            enabled = canTransformBrushOrSelection,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Flip, contentDescription = "H-Flip (H)",
                                modifier = Modifier.size(16.dp),
                                tint = if (editorState.brush?.hFlip == true) MaterialTheme.colorScheme.primary
                                       else if (canTransformBrushOrSelection) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        IconButton(
                            onClick = { editorState.flipOrCaptureV(); mapFocusReq.requestFocus() },
                            enabled = canTransformBrushOrSelection,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Flip, contentDescription = "V-Flip (V)",
                                modifier = Modifier.size(16.dp).graphicsLayer(rotationZ = 90f),
                                tint = if (editorState.brush?.vFlip == true) MaterialTheme.colorScheme.primary
                                       else if (canTransformBrushOrSelection) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        IconButton(
                            onClick = { editorState.rotateOrCapture(); mapFocusReq.requestFocus() },
                            enabled = canTransformBrushOrSelection,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Rotate (R)",
                                modifier = Modifier.size(16.dp),
                                tint = if (canTransformBrushOrSelection) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }

                        // ─── Save Picture ────────────────────────────────
                        if (renderData != null) {
                            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                            val coroutineScopeForSave = rememberCoroutineScope()
                            var exportMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(
                                    onClick = { exportMenuExpanded = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.SaveAlt,
                                        contentDescription = "Export room",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(
                                    expanded = exportMenuExpanded,
                                    onDismissRequest = { exportMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Export as PNG", fontSize = 12.sp) },
                                        onClick = {
                                            exportMenuExpanded = false
                                            coroutineScopeForSave.launch {
                                                val roomName = room?.name?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: "room"
                                                val chooser = javax.swing.JFileChooser().apply {
                                                    dialogTitle = "Save Map as PNG"
                                                    selectedFile = java.io.File("$roomName.png")
                                                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PNG Image", "png")
                                                }
                                                if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                                    var file = chooser.selectedFile
                                                    if (!file.name.endsWith(".png", ignoreCase = true)) file = java.io.File(file.path + ".png")
                                                    val rd = renderData!!
                                                    val es = editorState
                                                    val romRh = room?.let { romParser.readRoomHeader(it.getRoomIdAsInt()) }
                                                    val rh = if (romRh != null && es != null) es.applyHeaderChanges(romRh) else romRh
                                                    val rWidthScreens = rh?.width ?: 0
                                                    val rHeightScreens = rh?.height ?: 0
                                                    val scrollDataForSave = es?.workingScrolls ?: rh?.let { romParser.parseScrollData(it.roomScrollsPtr, it.width, it.height) }
                                                    val activeOvs = overlayToggles.filter { it.value }.keys
                                                    val img = if (es != null && es.workingLevelData != null && rh != null) {
                                                        val edited = MapRenderer(romParser, es.tileGraphics).renderRoomFromLevelData(rh, es.workingLevelData!!, es.workingPlms, es.workingEnemies)
                                                        if (edited != null) buildCompositeImage(edited, activeOvs, showGrid, scrollDataForSave, rWidthScreens, rHeightScreens,
                                                            showFlatSlopeSurfaces = showFlatSlopeSurfaces)
                                                        else buildCompositeImage(rd, activeOvs, showGrid, scrollDataForSave, rWidthScreens, rHeightScreens,
                                                            showFlatSlopeSurfaces = showFlatSlopeSurfaces)
                                                    } else {
                                                        buildCompositeImage(rd, activeOvs, showGrid, scrollDataForSave, rWidthScreens, rHeightScreens,
                                                            showFlatSlopeSurfaces = showFlatSlopeSurfaces)
                                                    }
                                                    ImageIO.write(img, "PNG", file)
                                                }
                                            }
                                            mapFocusReq.requestFocus()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export as JSON", fontSize = 12.sp) },
                                        onClick = {
                                            exportMenuExpanded = false
                                            if (editorState != null && romParser != null && room != null) {
                                                try {
                                                    val rid = room.getRoomIdAsInt()
                                                    val json = editorState.exportRoomToJson(rid, romParser)
                                                    val roomHex = rid.toString(16).uppercase().padStart(4, '0')
                                                    val defaultName = "${room.name.replace(" ", "_")}_$roomHex.json"
                                                    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export Room JSON", java.awt.FileDialog.SAVE)
                                                    dialog.file = defaultName
                                                    dialog.isVisible = true
                                                    val dir = dialog.directory; val file = dialog.file
                                                    if (dir != null && file != null) {
                                                        java.io.File(dir, file).writeText(json)
                                                        mapCanvasLogLine("Exported room to: $dir$file")
                                                    }
                                                } catch (ex: Exception) {
                                                    mapCanvasLogLine("Room export failed: ${ex.message}")
                                                }
                                            }
                                            mapFocusReq.requestFocus()
                                        }
                                    )
                                }
                            }
                        }
                    }

                }

                // ─── Map Display ─────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(EditorColors.romBackground),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Loading...", color = Color.White)
                            }
                        }
                        errorMessage != null -> {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        }
                        renderData != null -> {
                            val data = renderData!!
                            val currentRoomId = room.getRoomIdAsInt()
                            // Use effective dimensions from EditorState (updates immediately on resize)
                            val effectiveBlocksWide = editorState?.workingBlocksWide ?: data.blocksWide
                            val effectiveBlocksTall = editorState?.workingBlocksTall ?: data.blocksTall
                            val activeOverlays = (
                                overlayToggles.filter { it.value }.keys +
                                    if (editorState?.activeRoomLayer == RoomEditLayer.LAYER2) setOf(TileOverlay.LAYER2) else emptySet()
                                ).toSet()
                            val customItems = remember(editorState?.patchVersion, editorState?.project?.patches) {
                                editorState?.enabledCustomItems().orEmpty()
                            }

                            val roomHeader = remember(room, editVersion) {
                                room?.let { r ->
                                    val rh = romParser.readRoomHeader(r.getRoomIdAsInt()) ?: return@let null
                                    editorState?.applyHeaderChanges(rh) ?: rh
                                }
                            }
                            val scrollVer = editorState?.scrollVersion ?: 0
                            val scrollDataForOverlay = remember(scrollVer, roomHeader) {
                                val ws = editorState?.workingScrolls
                                if (ws != null && ws.isNotEmpty()) ws.copyOf()
                                else roomHeader?.let { rh -> romParser.parseScrollData(rh.roomScrollsPtr, rh.width, rh.height) }
                            }
                            val rWidthScreens = roomHeader?.width ?: 0
                            val rHeightScreens = roomHeader?.height ?: 0
                            val activeRoomArea = editorState?.activeRoomAreaForEditing() ?: roomHeader?.area ?: 0
                            val saveSpawnMarkers = remember(roomHeader, editVersion, activeRoomArea) {
                                val es = editorState
                                val rh = roomHeader
                                if (es == null || rh == null) emptyList()
                                else es.workingPlms
                                    .filter { it.id == 0xB76F }
                                    .mapNotNull { plm ->
                                        val saveIndex = plm.param and 0xFF
                                        val spawn = es.effectiveSaveStationSpawn(activeRoomArea, saveIndex, romParser) ?: return@mapNotNull null
                                        SaveSpawnMarker(
                                            x = spawn.scrollX + spawn.samusXSigned + 112,
                                            y = spawn.scrollY + spawn.samusYSigned + 24,
                                            label = "S$saveIndex",
                                            source = spawn.source,
                                        )
                                    }
                            }

                            // Layer 3 FX overlay data + fxType for animation
                            val layer3Info = remember(roomHeader, activeOverlays.contains(TileOverlay.LAYER3)) {
                                if (!activeOverlays.contains(TileOverlay.LAYER3) || roomHeader == null) null
                                else {
                                    val fxEntries = romParser.parseFxEntries(roomHeader.fxPtr)
                                    val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
                                    val fxType = defaultFx?.fxType ?: 0
                                    if (fxType == 0) null
                                    else {
                                        val pixels = romParser.renderLayer3Image(fxType, layer3Palette(fxType))
                                        if (pixels != null) Triple(pixels, fxType, defaultFx) else null
                                    }
                                }
                            }
                            val layer3Data = layer3Info?.first

                            // Layer 2 BG overlay data
                            val layer2Data = remember(roomHeader, activeOverlays.contains(TileOverlay.LAYER2), editVersion) {
                                if (!activeOverlays.contains(TileOverlay.LAYER2) || roomHeader == null || romParser == null) null
                                else {
                                    val renderer = MapRenderer(romParser, editorState?.tileGraphics)
                                    renderer.renderLayer2(roomHeader, editorState?.workingLevelData)
                                }
                            }

                            val compositeImage = remember(data, activeOverlays.toSet(), showGrid, scrollDataForOverlay?.contentHashCode(), layer2Data?.contentHashCode(), customItems, showItemNames, showEnemyNames, showFlatSlopeSurfaces) {
                                buildCompositeImage(data, activeOverlays, showGrid, scrollDataForOverlay, rWidthScreens, rHeightScreens,
                                    layer2Pixels = layer2Data, customItems = customItems, showItemNames = showItemNames,
                                    showEnemyNames = showEnemyNames, showFlatSlopeSurfaces = showFlatSlopeSurfaces)
                            }

                            val hScrollState = rememberScrollState()
                            val vScrollState = rememberScrollState()
                            val coroutineScope = rememberCoroutineScope()
                            var canvasViewW by remember { mutableStateOf(0) }
                            var canvasViewH by remember { mutableStateOf(0) }
                            val fitZoom = fitRoomZoomForViewport(
                                viewportWidthPx = canvasViewW,
                                viewportHeightPx = canvasViewH,
                                density = LocalDensity.current.density,
                                contentWidthPx = data.width,
                                contentHeightPx = data.height,
                            )
                            LaunchedEffect(currentRoomId, fitZoom, data.width, data.height) {
                                val targetFit = fitZoom ?: return@LaunchedEffect
                                val savedZoom = inMemoryRoomZooms[currentRoomId]
                                val switchingRooms = zoomInitializedRoomId != currentRoomId
                                zoomState.value = (savedZoom ?: targetFit).coerceIn(ROOM_MIN_ZOOM, ROOM_MAX_ZOOM)
                                zoomInitializedRoomId = currentRoomId
                                if (switchingRooms || savedZoom == null) {
                                    hScrollState.scrollTo(0)
                                    vScrollState.scrollTo(0)
                                }
                            }
                            LaunchedEffect(currentRoomId, zoomLevel, fitZoom, zoomInitializedRoomId) {
                                val targetFit = fitZoom ?: return@LaunchedEffect
                                if (zoomInitializedRoomId != currentRoomId) return@LaunchedEffect
                                val hasSavedZoom = inMemoryRoomZooms.containsKey(currentRoomId)
                                if (shouldSaveRoomZoom(hasSavedZoom, zoomLevel, targetFit)) {
                                    inMemoryRoomZooms[currentRoomId] = zoomLevel.coerceIn(ROOM_MIN_ZOOM, ROOM_MAX_ZOOM)
                                }
                            }
                            var isDragging by remember { mutableStateOf(false) }
                            var lastDragX by remember { mutableStateOf(0f) }
                            var lastDragY by remember { mutableStateOf(0f) }

                            var isPainting by remember { mutableStateOf(false) }

                            // Right-click properties popup state
                            var propsBlockX by remember { mutableStateOf(-1) }
                            var propsBlockY by remember { mutableStateOf(-1) }
                            var propsExpanded by remember { mutableStateOf(false) }
                            var propsBlockType by remember { mutableStateOf(0) }
                            var propsBts by remember { mutableStateOf(0) }
                            var propsMetatile by remember { mutableStateOf(0) }

                            // Right-click context menu state
                            var contextMenuExpanded by remember { mutableStateOf(false) }
                            var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
                            var showSavePatternDialog by remember { mutableStateOf(false) }
                            val density = LocalDensity.current.density

                            fun pointerToBlock(posX: Float, posY: Float): Pair<Int, Int> {
                                // Pointer & scroll are in physical pixels; layout uses dp.
                                // Tile display size in pointer units = 16 * zoom * density.
                                val tilePx = 16f * zoomLevel * density
                                return Pair(
                                    ((posX + hScrollState.value) / tilePx).toInt(),
                                    ((posY + vScrollState.value) / tilePx).toInt()
                                )
                            }

                            // Re-render from working data (reacts to editVersion from EditorState)
                            val compositeForEdit = remember(data, editVersion, activeOverlays.toSet(), showGrid, scrollDataForOverlay?.contentHashCode(), scrollVer, layer2Data?.contentHashCode(), customItems, showItemNames, showEnemyNames, showFlatSlopeSurfaces) {
                                val es = editorState
                                if (es != null && es.workingLevelData != null) {
                                    val rh = roomHeader
                                    if (rh != null) {
                                        val r = MapRenderer(romParser, es.tileGraphics).renderRoomFromLevelData(rh, es.workingLevelData!!, es.workingPlms, es.workingEnemies)
                                        if (r != null) return@remember buildCompositeImage(r, activeOverlays, showGrid, scrollDataForOverlay, rWidthScreens, rHeightScreens,
                                            layer2Pixels = layer2Data, customItems = customItems, showItemNames = showItemNames,
                                            showEnemyNames = showEnemyNames, showFlatSlopeSurfaces = showFlatSlopeSurfaces)
                                    }
                                }
                                compositeImage
                            }
                            val editBitmap = remember(compositeForEdit) { compositeForEdit.toComposeImageBitmap() }

                            LaunchedEffect(Unit) { mapFocusReq.requestFocus() }
                            val scrollTargetX = editorState?.scrollTargetBlockX ?: -1
                            val scrollTargetY = editorState?.scrollTargetBlockY ?: -1
                            LaunchedEffect(scrollTargetX, scrollTargetY) {
                                if (scrollTargetX >= 0 && scrollTargetY >= 0 && editorState != null) {
                                    val tilePx = 16f * zoomLevel * density
                                    val targetPxX = (scrollTargetX * tilePx).toInt()
                                    val targetPxY = (scrollTargetY * tilePx).toInt()
                                    hScrollState.scrollTo((targetPxX - canvasViewW / 2).coerceIn(0, hScrollState.maxValue))
                                    vScrollState.scrollTo((targetPxY - canvasViewH / 2).coerceIn(0, vScrollState.maxValue))
                                    editorState.scrollTargetBlockX = -1
                                    editorState.scrollTargetBlockY = -1
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onSizeChanged { canvasViewW = it.width; canvasViewH = it.height }
                                    .then(
                                        if (editorState != null)
                                            Modifier.pointerHoverIcon(PixelEditorCursors.forEditorTool(editorState.activeTool))
                                        else Modifier
                                    )
                                    .onPointerEvent(PointerEventType.Scroll) { event ->
                                        val ne = event.nativeEvent as? MouseEvent
                                        val isZoom = isZoomModifierPressed(event.nativeEvent)
                                        val sd = event.changes.first().scrollDelta
                                        if (isZoom) {
                                            val mousePos = event.changes.first().position
                                            val contentXBefore = (hScrollState.value + mousePos.x) / zoomLevel
                                            val contentYBefore = (vScrollState.value + mousePos.y) / zoomLevel
                                            val newZoom = zoomAfterScroll(
                                                zoomLevel,
                                                sd.y,
                                                minZoom = ROOM_MIN_ZOOM,
                                                maxZoom = ROOM_MAX_ZOOM,
                                            )
                                            zoomState.value = newZoom
                                            coroutineScope.launch {
                                                val newScrollX = (contentXBefore * newZoom - mousePos.x).toInt().coerceAtLeast(0)
                                                val newScrollY = (contentYBefore * newZoom - mousePos.y).toInt().coerceAtLeast(0)
                                                hScrollState.scrollTo(newScrollX)
                                                vScrollState.scrollTo(newScrollY)
                                            }
                                        } else coroutineScope.launch {
                                            val pan = resolvePanScrollDelta(
                                                rawX = sd.x,
                                                rawY = sd.y,
                                                shiftPressed = ne?.isShiftDown == true
                                            )
                                            hScrollState.scrollTo((hScrollState.value + pan.x).toInt().coerceIn(0, hScrollState.maxValue))
                                            vScrollState.scrollTo((vScrollState.value + pan.y).toInt().coerceIn(0, vScrollState.maxValue))
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Press) { event ->
                                        mapFocusReq.requestFocus()
                                        val ne = event.nativeEvent as? MouseEvent
                                        if (ne != null && ne.button == MouseEvent.BUTTON2) {
                                            isDragging = true; val p = event.changes.first().position; lastDragX = p.x; lastDragY = p.y
                                        } else if (ne != null && ne.button == MouseEvent.BUTTON3 && editorState != null) {
                                            val pos = event.changes.first().position
                                            val (bx, by) = pointerToBlock(pos.x, pos.y)
                                            val ss = editorState.mapSelStart
                                            val se = editorState.mapSelEnd
                                            val hasMultiSel = ss != null && se != null &&
                                                (kotlin.math.abs(ss.first - se.first) > 0 || kotlin.math.abs(ss.second - se.second) > 0)

                                            if (hasMultiSel && editorState.activeRoomLayer == RoomEditLayer.LAYER1) {
                                                contextMenuOffset = DpOffset((pos.x / density).dp, (pos.y / density).dp)
                                                contextMenuExpanded = true
                                            } else if (editorState.activeRoomLayer == RoomEditLayer.LAYER1) {
                                                if (bx in 0 until effectiveBlocksWide && by in 0 until effectiveBlocksTall) {
                                                    val word = editorState.readBlockWord(bx, by)
                                                    propsBlockX = bx; propsBlockY = by
                                                    propsMetatile = word and 0x3FF
                                                    propsBlockType = (word shr 12) and 0xF
                                                    propsBts = editorState.readBts(bx, by)
                                                    propsExpanded = true
                                                }
                                            }
                                        } else if (ne != null && ne.button == MouseEvent.BUTTON1 && editorState != null) {
                                            val (bx, by) = pointerToBlock(event.changes.first().position.x, event.changes.first().position.y)
                                            // Track clicked block for "Move Samus Here" when emulator is live
                                            if (emulatorConnected && bx in 0 until effectiveBlocksWide && by in 0 until effectiveBlocksTall) {
                                            }
                                            // Cmd/Ctrl+click on a door block → navigate to connected room
                                            if ((ne.isMetaDown || ne.isControlDown) && onRoomSelected != null &&
                                                bx in 0 until effectiveBlocksWide && by in 0 until effectiveBlocksTall) {
                                                val word = editorState.readBlockWord(bx, by)
                                                val blockType = (word shr 12) and 0xF
                                                if (blockType == 0x9) {
                                                    val bts = editorState.readBts(bx, by)
                                                    val door = editorState.doorEntries.getOrNull(bts)
                                                    if (door != null) {
                                                        val destRoom = rooms.firstOrNull { it.getRoomIdAsInt() == door.destRoomPtr }
                                                        if (destRoom != null) {
                                                            onRoomSelected(destRoom)
                                                            return@onPointerEvent
                                                        }
                                                    }
                                                }
                                            }
                                            if (ne.isMetaDown || ne.isControlDown) return@onPointerEvent
                                            if (editorState.floatingSelection != null) {
                                                editorState.setFloatingSelectionPosition(bx, by)
                                                editorState.commitFloatingSelection()
                                                return@onPointerEvent
                                            }
                                            when (editorState.activeTool) {
                                                EditorTool.SELECT -> {
                                                    editorState.mapSelStart = Pair(bx, by)
                                                    editorState.mapSelEnd = Pair(bx, by)
                                                    isPainting = true
                                                }
                                                EditorTool.PAINT -> if (editorState.brush != null) {
                                                    isPainting = true; editorState.beginStroke(); editorState.paintAt(bx, by)
                                                }
                                                EditorTool.FILL -> if (editorState.brush != null) {
                                                    editorState.beginStroke(); editorState.floodFill(bx, by); editorState.endStroke()
                                                }
                                                EditorTool.ERASE -> {
                                                    isPainting = true; editorState.beginStroke(); editorState.eraseAt(bx, by)
                                                }
                                                EditorTool.SAMPLE -> {
                                                    editorState.sampleTile(bx, by)
                                                }
                                            }
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Release) { event ->
                                        val ne = event.nativeEvent as? MouseEvent
                                        if (ne == null || ne.button == MouseEvent.BUTTON2) isDragging = false
                                        if (isPainting && editorState?.activeTool == EditorTool.SELECT) {
                                            isPainting = false
                                            val ss = editorState?.mapSelStart
                                            val se = editorState?.mapSelEnd
                                            if (ss != null && se != null && ss == se && editorState != null &&
                                                editorState.activeRoomLayer == RoomEditLayer.LAYER1) {
                                                val bx = ss.first; val by = ss.second
                                                if (bx in 0 until effectiveBlocksWide && by in 0 until effectiveBlocksTall) {
                                                    val word = editorState.readBlockWord(bx, by)
                                                    propsBlockX = bx; propsBlockY = by
                                                    propsMetatile = word and 0x3FF
                                                    propsBlockType = (word shr 12) and 0xF
                                                    propsBts = editorState.readBts(bx, by)
                                                    propsExpanded = true
                                                }
                                            }
                                        } else if (isPainting) {
                                            isPainting = false; editorState?.endStroke()
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Move) { event ->
                                        mapFocusReq.requestFocus()
                                        val pos = event.changes.first().position
                                        if (editorState != null) {
                                            val (bx, by) = pointerToBlock(pos.x, pos.y)
                                            editorState.updateHover(bx, by)
                                            if (!isPainting && !isDragging && editorState.floatingSelection != null) {
                                                editorState.setFloatingSelectionPosition(bx, by)
                                            }
                                        }
                                        if (isDragging) {
                                            val ne = event.nativeEvent as? MouseEvent
                                            if (ne != null && (ne.modifiersEx and java.awt.event.InputEvent.BUTTON2_DOWN_MASK) == 0) {
                                                isDragging = false
                                            }
                                            val dx = lastDragX - pos.x; val dy = lastDragY - pos.y; lastDragX = pos.x; lastDragY = pos.y
                                            coroutineScope.launch {
                                                hScrollState.scrollTo((hScrollState.value + dx.toInt()).coerceIn(0, hScrollState.maxValue))
                                                vScrollState.scrollTo((vScrollState.value + dy.toInt()).coerceIn(0, vScrollState.maxValue))
                                            }
                                        }
                                        if (isPainting && editorState != null) {
                                            val (bx, by) = pointerToBlock(pos.x, pos.y)
                                            when (editorState.activeTool) {
                                                EditorTool.SELECT -> editorState.mapSelEnd = Pair(bx, by)
                                                EditorTool.PAINT -> editorState.paintAt(bx, by)
                                                EditorTool.ERASE -> editorState.eraseAt(bx, by)
                                                else -> {}
                                            }
                                        }
                                    }
                                    .onPointerEvent(PointerEventType.Enter) {
                                        mapFocusReq.requestFocus()
                                    }
                                    .onPointerEvent(PointerEventType.Exit) {
                                        isDragging = false
                                        if (editorState != null) { editorState.hoverBlockX = -1; editorState.hoverBlockY = -1 }
                                    }
                                    .horizontalScroll(hScrollState)
                                    .verticalScroll(vScrollState)
                            ) {
                                // Map image + cursor preview overlay
                                Box {
                                    Image(
                                        bitmap = editBitmap,
                                        contentDescription = room.name,
                                        modifier = Modifier
                                            .requiredWidth((data.width * zoomLevel).dp)
                                            .requiredHeight((data.height * zoomLevel).dp),
                                        contentScale = ContentScale.FillBounds
                                    )
                                    // Animated Layer 3 overlay (fog, rain, spores, heat shimmer)
                                    if (layer3Info != null) {
                                        val l3Pixels = layer3Info.first
                                        val l3FxType = layer3Info.second
                                        val l3SrcW = 256
                                        val l3SrcH = 264
                                        // Use full 256x264 tile — BG3 nametable is 32x33 rows
                                        val l3TileW = 256
                                        val l3TileH = l3SrcH
                                        val l3Bitmap = remember(l3Pixels) {
                                            val img = java.awt.image.BufferedImage(l3SrcW, l3SrcH, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                                            img.setRGB(0, 0, l3SrcW, l3SrcH, l3Pixels, 0, l3SrcW)
                                            img.toComposeImageBitmap()
                                        }
                                        // V-flipped bitmap for water tiling (alternating rows tessellate better)
                                        val l3BitmapFlipped = remember(l3Pixels, l3FxType) {
                                            if (l3FxType != 0x06) null
                                            else {
                                                val img = java.awt.image.BufferedImage(l3SrcW, l3SrcH, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                                                img.setRGB(0, 0, l3SrcW, l3SrcH, l3Pixels, 0, l3SrcW)
                                                val flipped = java.awt.image.BufferedImage(l3SrcW, l3SrcH, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                                                val g2 = flipped.createGraphics()
                                                g2.drawImage(img, 0, l3SrcH, l3SrcW, 0, 0, 0, l3SrcW, l3SrcH, null)
                                                g2.dispose()
                                                flipped.toComposeImageBitmap()
                                            }
                                        }
                                        val l3Scroll = remember(l3FxType) { layer3ScrollSpeed(l3FxType) }
                                        var l3OffsetX by remember { mutableStateOf(0f) }
                                        var l3OffsetY by remember { mutableStateOf(0f) }
                                        if (l3Scroll.first != 0f || l3Scroll.second != 0f) {
                                            LaunchedEffect(l3FxType) {
                                                var frame = 0f
                                                while (true) {
                                                    kotlinx.coroutines.delay(16L)
                                                    frame += 1f
                                                    val dx = when (l3FxType) {
                                                        0x08 -> kotlin.math.sin(frame * 0.02f) * 0.8f // Spores: sinusoidal sway
                                                        else -> l3Scroll.first
                                                    }
                                                    val dy = when (l3FxType) {
                                                        0x06 -> kotlin.math.sin(frame * 0.008f) * 0.15f // Water: very gentle vertical bob
                                                        else -> l3Scroll.second
                                                    }
                                                    l3OffsetX = (l3OffsetX + dx + l3TileW) % l3TileW
                                                    l3OffsetY = (l3OffsetY + dy + l3TileH) % l3TileH
                                                }
                                            }
                                        }
                                        // Standard L3 tiled overlay (rain, fog, spores, lava surface, etc.)
                                        Canvas(
                                                modifier = Modifier
                                                    .requiredWidth((data.width * zoomLevel).dp)
                                                    .requiredHeight((data.height * zoomLevel).dp)
                                                    .graphicsLayer { clip = true }
                                            ) {
                                                val scaleX = size.width / data.width
                                                val scaleY = size.height / data.height
                                                val scaledW = l3TileW * scaleX
                                                val scaledH = l3TileH * scaleY
                                                val ox = (l3OffsetX % l3TileW) * scaleX
                                                val oy = (l3OffsetY % l3TileH) * scaleY
                                                val startX = ox - scaledW
                                                val startY = oy - scaledH
                                                var ty = startY
                                                var rowIdx = 0
                                                while (ty < size.height) {
                                                    var tx = startX
                                                    // For water: alternate V-flipped rows for seamless tessellation
                                                    val bmp = if (l3BitmapFlipped != null && rowIdx % 2 == 1) l3BitmapFlipped else l3Bitmap
                                                    while (tx < size.width) {
                                                        val dstW = kotlin.math.ceil(scaledW).toInt()
                                                        val dstH = kotlin.math.ceil(scaledH).toInt()
                                                        val ix = tx.toInt()
                                                        val iy = ty.toInt()
                                                        if (ix + dstW > 0 && iy + dstH > 0 &&
                                                            ix < size.width.toInt() && iy < size.height.toInt()) {
                                                            drawImage(
                                                                image = bmp,
                                                                dstOffset = androidx.compose.ui.unit.IntOffset(ix, iy),
                                                                dstSize = androidx.compose.ui.unit.IntSize(dstW, dstH),
                                                                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                                                            )
                                                        }
                                                        tx += scaledW
                                                    }
                                                    ty += scaledH
                                                    rowIdx++
                                                }
                                            }
                                        // TODO: Heat shimmer (fxBitC 0x20/0x40) = per-scanline HDMA warp of base image
                                        // This is a BG warp effect on L1/L2, not an L3 overlay — requires pixel-level displacement
                                    }
                                    // Liquid level overlay (water/lava/acid)
                                    if (roomHeader != null && overlayToggles[TileOverlay.LIQUID] == true) {
                                        val fxEntries = remember(roomHeader.fxPtr) { romParser.parseFxEntries(roomHeader.fxPtr) }
                                        val defaultFx = fxEntries.lastOrNull { it.doorSelect == 0 }
                                        // Apply editor FX override if present
                                        val editorFxChange = editorState?.project?.rooms?.get(
                                            editorState.project.roomKey(roomHeader.roomId)
                                        )?.fxChange
                                        val effectiveFxType = editorFxChange?.fxType ?: defaultFx?.fxType ?: 0
                                        val effectiveLiquidStart = editorFxChange?.liquidSurfaceStart ?: defaultFx?.liquidSurfaceStart ?: 0xFFFF
                                        if (effectiveLiquidStart != 0xFFFF) {
                                            val liquidY = effectiveLiquidStart
                                            val liquidColor = when (liquidPhysicsIndex(effectiveFxType)) {
                                                1 -> Color(0x44FF4400)     // lava (orange-red) — Varia protects
                                                2 -> Color(0x44CCCC00)    // acid (yellow) — ignores suits
                                                3 -> Color(0x443388FF)     // water (blue) — no damage
                                                else -> Color(0x443388FF)  // unknown — default blue
                                            }
                                            Canvas(
                                                modifier = Modifier
                                                    .requiredWidth((data.width * zoomLevel).dp)
                                                    .requiredHeight((data.height * zoomLevel).dp)
                                            ) {
                                                val scaleY = size.height / data.height
                                                val surfacePixelY = liquidY.toFloat() * scaleY
                                                if (surfacePixelY < size.height) {
                                                    drawRect(
                                                        color = liquidColor,
                                                        topLeft = androidx.compose.ui.geometry.Offset(0f, surfacePixelY),
                                                        size = androidx.compose.ui.geometry.Size(size.width, size.height - surfacePixelY)
                                                    )
                                                    drawLine(
                                                        color = liquidColor.copy(alpha = 0.8f),
                                                        start = androidx.compose.ui.geometry.Offset(0f, surfacePixelY),
                                                        end = androidx.compose.ui.geometry.Offset(size.width, surfacePixelY),
                                                        strokeWidth = 2f
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (activeOverlays.contains(TileOverlay.ITEMS) && saveSpawnMarkers.isNotEmpty()) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val scaleX = size.width / data.width
                                            val scaleY = size.height / data.height
                                            for (marker in saveSpawnMarkers) {
                                                val x = marker.x * scaleX
                                                val y = marker.y * scaleY
                                                if (x < 0f || y < 0f || x > size.width || y > size.height) continue
                                                val color = when (marker.source) {
                                                    "ROM" -> Color(0xFF44CCFF)
                                                    "Auto" -> Color(0xFF66DD88)
                                                    else -> Color(0xFFFFDD44)
                                                }
                                                drawCircle(
                                                    color = Color.Black.copy(alpha = 0.75f),
                                                    radius = 7f,
                                                    center = androidx.compose.ui.geometry.Offset(x, y),
                                                )
                                                drawCircle(
                                                    color = color.copy(alpha = 0.9f),
                                                    radius = 5f,
                                                    center = androidx.compose.ui.geometry.Offset(x, y),
                                                )
                                                drawLine(
                                                    color = Color.White,
                                                    start = androidx.compose.ui.geometry.Offset(x - 9f, y),
                                                    end = androidx.compose.ui.geometry.Offset(x + 9f, y),
                                                    strokeWidth = 1.5f,
                                                )
                                                drawLine(
                                                    color = Color.White,
                                                    start = androidx.compose.ui.geometry.Offset(x, y - 9f),
                                                    end = androidx.compose.ui.geometry.Offset(x, y + 9f),
                                                    strokeWidth = 1.5f,
                                                )
                                            }
                                        }
                                    }
                                    // Ghost preview: render actual tile graphics for paint or floating selection placement.
                                    if (editorState != null && editorState.brush != null &&
                                        ((editorState.activeTool == EditorTool.PAINT && editorState.hoverBlockX >= 0) ||
                                            editorState.floatingSelection != null)) {
                                        val floating = editorState.floatingSelection
                                        val hx = floating?.x ?: editorState.hoverBlockX
                                        val hy = floating?.y ?: editorState.hoverBlockY
                                        val b = editorState.brush!!
                                        val tg = editorState.tileGraphics
                                        // Build a preview image of the brush at the hover position
                                        val previewBitmap = remember(b, tg) {
                                            if (tg == null) null
                                            else {
                                                val pw = b.cols * 16; val ph = b.rows * 16
                                                val img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
                                                val bgFill = 0xFF000000.toInt()
                                                for (r in 0 until b.rows) {
                                                    for (c in 0 until b.cols) {
                                                        val ck = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                                                        if (ck in b.skipCells) continue
                                                        val idx = b.tiles.getOrNull(r)?.getOrNull(c) ?: continue
                                                        val pixels = tg.renderMetatile(idx) ?: continue
                                                        val dc = if (b.hFlip) (b.cols - 1 - c) else c
                                                        val dr = if (b.vFlip) (b.rows - 1 - r) else r
                                                        val effH = b.tileHFlip(r, c)
                                                        val effV = b.tileVFlip(r, c)
                                                        for (ty in 0 until 16) for (tx in 0 until 16) {
                                                            val sx = if (effH) 15 - tx else tx
                                                            val sy = if (effV) 15 - ty else ty
                                                            val argb = pixels[sy * 16 + sx]
                                                            img.setRGB(dc * 16 + tx, dr * 16 + ty, if (argb != 0) argb else bgFill)
                                                        }
                                                    }
                                                }
                                                for (y in 0 until ph) for (x in 0 until pw) {
                                                    val p = img.getRGB(x, y)
                                                    if (p != 0) img.setRGB(x, y, (p and 0x00FFFFFF) or 0x99000000.toInt())
                                                }
                                                img.toComposeImageBitmap()
                                            }
                                        }
                                        if (previewBitmap != null) {
                                            val tileSize = 16f * zoomLevel
                                            val offX = hx * tileSize
                                            val offY = hy * tileSize
                                            Image(
                                                bitmap = previewBitmap,
                                                contentDescription = if (floating != null) "Selection preview" else "Brush preview",
                                                modifier = Modifier
                                                    .offset(x = (offX).dp, y = (offY).dp)
                                                    .requiredWidth((b.cols * 16 * zoomLevel).dp)
                                                    .requiredHeight((b.rows * 16 * zoomLevel).dp),
                                                contentScale = ContentScale.FillBounds
                                            )
                                        }
                                        if (floating != null) {
                                            Canvas(
                                                modifier = Modifier
                                                    .requiredWidth((data.width * zoomLevel).dp)
                                                    .requiredHeight((data.height * zoomLevel).dp)
                                            ) {
                                                val tileW = size.width / data.blocksWide
                                                val tileH = size.height / data.blocksTall
                                                val rx = hx * tileW
                                                val ry = hy * tileH
                                                val rw = b.cols * tileW
                                                val rh = b.rows * tileH
                                                drawRect(
                                                    color = Color.Cyan.copy(alpha = 0.12f),
                                                    topLeft = androidx.compose.ui.geometry.Offset(rx, ry),
                                                    size = androidx.compose.ui.geometry.Size(rw, rh)
                                                )
                                                drawRect(
                                                    color = Color.Cyan.copy(alpha = 0.9f),
                                                    topLeft = androidx.compose.ui.geometry.Offset(rx, ry),
                                                    size = androidx.compose.ui.geometry.Size(rw, rh),
                                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                        width = 2f,
                                                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    // Select mode cursor: crosshair outline
                                    if (editorState != null && editorState.hoverBlockX >= 0 && editorState.activeTool == EditorTool.SELECT
                                        && editorState.mapSelStart == null && editorState.floatingSelection == null) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            drawRect(
                                                color = Color.White.copy(alpha = 0.5f),
                                                topLeft = androidx.compose.ui.geometry.Offset(editorState.hoverBlockX * tileW, editorState.hoverBlockY * tileH),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 1.5f,
                                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
                                                )
                                            )
                                        }
                                    }
                                    // Erase cursor: red X
                                    if (editorState != null && editorState.hoverBlockX >= 0 && editorState.activeTool == EditorTool.ERASE) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            val x0 = editorState.hoverBlockX * tileW
                                            val y0 = editorState.hoverBlockY * tileH
                                            drawRect(
                                                color = Color.Red.copy(alpha = 0.15f),
                                                topLeft = androidx.compose.ui.geometry.Offset(x0, y0),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                            )
                                            drawRect(
                                                color = Color.Red.copy(alpha = 0.6f),
                                                topLeft = androidx.compose.ui.geometry.Offset(x0, y0),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                                            )
                                            val pad = tileW * 0.25f
                                            drawLine(Color.Red.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(x0 + pad, y0 + pad), androidx.compose.ui.geometry.Offset(x0 + tileW - pad, y0 + tileH - pad), strokeWidth = 1.5f)
                                            drawLine(Color.Red.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(x0 + tileW - pad, y0 + pad), androidx.compose.ui.geometry.Offset(x0 + pad, y0 + tileH - pad), strokeWidth = 1.5f)
                                        }
                                    }
                                    // Sample cursor: outline
                                    if (editorState != null && editorState.hoverBlockX >= 0 && editorState.activeTool == EditorTool.SAMPLE) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            drawRect(
                                                color = Color.Cyan.copy(alpha = 0.4f),
                                                topLeft = androidx.compose.ui.geometry.Offset(editorState.hoverBlockX * tileW, editorState.hoverBlockY * tileH),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                                            )
                                        }
                                    }
                                    // Right-click selection border
                                    if (propsExpanded && propsBlockX >= 0) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            drawRect(
                                                color = Color.Yellow.copy(alpha = 0.7f),
                                                topLeft = androidx.compose.ui.geometry.Offset(propsBlockX * tileW, propsBlockY * tileH),
                                                size = androidx.compose.ui.geometry.Size(tileW, tileH),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                                            )
                                        }
                                    }
                                    // Map selection rectangle (dotted-style)
                                    if (editorState != null && editorState.mapSelStart != null && editorState.mapSelEnd != null) {
                                        val sel0 = editorState.mapSelStart!!
                                        val sel1 = editorState.mapSelEnd!!
                                        val minBx = minOf(sel0.first, sel1.first)
                                        val minBy = minOf(sel0.second, sel1.second)
                                        val maxBx = maxOf(sel0.first, sel1.first)
                                        val maxBy = maxOf(sel0.second, sel1.second)
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val tileW = size.width / data.blocksWide
                                            val tileH = size.height / data.blocksTall
                                            val rx = minBx * tileW
                                            val ry = minBy * tileH
                                            val rw = (maxBx - minBx + 1) * tileW
                                            val rh = (maxBy - minBy + 1) * tileH
                                            drawRect(
                                                color = Color.White.copy(alpha = 0.15f),
                                                topLeft = androidx.compose.ui.geometry.Offset(rx, ry),
                                                size = androidx.compose.ui.geometry.Size(rw, rh)
                                            )
                                            drawRect(
                                                color = Color.White.copy(alpha = 0.9f),
                                                topLeft = androidx.compose.ui.geometry.Offset(rx, ry),
                                                size = androidx.compose.ui.geometry.Size(rw, rh),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 2f,
                                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                                                )
                                            )
                                        }
                                    }
                                    // Samus position marker (emulator overlay)
                                    if (samusPosition != null) {
                                        Canvas(
                                            modifier = Modifier
                                                .requiredWidth((data.width * zoomLevel).dp)
                                                .requiredHeight((data.height * zoomLevel).dp)
                                        ) {
                                            val scaleX = size.width / data.width
                                            val scaleY = size.height / data.height
                                            val cx = samusPosition.first * scaleX
                                            val cy = samusPosition.second * scaleY
                                            val markerSize = 12f
                                            // Filled rectangle
                                            drawRect(
                                                color = Color.Green.copy(alpha = 0.35f),
                                                topLeft = androidx.compose.ui.geometry.Offset(cx - markerSize, cy - markerSize),
                                                size = androidx.compose.ui.geometry.Size(markerSize * 2, markerSize * 2),
                                            )
                                            // Border
                                            drawRect(
                                                color = Color.Green.copy(alpha = 0.9f),
                                                topLeft = androidx.compose.ui.geometry.Offset(cx - markerSize, cy - markerSize),
                                                size = androidx.compose.ui.geometry.Size(markerSize * 2, markerSize * 2),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                                            )
                                            // Crosshair lines
                                            drawLine(Color.Green.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(cx - markerSize * 1.5f, cy), androidx.compose.ui.geometry.Offset(cx + markerSize * 1.5f, cy), strokeWidth = 1.5f)
                                            drawLine(Color.Green.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(cx, cy - markerSize * 1.5f), androidx.compose.ui.geometry.Offset(cx, cy + markerSize * 1.5f), strokeWidth = 1.5f)
                                        }
                                    }
                                }
                            }

                            // ─── Right-click context menu (multi-tile selection only) ──────
                            DropdownMenu(
                                expanded = contextMenuExpanded,
                                onDismissRequest = { contextMenuExpanded = false },
                                offset = contextMenuOffset
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Paint", fontSize = 11.sp) },
                                    onClick = {
                                        contextMenuExpanded = false
                                        editorState?.beginFloatingSelectionFromMapSelection()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Save Selection as Pattern", fontSize = 11.sp) },
                                    onClick = {
                                        contextMenuExpanded = false
                                        showSavePatternDialog = true
                                    }
                                )
                            }

                            // Save-as-pattern dialog
                            if (showSavePatternDialog && editorState != null) {
                                var patName by remember { mutableStateOf("") }
                                AlertDialog(
                                    onDismissRequest = { showSavePatternDialog = false },
                                    title = { Text("Save Selection as Pattern", fontSize = 14.sp) },
                                    text = {
                                        AppOutlinedTextField(
                                            value = patName,
                                            onValueChange = { patName = it },
                                            label = "Pattern name",
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            val n = patName.ifBlank { "Selection" }
                                            val pat = editorState.saveSelectionAsPattern(n)
                                            if (pat != null) editorState.loadPatternForEdit(pat.id)
                                            showSavePatternDialog = false
                                        }) { Text("Save") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showSavePatternDialog = false }) { Text("Cancel") }
                                    }
                                )
                            }

                            // ─── Right-click tile properties panel (floating, non-modal) ──────
                            if (propsExpanded && editorState != null) {
                                TilePropertiesPanel(
                                    blockX = propsBlockX,
                                    blockY = propsBlockY,
                                    metatile = propsMetatile,
                                    initialBlockType = propsBlockType,
                                    initialBts = propsBts,
                                    editorState = editorState,
                                    romParser = romParser,
                                    rooms = rooms,
                                    roomHeader = roomHeader,
                                    roomId = room.getRoomIdAsInt(),
                                    emulatorConnected = emulatorConnected,
                                    onMoveSamusHere = onMoveSamusHere,
                                    onDismiss = { propsExpanded = false; mapFocusReq.requestFocus() },
                                    modifier = Modifier.align(Alignment.TopEnd),
                                )
                            }

                            // Short Charge Ruler — floating centered overlay
                            if (showShortChargeRuler) {
                                Box(modifier = Modifier.align(Alignment.Center)) {
                                    ShortChargeRuler(
                                        stutters = shortChargeStutters,
                                        selectedTaps = shortChargeTaps,
                                        zoomLevel = zoomLevel
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (room == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select a room from the list", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Load a ROM file first", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}


private data class SaveSpawnMarker(
    val x: Int,
    val y: Int,
    val label: String,
    val source: String,
)

