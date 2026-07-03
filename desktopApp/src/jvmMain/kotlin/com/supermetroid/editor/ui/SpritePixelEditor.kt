package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import kotlin.math.abs
import kotlin.math.roundToInt

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
     * Optional reference image shown in a floating panel. Use this to display
     * the assembled/final sprite so the user can compare while editing raw tiles.
     */
    referenceImage: ImageBitmap? = null,
    /** Optional assembled reference animation frames shown in the floating reference panel. */
    referenceFrames: List<ImageBitmap> = emptyList(),
    /** Optional live reference renderer for editors where raw pixels assemble into a separate preview. */
    liveReferenceFrames: ((pixels: IntArray, width: Int, height: Int) -> List<ImageBitmap>)? = null,
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
    val liveRenderedReferenceFrames = if (liveReferenceFrames != null) {
        remember(label, editVersion, imageWidth, imageHeight) {
            liveReferenceFrames(state.pixels.copyOf(), imageWidth, imageHeight)
        }
    } else {
        emptyList()
    }
    val referencePreviewImage = liveRenderedReferenceFrames.firstOrNull()
        ?: referenceImage
        ?: referenceFrames.firstOrNull()
    val popupFrames = liveRenderedReferenceFrames.ifEmpty { referenceFrames }
    val referenceAvailable = referencePreviewImage != null
    var showReferencePopup by remember(referenceAvailable) {
        mutableStateOf(referenceAvailable)
    }
    val focusRequester = remember { FocusRequester() }

    // Force recomposition when state changes, including non-pixel state like active tool/cursor.
    fun refreshVersion() { editVersion++ }

    fun stopDrawing(commit: Boolean = true): Boolean {
        val wasDrawing = isDrawing
        isDrawing = false
        if (wasDrawing && commit && state.activeTool != PixelTool.SELECT) {
            state.commitPending()
        }
        return wasDrawing
    }

    fun setTool(tool: PixelTool) {
        focusRequester.requestFocus()
        if (tool != PixelTool.SELECT) {
            state.cancelFloatingSelection()
        }
        state.activeTool = tool
        refreshVersion()
    }

    fun applySelectionTransform(transform: Transform): Boolean {
        stopDrawing(commit = false)
        val changed = state.applyTransform(transform)
        if (changed) refreshVersion()
        return changed
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF1A1A2E))) {
        Column(
            modifier = Modifier.fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val ctrl = event.isCtrlPressed || event.isMetaPressed
                    val shift = event.isShiftPressed
                    when (event.key) {
                        Key.P, Key.B -> {
                            if (state.selActive && state.clipboardPixels == null) {
                                state.captureSelection()
                            }
                            state.selActive = false
                            setTool(PixelTool.PENCIL)
                            true
                        }
                        Key.E -> { setTool(PixelTool.ERASER); true }
                        Key.F, Key.G -> if (!ctrl) { setTool(PixelTool.FILL); true } else false
                        Key.I, Key.D -> { setTool(PixelTool.EYEDROPPER); true }
                        Key.S -> if (!ctrl) { setTool(PixelTool.SELECT); true } else false
                        Key.H -> applySelectionTransform(Transform.FLIP_H)
                        Key.V -> if (!ctrl) {
                            applySelectionTransform(Transform.FLIP_V)
                        } else {
                            state.pasteClipboard(); refreshVersion(); true
                        }
                        Key.R -> if (!ctrl) {
                            applySelectionTransform(if (shift) Transform.ROTATE_CCW else Transform.ROTATE_CW)
                        } else false
                        Key.C -> if (ctrl) { state.captureSelection(); true } else false
                        Key.X -> if (ctrl) {
                            state.captureSelection(); state.deleteSelection(); refreshVersion(); true
                        } else false
                        Key.Z -> if (ctrl) { state.undo(); refreshVersion(); true } else false
                        Key.Y -> if (ctrl) { state.redo(); refreshVersion(); true } else false
                        Key.A -> if (ctrl) {
                            state.selActive = true; state.selX1 = 0; state.selY1 = 0
                            state.selX2 = imageWidth - 1; state.selY2 = imageHeight - 1
                            refreshVersion(); true
                        } else false
                        Key.Delete, Key.Backspace -> {
                            if (state.floatingSelection != null) {
                                state.cancelFloatingSelection(); refreshVersion(); true
                            } else if (state.selActive) { state.deleteSelection(); refreshVersion(); true }
                            else false
                        }
                        Key.Enter -> {
                            if (state.floatingSelection != null) {
                                state.commitFloatingSelection(); refreshVersion(); true
                            } else false
                        }
                        Key.DirectionLeft -> {
                            if (state.nudgeFloatingSelection(-1, 0)) { refreshVersion(); true } else false
                        }
                        Key.DirectionRight -> {
                            if (state.nudgeFloatingSelection(1, 0)) { refreshVersion(); true } else false
                        }
                        Key.DirectionUp -> {
                            if (state.nudgeFloatingSelection(0, -1)) { refreshVersion(); true } else false
                        }
                        Key.DirectionDown -> {
                            if (state.nudgeFloatingSelection(0, 1)) { refreshVersion(); true } else false
                        }
                        Key.Escape -> {
                            if (state.floatingSelection != null) {
                                state.cancelFloatingSelection()
                            } else {
                                state.selActive = false
                                state.clearClipboard()
                                state.activeTool = PixelTool.PENCIL
                            }
                            refreshVersion(); true
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
                FilterChip(selected = state.activeTool == PixelTool.PENCIL, onClick = { setTool(PixelTool.PENCIL) },
                    label = { Icon(Icons.Default.Brush, "Pencil (P)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = state.activeTool == PixelTool.ERASER, onClick = { setTool(PixelTool.ERASER) },
                    label = { Icon(Icons.Default.Clear, "Eraser (E)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = state.activeTool == PixelTool.FILL, onClick = { setTool(PixelTool.FILL) },
                    label = { Icon(Icons.Default.FormatColorFill, "Fill (F)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = state.activeTool == PixelTool.EYEDROPPER, onClick = { setTool(PixelTool.EYEDROPPER) },
                    label = { Icon(Icons.Default.Colorize, "Eyedrop (I)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))
                FilterChip(selected = state.activeTool == PixelTool.SELECT, onClick = { setTool(PixelTool.SELECT) },
                    label = { Icon(Icons.Default.Crop, "Select (S)", Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp))

                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                // Transform buttons operate only on an active selection or its floating preview.
                SmallButton("Flip H", "H", enabled = state.hasTransformTarget) { applySelectionTransform(Transform.FLIP_H) }
                SmallButton("Flip V", "V", enabled = state.hasTransformTarget) { applySelectionTransform(Transform.FLIP_V) }
                SmallButton("↻", "R", enabled = state.hasTransformTarget) { applySelectionTransform(Transform.ROTATE_CW) }
                SmallButton("↺", "⇧R", enabled = state.hasTransformTarget) { applySelectionTransform(Transform.ROTATE_CCW) }

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
                if (referencePreviewImage != null) {
                    FilterChip(selected = showReferencePopup, onClick = { showReferencePopup = !showReferencePopup },
                        label = { Text("Ref", fontSize = 9.sp) }, modifier = Modifier.height(28.dp))
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
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()
        val panCoroutineScope = rememberCoroutineScope()
        var isPanning by remember { mutableStateOf(false) }
        var lastPanX by remember { mutableStateOf(0f) }
        var lastPanY by remember { mutableStateOf(0f) }
        var isMovingFloatingSelection by remember { mutableStateOf(false) }
        var floatingGrabX by remember { mutableStateOf(0) }
        var floatingGrabY by remember { mutableStateOf(0) }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Pixel canvas
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { e ->
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
                    .onPointerEvent(PointerEventType.Press) { e ->
                        val ne = e.nativeEvent as? MouseEvent
                        if (ne != null && ne.button == MouseEvent.BUTTON2) {
                            isPanning = true
                            val p = e.changes.first().position
                            lastPanX = p.x; lastPanY = p.y
                        }
                    }
                    .onPointerEvent(PointerEventType.Move) { e ->
                        if (isPanning) {
                            val pos = e.changes.first().position
                            val ne = e.nativeEvent as? MouseEvent
                            if (ne != null && (ne.modifiersEx and InputEvent.BUTTON2_DOWN_MASK) == 0) {
                                isPanning = false
                            } else {
                                val dx = lastPanX - pos.x; val dy = lastPanY - pos.y
                                lastPanX = pos.x; lastPanY = pos.y
                                panCoroutineScope.launch {
                                    hScroll.scrollTo((hScroll.value + dx.toInt()).coerceIn(0, hScroll.maxValue))
                                    vScroll.scrollTo((vScroll.value + dy.toInt()).coerceIn(0, vScroll.maxValue))
                                }
                            }
                        }
                    }
                    .onPointerEvent(PointerEventType.Release) { e ->
                        val ne = e.nativeEvent as? MouseEvent
                        if (ne == null || ne.button == MouseEvent.BUTTON2) isPanning = false
                    }
                    .onPointerEvent(PointerEventType.Exit) { isPanning = false }
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll)
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
                        .onPointerEvent(PointerEventType.Move) { e ->
                            val pos = e.changes.first().position
                            val px = (pos.x / zoomLevel).toInt().coerceIn(0, imageWidth - 1)
                            val py = (pos.y / zoomLevel).toInt().coerceIn(0, imageHeight - 1)
                            hoverX = px; hoverY = py
                            if (isMovingFloatingSelection) {
                                state.moveFloatingSelectionTo(px - floatingGrabX, py - floatingGrabY)
                                refreshVersion()
                                return@onPointerEvent
                            }
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
                            val floating = state.floatingSelection
                            if (state.activeTool == PixelTool.SELECT && floating != null) {
                                if (state.isInsideFloatingSelection(px, py)) {
                                    isDrawing = false
                                    isMovingFloatingSelection = true
                                    floatingGrabX = px - floating.x
                                    floatingGrabY = py - floating.y
                                    refreshVersion()
                                    return@onPointerEvent
                                } else {
                                    state.cancelFloatingSelection()
                                }
                            }
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
                            isMovingFloatingSelection = false
                            val wasDrawing = stopDrawing()
                            // If selection is just a click (zero size), clear it
                            if (wasDrawing && state.activeTool == PixelTool.SELECT && state.selX1 == state.selX2 && state.selY1 == state.selY2) {
                                state.selActive = false
                            }
                            refreshVersion()
                        }
                        .onPointerEvent(PointerEventType.Exit) {
                            isMovingFloatingSelection = false
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

                    state.floatingSelection?.let { floating ->
                        val fx = floating.x * zoomLevel.toFloat()
                        val fy = floating.y * zoomLevel.toFloat()
                        val fw = floating.width * zoomLevel.toFloat()
                        val fh = floating.height * zoomLevel.toFloat()
                        drawRect(
                            Color(0x55333366),
                            Offset(fx, fy),
                            Size(fw, fh)
                        )
                        for (row in 0 until floating.height) {
                            for (col in 0 until floating.width) {
                                val imgX = floating.x + col
                                val imgY = floating.y + row
                                if (imgX !in 0 until imageWidth || imgY !in 0 until imageHeight) continue
                                val argb = floating.pixels[row * floating.width + col]
                                val alpha = (argb ushr 24) and 0xFF
                                if (alpha == 0) continue
                                drawRect(
                                    Color(argb).copy(alpha = 0.9f),
                                    Offset(imgX * zoomLevel.toFloat(), imgY * zoomLevel.toFloat()),
                                    Size(zoomLevel.toFloat(), zoomLevel.toFloat())
                                )
                            }
                        }
                        drawRect(
                            Color(0xFFFFD54F),
                            Offset(fx, fy),
                            Size(fw, fh),
                            style = Stroke(2f)
                        )
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
                    "B/P" to "Brush/Stamp",
                    "E" to "Eraser",
                    "F/G" to "Fill",
                    "I/D" to "Pick",
                    "S" to "Select",
                    "Del" to "Delete sel",
                    "H" to "Flip H",
                    "V" to "Flip V",
                    "R" to "Rot ↻",
                    "⇧R" to "Rot ↺",
                    "Enter" to "Place",
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

            }
        }
        }
        if (referencePreviewImage != null && showReferencePopup) {
            FloatingReferenceImage(
                image = referencePreviewImage,
                animationFrames = popupFrames,
                onClose = { showReferencePopup = false },
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
    }
}

@Composable
private fun FloatingReferenceImage(
    image: ImageBitmap,
    animationFrames: List<ImageBitmap>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    var offsetX by remember { mutableStateOf(18f) }
    var offsetY by remember { mutableStateOf(58f) }
    var panelWidthDp by remember { mutableStateOf(280f) }
    var panelHeightDp by remember { mutableStateOf(240f) }
    val frames = remember(image, animationFrames) {
        animationFrames.takeIf { it.isNotEmpty() } ?: listOf(image)
    }
    var frameIndex by remember { mutableStateOf(0) }
    var isPlaying by remember(frames.size) { mutableStateOf(frames.size > 1) }
    var frameDelayMs by remember { mutableStateOf(120L) }

    LaunchedEffect(frames.size) {
        frameIndex = frameIndex.coerceIn(0, frames.lastIndex.coerceAtLeast(0))
    }

    LaunchedEffect(frames, isPlaying, frameDelayMs) {
        if (!isPlaying || frames.size <= 1) return@LaunchedEffect
        while (true) {
            delay(frameDelayMs)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    val activeImage = frames[frameIndex.coerceIn(0, frames.lastIndex)]

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(panelWidthDp.dp)
            .height(panelHeightDp.dp),
        color = Color(0xF20D0D1A),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 10.dp,
        tonalElevation = 4.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(Color(0xFF22223A))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX += dragAmount.x
                                offsetY += dragAmount.y
                            }
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (frames.size > 1) "Assembled ${frameIndex + 1}/${frames.size}" else "Assembled",
                        fontSize = 10.sp,
                        color = Color(0xFFCCCCDD),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Close",
                        fontSize = 9.sp,
                        color = Color(0xFFAAAACC),
                        modifier = Modifier.clickable(onClick = onClose)
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF151525))
                        .border(1.dp, Color(0xFF444466), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = activeImage,
                        contentDescription = "Assembled sprite reference",
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(Color(0xFF22223A))
                        .padding(start = 6.dp, end = 34.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ReferenceControlButton("<", enabled = frames.size > 1) {
                        isPlaying = false
                        frameIndex = if (frameIndex <= 0) frames.lastIndex else frameIndex - 1
                    }
                    ReferenceControlButton(if (isPlaying) "Pause" else "Play", enabled = frames.size > 1) {
                        isPlaying = !isPlaying
                    }
                    ReferenceControlButton(">", enabled = frames.size > 1) {
                        isPlaying = false
                        frameIndex = (frameIndex + 1) % frames.size
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Speed", fontSize = 9.sp, color = Color(0xFF8888AA))
                    ReferenceControlButton("-", enabled = frameDelayMs < 600L) {
                        frameDelayMs = (frameDelayMs + 30L).coerceAtMost(600L)
                    }
                    Text("${frameDelayMs}ms", fontSize = 9.sp, color = Color(0xFFCCCCDD), modifier = Modifier.width(42.dp), textAlign = TextAlign.Center)
                    ReferenceControlButton("+", enabled = frameDelayMs > 30L) {
                        frameDelayMs = (frameDelayMs - 30L).coerceAtLeast(30L)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            panelWidthDp = (panelWidthDp + dragAmount.x / density).coerceIn(240f, 560f)
                            panelHeightDp = (panelHeightDp + dragAmount.y / density).coerceIn(160f, 560f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(14.dp)) {
                    drawLine(Color(0xFFAAAACC), Offset(size.width, 2f), Offset(2f, size.height), strokeWidth = 1.2f)
                    drawLine(Color(0xFF777799), Offset(size.width, 7f), Offset(7f, size.height), strokeWidth = 1.2f)
                }
            }
        }
    }
}

@Composable
private fun ReferenceControlButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .height(22.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        color = if (enabled) Color(0xFF333355) else Color(0xFF242436)
    ) {
        Box(Modifier.padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 9.sp,
                color = if (enabled) Color(0xFFDDDDEE) else Color(0xFF777788)
            )
        }
    }
}

@Composable
private fun SmallButton(label: String, hint: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.height(28.dp).clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$label ($hint)",
                fontSize = 8.sp,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                }
            )
        }
    }
}
