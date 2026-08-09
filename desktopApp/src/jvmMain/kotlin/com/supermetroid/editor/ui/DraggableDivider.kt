package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * Drag handle for resizing column width.
 * Uses drag deltas to avoid absolute-position coordinate conversion issues.
 * Pointer capture via awaitEachGesture keeps events flowing even outside bounds.
 */
@Composable
fun DraggableDividerVertical(
    onDelta: (deltaPx: Float) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 10.dp
) {
    val currentOnDelta by rememberUpdatedState(onDelta)
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.W_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Release) break
                        if (event.type == PointerEventType.Move) {
                            val change = event.changes.firstOrNull() ?: continue
                            val dx = change.position.x - change.previousPosition.x
                            if (dx != 0f) currentOnDelta(dx)
                        }
                    }
                }
            }
    )
}

/**
 * Drag handle for resizing tileset height.
 * Same delta-based approach as the vertical divider.
 */
@Composable
fun DraggableDividerHorizontal(
    onDelta: (deltaPy: Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp
) {
    val currentOnDelta by rememberUpdatedState(onDelta)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Release) break
                        if (event.type == PointerEventType.Move) {
                            val change = event.changes.firstOrNull() ?: continue
                            val dy = change.position.y - change.previousPosition.y
                            if (dy != 0f) currentOnDelta(dy)
                        }
                    }
                }
            }
    )
}
