package com.supermetroid.editor.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapCanvasZoomTest {
    @Test
    fun `fit zoom uses the limiting viewport dimension`() {
        val zoom = fitRoomZoomForViewport(
            viewportWidthPx = 1600,
            viewportHeightPx = 900,
            density = 2f,
            contentWidthPx = 1600,
            contentHeightPx = 600,
        )

        assertEquals(0.5f, zoom)
    }

    @Test
    fun `fit zoom clamps to supported range`() {
        assertEquals(
            4f,
            fitRoomZoomForViewport(
                viewportWidthPx = 5000,
                viewportHeightPx = 5000,
                density = 1f,
                contentWidthPx = 64,
                contentHeightPx = 64,
            ),
        )
        assertEquals(
            0.25f,
            fitRoomZoomForViewport(
                viewportWidthPx = 640,
                viewportHeightPx = 360,
                density = 1f,
                contentWidthPx = 8192,
                contentHeightPx = 4096,
            ),
        )
    }

    @Test
    fun `fit zoom is unavailable until viewport and content sizes are known`() {
        assertNull(
            fitRoomZoomForViewport(
                viewportWidthPx = 0,
                viewportHeightPx = 720,
                density = 1f,
                contentWidthPx = 1024,
                contentHeightPx = 512,
            )
        )
    }

    @Test
    fun `room zoom persistence ignores automatic fit until user changes zoom`() {
        assertFalse(shouldSaveRoomZoom(hasSavedZoom = false, zoomLevel = 0.5f, fitZoom = 0.5f))
        assertTrue(shouldSaveRoomZoom(hasSavedZoom = false, zoomLevel = 0.75f, fitZoom = 0.5f))
        assertTrue(shouldSaveRoomZoom(hasSavedZoom = true, zoomLevel = 0.5f, fitZoom = 0.5f))
    }
}
