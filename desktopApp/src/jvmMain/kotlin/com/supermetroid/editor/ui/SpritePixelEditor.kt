package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.LinkedList
import kotlin.math.abs

private data class SpritePixelEdit(val x: Int, val y: Int, val oldArgb: Int, val newArgb: Int)

private enum class Transform { FLIP_H, FLIP_V, ROTATE_CW, ROTATE_CCW }

/**
 * General-purpose pixel editor for ARGB images (enemy sprites, tile sheets, etc.)
 *
 * Tools:   D=Pencil  E=Eraser  G=Fill  I=Eyedropper  S=Select
 * Select:  drag to marquee, Ctrl+A=select all, Esc=deselect
 * Edit:    H=Flip H  V=Flip V  R=Rotate CW  Shift+R=Rotate CCW
 * History: Ctrl+Z=Undo  Ctrl+Y=Redo
 * Zoom:    +/-
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpritePixelEditor(
    label: String,
    initialPixels: IntArray,
    imageWidth: Int,
    imageHeight: Int,
    /** Optional fixed 16-color palette (for 4bpp tile sheets). null = derive from image. */
    fixedPalette: IntArray? = null,
    /**
     * Optional reference image shown in the right panel. Use this to display
     * the assembled/final sprite so the user can compare while editing raw tiles.
     */
    referenceImage: ImageBitmap? = null,
    onApply: (pixels: IntArray) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val pixels = remember(label) { initialPixels.copyOf() }

    // Palette: use fixed (for tile sheet) or extract from image
    val palette = remember(label) {
        if (fixedPalette != null) fixedPalette.toList()
        else {
            val unique = linkedSetOf<Int>()
            unique.add(0x00000000)
            pixels.forEach { argb ->
                if ((argb ushr 24) and 0xFF < 128) unique.add(0x00000000)
                else unique.add(argb or (0xFF shl 24))
            }
            unique.take(256).toList()
        }
    }

    var activeTool by remember { mutableStateOf(PixelTool.PENCIL) }
    var selectedColorArgb by remember { mutableStateOf(if (palette.size > 1) palette[1] else 0xFF000000.toInt()) }
    var zoomLevel by remember { mutableStateOf(8) }
    var showGrid by remember { mutableStateOf(true) }
    var showTileGrid by remember { mutableStateOf(fixedPalette != null) }
    var editVersion by remember { mutableStateOf(0) }

    // Selection state (pixel coordinates, inclusive)
    var selActive by remember { mutableStateOf(false) }
    var selX1 by remember { mutableStateOf(0) }
    var selY1 by remember { mutableStateOf(0) }
    var selX2 by remember { mutableStateOf(0) }
    var selY2 by remember { mutableStateOf(0) }
    var selDragging by remember { mutableStateOf(false) }

    val undoStack = remember { mutableStateListOf<List<SpritePixelEdit>>() }
    val redoStack = remember { mutableStateListOf<List<SpritePixelEdit>>() }
    var pendingEdits by remember { mutableStateOf(mutableListOf<SpritePixelEdit>()) }
    var isDrawing by remember { mutableStateOf(false) }

    fun selLeft()   = if (selActive) minOf(selX1, selX2).coerceIn(0, imageWidth - 1)  else 0
    fun selTop()    = if (selActive) minOf(selY1, selY2).coerceIn(0, imageHeight - 1) else 0
    fun selRight()  = if (selActive) maxOf(selX1, selX2).coerceIn(0, imageWidth - 1)  else imageWidth - 1
    fun selBottom() = if (selActive) maxOf(selY1, selY2).coerceIn(0, imageHeight - 1) else imageHeight - 1

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
        for (e in batch.reversed()) pixels[e.y * imageWidth + e.x] = e.oldArgb
        redoStack.add(batch)
        editVersion++
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val batch = redoStack.removeLast()
        for (e in batch) pixels[e.y * imageWidth + e.x] = e.newArgb
        undoStack.add(batch)
        editVersion++
    }

    fun drawPixel(px: Int, py: Int) {
        if (px !in 0 until imageWidth || py !in 0 until imageHeight) return
        val newArgb = if (activeTool == PixelTool.ERASER) 0x00000000 else selectedColorArgb
        val idx = py * imageWidth + px
        val old = pixels[idx]
        if (old == newArgb) return
        pixels[idx] = newArgb
        pendingEdits.add(SpritePixelEdit(px, py, old, newArgb))
        editVersion++
    }

    fun floodFill(startX: Int, startY: Int) {
        if (startX !in 0 until imageWidth || startY !in 0 until imageHeight) return
        val fillArgb = if (activeTool == PixelTool.ERASER) 0x00000000 else selectedColorArgb
        val targetArgb = pixels[startY * imageWidth + startX]
        if (targetArgb == fillArgb) return
        val queue = LinkedList<Pair<Int, Int>>()
        val visited = mutableSetOf<Pair<Int, Int>>()
        queue.add(startX to startY)
        val batch = mutableListOf<SpritePixelEdit>()
        while (queue.isNotEmpty()) {
            val (x, y) = queue.poll()
            if (x !in 0 until imageWidth || y !in 0 until imageHeight) continue
            if (x to y in visited) continue
            visited.add(x to y)
            if (pixels[y * imageWidth + x] != targetArgb) continue
            pixels[y * imageWidth + x] = fillArgb
            batch.add(SpritePixelEdit(x, y, targetArgb, fillArgb))
            queue.add(x - 1 to y); queue.add(x + 1 to y)
            queue.add(x to y - 1); queue.add(x to y + 1)
        }
        if (batch.isNotEmpty()) { undoStack.add(batch); redoStack.clear() }
        editVersion++
    }

    fun eyedrop(px: Int, py: Int) {
        if (px !in 0 until imageWidth || py !in 0 until imageHeight) return
        selectedColorArgb = pixels[py * imageWidth + px].let {
            if ((it ushr 24) == 0) 0x00000000 else it or (0xFF shl 24)
        }
        activeTool = PixelTool.PENCIL
    }

    fun applyTransform(transform: Transform) {
        val sx = selLeft();  val sy = selTop()
        val ex = selRight(); val ey = selBottom()
        val selW = ex - sx + 1
        val selH = ey - sy + 1

        // Extract region
        val src = Array(selH) { row -> IntArray(selW) { col -> pixels[(sy + row) * imageWidth + (sx + col)] } }

        // Transform
        val (dstW, dstH, dst) = when (transform) {
            Transform.FLIP_H -> Triple(selW, selH,
                Array(selH) { row -> IntArray(selW) { col -> src[row][selW - 1 - col] } })
            Transform.FLIP_V -> Triple(selW, selH,
                Array(selH) { row -> IntArray(selW) { col -> src[selH - 1 - row][col] } })
            Transform.ROTATE_CW -> Triple(selH, selW,
                Array(selW) { row -> IntArray(selH) { col -> src[selH - 1 - col][row] } })
            Transform.ROTATE_CCW -> Triple(selH, selW,
                Array(selW) { row -> IntArray(selH) { col -> src[col][selW - 1 - row] } })
        }

        // Apply: clear original, write transformed. Build unified undo batch.
        val batch = mutableListOf<SpritePixelEdit>()

        // Clear original region
        for (row in 0 until selH) for (col in 0 until selW) {
            val imgIdx = (sy + row) * imageWidth + (sx + col)
            val old = pixels[imgIdx]
            pixels[imgIdx] = 0
            batch.add(SpritePixelEdit(sx + col, sy + row, old, 0))
        }
        // Write transformed (clip to image bounds)
        for (row in 0 until dstH) for (col in 0 until dstW) {
            val imgX = sx + col; val imgY = sy + row
            if (imgX >= imageWidth || imgY >= imageHeight) continue
            val newArgb = dst[row][col]
            if (newArgb == 0) continue
            val imgIdx = imgY * imageWidth + imgX
            // Update existing batch entry for this pixel (was cleared to 0 above)
            val batchIdx = batch.indexOfFirst { e -> e.x == imgX && e.y == imgY && e.newArgb == 0 }
            if (batchIdx >= 0) batch[batchIdx] = batch[batchIdx].copy(newArgb = newArgb)
            else batch.add(SpritePixelEdit(imgX, imgY, pixels[imgIdx], newArgb))
            pixels[imgIdx] = newArgb
        }

        if (batch.isNotEmpty()) { undoStack.add(batch); redoStack.clear() }
        editVersion++
    }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF1A1A2E))
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val ctrl = event.isCtrlPressed || event.isMetaPressed
                val shift = event.isShiftPressed
                when (event.key) {
                    Key.D -> { activeTool = PixelTool.PENCIL; true }
                    Key.E -> { activeTool = PixelTool.ERASER; true }
                    Key.G -> if (!ctrl) { activeTool = PixelTool.FILL; true } else false
                    Key.I -> { activeTool = PixelTool.EYEDROPPER; true }
                    Key.S -> if (!ctrl) { activeTool = PixelTool.SELECT; true } else false
                    Key.H -> { applyTransform(Transform.FLIP_H); true }
                    Key.V -> { applyTransform(Transform.FLIP_V); true }
                    Key.R -> if (!ctrl) { applyTransform(if (shift) Transform.ROTATE_CCW else Transform.ROTATE_CW); true } else false
                    Key.Z -> if (ctrl) { undo(); true } else false
                    Key.Y -> if (ctrl) { redo(); true } else false
                    Key.A -> if (ctrl) {
                        selActive = true; selX1 = 0; selY1 = 0
                        selX2 = imageWidth - 1; selY2 = imageHeight - 1; true
                    } else false
                    Key.Escape -> { selActive = false; activeTool = PixelTool.PENCIL; true }
                    Key.Equals -> { zoomLevel = (zoomLevel + 4).coerceAtMost(48); true }
                    Key.Minus  -> { zoomLevel = (zoomLevel - 4).coerceAtLeast(2);  true }
                    else -> false
                }
            }
    ) {
        // ── Toolbar ──
        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary)
                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                // Draw tools
                FilterChip(selected = activeTool == PixelTool.PENCIL, onClick = { activeTool = PixelTool.PENCIL },
                    label = { Icon(Icons.Default.Brush, "Pencil (D)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = activeTool == PixelTool.ERASER, onClick = { activeTool = PixelTool.ERASER },
                    label = { Icon(Icons.Default.Clear, "Eraser (E)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = activeTool == PixelTool.FILL, onClick = { activeTool = PixelTool.FILL },
                    label = { Icon(Icons.Default.FormatColorFill, "Fill (G)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = activeTool == PixelTool.EYEDROPPER, onClick = { activeTool = PixelTool.EYEDROPPER },
                    label = { Icon(Icons.Default.Colorize, "Eyedrop (I)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = activeTool == PixelTool.SELECT, onClick = { activeTool = PixelTool.SELECT },
                    label = { Icon(Icons.Default.Crop, "Select (S)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))

                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                // Transform buttons (apply to selection or full image)
                SmallButton("Flip H", "H") { applyTransform(Transform.FLIP_H) }
                SmallButton("Flip V", "V") { applyTransform(Transform.FLIP_V) }
                SmallButton("↻", "R") { applyTransform(Transform.ROTATE_CW) }
                SmallButton("↺", "⇧R") { applyTransform(Transform.ROTATE_CCW) }

                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                IconButton(onClick = ::undo, enabled = undoStack.isNotEmpty(), modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Undo, "Undo",  Modifier.size(16.dp),
                        tint = if (undoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
                IconButton(onClick = ::redo, enabled = redoStack.isNotEmpty(), modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Redo, "Redo", Modifier.size(16.dp),
                        tint = if (redoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }

                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                FilterChip(selected = showGrid, onClick = { showGrid = !showGrid },
                    label = { Text("Px Grid", fontSize = 9.sp) }, modifier = Modifier.height(28.dp))
                if (fixedPalette != null) {
                    FilterChip(selected = showTileGrid, onClick = { showTileGrid = !showTileGrid },
                        label = { Text("8px Grid", fontSize = 9.sp) }, modifier = Modifier.height(28.dp))
                }

                IconButton(onClick = { zoomLevel = (zoomLevel - 4).coerceAtLeast(2) },
                    enabled = zoomLevel > 2, modifier = Modifier.size(28.dp)) {
                    Text("−", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Text("${zoomLevel}x", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                IconButton(onClick = { zoomLevel = (zoomLevel + 4).coerceAtMost(48) },
                    enabled = zoomLevel < 48, modifier = Modifier.size(28.dp)) {
                    Text("+", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }

                Spacer(Modifier.weight(1f))

                Surface(
                    modifier = Modifier.height(28.dp).clickable {
                        commitPending()
                        onApply(pixels)
                    },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Apply", fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Surface(
                    modifier = Modifier.height(28.dp).clickable { onClose() },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text("Close", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ── Canvas + palette panel ──
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Pixel canvas
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
            ) {
                val canvasW = imageWidth * zoomLevel
                val canvasH = imageHeight * zoomLevel
                var hoverX by remember { mutableStateOf(-1) }
                var hoverY by remember { mutableStateOf(-1) }
                @Suppress("UNUSED_VARIABLE") val versionTrigger = editVersion

                Canvas(
                    modifier = Modifier
                        .size((canvasW / density).dp, (canvasH / density).dp)
                        .pointerHoverIcon(PixelEditorCursors.forPixelTool(activeTool))
                        .onPointerEvent(PointerEventType.Move) { e ->
                            val pos = e.changes.first().position
                            val px = (pos.x / zoomLevel).toInt().coerceIn(0, imageWidth - 1)
                            val py = (pos.y / zoomLevel).toInt().coerceIn(0, imageHeight - 1)
                            hoverX = px; hoverY = py
                            if (isDrawing) {
                                when (activeTool) {
                                    PixelTool.PENCIL, PixelTool.ERASER -> drawPixel(px, py)
                                    PixelTool.SELECT -> { selX2 = px; selY2 = py }
                                    else -> {}
                                }
                            }
                        }
                        .onPointerEvent(PointerEventType.Press) { e ->
                            val pos = e.changes.first().position
                            val px = (pos.x / zoomLevel).toInt().coerceIn(0, imageWidth - 1)
                            val py = (pos.y / zoomLevel).toInt().coerceIn(0, imageHeight - 1)
                            isDrawing = true
                            when (activeTool) {
                                PixelTool.PENCIL, PixelTool.ERASER -> drawPixel(px, py)
                                PixelTool.FILL -> floodFill(px, py)
                                PixelTool.EYEDROPPER -> eyedrop(px, py)
                                PixelTool.SELECT -> {
                                    selActive = true
                                    selX1 = px; selY1 = py; selX2 = px; selY2 = py
                                    selDragging = true
                                }
                            }
                        }
                        .onPointerEvent(PointerEventType.Release) {
                            isDrawing = false
                            selDragging = false
                            if (activeTool != PixelTool.SELECT) commitPending()
                            // If selection is just a click (zero size), clear it
                            if (activeTool == PixelTool.SELECT && selX1 == selX2 && selY1 == selY2) {
                                selActive = false
                            }
                        }
                ) {
                    // Draw pixels
                    for (py in 0 until imageHeight) {
                        for (px in 0 until imageWidth) {
                            val argb = pixels[py * imageWidth + px]
                            val alpha = (argb ushr 24) and 0xFF
                            if (alpha > 0) {
                                drawRect(Color(argb),
                                    Offset(px * zoomLevel.toFloat(), py * zoomLevel.toFloat()),
                                    Size(zoomLevel.toFloat(), zoomLevel.toFloat()))
                            } else {
                                val isDark = (px + py) % 2 == 0
                                drawRect(if (isDark) Color(0xFF666666) else Color(0xFF999999),
                                    Offset(px * zoomLevel.toFloat(), py * zoomLevel.toFloat()),
                                    Size(zoomLevel.toFloat(), zoomLevel.toFloat()))
                            }
                        }
                    }

                    // Pixel grid
                    if (showGrid && zoomLevel >= 4) {
                        val gc = Color(0x20FFFFFF)
                        for (px in 0..imageWidth)
                            drawLine(gc, Offset(px * zoomLevel.toFloat(), 0f), Offset(px * zoomLevel.toFloat(), canvasH.toFloat()))
                        for (py in 0..imageHeight)
                            drawLine(gc, Offset(0f, py * zoomLevel.toFloat()), Offset(canvasW.toFloat(), py * zoomLevel.toFloat()))
                    }

                    // 8-pixel tile grid (for tile-sheet mode)
                    if (showTileGrid) {
                        val tgc = Color(0x50FFFFFF)
                        val step = 8 * zoomLevel
                        var x = 0f
                        while (x <= canvasW) {
                            drawLine(tgc, Offset(x, 0f), Offset(x, canvasH.toFloat()), strokeWidth = 1.5f)
                            x += step
                        }
                        var y = 0f
                        while (y <= canvasH) {
                            drawLine(tgc, Offset(0f, y), Offset(canvasW.toFloat(), y), strokeWidth = 1.5f)
                            y += step
                        }
                    }

                    // Selection rect (dashed marching-ants style)
                    if (selActive) {
                        val sx = selLeft() * zoomLevel.toFloat()
                        val sy = selTop() * zoomLevel.toFloat()
                        val sw = (selRight() - selLeft() + 1) * zoomLevel.toFloat()
                        val sh = (selBottom() - selTop() + 1) * zoomLevel.toFloat()
                        val stroke = Stroke(
                            width = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                        )
                        drawRect(Color.White, Offset(sx, sy), Size(sw, sh), style = stroke)
                        drawRect(Color(0xFF000000), Offset(sx + 1f, sy + 1f), Size(sw - 2f, sh - 2f), style = stroke)
                    }

                    // Hover highlight
                    if (hoverX >= 0 && hoverY >= 0) {
                        drawRect(Color(0x70FFFFFF),
                            Offset(hoverX * zoomLevel.toFloat(), hoverY * zoomLevel.toFloat()),
                            Size(zoomLevel.toFloat(), zoomLevel.toFloat()),
                            style = Stroke(1.5f))
                    }
                }
            }

            // Right panel: palette + info
            Column(
                modifier = Modifier
                    .width(150.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF0D0D1A))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Palette", fontSize = 10.sp, color = Color(0xFFAAAAAA), fontWeight = FontWeight.Medium)

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    maxItemsInEachRow = 8
                ) {
                    palette.forEach { argb ->
                        val alpha = (argb ushr 24) and 0xFF
                        val isSelected = argb == selectedColorArgb
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .clickable { selectedColorArgb = argb }
                                .border(
                                    if (isSelected) 2.dp else 0.5.dp,
                                    if (isSelected) Color.White else Color(0x60FFFFFF),
                                    RoundedCornerShape(2.dp)
                                )
                                .background(if (alpha == 0) Color(0xFF666666) else Color(argb))
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text("Selected", fontSize = 9.sp, color = Color(0xFF888888))
                Box(
                    modifier = Modifier.fillMaxWidth().height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if ((selectedColorArgb ushr 24) and 0xFF == 0) Color(0xFF666666)
                            else Color(selectedColorArgb)
                        )
                        .border(1.dp, Color(0x60FFFFFF), RoundedCornerShape(4.dp))
                )
                val selAlpha = (selectedColorArgb ushr 24) and 0xFF
                if (selAlpha == 0) Text("Transparent", fontSize = 9.sp, color = Color(0xFF888888))
                else {
                    val r = (selectedColorArgb shr 16) and 0xFF
                    val g = (selectedColorArgb shr 8) and 0xFF
                    val b = selectedColorArgb and 0xFF
                    Text("#%02X%02X%02X".format(r, g, b), fontSize = 9.sp, color = Color(0xFFAAAAAA))
                }

                Divider(color = Color(0xFF333355))

                // Dimensions & info
                Text("${imageWidth}×${imageHeight}px", fontSize = 9.sp, color = Color(0xFF888888))
                Text("${palette.size} colors", fontSize = 9.sp, color = Color(0xFF888888))
                Text("${undoStack.size} undo steps", fontSize = 9.sp, color = Color(0xFF666688))

                Divider(color = Color(0xFF333355))
                Text("Shortcuts", fontSize = 9.sp, color = Color(0xFF888888), fontWeight = FontWeight.Medium)
                val shortcuts = listOf(
                    "D" to "Pencil",
                    "E" to "Eraser",
                    "G" to "Fill",
                    "I" to "Pick",
                    "S" to "Select",
                    "H" to "Flip H",
                    "V" to "Flip V",
                    "R" to "Rot ↻",
                    "⇧R" to "Rot ↺",
                    "^Z/Y" to "Undo/Redo",
                    "^A" to "Sel All",
                    "Esc" to "Deselect"
                )
                shortcuts.forEach { (key, action) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(key, fontSize = 8.sp, color = Color(0xFFAAAACC))
                        Text(action, fontSize = 8.sp, color = Color(0xFF888888))
                    }
                }

                if (selActive) {
                    Divider(color = Color(0xFF333355))
                    val w = abs(selX2 - selX1) + 1
                    val h = abs(selY2 - selY1) + 1
                    Text("Selection: ${w}×${h}", fontSize = 9.sp, color = Color(0xFFAABBCC))
                    Text("(${selLeft()}, ${selTop()})", fontSize = 8.sp, color = Color(0xFF888888))
                }

                // Reference image — shows the assembled/final sprite for comparison
                if (referenceImage != null) {
                    Divider(color = Color(0xFF333355))
                    Text("Reference", fontSize = 9.sp,
                        color = Color(0xFF888888), fontWeight = FontWeight.Medium)
                    Text("Assembled sprite", fontSize = 8.sp, color = Color(0xFF666688))
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = referenceImage,
                            contentDescription = "Reference sprite preview",
                            modifier = Modifier
                                .sizeIn(maxWidth = 160.dp, maxHeight = 160.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF444466), RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, hint: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.height(28.dp).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("$label ($hint)", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
