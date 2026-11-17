package com.supermetroid.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.asm.AsmWorkspaceState
import java.awt.event.MouseWheelEvent

/** Full-height hit target, including blank rows and both gutter columns. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun AsmSourceGutterToggle(
    state: AsmWorkspaceState,
    paneId: Long,
    width: Dp,
    leadingOffset: Dp = 0.dp,
    horizontalOffset: Int,
    onScroll: (Offset, Boolean) -> Unit,
    modifier: Modifier,
) {
    // The target follows the scrolling gutter and is clipped at the viewport:
    // a horizontally hidden gutter must not intercept clicks on source code.
    Box(modifier.clipToBounds()) {
        Box(
            Modifier.offset(x = leadingOffset).offset { IntOffset(-horizontalOffset, 0) }
                .width(width).fillMaxHeight()
                .testTag("asm-source-gutter-toggle-$paneId")
                .pointerHoverIcon(PointerIcon.Hand)
                .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    onScroll(change.scrollDelta, (event.nativeEvent as? MouseWheelEvent)?.isShiftDown == true)
                    change.consume()
                }
                .focusProperties { canFocus = false }
                .clickable(onClickLabel = "Cycle gutter display", role = Role.Button) {
                    state.sourceGutterMode = state.sourceGutterMode.next()
                }
                .semantics {
                    contentDescription = "Source gutter"
                    stateDescription = state.sourceGutterMode.title
                },
        )
    }
}

/** A separate IDE-style lane keeps breakpoint clicks independent from the
 * line/address display toggle. The caller owns source-to-address validation. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun AsmBreakpointGutter(
    paneId: Long,
    width: Dp,
    horizontalOffset: Int,
    onLineClick: (Float) -> Unit,
    onScroll: (Offset, Boolean) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.clipToBounds()) {
        Box(
            Modifier.offset { IntOffset(-horizontalOffset, 0) }
                .width(width).fillMaxHeight()
                .testTag("asm-breakpoint-gutter-$paneId")
                .pointerHoverIcon(PointerIcon.Hand)
                .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    onScroll(change.scrollDelta, (event.nativeEvent as? MouseWheelEvent)?.isShiftDown == true)
                    change.consume()
                }
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    if (event.buttons.isPrimaryPressed) {
                        onLineClick(change.position.y)
                        change.consume()
                    }
                }
                .focusProperties { canFocus = false }
                .semantics {
                    contentDescription = "Breakpoint gutter"
                    stateDescription = "Click an emitted statement to add or remove a breakpoint"
                },
        )
    }
}
