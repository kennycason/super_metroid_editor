package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.TileGraphics
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.util.LinkedList

private data class PixelEdit(val tileNum: Int, val x: Int, val y: Int, val oldIdx: Int, val newIdx: Int)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TilePixelEditor(
    tileGraphics: TileGraphics,
    editorState: EditorState,
    tilesetEditorState: TilesetEditorState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density

    // Whole 16x16 metatile being edited
    val metatileIdx = editorState.editorSelectedMetatile
    val metaWords = remember(metatileIdx) { tileGraphics.getMetatileWords(metatileIdx) }
    val defaultPalRow = metaWords?.let { (it[0] shr 10) and 7 } ?: 0

    // Drawing state
    var activeTool by remember { mutableStateOf(PixelTool.PENCIL) }
    var selectedPalRow by remember(defaultPalRow) { mutableStateOf(defaultPalRow) }
    var selectedColorIdx by remember { mutableStateOf(1) }
    var zoomLevel by remember { mutableStateOf(24) } // pixels per SNES pixel
    var editVersion by remember { mutableStateOf(0) }
    var showGrid by remember { mutableStateOf(true) }
    var showHsvPicker by remember { mutableStateOf(true) }

    // Undo/redo stacks
    val undoStack = remember { mutableStateListOf<List<PixelEdit>>() }
    val redoStack = remember { mutableStateListOf<List<PixelEdit>>() }
    var pendingEdits by remember { mutableStateOf(mutableListOf<PixelEdit>()) }
    var isDrawing by remember { mutableStateOf(false) }

    @Suppress("UNUSED_VARIABLE")
    val palVer = editorState.paletteVersion  // observe palette changes from left-column editor
    val palettes = tileGraphics.getPalettes()
    val coroutineScope = rememberCoroutineScope()

    // Sync selection from left-column palette editor (via editorState.sampledPaletteRow/Col)
    val extRow = editorState.sampledPaletteRow
    val extCol = editorState.sampledPaletteCol
    var lastExtRow by remember { mutableStateOf(-1) }
    var lastExtCol by remember { mutableStateOf(-1) }
    if (extRow in 0..7 && (extRow != lastExtRow || extCol != lastExtCol)) {
        selectedPalRow = extRow
        lastExtRow = extRow
        if (extCol in 1..15) {
            selectedColorIdx = extCol
        }
        lastExtCol = extCol
    }

    fun commitPending() {
        if (pendingEdits.isNotEmpty()) {
            undoStack.add(pendingEdits.toList())
            redoStack.clear()
            pendingEdits = mutableListOf()
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val batch = undoStack.removeLast()
        for (e in batch.reversed()) tileGraphics.writePixelIndex(e.tileNum, e.x, e.y, e.oldIdx)
        redoStack.add(batch)
        editVersion++
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val batch = redoStack.removeLast()
        for (e in batch) tileGraphics.writePixelIndex(e.tileNum, e.x, e.y, e.newIdx)
        undoStack.add(batch)
        editVersion++
    }

    fun drawPixel(px: Int, py: Int) {
        if (px !in 0..15 || py !in 0..15 || metatileIdx < 0) return
        val colorIdx = if (activeTool == PixelTool.ERASER) 0 else selectedColorIdx
        val old = tileGraphics.readMetatilePixel(metatileIdx, px, py)
        if (old == colorIdx) return
        tileGraphics.writeMetatilePixel(metatileIdx, px, py, colorIdx)
        val coords = tileGraphics.metatilePixelToTileCoords(metatileIdx, px, py) ?: return
        pendingEdits.add(PixelEdit(coords.first, coords.second, coords.third, old, colorIdx))
        editVersion++
    }

    fun floodFill(startX: Int, startY: Int) {
        if (startX !in 0..15 || startY !in 0..15 || metatileIdx < 0) return
        val targetIdx = tileGraphics.readMetatilePixel(metatileIdx, startX, startY)
        val fillIdx = if (activeTool == PixelTool.ERASER) 0 else selectedColorIdx
        if (targetIdx == fillIdx) return
        val queue = LinkedList<Pair<Int, Int>>()
        val visited = mutableSetOf<Pair<Int, Int>>()
        queue.add(startX to startY)
        val batch = mutableListOf<PixelEdit>()
        while (queue.isNotEmpty()) {
            val (x, y) = queue.poll()
            if (x !in 0..15 || y !in 0..15) continue
            if (x to y in visited) continue
            visited.add(x to y)
            val cur = tileGraphics.readMetatilePixel(metatileIdx, x, y)
            if (cur != targetIdx) continue
            tileGraphics.writeMetatilePixel(metatileIdx, x, y, fillIdx)
            val coords = tileGraphics.metatilePixelToTileCoords(metatileIdx, x, y)
            if (coords != null) batch.add(PixelEdit(coords.first, coords.second, coords.third, cur, fillIdx))
            queue.add(x - 1 to y); queue.add(x + 1 to y)
            queue.add(x to y - 1); queue.add(x to y + 1)
        }
        if (batch.isNotEmpty()) {
            undoStack.add(batch)
            redoStack.clear()
        }
        editVersion++
    }

    fun eyedrop(px: Int, py: Int) {
        if (px !in 0..15 || py !in 0..15 || metatileIdx < 0) return
        val idx = tileGraphics.readMetatilePixel(metatileIdx, px, py)
        selectedColorIdx = idx
        selectedPalRow = tileGraphics.getMetatilePixelPaletteRow(metatileIdx, px, py)
        // Sync to EditorState so left-column Palette Editor can follow
        editorState.sampledPaletteRow = selectedPalRow
        editorState.sampledPaletteCol = idx
        activeTool = PixelTool.PENCIL
    }

    // Persist edits when user navigates away or closes (metatile can touch both var and CRE)
    fun persistEdits() {
        commitPending()
        val varRaw = tileGraphics.getRawVarGfx()
        val creRaw = tileGraphics.getRawCreGfx()
        if (varRaw != null) {
            editorState.project.customGfx.varGfx[editorState.editorTilesetId.toString()] =
                java.util.Base64.getEncoder().encodeToString(varRaw)
            editorState.markDirty()
        }
        if (creRaw != null) {
            editorState.project.customGfx.creGfx = java.util.Base64.getEncoder().encodeToString(creRaw)
            editorState.markDirty()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF1A1A2E))
        .focusable()
        .onKeyEvent { event ->
            if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                val ctrl = event.isCtrlPressed || event.isMetaPressed
                when (event.key) {
                    androidx.compose.ui.input.key.Key.D -> { activeTool = PixelTool.PENCIL; true }
                    androidx.compose.ui.input.key.Key.E -> { activeTool = PixelTool.ERASER; true }
                    androidx.compose.ui.input.key.Key.G -> {
                        if (!ctrl) { activeTool = PixelTool.FILL; true } else false
                    }
                    androidx.compose.ui.input.key.Key.I -> { activeTool = PixelTool.EYEDROPPER; true }
                    androidx.compose.ui.input.key.Key.Z -> if (ctrl) { undo(); true } else false
                    androidx.compose.ui.input.key.Key.Y -> if (ctrl) { redo(); true } else false
                    androidx.compose.ui.input.key.Key.Equals -> { zoomLevel = (zoomLevel + 4).coerceAtMost(48); true }
                    androidx.compose.ui.input.key.Key.Minus -> { zoomLevel = (zoomLevel - 4).coerceAtLeast(8); true }
                    else -> false
                }
            } else false
        }
    ) {
        // ── Toolbar (match Rooms/Tileset: MaterialTheme, FilterChip, IconButton) ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Tools (FilterChip like Rooms)
                FilterChip(
                    selected = activeTool == PixelTool.PENCIL,
                    onClick = { activeTool = PixelTool.PENCIL },
                    label = { Icon(Icons.Default.Brush, null, Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = activeTool == PixelTool.ERASER,
                    onClick = { activeTool = PixelTool.ERASER },
                    label = { Icon(Icons.Default.Clear, null, Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = activeTool == PixelTool.FILL,
                    onClick = { activeTool = PixelTool.FILL },
                    label = { Icon(Icons.Default.FormatColorFill, null, Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = activeTool == PixelTool.EYEDROPPER,
                    onClick = { activeTool = PixelTool.EYEDROPPER },
                    label = { Icon(Icons.Default.Colorize, null, Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp)
                )

                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                // Undo/Redo (IconButton like Rooms)
                IconButton(onClick = { undo() }, enabled = undoStack.isNotEmpty(), modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Undo, "Undo", Modifier.size(16.dp),
                        tint = if (undoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
                IconButton(onClick = { redo() }, enabled = redoStack.isNotEmpty(), modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Redo, "Redo", Modifier.size(16.dp),
                        tint = if (redoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }

                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                FilterChip(
                    selected = showGrid,
                    onClick = { showGrid = !showGrid },
                    label = { Text("Grid", fontSize = 9.sp) },
                    modifier = Modifier.height(28.dp)
                )

                // Zoom
                IconButton(onClick = { zoomLevel = (zoomLevel - 4).coerceAtLeast(8) }, enabled = zoomLevel > 8, modifier = Modifier.size(28.dp)) {
                    Text("−", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Text("${zoomLevel}x", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
                IconButton(onClick = { zoomLevel = (zoomLevel + 4).coerceAtMost(48) }, enabled = zoomLevel < 48, modifier = Modifier.size(28.dp)) {
                    Text("+", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }

                Spacer(Modifier.weight(1f))

                // Apply & Close (Surface like Tileset Export/Import) — 9.sp to match Grid, avoid cut-off
                Surface(
                    modifier = Modifier.height(28.dp).clickable {
                        persistEdits()
                        coroutineScope.launch { tilesetEditorState.gridData = tileGraphics.renderTilesetGrid() }
                    },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text("Apply", fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 0.dp))
                }
                Surface(
                    modifier = Modifier.height(28.dp).clickable { onClose() },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text("Close", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 0.dp))
                }
            }
        }

        // ── Main content ──
        Row(modifier = Modifier.fillMaxSize()) {
            // Left: Canvas area
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Tile info bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Metatile #$metatileIdx (0x${metatileIdx.toString(16).uppercase().padStart(3, '0')}) — 16×16",
                        fontSize = 10.sp, color = Color(0xFFB0B8D1)
                    )
                    Text("│", fontSize = 10.sp, color = Color(0xFF3A3F5C))
                    Text(
                        "Palette $selectedPalRow",
                        fontSize = 10.sp, color = Color(0xFFB0B8D1)
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Pixel canvas (16×16 metatile)
                val canvasSize = 16 * zoomLevel
                var hoverPixel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

                // key forces Canvas recreation when palette data or selection changes
                key(palVer, editVersion, selectedPalRow) {
                Box(
                    modifier = Modifier
                        .size(canvasSize.dp + if (showGrid) 1.dp else 0.dp)
                        .border(1.dp, Color(0xFF3A3F5C))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerHoverIcon(PixelEditorCursors.forPixelTool(activeTool))
                            .onPointerEvent(PointerEventType.Press) { event ->
                                val pos = event.changes.first().position
                                val px = (pos.x / density / zoomLevel).toInt().coerceIn(0, 15)
                                val py = (pos.y / density / zoomLevel).toInt().coerceIn(0, 15)
                                when (activeTool) {
                                    PixelTool.PENCIL, PixelTool.ERASER -> {
                                        isDrawing = true
                                        drawPixel(px, py)
                                    }
                                    PixelTool.FILL -> floodFill(px, py)
                                    PixelTool.EYEDROPPER -> eyedrop(px, py)
                                    PixelTool.SELECT -> {} // SELECT not used in TilePixelEditor
                                }
                            }
                            .onPointerEvent(PointerEventType.Move) { event ->
                                val pos = event.changes.first().position
                                val px = (pos.x / density / zoomLevel).toInt()
                                val py = (pos.y / density / zoomLevel).toInt()
                                hoverPixel = if (px in 0..15 && py in 0..15) px to py else null
                                if (isDrawing && (activeTool == PixelTool.PENCIL || activeTool == PixelTool.ERASER)) {
                                    drawPixel(px.coerceIn(0, 15), py.coerceIn(0, 15))
                                }
                            }
                            .onPointerEvent(PointerEventType.Release) {
                                if (isDrawing) {
                                    isDrawing = false
                                    commitPending()
                                }
                            }
                            .onPointerEvent(PointerEventType.Exit) {
                                hoverPixel = null
                            }
                    ) {
                        val cellW = size.width / 16f
                        val cellH = size.height / 16f
                        val pal = palettes ?: return@Canvas

                        for (y in 0..15) {
                            for (x in 0..15) {
                                val idx = tileGraphics.readMetatilePixel(metatileIdx, x, y)
                                val argb = if (idx == 0) null else pal[selectedPalRow][idx]
                                val left = x * cellW
                                val top = y * cellH

                                if (argb == null) {
                                    val halfW = cellW / 2
                                    val halfH = cellH / 2
                                    drawRect(Color(0xFF404040), Offset(left, top), Size(halfW, halfH))
                                    drawRect(Color(0xFF606060), Offset(left + halfW, top), Size(halfW, halfH))
                                    drawRect(Color(0xFF606060), Offset(left, top + halfH), Size(halfW, halfH))
                                    drawRect(Color(0xFF404040), Offset(left + halfW, top + halfH), Size(halfW, halfH))
                                } else {
                                    val r = (argb shr 16) and 0xFF
                                    val g = (argb shr 8) and 0xFF
                                    val b = argb and 0xFF
                                    drawRect(Color(r / 255f, g / 255f, b / 255f), Offset(left, top), Size(cellW, cellH))
                                }
                            }
                        }

                        // Grid lines
                        if (showGrid) {
                            val gridColor = Color(0x40FFFFFF)
                            for (i in 1..15) {
                                drawLine(gridColor, Offset(i * cellW, 0f), Offset(i * cellW, size.height), 1f)
                                drawLine(gridColor, Offset(0f, i * cellH), Offset(size.width, i * cellH), 1f)
                            }
                        }

                        // Hover highlight
                        val hp = hoverPixel
                        if (hp != null) {
                            drawRect(
                                Color.White.copy(alpha = 0.3f),
                                Offset(hp.first * cellW, hp.second * cellH),
                                Size(cellW, cellH)
                            )
                        }
                    }
                }
                } // key(palVer, editVersion)

                // Hover info
                val hp = hoverPixel
                if (hp != null && metatileIdx >= 0) {
                    val idx = tileGraphics.readMetatilePixel(metatileIdx, hp.first, hp.second)
                    val palRow = tileGraphics.getMetatilePixelPaletteRow(metatileIdx, hp.first, hp.second)
                    val snBgr = tileGraphics.getSnesBgr555(palRow, idx)
                    Text(
                        "Pixel (${hp.first}, ${hp.second})  |  Index: $idx  |  SNES: \$${snBgr.toString(16).uppercase().padStart(4, '0')}",
                        fontSize = 9.sp, color = Color(0xFF8890A8),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Text(" ", fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            // Right: Palette panel
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF2A2D45)))

            Column(
                modifier = Modifier.width(280.dp).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                // Active colour preview
                Text("Active Colour", fontSize = 10.sp, color = Color(0xFFB0B8D1),
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                if (palettes != null) {
                    val activeArgb = palettes[selectedPalRow][selectedColorIdx]
                    val ar = (activeArgb shr 16) and 0xFF
                    val ag = (activeArgb shr 8) and 0xFF
                    val ab = activeArgb and 0xFF
                    val snVal = tileGraphics.getSnesBgr555(selectedPalRow, selectedColorIdx)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier.size(32.dp)
                                .background(
                                    if (selectedColorIdx == 0) Color(0xFF404040)
                                    else Color(ar / 255f, ag / 255f, ab / 255f),
                                    RoundedCornerShape(4.dp)
                                )
                                .border(1.dp, Color(0xFF5A5F7C), RoundedCornerShape(4.dp))
                        ) {
                            if (selectedColorIdx == 0) {
                                Text("∅", fontSize = 14.sp, color = Color(0xFFAAAAAA),
                                    modifier = Modifier.align(Alignment.Center))
                            }
                        }
                        Column {
                            Text(
                                "Index $selectedColorIdx  |  Palette $selectedPalRow",
                                fontSize = 9.sp, color = Color(0xFFB0B8D1)
                            )
                            Text(
                                "SNES: \$${snVal.toString(16).uppercase().padStart(4, '0')}  " +
                                        "RGB: ($ar, $ag, $ab)",
                                fontSize = 8.sp, color = Color(0xFF7880A0),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Palette grid
                Text("Palette", fontSize = 10.sp, color = Color(0xFFB0B8D1),
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))

                if (palettes != null) {
                    for (palRow in 0..7) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(22.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Row label
                            Text(
                                "$palRow",
                                fontSize = 8.sp,
                                color = if (palRow == selectedPalRow) Color(0xFFFFD54F) else Color(0xFF6A6F88),
                                modifier = Modifier.width(14.dp),
                                textAlign = TextAlign.End,
                                fontWeight = if (palRow == selectedPalRow) FontWeight.Bold else FontWeight.Normal
                            )
                            Spacer(Modifier.width(4.dp))

                            for (colIdx in 0..15) {
                                val argb = palettes[palRow][colIdx]
                                val r = (argb shr 16) and 0xFF
                                val g = (argb shr 8) and 0xFF
                                val b = argb and 0xFF
                                val isSelected = palRow == selectedPalRow && colIdx == selectedColorIdx

                                Box(
                                    modifier = Modifier
                                        .weight(1f).fillMaxHeight()
                                        .padding(0.5.dp)
                                        .then(
                                            if (isSelected) Modifier.border(2.dp, Color.White, RoundedCornerShape(2.dp))
                                            else Modifier
                                        )
                                        .background(
                                            if (colIdx == 0) Color(0xFF303040) else Color(r / 255f, g / 255f, b / 255f),
                                            RoundedCornerShape(2.dp)
                                        )
                                        .clickable {
                                            selectedPalRow = palRow
                                            selectedColorIdx = colIdx
                                            editorState.sampledPaletteRow = palRow
                                            editorState.sampledPaletteCol = colIdx
                                        }
                                ) {
                                    if (colIdx == 0 && isSelected) {
                                        Text("∅", fontSize = 7.sp, color = Color(0xFFAAAAAA),
                                            modifier = Modifier.align(Alignment.Center))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // SNES Colour Picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SNES Colour Editor", fontSize = 10.sp, color = Color(0xFFB0B8D1),
                        fontWeight = FontWeight.SemiBold)
                    if (selectedColorIdx != 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("HSV", fontSize = 9.sp,
                                fontWeight = if (showHsvPicker) FontWeight.Bold else FontWeight.Normal,
                                color = if (showHsvPicker) Color(0xFF64B5F6) else Color(0xFF6A6F88),
                                modifier = Modifier.clickable { showHsvPicker = true })
                            Text("RGB", fontSize = 9.sp,
                                fontWeight = if (!showHsvPicker) FontWeight.Bold else FontWeight.Normal,
                                color = if (!showHsvPicker) Color(0xFF64B5F6) else Color(0xFF6A6F88),
                                modifier = Modifier.clickable { showHsvPicker = false })
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (selectedColorIdx == 0) {
                    Text("Index 0 is transparent (cannot edit)", fontSize = 9.sp, color = Color(0xFF6A6F88))
                } else if (palettes != null) {
                    val bgr555 = tileGraphics.getSnesBgr555(selectedPalRow, selectedColorIdx)
                    if (bgr555 >= 0) {
                        if (showHsvPicker) {
                            HsvColorPicker(bgr555, onColorChanged = { newBgr ->
                                tileGraphics.setPaletteEntry(selectedPalRow, selectedColorIdx, newBgr)
                                editorState.paletteVersion++
                                editVersion++
                            })
                        } else {
                            SnesBgr555Editor(bgr555) { newBgr ->
                                tileGraphics.setPaletteEntry(selectedPalRow, selectedColorIdx, newBgr)
                                editorState.paletteVersion++
                                editVersion++
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Keyboard shortcut reference
                Text("Shortcuts", fontSize = 10.sp, color = Color(0xFFB0B8D1),
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val shortcuts = listOf(
                    "D" to "Pencil", "E" to "Eraser", "G" to "Fill",
                    "I" to "Eyedropper", "X" to "Swap fg/bg",
                    "Ctrl+Z" to "Undo", "Ctrl+Y" to "Redo",
                    "+/-" to "Zoom"
                )
                for ((key, desc) in shortcuts) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(key, fontSize = 8.sp, color = Color(0xFFFFD54F),
                            fontFamily = FontFamily.Monospace, modifier = Modifier.width(50.dp),
                            textAlign = TextAlign.End)
                        Text(desc, fontSize = 8.sp, color = Color(0xFF8890A8))
                    }
                }
            }
        }
    }
}

@Composable
private fun SnesColorEditor(
    tileGraphics: TileGraphics,
    palRow: Int,
    colIdx: Int,
    onColorChanged: () -> Unit
) {
    if (colIdx == 0) {
        Text("Index 0 is transparent (cannot edit)", fontSize = 9.sp, color = Color(0xFF6A6F88))
        return
    }

    val bgr555 = tileGraphics.getSnesBgr555(palRow, colIdx)
    if (bgr555 < 0) return

    SnesBgr555Editor(bgr555) { newBgr ->
        tileGraphics.setPaletteEntry(palRow, colIdx, newBgr)
        onColorChanged()
    }
}

/**
 * Reusable SNES BGR555 color editor with R/G/B gradient sliders.
 * Accepts a raw BGR555 value and calls [onColorChanged] with the new BGR555 on each edit.
 */
@Composable
internal fun SnesBgr555Editor(
    bgr555: Int,
    onColorChanged: (Int) -> Unit
) {
    var r5 by remember(bgr555) { mutableStateOf(bgr555 and 0x1F) }
    var g5 by remember(bgr555) { mutableStateOf((bgr555 shr 5) and 0x1F) }
    var b5 by remember(bgr555) { mutableStateOf((bgr555 shr 10) and 0x1F) }

    fun applyColor() {
        val newBgr = (b5 shl 10) or (g5 shl 5) or r5
        onColorChanged(newBgr)
    }

    // Preview of current colour
    val r8 = (r5 shl 3) or (r5 shr 2)
    val g8 = (g5 shl 3) or (g5 shr 2)
    val b8 = (b5 shl 3) or (b5 shr 2)
    val snesHex = ((b5 shl 10) or (g5 shl 5) or r5).toString(16).uppercase().padStart(4, '0')

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(28.dp)
                .background(Color(r8 / 255f, g8 / 255f, b8 / 255f), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF5A5F7C), RoundedCornerShape(4.dp))
        )
        Column {
            Text("\$$snesHex", fontSize = 10.sp, color = Color(0xFFFFD54F), fontFamily = FontFamily.Monospace)
            Text("R:$r5 G:$g5 B:$b5", fontSize = 8.sp, color = Color(0xFF8890A8), fontFamily = FontFamily.Monospace)
        }
    }
    Spacer(Modifier.height(8.dp))

    // RGB gradient sliders
    SnesChannelSlider("R", Color.Red, r5) { r5 = it; applyColor() }
    Spacer(Modifier.height(4.dp))
    SnesChannelSlider("G", Color.Green, g5) { g5 = it; applyColor() }
    Spacer(Modifier.height(4.dp))
    SnesChannelSlider("B", Color(0xFF4488FF), b5) { b5 = it; applyColor() }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SnesChannelSlider(
    label: String,
    channelColor: Color,
    value: Int,
    onValueChanged: (Int) -> Unit
) {
    val density = LocalDensity.current.density
    var isDragging by remember { mutableStateOf(false) }
    var sliderWidth by remember { mutableStateOf(1f) }

    fun posToValue(posX: Float): Int {
        val px = posX / density
        return ((px / sliderWidth) * 32).toInt().coerceIn(0, 31)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(24.dp)
    ) {
        Text(label, fontSize = 9.sp, color = channelColor, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(16.dp))

        Box(
            modifier = Modifier
                .weight(1f).height(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .onPointerEvent(PointerEventType.Press) { event ->
                    isDragging = true
                    val v = posToValue(event.changes.first().position.x)
                    if (v != value) onValueChanged(v)
                }
                .onPointerEvent(PointerEventType.Move) { event ->
                    if (isDragging) {
                        val v = posToValue(event.changes.first().position.x)
                        if (v != value) onValueChanged(v)
                    }
                }
                .onPointerEvent(PointerEventType.Release) { isDragging = false }
                .onPointerEvent(PointerEventType.Exit) { isDragging = false }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                sliderWidth = size.width / density
                val cellW = size.width / 32f
                for (i in 0..31) {
                    val fraction = i / 31f
                    val r = when (label) { "R" -> fraction; else -> 0f }
                    val g = when (label) { "G" -> fraction; else -> 0f }
                    val b = when (label) { "B" -> fraction; else -> 0f }
                    drawRect(
                        Color(r, g, b),
                        Offset(i * cellW, 0f),
                        Size(cellW + 1f, size.height)
                    )
                }
                val markerX = value * cellW + cellW / 2
                drawCircle(Color.White, 5f, Offset(markerX, size.height / 2))
                drawCircle(Color.Black, 3f, Offset(markerX, size.height / 2))
            }
        }

        Text(
            value.toString().padStart(2),
            fontSize = 9.sp, color = Color(0xFFB0B8D1),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(20.dp),
            textAlign = TextAlign.End
        )
    }
}
