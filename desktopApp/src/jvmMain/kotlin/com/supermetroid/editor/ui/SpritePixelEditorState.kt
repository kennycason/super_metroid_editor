package com.supermetroid.editor.ui

import java.util.LinkedList

/**
 * Encapsulates pixel editor state and operations for testability.
 * All coordinate parameters are in pixel space (not screen space).
 */
class SpritePixelEditorState(
    val imageWidth: Int,
    val imageHeight: Int,
    initialPixels: IntArray,
    val palette: List<Int>
) {
    val pixels: IntArray = initialPixels.copyOf()

    var activeTool: PixelTool = PixelTool.PENCIL
    var selectedColorArgb: Int = if (palette.size > 1) palette[1] else 0xFF000000.toInt()

    // Selection (pixel coordinates, inclusive)
    var selActive: Boolean = false
    var selX1: Int = 0
    var selY1: Int = 0
    var selX2: Int = 0
    var selY2: Int = 0

    // Clipboard
    var clipboardPixels: IntArray? = null
        private set
    var clipboardWidth: Int = 0
        private set
    var clipboardHeight: Int = 0
        private set

    // Undo/redo
    val undoStack = mutableListOf<List<SpritePixelEdit>>()
    val redoStack = mutableListOf<List<SpritePixelEdit>>()
    private var pendingEdits = mutableListOf<SpritePixelEdit>()

    var editVersion: Int = 0
        private set

    fun selLeft()   = if (selActive) minOf(selX1, selX2).coerceIn(0, imageWidth - 1)  else 0
    fun selTop()    = if (selActive) minOf(selY1, selY2).coerceIn(0, imageHeight - 1) else 0
    fun selRight()  = if (selActive) maxOf(selX1, selX2).coerceIn(0, imageWidth - 1)  else imageWidth - 1
    fun selBottom() = if (selActive) maxOf(selY1, selY2).coerceIn(0, imageHeight - 1) else imageHeight - 1

    fun commitPending() {
        if (pendingEdits.isNotEmpty()) {
            undoStack.add(pendingEdits.toList())
            redoStack.clear()
            pendingEdits = mutableListOf()
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val batch = undoStack.removeLast()
        for (e in batch.reversed()) pixels[e.y * imageWidth + e.x] = e.oldArgb
        redoStack.add(batch)
        editVersion++
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val batch = redoStack.removeLast()
        for (e in batch) pixels[e.y * imageWidth + e.x] = e.newArgb
        undoStack.add(batch)
        editVersion++
    }

    fun drawPixel(px: Int, py: Int) {
        if (px !in 0 until imageWidth || py !in 0 until imageHeight) return
        val newArgb = if (activeTool == PixelTool.ERASER) 0x00000000 else selectedColorArgb
        val idx = py * imageWidth + px
        val old = pixels[idx]
        if (old == newArgb) return
        pixels[idx] = newArgb
        pendingEdits.add(SpritePixelEdit(px, py, old, newArgb))
        editVersion++
    }

    fun floodFill(startX: Int, startY: Int) {
        if (startX !in 0 until imageWidth || startY !in 0 until imageHeight) return
        val fillArgb = if (activeTool == PixelTool.ERASER) 0x00000000 else selectedColorArgb
        val targetArgb = pixels[startY * imageWidth + startX]
        if (targetArgb == fillArgb) return
        val queue = LinkedList<Pair<Int, Int>>()
        val visited = mutableSetOf<Pair<Int, Int>>()
        queue.add(startX to startY)
        val batch = mutableListOf<SpritePixelEdit>()
        while (queue.isNotEmpty()) {
            val (x, y) = queue.poll()
            if (x !in 0 until imageWidth || y !in 0 until imageHeight) continue
            if (x to y in visited) continue
            visited.add(x to y)
            if (pixels[y * imageWidth + x] != targetArgb) continue
            pixels[y * imageWidth + x] = fillArgb
            batch.add(SpritePixelEdit(x, y, targetArgb, fillArgb))
            queue.add(x - 1 to y); queue.add(x + 1 to y)
            queue.add(x to y - 1); queue.add(x to y + 1)
        }
        if (batch.isNotEmpty()) { undoStack.add(batch); redoStack.clear() }
        editVersion++
    }

    fun eyedrop(px: Int, py: Int) {
        if (px !in 0 until imageWidth || py !in 0 until imageHeight) return
        selectedColorArgb = pixels[py * imageWidth + px].let {
            if ((it ushr 24) == 0) 0x00000000 else it or (0xFF shl 24)
        }
        clipboardPixels = null
        activeTool = PixelTool.PENCIL
    }

    /** Copy the current selection into the clipboard. */
    fun captureSelection() {
        if (!selActive) return
        val sx = selLeft(); val sy = selTop()
        val ex = selRight(); val ey = selBottom()
        val w = ex - sx + 1; val h = ey - sy + 1
        if (w <= 0 || h <= 0) return
        val captured = IntArray(w * h)
        for (row in 0 until h) for (col in 0 until w) {
            captured[row * w + col] = pixels[(sy + row) * imageWidth + (sx + col)]
        }
        clipboardPixels = captured
        clipboardWidth = w
        clipboardHeight = h
    }

    /** Paste clipboard at the given pixel position. Returns true if any pixels changed. */
    fun pasteAt(destX: Int, destY: Int): Boolean {
        val clip = clipboardPixels ?: return false
        val batch = mutableListOf<SpritePixelEdit>()
        for (row in 0 until clipboardHeight) for (col in 0 until clipboardWidth) {
            val imgX = destX + col; val imgY = destY + row
            if (imgX !in 0 until imageWidth || imgY !in 0 until imageHeight) continue
            val newArgb = clip[row * clipboardWidth + col]
            val idx = imgY * imageWidth + imgX
            val old = pixels[idx]
            if (old == newArgb) continue
            pixels[idx] = newArgb
            batch.add(SpritePixelEdit(imgX, imgY, old, newArgb))
        }
        if (batch.isNotEmpty()) { undoStack.add(batch); redoStack.clear() }
        editVersion++
        return batch.isNotEmpty()
    }

    /** Paste clipboard at selection origin or (0,0). */
    fun pasteClipboard(): Boolean {
        val sx = if (selActive) selLeft() else 0
        val sy = if (selActive) selTop() else 0
        return pasteAt(sx, sy)
    }

    /** Delete (clear to transparent) all pixels in the current selection. */
    fun deleteSelection() {
        if (!selActive) return
        val sx = selLeft(); val sy = selTop()
        val ex = selRight(); val ey = selBottom()
        val batch = mutableListOf<SpritePixelEdit>()
        for (row in sy..ey) for (col in sx..ex) {
            val idx = row * imageWidth + col
            val old = pixels[idx]
            if (old == 0x00000000) continue
            pixels[idx] = 0x00000000
            batch.add(SpritePixelEdit(col, row, old, 0x00000000))
        }
        if (batch.isNotEmpty()) { undoStack.add(batch); redoStack.clear() }
        editVersion++
    }

    /** Clear clipboard (exit stamp mode). */
    fun clearClipboard() {
        clipboardPixels = null
        clipboardWidth = 0
        clipboardHeight = 0
    }

    /** Whether the pencil tool is in stamp mode (has a clipboard loaded). */
    val isStampMode: Boolean get() = activeTool == PixelTool.PENCIL && clipboardPixels != null

    fun applyTransform(transform: Transform) {
        val sx = selLeft();  val sy = selTop()
        val ex = selRight(); val ey = selBottom()
        val selW = ex - sx + 1
        val selH = ey - sy + 1

        val src = Array(selH) { row -> IntArray(selW) { col -> pixels[(sy + row) * imageWidth + (sx + col)] } }

        val (dstW, dstH, dst) = when (transform) {
            Transform.FLIP_H -> Triple(selW, selH,
                Array(selH) { row -> IntArray(selW) { col -> src[row][selW - 1 - col] } })
            Transform.FLIP_V -> Triple(selW, selH,
                Array(selH) { row -> IntArray(selW) { col -> src[selH - 1 - row][col] } })
            Transform.ROTATE_CW -> Triple(selH, selW,
                Array(selW) { row -> IntArray(selH) { col -> src[selH - 1 - col][row] } })
            Transform.ROTATE_CCW -> Triple(selH, selW,
                Array(selW) { row -> IntArray(selH) { col -> src[col][selW - 1 - row] } })
        }

        val batch = mutableListOf<SpritePixelEdit>()

        for (row in 0 until selH) for (col in 0 until selW) {
            val imgIdx = (sy + row) * imageWidth + (sx + col)
            val old = pixels[imgIdx]
            pixels[imgIdx] = 0
            batch.add(SpritePixelEdit(sx + col, sy + row, old, 0))
        }
        for (row in 0 until dstH) for (col in 0 until dstW) {
            val imgX = sx + col; val imgY = sy + row
            if (imgX >= imageWidth || imgY >= imageHeight) continue
            val newArgb = dst[row][col]
            if (newArgb == 0) continue
            val imgIdx = imgY * imageWidth + imgX
            val batchIdx = batch.indexOfFirst { e -> e.x == imgX && e.y == imgY && e.newArgb == 0 }
            if (batchIdx >= 0) batch[batchIdx] = batch[batchIdx].copy(newArgb = newArgb)
            else batch.add(SpritePixelEdit(imgX, imgY, pixels[imgIdx], newArgb))
            pixels[imgIdx] = newArgb
        }

        if (batch.isNotEmpty()) { undoStack.add(batch); redoStack.clear() }
        editVersion++
    }
}

data class SpritePixelEdit(val x: Int, val y: Int, val oldArgb: Int, val newArgb: Int)

enum class Transform { FLIP_H, FLIP_V, ROTATE_CW, ROTATE_CCW }
