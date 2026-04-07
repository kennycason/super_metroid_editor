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
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.Room
import com.supermetroid.editor.rom.MinimapData
import com.supermetroid.editor.rom.MinimapTiles
import com.supermetroid.editor.rom.MapStationData
import com.supermetroid.editor.rom.RomParser

/** Tile indices that are background/empty in the SNES tilemap. */
private val EMPTY_TILES = setOf(0x00, 0x1F)

/** 2bpp pixel value → Color for each of the 4 minimap palettes.
 *  pixel 0 = background, 1 = room fill, 2 = border, 3 = bg accent. */
private val MINIMAP_PALETTES = arrayOf(
    // Palette 0: black/dark
    arrayOf(Color(0xFF000008), Color(0xFF101020), Color(0xFF383850), Color(0xFF0C0C18)),
    // Palette 1: blue
    arrayOf(Color(0xFF000830), Color(0xFF2850A0), Color(0xFF80B0E8), Color(0xFF102858)),
    // Palette 2: white/gray
    arrayOf(Color(0xFF181820), Color(0xFF808898), Color(0xFFD0D0E8), Color(0xFF404050)),
    // Palette 3: red (explored)
    arrayOf(Color(0xFF180008), Color(0xFFA03030), Color(0xFFE08888), Color(0xFF481018)),
)

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

        Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(4.dp))

        // Tools — single row: paint, sample, fill, undo, redo, zoom+, zoom-
        Text("Tool", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MmToolBtn(Icons.Default.Brush, "Paint", state.tool == MinimapTool.PAINT) { state.tool = MinimapTool.PAINT }
            MmToolBtn(Icons.Default.Colorize, "Sample", state.tool == MinimapTool.EYEDROPPER) { state.tool = MinimapTool.EYEDROPPER }
            MmToolBtn(Icons.Default.FormatColorFill, "Fill", state.tool == MinimapTool.FILL) { state.tool = MinimapTool.FILL }
            Spacer(Modifier.width(4.dp))
            MmIconBtn(Icons.Default.Undo, "Undo", state.undoStack.isNotEmpty()) { state.undo(editorState) }
            MmIconBtn(Icons.Default.Redo, "Redo", state.redoStack.isNotEmpty()) { state.redo(editorState) }
            MmIconBtn(Icons.Default.ZoomIn, "+", true) { state.cellSize = (state.cellSize + 4).coerceAtMost(64f) }
            MmIconBtn(Icons.Default.ZoomOut, "-", true) { state.cellSize = (state.cellSize - 4).coerceAtLeast(4f) }
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
        MinimapTilePalette(selectedTile = state.selectedTile, tileGfx = state.tileGraphics, onSelect = { state.selectedTile = it })

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
                        onClick = { state.selectedRoomIndex = i; roomDropdownExpanded = false },
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

/** Main canvas for the minimap editor. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MinimapCanvas(state: MinimapEditorState, editorState: EditorState, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A14))) {
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val canvasW = (state.cellSize * MinimapData.MAP_WIDTH).dp
        val canvasH = (state.cellSize * MinimapData.MAP_HEIGHT).dp
        // Pixel cell size for pointer coordinate conversion (dp cellSize * density)
        val csPx = with(density) { state.cellSize.dp.toPx() }
        Box(Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val ne = event.nativeEvent as? MouseEvent
                val isZoom = ne?.let { it.isControlDown || it.isMetaDown } ?: false
                val sd = event.changes.first().scrollDelta
                if (isZoom) {
                    val mousePos = event.changes.first().position
                    val oldCs = csPx
                    val factor = if (sd.y < 0) 1.15f else 1f / 1.15f
                    val newCsDp = (state.cellSize * factor).coerceIn(4f, 64f)
                    val newCsPx = newCsDp * density.density
                    val contentXBefore = (hScroll.value + mousePos.x) / oldCs
                    val contentYBefore = (vScroll.value + mousePos.y) / oldCs
                    state.cellSize = newCsDp
                    coroutineScope.launch {
                        hScroll.scrollTo(((contentXBefore * newCsPx) - mousePos.x).toInt().coerceAtLeast(0))
                        vScroll.scrollTo(((contentYBefore * newCsPx) - mousePos.y).toInt().coerceAtLeast(0))
                    }
                } else coroutineScope.launch {
                    vScroll.scrollTo((vScroll.value + sd.y * 40).toInt().coerceIn(0, vScroll.maxValue))
                    hScroll.scrollTo((hScroll.value + sd.x * 40).toInt().coerceIn(0, hScroll.maxValue))
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
                        Key.P -> { state.tool = MinimapTool.PAINT; true }
                        Key.F -> { state.tool = MinimapTool.FILL; true }
                        Key.I -> { state.tool = MinimapTool.EYEDROPPER; true }
                        Key.Z -> if (ctrl) { state.undo(editorState); true } else false
                        Key.Y -> if (ctrl) { state.redo(editorState); true } else false
                        Key.Equals -> { state.cellSize = (state.cellSize + 4).coerceAtMost(64f); true }
                        Key.Minus -> { state.cellSize = (state.cellSize - 4).coerceAtLeast(4f); true }
                        else -> false
                    }
                }
                .pointerHoverIcon(PixelEditorCursors.forMinimapTool(state.tool))
                .pointerInput(state.tool, state.selectedTile, state.selectedPalette, csPx) {
                    detectTapGestures { offset ->
                        focusRequester.requestFocus()
                        val x = (offset.x / csPx).toInt(); val y = (offset.y / csPx).toInt()
                        when (state.tool) {
                            MinimapTool.PAINT -> state.paintTile(x, y, editorState)
                            MinimapTool.EYEDROPPER -> state.sampleTile(x, y)
                            MinimapTool.FILL -> state.fillTile(x, y, editorState)
                        }
                    }
                }
                .pointerInput(state.tool, state.selectedTile, state.selectedPalette, csPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val x = (offset.x / csPx).toInt(); val y = (offset.y / csPx).toInt()
                            if (state.tool == MinimapTool.PAINT && x in 0 until MinimapData.MAP_WIDTH && y in 0 until MinimapData.MAP_HEIGHT)
                                state.pendingStroke = state.mapData.withTile(x, y, MinimapData.makeTileWord(state.selectedTile, state.selectedPalette))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val x = (change.position.x / csPx).toInt(); val y = (change.position.y / csPx).toInt()
                            if (state.tool == MinimapTool.PAINT && x in 0 until MinimapData.MAP_WIDTH && y in 0 until MinimapData.MAP_HEIGHT)
                                state.pendingStroke = (state.pendingStroke ?: state.mapData).withTile(x, y, MinimapData.makeTileWord(state.selectedTile, state.selectedPalette))
                            state.hoverX = x; state.hoverY = y
                        },
                        onDragEnd = { state.commitStroke(editorState) },
                        onDragCancel = { state.pendingStroke = null },
                    )
                }
                .pointerInput(csPx) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pos = event.changes.firstOrNull()?.position
                            if (pos != null) { state.hoverX = (pos.x / csPx).toInt(); state.hoverY = (pos.y / csPx).toInt() }
                        }
                    }
                }
        ) {
            // Compute pixel cell size from actual canvas pixel dimensions (density-correct)
            val csPixels = size.width / MinimapData.MAP_WIDTH
            drawMinimapGrid(state.displayData, csPixels, state.showGrid, state.showRoomOutlines,
                state.showRoomTiles, state.showPixelView, state.tileGraphics, state.showStationOverlay,
                state.stationData, state.areaRooms, state.hoverX, state.hoverY,
                state.tool, state.selectedTile, state.selectedPalette, state.selectedRoom)
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
    tool: MinimapTool = MinimapTool.PAINT, selectedTile: Int = 0, selectedPalette: Int = 0,
    selectedRoom: Room? = null,
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
    // 1b. Pixel view — actual 2bpp tile graphics from ROM
    if (showPixelView && tileGfx != null) for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
        val w = data.getTile(x, y); val idx = MinimapData.tileIndex(w); val p = MinimapData.tilePalette(w)
        if (w == 0 || idx in EMPTY_TILES) continue
        val hFlip = MinimapData.tileHFlip(w); val vFlip = MinimapData.tileVFlip(w)
        val pixels = tileGfx[idx.coerceIn(0, 255)]
        val palColors = MINIMAP_PALETTES[p.coerceIn(0, 3)]
        val px = x * cs; val py = y * cs; val ps = cs / 8f
        for (pr in 0 until 8) for (pc in 0 until 8) {
            val sr = if (vFlip) 7 - pr else pr
            val sc = if (hFlip) 7 - pc else pc
            val pv = pixels[sr * 8 + sc]
            val color = palColors[pv]
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
    // 2b. Selected room highlight (always visible when a room is selected)
    if (selectedRoom != null) {
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
    // 5. Hover cursor + paint preview
    if (hx in 0 until MinimapData.MAP_WIDTH && hy in 0 until MinimapData.MAP_HEIGHT) {
        val px = hx * cs; val py = hy * cs
        // Paint preview: show faded version of the tile that will be painted
        if (tool == MinimapTool.PAINT && tileGfx != null && selectedTile in 0..255) {
            val previewPixels = tileGfx[selectedTile]
            val palColors = MINIMAP_PALETTES[selectedPalette.coerceIn(0, 3)]
            val ps = cs / 8f
            for (pr in 0 until 8) for (pc in 0 until 8) {
                val color = palColors[previewPixels[pr * 8 + pc]]
                drawRect(color.copy(alpha = 0.5f), Offset(px + pc * ps, py + pr * ps), Size(ps + 0.5f, ps + 0.5f))
            }
        }
        drawRect(Color.White.copy(alpha = 0.3f), Offset(px, py), Size(cs, cs), style = Stroke(2f))
    }
}

private fun DrawScope.drawTileDetails(t: Int, px: Float, py: Float, cs: Float, hFlip: Boolean = false, vFlip: Boolean = false) {
    val wc = Color.White.copy(alpha = 0.8f); val w = (cs / 6f).coerceAtLeast(1f)
    // Wall sets derived from actual 2bpp pixel data at ROM $D3200 (border = pixel value 2 on full edge)
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
private fun MmToolBtn(icon: ImageVector, label: String, sel: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(44.dp).clickable(onClick = onClick)
            .border(if (sel) 1.5.dp else 0.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(3.dp)),
        color = Color.Transparent, shape = RoundedCornerShape(3.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, modifier = Modifier.size(32.dp),
                tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

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
private fun MmIconBtn(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, label, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun MinimapTilePalette(selectedTile: Int, tileGfx: Array<IntArray>?, onSelect: (Int) -> Unit) {
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
                                    // Render actual 2bpp tile graphics (palette 1 = blue for preview)
                                    val palColors = MINIMAP_PALETTES[1]
                                    val ps = cs / 8f
                                    for (pr in 0 until 8) for (pc in 0 until 8) {
                                        drawRect(palColors[pixels[pr * 8 + pc]], Offset(pc * ps, pr * ps), Size(ps + 0.5f, ps + 0.5f))
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
        if (name != null) Text(name, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
