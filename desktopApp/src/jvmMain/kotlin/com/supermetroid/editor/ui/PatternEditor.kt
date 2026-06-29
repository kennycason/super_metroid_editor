package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.PatternCell
import com.supermetroid.editor.data.TilePattern
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

// ─── Left column: pattern list ──────────────────────────────────────────

@Composable
fun PatternListPanel(
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patternVersion
    val patterns = editorState.project.patterns
    val tg = editorState.tileGraphics
    val selectedId = editorState.selectedPatternId
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Patterns", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Button(
                onClick = { showCreateDialog = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) { Text("+ New", fontSize = 11.sp) }
        }

        Divider()

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            if (patterns.isEmpty()) {
                Text(
                    "No patterns yet.\nClick + New to create one.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            val crePats = patterns.filter { it.tilesetId == null }
            val urePats = patterns.filter { it.tilesetId != null }

            if (crePats.isNotEmpty()) {
                Text("CRE (Common)", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
                for (pat in crePats) PatternListItem(pat, pat.id == selectedId, tg) {
                    editorState.loadPatternForEdit(pat.id)
                }
            }

            if (urePats.isNotEmpty()) {
                val grouped = urePats.groupBy { it.tilesetId!! }
                for ((tsId, pats) in grouped.entries.sortedBy { it.key }) {
                    Text("Tileset $tsId", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
                    for (pat in pats) PatternListItem(pat, pat.id == selectedId, tg) {
                        editorState.loadPatternForEdit(pat.id)
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePatternDialog(
            currentTilesetId = editorState.currentTilesetId,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, cols, rows, tilesetId ->
                val pat = editorState.addPattern(name, cols, rows, tilesetId)
                editorState.loadPatternForEdit(pat.id)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun PatternListItem(
    pattern: TilePattern,
    isSelected: Boolean,
    tg: TileGraphics?,
    onSelect: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val thumb = remember(pattern, pattern.cells.hashCode(), tg) {
        renderPatternThumbnail(pattern, tg)?.toComposeImageBitmap()
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() }
            .padding(horizontal = 4.dp, vertical = 1.dp),
        color = bg, shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(48.dp)
                    .background(EditorColors.romBackground, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = pattern.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(2.dp)
                    )
                } else {
                    Text("?", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(pattern.name, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pattern.cols}×${pattern.rows}" +
                    (if (pattern.tilesetId != null) " TS:${pattern.tilesetId}" else " CRE") +
                    (if (pattern.builtIn) " (built-in)" else ""),
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePatternDialog(
    currentTilesetId: Int,
    onDismiss: () -> Unit,
    onCreate: (name: String, cols: Int, rows: Int, tilesetId: Int?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var colsText by remember { mutableStateOf("4") }
    var rowsText by remember { mutableStateOf("4") }
    var isCre by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Pattern", fontSize = 14.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppOutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = "Name",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedTextField(
                        value = colsText,
                        onValueChange = { colsText = it.filter { c -> c.isDigit() } },
                        label = "Width",
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        fontSize = 12.sp
                    )
                    AppOutlinedTextField(
                        value = rowsText,
                        onValueChange = { rowsText = it.filter { c -> c.isDigit() } },
                        label = "Height",
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        fontSize = 12.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isCre, onCheckedChange = { isCre = it }, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isCre) "CRE (shared)" else "Tileset $currentTilesetId", fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val c = colsText.toIntOrNull()?.coerceIn(1, 32) ?: 4
                val r = rowsText.toIntOrNull()?.coerceIn(1, 32) ?: 4
                onCreate(name.ifBlank { "Pattern ${c}×${r}" }, c, r, if (isCre) null else currentTilesetId)
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Right canvas: pattern editor (mirrors MapCanvas) ───────────────────

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PatternEditorCanvas(
    editorState: EditorState,
    @Suppress("UNUSED_PARAMETER") romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patternVersion
    @Suppress("UNUSED_VARIABLE") val ev = editorState.patternEditVersion
    val pattern = editorState.activePattern

    if (pattern == null) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            Text("Select a pattern to edit", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        return
    }

    @Suppress("UNUSED_VARIABLE") val uv = editorState.patUndoVersion
    val tg = editorState.tileGraphics
    val density = LocalDensity.current.density
    var zoomLevel by remember { mutableStateOf(3f) }
    val focusReq = remember { FocusRequester() }
    var isPainting by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    // Tile properties panel state
    var propsExpanded by remember { mutableStateOf(false) }
    var propsBlockX by remember { mutableStateOf(0) }
    var propsBlockY by remember { mutableStateOf(0) }
    var propsBlockType by remember { mutableStateOf(0) }
    var propsBts by remember { mutableStateOf(0) }
    var propsMetatile by remember { mutableStateOf(0) }

    // Tile meta overlay toggle
    var showTileMeta by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // ─── Toolbar ──────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(pattern.name, fontWeight = FontWeight.Medium, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showRenameDialog = true })
            IconButton(onClick = { showRenameDialog = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Edit, "Rename", Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${pattern.cols}×${pattern.rows}", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pattern.tilesetId != null) Text("TS:${pattern.tilesetId}", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text("CRE", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)

            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

            FilterChip(
                selected = editorState.activeTool == EditorTool.SELECT,
                onClick = { editorState.activeTool = EditorTool.SELECT; focusReq.requestFocus() },
                label = { Icon(Icons.Default.SelectAll, "Select", Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp)
            )
            FilterChip(
                selected = editorState.activeTool == EditorTool.PAINT,
                onClick = { editorState.activeTool = EditorTool.PAINT; focusReq.requestFocus() },
                label = { Icon(Icons.Default.Brush, "Paint", Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp)
            )
            FilterChip(
                selected = editorState.activeTool == EditorTool.FILL,
                onClick = { editorState.activeTool = EditorTool.FILL; focusReq.requestFocus() },
                label = { Icon(Icons.Default.FormatColorFill, "Fill", Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp)
            )
            FilterChip(
                selected = editorState.activeTool == EditorTool.SAMPLE,
                onClick = { editorState.activeTool = EditorTool.SAMPLE; focusReq.requestFocus() },
                label = { Icon(Icons.Default.Colorize, "Sample", Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp)
            )
            FilterChip(
                selected = editorState.activeTool == EditorTool.ERASE,
                onClick = { editorState.activeTool = EditorTool.ERASE; focusReq.requestFocus() },
                label = { Icon(Icons.Default.Clear, "Erase", Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp)
            )

            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

            IconButton(onClick = { editorState.patUndo(); focusReq.requestFocus() },
                enabled = editorState.patternUndoStack.isNotEmpty(), modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Undo, "Undo", Modifier.size(16.dp),
                    tint = if (editorState.patternUndoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
            IconButton(onClick = { editorState.patRedo(); focusReq.requestFocus() },
                enabled = editorState.patternRedoStack.isNotEmpty(), modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Redo, "Redo", Modifier.size(16.dp),
                    tint = if (editorState.patternRedoStack.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }

            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

            IconButton(onClick = { editorState.flipOrCaptureH(); focusReq.requestFocus() },
                enabled = editorState.brush != null, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Flip, "H-Flip", Modifier.size(16.dp),
                    tint = if (editorState.brush?.hFlip == true) MaterialTheme.colorScheme.primary
                    else if (editorState.brush != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
            IconButton(onClick = { editorState.flipOrCaptureV(); focusReq.requestFocus() },
                enabled = editorState.brush != null, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Flip, "V-Flip", Modifier.size(16.dp).graphicsLayer(rotationZ = 90f),
                    tint = if (editorState.brush?.vFlip == true) MaterialTheme.colorScheme.primary
                    else if (editorState.brush != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }

            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedButton(onClick = { showResizeDialog = true },
                contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(28.dp)) {
                Text("Resize", fontSize = 9.sp)
            }
            Button(onClick = {
                editorState.brush = editorState.patternToBrush(pattern)
                editorState.activeTool = EditorTool.PAINT
            }, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(28.dp)) {
                Text("Use", fontSize = 9.sp)
            }
            if (!pattern.builtIn) {
                OutlinedButton(onClick = { showDeleteConfirm = true },
                    contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(28.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete", fontSize = 9.sp)
                }
            }

            Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

            FilterChip(
                selected = showTileMeta,
                onClick = { showTileMeta = !showTileMeta },
                label = { Text("Meta", fontSize = 9.sp) },
                modifier = Modifier.height(28.dp),
                leadingIcon = if (showTileMeta) { { Icon(Icons.Default.Visibility, null, Modifier.size(12.dp)) } } else null
            )

            Spacer(Modifier.weight(1f))

            Text("${(zoomLevel * 100).toInt()}%", fontSize = 10.sp,
                modifier = Modifier.width(36.dp))
            Slider(
                value = zoomLevel,
                onValueChange = { zoomLevel = it },
                valueRange = 1f..8f,
                steps = 13,
                modifier = Modifier.width(80.dp).height(28.dp)
            )
        }

        // ─── Canvas ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(EditorColors.romBackground)
                .focusRequester(focusReq)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.S -> { editorState.activeTool = EditorTool.SELECT; true }
                        Key.P -> { editorState.activeTool = EditorTool.PAINT; true }
                        Key.E -> { editorState.activeTool = EditorTool.ERASE; true }
                        Key.F -> { editorState.activeTool = EditorTool.FILL; true }
                        Key.I -> { editorState.activeTool = EditorTool.SAMPLE; true }
                        Key.Z -> if (event.isCtrlPressed || event.isMetaPressed) {
                            if (event.isShiftPressed) editorState.patRedo() else editorState.patUndo(); true
                        } else false
                        Key.Y -> if (event.isCtrlPressed || event.isMetaPressed) {
                            editorState.patRedo(); true
                        } else false
                        Key.H -> { editorState.flipOrCaptureH(); true }
                        Key.V -> if (!event.isCtrlPressed && !event.isMetaPressed) {
                            editorState.flipOrCaptureV(); true
                        } else false
                        Key.Equals -> { zoomLevel = (zoomLevel * 1.25f).coerceIn(1f, 8f); true }
                        Key.Minus -> { zoomLevel = (zoomLevel / 1.25f).coerceIn(1f, 8f); true }
                        else -> false
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val ne = event.nativeEvent as? MouseEvent
                    val isZoom = ne?.let { it.isControlDown || it.isMetaDown } ?: false
                    val sd = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    if (isZoom) {
                        zoomLevel = (zoomLevel * if (sd.y < 0) EditorColors.ZOOM_FACTOR else 1f / EditorColors.ZOOM_FACTOR).coerceIn(1f, 8f)
                    }
                }
        ) {
            val tilePx = 16f * zoomLevel
            val canvasW = (pattern.cols * tilePx).toInt()
            val canvasH = (pattern.rows * tilePx).toInt()
            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()

            fun pointerToBlock(px: Float, py: Float): Pair<Int, Int> {
                val rawPx = px / density
                val rawPy = py / density
                val bx = ((rawPx + hScroll.value / density) / (tilePx / density)).toInt()
                val by = ((rawPy + vScroll.value / density) / (tilePx / density)).toInt()
                return Pair(bx.coerceIn(0, pattern.cols - 1), by.coerceIn(0, pattern.rows - 1))
            }

            // Render pattern image
            val patImg = remember(pattern, ev, tg, zoomLevel, showTileMeta) {
                renderPatternImage(pattern, tg, zoomLevel, showTileMeta)?.toComposeImageBitmap()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
                    .verticalScroll(vScroll)
            ) {
                if (patImg != null) {
                    Image(
                        bitmap = patImg,
                        contentDescription = "Pattern",
                        contentScale = ContentScale.None,
                        modifier = Modifier
                            .requiredWidth((canvasW / density).dp)
                            .requiredHeight((canvasH / density).dp)
                            .onPointerEvent(PointerEventType.Press) { event ->
                                focusReq.requestFocus()
                                val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                val (bx, by) = pointerToBlock(pos.x, pos.y)
                                val button = (event.nativeEvent as? MouseEvent)?.button ?: MouseEvent.BUTTON1
                                if (button == MouseEvent.BUTTON3 || (button == MouseEvent.BUTTON1 &&
                                    ((event.nativeEvent as? MouseEvent)?.modifiersEx
                                        ?: 0) and java.awt.event.InputEvent.CTRL_DOWN_MASK != 0)) {
                                    val cell = editorState.patReadCell(bx, by) ?: PatternCell(0, blockType = 0)
                                    propsBlockX = bx; propsBlockY = by
                                    propsMetatile = cell.metatile
                                    propsBlockType = cell.blockType
                                    propsBts = cell.bts
                                    propsExpanded = true
                                    return@onPointerEvent
                                }
                                when (editorState.activeTool) {
                                    EditorTool.PAINT -> {
                                        isPainting = true
                                        editorState.patBeginStroke()
                                        editorState.patPaintAt(bx, by)
                                    }
                                    EditorTool.ERASE -> {
                                        isPainting = true
                                        editorState.patBeginStroke()
                                        editorState.patEraseAt(bx, by)
                                    }
                                    EditorTool.FILL -> {
                                        editorState.patFloodFill(bx, by)
                                    }
                                    EditorTool.SAMPLE -> {
                                        editorState.patSampleTile(bx, by)
                                    }
                                    EditorTool.SELECT -> {}
                                }
                            }
                            .onPointerEvent(PointerEventType.Move) { event ->
                                val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                val (bx, by) = pointerToBlock(pos.x, pos.y)
                                editorState.patHoverX = bx; editorState.patHoverY = by
                                val btns = (event.nativeEvent as? MouseEvent)?.modifiersEx ?: 0
                                val lmb = (btns and java.awt.event.InputEvent.BUTTON1_DOWN_MASK) != 0
                                if (isPainting && lmb) {
                                    when (editorState.activeTool) {
                                        EditorTool.PAINT -> editorState.patPaintAt(bx, by)
                                        EditorTool.ERASE -> editorState.patEraseAt(bx, by)
                                        else -> {}
                                    }
                                }
                            }
                            .onPointerEvent(PointerEventType.Release) {
                                if (isPainting) {
                                    editorState.patEndStroke()
                                    isPainting = false
                                }
                            }
                            .onPointerEvent(PointerEventType.Exit) {
                                editorState.patHoverX = -1; editorState.patHoverY = -1
                            }
                    )

                    // Eraser cursor preview
                    if (editorState.patHoverX >= 0 && editorState.activeTool == EditorTool.ERASE) {
                        val hx = editorState.patHoverX
                        val hy = editorState.patHoverY
                        val tileSize = tilePx / density
                        val left = hx * tileSize
                        val top = hy * tileSize
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .offset(x = left.dp, y = top.dp)
                                .requiredSize(tileSize.dp)
                        ) {
                            drawRect(Color.Red.copy(alpha = 0.25f), size = size)
                            drawRect(Color.Red.copy(alpha = 0.8f), size = size,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                            drawLine(Color.Red.copy(alpha = 0.8f),
                                start = androidx.compose.ui.geometry.Offset(2f, 2f),
                                end = androidx.compose.ui.geometry.Offset(size.width - 2f, size.height - 2f),
                                strokeWidth = 2f)
                            drawLine(Color.Red.copy(alpha = 0.8f),
                                start = androidx.compose.ui.geometry.Offset(size.width - 2f, 2f),
                                end = androidx.compose.ui.geometry.Offset(2f, size.height - 2f),
                                strokeWidth = 2f)
                        }
                    }

                    // Brush preview ghost
                    if (editorState.patHoverX >= 0 && editorState.brush != null &&
                        editorState.activeTool == EditorTool.PAINT && tg != null) {
                        val hx = editorState.patHoverX
                        val hy = editorState.patHoverY
                        val b = editorState.brush!!
                        val preview = remember(b, hx, hy, tg) {
                            val pw = b.cols * 16; val ph = b.rows * 16
                            val img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
                            for (r in 0 until b.rows) for (c in 0 until b.cols) {
                                val ck = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                                if (ck in b.skipCells) continue
                                val idx = b.tiles.getOrNull(r)?.getOrNull(c) ?: continue
                                val pixels = tg.renderMetatile(idx) ?: continue
                                val dc = if (b.hFlip) (b.cols - 1 - c) else c
                                val dr = if (b.vFlip) (b.rows - 1 - r) else r
                                val effH = b.tileHFlip(r, c); val effV = b.tileVFlip(r, c)
                                for (ty in 0 until 16) for (tx in 0 until 16) {
                                    val sx = if (effH) 15 - tx else tx
                                    val sy = if (effV) 15 - ty else ty
                                    val argb = pixels[sy * 16 + sx]
                                    if (argb != 0) img.setRGB(dc * 16 + tx, dr * 16 + ty, argb)
                                }
                            }
                            for (y in 0 until ph) for (x in 0 until pw) {
                                val p = img.getRGB(x, y)
                                if (p != 0) img.setRGB(x, y, (p and 0x00FFFFFF) or 0x99000000.toInt())
                            }
                            img.toComposeImageBitmap()
                        }
                        val tileSize = tilePx / density
                        Image(
                            bitmap = preview,
                            contentDescription = null,
                            modifier = Modifier
                                .offset(x = (hx * tileSize).dp, y = (hy * tileSize).dp)
                                .requiredWidth((b.cols * tileSize).dp)
                                .requiredHeight((b.rows * tileSize).dp),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("Load a room first to get tileset graphics",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    }
                }
            }

            // ─── Tile Properties Panel (floating, right side) ──────
            if (propsExpanded) {
                val editableBlockTypes = listOf(
                    0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Treadmill",
                    0x4 to "Shootable Air", 0x5 to "H-Extend", 0x8 to "Solid",
                    0x9 to "Door", 0xA to "Spike", 0xB to "Crumble",
                    0xC to "Shot Block", 0xD to "V-Extend", 0xE to "Grapple", 0xF to "Bomb Block"
                )
                val propsTypeName = blockTypeName(propsBlockType)
                val btsOpts = btsOptionsForBlockType(propsBlockType)

                Card(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).width(240.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState())) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "($propsBlockX,$propsBlockY) #$propsMetatile ${blockTypeName(propsBlockType)}",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                            Text("✕", modifier = Modifier.clickable { propsExpanded = false }.padding(4.dp),
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("Block Type", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        var btExpanded by remember { mutableStateOf(false) }
                        Box {
                            Surface(
                                modifier = Modifier.fillMaxWidth().height(32.dp).clickable { btExpanded = true },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("0x${propsBlockType.toString(16).uppercase()} $propsTypeName",
                                        fontSize = 11.sp, modifier = Modifier.weight(1f))
                                    Text("▾", fontSize = 10.sp)
                                }
                            }
                            DropdownMenu(expanded = btExpanded, onDismissRequest = { btExpanded = false }) {
                                for ((tv, tn) in editableBlockTypes) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                RadioButton(selected = propsBlockType == tv, onClick = null,
                                                    modifier = Modifier.size(16.dp))
                                                Text("0x${tv.toString(16).uppercase()} $tn", fontSize = 11.sp)
                                            }
                                        },
                                        onClick = {
                                            btExpanded = false
                                            if (tv != propsBlockType) {
                                                propsBlockType = tv
                                                editorState.patSetCellProperties(propsBlockX, propsBlockY, tv, propsBts)
                                            }
                                        },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        var hoveredSlopeBts by remember { mutableStateOf<Int?>(null) }
                        if (propsBlockType == 0x1) {
                            val displayBts = hoveredSlopeBts ?: propsBts
                            val displayName = btsOpts.firstOrNull { it.first == (displayBts and 0x40.inv()) }?.second
                                ?: btsOpts.firstOrNull { it.first == displayBts }?.second
                            if (displayName != null) {
                                val flipLabel = if (displayBts and 0x40 != 0) " [X-Flipped]" else ""
                                Text(
                                    "0x${displayBts.toString(16).uppercase().padStart(2, '0')} $displayName$flipLabel",
                                    fontSize = 9.sp,
                                    color = if (hoveredSlopeBts != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 2.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        val btsLabel = when (propsBlockType) {
                            0x9 -> "Door Connection Index"
                            0x1 -> "Slope Shape"
                            else -> "Sub Type (BTS)"
                        }
                        Text(btsLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))

                        if (propsBlockType == 0x1) {
                            SlopeGridPicker(
                                selectedBts = propsBts,
                                onSelect = { bv ->
                                    if (bv != propsBts) {
                                        propsBts = bv
                                        editorState.patSetCellProperties(
                                            propsBlockX, propsBlockY, propsBlockType, bv)
                                    }
                                },
                                onHoverBts = { hoveredSlopeBts = it }
                            )
                            Spacer(Modifier.height(4.dp))
                        } else if (btsOpts.isNotEmpty()) {
                            var btsDropExpanded by remember { mutableStateOf(false) }
                            val btsName = btsOpts.firstOrNull { it.first == propsBts }?.second
                                ?: "Custom (0x${propsBts.toString(16).uppercase().padStart(2, '0')})"
                            Box {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().height(32.dp)
                                        .clickable { btsDropExpanded = true },
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(btsName, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                        Text("▾", fontSize = 10.sp)
                                    }
                                }
                                DropdownMenu(expanded = btsDropExpanded,
                                    onDismissRequest = { btsDropExpanded = false }) {
                                    for ((bv, bn) in btsOpts) {
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    RadioButton(selected = propsBts == bv, onClick = null,
                                                        modifier = Modifier.size(16.dp))
                                                    Text("0x${bv.toString(16).uppercase().padStart(2, '0')} $bn",
                                                        fontSize = 11.sp)
                                                }
                                            },
                                            onClick = {
                                                btsDropExpanded = false
                                                if (bv != propsBts) {
                                                    propsBts = bv
                                                    editorState.patSetCellProperties(
                                                        propsBlockX, propsBlockY, propsBlockType, bv)
                                                }
                                            },
                                            modifier = Modifier.height(28.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (btsOpts.isNotEmpty()) "Raw:" else "BTS:", fontSize = 10.sp)
                            var rawText by remember(propsBlockX, propsBlockY, propsBts) {
                                mutableStateOf(propsBts.toString(16).uppercase().padStart(2, '0'))
                            }
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(32.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                BasicTextField(
                                    value = rawText,
                                    onValueChange = { s ->
                                        val filtered = s.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(2)
                                        rawText = filtered
                                        val v = filtered.toIntOrNull(16)
                                        if (v != null && v in 0..255 && v != propsBts) {
                                            editorState.patSetCellProperties(
                                                propsBlockX, propsBlockY, propsBlockType, v)
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }

                        // ── Items / PLMs ──
                        Spacer(Modifier.height(8.dp))
                        Divider()
                        Spacer(Modifier.height(4.dp))
                        Text("Items / PLMs", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                        val cell = editorState.patReadCell(propsBlockX, propsBlockY)
                        if (cell != null && cell.plmId != 0) {
                            val plmName = when {
                                editorState.isCustomItemPlm(cell.plmId) ->
                                    editorState.customItemNameForPlm(cell.plmId) ?: "Item 0x${cell.plmId.toString(16)}"
                                RomParser.isItemPlm(cell.plmId) ->
                                    RomParser.itemNameForPlm(cell.plmId) ?: "Item 0x${cell.plmId.toString(16)}"
                                RomParser.isStationPlm(cell.plmId) ->
                                    RomParser.stationNameForPlm(cell.plmId) ?: "Station"
                                RomParser.isGatePlm(cell.plmId) ->
                                    RomParser.gateNameForPlm(cell.plmId, cell.plmParam) ?: "Gate"
                                else -> RomParser.plmDisplayName(cell.plmId, cell.plmParam)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(plmName, fontSize = 10.sp)
                                Text("✕", modifier = Modifier
                                    .clickable { editorState.patRemoveCellPlm(propsBlockX, propsBlockY) }
                                    .padding(horizontal = 4.dp),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Text("None", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                        }

                        // Add Item
                        Spacer(Modifier.height(4.dp))
                        var addItemExpanded by remember { mutableStateOf(false) }
                        var addItemStyle by remember { mutableStateOf(0) }
                        val customItems = remember(editorState.patchVersion, editorState.project.patches) {
                            editorState.enabledCustomItems()
                        }
                        Box {
                            Surface(
                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                    .clickable { addItemExpanded = true },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("+ Add Item", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            DropdownMenu(expanded = addItemExpanded,
                                onDismissRequest = { addItemExpanded = false }) {
                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("Visible" to 0, "Chozo" to 1, "Hidden" to 2).forEach { (label, idx) ->
                                        FilterChip(
                                            selected = addItemStyle == idx,
                                            onClick = { addItemStyle = idx },
                                            label = { Text(label, fontSize = 9.sp) },
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                }
                                Divider()
                                for (item in RomParser.ITEM_DEFS) {
                                    val plmId = when (addItemStyle) {
                                        1 -> item.chozoId; 2 -> item.hiddenId; else -> item.visibleId
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Text(item.shortLabel, fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold)
                                                Text(item.name, fontSize = 11.sp)
                                            }
                                        },
                                        onClick = {
                                            addItemExpanded = false
                                            editorState.patSetCellPlm(propsBlockX, propsBlockY, plmId, 0)
                                        },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                                for (item in customItems) {
                                    val plmId = when (addItemStyle) {
                                        1 -> item.chozoPlmId
                                        2 -> item.hiddenPlmId
                                        else -> item.visiblePlmId
                                    } ?: continue
                                    DropdownMenuItem(
                                        text = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Text(item.shortLabel, fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold)
                                                Text(item.name, fontSize = 11.sp)
                                            }
                                        },
                                        onClick = {
                                            addItemExpanded = false
                                            editorState.patSetCellPlm(propsBlockX, propsBlockY, plmId, 0)
                                        },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        }

                        // Add Station / Gate
                        Spacer(Modifier.height(4.dp))
                        var addStationExpanded by remember { mutableStateOf(false) }
                        Box {
                            Surface(
                                modifier = Modifier.fillMaxWidth().height(28.dp)
                                    .clickable { addStationExpanded = true },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 8.dp).fillMaxHeight(),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("+ Add Station / Gate", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                            DropdownMenu(expanded = addStationExpanded,
                                onDismissRequest = { addStationExpanded = false }) {
                                Text("Stations", fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                for (station in RomParser.STATION_PLMS) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Text(station.shortLabel, fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    fontWeight = FontWeight.Bold)
                                                Text(station.name, fontSize = 11.sp)
                                            }
                                        },
                                        onClick = {
                                            addStationExpanded = false
                                            editorState.patSetCellPlm(
                                                propsBlockX, propsBlockY, station.plmId, station.defaultParam)
                                        },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                                Divider()
                                Text("Gates", fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                for (gate in RomParser.GATE_PLMS) {
                                    DropdownMenuItem(
                                        text = { Text(gate.name, fontSize = 11.sp) },
                                        onClick = {
                                            addStationExpanded = false
                                            editorState.patSetCellPlm(
                                                propsBlockX, propsBlockY, gate.plmId, gate.param)
                                        },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Pattern?") },
            text = { Text("Delete \"${pattern.name}\"? This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    editorState.removePattern(pattern.id)
                    showDeleteConfirm = false
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        var newName by remember { mutableStateOf(pattern.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Pattern", fontSize = 14.sp) },
            text = {
                AppOutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = "Name", singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val trimmed = newName.trim()
                    if (trimmed.isNotEmpty()) {
                        editorState.renamePattern(pattern.id, trimmed)
                    }
                    showRenameDialog = false
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
        )
    }

    // Resize dialog
    if (showResizeDialog) {
        var newW by remember { mutableStateOf(pattern.cols.toString()) }
        var newH by remember { mutableStateOf(pattern.rows.toString()) }
        AlertDialog(
            onDismissRequest = { showResizeDialog = false },
            title = { Text("Resize Pattern", fontSize = 14.sp) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedTextField(
                        value = newW, onValueChange = { newW = it.filter { c -> c.isDigit() } },
                        label = "Width", modifier = Modifier.weight(1f), singleLine = true
                    )
                    AppOutlinedTextField(
                        value = newH, onValueChange = { newH = it.filter { c -> c.isDigit() } },
                        label = "Height", modifier = Modifier.weight(1f), singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val w = newW.toIntOrNull()?.coerceIn(1, 32) ?: pattern.cols
                    val h = newH.toIntOrNull()?.coerceIn(1, 32) ?: pattern.rows
                    editorState.resizePattern(pattern.id, w, h)
                    showResizeDialog = false
                }) { Text("Resize") }
            },
            dismissButton = { TextButton(onClick = { showResizeDialog = false }) { Text("Cancel") } }
        )
    }
}

// ─── Pattern thumbnail list (for Rooms bottom pane & Tilesets sub-tab) ───

@Composable
fun PatternThumbnailList(
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patternVersion
    val patterns = editorState.project.patterns
    val tg = editorState.tileGraphics
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Patterns", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Button(
                onClick = { showCreateDialog = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(26.dp)
            ) { Text("+ New", fontSize = 10.sp) }
        }

        if (patterns.isEmpty()) {
            Text("No patterns yet", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            for (pat in patterns) {
                val isSelected = pat.id == editorState.selectedPatternId
                val thumb = remember(pat, pat.cells.hashCode(), tg) {
                    renderPatternThumbnail(pat, tg)?.toComposeImageBitmap()
                }
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { editorState.selectAndApplyPattern(pat.id) }
                        .padding(horizontal = 3.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp)
                                .background(EditorColors.romBackground, RoundedCornerShape(3.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb,
                                    contentDescription = pat.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(2.dp)
                                )
                            } else {
                                Text("?", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f))
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pat.name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pat.cols}×${pat.rows}" +
                                (if (pat.tilesetId != null) " TS:${pat.tilesetId}" else " CRE"),
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePatternDialog(
            currentTilesetId = editorState.currentTilesetId,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, cols, rows, tilesetId ->
                val pat = editorState.addPattern(name, cols, rows, tilesetId)
                editorState.loadPatternForEdit(pat.id)
                showCreateDialog = false
            }
        )
    }
}

private fun renderPatternThumbnail(
    pattern: TilePattern,
    tg: TileGraphics?
): BufferedImage? {
    if (tg == null) return null
    val tilePx = 16
    val w = pattern.cols * tilePx
    val h = pattern.rows * tilePx
    if (w <= 0 || h <= 0) return null
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = java.awt.Color(0x0C, 0x0C, 0x18)
    g.fillRect(0, 0, w, h)

    for (r in 0 until pattern.rows) for (c in 0 until pattern.cols) {
        val cell = pattern.getCell(r, c) ?: continue
        val pixels = tg.renderMetatile(cell.metatile) ?: continue
        val dx = c * tilePx; val dy = r * tilePx
        for (py in 0 until 16) for (px in 0 until 16) {
            val srcX = if (cell.hFlip) 15 - px else px
            val srcY = if (cell.vFlip) 15 - py else py
            val argb = pixels[srcY * 16 + srcX]
            if (argb != 0) img.setRGB(dx + px, dy + py, argb)
        }
    }
    g.dispose()
    return img
}

/** Render the entire pattern as a BufferedImage at the given zoom level. */
internal fun renderPatternImage(
    pattern: TilePattern,
    tg: TileGraphics?,
    zoom: Float,
    showTileMeta: Boolean = false
): BufferedImage? {
    if (tg == null) return null
    val tilePx = (16 * zoom).toInt()
    val w = pattern.cols * tilePx
    val h = pattern.rows * tilePx
    if (w <= 0 || h <= 0) return null
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = java.awt.Color(0x0C, 0x0C, 0x18)
    g.fillRect(0, 0, w, h)
    val scale = tilePx / 16

    val g2 = g as java.awt.Graphics2D
    for (r in 0 until pattern.rows) for (c in 0 until pattern.cols) {
        val cell = pattern.getCell(r, c)
        val dx = c * tilePx; val dy = r * tilePx
        if (cell == null) {
            g2.color = java.awt.Color(0x22, 0x22, 0x30)
            g2.stroke = java.awt.BasicStroke(maxOf(1f, scale * 0.5f))
            val pad = maxOf(2, tilePx / 5)
            g2.drawLine(dx + pad, dy + pad, dx + tilePx - pad, dy + tilePx - pad)
            g2.drawLine(dx + tilePx - pad, dy + pad, dx + pad, dy + tilePx - pad)
            continue
        }
        val pixels = tg.renderMetatile(cell.metatile) ?: continue
        for (py in 0 until 16) for (px in 0 until 16) {
            val srcX = if (cell.hFlip) 15 - px else px
            val srcY = if (cell.vFlip) 15 - py else py
            val argb = pixels[srcY * 16 + srcX]
            if (argb == 0) continue
            for (sy in 0 until scale) for (sx in 0 until scale) {
                val ix = dx + px * scale + sx; val iy = dy + py * scale + sy
                if (ix in 0 until w && iy in 0 until h) img.setRGB(ix, iy, argb)
            }
        }
    }

    // Grid lines
    g.color = java.awt.Color(0x33, 0x33, 0x44)
    for (c in 0..pattern.cols) g.drawLine(c * tilePx, 0, c * tilePx, h)
    for (r in 0..pattern.rows) g.drawLine(0, r * tilePx, w, r * tilePx)

    // Tile meta overlays
    if (showTileMeta) {
        val iconSize = maxOf(tilePx / 2, 8)
        val fontSize = maxOf(iconSize - 3, 6)
        g2.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, fontSize)

        for (r in 0 until pattern.rows) for (c in 0 until pattern.cols) {
            val cell = pattern.getCell(r, c) ?: continue
            val bt = cell.blockType
            if (bt == 0) continue
            val overlay = blockTypeToOverlay(bt, cell.bts) ?: continue
            val color = java.awt.Color(
                ((overlay.color shr 16) and 0xFF).toInt(),
                ((overlay.color shr 8) and 0xFF).toInt(),
                (overlay.color and 0xFF).toInt(),
                ((overlay.color shr 24) and 0xFF).toInt()
            )
            val ix = (c + 1) * tilePx - iconSize
            val iy = (r + 1) * tilePx - iconSize

            g2.color = java.awt.Color(0, 0, 0, 200)
            g2.fillRect(ix, iy, iconSize, iconSize)
            g2.color = color
            g2.stroke = java.awt.BasicStroke(2f)
            g2.drawRect(ix + 1, iy + 1, iconSize - 3, iconSize - 3)
            g2.stroke = java.awt.BasicStroke(1f)

            g2.color = java.awt.Color.WHITE
            val fm = g2.fontMetrics
            val label = overlay.shortLabel
            val tw = fm.stringWidth(label)
            g2.drawString(label, ix + (iconSize - tw) / 2, iy + (iconSize + fm.ascent - fm.descent) / 2)
        }
    }

    g.dispose()
    return img
}

private fun blockTypeToOverlay(blockType: Int, bts: Int): TileOverlay? = when (blockType) {
    0x8 -> TileOverlay.SOLID
    0x1 -> TileOverlay.SLOPE
    0x9 -> TileOverlay.DOOR
    0xA -> TileOverlay.SPIKE
    0xF -> TileOverlay.BOMB
    0xE -> TileOverlay.GRAPPLE
    0x3 -> TileOverlay.TREADMILL
    0xC -> when (shotBlockCategory(bts)) {
        ShotCategory.SUPER -> TileOverlay.SHOT_SUPER
        ShotCategory.PB -> TileOverlay.SHOT_PB
        else -> TileOverlay.SHOT_BEAM
    }
    0xB -> if (bts == 0x0E || bts == 0x0F) TileOverlay.SPEED else TileOverlay.CRUMBLE
    else -> null
}
