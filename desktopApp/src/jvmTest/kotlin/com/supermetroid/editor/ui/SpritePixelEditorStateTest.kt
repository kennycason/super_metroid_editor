package com.supermetroid.editor.ui

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SpritePixelEditorStateTest {

    private val transparent = 0x00000000
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun make4x4(vararg colors: Int): SpritePixelEditorState {
        val pixels = IntArray(16) { if (it < colors.size) colors[it] else transparent }
        return SpritePixelEditorState(4, 4, pixels, listOf(transparent, red, green, blue, white))
    }

    private fun SpritePixelEditorState.px(x: Int, y: Int) = pixels[y * imageWidth + x]

    @Nested
    inner class DrawPixel {
        @Test
        fun `draw pixel sets color and creates pending edit`() {
            val state = make4x4()
            state.selectedColorArgb = red
            state.drawPixel(0, 0)
            assertEquals(red, state.px(0, 0))
        }

        @Test
        fun `draw pixel out of bounds is a no-op`() {
            val state = make4x4()
            state.selectedColorArgb = red
            state.drawPixel(-1, 0)
            state.drawPixel(4, 0)
            state.drawPixel(0, -1)
            state.drawPixel(0, 4)
            // All pixels remain transparent
            for (i in state.pixels.indices) assertEquals(transparent, state.pixels[i])
        }

        @Test
        fun `draw same color is a no-op`() {
            val state = make4x4(red)
            val version = state.editVersion
            state.selectedColorArgb = red
            state.drawPixel(0, 0)
            assertEquals(version, state.editVersion)
        }

        @Test
        fun `eraser clears to transparent`() {
            val state = make4x4(red, green, blue)
            state.activeTool = PixelTool.ERASER
            state.drawPixel(0, 0)
            assertEquals(transparent, state.px(0, 0))
        }
    }

    @Nested
    inner class FloodFill {
        @Test
        fun `flood fill replaces connected same-color region`() {
            // 4x4: top row red, rest transparent
            val pixels = IntArray(16)
            for (x in 0..3) pixels[x] = red
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red, green))
            state.selectedColorArgb = green
            state.floodFill(0, 0)
            for (x in 0..3) assertEquals(green, state.px(x, 0))
            // Second row should remain transparent
            assertEquals(transparent, state.px(0, 1))
        }

        @Test
        fun `flood fill same color is a no-op`() {
            val state = make4x4(red)
            state.selectedColorArgb = red
            state.floodFill(0, 0)
            assertEquals(red, state.px(0, 0))
            assertEquals(0, state.undoStack.size)
        }
    }

    @Nested
    inner class UndoRedo {
        @Test
        fun `undo reverts draw pixel`() {
            val state = make4x4()
            state.selectedColorArgb = red
            state.drawPixel(1, 1)
            state.commitPending()
            assertEquals(red, state.px(1, 1))
            state.undo()
            assertEquals(transparent, state.px(1, 1))
        }

        @Test
        fun `redo reapplies undone change`() {
            val state = make4x4()
            state.selectedColorArgb = red
            state.drawPixel(1, 1)
            state.commitPending()
            state.undo()
            state.redo()
            assertEquals(red, state.px(1, 1))
        }

        @Test
        fun `undo on empty stack is a no-op`() {
            val state = make4x4()
            val version = state.editVersion
            state.undo()
            assertEquals(version, state.editVersion)
        }

        @Test
        fun `redo on empty stack is a no-op`() {
            val state = make4x4()
            val version = state.editVersion
            state.redo()
            assertEquals(version, state.editVersion)
        }

        @Test
        fun `new edit after undo clears redo stack`() {
            val state = make4x4()
            state.selectedColorArgb = red
            state.drawPixel(0, 0)
            state.commitPending()
            state.undo()
            assertEquals(1, state.redoStack.size)
            state.selectedColorArgb = green
            state.drawPixel(0, 0)
            state.commitPending()
            assertEquals(0, state.redoStack.size)
        }
    }

    @Nested
    inner class Selection {
        @Test
        fun `selLeft and selRight normalize coordinates`() {
            val state = make4x4()
            state.selActive = true
            state.selX1 = 3; state.selY1 = 3
            state.selX2 = 1; state.selY2 = 1
            assertEquals(1, state.selLeft())
            assertEquals(3, state.selRight())
            assertEquals(1, state.selTop())
            assertEquals(3, state.selBottom())
        }

        @Test
        fun `selection defaults to full image when not active`() {
            val state = make4x4()
            state.selActive = false
            assertEquals(0, state.selLeft())
            assertEquals(0, state.selTop())
            assertEquals(3, state.selRight())
            assertEquals(3, state.selBottom())
        }

        @Test
        fun `selection coordinates are clamped to image bounds`() {
            val state = make4x4()
            state.selActive = true
            state.selX1 = -5; state.selY1 = -5
            state.selX2 = 99; state.selY2 = 99
            assertEquals(0, state.selLeft())
            assertEquals(0, state.selTop())
            assertEquals(3, state.selRight())
            assertEquals(3, state.selBottom())
        }
    }

    @Nested
    inner class CaptureSelection {
        @Test
        fun `capture copies selected region to clipboard`() {
            // Fill 4x4 with distinct colors at known positions
            val pixels = IntArray(16)
            pixels[0] = red; pixels[1] = green; pixels[4] = blue; pixels[5] = white
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red, green, blue, white))

            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 1
            state.captureSelection()

            assertNotNull(state.clipboardPixels)
            assertEquals(2, state.clipboardWidth)
            assertEquals(2, state.clipboardHeight)
            assertArrayEquals(intArrayOf(red, green, blue, white), state.clipboardPixels)
        }

        @Test
        fun `capture with no selection is a no-op`() {
            val state = make4x4(red)
            state.selActive = false
            state.captureSelection()
            assertNull(state.clipboardPixels)
        }

        @Test
        fun `capture single pixel`() {
            val state = make4x4(red)
            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 0; state.selY2 = 0
            state.captureSelection()
            assertEquals(1, state.clipboardWidth)
            assertEquals(1, state.clipboardHeight)
            assertArrayEquals(intArrayOf(red), state.clipboardPixels)
        }
    }

    @Nested
    inner class PasteAt {
        @Test
        fun `paste places clipboard at target position`() {
            val state = make4x4()
            // Manually set clipboard
            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 0; state.selY2 = 0
            state.selectedColorArgb = red
            state.drawPixel(0, 0)
            state.commitPending()
            state.captureSelection()

            // Paste at (2,2)
            state.pasteAt(2, 2)
            assertEquals(red, state.px(2, 2))
        }

        @Test
        fun `paste 2x2 clipboard at position`() {
            val pixels = IntArray(16)
            pixels[0] = red; pixels[1] = green; pixels[4] = blue; pixels[5] = white
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red, green, blue, white))

            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 1
            state.captureSelection()

            state.pasteAt(2, 2)
            assertEquals(red, state.px(2, 2))
            assertEquals(green, state.px(3, 2))
            assertEquals(blue, state.px(2, 3))
            assertEquals(white, state.px(3, 3))
        }

        @Test
        fun `paste clips to image bounds`() {
            val state = make4x4(red)
            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 1
            state.captureSelection()

            // Paste at (3,3) - only top-left pixel fits
            state.pasteAt(3, 3)
            assertEquals(red, state.px(3, 3))
        }

        @Test
        fun `paste with no clipboard returns false`() {
            val state = make4x4()
            assertFalse(state.pasteAt(0, 0))
        }

        @Test
        fun `paste is undoable`() {
            val state = make4x4()
            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 0; state.selY2 = 0
            state.selectedColorArgb = red
            state.drawPixel(0, 0)
            state.commitPending()
            state.captureSelection()

            state.pasteAt(2, 2)
            assertEquals(red, state.px(2, 2))
            state.undo()
            assertEquals(transparent, state.px(2, 2))
        }
    }

    @Nested
    inner class PasteClipboard {
        @Test
        fun `pasteClipboard uses selection origin when active`() {
            val state = make4x4(red)
            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 0; state.selY2 = 0
            state.captureSelection()

            // Select a different region as paste target
            state.selX1 = 2; state.selY1 = 2; state.selX2 = 2; state.selY2 = 2
            state.pasteClipboard()
            assertEquals(red, state.px(2, 2))
        }

        @Test
        fun `pasteClipboard uses 0,0 when no selection`() {
            val state = make4x4()
            state.selActive = true
            state.selX1 = 1; state.selY1 = 1; state.selX2 = 1; state.selY2 = 1
            state.selectedColorArgb = green
            state.drawPixel(1, 1)
            state.commitPending()
            state.captureSelection()

            state.selActive = false
            state.pasteClipboard()
            assertEquals(green, state.px(0, 0))
        }
    }

    @Nested
    inner class DeleteSelection {
        @Test
        fun `delete clears selected pixels to transparent`() {
            val pixels = IntArray(16) { red }
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red))

            state.selActive = true
            state.selX1 = 1; state.selY1 = 1; state.selX2 = 2; state.selY2 = 2
            state.deleteSelection()

            // Selected 2x2 region should be transparent
            assertEquals(transparent, state.px(1, 1))
            assertEquals(transparent, state.px(2, 1))
            assertEquals(transparent, state.px(1, 2))
            assertEquals(transparent, state.px(2, 2))
            // Surrounding pixels should remain red
            assertEquals(red, state.px(0, 0))
            assertEquals(red, state.px(3, 3))
            assertEquals(red, state.px(0, 1))
            assertEquals(red, state.px(3, 2))
        }

        @Test
        fun `delete is undoable`() {
            val pixels = IntArray(16) { red }
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red))

            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 1
            state.deleteSelection()
            assertEquals(transparent, state.px(0, 0))
            state.undo()
            assertEquals(red, state.px(0, 0))
        }

        @Test
        fun `delete with no selection is a no-op`() {
            val pixels = IntArray(16) { red }
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red))
            state.selActive = false
            val version = state.editVersion
            state.deleteSelection()
            // editVersion should not increment because no selection
            assertEquals(version, state.editVersion)
        }

        @Test
        fun `delete already transparent pixels creates no undo entry`() {
            val state = make4x4() // all transparent
            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 1
            state.deleteSelection()
            assertEquals(0, state.undoStack.size)
        }
    }

    @Nested
    inner class ClearClipboard {
        @Test
        fun `clearClipboard resets clipboard state`() {
            val state = make4x4(red)
            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 0; state.selY2 = 0
            state.captureSelection()
            assertNotNull(state.clipboardPixels)

            state.clearClipboard()
            assertNull(state.clipboardPixels)
            assertEquals(0, state.clipboardWidth)
            assertEquals(0, state.clipboardHeight)
        }
    }

    @Nested
    inner class StampMode {
        @Test
        fun `isStampMode is true when pencil tool has clipboard`() {
            val state = make4x4(red)
            state.activeTool = PixelTool.PENCIL
            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 0; state.selY2 = 0
            state.captureSelection()
            assertTrue(state.isStampMode)
        }

        @Test
        fun `isStampMode is false without clipboard`() {
            val state = make4x4()
            state.activeTool = PixelTool.PENCIL
            assertFalse(state.isStampMode)
        }

        @Test
        fun `isStampMode is false when tool is not pencil`() {
            val state = make4x4(red)
            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 0; state.selY2 = 0
            state.captureSelection()
            state.activeTool = PixelTool.ERASER
            assertFalse(state.isStampMode)
        }

        @Test
        fun `eyedrop clears clipboard`() {
            val state = make4x4(red, green)
            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 0; state.selY2 = 0
            state.captureSelection()
            assertTrue(state.isStampMode)

            state.eyedrop(1, 0)
            assertNull(state.clipboardPixels)
            assertFalse(state.isStampMode)
            assertEquals(PixelTool.PENCIL, state.activeTool)
        }
    }

    @Nested
    inner class Transforms {
        @Test
        fun `flip horizontal reverses pixel columns`() {
            // 2x2 in top-left: [red, green] / [blue, white]
            val pixels = IntArray(16)
            pixels[0] = red; pixels[1] = green; pixels[4] = blue; pixels[5] = white
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red, green, blue, white))

            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 1
            state.applyTransform(Transform.FLIP_H)

            assertEquals(green, state.px(0, 0))
            assertEquals(red, state.px(1, 0))
            assertEquals(white, state.px(0, 1))
            assertEquals(blue, state.px(1, 1))
        }

        @Test
        fun `flip vertical reverses pixel rows`() {
            val pixels = IntArray(16)
            pixels[0] = red; pixels[1] = green; pixels[4] = blue; pixels[5] = white
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red, green, blue, white))

            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 1
            state.applyTransform(Transform.FLIP_V)

            assertEquals(blue, state.px(0, 0))
            assertEquals(white, state.px(1, 0))
            assertEquals(red, state.px(0, 1))
            assertEquals(green, state.px(1, 1))
        }

        @Test
        fun `rotate CW rotates 90 degrees clockwise`() {
            val pixels = IntArray(16)
            pixels[0] = red; pixels[1] = green; pixels[4] = blue; pixels[5] = white
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red, green, blue, white))

            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 1
            state.applyTransform(Transform.ROTATE_CW)

            // CW rotation: top-left gets bottom-left, top-right gets top-left, etc.
            assertEquals(blue, state.px(0, 0))
            assertEquals(red, state.px(1, 0))
            assertEquals(white, state.px(0, 1))
            assertEquals(green, state.px(1, 1))
        }

        @Test
        fun `rotate CCW rotates 90 degrees counter-clockwise`() {
            val pixels = IntArray(16)
            pixels[0] = red; pixels[1] = green; pixels[4] = blue; pixels[5] = white
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red, green, blue, white))

            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 1
            state.applyTransform(Transform.ROTATE_CCW)

            assertEquals(green, state.px(0, 0))
            assertEquals(white, state.px(1, 0))
            assertEquals(red, state.px(0, 1))
            assertEquals(blue, state.px(1, 1))
        }

        @Test
        fun `transform is undoable`() {
            val pixels = IntArray(16)
            pixels[0] = red; pixels[1] = green
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red, green))

            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 0
            state.applyTransform(Transform.FLIP_H)
            assertEquals(green, state.px(0, 0))
            assertEquals(red, state.px(1, 0))

            state.undo()
            assertEquals(red, state.px(0, 0))
            assertEquals(green, state.px(1, 0))
        }
    }

    @Nested
    inner class CutWorkflow {
        @Test
        fun `cut captures selection and then deletes pixels`() {
            val pixels = IntArray(16)
            pixels[0] = red; pixels[1] = green; pixels[4] = blue; pixels[5] = white
            val state = SpritePixelEditorState(4, 4, pixels, listOf(transparent, red, green, blue, white))

            state.selActive = true
            state.selX1 = 0; state.selY1 = 0; state.selX2 = 1; state.selY2 = 1

            // Cut = capture + delete
            state.captureSelection()
            state.deleteSelection()

            // Clipboard should have the original pixels
            assertNotNull(state.clipboardPixels)
            assertArrayEquals(intArrayOf(red, green, blue, white), state.clipboardPixels)

            // Canvas should be cleared
            assertEquals(transparent, state.px(0, 0))
            assertEquals(transparent, state.px(1, 0))
            assertEquals(transparent, state.px(0, 1))
            assertEquals(transparent, state.px(1, 1))

            // Paste at new location
            state.pasteAt(2, 2)
            assertEquals(red, state.px(2, 2))
            assertEquals(green, state.px(3, 2))
            assertEquals(blue, state.px(2, 3))
            assertEquals(white, state.px(3, 3))
        }
    }

    @Nested
    inner class Eyedrop {
        @Test
        fun `eyedrop picks color and switches to pencil`() {
            val state = make4x4(red)
            state.activeTool = PixelTool.SELECT
            state.eyedrop(0, 0)
            assertEquals(red, state.selectedColorArgb)
            assertEquals(PixelTool.PENCIL, state.activeTool)
        }

        @Test
        fun `eyedrop on transparent pixel sets transparent`() {
            val state = make4x4()
            state.selectedColorArgb = red
            state.eyedrop(0, 0)
            assertEquals(transparent, state.selectedColorArgb)
        }

        @Test
        fun `eyedrop out of bounds is a no-op`() {
            val state = make4x4()
            state.selectedColorArgb = red
            state.eyedrop(-1, 0)
            assertEquals(red, state.selectedColorArgb)
        }
    }
}
