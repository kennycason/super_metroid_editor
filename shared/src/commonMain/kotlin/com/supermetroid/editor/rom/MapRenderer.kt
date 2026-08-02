package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room
import com.supermetroid.editor.util.EditorLog

/**
 * Renders Super Metroid room maps from decompressed level data.
 * 
 * Each 2-byte block entry (LE):
 *   Bits 0-9:   Block/metatile index
 *   Bit  10:    Horizontal flip
 *   Bit  11:    Vertical flip
 *   Bits 12-15: Block type (collision)
 * 
 * Block types: 0=Air, 1=Slope, 8=Solid, 9=Door, A=Spike, 
 *              B=Crumble, C=Shot, E=Grapple, F=Bomb
 */
class MapRenderer(private val romParser: RomParser, customTileGraphics: TileGraphics? = null) {
    
    companion object {
        const val BLOCK_SIZE = 16
        const val BLOCKS_PER_SCREEN = 16
    }
    
    private val tileGraphics = customTileGraphics ?: TileGraphics(romParser)
    
    /**
     * Classify block types into visual categories for rendering.
     */
    private fun isSolid(blockType: Int): Boolean = when (blockType) {
        0x1, 0x3, 0x8, 0xB, 0xC, 0xE, 0xF -> true  // Slope, treadmill, solid, crumble, shot, grapple, bomb
        else -> false
    }
    
    private fun isDoor(blockType: Int): Boolean = blockType == 0x9
    private fun isSpike(blockType: Int): Boolean = blockType == 0xA
    private fun isSlope(blockType: Int): Boolean = blockType == 0x1
    
    // Area-based terrain colors
    private val areaTerrainColors = mapOf(
        0 to intArrayOf(0xFF566878.toInt(), 0xFF4A5A6A.toInt()), // Crateria — cool gray-blue
        1 to intArrayOf(0xFF4A6848.toInt(), 0xFF3E5A3E.toInt()), // Brinstar — green
        2 to intArrayOf(0xFF785040.toInt(), 0xFF6A4438.toInt()), // Norfair — warm red-brown
        3 to intArrayOf(0xFF505868.toInt(), 0xFF444E5C.toInt()), // Wrecked Ship — dark blue-gray
        4 to intArrayOf(0xFF406888.toInt(), 0xFF385A78.toInt()), // Maridia — ocean blue
        5 to intArrayOf(0xFF706048.toInt(), 0xFF625440.toInt()), // Tourian — brown-gold
        6 to intArrayOf(0xFF686868.toInt(), 0xFF5A5A5A.toInt()), // Ceres — neutral gray
    )
    
    private val bgColor = RomConstants.ROM_BG_COLOR  // Deep dark background
    private val spikeColor = 0xFFD04040.toInt()     // Red spikes
    private val doorColor = 0xFF4090F0.toInt()      // Fallback blue doors (no tile graphics)
    private val edgeColor = 0xFF1A1A28.toInt()      // Dark edge highlight
    private val gridColor = 0x30FFFFFF              // Subtle screen grid
    
    fun renderRoom(room: Room): RoomRenderData? {
        if (room.levelDataPtr == 0) return renderGrid(room)
        val levelData: ByteArray
        try {
            levelData = romParser.decompressLZ2(room.levelDataPtr)
        } catch (e: Exception) {
            return renderGrid(room)
        }
        return renderRoomFromLevelData(room, levelData)
    }
    
    /** Render from already-decompressed (possibly edited) level data. */
    fun renderRoomFromLevelData(room: Room, levelData: ByteArray, plmOverrides: List<RomParser.PlmEntry>? = null, enemyOverrides: List<RomParser.EnemyEntry>? = null): RoomRenderData? {
        if (levelData.size < 2) return renderGrid(room)
        
        val layer1Size = (levelData[0].toInt() and 0xFF) or ((levelData[1].toInt() and 0xFF) shl 8)
        val tileDataStart = 2
        val blocksWide = room.width * BLOCKS_PER_SCREEN
        val blocksTall = room.height * BLOCKS_PER_SCREEN
        val totalBlocks = blocksWide * blocksTall
        
        // Metatile offset: no longer needed — loadTileset() is data-driven
        // and handles CRE prepend vs. full variable table automatically.
        val metatileOffset = 0
        val hasTileGraphics = try {
            tileGraphics.loadTileset(room.tileset)
        } catch (e: Exception) {
            EditorLog.warn(e, "Failed to load tileset ${room.tileset}: ${e.message}")
            false
        }
        
        // Render to pixels
        val pixelWidth = blocksWide * BLOCK_SIZE
        val pixelHeight = blocksTall * BLOCK_SIZE
        val pixels = IntArray(pixelWidth * pixelHeight)
        pixels.fill(bgColor)
        val btsDataStart = tileDataStart + layer1Size
        
        // Parse PLM set for door cap coloring and item positions
        val plms = plmOverrides ?: romParser.parsePlmSet(room.plmSetPtr)
        // Build map: block (x,y) → door cap ARGB color
        val doorCapColors = mutableMapOf<Long, Int>()
        for (plm in plms) {
            val capColor = RomParser.doorCapColor(plm.id) ?: continue
            val horiz = RomParser.doorCapIsHorizontal(plm.id)
            for (d in 0 until 4) {
                val key = if (horiz) packXY(plm.x + d, plm.y) else packXY(plm.x, plm.y + d)
                doorCapColors[key] = capColor
            }
        }
        
        // Parse and render each block
        for (tileIdx in 0 until totalBlocks) {
            val dataOffset = tileDataStart + tileIdx * 2
            if (dataOffset + 1 >= levelData.size) break
            
            val lo = levelData[dataOffset].toInt() and 0xFF
            val hi = levelData[dataOffset + 1].toInt() and 0xFF
            val blockWord = (hi shl 8) or lo
            
            val blockType = (blockWord shr 12) and 0x0F
            val rawMetatileIndex = blockWord and 0x03FF
            val metatileIndex = minOf(rawMetatileIndex + metatileOffset, 1023)
            val hFlip = (blockWord shr 10) and 1
            val vFlip = (blockWord shr 11) and 1
            
            val bx = tileIdx % blocksWide
            val by = tileIdx / blocksWide
            val px = bx * BLOCK_SIZE
            val py = by * BLOCK_SIZE
            
            val shouldUseTileGraphics = hasTileGraphics && !tileGraphics.isPlaceholderMetatile(metatileIndex)
            if (shouldUseTileGraphics) {
                val metatilePixels = tileGraphics.renderMetatile(metatileIndex)
                if (metatilePixels != null && metatilePixels.any { it != 0 }) {
                    for (ty in 0 until 16) {
                        for (tx in 0 until 16) {
                            val sx = if (hFlip != 0) 15 - tx else tx
                            val sy = if (vFlip != 0) 15 - ty else ty
                            val argb = metatilePixels[sy * 16 + sx]
                            if (argb != 0) {
                                setPixel(pixels, pixelWidth, pixelHeight, px + tx, py + ty, argb)
                            }
                        }
                    }
                } else {
                    renderFallbackBlock(room, blockType, pixels, pixelWidth, pixelHeight, px, py)
                }
            } else {
                renderFallbackBlock(room, blockType, pixels, pixelWidth, pixelHeight, px, py)
            }
            
            // Tint door cap blocks: recolor blue pixels to the correct door color
            val capColor = doorCapColors[packXY(bx, by)]
            if (capColor != null && capColor != RomParser.DOOR_CAP_BLUE) {
                tintDoorCap(pixels, pixelWidth, pixelHeight, px, py, capColor)
            }
        }
        
        // Parse block types, BTS data, and H-flip flags for overlay system.
        val blockTypes = IntArray(totalBlocks)
        val btsBytes = ByteArray(totalBlocks)
        val hFlipFlags = BooleanArray(totalBlocks)
        
        for (i in 0 until totalBlocks) {
            val offset = tileDataStart + i * 2
            if (offset + 1 < levelData.size) {
                val lo = levelData[offset].toInt() and 0xFF
                val hi = levelData[offset + 1].toInt() and 0xFF
                val word = (hi shl 8) or lo
                blockTypes[i] = (word shr 12) and 0x0F
                hFlipFlags[i] = (word and (1 shl 10)) != 0
            }
            val btsOffset = btsDataStart + i
            if (btsOffset < levelData.size) {
                btsBytes[i] = levelData[btsOffset]
            }
        }
        
        val itemBlockSet = mutableSetOf<Int>()
        for (plm in plms) {
            if (RomParser.isItemPlm(plm.id)) {
                val idx = plm.y * blocksWide + plm.x
                if (idx in 0 until totalBlocks) itemBlockSet.add(idx)
            }
        }

        val enemies = enemyOverrides ?: romParser.parseEnemyPopulation(room.enemySetPtr)

        return RoomRenderData(pixelWidth, pixelHeight, pixels, blocksWide, blocksTall, blockTypes, btsBytes,
            hFlipFlags = hFlipFlags, itemBlocks = itemBlockSet, plmEntries = plms, enemyEntries = enemies)
    }
    
    /** Pack two ints into a Long key for map lookup. */
    private fun packXY(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    
    /**
     * Tint blue door cap pixels to a target color. Scans the 16×16 block for
     * "blue-ish" pixels (blue dominant) and recolors them, preserving brightness.
     */
    private fun tintDoorCap(pixels: IntArray, w: Int, h: Int, px: Int, py: Int, targetColor: Int) {
        val tr = (targetColor shr 16) and 0xFF
        val tg = (targetColor shr 8) and 0xFF
        val tb = targetColor and 0xFF
        for (dy in 0 until BLOCK_SIZE) {
            for (dx in 0 until BLOCK_SIZE) {
                val x = px + dx
                val y = py + dy
                if (x !in 0 until w || y !in 0 until h) continue
                val idx = y * w + x
                val pixel = pixels[idx]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // Is this pixel "blue enough" to be part of the door cap?
                if (b > 60 && b > r + 15 && b > g + 15) {
                    val brightness = b.toFloat() / 255f
                    val newR = (tr * brightness).toInt().coerceIn(0, 255)
                    val newG = (tg * brightness).toInt().coerceIn(0, 255)
                    val newB = (tb * brightness).toInt().coerceIn(0, 255)
                    pixels[idx] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
                }
            }
        }
    }
    
    private fun fillBlock(pixels: IntArray, w: Int, h: Int, px: Int, py: Int, color: Int) {
        for (y in 0 until BLOCK_SIZE) {
            for (x in 0 until BLOCK_SIZE) {
                setPixel(pixels, w, h, px + x, py + y, color)
            }
        }
    }

    private fun renderFallbackBlock(
        room: Room,
        blockType: Int,
        pixels: IntArray,
        w: Int,
        h: Int,
        px: Int,
        py: Int,
    ) {
        val color = fallbackBlockColor(room, blockType)
        if (color != bgColor) {
            fillBlock(pixels, w, h, px, py, color)
        }
    }

    private fun fallbackBlockColor(room: Room, blockType: Int): Int {
        val terrainColors = areaTerrainColors[room.area] ?: areaTerrainColors[0]!!
        return when {
            isDoor(blockType) -> doorColor
            isSpike(blockType) -> spikeColor
            isSolid(blockType) -> terrainColors[0]
            else -> bgColor
        }
    }
    
    private fun setPixel(pixels: IntArray, w: Int, h: Int, x: Int, y: Int, color: Int) {
        if (x in 0 until w && y in 0 until h) {
            pixels[y * w + x] = color
        }
    }
    
    private fun drawScreenGrid(pixels: IntArray, w: Int, h: Int, screensW: Int, screensH: Int) {
        for (sy in 0..screensH) {
            val y = sy * BLOCKS_PER_SCREEN * BLOCK_SIZE
            if (y in 0 until h) {
                for (x in 0 until w) pixels[y * w + x] = blendColor(pixels[y * w + x], gridColor)
            }
        }
        for (sx in 0..screensW) {
            val x = sx * BLOCKS_PER_SCREEN * BLOCK_SIZE
            if (x in 0 until w) {
                for (y in 0 until h) pixels[y * w + x] = blendColor(pixels[y * w + x], gridColor)
            }
        }
    }
    
    private fun renderGrid(room: Room): RoomRenderData {
        val pw = room.width * BLOCKS_PER_SCREEN * BLOCK_SIZE
        val ph = room.height * BLOCKS_PER_SCREEN * BLOCK_SIZE
        val pixels = IntArray(pw * ph)
        val tc = areaTerrainColors[room.area]?.get(1) ?: 0xFF2A2A2A.toInt()
        pixels.fill(tc)
        return RoomRenderData(pw, ph, pixels)
    }
    
    /**
     * Render the Layer 2 background for a room.
     * For scrolling BG rooms (bgScrolling != 0), reads and tiles the bgDataPtr nametable.
     * For embedded L2 rooms (bgScrolling == 0), renders the full-room L2 metatile data.
     * Returns a pixel array the same size as the room (pixelWidth × pixelHeight), or null.
     */
    fun renderLayer2(room: Room, levelData: ByteArray? = null): IntArray? {
        val pixelWidth = room.width * BLOCKS_PER_SCREEN * BLOCK_SIZE
        val pixelHeight = room.height * BLOCKS_PER_SCREEN * BLOCK_SIZE

        if (room.bgScrolling != 0 && room.bgDataPtr != 0) {
            // Scrolling BG: read the 32×32 8x8 nametable and tile it across the room
            val tilemap = romParser.readBgTilemap(room.bgDataPtr) ?: return null
            val (screenPixels, sw, sh) = tileGraphics.renderBgTilemap(tilemap) ?: return null
            // Tile the 256×256 BG screen across the full room area
            val pixels = IntArray(pixelWidth * pixelHeight)
            for (y in 0 until pixelHeight) {
                val srcY = y % sh
                for (x in 0 until pixelWidth) {
                    val srcX = x % sw
                    pixels[y * pixelWidth + x] = screenPixels[srcY * sw + srcX]
                }
            }
            return pixels
        } else if (room.bgScrolling == 0) {
            // Embedded Layer 2: metatile data in level data
            val data = levelData ?: try { romParser.decompressLZ2(room.levelDataPtr) } catch (_: Exception) { null } ?: return null
            val blocksWide = room.width * BLOCKS_PER_SCREEN
            val blocksTall = room.height * BLOCKS_PER_SCREEN
            val l2Words = romParser.readEmbeddedLayer2(data, blocksWide, blocksTall) ?: return null
            val pixels = IntArray(pixelWidth * pixelHeight)
            for (idx in l2Words.indices) {
                val word = l2Words[idx]
                val metatileIndex = word and 0x03FF
                val hFlip = (word shr 10) and 1
                val vFlip = (word shr 11) and 1
                val metatilePixels = tileGraphics.renderMetatile(metatileIndex) ?: continue
                val bx = idx % blocksWide
                val by = idx / blocksWide
                val px = bx * BLOCK_SIZE
                val py = by * BLOCK_SIZE
                for (ty in 0 until 16) {
                    for (tx in 0 until 16) {
                        val sx = if (hFlip != 0) 15 - tx else tx
                        val sy = if (vFlip != 0) 15 - ty else ty
                        val argb = metatilePixels[sy * 16 + sx]
                        if (argb != 0) {
                            pixels[(py + ty) * pixelWidth + (px + tx)] = argb
                        }
                    }
                }
            }
            return pixels
        }
        return null
    }

    private fun blendColor(base: Int, overlay: Int): Int {
        val alpha = (overlay ushr 24) and 0xFF
        if (alpha == 0) return base
        val inv = 255 - alpha
        val r = (((overlay shr 16) and 0xFF) * alpha + ((base shr 16) and 0xFF) * inv) / 255
        val g = (((overlay shr 8) and 0xFF) * alpha + ((base shr 8) and 0xFF) * inv) / 255
        val b = ((overlay and 0xFF) * alpha + (base and 0xFF) * inv) / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

data class RoomRenderData(
    val width: Int,           // Pixel width
    val height: Int,          // Pixel height
    val pixels: IntArray,     // ARGB pixel data
    val blocksWide: Int = 0,  // Width in 16x16 blocks
    val blocksTall: Int = 0,  // Height in 16x16 blocks
    val blockTypes: IntArray = IntArray(0),  // Block type per tile (0-15)
    val btsData: ByteArray = ByteArray(0),   // BTS byte per tile
    val hFlipFlags: BooleanArray = BooleanArray(0), // H-flip per tile from block word bit 10
    val itemBlocks: Set<Int> = emptySet(),   // Block indices that have items (from PLM; empty until we parse PLM set)
    val plmEntries: List<RomParser.PlmEntry> = emptyList(),
    val enemyEntries: List<RomParser.EnemyEntry> = emptyList(),
)
