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

    var floatingSelection: FloatingPixelSelection? = null
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
        if (cancelFloatingSelection()) return
        if (undoStack.isEmpty()) return
        val batch = undoStack.removeLast()
        for (e in batch.reversed()) pixels[e.y * imageWidth + e.x] = e.oldArgb
        redoStack.add(batch)
        editVersion++
    }

    fun redo() {
        if (cancelFloatingSelection()) return
        if (redoStack.isEmpty()) return
        val batch = redoStack.removeLast()
        for (e in batch) pixels[e.y * imageWidth + e.x] = e.newArgb
        undoStack.add(batch)
        editVersion++
    }

    fun drawPixel(px: Int, py: Int) {
        cancelFloatingSelection()
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
        cancelFloatingSelection()
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
        cancelFloatingSelection()
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
        cancelFloatingSelection()
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
        if (floatingSelection != null) {
            cancelFloatingSelection()
            return
        }
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

    val hasTransformTarget: Boolean get() = selActive || floatingSelection != null

    fun applyTransform(transform: Transform): Boolean {
        val currentFloating = floatingSelection
        if (currentFloating != null) {
            val transformed = transformPixels(
                currentFloating.pixels,
                currentFloating.width,
                currentFloating.height,
                transform
            )
            floatingSelection = currentFloating.copy(
                x = clampFloatingX(currentFloating.x, transformed.width),
                y = clampFloatingY(currentFloating.y, transformed.height),
                width = transformed.width,
                height = transformed.height,
                pixels = transformed.pixels
            )
            editVersion++
            return true
        }

        if (!selActive) return false
        val sx = selLeft(); val sy = selTop()
        val selW = selRight() - sx + 1
        val selH = selBottom() - sy + 1
        if (selW <= 0 || selH <= 0) return false

        val src = IntArray(selW * selH)
        for (row in 0 until selH) {
            for (col in 0 until selW) {
                src[row * selW + col] = pixels[(sy + row) * imageWidth + (sx + col)]
            }
        }
        val transformed = transformPixels(src, selW, selH, transform)
        floatingSelection = FloatingPixelSelection(
            x = clampFloatingX(sx, transformed.width),
            y = clampFloatingY(sy, transformed.height),
            width = transformed.width,
            height = transformed.height,
            pixels = transformed.pixels,
            sourceX = sx,
            sourceY = sy,
            sourceWidth = selW,
            sourceHeight = selH
        )
        selActive = false
        activeTool = PixelTool.SELECT
        editVersion++
        return true
    }

    fun moveFloatingSelectionTo(x: Int, y: Int): Boolean {
        val floating = floatingSelection ?: return false
        val nextX = clampFloatingX(x, floating.width)
        val nextY = clampFloatingY(y, floating.height)
        if (nextX == floating.x && nextY == floating.y) return false
        floatingSelection = floating.copy(x = nextX, y = nextY)
        editVersion++
        return true
    }

    fun nudgeFloatingSelection(dx: Int, dy: Int): Boolean {
        val floating = floatingSelection ?: return false
        return moveFloatingSelectionTo(floating.x + dx, floating.y + dy)
    }

    fun commitFloatingSelection(): Boolean {
        val floating = floatingSelection ?: return false
        val finalByCoord = linkedMapOf<Pair<Int, Int>, Pair<Int, Int>>()

        fun setFinal(x: Int, y: Int, newArgb: Int) {
            if (x !in 0 until imageWidth || y !in 0 until imageHeight) return
            val key = x to y
            val oldArgb = finalByCoord[key]?.first ?: pixels[y * imageWidth + x]
            finalByCoord[key] = oldArgb to newArgb
        }

        for (row in 0 until floating.sourceHeight) {
            for (col in 0 until floating.sourceWidth) {
                setFinal(floating.sourceX + col, floating.sourceY + row, 0x00000000)
            }
        }
        for (row in 0 until floating.height) {
            for (col in 0 until floating.width) {
                setFinal(floating.x + col, floating.y + row, floating.pixels[row * floating.width + col])
            }
        }

        val batch = mutableListOf<SpritePixelEdit>()
        for ((coord, values) in finalByCoord) {
            val (x, y) = coord
            val (oldArgb, newArgb) = values
            if (oldArgb == newArgb) continue
            pixels[y * imageWidth + x] = newArgb
            batch.add(SpritePixelEdit(x, y, oldArgb, newArgb))
        }

        floatingSelection = null
        selActive = true
        selX1 = floating.x
        selY1 = floating.y
        selX2 = (floating.x + floating.width - 1).coerceIn(0, imageWidth - 1)
        selY2 = (floating.y + floating.height - 1).coerceIn(0, imageHeight - 1)
        if (batch.isNotEmpty()) {
            undoStack.add(batch)
            redoStack.clear()
        }
        editVersion++
        return batch.isNotEmpty()
    }

    fun cancelFloatingSelection(): Boolean {
        val floating = floatingSelection ?: return false
        floatingSelection = null
        selActive = true
        selX1 = floating.sourceX
        selY1 = floating.sourceY
        selX2 = floating.sourceX + floating.sourceWidth - 1
        selY2 = floating.sourceY + floating.sourceHeight - 1
        editVersion++
        return true
    }

    fun isInsideFloatingSelection(px: Int, py: Int): Boolean {
        val floating = floatingSelection ?: return false
        return px in floating.x until floating.x + floating.width &&
            py in floating.y until floating.y + floating.height
    }

    private fun clampFloatingX(x: Int, width: Int): Int {
        val maxX = (imageWidth - width).coerceAtLeast(0)
        return x.coerceIn(0, maxX)
    }

    private fun clampFloatingY(y: Int, height: Int): Int {
        val maxY = (imageHeight - height).coerceAtLeast(0)
        return y.coerceIn(0, maxY)
    }

    private fun transformPixels(src: IntArray, width: Int, height: Int, transform: Transform): TransformedPixels {
        val dstW: Int
        val dstH: Int
        val dst: IntArray
        when (transform) {
            Transform.FLIP_H -> {
                dstW = width
                dstH = height
                dst = IntArray(dstW * dstH)
                for (row in 0 until height) {
                    for (col in 0 until width) {
                        dst[row * dstW + col] = src[row * width + (width - 1 - col)]
                    }
                }
            }
            Transform.FLIP_V -> {
                dstW = width
                dstH = height
                dst = IntArray(dstW * dstH)
                for (row in 0 until height) {
                    for (col in 0 until width) {
                        dst[row * dstW + col] = src[(height - 1 - row) * width + col]
                    }
                }
            }
            Transform.ROTATE_CW -> {
                dstW = height
                dstH = width
                dst = IntArray(dstW * dstH)
                for (row in 0 until dstH) {
                    for (col in 0 until dstW) {
                        dst[row * dstW + col] = src[(height - 1 - col) * width + row]
                    }
                }
            }
            Transform.ROTATE_CCW -> {
                dstW = height
                dstH = width
                dst = IntArray(dstW * dstH)
                for (row in 0 until dstH) {
                    for (col in 0 until dstW) {
                        dst[row * dstW + col] = src[col * width + (width - 1 - row)]
                    }
                }
            }
        }
        return TransformedPixels(dstW, dstH, dst)
    }
}

data class SpritePixelEdit(val x: Int, val y: Int, val oldArgb: Int, val newArgb: Int)

data class FloatingPixelSelection(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FloatingPixelSelection) return false
        return x == other.x &&
            y == other.y &&
            width == other.width &&
            height == other.height &&
            pixels.contentEquals(other.pixels) &&
            sourceX == other.sourceX &&
            sourceY == other.sourceY &&
            sourceWidth == other.sourceWidth &&
            sourceHeight == other.sourceHeight
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        result = 31 * result + sourceX
        result = 31 * result + sourceY
        result = 31 * result + sourceWidth
        result = 31 * result + sourceHeight
        return result
    }
}

private data class TransformedPixels(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)

enum class Transform { FLIP_H, FLIP_V, ROTATE_CW, ROTATE_CCW }
