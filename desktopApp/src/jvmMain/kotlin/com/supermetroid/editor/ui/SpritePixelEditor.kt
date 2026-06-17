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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlin.math.abs

private val IS_MAC = System.getProperty("os.name", "").lowercase().contains("mac")
private val MOD_KEY = if (IS_MAC) "Cmd" else "Ctrl"

/**
 * General-purpose pixel editor for ARGB images (enemy sprites, tile sheets, etc.)
 *
 * Tools:   P=Pencil  E=Eraser  F=Fill  I=Eyedropper  S=Select
 * Select:  drag to marquee, Ctrl/Cmd+A=select all, Esc=deselect, Del=delete pixels
 * Copy:    Ctrl/Cmd+C=copy selection, Ctrl/Cmd+V=paste, P=capture selection as stamp
 * Edit:    H=Flip H  V=Flip V  R=Rotate CW  Shift+R=Rotate CCW
 * History: Ctrl/Cmd+Z=Undo  Ctrl/Cmd+Y=Redo
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

    val state = remember(label) {
        val palette = if (fixedPalette != null) fixedPalette.toList()
        else {
            val unique = linkedSetOf<Int>()
            unique.add(0x00000000)
            initialPixels.forEach { argb ->
                if ((argb ushr 24) and 0xFF < 128) unique.add(0x00000000)
                else unique.add(argb or (0xFF shl 24))
            }
            unique.take(256).toList()
        }
        SpritePixelEditorState(imageWidth, imageHeight, initialPixels, palette)
    }

    var zoomLevel by remember { mutableStateOf(8) }
    var showGrid by remember { mutableStateOf(true) }
    var showTileGrid by remember { mutableStateOf(fixedPalette != null) }
    var editVersion by remember { mutableStateOf(0) }
    var isDrawing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Force recomposition when state changes
    fun refreshVersion() { editVersion = state.editVersion }

    fun stopDrawing(commit: Boolean = true): Boolean {
        val wasDrawing = isDrawing
        isDrawing = false
        if (wasDrawing && commit && state.activeTool != PixelTool.SELECT) {
            state.commitPending()
        }
        return wasDrawing
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF1A1A2E))
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val ctrl = event.isCtrlPressed || event.isMetaPressed
                val shift = event.isShiftPressed
                when (event.key) {
                    Key.P -> {
                        if (state.selActive && state.clipboardPixels == null) {
                            state.captureSelection()
                        }
                        state.selActive = false
                        state.activeTool = PixelTool.PENCIL
                        refreshVersion(); true
                    }
                    Key.E -> { state.activeTool = PixelTool.ERASER; true }
                    Key.F -> if (!ctrl) { state.activeTool = PixelTool.FILL; true } else false
                    Key.I -> { state.activeTool = PixelTool.EYEDROPPER; true }
                    Key.S -> if (!ctrl) { state.activeTool = PixelTool.SELECT; true } else false
                    Key.H -> { state.applyTransform(Transform.FLIP_H); refreshVersion(); true }
                    Key.V -> if (!ctrl) {
                        state.applyTransform(Transform.FLIP_V); refreshVersion(); true
                    } else {
                        state.pasteClipboard(); refreshVersion(); true
                    }
                    Key.R -> if (!ctrl) {
                        state.applyTransform(if (shift) Transform.ROTATE_CCW else Transform.ROTATE_CW)
                        refreshVersion(); true
                    } else false
                    Key.C -> if (ctrl) { state.captureSelection(); true } else false
                    Key.X -> if (ctrl) {
                        state.captureSelection(); state.deleteSelection(); refreshVersion(); true
                    } else false
                    Key.Z -> if (ctrl) { state.undo(); refreshVersion(); true } else false
                    Key.Y -> if (ctrl) { state.redo(); refreshVersion(); true } else false
                    Key.A -> if (ctrl) {
                        state.selActive = true; state.selX1 = 0; state.selY1 = 0
                        state.selX2 = imageWidth - 1; state.selY2 = imageHeight - 1; true
                    } else false
                    Key.Delete, Key.Backspace -> {
                        if (state.selActive) { state.deleteSelection(); refreshVersion(); true }
                        else false
                    }
                    Key.Escape -> {
                        state.selActive = false; state.clearClipboard()
                        state.activeTool = PixelTool.PENCIL; true
                    }
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
                FilterChip(selected = state.activeTool == PixelTool.PENCIL, onClick = { state.activeTool = PixelTool.PENCIL },
                    label = { Icon(Icons.Default.Brush, "Pencil (P)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = state.activeTool == PixelTool.ERASER, onClick = { state.activeTool = PixelTool.ERASER },
                    label = { Icon(Icons.Default.Clear, "Eraser (E)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = state.activeTool == PixelTool.FILL, onClick = { state.activeTool = PixelTool.FILL },
                    label = { Icon(Icons.Default.FormatColorFill, "Fill (F)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = state.activeTool == PixelTool.EYEDROPPER, onClick = { state.activeTool = PixelTool.EYEDROPPER },
                    label = { Icon(Icons.Default.Colorize, "Eyedrop (I)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = state.activeTool == PixelTool.SELECT, onClick = { state.activeTool = PixelTool.SELECT },
                    label = { Icon(Icons.Default.Crop, "Select (S)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))

                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                // Transform buttons (apply to selection or full image)
                SmallButton("Flip H", "H") { state.applyTransform(Transform.FLIP_H); refreshVersion() }
                SmallButton("Flip V", "V") { state.applyTransform(Transform.FLIP_V); refreshVersion() }
                SmallButton("↻", "R") { state.applyTransform(Transform.ROTATE_CW); refreshVersion() }
                SmallButton("↺", "⇧R") { state.applyTransform(Transform.ROTATE_CCW); refreshVersion() }

                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                IconButton(onClick = { state.undo(); refreshVersion() }, enabled = state.undoStack.isNotEmpty(), modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Undo, "Undo",  Modifier.size(16.dp),
                        tint = if (state.undoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
                IconButton(onClick = { state.redo(); refreshVersion() }, enabled = state.redoStack.isNotEmpty(), modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Redo, "Redo", Modifier.size(16.dp),
                        tint = if (state.redoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
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
                        state.commitPending()
                        onApply(state.pixels)
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
                        .pointerHoverIcon(PixelEditorCursors.forPixelTool(state.activeTool))
                        .onPointerEvent(PointerEventType.Scroll) { e ->
                            if (isZoomModifierPressed(e.nativeEvent)) {
                                val scrollDelta = e.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                                zoomLevel = zoomAfterScroll(
                                    currentZoom = zoomLevel.toFloat(),
                                    scrollDeltaY = scrollDelta.y,
                                    minZoom = 2f,
                                    maxZoom = 48f
                                ).toInt().coerceIn(2, 48)
                            }
                        }
                        .onPointerEvent(PointerEventType.Move) { e ->
                            val pos = e.changes.first().position
                            val px = (pos.x / zoomLevel).toInt().coerceIn(0, imageWidth - 1)
                            val py = (pos.y / zoomLevel).toInt().coerceIn(0, imageHeight - 1)
                            hoverX = px; hoverY = py
                            if (isDrawing && !isPrimaryPointerStillDown(e.nativeEvent)) {
                                stopDrawing()
                                refreshVersion()
                                return@onPointerEvent
                            }
                            if (isDrawing) {
                                when (state.activeTool) {
                                    PixelTool.PENCIL -> { if (!state.isStampMode) state.drawPixel(px, py) }
                                    PixelTool.ERASER -> state.drawPixel(px, py)
                                    PixelTool.SELECT -> { state.selX2 = px; state.selY2 = py }
                                    else -> {}
                                }
                                refreshVersion()
                            }
                        }
                        .onPointerEvent(PointerEventType.Press) { e ->
                            if (!isPrimaryPointerPress(e.nativeEvent)) return@onPointerEvent
                            focusRequester.requestFocus()
                            val pos = e.changes.first().position
                            val px = (pos.x / zoomLevel).toInt().coerceIn(0, imageWidth - 1)
                            val py = (pos.y / zoomLevel).toInt().coerceIn(0, imageHeight - 1)
                            when (state.activeTool) {
                                PixelTool.PENCIL -> {
                                    if (state.isStampMode) {
                                        isDrawing = false
                                        state.pasteAt(px, py)
                                    } else {
                                        isDrawing = true
                                        state.drawPixel(px, py)
                                    }
                                }
                                PixelTool.ERASER -> {
                                    isDrawing = true
                                    state.drawPixel(px, py)
                                }
                                PixelTool.FILL -> {
                                    isDrawing = false
                                    state.floodFill(px, py)
                                }
                                PixelTool.EYEDROPPER -> {
                                    isDrawing = false
                                    state.eyedrop(px, py)
                                }
                                PixelTool.SELECT -> {
                                    isDrawing = true
                                    state.selActive = true
                                    state.selX1 = px; state.selY1 = py
                                    state.selX2 = px; state.selY2 = py
                                }
                            }
                            refreshVersion()
                        }
                        .onPointerEvent(PointerEventType.Release) {
                            val wasDrawing = stopDrawing()
                            // If selection is just a click (zero size), clear it
                            if (wasDrawing && state.activeTool == PixelTool.SELECT && state.selX1 == state.selX2 && state.selY1 == state.selY2) {
                                state.selActive = false
                            }
                            refreshVersion()
                        }
                        .onPointerEvent(PointerEventType.Exit) {
                            stopDrawing()
                            hoverX = -1; hoverY = -1
                            refreshVersion()
                        }
                ) {
                    // Draw pixels
                    for (py in 0 until imageHeight) {
                        for (px in 0 until imageWidth) {
                            val argb = state.pixels[py * imageWidth + px]
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
                    if (state.selActive) {
                        val sx = state.selLeft() * zoomLevel.toFloat()
                        val sy = state.selTop() * zoomLevel.toFloat()
                        val sw = (state.selRight() - state.selLeft() + 1) * zoomLevel.toFloat()
                        val sh = (state.selBottom() - state.selTop() + 1) * zoomLevel.toFloat()
                        val stroke = Stroke(
                            width = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                        )
                        drawRect(Color.White, Offset(sx, sy), Size(sw, sh), style = stroke)
                        drawRect(Color(0xFF000000), Offset(sx + 1f, sy + 1f), Size(sw - 2f, sh - 2f), style = stroke)
                    }

                    // Stamp hover preview (semi-transparent clipboard at cursor)
                    val clip = state.clipboardPixels
                    if (state.isStampMode && clip != null && hoverX >= 0 && hoverY >= 0) {
                        for (row in 0 until state.clipboardHeight) {
                            for (col in 0 until state.clipboardWidth) {
                                val imgX = hoverX + col; val imgY = hoverY + row
                                if (imgX >= imageWidth || imgY >= imageHeight) continue
                                val argb = clip[row * state.clipboardWidth + col]
                                val alpha = (argb ushr 24) and 0xFF
                                if (alpha == 0) continue
                                // Draw with 50% opacity preview
                                val previewColor = Color(
                                    red = ((argb shr 16) and 0xFF) / 255f,
                                    green = ((argb shr 8) and 0xFF) / 255f,
                                    blue = (argb and 0xFF) / 255f,
                                    alpha = 0.5f
                                )
                                drawRect(previewColor,
                                    Offset(imgX * zoomLevel.toFloat(), imgY * zoomLevel.toFloat()),
                                    Size(zoomLevel.toFloat(), zoomLevel.toFloat()))
                            }
                        }
                        // Draw outline around stamp preview
                        val prevW = state.clipboardWidth.coerceAtMost(imageWidth - hoverX)
                        val prevH = state.clipboardHeight.coerceAtMost(imageHeight - hoverY)
                        drawRect(Color(0xAAFFFFFF),
                            Offset(hoverX * zoomLevel.toFloat(), hoverY * zoomLevel.toFloat()),
                            Size(prevW * zoomLevel.toFloat(), prevH * zoomLevel.toFloat()),
                            style = Stroke(1.5f))
                    } else if (hoverX >= 0 && hoverY >= 0) {
                        // Single pixel hover highlight
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
                    state.palette.forEach { argb ->
                        val alpha = (argb ushr 24) and 0xFF
                        val isSelected = argb == state.selectedColorArgb
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .clickable {
                                    focusRequester.requestFocus()
                                    state.selectedColorArgb = argb
                                }
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
                            if ((state.selectedColorArgb ushr 24) and 0xFF == 0) Color(0xFF666666)
                            else Color(state.selectedColorArgb)
                        )
                        .border(1.dp, Color(0x60FFFFFF), RoundedCornerShape(4.dp))
                )
                val selAlpha = (state.selectedColorArgb ushr 24) and 0xFF
                if (selAlpha == 0) Text("Transparent", fontSize = 9.sp, color = Color(0xFF888888))
                else {
                    val r = (state.selectedColorArgb shr 16) and 0xFF
                    val g = (state.selectedColorArgb shr 8) and 0xFF
                    val b = state.selectedColorArgb and 0xFF
                    Text("#%02X%02X%02X".format(r, g, b), fontSize = 9.sp, color = Color(0xFFAAAAAA))
                }

                Divider(color = Color(0xFF333355))

                // Dimensions & info
                Text("${imageWidth}×${imageHeight}px", fontSize = 9.sp, color = Color(0xFF888888))
                Text("${state.palette.size} colors", fontSize = 9.sp, color = Color(0xFF888888))
                Text("${state.undoStack.size} undo steps", fontSize = 9.sp, color = Color(0xFF666688))
                if (state.isStampMode) {
                    Text("Stamp: ${state.clipboardWidth}×${state.clipboardHeight}", fontSize = 9.sp, color = Color(0xFFAACC88))
                }

                Divider(color = Color(0xFF333355))
                Text("Shortcuts", fontSize = 9.sp, color = Color(0xFF888888), fontWeight = FontWeight.Medium)
                val shortcuts = listOf(
                    "P" to "Pencil/Stamp",
                    "E" to "Eraser",
                    "F" to "Fill",
                    "I" to "Pick",
                    "S" to "Select",
                    "Del" to "Delete sel",
                    "H" to "Flip H",
                    "V" to "Flip V",
                    "R" to "Rot ↻",
                    "⇧R" to "Rot ↺",
                    "${MOD_KEY}+C" to "Copy",
                    "${MOD_KEY}+V" to "Paste",
                    "${MOD_KEY}+X" to "Cut",
                    "${MOD_KEY}+Z/Y" to "Undo/Redo",
                    "${MOD_KEY}+A" to "Sel All",
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

                if (state.selActive) {
                    Divider(color = Color(0xFF333355))
                    val w = abs(state.selX2 - state.selX1) + 1
                    val h = abs(state.selY2 - state.selY1) + 1
                    Text("Selection: ${w}×${h}", fontSize = 9.sp, color = Color(0xFFAABBCC))
                    Text("(${state.selLeft()}, ${state.selTop()})", fontSize = 8.sp, color = Color(0xFF888888))
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
