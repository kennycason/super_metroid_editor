package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Short Charge distance data — 2D model with stutters × taps.
 *
 * Two independent variables determine shinespark charge distance:
 *   - **Taps** (0-4): releasing/re-pressing forward direction while dashing.
 *     Each tap exploits "magic frames" in the acceleration curve for speed gain.
 *   - **Stutters** (0-3): briefly walking (release dash) before re-engaging.
 *     Resets Samus's base speed, enabling taps to be more effective in shorter distances.
 *
 * Known data points from MapRandomizer sm-json-data/strats.md skill levels:
 *   Skill -: 0 stutters, 0 taps = 31 tiles (default, just hold dash)
 *   Skill A: 0 stutters, 1 tap  = 25 tiles
 *   Skill B: 1 stutter,  2 taps = 20 tiles
 *   Skill C: 1 stutter,  3 taps = 16 tiles
 *   Skill D: 1 stutter,  4 taps = 15 tiles
 *   Skill E: 2 stutters,  4 taps = 14 tiles
 *   Skill F: 3 stutters,  4 taps = 13 tiles
 *   Skill G: near-perfect         = 11 tiles
 *
 * Gaps filled with interpolation from run_speed.rs shortcharge table.
 */
object ShortChargeData {

    /**
     * Distance matrix: [stutters][taps] → tiles needed.
     * Rows = stutter count (0-3), columns = tap count (0-4).
     * Values marked (exact) are from MapRandomizer strats.md.
     */
    val distanceMatrix: Array<IntArray> = arrayOf(
        //     0t     1t     2t     3t     4t
        intArrayOf(31,    25,    22,    20,    18),   // 0 stutters  (31,25 exact)
        intArrayOf(29,    23,    20,    16,    15),   // 1 stutter   (20,16,15 exact)
        intArrayOf(27,    21,    18,    15,    14),   // 2 stutters  (14 exact)
        intArrayOf(25,    19,    16,    14,    13),   // 3 stutters  (13 exact)
    )

    /** Get tiles needed for a specific stutter+tap combination */
    fun tilesFor(stutters: Int, taps: Int): Int {
        val s = stutters.coerceIn(0, 3)
        val t = taps.coerceIn(0, 4)
        return distanceMatrix[s][t]
    }

    /** Maximum tiles (default, no technique) — determines ruler length */
    val maxTiles: Int = distanceMatrix[0][0]

    /** Display colors for each tap count */
    val colorForTaps: Map<Int, Color> = mapOf(
        0 to Color(0xFFAAAAAA),  // gray — default (baseline)
        1 to Color(0xFF81C784),  // green
        2 to Color(0xFF4FC3F7),  // light blue
        3 to Color(0xFFFFB74D),  // orange
        4 to Color(0xFFEF5350),  // red
    )

    /** Display colors for stutters */
    val colorForStutters: Map<Int, Color> = mapOf(
        0 to Color(0xFFAAAAAA),  // gray
        1 to Color(0xFF90CAF9),  // light blue
        2 to Color(0xFFCE93D8),  // purple
        3 to Color(0xFFF48FB1),  // pink
    )

    /** Tap labels */
    val labelForTaps: Map<Int, String> = mapOf(
        0 to "Default",
        1 to "1-tap",
        2 to "2-tap",
        3 to "3-tap",
        4 to "4-tap",
    )
}

/**
 * A draggable graduated ruler overlay showing shinespark charge distances.
 *
 * Two controls: stutter count (toolbar) and tap markers (on ruler).
 * The ruler length = default charge distance (31 tiles).
 * Colored vertical lines mark each tap count's distance for the selected stutter count.
 * Labels alternate vertical position to avoid overlap.
 *
 * Click and drag to reposition on the canvas.
 *
 * @param stutters current stutter count (0-3)
 * @param selectedTaps current highlighted tap count (0-4)
 * @param zoomLevel current canvas zoom
 * @param tileSize tile size in pixels (default 16)
 */
@Composable
fun ShortChargeRuler(
    stutters: Int,
    selectedTaps: Int,
    zoomLevel: Float,
    tileSize: Int = 16,
    modifier: Modifier = Modifier
) {
    val maxTiles = ShortChargeData.maxTiles
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val pxPerTile = tileSize * zoomLevel
    val rulerWidthDp = (maxTiles * pxPerTile / density)
    val rulerHeightDp = 64f

    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val textMeasurer = rememberTextMeasurer()
    val rulerFontSizes = LocalEditorTheme.current.fontSize.value
    val rulerLabelFontSize = rulerFontSizes.statusBar
    val selectedRulerLabelFontSize = rulerFontSizes.detail

    Canvas(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .width(rulerWidthDp.dp)
            .height(rulerHeightDp.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val scale = w / maxTiles  // pixels per tile in draw space
        val tickZoneTop = 18f
        val tickZoneBottom = h

        // Background
        drawRect(
            color = Color(0xCC1A1A2E.toInt()),
            size = size
        )
        drawRect(
            color = Color(0xFF333355.toInt()),
            size = size,
            style = Stroke(width = 1f)
        )

        // Title: "Short Charge" + stutter info
        val stutterSuffix = if (stutters > 0) "  ${stutters}s" else ""
        val titleResult = textMeasurer.measure(
            "Short Charge$stutterSuffix",
            TextStyle(fontSize = rulerLabelFontSize, color = Color(0xFFBBBBCC.toInt()))
        )
        drawText(titleResult, topLeft = Offset(3f, 2f))

        // Draw tick marks
        for (tile in 0..maxTiles) {
            val x = tile * scale

            // Major tick at each tile boundary
            val majorHeight = 16f
            drawLine(
                color = Color(0xFF666688.toInt()),
                start = Offset(x, tickZoneBottom),
                end = Offset(x, tickZoneBottom - majorHeight),
                strokeWidth = 1f
            )

            // Tile number labels every 5 tiles
            if (tile % 5 == 0 && tile > 0) {
                val label = tile.toString()
                val textResult = textMeasurer.measure(
                    label,
                    TextStyle(fontSize = rulerLabelFontSize, color = Color(0xFF888899.toInt()))
                )
                drawText(
                    textResult,
                    topLeft = Offset(
                        x - textResult.size.width / 2f,
                        tickZoneBottom - majorHeight - textResult.size.height - 1f
                    )
                )
            }

            if (tile < maxTiles) {
                // Half-tile tick (8px midpoint)
                val halfX = x + scale / 2f
                val midHeight = 10f
                drawLine(
                    color = Color(0xFF555577.toInt()),
                    start = Offset(halfX, tickZoneBottom),
                    end = Offset(halfX, tickZoneBottom - midHeight),
                    strokeWidth = 0.8f
                )

                // Quarter-tile ticks (4px marks)
                val quarterHeight = 6f
                val q1 = x + scale / 4f
                val q3 = x + 3f * scale / 4f
                drawLine(
                    color = Color(0xFF444466.toInt()),
                    start = Offset(q1, tickZoneBottom),
                    end = Offset(q1, tickZoneBottom - quarterHeight),
                    strokeWidth = 0.5f
                )
                drawLine(
                    color = Color(0xFF444466.toInt()),
                    start = Offset(q3, tickZoneBottom),
                    end = Offset(q3, tickZoneBottom - quarterHeight),
                    strokeWidth = 0.5f
                )
            }
        }

        // Draw distance markers for each tap count at the current stutter level
        val labelRowHeight = 11f
        for (taps in 4 downTo 0) {
            val tiles = ShortChargeData.tilesFor(stutters, taps)
            val color = ShortChargeData.colorForTaps[taps] ?: Color.White
            val label = ShortChargeData.labelForTaps[taps] ?: ""
            val x = tiles * scale
            val isSelected = taps == selectedTaps
            val alpha = if (isSelected) 1f else 0.35f
            val lineWidth = if (isSelected) 2.5f else 1.2f

            // Vertical marker line
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(x, tickZoneTop),
                end = Offset(x, tickZoneBottom),
                strokeWidth = lineWidth
            )

            // Shaded region from 0 to this distance (only for selected)
            if (isSelected) {
                drawRect(
                    color = color.copy(alpha = 0.08f),
                    topLeft = Offset(0f, tickZoneTop),
                    size = Size(x, tickZoneBottom - tickZoneTop)
                )
            }

            // Label — alternate rows to avoid overlap
            // taps 0,2,4 on top row; taps 1,3 on second row
            val labelRow = if (taps % 2 == 0) 0 else 1
            val labelY = tickZoneTop + labelRow * labelRowHeight
            val textResult = textMeasurer.measure(
                label,
                TextStyle(
                    fontSize = if (isSelected) selectedRulerLabelFontSize else rulerLabelFontSize,
                    color = color.copy(alpha = if (isSelected) 1f else 0.5f)
                )
            )
            val labelX = (x - textResult.size.width - 3f).coerceAtLeast(2f)
            drawText(textResult, topLeft = Offset(labelX, labelY))
        }
    }
}

/**
 * Stutter count selector — small row of numbered buttons (0-3).
 */
@Composable
fun StutterSelector(
    selectedStutters: Int,
    onStuttersChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Stutters:", fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (stutters in 0..3) {
            val color = ShortChargeData.colorForStutters[stutters] ?: Color.White
            val isSelected = stutters == selectedStutters
            Box(
                modifier = Modifier
                    .height(22.dp)
                    .width(24.dp)
                    .background(
                        if (isSelected) color.copy(alpha = 0.25f) else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 0.5.dp,
                        color = if (isSelected) color else color.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .clickable { onStuttersChanged(stutters) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$stutters",
                    fontSize = LocalEditorTheme.current.fontSize.value.detail,
                    color = if (isSelected) color else color.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Tap count selector — small row of numbered buttons (0-4).
 */
@Composable
fun TapSelector(
    selectedTaps: Int,
    onTapsChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Taps:", fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (taps in 0..4) {
            val color = ShortChargeData.colorForTaps[taps] ?: Color.White
            val isSelected = taps == selectedTaps
            Box(
                modifier = Modifier
                    .height(22.dp)
                    .width(24.dp)
                    .background(
                        if (isSelected) color.copy(alpha = 0.25f) else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 0.5.dp,
                        color = if (isSelected) color else color.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .clickable { onTapsChanged(taps) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$taps",
                    fontSize = LocalEditorTheme.current.fontSize.value.detail,
                    color = if (isSelected) color else color.copy(alpha = 0.5f)
                )
            }
        }
    }
}
