package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.awt.event.MouseEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.MinimapTiles
import com.supermetroid.editor.rom.MapStationData
import com.supermetroid.editor.rom.RomParser

/** Tile indices that are background/empty in the SNES tilemap. */
private val EMPTY_TILES = setOf(0x00, 0x1F)
private const val MINIMAP_MIN_CELL_SIZE = 4f
private const val MINIMAP_MAX_CELL_SIZE = 64f
private const val MINIMAP_ZOOM_EPSILON = 0.0005f
private val inMemoryMinimapCellSizes = mutableStateMapOf<Int, Float>()

internal fun fitMinimapCellSizeForViewport(
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    density: Float,
    mapWidthCells: Int,
    mapHeightCells: Int,
    minCellSize: Float = MINIMAP_MIN_CELL_SIZE,
    maxCellSize: Float = MINIMAP_MAX_CELL_SIZE,
): Float? {
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || density <= 0f ||
        mapWidthCells <= 0 || mapHeightCells <= 0
    ) {
        return null
    }
    val viewportWidthDp = viewportWidthPx / density
    val viewportHeightDp = viewportHeightPx / density
    val fitWidth = viewportWidthDp / mapWidthCells
    val fitHeight = viewportHeightDp / mapHeightCells
    return minOf(fitWidth, fitHeight).coerceIn(minCellSize, maxCellSize)
}

internal fun shouldSaveMinimapCellSize(
    hasSavedCellSize: Boolean,
    cellSize: Float,
    fitCellSize: Float,
): Boolean =
    hasSavedCellSize || kotlin.math.abs(cellSize - fitCellSize) > MINIMAP_ZOOM_EPSILON

/** 4bpp pixel value -> Color for each of the 4 minimap preview palettes. */
private val MINIMAP_PALETTES = arrayOf(
    // Palette 0: black/dark
    arrayOf(
        Color(0xFF000008), Color(0xFF101020), Color(0xFF383850), Color(0xFF0C0C18),
        Color(0xFF202030), Color(0xFF2C2C40), Color(0xFF484860), Color(0xFF606078),
        Color(0xFF080810), Color(0xFF181828), Color(0xFF303048), Color(0xFF505068),
        Color(0xFF686880), Color(0xFF808098), Color(0xFFA0A0B8), Color(0xFFC0C0D8),
    ),
    // Palette 1: blue
    arrayOf(
        Color(0xFF000830), Color(0xFF2850A0), Color(0xFF80B0E8), Color(0xFF102858),
        Color(0xFF183C78), Color(0xFF3868B8), Color(0xFF5890D8), Color(0xFFA8D8F8),
        Color(0xFF081840), Color(0xFF204890), Color(0xFF4078C8), Color(0xFF68A0E0),
        Color(0xFF90C0F0), Color(0xFFB8E0FF), Color(0xFFD0ECFF), Color(0xFFE8F8FF),
    ),
    // Palette 2: white/gray
    arrayOf(
        Color(0xFF181820), Color(0xFF808898), Color(0xFFD0D0E8), Color(0xFF404050),
        Color(0xFF585868), Color(0xFF707080), Color(0xFF9898A8), Color(0xFFE8E8F8),
        Color(0xFF202028), Color(0xFF383840), Color(0xFF606070), Color(0xFF9090A0),
        Color(0xFFA8A8B8), Color(0xFFC0C0D0), Color(0xFFF0F0F8), Color(0xFFFFFFFF),
    ),
    // Palette 3: red (explored)
    arrayOf(
        Color(0xFF180008), Color(0xFFA03030), Color(0xFFE08888), Color(0xFF481018),
        Color(0xFF702020), Color(0xFFB84848), Color(0xFFD86868), Color(0xFFFFB0B0),
        Color(0xFF300008), Color(0xFF581010), Color(0xFF882828), Color(0xFFC05050),
        Color(0xFFE07878), Color(0xFFFF9898), Color(0xFFFFC8C8), Color(0xFFFFE8E8),
    ),
)

private fun minimapPixelColor(palette: Int, pixelValue: Int): Color =
    MINIMAP_PALETTES[palette.coerceIn(0, MINIMAP_PALETTES.lastIndex)][pixelValue.coerceIn(0, 15)]

/** Left sidebar for the minimap editor. */
@Composable
fun MinimapSidebar(
    state: MinimapEditorState,
    romParser: RomParser?,
    editorState: EditorState,
    modifier: Modifier = Modifier,
) {
    val parser = romParser ?: return
    state.initIfNeeded(parser, editorState)

    Column(modifier = modifier.fillMaxSize().padding(6.dp).verticalScroll(rememberScrollState())) {
        // Area
        Text("Area", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        for (area in 0 until MinimapData.NUM_AREAS) {
            val sel = area == state.selectedArea
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { state.loadArea(parser, area, editorState) },
                color = if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(3.dp),
            ) {
                Text(MinimapData.AREA_NAMES[area], fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(Modifier.height(4.dp)); Divider(); Spacer(Modifier.height(4.dp))

        // Display toggles
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.showGrid, onCheckedChange = { state.showGrid = it }, modifier = Modifier.size(16.dp))
            Text("Grid", fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.showRoomOutlines, onCheckedChange = { state.showRoomOutlines = it }, modifier = Modifier.size(16.dp))
            Text("Room outlines", fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.showPixelView, onCheckedChange = { state.showPixelView = it }, modifier = Modifier.size(16.dp))
            Text("Tiles", fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.showStationOverlay, onCheckedChange = { state.showStationOverlay = it }, modifier = Modifier.size(16.dp))
            Text("Station reveal", fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
        }

        Spacer(Modifier.height(4.dp)); Divider(); Spacer(Modifier.height(4.dp))

        // Tile palette — fills available width
        Text("Tile", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        MinimapTilePalette(
            selectedTile = state.selectedTile,
            selectedHFlip = state.selectedHFlip,
            selectedVFlip = state.selectedVFlip,
            tileGfx = state.tileGraphics,
            onSelect = { state.selectedTile = it },
        )

        Spacer(Modifier.height(4.dp)); Divider(); Spacer(Modifier.height(4.dp))

        // Palette row
        Text("Palette", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (pal in 0..3) {
                val label = when (pal) { 0 -> "Blk"; 1 -> "Blu"; 2 -> "Wht"; else -> "Red" }
                val sel = pal == state.selectedPalette
                Surface(
                    modifier = Modifier.height(44.dp).weight(1f).padding(top = 2.dp, bottom = 6.dp)
                        .clickable { state.selectedPalette = pal }
                        .border(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(3.dp)),
                    color = if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(3.dp),
                ) { Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 11.sp) } }
            }
        }

        Spacer(Modifier.height(4.dp)); Divider(); Spacer(Modifier.height(4.dp))

        // Room selector (dropdown)
        Text("Room", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        val rooms = state.areaRooms
        val selRoom = state.selectedRoom
        var roomDropdownExpanded by remember { androidx.compose.runtime.mutableStateOf(false) }
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { roomDropdownExpanded = true }
                    .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(3.dp)),
                color = Color.Transparent, shape = RoundedCornerShape(3.dp),
            ) {
                Text(
                    selRoom?.let { "0x${it.roomId.toString(16).uppercase()} ${it.name}" } ?: "Select room...",
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    color = if (selRoom != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = roomDropdownExpanded,
                onDismissRequest = { roomDropdownExpanded = false },
                modifier = Modifier.height(300.dp),
            ) {
                for ((i, room) in rooms.withIndex()) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("0x${room.roomId.toString(16).uppercase()} ${room.name}", fontSize = 10.sp) },
                        onClick = { state.selectRoomByIndex(i, editorState); roomDropdownExpanded = false },
                    )
                }
            }
        }
        // D-pad + position inputs for selected room
        if (selRoom != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("X:", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("${selRoom.mapX}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("Y:", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("${selRoom.mapY}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("${selRoom.width}x${selRoom.height}", fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var areaMenuExpanded by remember(selRoom.roomId) { androidx.compose.runtime.mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(26.dp).clickable { areaMenuExpanded = true },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Area: ${MinimapData.AREA_NAMES[state.selectedArea]}", fontSize = 10.sp)
                        Text("Move… ▾", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = areaMenuExpanded, onDismissRequest = { areaMenuExpanded = false }) {
                    for (area in 0 until MinimapData.NUM_AREAS) {
                        DropdownMenuItem(
                            text = { Text(MinimapData.AREA_NAMES[area], fontSize = 10.sp) },
                            enabled = area != selRoom.area,
                            onClick = {
                                areaMenuExpanded = false
                                state.reassignRoomArea(selRoom.roomId, area, editorState)
                            },
                        )
                    }
                }
            }
            // D-pad: 3x3 grid of arrow buttons
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row {
                        DpadBtn("\u2196") { state.moveRoom(-1, -1, editorState) }
                        DpadBtn("\u2191") { state.moveRoom(0, -1, editorState) }
                        DpadBtn("\u2197") { state.moveRoom(1, -1, editorState) }
                    }
                    Row {
                        DpadBtn("\u2190") { state.moveRoom(-1, 0, editorState) }
                        Box(Modifier.size(28.dp)) // center spacer
                        DpadBtn("\u2192") { state.moveRoom(1, 0, editorState) }
                    }
                    Row {
                        DpadBtn("\u2199") { state.moveRoom(-1, 1, editorState) }
                        DpadBtn("\u2193") { state.moveRoom(0, 1, editorState) }
                        DpadBtn("\u2198") { state.moveRoom(1, 1, editorState) }
                    }
                }
            }
            // Apply / Cancel buttons when moving
            if (state.isMovingRoom) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier.weight(1f).height(28.dp)
                            .clickable { state.applyMove(editorState) }
                            .border(1.dp, Color(0xFF00CC66), RoundedCornerShape(4.dp)),
                        color = Color(0xFF00CC66).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp),
                    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Apply", fontSize = 10.sp, fontWeight = FontWeight.Bold) } }
                    Surface(
                        modifier = Modifier.weight(1f).height(28.dp)
                            .clickable { state.cancelMove(editorState) }
                            .border(1.dp, Color(0xFFCC3333), RoundedCornerShape(4.dp)),
                        color = Color(0xFFCC3333).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp),
                    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Cancel", fontSize = 10.sp, fontWeight = FontWeight.Bold) } }
                }
            }
        }

        Spacer(Modifier.height(4.dp)); Divider(); Spacer(Modifier.height(4.dp))

        // Info
        Text("Info", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        val hx = state.hoverX; val hy = state.hoverY
        if (hx in 0 until MinimapData.MAP_WIDTH && hy in 0 until MinimapData.MAP_HEIGHT) {
            val w = state.displayData.getTile(hx, hy)
            val idx = MinimapData.tileIndex(w); val pal = MinimapData.tilePalette(w)
            val name = MinimapTiles.TILE_NAMES[idx] ?: "0x${idx.toString(16).uppercase()}"
            Text("($hx,$hy) $name", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("Idx:0x${idx.toString(16).uppercase()} Pal:$pal", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            val room = state.areaRooms.firstOrNull { r ->
                hx in r.mapX until (r.mapX + r.width) && hy in r.mapY until (r.mapY + r.height)
            }
            if (room != null) Text(room.name, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
        } else {
            Text("Hover over map", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MinimapToolbar(
    state: MinimapEditorState,
    editorState: EditorState,
    requestFocus: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Area Map — ${MinimapData.AREA_NAMES[state.selectedArea]}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

            MinimapToolChip(Icons.Default.SelectAll, "Select (S)", state.tool == MinimapTool.SELECT) {
                state.activateTool(MinimapTool.SELECT, editorState); requestFocus()
            }
            MinimapToolChip(Icons.Default.Brush, "Paint (P)", state.tool == MinimapTool.PAINT) {
                state.activateTool(MinimapTool.PAINT, editorState); requestFocus()
            }
            MinimapToolChip(Icons.Default.FormatColorFill, "Fill (G)", state.tool == MinimapTool.FILL) {
                state.activateTool(MinimapTool.FILL, editorState); requestFocus()
            }
            MinimapToolChip(Icons.Outlined.Delete, "Erase (E)", state.tool == MinimapTool.ERASE) {
                state.activateTool(MinimapTool.ERASE, editorState); requestFocus()
            }
            MinimapToolChip(Icons.Default.Colorize, "Sample (I)", state.tool == MinimapTool.EYEDROPPER) {
                state.activateTool(MinimapTool.EYEDROPPER, editorState); requestFocus()
            }
            MinimapToolChip(Icons.Default.Map, "Station reveal", state.tool == MinimapTool.REVEAL) {
                state.activateTool(MinimapTool.REVEAL, editorState)
                state.showStationOverlay = true
                requestFocus()
            }

            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
            IconButton(
                onClick = { state.undo(editorState); requestFocus() },
                enabled = state.undoStack.isNotEmpty(),
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Default.Undo, "Undo (Ctrl/Cmd+Z)", Modifier.size(16.dp))
            }
            IconButton(
                onClick = { state.redo(editorState); requestFocus() },
                enabled = state.redoStack.isNotEmpty(),
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Default.Redo, "Redo (Ctrl/Cmd+Y)", Modifier.size(16.dp))
            }

            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
            IconButton(
                onClick = { state.flipSelectedHorizontal(); requestFocus() },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Flip,
                    "Flip selected map tile horizontally (H)",
                    Modifier.size(16.dp),
                    tint = if (state.selectedHFlip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { state.flipSelectedVertical(); requestFocus() },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Flip,
                    "Flip selected map tile vertically (V)",
                    Modifier.size(16.dp).graphicsLayer(rotationZ = 90f),
                    tint = if (state.selectedVFlip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { state.rotateSelected(clockwise = true, editorState); requestFocus() },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Default.RotateRight, "Rotate selected map tile (R / Shift+R)", Modifier.size(16.dp))
            }

            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
            IconButton(
                onClick = { state.cellSize = (state.cellSize - 4).coerceAtLeast(MINIMAP_MIN_CELL_SIZE); requestFocus() },
                enabled = state.cellSize > MINIMAP_MIN_CELL_SIZE,
                modifier = Modifier.size(28.dp),
            ) { Text("−", fontSize = 14.sp) }
            Text("${state.cellSize.toInt()}x", fontSize = 10.sp, modifier = Modifier.width(32.dp))
            IconButton(
                onClick = { state.cellSize = (state.cellSize + 4).coerceAtMost(MINIMAP_MAX_CELL_SIZE); requestFocus() },
                enabled = state.cellSize < MINIMAP_MAX_CELL_SIZE,
                modifier = Modifier.size(28.dp),
            ) { Text("+", fontSize = 14.sp) }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MinimapToolChip(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Icon(icon, description, Modifier.size(14.dp)) },
        modifier = Modifier.height(28.dp),
    )
}

/** Main canvas for the minimap editor. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MinimapCanvas(state: MinimapEditorState, editorState: EditorState, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    state.areaReassignmentError?.let { message ->
        AlertDialog(
            onDismissRequest = state::dismissAreaReassignmentError,
            title = { Text("Room area unchanged") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = state::dismissAreaReassignmentError) { Text("OK") }
            },
        )
    }
    Column(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A14))) {
        MinimapToolbar(state, editorState) { focusRequester.requestFocus() }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()
        val density = androidx.compose.ui.platform.LocalDensity.current
        var viewportWidthPx by remember { androidx.compose.runtime.mutableStateOf(0) }
        var viewportHeightPx by remember { androidx.compose.runtime.mutableStateOf(0) }
        var zoomInitializedArea by remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
        var contextMenuExpanded by remember { androidx.compose.runtime.mutableStateOf(false) }
        var contextMenuRoomId by remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
        var contextMenuOffset by remember { androidx.compose.runtime.mutableStateOf(DpOffset.Zero) }
        var primaryCanvasPress by remember { androidx.compose.runtime.mutableStateOf(false) }
        val fitCellSize = fitMinimapCellSizeForViewport(
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            density = density.density,
            mapWidthCells = MinimapData.MAP_WIDTH,
            mapHeightCells = MinimapData.MAP_HEIGHT,
        )
        LaunchedEffect(state.selectedArea, fitCellSize) {
            val targetFit = fitCellSize ?: return@LaunchedEffect
            val savedCellSize = inMemoryMinimapCellSizes[state.selectedArea]
            val switchingAreas = zoomInitializedArea != state.selectedArea
            state.cellSize = (savedCellSize ?: targetFit).coerceIn(MINIMAP_MIN_CELL_SIZE, MINIMAP_MAX_CELL_SIZE)
            zoomInitializedArea = state.selectedArea
            if (switchingAreas || savedCellSize == null) {
                hScroll.scrollTo(0)
                vScroll.scrollTo(0)
            }
        }
        LaunchedEffect(state.selectedArea, state.cellSize, fitCellSize, zoomInitializedArea) {
            val targetFit = fitCellSize ?: return@LaunchedEffect
            if (zoomInitializedArea != state.selectedArea) return@LaunchedEffect
            val hasSavedCellSize = inMemoryMinimapCellSizes.containsKey(state.selectedArea)
            if (shouldSaveMinimapCellSize(hasSavedCellSize, state.cellSize, targetFit)) {
                inMemoryMinimapCellSizes[state.selectedArea] =
                    state.cellSize.coerceIn(MINIMAP_MIN_CELL_SIZE, MINIMAP_MAX_CELL_SIZE)
            }
        }
        val canvasW = (state.cellSize * MinimapData.MAP_WIDTH).dp
        val canvasH = (state.cellSize * MinimapData.MAP_HEIGHT).dp
        // Pixel cell size for pointer coordinate conversion (dp cellSize * density)
        val csPx = with(density) { state.cellSize.dp.toPx() }
        Box(Modifier.fillMaxSize()
            .onSizeChanged { size ->
                viewportWidthPx = size.width
                viewportHeightPx = size.height
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val ne = event.nativeEvent as? MouseEvent
                val isZoom = isZoomModifierPressed(event.nativeEvent)
                val sd = event.changes.first().scrollDelta
                if (isZoom) {
                    val mousePos = event.changes.first().position
                    val oldCs = csPx
                    val newCsDp = zoomAfterScroll(
                        currentZoom = state.cellSize,
                        scrollDeltaY = sd.y,
                        minZoom = MINIMAP_MIN_CELL_SIZE,
                        maxZoom = MINIMAP_MAX_CELL_SIZE,
                    )
                    val newCsPx = newCsDp * density.density
                    val contentXBefore = (hScroll.value + mousePos.x) / oldCs
                    val contentYBefore = (vScroll.value + mousePos.y) / oldCs
                    state.cellSize = newCsDp
                    coroutineScope.launch {
                        hScroll.scrollTo(((contentXBefore * newCsPx) - mousePos.x).toInt().coerceAtLeast(0))
                        vScroll.scrollTo(((contentYBefore * newCsPx) - mousePos.y).toInt().coerceAtLeast(0))
                    }
                } else coroutineScope.launch {
                    val pan = resolvePanScrollDelta(
                        rawX = sd.x,
                        rawY = sd.y,
                        shiftPressed = ne?.isShiftDown == true
                    )
                    vScroll.scrollTo((vScroll.value + pan.y * 40).toInt().coerceIn(0, vScroll.maxValue))
                    hScroll.scrollTo((hScroll.value + pan.x * 40).toInt().coerceIn(0, hScroll.maxValue))
                }
            }
            .horizontalScroll(hScroll).verticalScroll(vScroll)
        ) {
        Canvas(
            modifier = Modifier
                .size(canvasW, canvasH)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val ctrl = event.isCtrlPressed || event.isMetaPressed
                    when (event.key) {
                        Key.S -> { state.activateTool(MinimapTool.SELECT, editorState); true }
                        Key.P -> { state.activateTool(MinimapTool.PAINT, editorState); true }
                        Key.G -> { state.activateTool(MinimapTool.FILL, editorState); true }
                        Key.E -> { state.activateTool(MinimapTool.ERASE, editorState); true }
                        Key.I -> { state.activateTool(MinimapTool.EYEDROPPER, editorState); true }
                        Key.H -> { state.flipSelectedHorizontal(); true }
                        Key.V -> { state.flipSelectedVertical(); true }
                        Key.R -> { state.rotateSelected(clockwise = !event.isShiftPressed, editorState); true }
                        Key.Z -> if (ctrl) {
                            if (event.isShiftPressed) state.redo(editorState) else state.undo(editorState)
                            true
                        } else false
                        Key.Y -> if (ctrl) { state.redo(editorState); true } else false
                        Key.Equals -> { state.cellSize = (state.cellSize + 4).coerceAtMost(MINIMAP_MAX_CELL_SIZE); true }
                        Key.Minus -> { state.cellSize = (state.cellSize - 4).coerceAtLeast(MINIMAP_MIN_CELL_SIZE); true }
                        Key.DirectionLeft -> if (state.selectedRoom != null) { state.moveRoom(-1, 0, editorState); true } else false
                        Key.DirectionRight -> if (state.selectedRoom != null) { state.moveRoom(1, 0, editorState); true } else false
                        Key.DirectionUp -> if (state.selectedRoom != null) { state.moveRoom(0, -1, editorState); true } else false
                        Key.DirectionDown -> if (state.selectedRoom != null) { state.moveRoom(0, 1, editorState); true } else false
                        Key.Escape -> if (state.isMovingRoom) { state.cancelMove(editorState); true }
                            else if (state.selectedRoomIndex >= 0) { state.clearRoomSelection(editorState); true }
                            else false
                        Key.Enter -> if (state.isMovingRoom) { state.applyMove(editorState); true } else false
                        else -> false
                    }
                }
                .pointerHoverIcon(PixelEditorCursors.forMinimapTool(state.tool))
                .onPointerEvent(PointerEventType.Press) { event ->
                    val native = event.nativeEvent as? MouseEvent
                    if (native?.button == MouseEvent.BUTTON2) {
                        primaryCanvasPress = false
                        // Middle-drag moves rooms from any tool and must not
                        // leak into paint/erase drag gesture detection.
                        event.changes.forEach { it.consume() }
                        focusRequester.requestFocus()
                        val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                        state.beginMiddleRoomDrag(
                            (position.x / csPx).toInt(),
                            (position.y / csPx).toInt(),
                            editorState,
                        )
                    } else if (native?.button == MouseEvent.BUTTON3) {
                        primaryCanvasPress = false
                        // Keep the secondary click from also reaching the tap/paint gesture below.
                        event.changes.forEach { it.consume() }
                        focusRequester.requestFocus()
                        val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                        val x = (position.x / csPx).toInt()
                        val y = (position.y / csPx).toInt()
                        state.selectRoomAt(x, y, editorState)
                        val contextRoom = state.selectedRoom
                        contextMenuRoomId = contextRoom?.roomId
                        if (contextRoom != null) {
                            contextMenuOffset = DpOffset(
                                ((position.x - hScroll.value).coerceAtLeast(0f) / density.density).dp,
                                ((position.y - vScroll.value).coerceAtLeast(0f) / density.density).dp,
                            )
                            contextMenuExpanded = true
                        }
                    } else {
                        // The generic Compose tap detector also sees mouse
                        // buttons on Desktop. Gate its action explicitly so a
                        // context click can never lift, paint, or erase behind
                        // the popup.
                        val isPrimary = native == null || native.button == MouseEvent.BUTTON1
                        if (isPrimary && state.tool == MinimapTool.SELECT) {
                            primaryCanvasPress = false
                            event.changes.forEach { it.consume() }
                            focusRequester.requestFocus()
                            val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                            state.beginPrimaryRoomDrag(
                                (position.x / csPx).toInt(),
                                (position.y / csPx).toInt(),
                                editorState,
                            )
                        } else {
                            primaryCanvasPress = isPrimary
                        }
                    }
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    if (state.isPointerDraggingRoom) {
                        event.changes.forEach { it.consume() }
                        val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                        state.updatePointerRoomMove(
                            (position.x / csPx).toInt(),
                            (position.y / csPx).toInt(),
                        )
                    }
                }
                .onPointerEvent(PointerEventType.Release) { event ->
                    if (state.isPointerDraggingRoom) {
                        event.changes.forEach { it.consume() }
                        val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                        val x = (position.x / csPx).toInt()
                        val y = (position.y / csPx).toInt()
                        if (state.isPrimaryDraggingRoom) {
                            state.endPrimaryRoomDrag(x, y, editorState)
                        } else {
                            state.endMiddleRoomDrag(x, y, editorState)
                        }
                    }
                }
                .pointerInput(state.tool, state.selectedTileWord, csPx) {
                    detectTapGestures { offset ->
                        if (!primaryCanvasPress) return@detectTapGestures
                        primaryCanvasPress = false
                        focusRequester.requestFocus()
                        val x = (offset.x / csPx).toInt(); val y = (offset.y / csPx).toInt()
                        when (state.tool) {
                            MinimapTool.PAINT -> state.paintTile(x, y, editorState)
                            MinimapTool.EYEDROPPER -> state.sampleTile(x, y)
                            MinimapTool.FILL -> state.fillTile(x, y, editorState)
                            MinimapTool.SELECT -> Unit // Select uses press-drag-release above.
                            MinimapTool.ERASE -> state.eraseTile(x, y, editorState)
                            MinimapTool.REVEAL -> state.toggleReveal(x, y, editorState)
                        }
                    }
                }
                .pointerInput(state.tool, state.selectedTileWord, csPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (primaryCanvasPress) {
                                val x = (offset.x / csPx).toInt(); val y = (offset.y / csPx).toInt()
                                if ((state.tool == MinimapTool.PAINT || state.tool == MinimapTool.ERASE) &&
                                    x in 0 until MinimapData.MAP_WIDTH && y in 0 until MinimapData.MAP_HEIGHT
                                ) {
                                    val word = if (state.tool == MinimapTool.ERASE) MinimapTiles.EMPTY else state.selectedTileWord
                                    state.pendingStroke = state.mapData.withTile(x, y, word)
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            if (primaryCanvasPress) {
                                change.consume()
                                val x = (change.position.x / csPx).toInt(); val y = (change.position.y / csPx).toInt()
                                if ((state.tool == MinimapTool.PAINT || state.tool == MinimapTool.ERASE) &&
                                    x in 0 until MinimapData.MAP_WIDTH && y in 0 until MinimapData.MAP_HEIGHT
                                ) {
                                    val word = if (state.tool == MinimapTool.ERASE) MinimapTiles.EMPTY else state.selectedTileWord
                                    state.pendingStroke = (state.pendingStroke ?: state.mapData).withTile(x, y, word)
                                }
                                state.hoverX = x; state.hoverY = y
                            }
                        },
                        onDragEnd = {
                            if (primaryCanvasPress) state.commitStroke(editorState)
                            primaryCanvasPress = false
                        },
                        onDragCancel = {
                            state.pendingStroke = null
                            primaryCanvasPress = false
                        },
                    )
                }
                .pointerInput(csPx) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pos = event.changes.firstOrNull()?.position
                            if (pos != null) {
                                val x = (pos.x / csPx).toInt()
                                val y = (pos.y / csPx).toInt()
                                state.hoverX = x
                                state.hoverY = y
                            }
                        }
                    }
                }
        ) {
            // Compute pixel cell size from actual canvas pixel dimensions (density-correct)
            val csPixels = size.width / MinimapData.MAP_WIDTH
            drawMinimapGrid(state.displayData, csPixels, state.showGrid, state.showRoomOutlines,
                state.showRoomTiles, state.showPixelView, state.tileGraphics, state.showStationOverlay,
                state.stationData, state.areaRooms, state.hoverX, state.hoverY,
                state.tool, state.selectedTileWord, state.selectedRoom, state.moveBuffer)
        }
        }
        DropdownMenu(
            expanded = contextMenuExpanded,
            onDismissRequest = {
                contextMenuExpanded = false
                contextMenuRoomId = null
                focusRequester.requestFocus()
            },
            offset = contextMenuOffset,
        ) {
            val room = contextMenuRoomId?.let { roomId ->
                state.areaRooms.firstOrNull { it.roomId == roomId }
            }
            if (room != null) {
                DropdownMenuItem(
                    text = { Text("Move ${room.name} to…", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    onClick = {},
                    enabled = false,
                )
                for (area in 0 until MinimapData.NUM_AREAS) {
                    DropdownMenuItem(
                        text = { Text(MinimapData.AREA_NAMES[area], fontSize = 10.sp) },
                        enabled = area != room.area,
                        onClick = {
                            contextMenuExpanded = false
                            contextMenuRoomId = null
                            state.reassignRoomArea(room.roomId, area, editorState)
                            focusRequester.requestFocus()
                        },
                    )
                }
                Divider()
                DropdownMenuItem(
                    text = { Text("Hide room map graphics", fontSize = 10.sp) },
                    onClick = {
                        contextMenuExpanded = false
                        contextMenuRoomId = null
                        state.clearRoomMap(room.roomId, editorState)
                        focusRequester.requestFocus()
                    },
                )
            }
        }
        }
    }
}

// ─── Canvas drawing ───

private fun DrawScope.drawMinimapGrid(
    data: MinimapData, cs: Float, showGrid: Boolean, showOutlines: Boolean,
    showTiles: Boolean, showPixelView: Boolean, tileGfx: Array<IntArray>?,
    showStation: Boolean, stationData: MapStationData,
    rooms: List<Room>, hx: Int, hy: Int,
    tool: MinimapTool = MinimapTool.PAINT, selectedWord: Int = 0,
    selectedRoom: Room? = null, moveBuf: RoomMoveBuffer? = null,
) {
    val pal = arrayOf(Color(0xFF18182C), Color(0xFF3060A0), Color(0xFFC0C0D0), Color(0xFFA03030))
    // 1. Room tiles — render directly from minimap tile data grid
    if (showTiles) for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
        val w = data.getTile(x, y); val idx = MinimapData.tileIndex(w); val p = MinimapData.tilePalette(w)
        if (w == 0 || idx in EMPTY_TILES) continue
        val px = x * cs; val py = y * cs
        drawRect(pal[p.coerceIn(0, 3)], Offset(px, py), Size(cs, cs))
        val hFlip = MinimapData.tileHFlip(w); val vFlip = MinimapData.tileVFlip(w)
        drawTileDetails(idx, px, py, cs, hFlip, vFlip)
    }
    // 1b. Pixel view — actual 4bpp tile graphics from ROM
    if (showPixelView && tileGfx != null) for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
        val w = data.getTile(x, y); val idx = MinimapData.tileIndex(w); val p = MinimapData.tilePalette(w)
        if (w == 0 || idx in EMPTY_TILES) continue
        val hFlip = MinimapData.tileHFlip(w); val vFlip = MinimapData.tileVFlip(w)
        val pixels = tileGfx[idx.coerceIn(0, 255)]
        val px = x * cs; val py = y * cs; val ps = cs / 8f
        for (pr in 0 until 8) for (pc in 0 until 8) {
            val sr = if (vFlip) 7 - pr else pr
            val sc = if (hFlip) 7 - pc else pc
            val pv = pixels[sr * 8 + sc]
            val color = minimapPixelColor(p, pv)
            drawRect(color, Offset(px + pc * ps, py + pr * ps), Size(ps + 0.5f, ps + 0.5f))
        }
    }
    // 2. Room outlines (green stroke)
    if (showOutlines) for (room in rooms) {
        val rx = room.mapX * cs; val ry = room.mapY * cs
        val rw = room.width * cs; val rh = room.height * cs
        drawRect(Color(0x3000FF88), Offset(rx, ry), Size(rw, rh))
        drawRect(Color(0xAA00FF88), Offset(rx, ry), Size(rw, rh), style = Stroke(1f))
    }
    // 2b. Move buffer overlay — render lifted tiles at target position (semi-transparent)
    if (moveBuf != null && tileGfx != null) {
        for (ry in 0 until moveBuf.height) for (rx in 0 until moveBuf.width) {
            val w = moveBuf.tiles[ry][rx]
            if (w == 0) continue
            val idx = MinimapData.tileIndex(w); val p = MinimapData.tilePalette(w)
            if (idx in EMPTY_TILES) continue
            val mx = moveBuf.currentX + rx; val my = moveBuf.currentY + ry
            if (mx !in 0 until MinimapData.MAP_WIDTH || my !in 0 until MinimapData.MAP_HEIGHT) continue
            val px = mx * cs; val py = my * cs
            val hFlip = MinimapData.tileHFlip(w); val vFlip = MinimapData.tileVFlip(w)
            val pixels = tileGfx[idx.coerceIn(0, 255)]
            val ps = cs / 8f
            for (pr in 0 until 8) for (pc in 0 until 8) {
                val sr = if (vFlip) 7 - pr else pr; val sc = if (hFlip) 7 - pc else pc
                val color = minimapPixelColor(p, pixels[sr * 8 + sc])
                drawRect(color.copy(alpha = 0.7f), Offset(px + pc * ps, py + pr * ps), Size(ps + 0.5f, ps + 0.5f))
            }
        }
        // Outline around buffer target position
        drawRect(Color(0xFFFFCC00), Offset(moveBuf.currentX * cs, moveBuf.currentY * cs),
            Size(moveBuf.width * cs, moveBuf.height * cs), style = Stroke(2f))
    }
    // 2c. Selected room highlight (always visible when a room is selected and not moving)
    if (selectedRoom != null && moveBuf == null) {
        val rx = selectedRoom.mapX * cs; val ry = selectedRoom.mapY * cs
        val rw = selectedRoom.width * cs; val rh = selectedRoom.height * cs
        drawRect(Color(0x2000FF88), Offset(rx, ry), Size(rw, rh))
        drawRect(Color(0xFF00FF88), Offset(rx, ry), Size(rw, rh), style = Stroke(2f))
    }
    // 3. Grid
    if (showGrid) {
        val g = Color.White.copy(alpha = 0.06f)
        for (x in 0..MinimapData.MAP_WIDTH) drawLine(g, Offset(x * cs, 0f), Offset(x * cs, MinimapData.MAP_HEIGHT * cs))
        for (y in 0..MinimapData.MAP_HEIGHT) drawLine(g, Offset(0f, y * cs), Offset(MinimapData.MAP_WIDTH * cs, y * cs))
    }
    // 4. Station reveal overlay
    if (showStation) for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH)
        if (stationData.isRevealed(x, y)) drawRect(Color(0x40FF69B4), Offset(x * cs, y * cs), Size(cs, cs))
    if (showStation && moveBuf != null) {
        for (ry in moveBuf.revealed.indices) for (rx in moveBuf.revealed[ry].indices) {
            if (moveBuf.revealed[ry][rx]) {
                drawRect(
                    Color(0x60FF69B4),
                    Offset((moveBuf.currentX + rx) * cs, (moveBuf.currentY + ry) * cs),
                    Size(cs, cs),
                )
            }
        }
    }
    // 5. Hover cursor + paint preview
    if (hx in 0 until MinimapData.MAP_WIDTH && hy in 0 until MinimapData.MAP_HEIGHT) {
        val px = hx * cs; val py = hy * cs
        // Paint preview: show faded version of the tile that will be painted
        val selectedTile = MinimapData.tileIndex(selectedWord)
        val selectedPalette = MinimapData.tilePalette(selectedWord)
        if (tool == MinimapTool.PAINT && tileGfx != null && selectedTile in 0..255) {
            val previewPixels = tileGfx[selectedTile]
            val ps = cs / 8f
            for (pr in 0 until 8) for (pc in 0 until 8) {
                val sourceRow = if (MinimapData.tileVFlip(selectedWord)) 7 - pr else pr
                val sourceCol = if (MinimapData.tileHFlip(selectedWord)) 7 - pc else pc
                val color = minimapPixelColor(selectedPalette, previewPixels[sourceRow * 8 + sourceCol])
                drawRect(color.copy(alpha = 0.5f), Offset(px + pc * ps, py + pr * ps), Size(ps + 0.5f, ps + 0.5f))
            }
        }
        drawRect(Color.White.copy(alpha = 0.3f), Offset(px, py), Size(cs, cs), style = Stroke(2f))
    }
}

private fun DrawScope.drawTileDetails(t: Int, px: Float, py: Float, cs: Float, hFlip: Boolean = false, vFlip: Boolean = false) {
    val wc = Color.White.copy(alpha = 0.8f); val w = (cs / 6f).coerceAtLeast(1f)
    // Wall sets derived from actual 4bpp pause-map pixel data.
    var top = t in setOf(0x20,0x21,0x22,0x24,0x25,0x26, 0x4D,0x4F,0x5E,0x6E,0x6F, 0x76,0x8E,0x8F)
    var bot = t in setOf(0x20,0x21,0x22, 0x4D,0x5E,0x5F,0x6F, 0x8F)
    var left = t in setOf(0x10,0x20,0x21,0x23,0x24,0x25, 0x4D,0x4F,0x6E,0x6F, 0x77,0x8E,0x8F)
    var right = t in setOf(0x10,0x20,0x23,0x24,0x27, 0x4D,0x4F,0x6E,0x6F)
    // Apply flip bits — swap walls to opposite sides
    if (hFlip) { val tmp = left; left = right; right = tmp }
    if (vFlip) { val tmp = top; top = bot; bot = tmp }
    if (top) drawLine(wc, Offset(px,py+.5f), Offset(px+cs,py+.5f), w)
    if (bot) drawLine(wc, Offset(px,py+cs-.5f), Offset(px+cs,py+cs-.5f), w)
    if (left) drawLine(wc, Offset(px+.5f,py), Offset(px+.5f,py+cs), w)
    if (right) drawLine(wc, Offset(px+cs-.5f,py), Offset(px+cs-.5f,py+cs), w)
    if (t in 0x76..0x80) drawCircle(Color(0xFFFFCC00), cs/5f, Offset(px+cs/2f,py+cs/2f))
    when (t) {
        0x04 -> drawArr(px,py,cs,w,0); 0x05 -> drawArr(px,py,cs,w,2)
        0x02 -> drawArr(px,py,cs,w,1); 0x03 -> drawArr(px,py,cs,w,3)
        0x44 -> drawCircle(Color(0xFF44FF44), cs/4f, Offset(px+cs/2f,py+cs/2f))
        0x46 -> drawCircle(Color(0xFF44CCFF), cs/4f, Offset(px+cs/2f,py+cs/2f))
        0x48 -> drawCircle(Color(0xFFFF8800), cs/4f, Offset(px+cs/2f,py+cs/2f))
        0x4A -> drawCircle(Color(0xFFFF4444), cs/4f, Offset(px+cs/2f,py+cs/2f))
        0xCE, 0x12 -> {
            val cx = px+cs/2f
            drawLine(wc, Offset(cx,py+cs*.15f), Offset(cx,py+cs*.85f), w*1.5f)
            drawLine(wc, Offset(cx-cs*.15f,py+cs*.3f), Offset(cx,py+cs*.15f), w)
            drawLine(wc, Offset(cx+cs*.15f,py+cs*.3f), Offset(cx,py+cs*.15f), w)
            drawLine(wc, Offset(cx-cs*.15f,py+cs*.7f), Offset(cx,py+cs*.85f), w)
            drawLine(wc, Offset(cx+cs*.15f,py+cs*.7f), Offset(cx,py+cs*.85f), w)
        }
    }
}

private fun DrawScope.drawArr(px: Float, py: Float, cs: Float, w: Float, d: Int) {
    val c = Color.White.copy(alpha = 0.8f); val cx=px+cs/2f; val cy=py+cs/2f; val h=cs*.3f; val a=cs*.15f
    when(d) {
        0 -> { drawLine(c,Offset(cx-h,cy),Offset(cx+h,cy),w); drawLine(c,Offset(cx+h-a,cy-a),Offset(cx+h,cy),w); drawLine(c,Offset(cx+h-a,cy+a),Offset(cx+h,cy),w) }
        1 -> { drawLine(c,Offset(cx,cy-h),Offset(cx,cy+h),w); drawLine(c,Offset(cx-a,cy+h-a),Offset(cx,cy+h),w); drawLine(c,Offset(cx+a,cy+h-a),Offset(cx,cy+h),w) }
        2 -> { drawLine(c,Offset(cx+h,cy),Offset(cx-h,cy),w); drawLine(c,Offset(cx-h+a,cy-a),Offset(cx-h,cy),w); drawLine(c,Offset(cx-h+a,cy+a),Offset(cx-h,cy),w) }
        3 -> { drawLine(c,Offset(cx,cy+h),Offset(cx,cy-h),w); drawLine(c,Offset(cx-a,cy-h+a),Offset(cx,cy-h),w); drawLine(c,Offset(cx+a,cy-h+a),Offset(cx,cy-h),w) }
    }
}

// ─── Sidebar widgets ───

@Composable
private fun DpadBtn(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(28.dp).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp)
        }
    }
}

@Composable
private fun MinimapTilePalette(
    selectedTile: Int,
    selectedHFlip: Boolean,
    selectedVFlip: Boolean,
    tileGfx: Array<IntArray>?,
    onSelect: (Int) -> Unit,
) {
    val tiles = MinimapTiles.PALETTE_TILES
    val cols = 7
    val cellDp = 24.dp
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        for (row in tiles.indices step cols) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                for (col in 0 until cols) {
                    val idx = row + col
                    if (idx < tiles.size) {
                        val tile = tiles[idx]
                        val sel = tile == selectedTile
                        Box(
                            modifier = Modifier.size(cellDp)
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color(0xFF18182C), RoundedCornerShape(2.dp))
                                .border(if (sel) 1.5.dp else 0.5.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
                                .clickable { onSelect(tile) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.size(cellDp - 4.dp)) {
                                val cs = size.width
                                val pixels = tileGfx?.getOrNull(tile)
                                if (pixels != null) {
                                    // Render actual 4bpp tile graphics (palette 1 = blue for preview)
                                    val ps = cs / 8f
                                    for (pr in 0 until 8) for (pc in 0 until 8) {
                                        val sourceRow = if (sel && selectedVFlip) 7 - pr else pr
                                        val sourceCol = if (sel && selectedHFlip) 7 - pc else pc
                                        drawRect(minimapPixelColor(1, pixels[sourceRow * 8 + sourceCol]), Offset(pc * ps, pr * ps), Size(ps + 0.5f, ps + 0.5f))
                                    }
                                } else {
                                    // Fallback: simplified icon
                                    drawRect(Color(0xFF3060A0), Offset.Zero, Size(cs, cs))
                                    drawTileDetails(tile, 0f, 0f, cs)
                                }
                            }
                        }
                    }
                }
            }
        }
        val name = MinimapTiles.TILE_NAMES[selectedTile]
        if (name != null) {
            val transform = buildList {
                if (selectedHFlip) add("H-flip")
                if (selectedVFlip) add("V-flip")
            }.joinToString(", ")
            Text(
                if (transform.isEmpty()) name else "$name · $transform",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
