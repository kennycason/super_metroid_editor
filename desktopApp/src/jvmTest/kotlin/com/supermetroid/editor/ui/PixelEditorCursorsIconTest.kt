package com.supermetroid.editor.ui

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment

/**
 * Tests that Material Icon-based cursors render without errors
 * and produce valid, distinct cursor objects for each tool.
 */
class PixelEditorCursorsIconTest {

    @BeforeEach
    fun skipIfHeadless() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Cursor tests require a display")
    }

    @Test
    fun `pencil cursor renders from Brush icon without error`() {
        val cursor = PixelEditorCursors.pencil
        assertNotNull(cursor, "Pencil cursor should render successfully")
    }

    @Test
    fun `eraser cursor renders from Clear icon without error`() {
        val cursor = PixelEditorCursors.eraser
        assertNotNull(cursor, "Eraser cursor should render successfully")
    }

    @Test
    fun `fill cursor renders from FormatColorFill icon without error`() {
        val cursor = PixelEditorCursors.fill
        assertNotNull(cursor, "Fill cursor should render successfully")
    }

    @Test
    fun `eyedropper cursor renders from Colorize icon without error`() {
        val cursor = PixelEditorCursors.eyedropper
        assertNotNull(cursor, "Eyedropper cursor should render successfully")
    }

    @Test
    fun `select cursor is valid`() {
        val cursor = PixelEditorCursors.select
        assertNotNull(cursor, "Select cursor should be valid")
    }

    @Test
    fun `all tool cursors are distinct`() {
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
        assert(PixelEditorCursors.mapPaint === PixelEditorCursors.pencil)
        assert(PixelEditorCursors.mapSample === PixelEditorCursors.eyedropper)
        assert(PixelEditorCursors.mapFill === PixelEditorCursors.fill)
        assert(PixelEditorCursors.mapErase === PixelEditorCursors.eraser)
        assert(PixelEditorCursors.mapSelect === PixelEditorCursors.select)
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
        assert(pencil1 === pencil2) { "Pencil cursor should be cached" }
    }

    @Test
    fun `each tool cursor is distinct except mapped aliases`() {
        val cursors = PixelTool.entries.map { PixelEditorCursors.forPixelTool(it) }
        val distinctNonSelect = cursors.filter { it !== PixelEditorCursors.select }.toSet()
        assert(distinctNonSelect.size == 4) {
            "Expected 4 distinct non-select cursors, got ${distinctNonSelect.size}"
        }
    }
}
