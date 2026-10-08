package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.TileGraphics

/**
 * Interactive palette editor for SNES tilesets.
 *
 * Features:
 *   - 8x16 color grid (click to select)
 *   - BGR555 R/G/B slider editor
 *   - HSV color picker (click the color preview swatch to toggle)
 *   - Per-color undo/redo stack
 *   - Save to Project / Reset to ROM / Restore All Defaults
 */
@Composable
fun PaletteEditor(
    tileGraphics: TileGraphics?,
    tilesetId: String?,
    hasCustomPalette: Boolean,
    sampledPaletteRow: Int = -1,
    sampledPaletteCol: Int = -1,
    onPaletteSaved: () -> Unit,
    onPaletteReset: () -> Unit,
    onRefreshNeeded: () -> Unit,
    onColorSelected: (row: Int, col: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val tg = tileGraphics
    if (tg == null || tilesetId == null) {
        Text("Load a room to edit its tileset palette.", fontSize = fs.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp))
        return
    }

    var selectedRow by remember { mutableStateOf(0) }
    var selectedCol by remember { mutableStateOf(1) }
    var editVersion by remember { mutableStateOf(0) }
    var showHsvPicker by remember { mutableStateOf(true) }

    // Auto-select palette row/col ONLY when sampled values change (not on every recomposition)
    var lastSampledRow by remember { mutableStateOf(-1) }
    var lastSampledCol by remember { mutableStateOf(-1) }
    if (sampledPaletteRow in 0..7 && (sampledPaletteRow != lastSampledRow || sampledPaletteCol != lastSampledCol)) {
        selectedRow = sampledPaletteRow
        lastSampledRow = sampledPaletteRow
        if (sampledPaletteCol in 1..15) {
            selectedCol = sampledPaletteCol
        }
        lastSampledCol = sampledPaletteCol
    }

    // Undo/redo stacks for palette edits
    data class PaletteEdit(val row: Int, val col: Int, val oldBgr555: Int, val newBgr555: Int)
    val undoStack = remember { mutableStateListOf<PaletteEdit>() }
    val redoStack = remember { mutableStateListOf<PaletteEdit>() }

    fun applyColorChange(row: Int, col: Int, newBgr: Int, recordUndo: Boolean = true) {
        if (recordUndo) {
            val oldBgr = tg.getSnesBgr555(row, col)
            if (oldBgr == newBgr) return
            undoStack.add(PaletteEdit(row, col, oldBgr, newBgr))
            redoStack.clear()
        }
        tg.setPaletteEntry(row, col, newBgr)
        editVersion++
        onRefreshNeeded()
    }

    fun undoPalette() {
        if (undoStack.isEmpty()) return
        val edit = undoStack.removeLast()
        tg.setPaletteEntry(edit.row, edit.col, edit.oldBgr555)
        redoStack.add(edit)
        selectedRow = edit.row
        selectedCol = edit.col
        editVersion++
        onRefreshNeeded()
    }

    fun redoPalette() {
        if (redoStack.isEmpty()) return
        val edit = redoStack.removeLast()
        tg.setPaletteEntry(edit.row, edit.col, edit.newBgr555)
        undoStack.add(edit)
        selectedRow = edit.row
        selectedCol = edit.col
        editVersion++
        onRefreshNeeded()
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text("Palette Editor", fontSize = fs.heading, fontWeight = FontWeight.Bold)
                Text(
                    "Tileset $tilesetId" + if (hasCustomPalette) " (modified)" else "",
                    fontSize = fs.body,
                    color = if (hasCustomPalette) Color(0xFFFFD54F) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Undo/Redo buttons
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { undoPalette() },
                    enabled = undoStack.isNotEmpty()
                ) {
                    Text("Undo", fontSize = fs.detail)
                }
                OutlinedButton(
                    onClick = { redoPalette() },
                    enabled = redoStack.isNotEmpty()
                ) {
                    Text("Redo", fontSize = fs.detail)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 8x16 palette grid
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF1A1C2E),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // Column header (0-F)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(20.dp))
                    for (c in 0..15) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                c.toString(16).uppercase(),
                                fontSize = 7.sp,
                                color = Color(0xFF6A6F88),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))

                for (row in 0..7) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(22.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$row",
                            fontSize = 8.sp,
                            color = if (row == selectedRow) Color(0xFFFFD54F) else Color(0xFF6A6F88),
                            fontWeight = if (row == selectedRow) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(20.dp),
                            fontFamily = FontFamily.Monospace
                        )
                        for (col in 0..15) {
                            @Suppress("UNUSED_VARIABLE")
                            val v = editVersion  // force recomposition on edit
                            val bgr555 = tg.getSnesBgr555(row, col)
                            val isSelected = row == selectedRow && col == selectedCol
                            val isTransparent = col == 0

                            val cellColor = if (bgr555 >= 0) bgr555ToColor(bgr555) else Color.Black

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(if (isTransparent) Color(0xFF2A2C3E) else cellColor)
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, Color.White)
                                        else if (isTransparent) Modifier.border(1.dp, Color(0xFF3A3C4E))
                                        else Modifier
                                    )
                                    .clickable {
                                        selectedRow = row
                                        selectedCol = col
                                        onColorSelected(row, col)
                                    }
                            ) {
                                if (isTransparent) {
                                    Text("T", fontSize = 7.sp, color = Color(0xFF4A4C5E),
                                        modifier = Modifier.align(Alignment.Center))
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Color editor for selected cell
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF1A1C2E),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Row $selectedRow, Color $selectedCol",
                        fontSize = fs.body,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (selectedCol != 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("HSV", fontSize = fs.detail, fontWeight = if (showHsvPicker) FontWeight.Bold else FontWeight.Normal,
                                color = if (showHsvPicker) Color(0xFF64B5F6) else Color(0xFF6A6F88),
                                modifier = Modifier.clickable { showHsvPicker = true })
                            Text("RGB", fontSize = fs.detail, fontWeight = if (!showHsvPicker) FontWeight.Bold else FontWeight.Normal,
                                color = if (!showHsvPicker) Color(0xFF64B5F6) else Color(0xFF6A6F88),
                                modifier = Modifier.clickable { showHsvPicker = false })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (selectedCol == 0) {
                    Text(
                        "Color index 0 is transparent and cannot be edited.",
                        fontSize = fs.detail,
                        color = Color(0xFF6A6F88)
                    )
                } else {
                    val bgr555 = remember(selectedRow, selectedCol, editVersion) {
                        tg.getSnesBgr555(selectedRow, selectedCol)
                    }
                    if (bgr555 >= 0) {
                        if (showHsvPicker) {
                            HsvColorPicker(bgr555, onColorChanged = { newBgr ->
                                applyColorChange(selectedRow, selectedCol, newBgr)
                            })
                        } else {
                            SnesBgr555Editor(bgr555) { newBgr ->
                                applyColorChange(selectedRow, selectedCol, newBgr)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Divider(color = Color(0xFF2A2C3E))
        Spacer(Modifier.height(12.dp))

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = { onPaletteSaved() }) {
                Text("Save to Project", fontSize = fs.body)
            }
            if (hasCustomPalette) {
                OutlinedButton(onClick = {
                    onPaletteReset()
                    undoStack.clear()
                    redoStack.clear()
                    editVersion++
                }) {
                    Text("Reset to ROM", fontSize = fs.body)
                }
            }
        }
    }
}

/** Convert BGR555 to Compose Color. */
private fun bgr555ToColor(bgr555: Int): Color {
    val r5 = bgr555 and 0x1F
    val g5 = (bgr555 shr 5) and 0x1F
    val b5 = (bgr555 shr 10) and 0x1F
    return Color(
        (r5 shl 3 or (r5 shr 2)) / 255f,
        (g5 shl 3 or (g5 shr 2)) / 255f,
        (b5 shl 3 or (b5 shr 2)) / 255f
    )
}
