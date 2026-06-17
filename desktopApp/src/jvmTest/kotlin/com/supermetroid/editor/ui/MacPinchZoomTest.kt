package com.supermetroid.editor.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MacPinchZoomTest {

    @Test
    fun `positive magnification zooms in`() {
        assertEquals(
            1.2f,
            zoomAfterMagnification(currentZoom = 1f, magnification = 0.2, minZoom = 0.25f, maxZoom = 4f),
            0.0001f
        )
    }

    @Test
    fun `negative magnification zooms out`() {
        assertEquals(
            0.8f,
            zoomAfterMagnification(currentZoom = 1f, magnification = -0.2, minZoom = 0.25f, maxZoom = 4f),
            0.0001f
        )
    }

    @Test
    fun `magnification clamps to zoom bounds`() {
        assertEquals(
            4f,
            zoomAfterMagnification(currentZoom = 3.8f, magnification = 0.5, minZoom = 0.25f, maxZoom = 4f),
            0.0001f
        )
        assertEquals(
            0.25f,
            zoomAfterMagnification(currentZoom = 0.3f, magnification = -0.5, minZoom = 0.25f, maxZoom = 4f),
            0.0001f
        )
    }

    @Test
    fun `invalid magnification leaves clamped zoom unchanged`() {
        assertEquals(
            1f,
            zoomAfterMagnification(currentZoom = 1f, magnification = -1.5, minZoom = 0.25f, maxZoom = 4f),
            0.0001f
        )
    }
}
