package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Reusable HSV color picker with:
 *   - Saturation/Brightness square (X = saturation, Y = brightness)
 *   - Horizontal hue strip (rainbow gradient)
 *   - Preview swatch + hex display
 *
 * Accepts and emits SNES BGR555 values (0-0x7FFF).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HsvColorPicker(
    bgr555: Int,
    onColorChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val fs = LocalEditorTheme.current.fontSize.value
    // Convert BGR555 → RGB float → HSV
    val r5init = bgr555 and 0x1F
    val g5init = (bgr555 shr 5) and 0x1F
    val b5init = (bgr555 shr 10) and 0x1F
    val rInit = r5init / 31f
    val gInit = g5init / 31f
    val bInit = b5init / 31f
    val (hInit, sInit, vInit) = rgbToHsv(rInit, gInit, bInit)

    var hue by remember(bgr555) { mutableStateOf(hInit) }
    var sat by remember(bgr555) { mutableStateOf(sInit) }
    var bri by remember(bgr555) { mutableStateOf(vInit) }

    fun emit() {
        val (r, g, b) = hsvToRgb(hue, sat, bri)
        val r5 = (r * 31).roundToInt().coerceIn(0, 31)
        val g5 = (g * 31).roundToInt().coerceIn(0, 31)
        val b5 = (b * 31).roundToInt().coerceIn(0, 31)
        onColorChanged((b5 shl 10) or (g5 shl 5) or r5)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Saturation/Brightness square (fills available width) ──
        var sqDragging by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onPointerEvent(PointerEventType.Press) { e ->
                        sqDragging = true
                        val pos = e.changes.first().position
                        sat = (pos.x / size.width).coerceIn(0f, 1f)
                        bri = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                        emit()
                    }
                    .onPointerEvent(PointerEventType.Move) { e ->
                        if (sqDragging) {
                            val pos = e.changes.first().position
                            sat = (pos.x / size.width).coerceIn(0f, 1f)
                            bri = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                            emit()
                        }
                    }
                    .onPointerEvent(PointerEventType.Release) { sqDragging = false }
            ) {
                val w = size.width
                val h = size.height
                val steps = 32
                val cellW = w / steps
                val cellH = h / steps
                // Draw the sat/bri grid for current hue
                for (sx in 0 until steps) {
                    for (sy in 0 until steps) {
                        val s = sx / (steps - 1f)
                        val v = 1f - sy / (steps - 1f)
                        val (cr, cg, cb) = hsvToRgb(hue, s, v)
                        drawRect(
                            Color(cr, cg, cb),
                            Offset(sx * cellW, sy * cellH),
                            Size(cellW + 1f, cellH + 1f)
                        )
                    }
                }
                // Crosshair at current sat/bri
                val cx = sat * w
                val cy = (1f - bri) * h
                drawCircle(Color.White, 6f, Offset(cx, cy), style = Stroke(2f))
                drawCircle(Color.Black, 4f, Offset(cx, cy), style = Stroke(1f))
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Hue strip ──
        var hueDragging by remember { mutableStateOf(false) }
        var hueStripWidth by remember { mutableStateOf(1f) }
        val hueHeight = 20.dp

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(hueHeight)
                .onPointerEvent(PointerEventType.Press) { e ->
                    hueDragging = true
                    val px = e.changes.first().position.x
                    hue = ((px / hueStripWidth) * 360f).coerceIn(0f, 360f)
                    emit()
                }
                .onPointerEvent(PointerEventType.Move) { e ->
                    if (hueDragging) {
                        val px = e.changes.first().position.x
                        hue = ((px / hueStripWidth) * 360f).coerceIn(0f, 360f)
                        emit()
                    }
                }
                .onPointerEvent(PointerEventType.Release) { hueDragging = false }
        ) {
            hueStripWidth = size.width
            val w = size.width
            val h = size.height
            val steps = 72
            val cellW = w / steps
            for (i in 0 until steps) {
                val h0 = i * 360f / steps
                val (cr, cg, cb) = hsvToRgb(h0, 1f, 1f)
                drawRect(Color(cr, cg, cb), Offset(i * cellW, 0f), Size(cellW + 1f, h))
            }
            // Marker at current hue
            val mx = (hue / 360f) * w
            drawRect(Color.White, Offset(mx - 2f, 0f), Size(4f, h), style = Stroke(2f))
        }

        Spacer(Modifier.height(8.dp))

        // ── Preview + hex info ──
        val (pr, pg, pb) = hsvToRgb(hue, sat, bri)
        val r5 = (pr * 31).roundToInt().coerceIn(0, 31)
        val g5 = (pg * 31).roundToInt().coerceIn(0, 31)
        val b5 = (pb * 31).roundToInt().coerceIn(0, 31)
        val snesHex = ((b5 shl 10) or (g5 shl 5) or r5).toString(16).uppercase().padStart(4, '0')

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier.size(32.dp)
                    .background(Color(pr, pg, pb), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF5A5F7C), RoundedCornerShape(4.dp))
            )
            Column {
                Text("\$$snesHex", fontSize = fs.body, color = Color(0xFFFFD54F), fontFamily = FontFamily.Monospace)
                Text("R:$r5 G:$g5 B:$b5", fontSize = fs.detail, color = Color(0xFF8890A8), fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── HSV ↔ RGB conversion ──────────────────────────────────────────────

private fun rgbToHsv(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val v = max
    val s = if (max == 0f) 0f else d / max
    val h = when {
        d == 0f -> 0f
        max == r -> ((g - b) / d).mod(6f) * 60f
        max == g -> ((b - r) / d + 2f) * 60f
        else -> ((r - g) / d + 4f) * 60f
    }
    return Triple(h, s, v)
}

internal fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
    val c = v * s
    val x = c * (1f - ((h / 60f).mod(2f) - 1f).let { if (it < 0) -it else it })
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Triple(r1 + m, g1 + m, b1 + m)
}
