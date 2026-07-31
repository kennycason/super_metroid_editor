package com.supermetroid.editor.rom

/**
 * Handles Phantoon's BG2-tilemap-based sprite assembly.
 *
 * Phantoon's visible components (body, eye, tentacles, mouth) are rendered on
 * SNES BG2 using "extended tilemaps" (header word FFFE) stored in bank $A7.
 * Each tilemap references 8x8 tiles from the room tileset (variable tile
 * indices 0x132–0x179 in tileset 5).
 *
 * The palette used is tileset palette row 7, which during gameplay is replaced
 * by the Phantoon-specific palette at $A7:CA21.
 *
 * Data addresses verified by PhantoonAssemblyTest (100% match with E4BF.png).
 */
class PhantoonSpritemap(private val romParser: RomParser) {

    companion object {
        const val PHANTOON_ROOM_SNES = 0x8FCD13
        private const val PHANTOON_ROOM_HANDLE = "phantoon"
        private const val PHANTOON_ROOM_NAME = "Phantoon's Room"
        private const val PHANTOON_SPECIES_ID = 0xE4BF
        const val EMPTY_TILE = RomConstants.EMPTY_TILE
        const val PALETTE_ROW = 7
        /** Phantoon in-game palette: $A7:CA21, 16 BGR555 words. */
        const val PALETTE_SNES = 0xA7CA21

        /**
         * BG2 tilemap addresses for each Phantoon component in bank $A7.
         * Format: FFFE header, then rows of [DEST, COUNT, TILE_WORDS...], terminated by FFFF.
         */
        val COMPONENT_TILEMAPS = listOf(
            ComponentDef("Body",       "E4BF", 0xA7E0AA),
            ComponentDef("Eye (open)", "E4BF", 0xA7E1CE),
            ComponentDef("Eye (mid)",  "E4BF", 0xA7E202),
            ComponentDef("Eye (closed)", "E4BF", 0xA7E236),
            ComponentDef("Eyeball",    "E4BF", 0xA7E26A),
        )

        /** X offset to trim left transparent columns when comparing to legacy PNGs. */
        const val BODY_PNG_X_OFFSET = 5
    }

    data class ComponentDef(
        val name: String,
        val speciesId: String,
        val tilemapSnes: Int
    )

    data class TilemapEntry(
        val gridX: Int,
        val gridY: Int,
        val tileNum: Int,
        val hFlip: Boolean,
        val vFlip: Boolean,
        val paletteRow: Int
    )

    data class AssembledSprite(
        val name: String,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        val entries: List<TilemapEntry>,
        val tilesCols: Int,
        val tilesRows: Int
    ) {
        /**
         * Map a pixel coordinate to the underlying tile and pixel-within-tile.
         * Returns (tileNum, tilePixelX, tilePixelY) or null if the pixel is in
         * an empty tile or out of bounds.
         */
        fun pixelToTile(px: Int, py: Int): Triple<Int, Int, Int>? {
            if (px < 0 || py < 0 || px >= width || py >= height) return null
            val gx = px / 8
            val gy = py / 8
            val entry = entries.firstOrNull { it.gridX == gx && it.gridY == gy } ?: return null
            if (entry.tileNum == EMPTY_TILE) return null
            val lpx = px % 8
            val lpy = py % 8
            val tpx = if (entry.hFlip) 7 - lpx else lpx
            val tpy = if (entry.vFlip) 7 - lpy else lpy
            return Triple(entry.tileNum, tpx, tpy)
        }
    }

    private var palette: IntArray? = null
    private var tilesetId: Int = -1
    private var cachedTileGfx: TileGraphics? = null

    /** Load the Phantoon room's tileset and the in-game palette. Returns true on success. */
    fun load(): Boolean {
        val rom = romParser.getRomData()
        val stateOffset = findPhantoonStateOffset() ?: return false
        tilesetId = rom[stateOffset + 3].toInt() and 0xFF

        val tg = getTileGraphics()
        if (!tg.loadTileset(tilesetId)) return false

        val palPc = romParser.snesToPc(PALETTE_SNES)
        val pal = IntArray(16)
        pal[0] = 0x00000000
        for (i in 1 until 16) {
            val lo = rom[palPc + i * 2].toInt() and 0xFF
            val hi = rom[palPc + i * 2 + 1].toInt() and 0xFF
            pal[i] = EnemySpriteGraphics.snesColorToArgb((hi shl 8) or lo)
        }
        palette = pal
        return true
    }

    private fun findPhantoonStateOffset(): Int? =
        findStateOffsetByPhantoonEnemyData()
            ?: findRoomStateOffsets().lastOrNull()

    private fun findStateOffsetByPhantoonEnemyData(): Int? {
        for (roomInfo in romParser.roomCatalog.rooms) {
            val roomId = roomInfo.getRoomIdAsInt()
            for (state in romParser.parseRoomStatesWithData(roomId)) {
                if (state.tileset !in 0 until TileGraphics.NUM_TILESETS) continue
                val hasPhantoonGfx = romParser.parseEnemyGfxSet(state.enemyGfxPtr)
                    .any { it.speciesId == PHANTOON_SPECIES_ID }
                val hasPhantoonEnemy = romParser.parseEnemyPopulation(state.enemySetPtr)
                    .any { it.id == PHANTOON_SPECIES_ID }
                if (hasPhantoonGfx || hasPhantoonEnemy) {
                    return state.stateInfo.stateDataPcOffset
                }
            }
        }
        return null
    }

    private fun findRoomStateOffsets(): List<Int> {
        val catalog = romParser.roomCatalog
        if (catalog.source != RomRoomCatalogSource.VANILLA) {
            val catalogRoom = catalog.rooms.firstOrNull { room ->
                room.handle == PHANTOON_ROOM_HANDLE ||
                    room.handle.startsWith("${PHANTOON_ROOM_HANDLE}_") ||
                    room.name.equals(PHANTOON_ROOM_NAME, ignoreCase = true)
            }
            val catalogOffsets = catalogRoom
                ?.let { romParser.findAllStateDataOffsets(it.getRoomIdAsInt()) }
                .orEmpty()
            if (catalogOffsets.isNotEmpty()) return catalogOffsets
        }

        val vanillaRoomId = PHANTOON_ROOM_SNES and 0xFFFF
        return if (romParser.readRoomHeader(vanillaRoomId) != null) {
            romParser.findAllStateDataOffsets(vanillaRoomId)
        } else {
            emptyList()
        }
    }

    fun getTileGraphics(): TileGraphics {
        var tg = cachedTileGfx
        if (tg == null) {
            tg = TileGraphics(romParser)
            cachedTileGfx = tg
        }
        return tg
    }

    fun getTilesetId(): Int = tilesetId

    fun getPalette(): IntArray? = palette?.copyOf()

    /** Parse a BG2 extended tilemap (FFFE format) from the given SNES address. */
    fun parseTilemap(tilemapSnes: Int): List<TilemapEntry> {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(tilemapSnes)
        val header = readWord(rom, pc)
        if (header != 0xFFFE) return emptyList()

        val entries = mutableListOf<TilemapEntry>()
        var offset = pc + 2
        var row = 0
        while (true) {
            val dest = readWord(rom, offset)
            if (dest == 0xFFFF) break
            val count = readWord(rom, offset + 2)
            offset += 4
            for (col in 0 until count) {
                val tw = readWord(rom, offset)
                offset += 2
                entries.add(TilemapEntry(
                    gridX = col,
                    gridY = row,
                    tileNum = tw and 0x03FF,
                    hFlip = (tw shr 14) and 1 != 0,
                    vFlip = (tw shr 15) and 1 != 0,
                    paletteRow = (tw shr 10) and 7
                ))
            }
            row++
        }
        return entries
    }

    /**
     * Render an assembled sprite from the tilemap.
     * Uses tiles from the loaded tileset and the Phantoon palette.
     */
    fun renderComponent(def: ComponentDef): AssembledSprite? {
        val pal = palette ?: return null
        val tg = getTileGraphics()
        if (!tg.loadTileset(tilesetId)) return null

        val entries = parseTilemap(def.tilemapSnes)
        if (entries.isEmpty()) return null

        val cols = (entries.maxOfOrNull { it.gridX } ?: 0) + 1
        val rows = (entries.maxOfOrNull { it.gridY } ?: 0) + 1
        val w = cols * 8
        val h = rows * 8
        val pixels = IntArray(w * h)

        for (entry in entries) {
            if (entry.tileNum == EMPTY_TILE) continue
            val indices = tg.readTileIndices(entry.tileNum) ?: continue
            for (py in 0 until 8) {
                for (px in 0 until 8) {
                    val sx = if (entry.hFlip) 7 - px else px
                    val sy = if (entry.vFlip) 7 - py else py
                    val ci = indices[sy * 8 + sx]
                    val argb = if (ci == 0) 0x00000000 else pal[ci.coerceIn(0, pal.size - 1)]
                    val dx = entry.gridX * 8 + px
                    val dy = entry.gridY * 8 + py
                    if (dx < w && dy < h) pixels[dy * w + dx] = argb
                }
            }
        }

        return AssembledSprite(def.name, w, h, pixels, entries, cols, rows)
    }

    /**
     * Apply pixel edits from an ARGB buffer back to the tileset.
     * Only writes pixels that actually changed relative to [sprite].pixels,
     * preventing unchanged mirrored/shared tile pixels from overwriting edits.
     * Returns the set of tile numbers that were modified.
     */
    fun applyEdits(sprite: AssembledSprite, editedPixels: IntArray, tileGraphics: TileGraphics): Set<Int> {
        val pal = palette ?: return emptySet()
        val modified = mutableSetOf<Int>()

        for (py in 0 until sprite.height) {
            for (px in 0 until sprite.width) {
                val idx = py * sprite.width + px
                if (sprite.pixels[idx] == editedPixels[idx]) continue
                val mapping = sprite.pixelToTile(px, py) ?: continue
                val (tileNum, tpx, tpy) = mapping
                val argb = editedPixels[idx]
                val alpha = (argb ushr 24) and 0xFF
                val ci = if (alpha < 128) 0 else findNearestPaletteIndex(argb, pal)
                tileGraphics.writePixelIndex(tileNum, tpx, tpy, ci)
                modified.add(tileNum)
            }
        }
        return modified
    }

    private fun findNearestPaletteIndex(argb: Int, palette: IntArray): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var best = 1
        var bestDist = Int.MAX_VALUE
        for (i in 1 until palette.size) {
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (dist < bestDist) { bestDist = dist; best = i }
            if (dist == 0) break
        }
        return best
    }

    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
