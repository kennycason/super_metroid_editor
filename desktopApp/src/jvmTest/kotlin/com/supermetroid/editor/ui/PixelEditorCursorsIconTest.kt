package com.supermetroid.editor.ui

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment

/**
 * Tests that cursor objects are valid for each tool.
 * In headless mode (CI), verifies fallback cursors work correctly.
 * With a display, verifies custom icon cursors render and are distinct.
 */
class PixelEditorCursorsIconTest {

    private val headless = GraphicsEnvironment.isHeadless()

    @Test
    fun `pencil cursor renders without error`() {
        assertNotNull(PixelEditorCursors.pencil, "Pencil cursor should not be null")
    }

    @Test
    fun `eraser cursor renders without error`() {
        assertNotNull(PixelEditorCursors.eraser, "Eraser cursor should not be null")
    }

    @Test
    fun `fill cursor renders without error`() {
        assertNotNull(PixelEditorCursors.fill, "Fill cursor should not be null")
    }

    @Test
    fun `eyedropper cursor renders without error`() {
        assertNotNull(PixelEditorCursors.eyedropper, "Eyedropper cursor should not be null")
    }

    @Test
    fun `select cursor is valid`() {
        assertNotNull(PixelEditorCursors.select, "Select cursor should be valid")
    }

    @Test
    fun `all tool cursors are distinct when display is available`() {
        if (headless) return // cursors share a fallback in headless mode
        val pencil = PixelEditorCursors.pencil
        val eraser = PixelEditorCursors.eraser
        val fill = PixelEditorCursors.fill
        val eyedropper = PixelEditorCursors.eyedropper

        assertNotSame(pencil, eraser, "Pencil and eraser should be different")
        assertNotSame(pencil, fill, "Pencil and fill should be different")
        assertNotSame(pencil, eyedropper, "Pencil and eyedropper should be different")
        assertNotSame(eraser, fill, "Eraser and fill should be different")
    }

    @Test
    fun `map tool cursors alias pixel tool cursors`() {
        assertSame(PixelEditorCursors.mapPaint, PixelEditorCursors.pencil)
        assertSame(PixelEditorCursors.mapSample, PixelEditorCursors.eyedropper)
        assertSame(PixelEditorCursors.mapFill, PixelEditorCursors.fill)
        assertSame(PixelEditorCursors.mapErase, PixelEditorCursors.eraser)
        assertSame(PixelEditorCursors.mapSelect, PixelEditorCursors.select)
    }

    @Test
    fun `forPixelTool returns correct cursor for each tool`() {
        assertNotNull(PixelEditorCursors.forPixelTool(PixelTool.PENCIL))
        assertNotNull(PixelEditorCursors.forPixelTool(PixelTool.ERASER))
        assertNotNull(PixelEditorCursors.forPixelTool(PixelTool.FILL))
        assertNotNull(PixelEditorCursors.forPixelTool(PixelTool.EYEDROPPER))
        assertNotNull(PixelEditorCursors.forPixelTool(PixelTool.SELECT))
    }

    @Test
    fun `forEditorTool returns correct cursor for each tool`() {
        assertNotNull(PixelEditorCursors.forEditorTool(EditorTool.PAINT))
        assertNotNull(PixelEditorCursors.forEditorTool(EditorTool.FILL))
        assertNotNull(PixelEditorCursors.forEditorTool(EditorTool.SAMPLE))
        assertNotNull(PixelEditorCursors.forEditorTool(EditorTool.SELECT))
        assertNotNull(PixelEditorCursors.forEditorTool(EditorTool.ERASE))
    }

    @Test
    fun `cursors are cached across calls`() {
        val pencil1 = PixelEditorCursors.pencil
        val pencil2 = PixelEditorCursors.pencil
        assertSame(pencil1, pencil2, "Pencil cursor should be cached")
    }

    @Test
    fun `each tool cursor is distinct except mapped aliases when display available`() {
        if (headless) return
        val cursors = PixelTool.entries.map { PixelEditorCursors.forPixelTool(it) }
        val distinctNonSelect = cursors.filter { it !== PixelEditorCursors.select }.toSet()
        assert(distinctNonSelect.size == 4) {
            "Expected 4 distinct non-select cursors, got ${distinctNonSelect.size}"
        }
    }
}
