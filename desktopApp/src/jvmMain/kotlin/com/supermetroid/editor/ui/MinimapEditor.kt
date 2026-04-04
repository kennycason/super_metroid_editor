package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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

/** Tile indices that are known to be empty/background. */
private val EMPTY_TILES = setOf(0x00, 0x1F)

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
            MmIconBtn(Icons.Default.ZoomIn, "+", true) { state.cellSize = (state.cellSize + 2).coerceAtMost(32f) }
            MmIconBtn(Icons.Default.ZoomOut, "-", true) { state.cellSize = (state.cellSize - 2).coerceAtLeast(4f) }
        }

        Spacer(Modifier.height(4.dp)); Divider(); Spacer(Modifier.height(4.dp))

        // Display toggles
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.showGrid, onCheckedChange = { state.showGrid = it }, modifier = Modifier.size(16.dp))
            Text("Grid", fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.showRoomOverlay, onCheckedChange = { state.showRoomOverlay = it }, modifier = Modifier.size(16.dp))
            Text("Room outlines", fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.showStationOverlay, onCheckedChange = { state.showStationOverlay = it }, modifier = Modifier.size(16.dp))
            Text("Station reveal", fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
        }

        Spacer(Modifier.height(4.dp)); Divider(); Spacer(Modifier.height(4.dp))

        // Tile palette — fills available width
        Text("Tile", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        MinimapTilePalette(selectedTile = state.selectedTile, onSelect = { state.selectedTile = it })

        Spacer(Modifier.height(4.dp)); Divider(); Spacer(Modifier.height(4.dp))

        // Palette row
        Text("Palette", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (pal in 0..3) {
                val label = when (pal) { 0 -> "Blk"; 1 -> "Blu"; 2 -> "Wht"; else -> "Red" }
                val sel = pal == state.selectedPalette
                Surface(
                    modifier = Modifier.height(24.dp).weight(1f).padding(vertical = 1.dp)
                        .clickable { state.selectedPalette = pal }
                        .border(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(3.dp)),
                    color = if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(3.dp),
                ) { Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 9.sp) } }
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
@Composable
fun MinimapCanvas(state: MinimapEditorState, editorState: EditorState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A14))) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(state.tool, state.selectedTile, state.selectedPalette, state.cellSize) {
                    detectTapGestures { offset ->
                        val x = (offset.x / state.cellSize).toInt(); val y = (offset.y / state.cellSize).toInt()
                        when (state.tool) {
                            MinimapTool.PAINT -> state.paintTile(x, y, editorState)
                            MinimapTool.EYEDROPPER -> state.sampleTile(x, y)
                            MinimapTool.FILL -> state.fillTile(x, y, editorState)
                        }
                    }
                }
                .pointerInput(state.tool, state.selectedTile, state.selectedPalette, state.cellSize) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val x = (offset.x / state.cellSize).toInt(); val y = (offset.y / state.cellSize).toInt()
                            if (state.tool == MinimapTool.PAINT && x in 0 until MinimapData.MAP_WIDTH && y in 0 until MinimapData.MAP_HEIGHT)
                                state.pendingStroke = state.mapData.withTile(x, y, MinimapData.makeTileWord(state.selectedTile, state.selectedPalette))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val x = (change.position.x / state.cellSize).toInt(); val y = (change.position.y / state.cellSize).toInt()
                            if (state.tool == MinimapTool.PAINT && x in 0 until MinimapData.MAP_WIDTH && y in 0 until MinimapData.MAP_HEIGHT)
                                state.pendingStroke = (state.pendingStroke ?: state.mapData).withTile(x, y, MinimapData.makeTileWord(state.selectedTile, state.selectedPalette))
                            state.hoverX = x; state.hoverY = y
                        },
                        onDragEnd = { state.commitStroke(editorState) },
                        onDragCancel = { state.pendingStroke = null },
                    )
                }
                .pointerInput(state.cellSize) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pos = event.changes.firstOrNull()?.position
                            if (pos != null) { state.hoverX = (pos.x / state.cellSize).toInt(); state.hoverY = (pos.y / state.cellSize).toInt() }
                        }
                    }
                }
        ) {
            drawMinimapGrid(state.displayData, state.cellSize, state.showGrid, state.showRoomOverlay,
                state.showStationOverlay, state.stationData, state.areaRooms, state.hoverX, state.hoverY)
        }
    }
}

// ─── Canvas drawing ───

private fun DrawScope.drawMinimapGrid(
    data: MinimapData, cs: Float, showGrid: Boolean, showRooms: Boolean,
    showStation: Boolean, stationData: MapStationData, rooms: List<Room>, hx: Int, hy: Int,
) {
    val pal = arrayOf(Color(0xFF18182C), Color(0xFF3060A0), Color(0xFFC0C0D0), Color(0xFFA03030))
    for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH) {
        val w = data.getTile(x, y); val idx = MinimapData.tileIndex(w); val p = MinimapData.tilePalette(w)
        if (w == 0 || idx in EMPTY_TILES) continue
        val px = x * cs; val py = y * cs
        drawRect(pal[p.coerceIn(0, 3)], Offset(px, py), Size(cs, cs))
        drawTileDetails(idx, px, py, cs)
    }
    if (showGrid) {
        val g = Color.White.copy(alpha = 0.06f)
        for (x in 0..MinimapData.MAP_WIDTH) drawLine(g, Offset(x * cs, 0f), Offset(x * cs, MinimapData.MAP_HEIGHT * cs))
        for (y in 0..MinimapData.MAP_HEIGHT) drawLine(g, Offset(0f, y * cs), Offset(MinimapData.MAP_WIDTH * cs, y * cs))
    }
    if (showStation) for (y in 0 until MinimapData.MAP_HEIGHT) for (x in 0 until MinimapData.MAP_WIDTH)
        if (stationData.isRevealed(x, y)) drawRect(Color(0x40FF69B4), Offset(x * cs, y * cs), Size(cs, cs))
    if (showRooms) for (room in rooms) {
        val rx = room.mapX * cs; val ry = room.mapY * cs
        val rw = room.width * cs; val rh = room.height * cs
        drawRect(Color(0x3000FF88), Offset(rx, ry), Size(rw, rh))
        drawRect(Color(0xAA00FF88), Offset(rx, ry), Size(rw, rh), style = Stroke(1f))
    }
    if (hx in 0 until MinimapData.MAP_WIDTH && hy in 0 until MinimapData.MAP_HEIGHT)
        drawRect(Color.White.copy(alpha = 0.3f), Offset(hx * cs, hy * cs), Size(cs, cs), style = Stroke(2f))
}

private fun DrawScope.drawTileDetails(t: Int, px: Float, py: Float, cs: Float) {
    val wc = Color.White.copy(alpha = 0.8f); val w = (cs / 6f).coerceAtLeast(1f)
    if (t in setOf(0x20,0x21,0x22,0x28,0x29,0x2A,0x2C,0x77,0x7B,0x7C)) drawLine(wc, Offset(px,py+.5f), Offset(px+cs,py+.5f), w)
    if (t in setOf(0x23,0x24,0x26,0x28,0x29,0x2B,0x2C,0x78,0x7D,0x7E)) drawLine(wc, Offset(px,py+cs-.5f), Offset(px+cs,py+cs-.5f), w)
    if (t in setOf(0x20,0x24,0x25,0x28,0x2A,0x2B,0x2C,0x79,0x7B,0x7D)) drawLine(wc, Offset(px+.5f,py), Offset(px+.5f,py+cs), w)
    if (t in setOf(0x22,0x26,0x27,0x29,0x2A,0x2B,0x2C,0x7A,0x7C,0x7E)) drawLine(wc, Offset(px+cs-.5f,py), Offset(px+cs-.5f,py+cs), w)
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
        modifier = Modifier.size(22.dp).clickable(onClick = onClick)
            .border(if (sel) 1.5.dp else 0.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(3.dp)),
        color = Color.Transparent, shape = RoundedCornerShape(3.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, modifier = Modifier.size(14.dp),
                tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun MmIconBtn(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(22.dp)) {
        Icon(icon, label, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun MinimapTilePalette(selectedTile: Int, onSelect: (Int) -> Unit) {
    val tiles = MinimapTiles.PALETTE_TILES
    val cols = 7
    val cellDp = 20.dp
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
                                drawRect(Color(0xFF3060A0), Offset.Zero, Size(size.width, size.height))
                                drawTileDetails(tile, 0f, 0f, size.width)
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
