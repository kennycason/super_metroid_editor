package com.supermetroid.editor.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShiftSelectionTest {

    private fun newState(): EditorState {
        val state = EditorState()
        state.testMode = true
        state.initTestLevel(blocksWide = 6, blocksTall = 6)
        return state
    }

    private fun EditorState.writeWord(bx: Int, by: Int, word: Int) {
        val data = workingLevelData!!
        val idx = by * workingBlocksWide + bx
        val offset = 2 + idx * 2
        data[offset] = (word and 0xFF).toByte()
        data[offset + 1] = ((word shr 8) and 0xFF).toByte()
    }

    private fun EditorState.writeBtsForTest(bx: Int, by: Int, bts: Int) {
        val data = workingLevelData!!
        val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val idx = by * workingBlocksWide + bx
        data[2 + layer1Size + idx] = bts.toByte()
    }

    @Test
    fun `shift selection creates floating preview without modifying tiles`() {
        val state = newState()
        val sourceWord = 0x8005
        val destWord = 0x8009
        state.writeWord(1, 1, sourceWord)
        state.writeBtsForTest(1, 1, 0x12)
        state.writeWord(2, 1, destWord)

        state.mapSelStart = 1 to 1
        state.mapSelEnd = 1 to 1
        state.shiftSelection(1, 0)

        assertEquals(sourceWord, state.readBlockWord(1, 1), "Source remains unchanged while preview floats")
        assertEquals(0x12, state.readBts(1, 1))
        assertEquals(destWord, state.readBlockWord(2, 1), "Destination is not overwritten before commit")
        assertEquals(FloatingMapSelection(2, 1), state.floatingSelection)
        assertNull(state.mapSelStart)
        assertNotNull(state.brush)
    }

    @Test
    fun `commit floating selection writes destination as one undoable placement`() {
        val state = newState()
        val sourceWord = 0x8005
        val destWord = 0x8009
        state.writeWord(1, 1, sourceWord)
        state.writeBtsForTest(1, 1, 0x12)
        state.writeWord(2, 1, destWord)

        state.mapSelStart = 1 to 1
        state.mapSelEnd = 1 to 1
        state.shiftSelection(1, 0)
        val committed = state.commitFloatingSelection()

        assertTrue(committed)
        assertEquals(sourceWord, state.readBlockWord(1, 1), "Selection placement copies; it does not clear source")
        assertEquals(sourceWord, state.readBlockWord(2, 1), "Destination is overwritten only after commit")
        assertEquals(0x12, state.readBts(2, 1))
        assertNull(state.floatingSelection)

        state.undo()
        assertEquals(destWord, state.readBlockWord(2, 1), "Undo restores overwritten destination")
        assertEquals(sourceWord, state.readBlockWord(1, 1), "Undo leaves original source intact")
    }

    @Test
    fun `floating selection clamps to room bounds`() {
        val state = newState()
        state.writeWord(4, 4, 0x8010)
        state.writeWord(5, 5, 0x8011)
        state.mapSelStart = 4 to 4
        state.mapSelEnd = 5 to 5

        state.shiftSelection(10, 10)

        assertEquals(FloatingMapSelection(4, 4), state.floatingSelection)
    }

    @Test
    fun `flip and rotate selection transform floating preview without writing`() {
        val state = newState()
        state.writeWord(1, 1, 0x8001)
        state.writeWord(2, 1, 0x8002)
        state.writeWord(1, 2, 0x8003)
        state.writeWord(2, 2, 0x8004)
        state.activeTool = EditorTool.SELECT
        state.mapSelStart = 1 to 1
        state.mapSelEnd = 2 to 2

        state.flipOrCaptureH()
        state.rotateOrCapture()

        assertEquals(FloatingMapSelection(1, 1), state.floatingSelection)
        assertNull(state.mapSelStart)
        assertFalse(state.undoStack.isNotEmpty(), "Transforms should not create undo entries before placement")
        assertEquals(2, state.brush!!.rows)
        assertEquals(2, state.brush!!.cols)
        assertTrue(state.brush!!.hFlip)
    }
}
