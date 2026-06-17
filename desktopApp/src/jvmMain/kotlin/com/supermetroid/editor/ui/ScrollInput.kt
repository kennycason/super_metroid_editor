package com.supermetroid.editor.ui

import java.awt.event.MouseEvent

/**
 * Normalizes wheel input for panning. On Linux, standard mice commonly expose
 * horizontal pan as Shift + vertical wheel rather than a dedicated X delta.
 */
internal data class PanScrollDelta(val x: Float, val y: Float)

internal fun resolvePanScrollDelta(
    rawX: Float,
    rawY: Float,
    shiftPressed: Boolean,
): PanScrollDelta {
    if (!shiftPressed) return PanScrollDelta(rawX, rawY)

    val horizontal = if (rawX != 0f) rawX else rawY
    return PanScrollDelta(x = horizontal, y = 0f)
}

internal fun isZoomModifierPressed(nativeEvent: Any?): Boolean {
    val event = nativeEvent as? MouseEvent ?: return false
    return event.isControlDown || event.isMetaDown
}

internal fun zoomAfterScroll(
    currentZoom: Float,
    scrollDeltaY: Float,
    minZoom: Float,
    maxZoom: Float,
    zoomFactor: Float = EditorColors.ZOOM_FACTOR,
): Float {
    if (scrollDeltaY == 0f) return currentZoom.coerceIn(minZoom, maxZoom)
    val multiplier = if (scrollDeltaY < 0f) zoomFactor else 1f / zoomFactor
    return (currentZoom * multiplier).coerceIn(minZoom, maxZoom)
}
