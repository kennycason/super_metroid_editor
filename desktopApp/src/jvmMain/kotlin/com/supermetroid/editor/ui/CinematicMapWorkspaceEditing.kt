package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.CinematicMapAssets
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomVisualAssets
import com.supermetroid.editor.rom.SpriteAnimationFrame
import java.awt.image.BufferedImage
import kotlin.math.min

@Composable
internal fun CinematicMapSourceControls(
    editor: EditorState,
    visualAsset: RomVisualAssets.Asset,
    onEdit: (CinematicMapEditorState.Sheet) -> Unit,
) {
    val map = CinematicMapAssets.forVisualAsset(visualAsset) ?: return
    val status = remember(map.key, editor.asmVisualSourceRevision, editor.spriteVersion) {
        editor.cinematicMapEditingStatus(map.key)
    }
    var error by remember(map.key) { mutableStateOf<String?>(null) }
    val fs = LocalEditorTheme.current.fontSize.value
    Text("Layout: ${map.name}", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpriteWorkspaceAction("Edit map layout", {
            error = runCatching { onEdit(editor.cinematicMaps.load(visualAsset)) }.exceptionOrNull()?.message
        }, enabled = status.editable)
        if (status.editable && editor.cinematicMaps.hasChanges(map.key)) {
            SpriteWorkspaceAction("Restore map layout", { editor.cinematicMaps.restore(map.key) })
        }
    }
    status.message?.let {
        Text(
            it,
            fontSize = fs.detail,
            color = if (status.editable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.detail) }
}

@Composable
internal fun CinematicMapEditing(
    parser: RomParser,
    editor: EditorState,
    sheet: CinematicMapEditorState.Sheet,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var raw by remember(sheet) { mutableStateOf(sheet.rawData.copyOf()) }
    var selectedFrame by remember(sheet) { mutableIntStateOf(0) }
    var selectedX by remember(sheet) { mutableIntStateOf(0) }
    var selectedY by remember(sheet) { mutableIntStateOf(0) }
    var error by remember(sheet) { mutableStateOf<String?>(null) }
    val effective = remember(parser, editor.editVersion, editor.paletteVersion, editor.asmVisualSourceRevision) {
        runCatching { editor.nativeSprites.previewParser(parser) }.getOrDefault(parser)
    }
    val mapFrame = sheet.mapAsset.frame(selectedFrame)
    val preview = remember(effective, sheet, raw, selectedFrame) {
        editor.cinematicMaps.render(effective, sheet, raw, selectedFrame)
    }
    val bitmap = remember(preview) { preview?.toImageBitmap() }
    val entry = sheet.mapAsset.entry(raw, selectedX, selectedY, selectedFrame)
    val fs = LocalEditorTheme.current.fontSize.value

    Row(modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .background(Color(0xFF111122))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            CinematicMapCanvas(
                bitmap = bitmap,
                columns = mapFrame.columns,
                rows = mapFrame.rows,
                selectedX = selectedX,
                selectedY = selectedY,
                onSelect = { x, y -> selectedX = x; selectedY = y },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(sheet.mapAsset.name, fontSize = fs.heading)
            if (sheet.mapAsset.frames.size > 1) {
                SpriteOptionSelector(
                    sheet.mapAsset.frames.map { it.name },
                    selectedFrame,
                    {
                        selectedFrame = it
                        selectedX = 0
                        selectedY = 0
                    },
                    Modifier.fillMaxWidth(),
                )
            }
            Text(
                "Cell ${selectedX + 1}, ${selectedY + 1} · ${mapFrame.columns}×${mapFrame.rows}",
                fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TileNumberField(
                value = if (sheet.mapAsset.format == CinematicMapAssets.EntryFormat.MODE7_BYTE) entry else entry and 0x03FF,
                maxValue = if (sheet.mapAsset.format == CinematicMapAssets.EntryFormat.MODE7_BYTE) 0xFF else 0x03FF,
                onValue = { tile ->
                    val updated = if (sheet.mapAsset.format == CinematicMapAssets.EntryFormat.MODE7_BYTE) tile
                    else (entry and 0xFC00) or tile
                    raw = sheet.mapAsset.withEntry(raw, selectedX, selectedY, updated, selectedFrame)
                },
            )
            if (sheet.mapAsset.format == CinematicMapAssets.EntryFormat.SNES_BG_WORD) {
                TileNumberField(
                    label = "Palette row",
                    value = entry ushr 10 and 7,
                    maxValue = 7,
                    hex = false,
                    onValue = { palette ->
                        raw = sheet.mapAsset.withEntry(raw, selectedX, selectedY, (entry and 0xE3FF) or (palette shl 10), selectedFrame)
                    },
                )
                MapFlag("Priority", entry and 0x2000 != 0) { enabled ->
                    raw = sheet.mapAsset.withEntry(raw, selectedX, selectedY,
                        if (enabled) entry or 0x2000 else entry and 0x2000.inv(), selectedFrame)
                }
                MapFlag("Horizontal flip", entry and 0x4000 != 0) { enabled ->
                    raw = sheet.mapAsset.withEntry(raw, selectedX, selectedY,
                        if (enabled) entry or 0x4000 else entry and 0x4000.inv(), selectedFrame)
                }
                MapFlag("Vertical flip", entry and 0x8000 != 0) { enabled ->
                    raw = sheet.mapAsset.withEntry(raw, selectedX, selectedY,
                        if (enabled) entry or 0x8000 else entry and 0x8000.inv(), selectedFrame)
                }
            }
            Text(
                if (sheet.mapAsset.format == CinematicMapAssets.EntryFormat.MODE7_BYTE) {
                    "Mode 7 maps store one tile number per cell."
                } else {
                    "BG map words preserve tile, palette, priority and flip fields exactly."
                },
                fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.detail) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        error = null
                        val applied = runCatching { editor.cinematicMaps.apply(sheet, raw) }
                            .onFailure { error = it.message }
                            .getOrDefault(false)
                        if (applied || editor.pendingTilesetAssetRelocation is TilesetAssetRelocationProposal.CinematicMap) {
                            onClose()
                        }
                    },
                    enabled = !raw.contentEquals(sheet.rawData),
                ) { Text("Apply") }
                OutlinedButton(
                    onClick = { raw = sheet.rawData.copyOf(); error = null },
                    enabled = !raw.contentEquals(sheet.rawData),
                ) { Text("Revert") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun TileNumberField(
    value: Int,
    maxValue: Int,
    onValue: (Int) -> Unit,
    label: String = "Tile number",
    hex: Boolean = true,
) {
    var text by remember(value, hex) {
        mutableStateOf(if (hex) value.toString(16).uppercase().padStart(if (maxValue > 0xFF) 3 else 2, '0') else value.toString())
    }
    OutlinedTextField(
        value = text,
        onValueChange = { candidate ->
            val filtered = candidate.filter { if (hex) it.isDigit() || it.lowercaseChar() in 'a'..'f' else it.isDigit() }
                .take(if (hex && maxValue > 0xFF) 3 else 2)
            text = filtered
            filtered.toIntOrNull(if (hex) 16 else 10)?.takeIf { it in 0..maxValue }?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        prefix = if (hex) ({ Text("\$") }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MapFlag(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun CinematicMapCanvas(
    bitmap: ImageBitmap?,
    columns: Int,
    rows: Int,
    selectedX: Int,
    selectedY: Int,
    onSelect: (Int, Int) -> Unit,
    modifier: Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    Canvas(
        modifier.onSizeChanged { canvasSize = it }.pointerInput(bitmap, columns, rows, canvasSize) {
            detectTapGestures { tap ->
                val image = bitmap ?: return@detectTapGestures
                val scale = min(canvasSize.width.toFloat() / image.width, canvasSize.height.toFloat() / image.height)
                if (scale <= 0f) return@detectTapGestures
                val left = (canvasSize.width - image.width * scale) / 2f
                val top = (canvasSize.height - image.height * scale) / 2f
                val x = ((tap.x - left) / (8f * scale)).toInt()
                val y = ((tap.y - top) / (8f * scale)).toInt()
                if (x in 0 until columns && y in 0 until rows) onSelect(x, y)
            }
        },
    ) {
        val image = bitmap ?: return@Canvas
        val scale = min(size.width / image.width, size.height / image.height)
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        val left = ((size.width - width) / 2f).toInt()
        val top = ((size.height - height) / 2f).toInt()
        drawImage(
            image,
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(width, height),
            filterQuality = FilterQuality.None,
        )
        val cell = 8f * scale
        drawRect(
            color = Color.White,
            topLeft = Offset(left + selectedX * cell, top + selectedY * cell),
            size = Size(cell, cell),
            style = Stroke(width = 2f),
        )
    }
}

private fun SpriteAnimationFrame.toImageBitmap(): ImageBitmap =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, width, height, pixels, 0, width)
    }.toComposeImageBitmap()
