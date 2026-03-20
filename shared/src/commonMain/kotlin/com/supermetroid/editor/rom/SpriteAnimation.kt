package com.supermetroid.editor.rom

/**
 * A single frame in a sprite animation.
 *
 * @param pixels     ARGB pixel data for the rendered frame
 * @param width      Frame width in pixels
 * @param height     Frame height in pixels
 * @param durationTicks Frame duration in game ticks (1 tick = 1/60s). 0 = use player default.
 * @param label      Human-readable label (e.g., "Run Right #3")
 */
data class SpriteAnimationFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val durationTicks: Int = 0,
    val label: String = ""
) {
    /** Duration in milliseconds at 60fps */
    val durationMs: Int get() = if (durationTicks > 0) (durationTicks * 1000) / 60 else 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpriteAnimationFrame) return false
        return width == other.width && height == other.height &&
                durationTicks == other.durationTicks && label == other.label &&
                pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = pixels.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + durationTicks
        result = 31 * result + label.hashCode()
        return result
    }
}

/**
 * A complete sprite animation: a named sequence of frames.
 *
 * @param name   Display name (e.g., "Zoomer Walk", "Samus Run Right")
 * @param frames Ordered list of animation frames
 * @param loop   Whether the animation should loop (most enemy animations do)
 */
data class SpriteAnimation(
    val name: String,
    val frames: List<SpriteAnimationFrame>,
    val loop: Boolean = true
) {
    /** Total animation duration in game ticks */
    val totalTicks: Int get() = frames.sumOf { it.durationTicks }

    /** Total animation duration in milliseconds */
    val totalMs: Int get() = if (totalTicks > 0) (totalTicks * 1000) / 60 else 0

    /** True if this animation has meaningful timing data */
    val hasTiming: Boolean get() = frames.any { it.durationTicks > 0 }
}

/**
 * Renders a sprite sheet image from animation frames.
 *
 * Lays out frames in a grid: each row has [columns] frames, each cell is the
 * maximum frame size + optional padding. Transparency is preserved.
 *
 * @param frames   Frames to lay out
 * @param columns  Number of columns per row (default: 8)
 * @param padding  Pixels of padding between cells (default: 1)
 * @return Triple of (ARGB pixels, sheet width, sheet height)
 */
fun renderSpriteSheet(
    frames: List<SpriteAnimationFrame>,
    columns: Int = 8,
    padding: Int = 1
): Triple<IntArray, Int, Int> {
    if (frames.isEmpty()) return Triple(IntArray(0), 0, 0)

    val cellW = frames.maxOf { it.width } + padding * 2
    val cellH = frames.maxOf { it.height } + padding * 2
    val rows = (frames.size + columns - 1) / columns
    val sheetW = cellW * columns
    val sheetH = cellH * rows
    val pixels = IntArray(sheetW * sheetH) // transparent

    for ((idx, frame) in frames.withIndex()) {
        val col = idx % columns
        val row = idx / columns
        val ox = col * cellW + padding + (cellW - padding * 2 - frame.width) / 2
        val oy = row * cellH + padding + (cellH - padding * 2 - frame.height) / 2

        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val srcPx = frame.pixels[y * frame.width + x]
                if (srcPx != 0) {
                    val dx = ox + x
                    val dy = oy + y
                    if (dx in 0 until sheetW && dy in 0 until sheetH) {
                        pixels[dy * sheetW + dx] = srcPx
                    }
                }
            }
        }
    }

    return Triple(pixels, sheetW, sheetH)
}

/**
 * Renders a sprite sheet where each animation is one row, with variable frame counts.
 * Rows are labeled and padded independently.
 *
 * @param animations List of animations (each becomes one row)
 * @param cellWidth  Fixed cell width for all frames
 * @param cellHeight Fixed cell height for all frames
 * @param padding    Pixels between cells
 * @return Triple of (ARGB pixels, sheet width, sheet height)
 */
fun renderMultiAnimationSheet(
    animations: List<SpriteAnimation>,
    cellWidth: Int = 0,
    cellHeight: Int = 0,
    padding: Int = 1
): Triple<IntArray, Int, Int> {
    if (animations.isEmpty()) return Triple(IntArray(0), 0, 0)

    val allFrames = animations.flatMap { it.frames }
    if (allFrames.isEmpty()) return Triple(IntArray(0), 0, 0)

    val cw = if (cellWidth > 0) cellWidth else allFrames.maxOf { it.width } + padding * 2
    val ch = if (cellHeight > 0) cellHeight else allFrames.maxOf { it.height } + padding * 2
    val maxCols = animations.maxOf { it.frames.size }
    val sheetW = cw * maxCols
    val sheetH = ch * animations.size
    val pixels = IntArray(sheetW * sheetH) // transparent

    for ((rowIdx, anim) in animations.withIndex()) {
        for ((colIdx, frame) in anim.frames.withIndex()) {
            val ox = colIdx * cw + padding + (cw - padding * 2 - frame.width) / 2
            val oy = rowIdx * ch + padding + (ch - padding * 2 - frame.height) / 2

            for (y in 0 until frame.height) {
                for (x in 0 until frame.width) {
                    val srcPx = frame.pixels[y * frame.width + x]
                    if (srcPx != 0) {
                        val dx = ox + x
                        val dy = oy + y
                        if (dx in 0 until sheetW && dy in 0 until sheetH) {
                            pixels[dy * sheetW + dx] = srcPx
                        }
                    }
                }
            }
        }
    }

    return Triple(pixels, sheetW, sheetH)
}
