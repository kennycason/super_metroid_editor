package com.supermetroid.editor.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

@Composable
fun rememberVerticalSelectionFocusRequester(
    enabled: Boolean = true,
    requestFocusOnReady: Boolean = true,
    requestFocusKey: Any? = Unit,
): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(enabled, requestFocusOnReady, requestFocusKey) {
        if (enabled && requestFocusOnReady) {
            requestVerticalSelectionFocus(focusRequester)
        }
    }
    return focusRequester
}

fun requestVerticalSelectionFocus(focusRequester: FocusRequester) {
    runCatching { focusRequester.requestFocus() }
}

fun Modifier.verticalSelectionKeyNavigation(
    focusRequester: FocusRequester,
    itemCount: Int,
    selectedIndex: Int,
    enabled: Boolean = true,
    onSelectIndex: (Int) -> Unit,
): Modifier {
    if (!enabled) return this

    return focusRequester(focusRequester)
        .focusable(enabled = enabled)
        .onKeyEvent { event ->
            if (!enabled || itemCount <= 0 || event.type != KeyEventType.KeyDown) {
                return@onKeyEvent false
            }

            val nextIndex = when (event.key) {
                Key.DirectionUp -> nextVerticalSelectionIndex(selectedIndex, itemCount, -1)
                Key.DirectionDown -> nextVerticalSelectionIndex(selectedIndex, itemCount, 1)
                else -> return@onKeyEvent false
            }

            onSelectIndex(nextIndex)
            true
        }
}

private fun nextVerticalSelectionIndex(selectedIndex: Int, itemCount: Int, delta: Int): Int {
    if (selectedIndex !in 0 until itemCount) {
        return if (delta < 0) itemCount - 1 else 0
    }
    return (selectedIndex + delta + itemCount) % itemCount
}
