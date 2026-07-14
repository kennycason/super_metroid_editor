package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import com.supermetroid.editor.rom.TilesetGridData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

private val EDITABLE_BLOCK_TYPES = listOf(
    0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Treadmill",
    0x4 to "Shootable Air", 0x5 to "H-Extend", 0x8 to "Solid",
    0x9 to "Door", 0xA to "Spike", 0xB to "Crumble",
    0xC to "Shot Block", 0xD to "V-Extend", 0xE to "Grapple", 0xF to "Bomb Block"
)

private enum class TilePickerScope { ALL, AREA, CRE }

// ─── Shared tileset loading state ──────────────────────────────────────

class TilesetEditorState {
    var gridData by mutableStateOf<TilesetGridData?>(null)
    var palettes by mutableStateOf<Array<IntArray>?>(null)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var highlightPalette by mutableStateOf(-1)

    /** Re-render the tileset grid and palette swatches from current TileGraphics state. */
    fun refreshGrid(tileGraphics: TileGraphics?) {
        val tg = tileGraphics ?: return
        gridData = tg.renderTilesetGrid()
        palettes = tg.getPalettes()
    }
}

// ─── Left column: tileset list + palette ───────────────────────────────

@Composable
fun TilesetListPanel(
    romParser: RomParser?,
    editorState: EditorState,
    tilesetEditorState: TilesetEditorState,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val tilesetId = editorState.editorTilesetId
    val navigationFocusRequester = rememberVerticalSelectionFocusRequester(
        enabled = romParser != null,
        requestFocusKey = romParser
    )

    fun loadTileset(id: Int) {
        if (romParser == null) return
        tilesetEditorState.isLoading = true
        tilesetEditorState.errorMessage = null
        tilesetEditorState.gridData = null
        tilesetEditorState.palettes = null
        coroutineScope.launch {
            try {
                val ok = withContext(Dispatchers.Default) { editorState.loadEditorTileset(id, romParser) }
                if (!ok) { tilesetEditorState.errorMessage = "Failed to load tileset $id"; return@launch }
                val tg = editorState.editorTileGraphics!!
                tilesetEditorState.gridData = withContext(Dispatchers.Default) { tg.renderTilesetGrid() }
                tilesetEditorState.palettes = tg.getPalettes()
            } catch (e: Exception) { tilesetEditorState.errorMessage = e.message ?: "Error" }
            finally { tilesetEditorState.isLoading = false }
        }
    }

    LaunchedEffect(romParser) {
        if (romParser != null && tilesetEditorState.gridData == null) loadTileset(tilesetId)
    }

    Card(
        modifier = modifier.verticalSelectionKeyNavigation(
            focusRequester = navigationFocusRequester,
            itemCount = TileGraphics.NUM_TILESETS,
            selectedIndex = tilesetId,
            enabled = romParser != null,
            onSelectIndex = ::loadTileset
        ),
        shape = androidx.compose.ui.graphics.RectangleShape,
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Tilesets",
                style = MaterialTheme.typography.titleSmall,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp, 6.dp, 8.dp, 4.dp)
            )

            // Scrollable tileset list
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                for (id in 0 until TileGraphics.NUM_TILESETS) {
                    val isSelected = id == tilesetId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                requestVerticalSelectionFocus(navigationFocusRequester)
                                loadTileset(id)
                            },
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                               else Color.Transparent
                    ) {
                        Text(
                            "Tileset $id",
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Palette bar at the bottom
            val palettes = tilesetEditorState.palettes
            if (palettes != null) {
                Divider()
                Text(
                    "Palettes",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp, 4.dp, 8.dp, 2.dp)
                )
                PaletteBar(
                    palettes = palettes,
                    highlightPalette = tilesetEditorState.highlightPalette,
                    onToggle = { idx ->
                        tilesetEditorState.highlightPalette =
                            if (tilesetEditorState.highlightPalette == idx) -1 else idx
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ─── Right side: full tileset canvas with toolbar ──────────────────────

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TilesetCanvas(
    romParser: RomParser?,
    editorState: EditorState,
    tilesetEditorState: TilesetEditorState,
    modifier: Modifier = Modifier
) {
    val zoomState = remember { mutableStateOf(2.0f) }
    val zoomLevel = zoomState.value
    AttachMacPinchZoom(LocalSwingWindow.current, zoomState, minZoom = 0.5f, maxZoom = 8f)
    val tilesetId = editorState.editorTilesetId
    val selectedMeta = editorState.editorSelectedMetatile
    val gridData = tilesetEditorState.gridData
    val coroutineScope = rememberCoroutineScope()
    var showPixelEditor by remember { mutableStateOf(false) }
    var showComposer by remember { mutableStateOf(false) }

    fun reloadCurrentTileset() {
        val parser = romParser ?: return
        val id = editorState.editorTilesetId
        tilesetEditorState.isLoading = true
        tilesetEditorState.errorMessage = null
        coroutineScope.launch {
            try {
                val ok = withContext(Dispatchers.Default) { editorState.loadEditorTileset(id, parser) }
                if (!ok) {
                    tilesetEditorState.errorMessage = "Failed to load tileset $id"
                    return@launch
                }
                val tg = editorState.editorTileGraphics!!
                tilesetEditorState.gridData = withContext(Dispatchers.Default) { tg.renderTilesetGrid() }
                tilesetEditorState.palettes = tg.getPalettes()
            } catch (e: Exception) {
                tilesetEditorState.errorMessage = e.message ?: "Error"
            } finally {
                tilesetEditorState.isLoading = false
            }
        }
    }

    // If pixel editor is open, show it instead of the grid
    if (showPixelEditor && editorState.editorTileGraphics != null && selectedMeta >= 0) {
        TilePixelEditor(
            tileGraphics = editorState.editorTileGraphics!!,
            editorState = editorState,
            tilesetEditorState = tilesetEditorState,
            onClose = { showPixelEditor = false },
            modifier = modifier
        )
        return
    }

    if (showComposer && editorState.editorTileGraphics != null && selectedMeta >= 0) {
        MetatileComposer(
            tileGraphics = editorState.editorTileGraphics!!,
            editorState = editorState,
            tilesetEditorState = tilesetEditorState,
            onClose = { showComposer = false },
            modifier = modifier
        )
        return
    }

    Card(
        modifier = modifier,
        shape = androidx.compose.ui.graphics.RectangleShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top toolbar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                    Text("Tileset $tilesetId", fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary)

                    Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                    if (selectedMeta >= 0) {
                        TileToolbarInfo(
                            tilesetId = tilesetId,
                            metatileIndex = selectedMeta,
                            editorState = editorState,
                            previewVersion = tilesetEditorState.gridData,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Text("Click a tile to select", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                    }

                    Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                    if (selectedMeta >= 0 && editorState.editorTileGraphics != null) {
                        FilterChip(
                            selected = false,
                            onClick = { showComposer = true },
                            label = { Text("Compose", fontSize = 9.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = false,
                            onClick = { showPixelEditor = true },
                            label = { Icon(Icons.Default.Brush, contentDescription = "Edit Pixels", modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(28.dp)
                        )
                    }

                    Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

                    // Export/Import buttons (Surface like Rooms Tile Meta dropdown)
                    var exportMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        Surface(
                            modifier = Modifier.height(28.dp).clickable { exportMenuExpanded = true },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.GetApp, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text("Export", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("▾", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                            }
                        }
                        DropdownMenu(
                            expanded = exportMenuExpanded,
                            onDismissRequest = { exportMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export Area Tiles (URE)", fontSize = 10.sp) },
                                onClick = {
                                    exportMenuExpanded = false
                                    val fd = FileDialog(null as Frame?, "Export Area Tiles", FileDialog.SAVE)
                                    fd.file = "tileset_${tilesetId}_ure.png"
                                    fd.isVisible = true
                                    if (fd.file != null) {
                                        val path = java.io.File(fd.directory, fd.file).absolutePath
                                        editorState.exportTileSheet(path, isCre = false)
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("Export Common Tiles (CRE)", fontSize = 10.sp) },
                                onClick = {
                                    exportMenuExpanded = false
                                    val fd = FileDialog(null as Frame?, "Export Common Tiles", FileDialog.SAVE)
                                    fd.file = "cre_tiles.png"
                                    fd.isVisible = true
                                    if (fd.file != null) {
                                        val path = java.io.File(fd.directory, fd.file).absolutePath
                                        editorState.exportTileSheet(path, isCre = true)
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Export Palette Reference", fontSize = 10.sp) },
                                onClick = {
                                    exportMenuExpanded = false
                                    val fd = FileDialog(null as Frame?, "Export Palette", FileDialog.SAVE)
                                    fd.file = "tileset_${tilesetId}_palette.png"
                                    fd.isVisible = true
                                    if (fd.file != null) {
                                        val path = java.io.File(fd.directory, fd.file).absolutePath
                                        editorState.exportPalette(path)
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }

                    var importMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        Surface(
                            modifier = Modifier.height(28.dp).clickable { importMenuExpanded = true },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Publish, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text("Import", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (editorState.hasCustomVarGfx() || editorState.hasCustomCreGfx()) {
                                    Text("●", fontSize = 8.sp, color = Color(0xFF66BB6A))
                                }
                                Text("▾", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                            }
                        }
                        DropdownMenu(
                            expanded = importMenuExpanded,
                            onDismissRequest = { importMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text("Import Area Tiles (URE)", fontSize = 10.sp)
                                        if (editorState.hasCustomVarGfx()) {
                                            Text("●", fontSize = 8.sp, color = Color(0xFF66BB6A))
                                        }
                                    }
                                },
                                onClick = {
                                    importMenuExpanded = false
                                    val fd = FileDialog(null as Frame?, "Import Area Tiles", FileDialog.LOAD)
                                    fd.setFilenameFilter { _, name -> name.endsWith(".png", true) || name.endsWith(".bmp", true) }
                                    fd.isVisible = true
                                    if (fd.file != null && romParser != null) {
                                        val path = java.io.File(fd.directory, fd.file).absolutePath
                                        if (editorState.importTileSheet(path, isCre = false)) {
                                            coroutineScope.launch {
                                                val tg = editorState.editorTileGraphics!!
                                                tilesetEditorState.gridData = withContext(Dispatchers.Default) { tg.renderTilesetGrid() }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text("Import Common Tiles (CRE)", fontSize = 10.sp)
                                        if (editorState.hasCustomCreGfx()) {
                                            Text("●", fontSize = 8.sp, color = Color(0xFF66BB6A))
                                        }
                                    }
                                },
                                onClick = {
                                    importMenuExpanded = false
                                    val fd = FileDialog(null as Frame?, "Import Common Tiles", FileDialog.LOAD)
                                    fd.setFilenameFilter { _, name -> name.endsWith(".png", true) || name.endsWith(".bmp", true) }
                                    fd.isVisible = true
                                    if (fd.file != null && romParser != null) {
                                        val path = java.io.File(fd.directory, fd.file).absolutePath
                                        if (editorState.importTileSheet(path, isCre = true)) {
                                            coroutineScope.launch {
                                                val tg = editorState.editorTileGraphics!!
                                                tilesetEditorState.gridData = withContext(Dispatchers.Default) { tg.renderTilesetGrid() }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }

                    val hasRevertOptions = editorState.hasCurrentTilesetOverrides()
                    var revertMenuExpanded by remember { mutableStateOf(false) }
                    val hasPaletteOverride = editorState.hasCustomPalette(tilesetId) ||
                            editorState.getPaletteEffect("tileset:$tilesetId") != null
                    Box {
                        Surface(
                            modifier = Modifier
                                .height(28.dp)
                                .clickable(enabled = hasRevertOptions) { revertMenuExpanded = true },
                            shape = MaterialTheme.shapes.small,
                            color = if (hasRevertOptions) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = if (hasRevertOptions) 1f else 0.45f
                                    )
                                )
                                Text(
                                    "Revert",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = if (hasRevertOptions) 1f else 0.45f
                                    )
                                )
                                if (hasRevertOptions) {
                                    Text("●", fontSize = 8.sp, color = Color(0xFF66BB6A))
                                }
                                Text(
                                    "▾",
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = if (hasRevertOptions) 0.8f else 0.35f
                                    )
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = revertMenuExpanded,
                            onDismissRequest = { revertMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Revert Area Tiles (URE)", fontSize = 10.sp) },
                                enabled = editorState.hasCustomVarGfx(),
                                onClick = {
                                    revertMenuExpanded = false
                                    if (editorState.resetCurrentTilesetOverrides(areaTiles = true, commonTiles = false, palette = false)) {
                                        reloadCurrentTileset()
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("Revert Common Tiles (CRE)", fontSize = 10.sp) },
                                enabled = editorState.hasCustomCreGfx(),
                                onClick = {
                                    revertMenuExpanded = false
                                    if (editorState.resetCurrentTilesetOverrides(areaTiles = false, commonTiles = true, palette = false)) {
                                        reloadCurrentTileset()
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("Revert Area Metatiles", fontSize = 10.sp) },
                                enabled = editorState.hasCustomVarTileTable(),
                                onClick = {
                                    revertMenuExpanded = false
                                    if (editorState.resetCurrentTilesetOverrides(
                                            areaTiles = false,
                                            commonTiles = false,
                                            palette = false,
                                            areaMetatiles = true,
                                            commonMetatiles = false
                                        )
                                    ) {
                                        reloadCurrentTileset()
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("Revert Common Metatiles (CRE)", fontSize = 10.sp) },
                                enabled = editorState.hasCustomCreTileTable(),
                                onClick = {
                                    revertMenuExpanded = false
                                    if (editorState.resetCurrentTilesetOverrides(
                                            areaTiles = false,
                                            commonTiles = false,
                                            palette = false,
                                            areaMetatiles = false,
                                            commonMetatiles = true
                                        )
                                    ) {
                                        reloadCurrentTileset()
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("Revert Palette", fontSize = 10.sp) },
                                enabled = hasPaletteOverride,
                                onClick = {
                                    revertMenuExpanded = false
                                    if (editorState.resetCurrentTilesetOverrides(areaTiles = false, commonTiles = false, palette = true)) {
                                        reloadCurrentTileset()
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Revert All", fontSize = 10.sp) },
                                enabled = hasRevertOptions,
                                onClick = {
                                    revertMenuExpanded = false
                                    if (editorState.resetCurrentTilesetOverrides(
                                            areaTiles = true,
                                            commonTiles = true,
                                            palette = true,
                                            areaMetatiles = true,
                                            commonMetatiles = true
                                        )
                                    ) {
                                        reloadCurrentTileset()
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }

                    Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                    Text("${(zoomLevel * 100).toInt()}%", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface)
            }

            // ── Main grid area ──
            Box(
                modifier = Modifier.fillMaxSize().background(EditorColors.romBackground)
            ) {
                when {
                    tilesetEditorState.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("Loading…", color = Color.White, fontSize = 12.sp)
                    }
                    tilesetEditorState.errorMessage != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(tilesetEditorState.errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                    romParser == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("Open a ROM first", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    gridData != null -> {
                        val data = gridData
                        val tg = editorState.editorTileGraphics
                        val highlightPalette = tilesetEditorState.highlightPalette
                        val bitmap = remember(data, selectedMeta, highlightPalette) {
                            tilesetEditorGrid(data, selectedMeta, highlightPalette, tg).toComposeImageBitmap()
                        }
                        val hScroll = rememberScrollState()
                        val vScroll = rememberScrollState()
                        val density = LocalDensity.current.density

                        Box(
                            modifier = Modifier.fillMaxSize()
                                .onPointerEvent(PointerEventType.Scroll) { event ->
                                    val ne = event.nativeEvent as? MouseEvent
                                    val sd = event.changes.first().scrollDelta
                                    val zoom = ne?.let { it.isControlDown || it.isMetaDown } ?: false
                                    if (zoom) {
                                        zoomState.value = (zoomLevel * if (sd.y < 0) EditorColors.ZOOM_FACTOR else 1f / EditorColors.ZOOM_FACTOR).coerceIn(0.5f, 8f)
                                    } else if (ne?.isShiftDown == true) {
                                        val delta = if (sd.x != 0f) sd.x else sd.y
                                        coroutineScope.launch {
                                            hScroll.scrollTo((hScroll.value + (delta * 40).toInt()).coerceIn(0, hScroll.maxValue))
                                        }
                                    } else {
                                        coroutineScope.launch {
                                            vScroll.scrollTo((vScroll.value + (sd.y * 40).toInt()).coerceIn(0, vScroll.maxValue))
                                        }
                                    }
                                }
                                .onPointerEvent(PointerEventType.Press) { event ->
                                    val ne = event.nativeEvent as? MouseEvent ?: return@onPointerEvent
                                    if (ne.button == MouseEvent.BUTTON1) {
                                        val pos = event.changes.first().position
                                        // Pointer coords are physical pixels; divide by density
                                        val tilePx = 16f * zoomLevel * density
                                        val tx = ((pos.x + hScroll.value) / tilePx).toInt()
                                        val ty = ((pos.y + vScroll.value) / tilePx).toInt()
                                        val idx = ty * data.gridCols + tx
                                        if (idx in 0 until 1024) editorState.selectEditorMetatile(idx)
                                    }
                                }
                                .horizontalScroll(hScroll)
                                .verticalScroll(vScroll)
                        ) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier
                                    .requiredWidth((data.width * zoomLevel).dp)
                                    .requiredHeight((data.height * zoomLevel).dp)
                                    .clearAndSetSemantics { },
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Metatile composer ────────────────────────────────────────────────

private val SUBTILE_LABELS = listOf("TL", "TR", "BL", "BR")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetatileComposer(
    tileGraphics: TileGraphics,
    editorState: EditorState,
    tilesetEditorState: TilesetEditorState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metatileIndex = editorState.editorSelectedMetatile
    var selectedQuadrant by remember(metatileIndex) { mutableStateOf(0) }
    var showTilePicker by remember(metatileIndex) { mutableStateOf(false) }
    val initialWords = remember(metatileIndex) {
        tileGraphics.getMetatileWords(metatileIndex) ?: IntArray(4)
    }
    var committedWords by remember(metatileIndex) { mutableStateOf(initialWords.copyOf()) }
    var stagedWords by remember(metatileIndex) { mutableStateOf(initialWords.copyOf()) }
    val selectedWord = stagedWords[selectedQuadrant]
    val selectedSubtile = TileGraphics.decodeMetatileWord(selectedWord)
    val hasStagedChanges = !stagedWords.contentEquals(committedWords)

    fun updateSelectedSubtile(
        tileNum: Int = selectedSubtile.tileNum,
        palette: Int = selectedSubtile.palette,
        priority: Boolean = selectedSubtile.priority,
        hFlip: Boolean = selectedSubtile.hFlip,
        vFlip: Boolean = selectedSubtile.vFlip,
    ) {
        val nextWords = stagedWords.copyOf()
        nextWords[selectedQuadrant] = TileGraphics.encodeMetatileWord(
            tileNum = tileNum,
            palette = palette,
            priority = priority,
            hFlip = hFlip,
            vFlip = vFlip,
        )
        stagedWords = nextWords
    }

    fun applyStagedWords() {
        if (!hasStagedChanges) return
        if (editorState.setCurrentMetatileWords(stagedWords.copyOf())) {
            committedWords = stagedWords.copyOf()
            tilesetEditorState.refreshGrid(tileGraphics)
        }
    }

    val preview = remember(stagedWords, editorState.paletteVersion) {
        tileGraphics.renderMetatileWords(stagedWords)?.let { pixels ->
            val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, 16, 16, pixels, 0, 16)
            img.toComposeImageBitmap()
        }
    }
    val tableScope = if (tileGraphics.isCreMetatileIndex(metatileIndex)) {
        "Shared CRE metatile table"
    } else {
        "Tileset ${editorState.editorTilesetId} variable metatile table"
    }
    val selectedTileSource = tileSourceLabel(tileGraphics, selectedSubtile.tileNum)
    val creReferencesAreaTile = tileGraphics.isCreMetatileIndex(metatileIndex) &&
            selectedSubtile.tileNum < tileGraphics.getCreOffset()
    val hasTableOverride = if (tileGraphics.isCreMetatileIndex(metatileIndex)) {
        editorState.hasCustomCreTileTable()
    } else {
        editorState.hasCustomVarTileTable()
    }

    Column(modifier = modifier.fillMaxSize().background(EditorColors.romBackground)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Metatile #$metatileIndex (0x${metatileIndex.toString(16).uppercase().padStart(3, '0')})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    tableScope,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasTableOverride) {
                    Text("●", fontSize = 9.sp, color = Color(0xFF66BB6A))
                }
                Spacer(Modifier.weight(1f))
                if (hasStagedChanges) {
                    Text("staged", fontSize = 9.sp, color = Color(0xFFFFCA28))
                }
                Surface(
                    modifier = Modifier.height(28.dp).clickable(enabled = hasStagedChanges) { applyStagedWords() },
                    shape = MaterialTheme.shapes.small,
                    color = if (hasStagedChanges) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        "Apply",
                        fontSize = 9.sp,
                        color = if (hasStagedChanges) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 0.dp)
                    )
                }
                if (hasStagedChanges) {
                    Surface(
                        modifier = Modifier.height(28.dp).clickable { stagedWords = committedWords.copyOf() },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "Discard",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 0.dp)
                        )
                    }
                }
                Surface(
                    modifier = Modifier.height(28.dp).clickable { onClose() },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "Close",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 0.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.width(280.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = null,
                        modifier = Modifier
                            .size(224.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .clearAndSetSemantics { },
                        contentScale = ContentScale.FillBounds
                    )
                }
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (q in 0..1) {
                            SubtileSelector(
                                label = SUBTILE_LABELS[q],
                                word = stagedWords[q],
                                tileGraphics = tileGraphics,
                                selected = selectedQuadrant == q,
                                onClick = { selectedQuadrant = q },
                                modifier = Modifier.width(132.dp)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (q in 2..3) {
                            SubtileSelector(
                                label = SUBTILE_LABELS[q],
                                word = stagedWords[q],
                                tileGraphics = tileGraphics,
                                selected = selectedQuadrant == q,
                                onClick = { selectedQuadrant = q },
                                modifier = Modifier.width(132.dp)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${SUBTILE_LABELS[selectedQuadrant]} subtile word 0x${selectedWord.toString(16).uppercase().padStart(4, '0')}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tile", fontSize = 10.sp, modifier = Modifier.width(58.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatNumberInput(
                        value = selectedSubtile.tileNum,
                        onChange = { updateSelectedSubtile(tileNum = it.coerceIn(0, TileGraphics.TOTAL_TILES - 1)) },
                        maxDigits = 4,
                        maxValue = TileGraphics.TOTAL_TILES - 1,
                        modifier = Modifier.width(82.dp)
                    )
                    Text(
                        "0x${selectedSubtile.tileNum.toString(16).uppercase().padStart(3, '0')}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        selectedTileSource,
                        fontSize = 9.sp,
                        color = if (selectedTileSource == "CRE") Color(0xFF42A5F5) else Color(0xFF66BB6A)
                    )
                    FilterChip(
                        selected = showTilePicker,
                        onClick = { showTilePicker = !showTilePicker },
                        label = { Text("Pick", fontSize = 9.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Palette", fontSize = 10.sp, modifier = Modifier.width(58.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatNumberInput(
                        value = selectedSubtile.palette,
                        onChange = { updateSelectedSubtile(palette = it.coerceIn(0, 7)) },
                        maxDigits = 1,
                        maxValue = 7,
                        modifier = Modifier.width(82.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = selectedSubtile.priority,
                        onClick = { updateSelectedSubtile(priority = !selectedSubtile.priority) },
                        label = { Text("Priority", fontSize = 9.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    FilterChip(
                        selected = selectedSubtile.hFlip,
                        onClick = { updateSelectedSubtile(hFlip = !selectedSubtile.hFlip) },
                        label = { Text("H Flip", fontSize = 9.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    FilterChip(
                        selected = selectedSubtile.vFlip,
                        onClick = { updateSelectedSubtile(vFlip = !selectedSubtile.vFlip) },
                        label = { Text("V Flip", fontSize = 9.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }

                Text(
                    "Staged word 0x${selectedWord.toString(16).uppercase().padStart(4, '0')}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (creReferencesAreaTile) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = Color(0xFF5D4037)
                    ) {
                        Text(
                            "Shared CRE metatile uses an Area tile; it will vary by tileset.",
                            fontSize = 10.sp,
                            color = Color(0xFFFFF3E0),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }

                if (showTilePicker) {
                    VisualTilePicker(
                        tileGraphics = tileGraphics,
                        selectedTile = selectedSubtile.tileNum,
                        paletteRow = selectedSubtile.palette,
                        versionKey = editorState.paletteVersion,
                        onPick = { updateSelectedSubtile(tileNum = it) },
                        modifier = Modifier.fillMaxWidth().height(320.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtileSelector(
    label: String,
    word: Int,
    tileGraphics: TileGraphics,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val subtile = TileGraphics.decodeMetatileWord(word)
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier
            .height(58.dp)
            .clickable { onClick() }
            .then(
                if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                else Modifier
            ),
        shape = MaterialTheme.shapes.small,
        color = bg
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg)
            Text(
                "${tileSourceLabel(tileGraphics, subtile.tileNum)} ${subtile.tileNum}  pal ${subtile.palette}",
                fontSize = 9.sp,
                color = fg
            )
            val flags = buildString {
                if (subtile.priority) append("P")
                if (subtile.hFlip) append("H")
                if (subtile.vFlip) append("V")
                if (isEmpty()) append("-")
            }
            Text(
                "0x${word.toString(16).uppercase().padStart(4, '0')}  $flags",
                fontSize = 9.sp,
                color = fg.copy(alpha = 0.82f)
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun VisualTilePicker(
    tileGraphics: TileGraphics,
    selectedTile: Int,
    paletteRow: Int,
    versionKey: Any?,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cols = 16
    val zoom = 3f
    val creOffset = tileGraphics.getCreOffset()
    val hasCreTiles = creOffset < TileGraphics.TOTAL_TILES
    var scope by remember(creOffset) { mutableStateOf(TilePickerScope.ALL) }
    val startTile = when (scope) {
        TilePickerScope.ALL -> 0
        TilePickerScope.AREA -> 0
        TilePickerScope.CRE -> creOffset
    }
    val tileCount = when (scope) {
        TilePickerScope.ALL -> TileGraphics.TOTAL_TILES
        TilePickerScope.AREA -> creOffset
        TilePickerScope.CRE -> TileGraphics.TOTAL_TILES - creOffset
    }
    val sheet = remember(tileGraphics, versionKey, paletteRow, startTile, tileCount) {
        val palMap = IntArray(TileGraphics.TOTAL_TILES) { paletteRow.coerceIn(0, 7) }
        tileGraphics.renderTileSheet(startTile, tileCount, cols = cols, tilePalMap = palMap)
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Text(
                "Tile Picker - palette $paletteRow",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = scope == TilePickerScope.ALL,
                    onClick = { scope = TilePickerScope.ALL },
                    label = { Text("All", fontSize = 9.sp) },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = scope == TilePickerScope.AREA,
                    onClick = { scope = TilePickerScope.AREA },
                    label = { Text("Area", fontSize = 9.sp) },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = scope == TilePickerScope.CRE,
                    enabled = hasCreTiles,
                    onClick = { scope = TilePickerScope.CRE },
                    label = { Text("CRE", fontSize = 9.sp) },
                    modifier = Modifier.height(28.dp)
                )
                Text(
                    "${startTile.toString(16).uppercase().padStart(3, '0')}-${(startTile + tileCount - 1).toString(16).uppercase().padStart(3, '0')}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            if (sheet == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tile data", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            val (pixels, sheetWidth, sheetHeight) = sheet
            val bitmap = remember(sheet, selectedTile, startTile) {
                tilePickerImage(pixels, sheetWidth, sheetHeight, cols, startTile, selectedTile).toComposeImageBitmap()
            }
            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()
            val density = LocalDensity.current.density

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(EditorColors.romBackground)
                    .onPointerEvent(PointerEventType.Press) { event ->
                        val ne = event.nativeEvent as? MouseEvent ?: return@onPointerEvent
                        if (ne.button != MouseEvent.BUTTON1) return@onPointerEvent
                        val pos = event.changes.first().position
                        val tilePx = 8f * zoom * density
                        val tx = ((pos.x + hScroll.value) / tilePx).toInt()
                        val ty = ((pos.y + vScroll.value) / tilePx).toInt()
                        val tile = startTile + ty * cols + tx
                        if (tile in startTile until (startTile + tileCount)) onPick(tile)
                    }
                    .horizontalScroll(hScroll)
                    .verticalScroll(vScroll)
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .requiredWidth((sheetWidth * zoom).dp)
                        .requiredHeight((sheetHeight * zoom).dp)
                        .clearAndSetSemantics { },
                    contentScale = ContentScale.FillBounds
                )
            }
        }
    }
}

private fun tilePickerImage(
    pixels: IntArray,
    width: Int,
    height: Int,
    cols: Int,
    startTile: Int,
    selectedTile: Int,
): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, width, height, pixels, 0, width)
    val g = img.createGraphics()
    val localTile = selectedTile - startTile
    if (localTile >= 0) {
        val x = (localTile % cols) * 8
        val y = (localTile / cols) * 8
        if (x + 7 >= width || y + 7 >= height) {
            g.dispose()
            return img
        }
        g.color = java.awt.Color(255, 200, 0, 220)
        g.stroke = java.awt.BasicStroke(1f)
        g.drawRect(x, y, 7, 7)
    }
    g.dispose()
    return img
}

private fun tileSourceLabel(tileGraphics: TileGraphics, tileNum: Int): String {
    if (tileNum !in 0 until TileGraphics.TOTAL_TILES) return "Invalid"
    return if (tileNum >= tileGraphics.getCreOffset()) "CRE" else "Area"
}

// ─── Toolbar tile info (inline, horizontal) ────────────────────────────

@Composable
private fun TileToolbarInfo(
    tilesetId: Int,
    metatileIndex: Int,
    editorState: EditorState,
    previewVersion: Any?,
    modifier: Modifier = Modifier
) {
    val eff = editorState.getEffectiveTileDefault(tilesetId, metatileIndex)
    var blockType by remember(tilesetId, metatileIndex) { mutableStateOf(eff.blockType) }
    var bts by remember(tilesetId, metatileIndex) { mutableStateOf(eff.bts) }
    val hasOverride = editorState.hasProjectOverride(tilesetId, metatileIndex)
    val hardcoded = TilesetDefaults.get(metatileIndex)

    val tg = editorState.editorTileGraphics
    val preview = remember(tilesetId, metatileIndex, previewVersion) {
        tg?.renderMetatile(metatileIndex)?.let { pixels ->
            val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, 16, 16, pixels, 0, 16)
            img.toComposeImageBitmap()
        }
    }

    val palIndices = remember(tilesetId, metatileIndex, previewVersion) {
        tg?.getMetatilePalettes(metatileIndex) ?: emptySet()
    }

    val sourceText = when {
        hasOverride -> "override"
        hardcoded != null -> "hardcoded"
        else -> "none"
    }
    val sourceColor = when {
        hasOverride -> Color(0xFF66BB6A)
        hardcoded != null -> Color(0xFF42A5F5)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier.height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                modifier = Modifier.size(20.dp).clearAndSetSemantics { },
                contentScale = ContentScale.FillBounds
            )
        }

        val palText = if (palIndices.isNotEmpty()) " pal ${palIndices.sorted().joinToString(",")}" else ""
        Text("#$metatileIndex (0x${metatileIndex.toString(16).uppercase().padStart(3, '0')})$palText",
            fontSize = 10.sp)

        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

        // Block Type dropdown
        var btExpanded by remember { mutableStateOf(false) }
        Box {
            Surface(
                modifier = Modifier.width(140.dp).height(24.dp)
                    .clickable { btExpanded = true },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "0x${blockType.toString(16).uppercase()} ${blockTypeName(blockType)}",
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text("▾", fontSize = 8.sp)
                }
            }
            DropdownMenu(expanded = btExpanded, onDismissRequest = { btExpanded = false }) {
                for ((typeVal, typeName) in EDITABLE_BLOCK_TYPES) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RadioButton(selected = blockType == typeVal, onClick = null, modifier = Modifier.size(14.dp))
                                Text("0x${typeVal.toString(16).uppercase()} $typeName", fontSize = 10.sp)
                            }
                        },
                        onClick = {
                            btExpanded = false
                            blockType = typeVal
                            editorState.setTileDefault(tilesetId, metatileIndex, typeVal, bts)
                        },
                        modifier = Modifier.height(26.dp)
                    )
                }
            }
        }

        // BTS dropdown
        val btsOptions = btsOptionsForBlockType(blockType)
        if (btsOptions.isNotEmpty()) {
            var btsExpanded by remember { mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier.width(140.dp).height(24.dp)
                        .clickable { btsExpanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val btsName = btsOptions.find { it.first == bts }?.second
                            ?: "0x${bts.toString(16).uppercase().padStart(2, '0')}"
                        Text(btsName, fontSize = 9.sp, modifier = Modifier.weight(1f))
                        Text("▾", fontSize = 8.sp)
                    }
                }
                DropdownMenu(expanded = btsExpanded, onDismissRequest = { btsExpanded = false }) {
                    for ((btsVal, btsName) in btsOptions) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    RadioButton(selected = bts == btsVal, onClick = null, modifier = Modifier.size(14.dp))
                                    Text("0x${btsVal.toString(16).uppercase().padStart(2, '0')} $btsName", fontSize = 10.sp)
                                }
                            },
                            onClick = {
                                btsExpanded = false
                                bts = btsVal
                                editorState.setTileDefault(tilesetId, metatileIndex, blockType, btsVal)
                            },
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }
            }
        }

        Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)

        // Source badge
        Text(sourceText, fontSize = 8.sp, color = sourceColor)

        // Reset button
        if (hasOverride) {
            TextButton(
                onClick = {
                    editorState.clearTileDefault(tilesetId, metatileIndex)
                    val reverted = editorState.getEffectiveTileDefault(tilesetId, metatileIndex)
                    blockType = reverted.blockType
                    bts = reverted.bts
                },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(22.dp)
            ) {
                Text("Reset", fontSize = 8.sp)
            }
        }
    }
}

// ─── Palette bar ───────────────────────────────────────────────────────

@Composable
private fun PaletteBar(
    palettes: Array<IntArray>,
    highlightPalette: Int,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        for (palIdx in palettes.indices) {
            val isHighlighted = palIdx == highlightPalette
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clickable { onToggle(palIdx) }
                    .then(
                        if (isHighlighted) Modifier.border(1.dp, Color.White)
                        else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$palIdx",
                    fontSize = 8.sp,
                    color = if (isHighlighted) Color.White else Color.Gray,
                    modifier = Modifier.width(12.dp)
                )
                for (col in palettes[palIdx]) {
                    val r = (col shr 16) and 0xFF
                    val g = (col shr 8) and 0xFF
                    val b = col and 0xFF
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(r / 255f, g / 255f, b / 255f))
                    )
                }
            }
        }
    }
}

// ─── Grid rendering helper ─────────────────────────────────────────────

private fun tilesetEditorGrid(
    data: TilesetGridData,
    selectedMeta: Int,
    highlightPalette: Int,
    tg: TileGraphics?
): BufferedImage {
    val img = BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, data.width, data.height, data.pixels, 0, data.width)
    val g = img.createGraphics()

    if (highlightPalette >= 0 && tg != null) {
        for (i in 0 until 1024) {
            val pals = tg.getMetatilePalettes(i)
            if (!pals.contains(highlightPalette)) {
                val col = i % data.gridCols
                val row = i / data.gridCols
                val px = col * 16; val py = row * 16
                g.color = java.awt.Color(0, 0, 0, 160)
                g.fillRect(px, py, 16, 16)
            }
        }
    }

    if (selectedMeta in 0 until 1024) {
        val col = selectedMeta % data.gridCols
        val row = selectedMeta / data.gridCols
        val px = col * 16; val py = row * 16
        g.color = java.awt.Color(255, 255, 255, 60)
        g.fillRect(px, py, 16, 16)
        g.color = java.awt.Color(255, 200, 0, 220)
        g.stroke = java.awt.BasicStroke(2f)
        g.drawRect(px, py, 15, 15)
    }

    g.dispose()
    return img
}
