package com.supermetroid.editor.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull

class PixelEditorCursorsTest {

    @Test
    fun `all PixelTool values produce a non-null cursor`() {
        for (tool in PixelTool.entries) {
            assertNotNull(PixelEditorCursors.forPixelTool(tool), "Cursor for $tool should not be null")
        }
    }

    @Test
    fun `all EditorTool values produce a non-null cursor`() {
        for (tool in EditorTool.entries) {
            assertNotNull(PixelEditorCursors.forEditorTool(tool), "Cursor for $tool should not be null")
        }
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
        // Pencil, eraser, fill, eyedropper should all be different objects
        val distinctNonSelect = cursors.filter { it !== PixelEditorCursors.select }.toSet()
        assert(distinctNonSelect.size == 4) {
            "Expected 4 distinct non-select cursors, got ${distinctNonSelect.size}"
        }
    }

    @Test
    fun `map tool cursors reuse pixel tool cursors`() {
        assert(PixelEditorCursors.mapPaint === PixelEditorCursors.pencil)
        assert(PixelEditorCursors.mapSample === PixelEditorCursors.eyedropper)
        assert(PixelEditorCursors.mapFill === PixelEditorCursors.fill)
        assert(PixelEditorCursors.mapErase === PixelEditorCursors.eraser)
        assert(PixelEditorCursors.mapSelect === PixelEditorCursors.select)
    }
}
