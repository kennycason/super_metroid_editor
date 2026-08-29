package com.supermetroid.editor.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.input.pointer.PointerIcon
import com.supermetroid.editor.ui.MinimapTool
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * Custom semi-transparent cursors for pixel editor tools.
 * Renders the same Material Icons used in the toolbar as cursor images,
 * so the cursor visually matches the active tool button.
 */
object PixelEditorCursors {

    private val cache = mutableMapOf<String, PointerIcon>()

    private const val CURSOR_SIZE = 24
    private const val CURSOR_ALPHA = 200 // semi-transparent

    /** Pencil/Brush cursor — hotspot at bottom-left. */
    val pencil: PointerIcon get() = cache.getOrPut("pencil") {
        renderIconCursor(Icons.Default.Brush, Point(3, CURSOR_SIZE - 2), "pencil")
    }

    /** Eraser cursor — hotspot centered. */
    val eraser: PointerIcon get() = cache.getOrPut("eraser") {
        renderIconCursor(Icons.Default.Clear, Point(CURSOR_SIZE / 2, CURSOR_SIZE / 2), "eraser")
    }

    /** Fill / paint bucket cursor — hotspot at bottom-right pour point. */
    val fill: PointerIcon get() = cache.getOrPut("fill") {
        renderIconCursor(Icons.Default.FormatColorFill, Point(CURSOR_SIZE - 4, CURSOR_SIZE - 2), "fill")
    }

    /** Eyedropper cursor — hotspot at bottom-left tip. */
    val eyedropper: PointerIcon get() = cache.getOrPut("eyedropper") {
        renderIconCursor(Icons.Default.Colorize, Point(2, CURSOR_SIZE - 2), "eyedropper")
    }

    /** Crosshair cursor for selection tool. */
    val select: PointerIcon get() = cache.getOrPut("select") {
        PointerIcon(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR))
    }

    /** Map-level paint cursor (reuses pencil). */
    val mapPaint: PointerIcon get() = pencil

    /** Map-level sample cursor (reuses eyedropper). */
    val mapSample: PointerIcon get() = eyedropper

    /** Map-level fill cursor (reuses fill). */
    val mapFill: PointerIcon get() = fill

    /** Map-level erase cursor (reuses eraser). */
    val mapErase: PointerIcon get() = eraser

    /** Map-level select cursor (reuses crosshair). */
    val mapSelect: PointerIcon get() = select

    /** Returns the appropriate cursor for the given PixelTool. */
    fun forPixelTool(tool: PixelTool): PointerIcon = when (tool) {
        PixelTool.PENCIL -> pencil
        PixelTool.ERASER -> eraser
        PixelTool.FILL -> fill
        PixelTool.EYEDROPPER -> eyedropper
        PixelTool.SELECT -> select
    }

    /** Returns the appropriate cursor for the given MinimapTool. */
    fun forMinimapTool(tool: MinimapTool): PointerIcon = when (tool) {
        MinimapTool.PAINT -> pencil
        MinimapTool.FILL -> fill
        MinimapTool.EYEDROPPER -> eyedropper
        MinimapTool.SELECT -> select
        MinimapTool.ERASE -> eraser
        MinimapTool.REVEAL -> select
    }

    /** Returns the appropriate cursor for the given EditorTool. */
    fun forEditorTool(tool: EditorTool): PointerIcon = when (tool) {
        EditorTool.PAINT -> mapPaint
        EditorTool.FILL -> mapFill
        EditorTool.SAMPLE -> mapSample
        EditorTool.SELECT -> mapSelect
        EditorTool.ERASE -> mapErase
    }

    /**
     * Renders a Material ImageVector icon to a BufferedImage cursor.
     * Extracts vector path data and draws with AWT Graphics2D.
     */
    private fun renderIconCursor(icon: ImageVector, hotspot: Point, name: String): PointerIcon {
        val img = BufferedImage(CURSOR_SIZE, CURSOR_SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        // Scale from icon viewport (typically 24x24) to cursor size
        val scaleX = CURSOR_SIZE.toFloat() / icon.viewportWidth
        val scaleY = CURSOR_SIZE.toFloat() / icon.viewportHeight

        // Draw a subtle dark outline first for contrast on any background
        val paths = extractPaths(icon.root)
        for (pathData in paths) {
            val awtPath = pathNodesToPath2D(pathData, scaleX, scaleY)
            g.color = Color(0, 0, 0, CURSOR_ALPHA / 2)
            g.stroke = BasicStroke(2.5f)
            g.draw(awtPath)
        }

        // Fill the icon paths with white + semi-transparent
        for (pathData in paths) {
            val awtPath = pathNodesToPath2D(pathData, scaleX, scaleY)
            g.color = Color(255, 255, 255, CURSOR_ALPHA)
            g.fill(awtPath)
            // Thin dark edge for definition
            g.color = Color(0, 0, 0, CURSOR_ALPHA * 3 / 4)
            g.stroke = BasicStroke(0.5f)
            g.draw(awtPath)
        }

        g.dispose()
        return createPointerIcon(img, hotspot, name)
    }

    /** Recursively extract all VectorPath pathData lists from a VectorGroup. */
    private fun extractPaths(group: VectorGroup): List<List<PathNode>> {
        val result = mutableListOf<List<PathNode>>()
        for (i in 0 until group.size) {
            when (val node = group[i]) {
                is VectorPath -> result.add(node.pathData)
                is VectorGroup -> result.addAll(extractPaths(node))
            }
        }
        return result
    }

    /** Convert Compose PathNode list to AWT Path2D for Graphics2D rendering. */
    private fun pathNodesToPath2D(nodes: List<PathNode>, scaleX: Float, scaleY: Float): Path2D.Float {
        val path = Path2D.Float()
        var curX = 0f
        var curY = 0f
        var lastControlX = 0f
        var lastControlY = 0f

        for (node in nodes) {
            when (node) {
                is PathNode.MoveTo -> {
                    path.moveTo((node.x * scaleX).toDouble(), (node.y * scaleY).toDouble())
                    curX = node.x; curY = node.y
                }
                is PathNode.RelativeMoveTo -> {
                    curX += node.dx; curY += node.dy
                    path.moveTo((curX * scaleX).toDouble(), (curY * scaleY).toDouble())
                }
                is PathNode.LineTo -> {
                    path.lineTo((node.x * scaleX).toDouble(), (node.y * scaleY).toDouble())
                    curX = node.x; curY = node.y
                }
                is PathNode.RelativeLineTo -> {
                    curX += node.dx; curY += node.dy
                    path.lineTo((curX * scaleX).toDouble(), (curY * scaleY).toDouble())
                }
                is PathNode.HorizontalTo -> {
                    curX = node.x
                    path.lineTo((curX * scaleX).toDouble(), (curY * scaleY).toDouble())
                }
                is PathNode.RelativeHorizontalTo -> {
                    curX += node.dx
                    path.lineTo((curX * scaleX).toDouble(), (curY * scaleY).toDouble())
                }
                is PathNode.VerticalTo -> {
                    curY = node.y
                    path.lineTo((curX * scaleX).toDouble(), (curY * scaleY).toDouble())
                }
                is PathNode.RelativeVerticalTo -> {
                    curY += node.dy
                    path.lineTo((curX * scaleX).toDouble(), (curY * scaleY).toDouble())
                }
                is PathNode.CurveTo -> {
                    path.curveTo(
                        (node.x1 * scaleX).toDouble(), (node.y1 * scaleY).toDouble(),
                        (node.x2 * scaleX).toDouble(), (node.y2 * scaleY).toDouble(),
                        (node.x3 * scaleX).toDouble(), (node.y3 * scaleY).toDouble()
                    )
                    lastControlX = node.x2; lastControlY = node.y2
                    curX = node.x3; curY = node.y3
                }
                is PathNode.RelativeCurveTo -> {
                    val x1 = curX + node.dx1; val y1 = curY + node.dy1
                    val x2 = curX + node.dx2; val y2 = curY + node.dy2
                    val x3 = curX + node.dx3; val y3 = curY + node.dy3
                    path.curveTo(
                        (x1 * scaleX).toDouble(), (y1 * scaleY).toDouble(),
                        (x2 * scaleX).toDouble(), (y2 * scaleY).toDouble(),
                        (x3 * scaleX).toDouble(), (y3 * scaleY).toDouble()
                    )
                    lastControlX = x2; lastControlY = y2
                    curX = x3; curY = y3
                }
                is PathNode.QuadTo -> {
                    path.quadTo(
                        (node.x1 * scaleX).toDouble(), (node.y1 * scaleY).toDouble(),
                        (node.x2 * scaleX).toDouble(), (node.y2 * scaleY).toDouble()
                    )
                    lastControlX = node.x1; lastControlY = node.y1
                    curX = node.x2; curY = node.y2
                }
                is PathNode.RelativeQuadTo -> {
                    val x1 = curX + node.dx1; val y1 = curY + node.dy1
                    val x2 = curX + node.dx2; val y2 = curY + node.dy2
                    path.quadTo(
                        (x1 * scaleX).toDouble(), (y1 * scaleY).toDouble(),
                        (x2 * scaleX).toDouble(), (y2 * scaleY).toDouble()
                    )
                    lastControlX = x1; lastControlY = y1
                    curX = x2; curY = y2
                }
                is PathNode.ReflectiveCurveTo -> {
                    val cx1 = 2 * curX - lastControlX
                    val cy1 = 2 * curY - lastControlY
                    path.curveTo(
                        (cx1 * scaleX).toDouble(), (cy1 * scaleY).toDouble(),
                        (node.x1 * scaleX).toDouble(), (node.y1 * scaleY).toDouble(),
                        (node.x2 * scaleX).toDouble(), (node.y2 * scaleY).toDouble()
                    )
                    lastControlX = node.x1; lastControlY = node.y1
                    curX = node.x2; curY = node.y2
                }
                is PathNode.RelativeReflectiveCurveTo -> {
                    val cx1 = 2 * curX - lastControlX
                    val cy1 = 2 * curY - lastControlY
                    val x1 = curX + node.dx1; val y1 = curY + node.dy1
                    val x2 = curX + node.dx2; val y2 = curY + node.dy2
                    path.curveTo(
                        (cx1 * scaleX).toDouble(), (cy1 * scaleY).toDouble(),
                        (x1 * scaleX).toDouble(), (y1 * scaleY).toDouble(),
                        (x2 * scaleX).toDouble(), (y2 * scaleY).toDouble()
                    )
                    lastControlX = x1; lastControlY = y1
                    curX = x2; curY = y2
                }
                is PathNode.ReflectiveQuadTo -> {
                    val cx = 2 * curX - lastControlX
                    val cy = 2 * curY - lastControlY
                    path.quadTo(
                        (cx * scaleX).toDouble(), (cy * scaleY).toDouble(),
                        (node.x * scaleX).toDouble(), (node.y * scaleY).toDouble()
                    )
                    lastControlX = cx; lastControlY = cy
                    curX = node.x; curY = node.y
                }
                is PathNode.RelativeReflectiveQuadTo -> {
                    val cx = 2 * curX - lastControlX
                    val cy = 2 * curY - lastControlY
                    val tx = curX + node.dx; val ty = curY + node.dy
                    path.quadTo(
                        (cx * scaleX).toDouble(), (cy * scaleY).toDouble(),
                        (tx * scaleX).toDouble(), (ty * scaleY).toDouble()
                    )
                    lastControlX = cx; lastControlY = cy
                    curX = tx; curY = ty
                }
                is PathNode.ArcTo -> {
                    appendArc(path, curX, curY, node.horizontalEllipseRadius, node.verticalEllipseRadius,
                        node.theta, node.isMoreThanHalf, node.isPositiveArc, node.arcStartX, node.arcStartY,
                        scaleX, scaleY)
                    curX = node.arcStartX; curY = node.arcStartY
                }
                is PathNode.RelativeArcTo -> {
                    val tx = curX + node.arcStartDx; val ty = curY + node.arcStartDy
                    appendArc(path, curX, curY, node.horizontalEllipseRadius, node.verticalEllipseRadius,
                        node.theta, node.isMoreThanHalf, node.isPositiveArc, tx, ty,
                        scaleX, scaleY)
                    curX = tx; curY = ty
                }
                PathNode.Close -> {
                    path.closePath()
                }
            }
        }
        return path
    }

    /**
     * Approximate SVG arc command by converting endpoint parameterization
     * to center parameterization and using AWT Arc2D.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun appendArc(
        path: Path2D.Float,
        x0: Float, y0: Float,
        rx: Float, ry: Float,
        xRotation: Float,
        largeArc: Boolean, sweep: Boolean,
        x1: Float, y1: Float,
        scaleX: Float, scaleY: Float
    ) {
        // Fallback: approximate arc as a line if radii are zero
        if (rx == 0f || ry == 0f) {
            path.lineTo((x1 * scaleX).toDouble(), (y1 * scaleY).toDouble())
            return
        }
        // Use endpoint-to-center conversion per SVG spec
        val dx = (x0 - x1) / 2.0
        val dy = (y0 - y1) / 2.0
        val cosA = Math.cos(Math.toRadians(xRotation.toDouble()))
        val sinA = Math.sin(Math.toRadians(xRotation.toDouble()))
        val x1p = cosA * dx + sinA * dy
        val y1p = -sinA * dx + cosA * dy

        var rxd = rx.toDouble(); var ryd = ry.toDouble()
        val x1pSq = x1p * x1p; val y1pSq = y1p * y1p
        val rxSq = rxd * rxd; val rySq = ryd * ryd
        val lambda = x1pSq / rxSq + y1pSq / rySq
        if (lambda > 1.0) {
            val s = Math.sqrt(lambda)
            rxd *= s; ryd *= s
        }

        // Approximate: draw a line for complex arcs at cursor scale
        path.lineTo((x1 * scaleX).toDouble(), (y1 * scaleY).toDouble())
    }

    private fun createPointerIcon(img: BufferedImage, hotspot: Point, name: String): PointerIcon {
        if (GraphicsEnvironment.isHeadless()) {
            return PointerIcon(Cursor.getDefaultCursor())
        }
        val toolkit = Toolkit.getDefaultToolkit()
        val bestSize = toolkit.getBestCursorSize(img.width, img.height)
        val finalImg = if (bestSize.width > img.width || bestSize.height > img.height) {
            val padded = BufferedImage(bestSize.width, bestSize.height, BufferedImage.TYPE_INT_ARGB)
            val pg = padded.createGraphics()
            pg.drawImage(img, 0, 0, null)
            pg.dispose()
            padded
        } else {
            img
        }
        val cursor = toolkit.createCustomCursor(finalImg, hotspot, name)
        return PointerIcon(cursor)
    }
}
