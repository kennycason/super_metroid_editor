package com.supermetroid.editor.ui

import java.awt.event.InputEvent
import java.awt.event.MouseEvent

internal fun isPrimaryPointerPress(nativeEvent: Any?): Boolean {
    val event = nativeEvent as? MouseEvent ?: return true
    return event.button == MouseEvent.BUTTON1
}

internal fun isPrimaryPointerStillDown(nativeEvent: Any?): Boolean {
    val event = nativeEvent as? MouseEvent ?: return true
    return event.modifiersEx and InputEvent.BUTTON1_DOWN_MASK != 0
}
