package com.supermetroid.editor.rom

/**
 * Handles Kraid's sprite data by combining the room tileset (tileset 27)
 * with Kraid's LZ-compressed 4bpp tiles at $B9:FA38.
 *
 * During gameplay, Kraid's AI loads 128 tiles into VRAM at tile index base
 * 0x100 (overwriting room tileset tiles at those indices). The BG2 nametable
 * ($B9:FE3E) and body tilemaps ($A7:97C8+) reference BOTH room tileset tiles
 * (indices 0x00-0xFF) and Kraid-specific tiles (0x100-0x17F).
 *
 * Palette row 6 is overwritten with kKraid_Palette2 ($A7:86C7) during the
 * fight. Each nametable entry specifies which palette row to use.
 */
class KraidSpritemap(private val romParser: RomParser) {

    companion object {
        const val KRAID_ROOM_SNES = 0x8FA59F
        private const val KRAID_ROOM_HANDLE = "kraid"
        private const val KRAID_ROOM_NAME = "Kraid's Room"
        const val TILE_GFX_SNES = 0xB9FA38
        const val TILE_GFX_PC = 0x1CFA38
        const val NAMETABLE_SNES = 0xB9FE3E
        const val NAMETABLE_PC = 0x1CFE3E
        /** kKraid_Palette2 — loaded to BG palette row 6 during the fight. */
        const val PALETTE_SNES = 0xA786C7
        const val PALETTE_ROW = 6
        /** All body/detail tiles actually use palette row 7 from the room tileset. */
        const val BODY_PALETTE_ROW = 7
        const val TILE_INDEX_BASE = 0x100
        const val TILE_COUNT = 128
        const val EMPTY_TILE = RomConstants.EMPTY_TILE
        const val BYTES_PER_TILE = RomConstants.BYTES_PER_4BPP_TILE
        /** BG2 nametable stride: Kraid's room uses 64-word rows (64×32 map). */
        const val BG2_STRIDE = 64

        val BODY_TILEMAPS = listOf(
            BodyTilemapDef("Body (initial)", 0xA797C8, 32, 12),
            BodyTilemapDef("Body (rising 1)", 0xA79AC8, 32, 12),
            BodyTilemapDef("Body (rising 2)", 0xA79DC8, 32, 12),
            BodyTilemapDef("Body (full height)", 0xA7A0C8, 32, 12),
        )

        val BIGSPRMAP_COMPONENTS = emptyList<ComponentDef>()

        val ALL_COMPONENTS: List<Any> = listOf("Full Body (nametable)") + BODY_TILEMAPS + BIGSPRMAP_COMPONENTS
    }

    data class BodyTilemapDef(
        val name: String,
        val snesAddr: Int,
        val cols: Int,
        val rows: Int
    )

    data class ComponentDef(
        val name: String,
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
        fun pixelToTile(px: Int, py: Int): Triple<Int, Int, Int>? {
            if (px < 0 || py < 0 || px >= width || py >= height) return null
            val gx = px / 8
            val gy = py / 8
            val entry = entries.firstOrNull { it.gridX == gx && it.gridY == gy } ?: return null
            val rawIdx = entry.tileNum - TILE_INDEX_BASE
            if (rawIdx < 0 || rawIdx >= TILE_COUNT) return null
            val lpx = px % 8
            val lpy = py % 8
            val tpx = if (entry.hFlip) 7 - lpx else lpx
            val tpy = if (entry.vFlip) 7 - lpy else lpy
            return Triple(rawIdx, tpx, tpy)
        }
    }

    /** Kraid's own 128 tiles (for tile sheet editing and ROM export). */
    private var tileData: ByteArray? = null
    /** In-game palette: room tileset palette row 7, used by all body/detail tiles. */
    private var palette: IntArray? = null
    /** Room tileset handler with Kraid tiles injected and palette row 6 overridden. */
    private var cachedTileGfx: TileGraphics? = null
    private var tilesetId: Int = -1

    fun load(): Boolean {
        return try {
            val tg = setupTileGraphics() ?: return false

            // Extract Kraid's 128 tiles from the loaded tileset for editing/export
            tileData = tg.extractRawTileData(TILE_INDEX_BASE, TILE_COUNT)

            palette = extractInGamePalette(tg)
            palette != null
        } catch (_: Exception) {
            false
        }
    }

    fun loadWithCustomTiles(customTileData: ByteArray): Boolean {
        tileData = customTileData.copyOf()
        val tg = setupTileGraphics() ?: return false
        tg.injectRawTileData(TILE_INDEX_BASE, customTileData)
        palette = extractInGamePalette(tg)
        return palette != null
    }

    /**
     * Load room tileset 27 and override palette row 6 with kKraid_Palette2.
     * Kraid's body tiles (0x100-0x17F) are already part of tileset 27 —
     * no injection needed. ($B9:FA38 is a background TILEMAP, not tile graphics.)
     */
    private fun setupTileGraphics(): TileGraphics? {
        val rom = romParser.getRomData()
        val stateOffsets = findRoomStateOffsets()
        if (stateOffsets.isEmpty()) return null
        tilesetId = rom[stateOffsets.last() + 3].toInt() and 0xFF

        val tg = TileGraphics(romParser)
        if (!tg.loadTileset(tilesetId)) return null

        val kraidPal2 = readKraidPalette2()
        if (kraidPal2 != null) {
            for (i in 0 until 16) {
                val bgr = snesColorFromArgb(kraidPal2[i])
                tg.setPaletteEntry(PALETTE_ROW, i, bgr)
            }
        }

        cachedTileGfx = tg
        return tg
    }

    private fun findRoomStateOffsets(): List<Int> {
        val catalog = romParser.roomCatalog
        if (catalog.source != RomRoomCatalogSource.VANILLA) {
            val catalogRoom = catalog.rooms.firstOrNull { room ->
                room.handle == KRAID_ROOM_HANDLE ||
                    room.handle.startsWith("${KRAID_ROOM_HANDLE}_") ||
                    room.name.equals(KRAID_ROOM_NAME, ignoreCase = true)
            }
            val catalogOffsets = catalogRoom
                ?.let { romParser.findAllStateDataOffsets(it.getRoomIdAsInt()) }
                .orEmpty()
            if (catalogOffsets.isNotEmpty()) return catalogOffsets
        }

        val vanillaRoomId = KRAID_ROOM_SNES and 0xFFFF
        return if (romParser.readRoomHeader(vanillaRoomId) != null) {
            romParser.findAllStateDataOffsets(vanillaRoomId)
        } else {
            emptyList()
        }
    }

    /**
     * Extract the in-game palette for Kraid's tiles.
     * All body/detail tiles use palette row 7 from the room tileset.
     */
    private fun extractInGamePalette(tg: TileGraphics): IntArray? {
        val palettes = tg.getPalettes() ?: return null
        if (BODY_PALETTE_ROW >= palettes.size) return null
        val row = palettes[BODY_PALETTE_ROW]
        val pal = row.copyOf()
        pal[0] = 0x00000000
        return pal
    }

    fun getTileGraphics(): TileGraphics? {
        return cachedTileGfx
    }

    fun getTileData(): ByteArray? = tileData

    fun getPalette(): IntArray? = palette?.copyOf()

    fun getTilesetId(): Int = tilesetId

    /** Read kKraid_Palette2 at $A7:86C7 (BG palette row 6, used for environment). */
    private fun readKraidPalette2(): IntArray? {
        val rom = romParser.getRomData()
        val palPc = romParser.snesToPc(PALETTE_SNES)
        if (palPc < 0 || palPc + 32 > rom.size) return null
        val pal = IntArray(16)
        pal[0] = 0x00000000
        for (i in 1 until 16) {
            val bgr = readWord(rom, palPc + i * 2)
            pal[i] = EnemySpriteGraphics.snesColorToArgb(bgr)
        }
        return pal
    }

    fun renderFullBody(): AssembledSprite? {
        val tg = cachedTileGfx ?: return null
        val nmPc = romParser.snesToPc(NAMETABLE_SNES)
        val nmData = romParser.decompressLZ5AtPc(nmPc)
        val nmWords = nmData.size / 2

        // BG2 nametable is stored as two 32×32 blocks (SNES SC size 01 = 64×32).
        // Block 0 (words 0-1023): left 32 columns; Block 1 (words 1024-2047): right 32 columns.
        // Deinterleave into a 64-column linear layout for rendering.
        if (nmWords == 2048) {
            val cols = 64
            val rows = 32
            val linearData = ByteArray(cols * rows * 2)
            for (r in 0 until rows) {
                // Left half: block 0, row r → columns 0-31
                System.arraycopy(nmData, (r * 32) * 2, linearData, (r * cols) * 2, 32 * 2)
                // Right half: block 1, row r → columns 32-63
                System.arraycopy(nmData, (1024 + r * 32) * 2, linearData, (r * cols + 32) * 2, 32 * 2)
            }
            return renderFromTilemap(tg, linearData, cols, rows, "Full Body (nametable)")
        }

        // Fallback for unexpected sizes
        val cols = 32
        val rows = nmWords / cols
        return renderFromTilemap(tg, nmData, cols, rows, "Full Body (nametable)")
    }

    fun renderBodyTilemap(def: BodyTilemapDef): AssembledSprite? {
        val tg = cachedTileGfx ?: return null
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(def.snesAddr)
        val dataSize = def.cols * def.rows * 2
        val tmData = ByteArray(dataSize)
        System.arraycopy(rom, pc, tmData, 0, dataSize)
        return renderFromTilemap(tg, tmData, def.cols, def.rows, def.name)
    }

    fun renderBigSprmap(def: ComponentDef): AssembledSprite? {
        val tg = cachedTileGfx ?: return null
        val palettes = tg.getPalettes() ?: return null
        val entries = parseFffeTilemap(def.tilemapSnes)
        if (entries.isEmpty()) return null

        val cols = (entries.maxOfOrNull { it.gridX } ?: 0) + 1
        val rows = (entries.maxOfOrNull { it.gridY } ?: 0) + 1
        val w = cols * 8
        val h = rows * 8
        val pixels = IntArray(w * h)

        for (entry in entries) {
            renderTileToPixels(tg, palettes, entry, pixels, w, h)
        }

        return AssembledSprite(def.name, w, h, pixels, entries, cols, rows)
    }

    fun applyEdits(sprite: AssembledSprite, editedPixels: IntArray): Set<Int> {
        val tiles = tileData ?: return emptySet()
        val pal = palette ?: return emptySet()
        val modified = mutableSetOf<Int>()

        for (py in 0 until sprite.height) {
            for (px in 0 until sprite.width) {
                val idx = py * sprite.width + px
                if (sprite.pixels[idx] == editedPixels[idx]) continue
                val mapping = sprite.pixelToTile(px, py) ?: continue
                val (rawTileIdx, tpx, tpy) = mapping
                if (rawTileIdx < 0 || rawTileIdx * BYTES_PER_TILE + BYTES_PER_TILE > tiles.size) continue
                val argb = editedPixels[idx]
                val alpha = (argb ushr 24) and 0xFF
                val ci = if (alpha < 128) 0 else findNearestPaletteIndex(argb, pal)
                writeTilePixel(tiles, rawTileIdx, tpx, tpy, ci)
                modified.add(rawTileIdx)
            }
        }

        if (modified.isNotEmpty()) {
            cachedTileGfx?.injectRawTileData(TILE_INDEX_BASE, tiles)
        }

        return modified
    }

    private fun renderFromTilemap(
        tg: TileGraphics, tmData: ByteArray, cols: Int, rows: Int, name: String
    ): AssembledSprite {
        val palettes = tg.getPalettes() ?: return AssembledSprite(name, cols * 8, rows * 8, IntArray(cols * rows * 64), emptyList(), cols, rows)
        val w = cols * 8
        val h = rows * 8
        val pixels = IntArray(w * h)
        val entries = mutableListOf<TilemapEntry>()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val word = readWord(tmData, (r * cols + c) * 2)
                val entry = TilemapEntry(
                    gridX = c, gridY = r,
                    tileNum = word and 0x03FF,
                    hFlip = (word shr 14) and 1 != 0,
                    vFlip = (word shr 15) and 1 != 0,
                    paletteRow = (word shr 10) and 7
                )
                entries.add(entry)
                renderTileToPixels(tg, palettes, entry, pixels, w, h)
            }
        }

        return AssembledSprite(name, w, h, pixels, entries, cols, rows)
    }

    private fun renderTileToPixels(
        tg: TileGraphics, palettes: Array<IntArray>,
        entry: TilemapEntry, pixels: IntArray, w: Int, h: Int
    ) {
        if (entry.tileNum == 0 || entry.tileNum == EMPTY_TILE) return

        val indices = tg.readTileIndices(entry.tileNum) ?: return
        val pal = palettes[entry.paletteRow.coerceIn(0, palettes.size - 1)]

        for (py in 0 until 8) {
            for (px in 0 until 8) {
                val sx = if (entry.hFlip) 7 - px else px
                val sy = if (entry.vFlip) 7 - py else py
                val ci = indices[sy * 8 + sx]
                if (ci == 0) continue
                val argb = pal[ci.coerceIn(0, pal.size - 1)]
                val dx = entry.gridX * 8 + px
                val dy = entry.gridY * 8 + py
                if (dx < w && dy < h) {
                    pixels[dy * w + dx] = argb
                }
            }
        }
    }

    private fun writeTilePixel(tiles: ByteArray, rawTileIdx: Int, px: Int, py: Int, colorIdx: Int) {
        val offset = rawTileIdx * BYTES_PER_TILE
        val bit = 7 - px
        fun setBit(byteOffset: Int, v: Int) {
            val cur = tiles[offset + byteOffset].toInt() and 0xFF
            tiles[offset + byteOffset] = if (v != 0) (cur or (1 shl bit)).toByte()
            else (cur and (1 shl bit).inv()).toByte()
        }
        setBit(py * 2, colorIdx and 1)
        setBit(py * 2 + 1, (colorIdx shr 1) and 1)
        setBit(py * 2 + 16, (colorIdx shr 2) and 1)
        setBit(py * 2 + 17, (colorIdx shr 3) and 1)
    }

    private fun parseFffeTilemap(snesAddr: Int): List<TilemapEntry> {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(snesAddr)
        val header = readWord(rom, pc)
        if (header != 0xFFFE) return emptyList()

        // First pass: collect raw dest/tile data
        data class RawRow(val dest: Int, val tiles: List<Int>)
        val rawRows = mutableListOf<RawRow>()
        var offset = pc + 2
        while (true) {
            val dest = readWord(rom, offset)
            if (dest == 0xFFFF) break
            val count = readWord(rom, offset + 2)
            offset += 4
            val tiles = mutableListOf<Int>()
            for (i in 0 until count) {
                tiles.add(readWord(rom, offset))
                offset += 2
            }
            rawRows.add(RawRow(dest, tiles))
        }
        if (rawRows.isEmpty()) return emptyList()

        // Kraid's BG2 uses a 64-word stride; derive row/col from dest values
        val baseDest = rawRows.first().dest
        val baseRow = baseDest / BG2_STRIDE
        val baseCol = baseDest % BG2_STRIDE

        val entries = mutableListOf<TilemapEntry>()
        for (raw in rawRows) {
            val row = raw.dest / BG2_STRIDE - baseRow
            val colStart = raw.dest % BG2_STRIDE - baseCol
            for ((i, tw) in raw.tiles.withIndex()) {
                entries.add(TilemapEntry(
                    gridX = colStart + i,
                    gridY = row,
                    tileNum = tw and 0x03FF,
                    hFlip = (tw shr 14) and 1 != 0,
                    vFlip = (tw shr 15) and 1 != 0,
                    paletteRow = (tw shr 10) and 7
                ))
            }
        }
        return entries
    }

    private fun findNearestPaletteIndex(argb: Int, pal: IntArray): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var best = 1
        var bestDist = Int.MAX_VALUE
        for (i in 1 until pal.size) {
            val pr = (pal[i] shr 16) and 0xFF
            val pg = (pal[i] shr 8) and 0xFF
            val pb = pal[i] and 0xFF
            val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (dist < bestDist) { bestDist = dist; best = i }
            if (dist == 0) break
        }
        return best
    }

    private fun snesColorFromArgb(argb: Int): Int {
        val r = ((argb shr 16) and 0xFF) / 8
        val g = ((argb shr 8) and 0xFF) / 8
        val b = (argb and 0xFF) / 8
        return (b shl 10) or (g shl 5) or r
    }

    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
