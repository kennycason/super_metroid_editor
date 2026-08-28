package com.supermetroid.editor.rom

/**
 * Loads and decodes Super Metroid tile graphics from ROM.
 * 
 * Pipeline (from SMILE source and Metroid Construction wiki):
 *   1. Tileset pointer table at $8F:E6A2 — 29 entries × 9 bytes each
 *      [tile_table_ptr(3)] [gfx_ptr(3)] [palette_ptr(3)]
 *   2. Decompress tile table → 1024 metatile entries × 8 bytes
 *      Each metatile = 4 SNES BG tilemap words (TL, TR, BL, BR)
 *   3. Decompress 8x8 tile graphics (4bpp planar, 32 bytes/tile)
 *      Variable tiles: indices 0-639, CRE tiles: indices 640-1023
 *   4. Load palette: 8 sub-palettes × 16 colors (BGR555)
 *   5. Render: level data tile index → metatile → 4 sub-tiles → pixels
 *
 * Vanilla CRE (Common Room Elements) defaults:
 *   GFX:        $B9:8000 (PC: $1C8000)
 *   Tile table: $B9:A09D (PC: $1CA09D)
 * Expanded/relocated layouts may repoint these; RomGraphicsCatalog detects
 * those pointers and loadTileset() uses the catalog values.
 *
 * Sources:
 *   - SMILE legacy: DecompressTiles.vb, DecompressTtable.vb, DrawTiles
 *   - aremath/sm_rando: leveldata_utils.py
 *   - SNESLab wiki: https://sneslab.net/wiki/LZ5
 *   - Metroid Construction: room_data_format
 */
data class MetatileSubtile(
    val tileNum: Int,
    val palette: Int,
    val priority: Boolean,
    val hFlip: Boolean,
    val vFlip: Boolean,
)

class TileGraphics(private val romParser: RomParser) {
    enum class TileSource { AREA, CRE, INVALID }

    enum class MetatileTableSource { VARIABLE, CRE, INVALID }

    private enum class GfxLayout { STANDARD_4BPP, SPLIT_PLANES_4BPP }

    
    companion object {
        // Tileset pointer table: SNES $8F:E6A2, 29 entries × 9 bytes
        const val TILESET_TABLE_SNES = 0x8FE6A2
        const val NUM_TILESETS = 29
        
        // CRE (Common Room Elements) fixed addresses
        const val CRE_GFX_SNES = 0xB98000
        const val CRE_TILE_TABLE_SNES = 0xB9A09D
        
        // Tile counts
        const val VARIABLE_TILE_COUNT = 640   // Tiles 0-639
        const val CRE_TILE_START = 640        // CRE tiles start at index 640
        const val CRE_METATILE_COUNT = 256    // CRE metatile definitions occupy slots 0-255
        const val TOTAL_TILES = 1024          // 0-1023
        const val METATILE_COUNT = 1024       // Number of metatile definitions
        const val AIR_METATILE_INDEX = 0x0FF  // Canonical transparent room air metatile
        
        /** @see RomConstants.BYTES_PER_4BPP_TILE */
        const val BYTES_PER_TILE = RomConstants.BYTES_PER_4BPP_TILE

        // Kraid's room uses tileset/graphics set 27
        const val KRAID_TILESET = 27

        const val CERES_AREA = 6

        // Mode 7 Ceres/Ridley tilesets store 4bpp bitplanes as two global halves:
        // [bp0/bp2 for all tiles][bp1/bp3 for all tiles], not 32-byte per-tile chunks.
        val MODE7_CERES_TILESETS = 17..20

        // Standard CRE graphics placement offset (tiles 640-1023)
        private const val CRE_GFX_OFFSET = 0x5000  // 640 tiles × 32 bytes
        private const val SPLIT_PLANE_OFFSET = TOTAL_TILES * BYTES_PER_TILE / 2

        fun encodeMetatileWord(
            tileNum: Int,
            palette: Int,
            priority: Boolean = false,
            hFlip: Boolean = false,
            vFlip: Boolean = false,
        ): Int {
            return (tileNum.coerceIn(0, TOTAL_TILES - 1) and 0x03FF) or
                ((palette.coerceIn(0, 7) and 7) shl 10) or
                (if (priority) 0x2000 else 0) or
                (if (hFlip) 0x4000 else 0) or
                (if (vFlip) 0x8000 else 0)
        }

        fun decodeMetatileWord(word: Int): MetatileSubtile =
            MetatileSubtile(
                tileNum = word and 0x03FF,
                palette = (word shr 10) and 7,
                priority = (word and 0x2000) != 0,
                hFlip = (word and 0x4000) != 0,
                vFlip = (word and 0x8000) != 0,
            )
    }

    // Cached data per tileset
    private var cachedTilesetId: Int = -1
    private var cachedHasCre: Boolean = false            // Whether CRE graphics were included
    private var rawTileData: ByteArray? = null          // Combined 4bpp tile graphics (var + CRE)
    private var metatiles: Array<IntArray>? = null       // metatile[idx] = 4 sub-tile words
    private var cachedPalette: Array<IntArray>? = null   // 8 palettes × 16 ARGB colors
    private var cachedGfxLayout: GfxLayout = GfxLayout.STANDARD_4BPP
    private var cachedDefinedMetatiles: Int = 0          // Entries actually present in the tile table
    private var cachedCreTableMetatileCount: Int = 0
    private var cachedVarTableStartMetatile: Int = 0
    private var cachedVarTableMetatileCount: Int = 0

    /**
     * Number of metatile slots the loaded tileset's tile table actually
     * defines (CRE + variable). Indices at or beyond this render as the
     * zero-filled placeholder, so generators should avoid them.
     */
    fun definedMetatileCount(): Int = cachedDefinedMetatiles

    /**
     * True when [index] is undefined for the loaded tileset, or when every
     * quadrant uses the sparse CRE placeholder tile ($3FF).
     *
     * Metatile $FF is the canonical room air/empty metatile. It often resolves
     * to fully transparent art while still carrying meaningful collision in
     * vanilla rooms, so it must not be treated as missing graphics.
     *
     * Do not reject metatiles merely because they reference CRE tiles
     * $3C6-$3C9: those tile numbers are also used by visible pipe art in
     * real tilesets, and the room renderer must draw that art.
     */
    fun isPlaceholderMetatile(index: Int): Boolean {
        val metas = metatiles ?: return true
        if (index < 0 || index >= metas.size || index >= cachedDefinedMetatiles) return true
        if (index == AIR_METATILE_INDEX) return false
        if (!cachedHasCre) return false
        return metas[index].all { (it and 0x3FF) == 0x3FF }
    }

    /**
     * Load a complete tileset (graphics + tile table + palette).
     * Returns true if successful.
     *
     * CRE handling (matching SMILE behavior):
     *   - Tile table: if variable table has 1024+ metatiles (8192+ bytes),
     *     it covers all slots — CRE table is NOT prepended.
     *     Otherwise CRE (256 metatiles) is prepended: [CRE 0-255][VAR 256-1023].
     *   - Graphics: CRE normally overlays at offset 0x5000 (tiles 640-1023).
     *     Exceptions:
     *       * Tileset 27 (Kraid) has no CRE overlay.
     *       * Mode 7 Ceres/Ridley tilesets 17-20 use a split-plane 32K layout, so
     *         byte 0x5000 is area tile plane data, not a CRE boundary.
     */
    fun loadTileset(tilesetId: Int, @Suppress("unused") noCre: Boolean = false): Boolean {
        if (tilesetId !in 0 until NUM_TILESETS) return false
        if (tilesetId == cachedTilesetId && rawTileData != null && !noCre) return true

        val entry = romParser.graphicsCatalog.entry(tilesetId) ?: return false
        val tileTablePtr = entry.tileTablePtr
        val gfxPtr = entry.gfxPtr
        val palettePtr = entry.palettePtr

        val varTileTable = romParser.decompressLZ2(tileTablePtr)
        val varGfx = romParser.decompressLZ2(gfxPtr)
        val paletteDecompressed = romParser.decompressLZ2(palettePtr)
        if (varTileTable.size < 8 || varTileTable.size % 8 != 0 ||
            varGfx.size < BYTES_PER_TILE || varGfx.size % BYTES_PER_TILE != 0 ||
            paletteDecompressed.size < 64
        ) {
            return false
        }
        cachedPalette = parsePalette(paletteDecompressed)

        // Tile table: if variable table already covers all 1024 metatiles, use it directly.
        // Otherwise prepend CRE (256 metatiles) to form [CRE][VAR] = 1024 total.
        val varTableCoversAll = varTileTable.size >= METATILE_COUNT * 8
        if (varTableCoversAll) {
            metatiles = parseTileTableRaw(varTileTable)
            cachedDefinedMetatiles = METATILE_COUNT
            cachedCreTableMetatileCount = 0
            cachedVarTableStartMetatile = 0
            cachedVarTableMetatileCount = METATILE_COUNT
        } else {
            val creTileTable = romParser.decompressLZ2(romParser.graphicsCatalog.creTileTablePtr)
            metatiles = parseTileTable(varTileTable, creTileTable)
            cachedCreTableMetatileCount = CRE_METATILE_COUNT
            cachedVarTableStartMetatile = CRE_METATILE_COUNT
            cachedVarTableMetatileCount = minOf(varTileTable.size / 8, METATILE_COUNT - cachedVarTableStartMetatile)
            cachedDefinedMetatiles = minOf(cachedVarTableStartMetatile + cachedVarTableMetatileCount, METATILE_COUNT)
        }

        // Graphics: variable tiles first, CRE overlays at offset 0x5000 (tiles 640-1023).
        //
        // Skip CRE overlay when:
        //  - Mode 7 Ceres/Ridley tilesets use a global split-plane layout where
        //    0x5000 is still area graphics data.
        //  - Variable GFX fills all 1024 tile slots (>= 32K) AND the tile table
        //    doesn't cover all metatiles (CRE metatile defs are prepended and may
        //    reference tiles 640+, e.g. tileset 26 / Kraid's room)
        //  - Tileset 27 (Kraid sprite), per SMILE source
        //  - Caller explicitly requested noCre
        val gfxLayout = if (usesMode7SplitPlaneLayout(tilesetId, varGfx.size)) {
            GfxLayout.SPLIT_PLANES_4BPP
        } else {
            GfxLayout.STANDARD_4BPP
        }
        val varGfxFull = varGfx.size >= TOTAL_TILES * BYTES_PER_TILE
        val skipCre = noCre ||
            gfxLayout == GfxLayout.SPLIT_PLANES_4BPP ||
            tilesetId == KRAID_TILESET ||
            (varGfxFull && !varTableCoversAll)
        val combinedGfx = ByteArray(TOTAL_TILES * BYTES_PER_TILE)
        val varCopyLen = minOf(varGfx.size, combinedGfx.size)
        System.arraycopy(varGfx, 0, combinedGfx, 0, varCopyLen)
        if (!skipCre) {
            val creGfx = romParser.decompressLZ2(romParser.graphicsCatalog.creGfxPtr)
            val copyLen = minOf(creGfx.size, combinedGfx.size - CRE_GFX_OFFSET)
            if (copyLen > 0) {
                System.arraycopy(creGfx, 0, combinedGfx, CRE_GFX_OFFSET, copyLen)
            }
        }
        rawTileData = combinedGfx
        cachedHasCre = !skipCre
        cachedGfxLayout = gfxLayout

        cachedTilesetId = tilesetId
        return true
    }
    
    /**
     * Render a 16x16 metatile to an ARGB pixel array.
     * Returns 256 pixels (16x16) or null if index is invalid.
     */
    /**
     * Render a 16x16 metatile to an ARGB pixel array.
     * Each metatile is 4 sub-tiles arranged: TL(0), TR(1), BL(2), BR(3).
     * 
     * SNES BG tilemap word format (per sub-tile):
     *   VH0PPPTTTTTTTTTT
     *   V = vertical flip (bit 15)
     *   H = horizontal flip (bit 14)
     *   0 = priority (bit 13, ignored for our purposes)
     *   PPP = palette row (bits 10-12)
     *   TTTTTTTTTT = tile number (bits 0-9)
     */
    fun renderMetatile(metatileIndex: Int): IntArray? {
        val metas = metatiles ?: return null
        if (metatileIndex < 0 || metatileIndex >= metas.size) return null
        return renderMetatileWords(metas[metatileIndex])
    }

    fun renderMetatileWords(words: IntArray): IntArray? {
        val pal = cachedPalette ?: return null
        if (rawTileData == null || words.size != 4) return null

        val pixels = IntArray(16 * 16)

        // 4 sub-tiles: TL(0), TR(1), BL(2), BR(3)
        for (quadrant in 0..3) {
            val word = words[quadrant]
            val tileNum = word and 0x03FF
            val paletteIdx = (word shr 10) and 7
            val hFlip = (word shr 14) and 1
            val vFlip = (word shr 15) and 1

            if (tileNum >= TOTAL_TILES) continue

            val baseX = if (quadrant % 2 == 0) 0 else 8
            val baseY = if (quadrant < 2) 0 else 8

            // Decode this 8x8 tile with its specific palette
            val subTilePixels = decode4bppTileWithPalette(tileNum, pal, paletteIdx)

            for (py in 0 until 8) {
                for (px in 0 until 8) {
                    val sx = if (hFlip != 0) 7 - px else px
                    val sy = if (vFlip != 0) 7 - py else py
                    val srcIdx = sy * 8 + sx
                    val dstIdx = (baseY + py) * 16 + (baseX + px)
                    pixels[dstIdx] = subTilePixels[srcIdx]
                }
            }
        }

        return pixels
    }
    
    /**
     * Render all metatiles (0..1023) in index order into a single grid image.
     * Layout: 32 columns × 32 rows of 16×16 metatiles → 512×512 pixels.
     * Returns null if tileset is not loaded.
     */
    fun renderTilesetGrid(): TilesetGridData? {
        if (metatiles == null || cachedTilesetId < 0) return null
        val gridCols = 32
        val gridRows = (METATILE_COUNT + gridCols - 1) / gridCols
        val tilePx = 16
        val width = gridCols * tilePx
        val height = gridRows * tilePx
        val pixels = IntArray(width * height)
        val bg = RomConstants.ROM_BG_COLOR
        pixels.fill(bg)
        for (i in 0 until METATILE_COUNT) {
            val metaPixels = renderMetatile(i) ?: continue
            val col = i % gridCols
            val row = i / gridCols
            val dx = col * tilePx
            val dy = row * tilePx
            for (py in 0 until 16) {
                for (px in 0 until 16) {
                    val dstIdx = (dy + py) * width + (dx + px)
                    val srcIdx = py * 16 + px
                    if (dstIdx in pixels.indices && srcIdx in metaPixels.indices) {
                        pixels[dstIdx] = metaPixels[srcIdx]
                    }
                }
            }
        }
        return TilesetGridData(width, height, pixels, gridCols, gridRows)
    }
    
    /** Get the 8 sub-palettes as ARGB color arrays (8 × 16 colors). Null if not loaded. */
    fun getPalettes(): Array<IntArray>? = cachedPalette?.map { it.copyOf() }?.toTypedArray()

    /** Get the palette index (0-7) used by a specific metatile's sub-tiles. Returns all unique palette indices. */
    fun getMetatilePalettes(metatileIndex: Int): Set<Int> {
        val metas = metatiles ?: return emptySet()
        if (metatileIndex < 0 || metatileIndex >= metas.size) return emptySet()
        return metas[metatileIndex].map { (it shr 10) and 7 }.toSet()
    }

    fun getCachedTilesetId(): Int = cachedTilesetId

    // ─── Tile sheet export/import ──────────────────────────────────────

    /**
     * For each 8x8 tile number (0-1023), find the palette row (0-7)
     * most commonly assigned to it by metatile definitions.
     */
    fun buildTilePaletteMap(): IntArray {
        val metas = metatiles ?: return IntArray(TOTAL_TILES)
        val counts = Array(TOTAL_TILES) { IntArray(8) }
        for (meta in metas) {
            for (word in meta) {
                val tileNum = word and 0x03FF
                val palIdx = (word shr 10) and 7
                if (tileNum < TOTAL_TILES) counts[tileNum][palIdx]++
            }
        }
        return IntArray(TOTAL_TILES) { i ->
            counts[i].indices.maxByOrNull { counts[i][it] } ?: 0
        }
    }

    /**
     * Render a range of 8x8 tiles as an ARGB pixel grid.
     * Returns (pixels, width, height) or null.
     */
    fun renderTileSheet(startTile: Int, numTiles: Int, cols: Int = 16, tilePalMap: IntArray? = null): Triple<IntArray, Int, Int>? {
        val pal = cachedPalette ?: return null
        if (rawTileData == null) return null
        val palMap = tilePalMap ?: buildTilePaletteMap()
        val rows = (numTiles + cols - 1) / cols
        val w = cols * 8
        val h = rows * 8
        val pixels = IntArray(w * h)
        pixels.fill(RomConstants.ROM_BG_COLOR)
        for (i in 0 until numTiles) {
            val tileNum = startTile + i
            if (tileNum >= TOTAL_TILES) break
            val palIdx = if (tileNum < palMap.size) palMap[tileNum] else 0
            val tp = decode4bppTileWithPalette(tileNum, pal, palIdx)
            val cx = (i % cols) * 8; val cy = (i / cols) * 8
            for (py in 0..7) for (px in 0..7) {
                pixels[(cy + py) * w + (cx + px)] = tp[py * 8 + px]
            }
        }
        return Triple(pixels, w, h)
    }

    /**
     * Render a SNES BG nametable (array of 16-bit tilemap words) to an ARGB pixel image.
     * Standard layout: 32 columns × 32 rows of 8×8 tiles = 256×256 pixels.
     * Each word: bits 0-9 = tile number, bits 10-12 = palette, bit 13 = priority,
     * bit 14 = H-flip, bit 15 = V-flip.
     * Returns (pixels, width=256, height=256) or null if tileset not loaded.
     */
    fun renderBgTilemap(tilemapWords: IntArray): Triple<IntArray, Int, Int>? {
        val pal = cachedPalette ?: return null
        if (rawTileData == null) return null
        val cols = 32
        val rows = 32
        val w = cols * 8  // 256
        val h = rows * 8  // 256
        val pixels = IntArray(w * h)
        for (i in tilemapWords.indices) {
            if (i >= cols * rows) break
            val word = tilemapWords[i]
            val tileNum = word and 0x03FF
            val paletteIdx = (word shr 10) and 7
            val hFlip = (word shr 14) and 1
            val vFlip = (word shr 15) and 1
            if (tileNum >= TOTAL_TILES) continue
            val tilePixels = decode4bppTileWithPalette(tileNum, pal, paletteIdx)
            val cx = (i % cols) * 8
            val cy = (i / cols) * 8
            for (py in 0 until 8) {
                for (px in 0 until 8) {
                    val sx = if (hFlip != 0) 7 - px else px
                    val sy = if (vFlip != 0) 7 - py else py
                    val argb = tilePixels[sy * 8 + sx]
                    if (argb != 0) {
                        pixels[(cy + py) * w + (cx + px)] = argb
                    }
                }
            }
        }
        return Triple(pixels, w, h)
    }

    /**
     * Convert an ARGB pixel grid back to raw 4bpp tile data.
     * For each 8x8 tile, uses the palette row assigned by the tileset's metatile table.
     */
    @Suppress("UNUSED_PARAMETER")
    fun importTileSheet(pixels: IntArray, imgWidth: Int, startTile: Int, numTiles: Int, cols: Int = 16): ByteArray {
        val pal = cachedPalette ?: return ByteArray(0)
        val tilePalMap = buildTilePaletteMap()
        val result = ByteArray(numTiles * BYTES_PER_TILE)
        for (i in 0 until numTiles) {
            val cx = (i % cols) * 8; val cy = (i / cols) * 8
            val tilePixels = IntArray(64)
            for (py in 0..7) for (px in 0..7) {
                val si = (cy + py) * imgWidth + (cx + px)
                tilePixels[py * 8 + px] = if (si in pixels.indices) pixels[si] else 0
            }
            val tileNum = startTile + i
            val paletteRow = tilePalMap.getOrNull(tileNum)
                ?: findBestPaletteForTile(tilePixels, pal)
            encode4bppTile(tilePixels, pal[paletteRow], result, i)
        }
        return result
    }

    /** Extract the variable (URE) graphics portion as raw 4bpp bytes. */
    fun getRawVarGfx(): ByteArray? {
        val data = rawTileData ?: return null
        val size = getCreOffset() * BYTES_PER_TILE
        return data.copyOfRange(0, minOf(size, data.size))
    }

    /** Extract the CRE graphics portion as raw 4bpp bytes. */
    fun getRawCreGfx(): ByteArray? {
        val data = rawTileData ?: return null
        val offset = getCreOffset() * BYTES_PER_TILE
        if (offset >= data.size) return null
        return data.copyOfRange(offset, data.size)
    }

    /** Replace variable graphics in the combined tile data. */
    fun applyCustomVarGfx(gfxData: ByteArray) {
        val data = rawTileData ?: return
        val maxLen = minOf(gfxData.size, getCreOffset() * BYTES_PER_TILE, data.size)
        System.arraycopy(gfxData, 0, data, 0, maxLen)
    }

    /** Replace CRE graphics in the combined tile data. */
    fun applyCustomCreGfx(gfxData: ByteArray) {
        val data = rawTileData ?: return
        val offset = getCreOffset() * BYTES_PER_TILE
        if (offset >= data.size) return
        val maxLen = minOf(gfxData.size, data.size - offset)
        System.arraycopy(gfxData, 0, data, offset, maxLen)
    }

    /** CRE tile start index (640 if CRE was included, 1024 otherwise). */
    fun getCreOffset(): Int = if (cachedHasCre) CRE_TILE_START else TOTAL_TILES

    /** Number of variable tiles. */
    fun getVarTileCount(): Int = getCreOffset()

    /** Number of CRE tiles. */
    fun getCreTileCount(): Int = TOTAL_TILES - getCreOffset()

    fun tileSource(tileNum: Int): TileSource = when {
        tileNum !in 0 until TOTAL_TILES -> TileSource.INVALID
        tileNum >= getCreOffset() -> TileSource.CRE
        else -> TileSource.AREA
    }

    fun tileSourceLabel(tileNum: Int): String = when (tileSource(tileNum)) {
        TileSource.AREA -> "Area"
        TileSource.CRE -> "CRE"
        TileSource.INVALID -> "Invalid"
    }

    fun metatileTableSource(index: Int): MetatileTableSource = when {
        isCreMetatileIndex(index) -> MetatileTableSource.CRE
        isVariableMetatileIndex(index) -> MetatileTableSource.VARIABLE
        else -> MetatileTableSource.INVALID
    }

    fun metatileTableSourceLabel(index: Int): String = when (metatileTableSource(index)) {
        MetatileTableSource.CRE -> "Shared CRE metatile table"
        MetatileTableSource.VARIABLE -> "Tileset-local variable metatile table"
        MetatileTableSource.INVALID -> "Undefined metatile slot"
    }

    /**
     * Overwrite raw 4bpp tile data at a given tile index.
     * Used to inject boss-specific tiles (e.g. Kraid at 0x100) into the
     * combined VRAM tile array, mimicking what the game's AI does at runtime.
     */
    fun injectRawTileData(startTileIndex: Int, data: ByteArray) {
        val dst = rawTileData ?: return
        val byteOffset = startTileIndex * BYTES_PER_TILE
        val copyLen = minOf(data.size, dst.size - byteOffset)
        if (copyLen > 0) System.arraycopy(data, 0, dst, byteOffset, copyLen)
    }

    /** Extract raw 4bpp tile data for a range of tiles. */
    fun extractRawTileData(startTileIndex: Int, tileCount: Int): ByteArray? {
        val src = rawTileData ?: return null
        val byteOffset = startTileIndex * BYTES_PER_TILE
        val byteLen = tileCount * BYTES_PER_TILE
        if (byteOffset + byteLen > src.size) return null
        return src.copyOfRange(byteOffset, byteOffset + byteLen)
    }

    /** Force tileset to re-load from ROM on next call. */
    fun invalidateCache() {
        cachedTilesetId = -1
        cachedHasCre = false
        rawTileData = null
        metatiles = null
        cachedPalette = null
        cachedGfxLayout = GfxLayout.STANDARD_4BPP
    }

    // ─── Pixel-level read / write for inline editor ────────────────────

    /**
     * Read a single pixel's palette colour index (0-15) from an 8x8 tile.
     * Returns -1 if the tile or data is out of range.
     */
    fun readPixelIndex(tileNum: Int, px: Int, py: Int): Int {
        if (tileNum < 0 || tileNum >= TOTAL_TILES) return -1
        val bit = 7 - px
        val row = read4bppTileRow(tileNum, py) ?: return -1
        val bp0 = (row.bp0 shr bit) and 1
        val bp1 = (row.bp1 shr bit) and 1
        val bp2 = (row.bp2 shr bit) and 1
        val bp3 = (row.bp3 shr bit) and 1
        return bp0 or (bp1 shl 1) or (bp2 shl 2) or (bp3 shl 3)
    }

    /**
     * Write a single pixel's palette colour index (0-15) into an 8x8 tile.
     */
    fun writePixelIndex(tileNum: Int, px: Int, py: Int, colorIdx: Int) {
        val data = rawTileData ?: return
        if (tileNum < 0 || tileNum >= TOTAL_TILES) return
        if (px !in 0..7 || py !in 0..7) return
        val bit = 7 - px
        val mask = (1 shl bit).inv()
        fun setBit(byteOff: Int, bitVal: Int) {
            data[byteOff] = ((data[byteOff].toInt() and 0xFF and mask) or ((bitVal and 1) shl bit)).toByte()
        }
        when (cachedGfxLayout) {
            GfxLayout.STANDARD_4BPP -> {
                val offset = tileNum * BYTES_PER_TILE
                if (offset + BYTES_PER_TILE > data.size) return
                setBit(offset + py * 2, colorIdx)
                setBit(offset + py * 2 + 1, colorIdx shr 1)
                setBit(offset + py * 2 + 16, colorIdx shr 2)
                setBit(offset + py * 2 + 17, colorIdx shr 3)
            }
            GfxLayout.SPLIT_PLANES_4BPP -> {
                val lowOffset = tileNum * 16
                val highOffset = SPLIT_PLANE_OFFSET + tileNum * 16
                if (lowOffset + 16 > data.size || highOffset + 16 > data.size) return
                setBit(lowOffset + py, colorIdx)
                setBit(highOffset + py, colorIdx shr 1)
                setBit(lowOffset + 8 + py, colorIdx shr 2)
                setBit(highOffset + 8 + py, colorIdx shr 3)
            }
        }
    }

    /**
     * Read the raw 4bpp index grid (8×8) for a tile.  Returns null on error.
     */
    fun readTileIndices(tileNum: Int): IntArray? {
        if (rawTileData == null || tileNum < 0 || tileNum >= TOTAL_TILES) return null
        val out = IntArray(64)
        for (y in 0..7) for (x in 0..7) out[y * 8 + x] = readPixelIndex(tileNum, x, y)
        return out
    }

    /**
     * Get the SNES BGR555 value for a palette entry.
     * Returns -1 if palette is not loaded.
     */
    fun getSnesBgr555(palRow: Int, colIdx: Int): Int {
        val pal = cachedPalette ?: return -1
        if (palRow !in pal.indices || colIdx !in 0..15) return -1
        val argb = pal[palRow][colIdx]
        val r = ((argb shr 16) and 0xFF) / 8
        val g = ((argb shr 8) and 0xFF) / 8
        val b = (argb and 0xFF) / 8
        return (b shl 10) or (g shl 5) or r
    }

    /**
     * Set a palette entry from an SNES BGR555 value.
     */
    fun setPaletteEntry(palRow: Int, colIdx: Int, bgr555: Int) {
        val pal = cachedPalette ?: return
        if (palRow !in pal.indices || colIdx !in 0..15) return
        pal[palRow][colIdx] = bgr555ToArgb(bgr555)
    }

    /** Get the raw palette data as BGR555 values. */
    fun getRawPaletteData(): ByteArray? {
        cachedPalette ?: return null
        val data = ByteArray(8 * 16 * 2)
        for (row in 0..7) for (col in 0..15) {
            val bgr555 = getSnesBgr555(row, col)
            val offset = (row * 16 + col) * 2
            data[offset] = (bgr555 and 0xFF).toByte()
            data[offset + 1] = ((bgr555 shr 8) and 0xFF).toByte()
        }
        return data
    }

    /** Get a metatile's sub-tile definitions (4 words: TL, TR, BL, BR). */
    fun getMetatileWords(metatileIndex: Int): IntArray? {
        val metas = metatiles ?: return null
        if (metatileIndex !in metas.indices) return null
        return metas[metatileIndex].copyOf()
    }

    fun setMetatileWords(metatileIndex: Int, words: IntArray): Boolean {
        val metas = metatiles ?: return false
        if (metatileIndex !in metas.indices || words.size != 4) return false
        metas[metatileIndex] = IntArray(4) { words[it] and 0xFFFF }
        return true
    }

    fun setMetatileWord(metatileIndex: Int, quadrant: Int, word: Int): Boolean {
        val metas = metatiles ?: return false
        if (metatileIndex !in metas.indices || quadrant !in 0..3) return false
        metas[metatileIndex][quadrant] = word and 0xFFFF
        return true
    }

    fun variableMetatileStart(): Int = cachedVarTableStartMetatile

    fun variableMetatileCount(): Int = cachedVarTableMetatileCount

    fun creMetatileCount(): Int = cachedCreTableMetatileCount

    fun isCreMetatileIndex(index: Int): Boolean =
        cachedHasCre && index in 0 until cachedCreTableMetatileCount

    fun isVariableMetatileIndex(index: Int): Boolean =
        index in cachedVarTableStartMetatile until (cachedVarTableStartMetatile + cachedVarTableMetatileCount)

    fun getRawVarTileTable(): ByteArray? =
        exportTileTableRange(cachedVarTableStartMetatile, cachedVarTableMetatileCount)

    fun getRawCreTileTable(): ByteArray? =
        if (cachedCreTableMetatileCount > 0) exportTileTableRange(0, cachedCreTableMetatileCount) else null

    fun applyCustomVarTileTable(rawTable: ByteArray): Boolean =
        applyTileTableRange(rawTable, cachedVarTableStartMetatile, cachedVarTableMetatileCount)

    fun applyCustomCreTileTable(rawTable: ByteArray): Boolean =
        if (cachedCreTableMetatileCount > 0) applyTileTableRange(rawTable, 0, cachedCreTableMetatileCount) else false

    /**
     * Read palette index (0-15) at a pixel in the 16x16 metatile.
     * screenPx, screenPy in 0..15. Maps to correct 8x8 sub-tile and accounts for flips.
     */
    fun readMetatilePixel(metatileIndex: Int, screenPx: Int, screenPy: Int): Int {
        val metas = metatiles ?: return 0
        if (metatileIndex !in metas.indices || screenPx !in 0..15 || screenPy !in 0..15) return 0
        val quadrant = (screenPx / 8) + 2 * (screenPy / 8)
        val word = metas[metatileIndex][quadrant]
        val tileNum = word and 0x03FF
        val hFlip = (word shr 14) and 1
        val vFlip = (word shr 15) and 1
        val lx = screenPx % 8
        val ly = screenPy % 8
        val tx = if (hFlip != 0) 7 - lx else lx
        val ty = if (vFlip != 0) 7 - ly else ly
        return readPixelIndex(tileNum, tx, ty)
    }

    /**
     * Write palette index at a pixel in the 16x16 metatile.
     * Maps to correct 8x8 sub-tile and accounts for flips.
     */
    fun writeMetatilePixel(metatileIndex: Int, screenPx: Int, screenPy: Int, colorIdx: Int) {
        val metas = metatiles ?: return
        if (metatileIndex !in metas.indices || screenPx !in 0..15 || screenPy !in 0..15) return
        val quadrant = (screenPx / 8) + 2 * (screenPy / 8)
        val word = metas[metatileIndex][quadrant]
        val tileNum = word and 0x03FF
        val hFlip = (word shr 14) and 1
        val vFlip = (word shr 15) and 1
        val lx = screenPx % 8
        val ly = screenPy % 8
        val tx = if (hFlip != 0) 7 - lx else lx
        val ty = if (vFlip != 0) 7 - ly else ly
        writePixelIndex(tileNum, tx, ty, colorIdx)
    }

    /** Get palette row for a pixel in the metatile (for color display). */
    fun getMetatilePixelPaletteRow(metatileIndex: Int, screenPx: Int, screenPy: Int): Int {
        val metas = metatiles ?: return 0
        if (metatileIndex !in metas.indices || screenPx !in 0..15 || screenPy !in 0..15) return 0
        val quadrant = (screenPx / 8) + 2 * (screenPy / 8)
        val word = metas[metatileIndex][quadrant]
        return (word shr 10) and 7
    }

    /** Map metatile screen coords to (tileNum, localX, localY) for undo/redo. */
    fun metatilePixelToTileCoords(metatileIndex: Int, screenPx: Int, screenPy: Int): Triple<Int, Int, Int>? {
        val metas = metatiles ?: return null
        if (metatileIndex !in metas.indices || screenPx !in 0..15 || screenPy !in 0..15) return null
        val quadrant = (screenPx / 8) + 2 * (screenPy / 8)
        val word = metas[metatileIndex][quadrant]
        val tileNum = word and 0x03FF
        val hFlip = (word shr 14) and 1
        val vFlip = (word shr 15) and 1
        val lx = screenPx % 8
        val ly = screenPy % 8
        val tx = if (hFlip != 0) 7 - lx else lx
        val ty = if (vFlip != 0) 7 - ly else ly
        return Triple(tileNum, tx, ty)
    }

    /** Render palette as ARGB pixels: 8 rows × 16 cols, each swatch cellSize×cellSize. */
    fun renderPaletteImage(cellSize: Int = 12): Triple<IntArray, Int, Int>? {
        val pal = cachedPalette ?: return null
        val w = 16 * cellSize; val h = 8 * cellSize
        val pixels = IntArray(w * h)
        for (row in 0..7) for (col in 0..15) {
            val color = pal[row][col]
            val bx = col * cellSize; val by = row * cellSize
            for (py in 0 until cellSize) for (px in 0 until cellSize) {
                pixels[(by + py) * w + (bx + px)] = color
            }
        }
        return Triple(pixels, w, h)
    }

    // ─── Internal helpers ──────────────────────────────────────────────

    private data class TileRowBitplanes(
        val bp0: Int,
        val bp1: Int,
        val bp2: Int,
        val bp3: Int,
    )

    private fun usesMode7SplitPlaneLayout(tilesetId: Int, gfxSize: Int): Boolean =
        tilesetId in MODE7_CERES_TILESETS && gfxSize >= TOTAL_TILES * BYTES_PER_TILE

    private fun read4bppTileRow(tileNum: Int, row: Int): TileRowBitplanes? {
        val data = rawTileData ?: return null
        if (tileNum !in 0 until TOTAL_TILES || row !in 0..7) return null

        return when (cachedGfxLayout) {
            GfxLayout.STANDARD_4BPP -> {
                val offset = tileNum * BYTES_PER_TILE
                if (offset + BYTES_PER_TILE > data.size) return null
                TileRowBitplanes(
                    bp0 = data[offset + row * 2].toInt() and 0xFF,
                    bp1 = data[offset + row * 2 + 1].toInt() and 0xFF,
                    bp2 = data[offset + row * 2 + 16].toInt() and 0xFF,
                    bp3 = data[offset + row * 2 + 17].toInt() and 0xFF,
                )
            }
            GfxLayout.SPLIT_PLANES_4BPP -> {
                val lowOffset = tileNum * 16
                val highOffset = SPLIT_PLANE_OFFSET + tileNum * 16
                if (lowOffset + 16 > data.size || highOffset + 16 > data.size) return null
                TileRowBitplanes(
                    bp0 = data[lowOffset + row].toInt() and 0xFF,
                    bp1 = data[highOffset + row].toInt() and 0xFF,
                    bp2 = data[lowOffset + 8 + row].toInt() and 0xFF,
                    bp3 = data[highOffset + 8 + row].toInt() and 0xFF,
                )
            }
        }
    }

    private fun findBestPaletteForTile(tilePixels: IntArray, palettes: Array<IntArray>): Int {
        var bestPal = 0; var bestScore = Int.MAX_VALUE
        for (p in palettes.indices) {
            var score = 0
            for (argb in tilePixels) {
                if ((argb ushr 24) < 128) continue // transparent
                score += closestColorDist(argb, palettes[p])
            }
            if (score < bestScore) { bestScore = score; bestPal = p }
        }
        return bestPal
    }

    private fun closestColorDist(argb: Int, palette: IntArray): Int {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        var best = Int.MAX_VALUE
        for (i in 1 until palette.size) { // skip index 0 (transparent)
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val d = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (d < best) best = d
        }
        return best
    }

    private fun encode4bppTile(tilePixels: IntArray, palette: IntArray, out: ByteArray, tileIndex: Int) {
        when (cachedGfxLayout) {
            GfxLayout.STANDARD_4BPP -> encodeStandard4bppTile(tilePixels, palette, out, tileIndex * BYTES_PER_TILE)
            GfxLayout.SPLIT_PLANES_4BPP -> encodeSplitPlane4bppTile(tilePixels, palette, out, tileIndex)
        }
    }

    private fun encodeStandard4bppTile(tilePixels: IntArray, palette: IntArray, out: ByteArray, outOffset: Int) {
        if (outOffset < 0 || outOffset + BYTES_PER_TILE > out.size) return
        for (y in 0..7) {
            var bp0 = 0; var bp1 = 0; var bp2 = 0; var bp3 = 0
            for (x in 0..7) {
                val argb = tilePixels[y * 8 + x]
                val ci = if ((argb ushr 24) < 128) 0 else findClosestPaletteIndex(argb, palette)
                val bit = 7 - x
                if (ci and 1 != 0) bp0 = bp0 or (1 shl bit)
                if (ci and 2 != 0) bp1 = bp1 or (1 shl bit)
                if (ci and 4 != 0) bp2 = bp2 or (1 shl bit)
                if (ci and 8 != 0) bp3 = bp3 or (1 shl bit)
            }
            out[outOffset + y * 2] = bp0.toByte()
            out[outOffset + y * 2 + 1] = bp1.toByte()
            out[outOffset + y * 2 + 16] = bp2.toByte()
            out[outOffset + y * 2 + 17] = bp3.toByte()
        }
    }

    private fun encodeSplitPlane4bppTile(tilePixels: IntArray, palette: IntArray, out: ByteArray, tileIndex: Int) {
        val splitOffset = out.size / 2
        val lowOffset = tileIndex * 16
        val highOffset = splitOffset + tileIndex * 16
        if (lowOffset < 0 || lowOffset + 16 > splitOffset || highOffset + 16 > out.size) return

        for (y in 0..7) {
            var bp0 = 0; var bp1 = 0; var bp2 = 0; var bp3 = 0
            for (x in 0..7) {
                val argb = tilePixels[y * 8 + x]
                val ci = if ((argb ushr 24) < 128) 0 else findClosestPaletteIndex(argb, palette)
                val bit = 7 - x
                if (ci and 1 != 0) bp0 = bp0 or (1 shl bit)
                if (ci and 2 != 0) bp1 = bp1 or (1 shl bit)
                if (ci and 4 != 0) bp2 = bp2 or (1 shl bit)
                if (ci and 8 != 0) bp3 = bp3 or (1 shl bit)
            }
            out[lowOffset + y] = bp0.toByte()
            out[highOffset + y] = bp1.toByte()
            out[lowOffset + 8 + y] = bp2.toByte()
            out[highOffset + 8 + y] = bp3.toByte()
        }
    }

    private fun findClosestPaletteIndex(argb: Int, palette: IntArray): Int {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        var bestIdx = 1; var bestDist = Int.MAX_VALUE
        for (i in 1 until palette.size) {
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val d = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (d < bestDist) { bestDist = d; bestIdx = i }
        }
        return bestIdx
    }
    
    /**
     * Parse metatile table from decompressed tile table data.
     * 
     * From SMILE DecompressTtable.vb:
     *   CombineArrays CRETtable, VarTtable, SizeOfCRETtable, SizeOfVarTtable, &H0, SizeOfCRETtable, OutputArray
     * 
     * This means: CRE tile table FIRST (at offset 0), then Variable tile table AFTER.
     * Each entry = 8 bytes = 4 × 16-bit LE words (TL, TR, BL, BR).
     */
    private fun parseTileTable(varTable: ByteArray, creTable: ByteArray): Array<IntArray> {
        val result = Array(METATILE_COUNT) { IntArray(4) }
        copyTileTableEntries(
            source = creTable,
            target = result,
            targetStart = 0,
            maxEntries = CRE_METATILE_COUNT,
        )
        copyTileTableEntries(
            source = varTable,
            target = result,
            targetStart = CRE_METATILE_COUNT,
            maxEntries = METATILE_COUNT - CRE_METATILE_COUNT,
        )
        return result
    }


    private fun parseTileTableRaw(data: ByteArray): Array<IntArray> {
        val result = Array(METATILE_COUNT) { IntArray(4) }
        val entryCount = minOf(data.size / 8, METATILE_COUNT)
        for (i in 0 until entryCount) {
            val offset = i * 8
            for (q in 0..3) {
                val lo = data[offset + q * 2].toInt() and 0xFF
                val hi = data[offset + q * 2 + 1].toInt() and 0xFF
                result[i][q] = (hi shl 8) or lo
            }
        }
        return result
    }

    private fun copyTileTableEntries(
        source: ByteArray,
        target: Array<IntArray>,
        targetStart: Int,
        maxEntries: Int,
    ) {
        val entryCount = minOf(source.size / 8, maxEntries, target.size - targetStart)
        for (i in 0 until entryCount) {
            val offset = i * 8
            for (q in 0..3) {
                val lo = source[offset + q * 2].toInt() and 0xFF
                val hi = source[offset + q * 2 + 1].toInt() and 0xFF
                target[targetStart + i][q] = (hi shl 8) or lo
            }
        }
    }

    private fun exportTileTableRange(startMetatile: Int, metatileCount: Int): ByteArray? {
        val metas = metatiles ?: return null
        if (startMetatile < 0 || metatileCount <= 0 || startMetatile + metatileCount > metas.size) return null
        val out = ByteArray(metatileCount * 8)
        for (i in 0 until metatileCount) {
            val words = metas[startMetatile + i]
            val offset = i * 8
            for (q in 0..3) {
                val word = words[q] and 0xFFFF
                out[offset + q * 2] = (word and 0xFF).toByte()
                out[offset + q * 2 + 1] = ((word shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun applyTileTableRange(rawTable: ByteArray, startMetatile: Int, maxMetatiles: Int): Boolean {
        val metas = metatiles ?: return false
        if (rawTable.isEmpty() || rawTable.size % 8 != 0 || startMetatile < 0 || maxMetatiles <= 0) return false
        val count = minOf(rawTable.size / 8, maxMetatiles, metas.size - startMetatile)
        if (count <= 0) return false
        for (i in 0 until count) {
            val offset = i * 8
            val words = IntArray(4)
            for (q in 0..3) {
                val lo = rawTable[offset + q * 2].toInt() and 0xFF
                val hi = rawTable[offset + q * 2 + 1].toInt() and 0xFF
                words[q] = (hi shl 8) or lo
            }
            metas[startMetatile + i] = words
        }
        cachedDefinedMetatiles = maxOf(cachedDefinedMetatiles, startMetatile + count)
        return true
    }
    
    /**
     * Decode a single 8x8 SNES 4bpp planar tile with a specific palette.
     * 
     * SNES 4bpp format (32 bytes per tile):
     *   Bytes 0-15: Bitplanes 0 & 1 interleaved (row 0: bytes 0,1; row 1: bytes 2,3; ...)
     *   Bytes 16-31: Bitplanes 2 & 3 interleaved (row 0: bytes 16,17; row 1: bytes 18,19; ...)
     */
    private fun decode4bppTileWithPalette(tileNum: Int, palette: Array<IntArray>, paletteIdx: Int): IntArray {
        val pixels = IntArray(64)

        val palIdx = minOf(paletteIdx, palette.size - 1)

        for (y in 0 until 8) {
            val row = read4bppTileRow(tileNum, y) ?: return pixels
            
            for (x in 0 until 8) {
                val bit = 7 - x
                val colorIdx = ((row.bp0 shr bit) and 1) or
                    (((row.bp1 shr bit) and 1) shl 1) or
                    (((row.bp2 shr bit) and 1) shl 2) or
                    (((row.bp3 shr bit) and 1) shl 3)
                
                // Color index 0 = transparent
                if (colorIdx == 0) {
                    pixels[y * 8 + x] = 0x00000000
                } else {
                    pixels[y * 8 + x] = palette[palIdx][colorIdx]
                }
            }
        }
        
        return pixels
    }
    
    /**
     * Parse decompressed palette data into 8 sub-palettes × 16 colors.
     * Each color is BGR555: 0BBBBBGGGGGRRRRR (16-bit LE).
     * Returns array of 8 palettes, each with 16 ARGB colors.
     */
    private fun parsePalette(paletteData: ByteArray): Array<IntArray> {
        val result = Array(8) { IntArray(16) }
        
        for (pal in 0 until 8) {
            for (col in 0 until 16) {
                val offset = (pal * 16 + col) * 2
                if (offset + 1 < paletteData.size) {
                    val lo = paletteData[offset].toInt() and 0xFF
                    val hi = paletteData[offset + 1].toInt() and 0xFF
                    val bgr555 = (hi shl 8) or lo
                    result[pal][col] = bgr555ToArgb(bgr555)
                }
            }
        }
        
        return result
    }
    
    /**
     * Convert BGR555 to ARGB8888.
     * BGR555: 0BBBBBGG GGGRRRRR
     */
    private fun bgr555ToArgb(bgr555: Int): Int {
        val r5 = bgr555 and 0x1F
        val g5 = (bgr555 shr 5) and 0x1F
        val b5 = (bgr555 shr 10) and 0x1F
        // Scale 5-bit to 8-bit: (v << 3) | (v >> 2)
        val r = (r5 shl 3) or (r5 shr 2)
        val g = (g5 shl 3) or (g5 shr 2)
        val b = (b5 shl 3) or (b5 shr 2)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    
}

/**
 * Rendered grid of all metatiles for the current tileset (index order).
 * Used by the tileset preview panel.
 */
data class TilesetGridData(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val gridCols: Int = 32,
    val gridRows: Int = 32
) {
    override fun equals(other: Any?) = other is TilesetGridData &&
        width == other.width && height == other.height && gridCols == other.gridCols && gridRows == other.gridRows &&
        pixels.contentEquals(other.pixels)
    override fun hashCode() = (width * 31 + height) * 31 + pixels.contentHashCode()
}
