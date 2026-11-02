package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*

/** Relative vertical drag, native discrete steps, and one commit per completed gesture. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SynthKnob(
    label: String, value: Int, range: IntRange, readout: String, tag: String,
    onDraft: (Int) -> Unit, onFinish: () -> Unit, enabled: Boolean = true,
) {
    require(range.first < range.last)
    val fs = LocalEditorTheme.current.fontSize.value
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var keyboardDraft by remember { mutableStateOf(false) }
    var wheelDraft by remember { mutableStateOf(false) }
    var wheelPosition by remember { mutableStateOf<Float?>(null) }
    var wheelVersion by remember { mutableStateOf(0) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onDraft)
    val finish by rememberUpdatedState(onFinish)
    fun finishPending() {
        val pending = keyboardDraft || wheelDraft
        keyboardDraft = false; wheelDraft = false; wheelPosition = null
        if (pending) finish()
    }
    LaunchedEffect(wheelVersion) {
        if (wheelPosition != null) { delay(200); finishPending() }
    }
    val accent = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outlineVariant
    val face = MaterialTheme.colorScheme.surfaceVariant
    val pointer = MaterialTheme.colorScheme.onSurface
    val alpha = if (enabled) 1f else 0.35f
    Column(Modifier.width(100.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = fs.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Canvas(Modifier.size(72.dp).testTag(tag)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) finishPending() }
            .onPreviewKeyEvent { event ->
                if (!enabled) return@onPreviewKeyEvent false
                val next = when (event.key) {
                    Key.DirectionUp, Key.DirectionRight -> (current + 1).coerceAtMost(range.last)
                    Key.DirectionDown, Key.DirectionLeft -> (current - 1).coerceAtLeast(range.first)
                    Key.MoveHome -> range.first
                    Key.MoveEnd -> range.last
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    if (wheelPosition != null) finishPending()
                    if (next != current) { keyboardDraft = true; change(next) }
                }
                if (event.type == KeyEventType.KeyUp) finishPending()
                true
            }
            .focusable(enabled)
            .semantics {
                contentDescription = label
                stateDescription = readout
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), range.last - range.first - 1)
                if (!enabled) disabled()
                setProgress { requested ->
                    if (!enabled || !requested.isFinite()) false else {
                        val next = requested.roundToInt().coerceIn(range)
                        if (next == current) false else { finishPending(); change(next); finish(); true }
                    }
                }
            }
            // Scroll only after focusing a knob: ordinary inspector scrolling must not edit sounds.
            .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                if (enabled && focused) {
                    val delta = -event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                    if (delta != 0f) {
                        if (keyboardDraft) finishPending()
                        val nextPosition = ((wheelPosition ?: current.toFloat()) + delta).coerceIn(range.first.toFloat(), range.last.toFloat())
                        wheelPosition = nextPosition
                        val next = nextPosition.roundToInt()
                        if (next != current) { wheelDraft = true; change(next) }
                        wheelVersion++
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .pointerInput(enabled, range) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    finishPending()
                    focus.requestFocus()
                    val start = current
                    val changeGesture = change
                    val finishGesture = finish
                    var position = start.toFloat()
                    var distance = 0f
                    var dragging = false
                    var last = start
                    var completed = false
                    try {
                        completed = drag(down.id) { input ->
                            val fine = if (currentEvent.keyboardModifiers.isShiftPressed) 0.2f else 1f
                            val dy = input.positionChange().y
                            distance += dy
                            position = (position - dy * (range.last - range.first) / (160.dp.toPx()) * fine)
                                .coerceIn(range.first.toFloat(), range.last.toFloat())
                            if (!dragging && abs(distance) < 4.dp.toPx()) { input.consume(); return@drag }
                            dragging = true
                            val next = position.roundToInt()
                            if (next != last) { last = next; changeGesture(next) }
                            input.consume()
                        }
                    } finally {
                        if (last != start) {
                            if (completed) finishGesture() else changeGesture(start)
                        }
                    }
                }
            }) {
            val fraction = (value.coerceIn(range) - range.first).toFloat() / (range.last - range.first)
            val radius = size.minDimension / 2
            val inset = 5.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(ring.copy(alpha = alpha), 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            if (fraction > 0f) drawArc(accent.copy(alpha = alpha), 135f, 270f * fraction, false, Offset(inset, inset), arcSize,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(face.copy(alpha = alpha), radius - 11.dp.toPx())
            if (focused) drawCircle(accent.copy(alpha = 0.7f), radius - 10.dp.toPx(), style = Stroke(1.dp.toPx()))
            val angle = (135f + fraction * 270f) * PI / 180
            val a = radius * 0.25f; val b = radius * 0.58f
            drawLine(pointer.copy(alpha = alpha), center + Offset(cos(angle).toFloat() * a, sin(angle).toFloat() * a),
                center + Offset(cos(angle).toFloat() * b, sin(angle).toFloat() * b), 2.dp.toPx(), StrokeCap.Round)
        }
        Text(readout, fontSize = fs.detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    }
}
