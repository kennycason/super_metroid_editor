package com.supermetroid.editor.ui

import androidx.compose.ui.unit.IntSize
import com.supermetroid.editor.data.AppSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FloatingEmulatorWindowTest {

    @Test
    fun `offscreen geometry is moved completely inside the editor`() {
        val fitted = fitEmulatorWindowGeometry(
            xPx = 1_500f,
            yPx = 1_000f,
            widthDp = 512f,
            containerSizePx = IntSize(1_200, 800),
            density = 1f,
        )

        assertEquals(688f, fitted.xPx, 0.001f)
        assertEquals(356f, fitted.yPx, 0.001f)
        assertEquals(512f, fitted.widthDp, 0.001f)
    }

    @Test
    fun `geometry fitting respects display density and available height`() {
        val fitted = fitEmulatorWindowGeometry(
            xPx = 500f,
            yPx = 500f,
            widthDp = 512f,
            containerSizePx = IntSize(1_000, 800),
            density = 2f,
        )

        assertEquals(453.333f, fitted.widthDp, 0.01f)
        assertEquals(93.333f, fitted.xPx, 0.02f)
        assertEquals(0f, fitted.yPx, 0.001f)
    }

    @Test
    fun `very small editor keeps the whole emulator reachable`() {
        val fitted = fitEmulatorWindowGeometry(
            xPx = -200f,
            yPx = 900f,
            widthDp = 900f,
            containerSizePx = IntSize(320, 220),
            density = 1f,
        )

        val totalHeight = 24f + fitted.widthDp / (4f / 3f) + 36f
        assertEquals(0f, fitted.xPx, 0.001f)
        assertEquals(0f, fitted.yPx, 0.001f)
        assertTrue(fitted.widthDp <= 320f)
        assertTrue(totalHeight <= 220.001f)
    }

    @Test
    fun `auto play is enabled by default`() {
        assertTrue(AppSettings().emulatorAutoPlayOnOpen)
    }
}
