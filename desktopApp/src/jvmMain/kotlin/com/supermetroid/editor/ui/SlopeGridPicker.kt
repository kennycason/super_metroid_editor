package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** ROM slope height tables indexed by shape byte (bits 0-4 of BTS), 16 column heights each. */
internal val SLOPE_HEIGHTS = arrayOf(
    intArrayOf( 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8), // 0x00 half solid bottom
    intArrayOf(16,16,16,16,16,16,16,16, 0, 0, 0, 0, 0, 0, 0, 0), // 0x01 half solid side
    intArrayOf(16,16,16,16,16,16,16,16, 8, 8, 8, 8, 8, 8, 8, 8), // 0x02 three-quarter
    intArrayOf( 8, 8, 8, 8, 8, 8, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0), // 0x03 quarter
    intArrayOf(16,16,16,16,16,16,16,16,16,16,16,16,16,16,16,16), // 0x04 fully solid (visual override)
    intArrayOf(16,15,14,13,12,11,10, 9, 9,10,11,12,13,14,15,16), // 0x05 shallow V-trough
    intArrayOf(16,14,12,10, 8, 6, 4, 2, 2, 4, 6, 8,10,12,14,16), // 0x06 deep V-trough
    intArrayOf( 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8), // 0x07 half solid (dup of 0x00)
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x08 unused
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x09 unused
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x0A unused
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x0B unused
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x0C unused
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x0D unused
    intArrayOf(12,12,12,12, 8, 8, 8, 8, 4, 4, 4, 4, 0, 0, 0, 0), // 0x0E staircase (4-step)
    intArrayOf(14,14,12,12,10,10, 8, 8, 6, 6, 4, 4, 2, 2, 0, 0), // 0x0F smooth staircase
    intArrayOf(16,16,16,16,16,16,16,16,16,16,16,16,16,16,16,16), // 0x10 fully solid
    intArrayOf(20,20,20,20,20,20,20,20,20,20,20,20,20,16,16,16), // 0x11 plateau (overshoot)
    intArrayOf(16,15,14,13,12,11,10, 9, 8, 7, 6, 5, 4, 3, 2, 1), // 0x12 steep 1-tile
    intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x13 unused
    intArrayOf( 8, 7, 6, 5, 4, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0), // 0x14 45° tile 1/2
    intArrayOf(16,16,16,16,16,16,16,16,16,15,14,13,12,11,10, 9), // 0x15 45° tile 2/2
    intArrayOf( 8, 8, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1), // 0x16 45° smooth tile 1/2
    intArrayOf(16,16,15,15,14,14,13,13,12,12,11,11,10,10, 9, 9), // 0x17 45° smooth tile 2/2
    intArrayOf( 6, 5, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2, 1, 1, 1), // 0x18 gentle tile 1/3
    intArrayOf(11,11,10,10,10, 9, 9, 9, 8, 8, 8, 7, 7, 7, 6, 6), // 0x19 gentle tile 2/3
    intArrayOf(16,16,16,15,15,15,14,14,14,13,13,13,12,12,12,11), // 0x1A gentle tile 3/3
    intArrayOf(16,14,12,10, 8, 6, 4, 2, 0, 0, 0, 0, 0, 0, 0, 0), // 0x1B steep tile 1/2
    intArrayOf(20,20,20,20,20,20,20,20,16,14,12,10, 8, 6, 4, 2), // 0x1C steep tile 2/2
    intArrayOf(16,13,10, 7, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // 0x1D steep tile 1/3
    intArrayOf(20,20,20,20,20,20,14,11, 8, 5, 2, 0, 0, 0, 0, 0), // 0x1E steep tile 2/3
    intArrayOf(20,20,20,20,20,20,20,20,20,20,20,15,12, 9, 6, 3), // 0x1F steep tile 3/3
)

private data class SlopeGroup(val label: String, val entries: List<Int>)

private val SLOPE_GRID_GROUPS = listOf(
    SlopeGroup("Square", listOf(0x00, 0x01, 0x02, 0x03, 0x04, -1, 0x07, 0x13)),
    SlopeGroup("45° Floor", listOf(0x14, 0x15, -1, 0x16, 0x17)),
    SlopeGroup("Gentle Floor", listOf(0x18, 0x19, 0x1A)),
    SlopeGroup("Steep Floor", listOf(0x12, -1, 0x1B, 0x1C, -1, 0x1D, 0x1E, 0x1F)),
    SlopeGroup("Square Ceiling", listOf(0x80, 0x82, 0x83, -1, 0x87, 0x93)),
    SlopeGroup("45° Ceiling", listOf(0x94, 0x95, -1, 0x96, 0x97)),
    SlopeGroup("Gentle Ceiling", listOf(0x98, 0x99, 0x9A)),
    SlopeGroup("Steep Ceiling", listOf(0x92, -1, 0x9B, 0x9C, -1, 0x9D, 0x9E, 0x9F)),
    SlopeGroup("Other", listOf(0x05, 0x06, -1, 0x0E, 0x0F, 0x10, 0x11)),
)

internal val SLOPE_BTS_NAMES: Map<Int, String> by lazy {
    btsOptionsForBlockType(0x1).associate { it.first to it.second }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SlopeGridPicker(
    selectedBts: Int,
    onSelect: (Int) -> Unit,
    onHoverBts: (Int?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cellSize = 26.dp
    val slopeColor = Color(0xFFEE7700)
    val selectedBorder = Color(0xFF44AAFF)
    val separatorColor = MaterialTheme.colorScheme.outlineVariant
    var hoveredBts by remember { mutableStateOf(-1) }

    val xFlip = (selectedBts and 0x40) != 0

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (group in SLOPE_GRID_GROUPS) {
            Text(group.label, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (bts in group.entries) {
                    if (bts == -1) {
                        Box(Modifier.width(4.dp).height(cellSize).background(separatorColor))
                        continue
                    }
                    val effectiveBts = if (xFlip) bts or 0x40 else bts
                    val isSelected = effectiveBts == selectedBts
                    val isHovered = bts == hoveredBts
                    Box(
                        modifier = Modifier
                            .size(cellSize)
                            .background(
                                when {
                                    isSelected -> selectedBorder.copy(alpha = 0.2f)
                                    isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                    else -> Color.Transparent
                                },
                                MaterialTheme.shapes.extraSmall
                            )
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) selectedBorder
                                        else if (isHovered) MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                shape = MaterialTheme.shapes.extraSmall
                            )
                            .clickable { onSelect(effectiveBts) }
                            .onPointerEvent(PointerEventType.Enter) {
                                hoveredBts = bts
                                onHoverBts(effectiveBts)
                            }
                            .onPointerEvent(PointerEventType.Exit) {
                                if (hoveredBts == bts) {
                                    hoveredBts = -1
                                    onHoverBts(null)
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                            drawSlopeCell(effectiveBts, slopeColor)
                        }
                        Text(
                            "0x${effectiveBts.toString(16).uppercase().padStart(2, '0')}",
                            fontSize = 7.sp,
                            color = Color.Black,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 1.dp)
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 2.dp, top = 4.dp)
        ) {
            Checkbox(
                checked = xFlip,
                onCheckedChange = { flip ->
                    val newBts = if (flip) selectedBts or 0x40 else selectedBts and 0x40.inv()
                    onSelect(newBts)
                },
                modifier = Modifier.size(18.dp)
            )
            Text("X-Flip", fontSize = 9.sp)
        }
    }
}

private fun DrawScope.drawSlopeCell(bts: Int, color: Color) {
    val s = size.width
    val shape = bts and 0x1F
    val isCeiling = (bts and 0x80) != 0
    val xFlip = (bts and 0x40) != 0

    if (shape >= SLOPE_HEIGHTS.size) return
    val heights = SLOPE_HEIGHTS[shape]
    if (heights.all { it == 0 }) {
        drawRect(color.copy(alpha = 0.5f))
        drawRect(color, style = Stroke(width = 1.5f))
        return
    }

    val path = Path()
    val scale = s / 16f

    if (!isCeiling) {
        path.moveTo(0f, s)
        for (screenX in 0 until 16) {
            val col = if (xFlip) screenX else (15 - screenX)
            val h = heights[col].coerceIn(0, 16)
            path.lineTo(screenX * scale, s - h * scale)
        }
        path.lineTo(s, s)
        path.close()
    } else {
        path.moveTo(0f, 0f)
        for (screenX in 0 until 16) {
            val col = if (xFlip) screenX else (15 - screenX)
            val h = heights[col].coerceIn(0, 16)
            path.lineTo(screenX * scale, h * scale)
        }
        path.lineTo(s, 0f)
        path.close()
    }

    drawPath(path, color.copy(alpha = 0.5f))
    drawPath(path, color, style = Stroke(width = 1.5f))
}
