package com.supermetroid.editor.ui

import com.supermetroid.editor.data.CustomItemDef
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RoomRenderData
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

internal object EnemySpriteCache {
    private val cache = mutableMapOf<String, BufferedImage?>()

    fun get(hexId: String): BufferedImage? {
        return cache.getOrPut(hexId) {
            val stream = EnemySpriteCache::class.java.getResourceAsStream("/enemies/$hexId.png")
            stream?.use { ImageIO.read(it) }
        }
    }
}

internal object ItemSpriteSheetCache {
    private var sheet: BufferedImage? = null
    private var loaded = false

    fun get(): BufferedImage? {
        if (!loaded) {
            loaded = true
            sheet = ItemSpriteSheetCache::class.java.getResourceAsStream("/item_sprites.png")
                ?.use { ImageIO.read(it) }
        }
        return sheet
    }
}

internal data class RoomItemSpriteCoord(val x: Int, val y: Int)

internal data class ItemOverlayDef(
    val name: String,
    val shortLabel: String,
    val sprite: RoomItemSpriteCoord?,
)

internal val ROOM_ITEM_SPRITE_COORDS = mapOf(
    "Morph Ball" to RoomItemSpriteCoord(0, 0),
    "Bomb" to RoomItemSpriteCoord(32, 0),
    "Energy Tank" to RoomItemSpriteCoord(64, 0),
    "Missile" to RoomItemSpriteCoord(0, 16),
    "Super Missile" to RoomItemSpriteCoord(32, 16),
    "Power Bomb" to RoomItemSpriteCoord(64, 16),
    "Reserve Tank" to RoomItemSpriteCoord(96, 16),
    "Hi-Jump Boots" to RoomItemSpriteCoord(0, 32),
    "Speed Booster" to RoomItemSpriteCoord(32, 32),
    "Grapple Beam" to RoomItemSpriteCoord(64, 32),
    "X-Ray Scope" to RoomItemSpriteCoord(96, 32),
    "Spring Ball" to RoomItemSpriteCoord(0, 48),
    "Space Jump" to RoomItemSpriteCoord(32, 48),
    "Screw Attack" to RoomItemSpriteCoord(64, 48),
    "Charge Beam" to RoomItemSpriteCoord(96, 48),
    "Spazer" to RoomItemSpriteCoord(0, 64),
    "Wave Beam" to RoomItemSpriteCoord(32, 64),
    "Ice Beam" to RoomItemSpriteCoord(64, 64),
    "Plasma Beam" to RoomItemSpriteCoord(96, 64),
    "Varia Suit" to RoomItemSpriteCoord(0, 80),
    "Gravity Suit" to RoomItemSpriteCoord(32, 80),
)

internal fun buildItemOverlayDefs(customItems: List<CustomItemDef>): Map<Int, ItemOverlayDef> = buildMap {
    for (item in RomParser.ITEM_DEFS) {
        val def = ItemOverlayDef(
            name = item.name,
            shortLabel = item.shortLabel,
            sprite = ROOM_ITEM_SPRITE_COORDS[item.name],
        )
        put(item.chozoId, def)
        put(item.visibleId, def)
        put(item.hiddenId, def)
    }
    for (item in customItems) {
        val def = ItemOverlayDef(
            name = item.name,
            shortLabel = item.shortLabel,
            sprite = RoomItemSpriteCoord(item.iconX, item.iconY),
        )
        item.chozoPlmId?.let { put(it, def) }
        item.visiblePlmId?.let { put(it, def) }
        item.hiddenPlmId?.let { put(it, def) }
    }
}

internal const val SCREEN_PX = 16 * 16  // 256 — one screen in pixels

/** Enemy IDs whose sprites should be horizontally flipped when initParam != 0. */
internal val ENEMY_IDS_FLIP_BY_INIT_PARAM = setOf(
    0xE6FF, // Fune
    0xE73F, // Namihe
    0xD47F, // Ripper
    0xD2FF, // Geruta
    0xD33F, 0xE67F, // Holtz
    0xD63F, 0xD89F, // Waver
    0xDD3F, // Sova
)

internal fun drawSpeedBoosterOverlay(g2: java.awt.Graphics2D, x: Int, y: Int, size: Int, color: java.awt.Color) {
    // Background
    g2.color = java.awt.Color(0, 0, 0, 200)
    g2.fillRect(x, y, size, size)

    // Arrow: right-pointing chevron like the speed booster icon
    val m = size / 8f  // unit
    val cx = x + size / 2f
    val cy = y + size / 2f

    // Double chevron arrow (two >>)
    val arrowXs1 = floatArrayOf(cx - 2 * m, cx, cx - 2 * m)
    val arrowYs1 = floatArrayOf(cy - 3 * m, cy, cy + 3 * m)
    val arrowXs2 = floatArrayOf(cx + 0.5f * m, cx + 2.5f * m, cx + 0.5f * m)
    val arrowYs2 = floatArrayOf(cy - 3 * m, cy, cy + 3 * m)

    g2.stroke = java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
    g2.color = color
    g2.drawPolyline(arrowXs1.map { it.toInt() }.toIntArray(), arrowYs1.map { it.toInt() }.toIntArray(), 3)
    g2.drawPolyline(arrowXs2.map { it.toInt() }.toIntArray(), arrowYs2.map { it.toInt() }.toIntArray(), 3)

    // Colored border
    g2.stroke = java.awt.BasicStroke(1f)
    g2.drawRect(x, y, size - 1, size - 1)
}

/**
 * Draw the actual collision profile for a slope tile, matching SMILE's overlay style.
 *
 * Uses the ROM height table to draw the exact solid area polygon per tile.
 * BTS bit 6 (0x40) is the collision engine's X-flip flag ($94:87C0).
 * BTS bit 7 (0x80) selects ceiling vs floor.
 * Shapes with all-zero heights (passthrough/air slopes) draw an orange square.
 */
internal fun drawSlopeOverlay(g2: java.awt.Graphics2D, px: Int, py: Int, bts: Int, color: java.awt.Color) {
    val s = 16
    val shape = bts and 0x1F
    val isCeiling = (bts and 0x80) != 0
    val xFlip = (bts and 0x40) != 0

    val heights = SLOPE_HEIGHTS[shape]
    if (heights.all { it == 0 }) {
        val bg = java.awt.Color(color.red, color.green, color.blue, 80)
        val border = java.awt.Color(color.red, color.green, color.blue, 200)
        g2.color = bg
        g2.fillRect(px, py, s, s)
        g2.color = border
        g2.stroke = java.awt.BasicStroke(1.5f)
        g2.drawRect(px, py, s, s)
        g2.stroke = java.awt.BasicStroke(1f)
        return
    }

    val bg = java.awt.Color(color.red, color.green, color.blue, 80)
    val border = java.awt.Color(color.red, color.green, color.blue, 200)

    val xPts = mutableListOf<Int>()
    val yPts = mutableListOf<Int>()

    // Build the height profile for each pixel column
    val profile = IntArray(s) { screenX ->
        val col = if (xFlip) screenX else (s - 1 - screenX)
        heights[col].coerceIn(0, s)
    }

    if (!isCeiling) {
        // Find first and last column with height > 0 to avoid baseline overshoot
        val first = profile.indexOfFirst { it > 0 }.takeIf { it >= 0 } ?: 0
        val last = profile.indexOfLast { it > 0 }.takeIf { it >= 0 } ?: (s - 1)
        xPts.add(px + first); yPts.add(py + s)
        for (screenX in first..last) {
            xPts.add(px + screenX); yPts.add(py + s - profile[screenX])
        }
        xPts.add(px + last); yPts.add(py + s)
    } else {
        val first = profile.indexOfFirst { it > 0 }.takeIf { it >= 0 } ?: 0
        val last = profile.indexOfLast { it > 0 }.takeIf { it >= 0 } ?: (s - 1)
        xPts.add(px + first); yPts.add(py)
        for (screenX in first..last) {
            xPts.add(px + screenX); yPts.add(py + profile[screenX])
        }
        xPts.add(px + last); yPts.add(py)
    }

    g2.color = bg
    g2.fillPolygon(xPts.toIntArray(), yPts.toIntArray(), xPts.size)
    g2.color = border
    g2.stroke = java.awt.BasicStroke(1.5f)
    g2.drawPolygon(xPts.toIntArray(), yPts.toIntArray(), xPts.size)
    g2.stroke = java.awt.BasicStroke(1f)
}

internal fun isFlatSurfaceBlock(blockType: Int, bts: Int): Boolean {
    return blockType == 0x8 || (blockType == 0xE && bts == 0x00)
}

internal fun drawFlatSurfaceOverlay(g2: java.awt.Graphics2D, data: RoomRenderData, color: java.awt.Color) {
    val blocksWide = data.blocksWide
    val blocksTall = data.blocksTall
    if (blocksWide == 0 || blocksTall == 0 || data.blockTypes.isEmpty()) return

    fun hasFlatSurfaceAt(x: Int, y: Int): Boolean {
        if (x !in 0 until blocksWide || y !in 0 until blocksTall) return false
        val idx = y * blocksWide + x
        val bts = if (idx < data.btsData.size) data.btsData[idx].toInt() and 0xFF else 0
        return idx < data.blockTypes.size && isFlatSurfaceBlock(data.blockTypes[idx], bts)
    }

    val lineColor = java.awt.Color(color.red, color.green, color.blue, 220)
    val oldStroke = g2.stroke
    g2.color = lineColor
    g2.stroke = java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_SQUARE, java.awt.BasicStroke.JOIN_MITER)

    for (by in 0 until blocksTall) {
        for (bx in 0 until blocksWide) {
            if (!hasFlatSurfaceAt(bx, by)) continue

            val px = bx * 16
            val py = by * 16
            val right = px + 16
            val bottom = py + 16

            if (by > 0 && !hasFlatSurfaceAt(bx, by - 1)) g2.drawLine(px, py, right, py)
            if (by < blocksTall - 1 && !hasFlatSurfaceAt(bx, by + 1)) g2.drawLine(px, bottom, right, bottom)
            if (bx > 0 && !hasFlatSurfaceAt(bx - 1, by)) g2.drawLine(px, py, px, bottom)
            if (bx < blocksWide - 1 && !hasFlatSurfaceAt(bx + 1, by)) g2.drawLine(right, py, right, bottom)
        }
    }

    g2.stroke = oldStroke
}

internal fun buildCompositeImage(
    data: RoomRenderData,
    activeOverlays: Set<TileOverlay>,
    showGrid: Boolean,
    scrollData: IntArray? = null,
    roomWidthScreens: Int = 0,
    roomHeightScreens: Int = 0,
    layer3Pixels: IntArray? = null,
    layer3Width: Int = 256,
    layer3Height: Int = 264,
    layer2Pixels: IntArray? = null,
    customItems: List<CustomItemDef> = emptyList(),
    showItemNames: Boolean = true,
    highlightItems: Boolean = true,
    showEnemyNames: Boolean = true,
    showFlatSlopeSurfaces: Boolean = true,
): BufferedImage {
    val img = BufferedImage(data.width, data.height, BufferedImage.TYPE_INT_ARGB)

    // Layer 2 background: draw behind Layer 1 (composite L2 then L1 on top)
    val pixels = if (activeOverlays.contains(TileOverlay.LAYER2) && layer2Pixels != null && layer2Pixels.size == data.pixels.size) {
        // Compose: L2 as base, then L1 pixels on top (L1 pixel 0 = transparent → show L2)
        val l1 = if (activeOverlays.contains(TileOverlay.LIGHTEN)) {
            IntArray(data.pixels.size) { i ->
                val argb = data.pixels[i]
                val a = argb ushr 24
                val r = minOf(((argb shr 16) and 0xFF) * 3, 255)
                val g = minOf(((argb shr 8) and 0xFF) * 3, 255)
                val b = minOf((argb and 0xFF) * 3, 255)
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        } else data.pixels
        IntArray(data.pixels.size) { i ->
            val l1px = l1[i]
            val l2px = layer2Pixels[i]
            // Show L2 where L1 is bgColor AND L2 has visible content (non-transparent)
            if (l1px == RomConstants.ROM_BG_COLOR && l2px != 0) l2px
            else l1px
        }
    } else if (activeOverlays.contains(TileOverlay.LIGHTEN)) {
        IntArray(data.pixels.size) { i ->
            val argb = data.pixels[i]
            val a = argb ushr 24
            val r = minOf(((argb shr 16) and 0xFF) * 3, 255)
            val g = minOf(((argb shr 8) and 0xFF) * 3, 255)
            val b = minOf((argb and 0xFF) * 3, 255)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    } else {
        data.pixels
    }
    img.setRGB(0, 0, data.width, data.height, pixels, 0, data.width)

    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)

    // Layer 3 overlay: tile the L3 image across the room
    if (activeOverlays.contains(TileOverlay.LAYER3) && layer3Pixels != null && layer3Width > 0 && layer3Height > 0) {
        val l3Img = BufferedImage(layer3Width, layer3Height, BufferedImage.TYPE_INT_ARGB)
        l3Img.setRGB(0, 0, layer3Width, layer3Height, layer3Pixels, 0, layer3Width)
        val oldComposite = g.composite
        g.composite = java.awt.AlphaComposite.SrcOver
        for (ty in 0 until data.height step layer3Height) {
            for (tx in 0 until data.width step layer3Width) {
                g.drawImage(l3Img, tx, ty, null)
            }
        }
        g.composite = oldComposite
    }

    // Draw per-screen scroll color overlay
    if (activeOverlays.contains(TileOverlay.SCROLLS) && scrollData != null && roomWidthScreens > 0) {
        val scrollColors = arrayOf(
            java.awt.Color(200, 40, 40, 40),   // Red (hidden)
            java.awt.Color(40, 80, 200, 40),    // Blue (explorable)
            java.awt.Color(40, 160, 50, 40),    // Green (PLM-gated)
        )
        val scrollBorderColors = arrayOf(
            java.awt.Color(200, 40, 40, 120),
            java.awt.Color(40, 80, 200, 120),
            java.awt.Color(40, 160, 50, 120),
        )
        val scrollLabels = arrayOf("RED", "BLUE", "GREEN")
        val g2 = g as java.awt.Graphics2D
        g2.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 12)
        for (sy in 0 until roomHeightScreens) {
            for (sx in 0 until roomWidthScreens) {
                val idx = sy * roomWidthScreens + sx
                val scrollVal = scrollData.getOrElse(idx) { 0x01 }.coerceIn(0, 2)
                val px = sx * SCREEN_PX
                val py = sy * SCREEN_PX
                g2.color = scrollColors[scrollVal]
                g2.fillRect(px, py, SCREEN_PX, SCREEN_PX)
                g2.color = scrollBorderColors[scrollVal]
                g2.stroke = java.awt.BasicStroke(2f)
                g2.drawRect(px + 1, py + 1, SCREEN_PX - 3, SCREEN_PX - 3)
                g2.stroke = java.awt.BasicStroke(1f)
                val fm = g2.fontMetrics
                val label = scrollLabels[scrollVal]
                val tw = fm.stringWidth(label)
                g2.color = java.awt.Color(255, 255, 255, 100)
                g2.drawString(label, px + (SCREEN_PX - tw) / 2, py + 16)
            }
        }
    }

    // Draw screen grid when toggle is on (one line every 256 px)
    if (showGrid) {
        g.color = java.awt.Color(255, 255, 255, 0x30)
        var x = 0
        while (x <= data.width) {
            g.drawLine(x, 0, x, data.height)
            x += SCREEN_PX
        }
        var y = 0
        while (y <= data.height) {
            g.drawLine(0, y, data.width, y)
            y += SCREEN_PX
        }
    }

    if (activeOverlays.isEmpty()) {
        g.dispose()
        return img
    }

    val blocksWide = data.blocksWide
    val blocksTall = data.blocksTall

    if (blocksWide == 0 || blocksTall == 0 || data.blockTypes.isEmpty()) {
        g.dispose()
        return img
    }
    val btsData = data.btsData
    val itemBlocks = data.itemBlocks

    if (showFlatSlopeSurfaces && activeOverlays.contains(TileOverlay.SLOPE)) {
        val slopeOverlay = TileOverlay.SLOPE
        val flatColor = java.awt.Color(
            ((slopeOverlay.color shr 16) and 0xFF).toInt(),
            ((slopeOverlay.color shr 8) and 0xFF).toInt(),
            (slopeOverlay.color and 0xFF).toInt(),
            ((slopeOverlay.color shr 24) and 0xFF).toInt(),
        )
        val flatGraphics = g as java.awt.Graphics2D
        flatGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        drawFlatSurfaceOverlay(flatGraphics, data, flatColor)
    }

    for (by in 0 until blocksTall) {
        for (bx in 0 until blocksWide) {
            val idx = by * blocksWide + bx
            if (idx >= data.blockTypes.size) continue

            val blockType = data.blockTypes[idx]
            val bts = if (idx < btsData.size) btsData[idx].toInt() and 0xFF else 0
            val px = bx * 16
            val py = by * 16

            val matchingOverlays = mutableListOf<TileOverlay>()
            if (activeOverlays.contains(TileOverlay.SOLID) && blockType == 0x8) matchingOverlays.add(TileOverlay.SOLID)
            if (activeOverlays.contains(TileOverlay.SLOPE) && blockType == 0x1) matchingOverlays.add(TileOverlay.SLOPE)
            if (activeOverlays.contains(TileOverlay.DOOR) && blockType == 0x9) matchingOverlays.add(TileOverlay.DOOR)
            if (activeOverlays.contains(TileOverlay.SPIKE) && blockType == 0xA) matchingOverlays.add(TileOverlay.SPIKE)
            if (activeOverlays.contains(TileOverlay.BOMB) && blockType == 0xF) matchingOverlays.add(TileOverlay.BOMB)
            if (blockType == 0xC) {
                when (shotBlockCategory(bts)) {
                    ShotCategory.BEAM -> if (activeOverlays.contains(TileOverlay.SHOT_BEAM)) matchingOverlays.add(TileOverlay.SHOT_BEAM)
                    ShotCategory.SUPER -> if (activeOverlays.contains(TileOverlay.SHOT_SUPER)) matchingOverlays.add(TileOverlay.SHOT_SUPER)
                    ShotCategory.PB -> if (activeOverlays.contains(TileOverlay.SHOT_PB)) matchingOverlays.add(TileOverlay.SHOT_PB)
                    ShotCategory.HIDDEN -> if (activeOverlays.contains(TileOverlay.SHOT_BEAM)) matchingOverlays.add(TileOverlay.SHOT_BEAM)
                    ShotCategory.DOOR -> {}
                }
            }
            if (blockType == 0xB) {
                val isSpeedBts = bts == 0x0E || bts == 0x0F
                if (isSpeedBts && activeOverlays.contains(TileOverlay.SPEED)) matchingOverlays.add(TileOverlay.SPEED)
                else if (!isSpeedBts && activeOverlays.contains(TileOverlay.CRUMBLE)) matchingOverlays.add(TileOverlay.CRUMBLE)
            }
            if (activeOverlays.contains(TileOverlay.GRAPPLE) && blockType == 0xE) matchingOverlays.add(TileOverlay.GRAPPLE)
            if (activeOverlays.contains(TileOverlay.TREADMILL) && blockType == 0x3) matchingOverlays.add(TileOverlay.TREADMILL)
            if (activeOverlays.contains(TileOverlay.ITEMS) && itemBlocks.contains(idx)) matchingOverlays.add(TileOverlay.ITEMS)

            // Overlay icons: 1/4 tile size (8×8 in a 16×16 tile), bottom-right quadrant
            val iconSize = 8
            var iconX = px + 16 - iconSize
            val iconY = py + 16 - iconSize

            val g2 = g as java.awt.Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 7)

            for (overlay in matchingOverlays) {
                val color = java.awt.Color(
                    ((overlay.color shr 16) and 0xFF).toInt(),
                    ((overlay.color shr 8) and 0xFF).toInt(),
                    (overlay.color and 0xFF).toInt(),
                    ((overlay.color shr 24) and 0xFF).toInt()
                )

                if (overlay == TileOverlay.SLOPE) {
                    drawSlopeOverlay(g2, px, py, bts, color)
                } else if (overlay == TileOverlay.SPEED) {
                    drawSpeedBoosterOverlay(g2, iconX, iconY, iconSize, color)
                    iconX -= (iconSize + 1)
                } else {
                    val label = richOverlayLabel(overlay, bts)
                    val fm = g2.fontMetrics
                    val labelW = fm.stringWidth(label)
                    val cellW = maxOf(iconSize, labelW + 2)

                    g2.color = java.awt.Color(0, 0, 0, 200)
                    g2.fillRect(iconX + iconSize - cellW, iconY, cellW, iconSize)
                    g2.color = color
                    g2.stroke = java.awt.BasicStroke(1.5f)
                    g2.drawRect(iconX + iconSize - cellW + 1, iconY + 1, cellW - 2, iconSize - 2)
                    g2.stroke = java.awt.BasicStroke(1f)
                    g2.color = java.awt.Color.WHITE
                    g2.drawString(label, iconX + iconSize - cellW + (cellW - labelW) / 2,
                        iconY + (iconSize + fm.ascent - fm.descent) / 2)
                    iconX -= (cellW + 1)
                }
            }
        }
    }

    // Draw item / station / gate / door cap labels (positioned at PLM block coordinates)
    if (activeOverlays.contains(TileOverlay.ITEMS) && data.plmEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val itemColor = java.awt.Color(0xFF, 0xCC, 0x00)       // gold
        val stationColor = java.awt.Color(0x44, 0xCC, 0xFF)    // cyan
        val gateColor = java.awt.Color(0xCC, 0x66, 0xFF)       // purple
        val doorCapColor = java.awt.Color(0x60, 0x80, 0xB0)    // gray-blue
        val itemSpriteSheet = ItemSpriteSheetCache.get()
        val itemDefs = buildItemOverlayDefs(customItems)
        for (plm in data.plmEntries) {
            val itemDef = itemDefs[plm.id]
            val isItem = itemDef != null
            val isStation = RomParser.isStationPlm(plm.id)
            val isGate = RomParser.isGatePlm(plm.id)
            val isDoorCap = RomParser.doorCapColor(plm.id) != null
            if (!isItem && !isStation && !isGate && !isDoorCap) continue
            val name = when {
                isItem -> itemDef?.name ?: continue
                isStation -> RomParser.stationNameForPlm(plm.id) ?: continue
                isDoorCap -> RomParser.doorCapDisplayName(plm.id) ?: continue
                else -> RomParser.gateNameForPlm(plm.id, plm.param) ?: continue
            }
            val badgeBorder = when {
                isStation -> stationColor
                isGate -> gateColor
                isDoorCap -> doorCapColor
                else -> itemColor
            }
            val horiz = isDoorCap && RomParser.doorCapIsHorizontal(plm.id)
            val cx = if (horiz) plm.x * 16 + 32 else plm.x * 16 + 8
            val cy = when {
                horiz -> plm.y * 16 + 8
                isDoorCap -> plm.y * 16 + 32
                else -> plm.y * 16 + 8
            }
            if (isItem) {
                val sprite = itemDef?.sprite
                if (itemSpriteSheet != null && sprite != null) {
                    if (highlightItems) {
                        g2.color = java.awt.Color(0, 0, 0, 160)
                        g2.fillRoundRect(cx - 10, cy - 10, 20, 20, 4, 4)
                        g2.color = itemColor
                        g2.drawRoundRect(cx - 10, cy - 10, 20, 20, 4, 4)
                    }
                    g2.drawImage(
                        itemSpriteSheet,
                        cx - 8,
                        cy - 8,
                        cx + 8,
                        cy + 8,
                        sprite.x,
                        sprite.y,
                        sprite.x + 16,
                        sprite.y + 16,
                        null,
                    )
                } else {
                    val label = itemDef?.shortLabel?.take(2).orEmpty()
                    g2.color = java.awt.Color(0, 0, 0, 200)
                    g2.fillRoundRect(cx - 8, cy - 8, 16, 16, 4, 4)
                    g2.color = itemColor
                    g2.drawRoundRect(cx - 8, cy - 8, 16, 16, 4, 4)
                    g2.color = java.awt.Color.WHITE
                    val labelW = fm.stringWidth(label)
                    g2.drawString(label, cx - labelW / 2, cy + (fm.ascent - fm.descent) / 2)
                }
            }
            if (!isItem || showItemNames) {
                val textWidth = fm.stringWidth(name)
                val badgeW = textWidth + 6
                val badgeH = fm.height + 2
                val bx = (cx - badgeW / 2).coerceIn(0, maxOf(0, data.width - badgeW))
                val rawBadgeY = if (isItem) cy + 12 else cy - badgeH / 2
                val by = rawBadgeY.coerceIn(0, maxOf(0, data.height - badgeH))
                g2.color = java.awt.Color(0, 0, 0, 200)
                g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
                g2.color = badgeBorder
                g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
                g2.color = java.awt.Color.WHITE
                g2.drawString(name, bx + 3, by + fm.ascent + 1)
            }
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    // Draw scroll PLM badges (positioned at PLM block coordinates)
    if (activeOverlays.contains(TileOverlay.SCROLL_PLMS) && data.plmEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val scrollBadgeColor = java.awt.Color(0xFF, 0x80, 0x40)
        for (plm in data.plmEntries) {
            if (!RomParser.isScrollPlm(plm.id)) continue
            val name = RomParser.scrollPlmName(plm.id) ?: continue
            val cx = plm.x * 16 + 8
            val cy = plm.y * 16 + 8
            val textWidth = fm.stringWidth(name)
            val badgeW = textWidth + 6
            val badgeH = fm.height + 2
            val bx = cx - badgeW / 2
            val by = cy - badgeH / 2
            g2.color = java.awt.Color(0, 0, 0, 200)
            g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = scrollBadgeColor
            g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
            g2.color = java.awt.Color.WHITE
            g2.drawString(name, bx + 3, by + fm.ascent + 1)
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    // Draw enemy sprites / markers (positioned at enemy pixel coordinates)
    if (activeOverlays.contains(TileOverlay.ENEMIES) && data.enemyEntries.isNotEmpty()) {
        val g2 = g as java.awt.Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val labelFont = java.awt.Font("SansSerif", java.awt.Font.BOLD, 9)
        g.font = labelFont
        val fm = g.fontMetrics
        val markerColor = java.awt.Color(0xFF, 0x66, 0x44)
        for (enemy in data.enemyEntries) {
            val name = RomParser.enemyName(enemy.id)
            val ex = enemy.x
            val ey = enemy.y
            if (ex < 0 || ex >= data.width || ey < 0 || ey >= data.height) continue

            val hexId = enemy.id.toString(16).uppercase().padStart(4, '0')
            val sprite = EnemySpriteCache.get(hexId)
            if (sprite != null) {
                val sx = ex - sprite.width / 2
                val sy = ey - sprite.height / 2
                // Flip sprite horizontally when initParam indicates right-facing
                val flipH = enemy.initParam != 0 && enemy.id in ENEMY_IDS_FLIP_BY_INIT_PARAM
                if (flipH) {
                    g2.drawImage(sprite, sx + sprite.width, sy, sx, sy + sprite.height,
                        0, 0, sprite.width, sprite.height, null)
                } else {
                    g2.drawImage(sprite, sx, sy, null)
                }
            } else {
                val diamondSize = 6
                val dx = intArrayOf(ex, ex + diamondSize, ex, ex - diamondSize)
                val dy = intArrayOf(ey - diamondSize, ey, ey + diamondSize, ey)
                g2.color = java.awt.Color(0xFF, 0x44, 0x22, 180)
                g2.fillPolygon(dx, dy, 4)
                g2.color = markerColor
                g2.stroke = java.awt.BasicStroke(1.5f)
                g2.drawPolygon(dx, dy, 4)
                g2.stroke = java.awt.BasicStroke(1f)
            }

            if (showEnemyNames) {
                val textWidth = fm.stringWidth(name)
                val badgeW = textWidth + 6
                val badgeH = fm.height + 2
                val bx = ex - badgeW / 2
                val spriteH = sprite?.height ?: 12
                val by = ey - spriteH / 2 - badgeH - 2
                g2.color = java.awt.Color(0, 0, 0, 200)
                g2.fillRoundRect(bx, by, badgeW, badgeH, 4, 4)
                g2.color = markerColor
                g2.drawRoundRect(bx, by, badgeW, badgeH, 4, 4)
                g2.color = java.awt.Color.WHITE
                g2.drawString(name, bx + 3, by + fm.ascent + 1)
            }
        }
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    }

    g.dispose()
    return img
}
