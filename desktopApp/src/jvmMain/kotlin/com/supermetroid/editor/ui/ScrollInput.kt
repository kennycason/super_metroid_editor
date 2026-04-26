package com.supermetroid.editor.ui

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
