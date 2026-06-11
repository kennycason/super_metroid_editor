package com.supermetroid.editor.rom

/**
 * Handles boss/enemy sprite tile graphics for the sprite editor.
 *
 * Super Metroid enemy sprites are stored as 4bpp tile blocks. Boss sprites (Phantoon, Kraid)
 * use LZ5-compressed data in banks $B7/$B9. Regular enemy sprites use raw (uncompressed)
 * 4bpp data at the GRAPHADR address in the species header.
 * Each 8x8 tile = 32 bytes (standard SNES 4bpp interleaved format):
 *   - Bytes  0-15: bitplanes 0+1 interleaved (2 bytes per row × 8 rows)
 *   - Bytes 16-31: bitplanes 2+3 interleaved (2 bytes per row × 8 rows)
 *
 * Tile block addresses confirmed via PhantoonSpriteInvestigateTest (room $CD13 enemy GFX set).
 */
class EnemySpriteGraphics(private val romParser: RomParser) {

    companion object {
        /** @see RomConstants.BYTES_PER_4BPP_TILE */
        const val BYTES_PER_TILE = RomConstants.BYTES_PER_4BPP_TILE

        private const val CROCOMIRE_SPECIES_ID = 0xDDBF
        private const val CROCOMIRE_TILESET_ID = 0x1B
        private const val DRAYGON_TILESET_ID = 0x1C
        private const val CROCOMIRE_BG_ENEMY_TILE_BASE = 0xD0
        private const val DRAYGON_BODY_SPECIES_ID = 0xDE3F
        private const val DRAYGON_BG_ENEMY_TILE_BASE = 0x100
        private const val CROCOMIRE_SKELETON_CHUNK_SIZE_BYTES = 0x200
        private val DRAYGON_SPECIES_IDS = setOf(0xDE3F, 0xDE7F, 0xDEBF, 0xDEFF)
        private val CROCOMIRE_SKELETON_VRAM_WORD_OFFSETS = intArrayOf(
            0x1600, 0x1700, 0x1800, 0x1900, 0x1E00, 0x1F00
        )

        /**
         * One LZ5-compressed block of sprite tiles in the ROM.
         * @param pcAddress PC offset of compressed data
         * @param snesAddress SNES address (for decompressLZ2WithSize)
         * @param vramWordAddr SNES VRAM word destination (informational)
         * @param label Human-readable tag
         */
        data class SpriteBlock(
            val pcAddress: Int,
            val snesAddress: Int,
            val vramWordAddr: Int,
            val label: String
        )

        /**
         * Phantoon sprite tile blocks — verified via room $CD13 enemy GFX set analysis.
         * All 4 Phantoon species ($E4BF body, $E4FF/$E53F/$E57F components) share these.
         *
         * The 5-byte GFX entry format parsed from raw $8F:8D1D data:
         *   Entry 0: [00 03] [B7] [0F 17] → VRAM=0x0300, bank=$B7, src=0x170F → $B7:170F
         *   Entry at +30: [80 03] [B7] [08 18] → VRAM=0x0380, bank=$B7, src=0x1808 → $B7:1808
         */
        val PHANTOON_BLOCKS = listOf(
            SpriteBlock(0x1B970F, 0xB7170F, 0x0300, "Phantoon Tiles A"),
            SpriteBlock(0x1B9808, 0xB71808, 0x0380, "Phantoon Tiles B")
        )

        /**
         * Kraid sprite tile block — 128 tiles LZ-compressed in bank $B9.
         * Loaded by Kraid_SetupGfxWithTilePrioClear ($A7:AAC6) to $7E:4000.
         */
        val KRAID_BLOCKS = listOf(
            SpriteBlock(0x1CFA38, 0xB9FA38, 0x0100, "Kraid Tiles")
        )

        /**
         * Complete 16-color SNES palette for Phantoon sprites, derived from
         * all 4 component PNGs (E4BF, E4FF, E53F, E57F). Index 0 = transparent.
         * The index order matches the sorted-brightness order found across all PNGs.
         * This palette is used for tile sheet rendering and ARGB→4bpp conversion.
         */
        val PHANTOON_PALETTE = intArrayOf(
            0x00000000,             // 0: transparent
            0xff181800.toInt(),     // 1: very dark olive
            0xff303000.toInt(),     // 2: dark olive
            0xff404008.toInt(),     // 3: olive
            0xff484810.toInt(),     // 4: olive (from E4FF/E53F/E57F)
            0xff585820.toInt(),     // 5: medium olive
            0xff686830.toInt(),     // 6: olive-green (from E4FF/E53F/E57F)
            0xff808040.toInt(),     // 7: yellow-olive
            0xff909058.toInt(),     // 8: light olive (E4BF only)
            0xffa8a868.toInt(),     // 9: pale olive (from E4FF/E53F/E57F)
            0xffa8a870.toInt(),     // 10: pale olive (similar)
            0xffd8d888.toInt(),     // 11: light yellow
            0xfff8f8f8.toInt(),     // 12: white (from E4FF/E53F/E57F)
            0xff500000.toInt(),     // 13: dark red
            0xffa00030.toInt(),     // 14: red-pink
            0xffe80070.toInt()      // 15: bright pink
        )

        /**
         * Known tile block addresses for supported enemies.
         * Each entry: species ID → list of SpriteBlocks.
         */
        val ENEMY_TILE_BLOCKS = mapOf(
            0xE4BF to PHANTOON_BLOCKS,
            0xE2BF to KRAID_BLOCKS,
        )

        /**
         * Enemy entries for the sprite editor. Names are derived from the
         * central ENEMY_NAMES map in RomParser to avoid duplication.
         * Only speciesId and category are specified here; name comes from
         * RomParser.enemyName(speciesId) at access time.
         */
        data class EnemySpriteEntry(
            val speciesId: Int,
            val category: String = "Enemy"
        ) {
            val name: String get() = RomParser.enemyName(speciesId)
        }

        /** Category assignments for the sprite editor list. */
        private val EDITOR_ENEMY_CATEGORIES = mapOf(
            // Bosses
            0xE4BF to "Boss", 0xE4FF to "Boss", 0xE53F to "Boss", 0xE57F to "Boss",
            0xE2BF to "Boss", 0xDDBF to "Boss", 0xDE3F to "Boss", 0xDE7F to "Boss",
            0xDEBF to "Boss", 0xDEFF to "Boss", 0xE13F to "Boss", 0xE17F to "Boss",
            0xEC3F to "Boss", 0xEC7F to "Boss", 0xEEBF to "Boss", 0xE27F to "Boss",
            // Mini-Bosses
            0xDF3F to "Mini-Boss", 0xF293 to "Mini-Boss", 0xE0FF to "Mini-Boss",
            0xEEFF to "Mini-Boss", 0xEF3F to "Mini-Boss", 0xEF7F to "Mini-Boss",
            0xEFBF to "Mini-Boss", 0xED3F to "Mini-Boss",
            // Space Pirates
            0xF353 to "Space Pirate", 0xF413 to "Space Pirate", 0xF453 to "Space Pirate",
            0xF493 to "Space Pirate", 0xF593 to "Space Pirate", 0xF613 to "Space Pirate",
            0xF653 to "Space Pirate", 0xF693 to "Space Pirate", 0xF6D3 to "Space Pirate",
            0xF713 to "Space Pirate", 0xF753 to "Space Pirate", 0xF793 to "Space Pirate",
            // Mechanisms
            0xD4FF to "Mechanism", 0xD53F to "Mechanism", 0xD57F to "Mechanism",
            0xD5BF to "Mechanism", 0xD5FF to "Mechanism", 0xF0BF to "Mechanism",
        )

        /** Ordered list of species IDs for the sprite editor. */
        private val EDITOR_ENEMY_IDS = listOf(
            // Bosses
            0xE4BF, 0xE4FF, 0xE53F, 0xE57F, 0xE2BF, 0xDDBF,
            0xDE3F, 0xDE7F, 0xDEBF, 0xDEFF, 0xE13F, 0xE17F,
            0xEC3F, 0xEC7F, 0xEEBF, 0xE27F,
            // Mini-Bosses
            0xDF3F, 0xF293, 0xE0FF, 0xEEFF, 0xEF3F, 0xEF7F, 0xEFBF, 0xED3F,
            // Wall Crawlers
            0xDCFF, 0xDC7F, 0xDC3F, 0xDCBF, 0xDD3F,
            // Hoppers
            0xD93F, 0xD97F, 0xD9BF, 0xD9FF, 0xDABF, 0xDA3F, 0xDD7F,
            // Flyers
            0xD7FF, 0xD83F, 0xDB7F, 0xD87F, 0xD8BF, 0xD63F, 0xDA7F, 0xDB3F,
            0xD6BF, 0xE9FF, 0xE8FF, 0xD0FF,
            // Kihunters / Boulder
            0xDFBF, 0xDFFF, 0xE03F,
            // Rippers
            0xD3FF, 0xD43F, 0xD47F,
            // Stationary / Plants
            0xCFFF, 0xCEBF, 0xCEFF, 0xCF3F, 0xCF7F, 0xCFBF, 0xD03F,
            0xD3BF, 0xD4BF, 0xDBBF, 0xDBFF, 0xE6BF, 0xE6FF, 0xE73F,
            0xE7BF, 0xE7FF, 0xEA7F, 0xE87F,
            // Aquatic / Maridia
            0xD77F, 0xD7BF, 0xD6FF, 0xD67F, 0xE5FF, 0xE63F,
            0xE93F, 0xEA3F, 0xE9BF,
            // Norfair
            0xD33F, 0xD37F, 0xD23F, 0xD27F, 0xD2BF, 0xD2FF,
            0xE07F, 0xE0BF, 0xE83F, 0xE8BF,
            // Spawners
            0xE97F, 0xF193, 0xF1D3, 0xF213, 0xF253, 0xF07F,
            // Space Pirates
            0xF353, 0xF413, 0xF453, 0xF493, 0xF593, 0xF613,
            0xF653, 0xF693, 0xF6D3, 0xF713, 0xF753, 0xF793,
            // Hachi (Kihunter bees)
            0xEABF, 0xEAFF, 0xEB3F, 0xEB7F, 0xEBBF, 0xEBFF,
            // Friendly / Misc
            0xE5BF, 0xD07F, 0xD0BF, 0xD13F, 0xD17F, 0xD1BF, 0xE1BF,
            // Mechanisms
            0xD4FF, 0xD53F, 0xD57F, 0xD5BF, 0xD5FF, 0xF0BF,
        )

        val EDITOR_ENEMIES: List<EnemySpriteEntry> = EDITOR_ENEMY_IDS.map { id ->
            EnemySpriteEntry(id, EDITOR_ENEMY_CATEGORIES[id] ?: "Enemy")
        }

        /**
         * Read a 16-color ARGB palette from a species header.
         *
         * The game's ProcessEnemyTilesets ($A0:8D64) loads exactly 32 bytes
         * (1 palette row) from $(bank):$(palette_ptr) — i.e. row 0:
         *   memcpy(&target_palettes[...], RomPtrWithBank(ED->bank, ED->palette_ptr), 32);
         */
        fun readEnemyPalette(romParser: RomParser, speciesId: Int): IntArray? {
            val rom = romParser.getRomData()
            val headerPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            if (headerPc < 0 || headerPc + 0x0D > rom.size) return null
            val palPtr = readU16(rom, headerPc + 2)
            val aiBank = readU8(rom, headerPc + 0x0C)

            val palSnes = (aiBank shl 16) or (palPtr and 0xFFFF)
            val palPc = romParser.snesToPc(palSnes)
            if (palPc < 0 || palPc + 32 > rom.size) return null

            val pal = IntArray(RomConstants.COLORS_PER_PALETTE)
            pal[0] = 0x00000000
            for (i in 1 until RomConstants.COLORS_PER_PALETTE) {
                val bgr = readU16(rom, palPc + i * 2)
                pal[i] = snesColorToArgb(bgr)
            }
            return pal
        }

        /**
         * Read species header stats.
         * @return Triple(tileDataSize, hp, damage) or null
         */
        fun readSpeciesStats(romParser: RomParser, speciesId: Int): Triple<Int, Int, Int>? {
            val rom = romParser.getRomData()
            val pc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            if (pc < 0 || pc + 8 > rom.size) return null
            val rawTileSize = readU16(rom, pc)
            val tileSize = rawTileSize and 0x7FFF // bit 15 is a VRAM offset flag, not size
            val hp = readU16(rom, pc + 4)
            val damage = readU16(rom, pc + 6)
            return Triple(tileSize, hp, damage)
        }

        /**
         * Read the GRAPHADR (graphics address) from a species header.
         * Located at species header +$36 (16-bit LE offset) and +$38 (bank byte).
         * This points to raw (uncompressed) 4bpp tile data in ROM.
         * The game copies exactly `tileDataSize & 0x7FFF` bytes from this address into VRAM.
         *
         * @return SpriteBlock with pcAddress and snesAddress, or null
         */
        fun readGraphicsBlock(romParser: RomParser, speciesId: Int): SpriteBlock? {
            val rom = romParser.getRomData()
            val pc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            if (pc < 0 || pc + 0x39 > rom.size) return null
            val gfxOffset = readU16(rom, pc + 0x36)
            val gfxBank = readU8(rom, pc + 0x38)
            if (gfxBank == 0 && gfxOffset == 0) return null
            val snesAddr = (gfxBank shl 16) or gfxOffset
            val pcAddr = romParser.snesToPc(snesAddr)
            if (pcAddr < 0 || pcAddr >= rom.size) return null
            return SpriteBlock(pcAddr, snesAddr, 0, "Tiles")
        }

        /**
         * Load enemy tile data directly from ROM using GRAPHADR.
         * The GRAPHADR field points to raw (uncompressed) 4bpp tile data in ROM.
         * We copy exactly tileDataSize bytes starting at the GRAPHADR address.
         * @return raw 4bpp tile bytes (tileDataSize bytes) or null
         */
        fun loadEnemyTileData(romParser: RomParser, speciesId: Int): ByteArray? {
            val stats = readSpeciesStats(romParser, speciesId) ?: return null
            val tileDataSize = stats.first
            if (tileDataSize <= 0) return null
            val block = readGraphicsBlock(romParser, speciesId) ?: return null
            val rom = romParser.getRomData()
            if (block.pcAddress + tileDataSize > rom.size) return null
            return rom.copyOfRange(block.pcAddress, block.pcAddress + tileDataSize)
        }

        /**
         * Build tile data for pose rendering when the game composes sprites from
         * multiple VRAM sources. Most enemies render directly from their raw enemy
         * graphics; Crocomire's BG2 body also references room tileset $1B, while
         * its enemy graphics are injected at physical room/VRAM tile $D0. Crocomire's
         * OAM child spritemaps use those same physical tile IDs; SNES OBJ palette and
         * name-table bits are not additional editor tile-index offsets.
         */
        fun loadEnemyRenderTileData(
            romParser: RomParser,
            speciesId: Int,
            enemyTileData: ByteArray? = null
        ): ByteArray? {
            val rawEnemyTiles = enemyTileData ?: loadEnemyTileData(romParser, speciesId) ?: return null
            if (speciesId in DRAYGON_SPECIES_IDS) {
                val draygonTiles = if (speciesId == DRAYGON_BODY_SPECIES_ID) {
                    rawEnemyTiles
                } else {
                    loadEnemyTileData(romParser, DRAYGON_BODY_SPECIES_ID) ?: rawEnemyTiles
                }
                val dest = DRAYGON_BG_ENEMY_TILE_BASE * BYTES_PER_TILE
                val out = ByteArray(dest + draygonTiles.size)
                draygonTiles.copyInto(out, destinationOffset = dest)
                return out
            }
            if (speciesId != CROCOMIRE_SPECIES_ID) return rawEnemyTiles

            val tileGraphics = TileGraphics(romParser)
            if (!tileGraphics.loadTileset(CROCOMIRE_TILESET_ID)) return rawEnemyTiles
            val roomTileData = tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES) ?: rawEnemyTiles
            tileGraphics.injectRawTileData(CROCOMIRE_BG_ENEMY_TILE_BASE, rawEnemyTiles)
            return tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES) ?: roomTileData
        }

        fun loadCrocomireRoomTileData(romParser: RomParser): ByteArray? {
            val tileGraphics = TileGraphics(romParser)
            if (!tileGraphics.loadTileset(CROCOMIRE_TILESET_ID)) return null
            return tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES)
        }

        fun loadDraygonRoomTileData(romParser: RomParser): ByteArray? {
            val tileGraphics = TileGraphics(romParser)
            if (!tileGraphics.loadTileset(DRAYGON_TILESET_ID)) return null
            return tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES)
        }

        /**
         * Crocomire's death sequence DMA-loads six 16-tile skeleton chunks over
         * sprite VRAM. Apply those chunks to an already composed Crocomire render
         * tile buffer so corpse/skeleton poses can render without disturbing live
         * Crocomire poses.
         */
        fun applyCrocomireSkeletonTileData(
            romParser: RomParser,
            renderTileData: ByteArray
        ): ByteArray? {
            val stats = readSpeciesStats(romParser, CROCOMIRE_SPECIES_ID) ?: return null
            val block = readGraphicsBlock(romParser, CROCOMIRE_SPECIES_ID) ?: return null
            val rom = romParser.getRomData()
            val skeletonPc = block.pcAddress + stats.first
            val totalSkeletonBytes = CROCOMIRE_SKELETON_CHUNK_SIZE_BYTES * CROCOMIRE_SKELETON_VRAM_WORD_OFFSETS.size
            if (skeletonPc < 0 || skeletonPc + totalSkeletonBytes > rom.size) return null

            val out = renderTileData.copyOf()
            for ((chunkIndex, vramWordOffset) in CROCOMIRE_SKELETON_VRAM_WORD_OFFSETS.withIndex()) {
                val destTile = vramWordOffset / 0x10
                val dest = destTile * BYTES_PER_TILE
                val src = skeletonPc + chunkIndex * CROCOMIRE_SKELETON_CHUNK_SIZE_BYTES
                if (dest + CROCOMIRE_SKELETON_CHUNK_SIZE_BYTES > out.size) return null
                rom.copyInto(
                    destination = out,
                    destinationOffset = dest,
                    startIndex = src,
                    endIndex = src + CROCOMIRE_SKELETON_CHUNK_SIZE_BYTES
                )
            }
            return out
        }

        /** Extract a ≤16-color palette from an ARGB pixel array (index 0 = transparent). */
        fun extractPaletteFromArgb(pixels: IntArray): IntArray {
            val palette = IntArray(16)
            palette[0] = 0x00000000
            var idx = 1
            for (argb in pixels) {
                if ((argb ushr 24) and 0xFF < 128) continue
                val opaque = argb or (0xFF shl 24)
                if (opaque !in palette && idx < 16) {
                    palette[idx++] = opaque
                }
                if (idx >= 16) break
            }
            return palette
        }

        /** SNES BGR555 → ARGB */
        fun snesColorToArgb(bgr555: Int): Int {
            val r = ((bgr555 and 0x001F) * 255 + 15) / 31
            val g = (((bgr555 shr 5) and 0x001F) * 255 + 15) / 31
            val b = (((bgr555 shr 10) and 0x001F) * 255 + 15) / 31
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** ARGB → SNES BGR555 */
        fun argbToSnesColor(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return ((r * 31 + 127) / 255) or
                (((g * 31 + 127) / 255) shl 5) or
                (((b * 31 + 127) / 255) shl 10)
        }
    }

    /** Mutable working tile data per block. null = not loaded. */
    private var rawBlocks: MutableList<ByteArray>? = null

    /** Load and LZ5-decompress tile blocks from the ROM. Returns false on failure. */
    fun load(blocks: List<SpriteBlock>): Boolean {
        return try {
            rawBlocks = blocks.map { block ->
                romParser.decompressLZ5AtPc(block.pcAddress)
            }.toMutableList()
            true
        } catch (e: Exception) {
            rawBlocks = null
            false
        }
    }

    /**
     * Load from already-decompressed raw 4bpp byte arrays (e.g. stored in project).
     * Each element of rawBlocks corresponds to one SpriteBlock.
     */
    fun loadFromRaw(blocks: List<ByteArray>) {
        rawBlocks = blocks.map { it.copyOf() }.toMutableList()
    }

    /**
     * Load blocks from ROM, then override individual blocks with custom bytes
     * where they exist in the provided custom map (key = block index).
     */
    fun loadWithOverrides(romBlocks: List<SpriteBlock>, customRaw: Map<Int, ByteArray>): Boolean {
        if (!load(romBlocks)) return false
        val blocks = rawBlocks ?: return false
        for ((i, raw) in customRaw) {
            if (i < blocks.size) blocks[i] = raw.copyOf()
        }
        return true
    }

    /** Total number of 8x8 tiles across all loaded blocks. */
    fun getTileCount(): Int =
        rawBlocks?.sumOf { it.size / BYTES_PER_TILE } ?: 0

    /** Number of tiles in block [blockIndex]. */
    fun getTileCountInBlock(blockIndex: Int): Int =
        rawBlocks?.getOrNull(blockIndex)?.let { it.size / BYTES_PER_TILE } ?: 0

    private fun resolveGlobalTile(globalTile: Int): Triple<Int, ByteArray, Int>? {
        val blocks = rawBlocks ?: return null
        var remaining = globalTile
        for ((bi, block) in blocks.withIndex()) {
            val count = block.size / BYTES_PER_TILE
            if (remaining < count) return Triple(bi, block, remaining)
            remaining -= count
        }
        return null
    }

    /** Read pixel palette index (0–15) from a global tile number. */
    fun readPixelIndex(globalTile: Int, px: Int, py: Int): Int {
        val (_, block, localTile) = resolveGlobalTile(globalTile) ?: return 0
        val offset = localTile * BYTES_PER_TILE
        if (offset + BYTES_PER_TILE > block.size) return 0
        val bit = 7 - px
        val bp0 = (block[offset + py * 2].toInt() shr bit) and 1
        val bp1 = (block[offset + py * 2 + 1].toInt() shr bit) and 1
        val bp2 = (block[offset + py * 2 + 16].toInt() shr bit) and 1
        val bp3 = (block[offset + py * 2 + 17].toInt() shr bit) and 1
        return bp0 or (bp1 shl 1) or (bp2 shl 2) or (bp3 shl 3)
    }

    /** Write palette index (0–15) into the working tile data. */
    fun writePixelIndex(globalTile: Int, px: Int, py: Int, colorIdx: Int) {
        val (_, block, localTile) = resolveGlobalTile(globalTile) ?: return
        val offset = localTile * BYTES_PER_TILE
        if (offset + BYTES_PER_TILE > block.size) return
        val bit = 7 - px
        fun setBit(byteOffset: Int, v: Int) {
            val cur = block[offset + byteOffset].toInt() and 0xFF
            block[offset + byteOffset] = if (v != 0) (cur or (1 shl bit)).toByte()
                                         else (cur and (1 shl bit).inv()).toByte()
        }
        setBit(py * 2,       colorIdx and 1)
        setBit(py * 2 + 1,  (colorIdx shr 1) and 1)
        setBit(py * 2 + 16, (colorIdx shr 2) and 1)
        setBit(py * 2 + 17, (colorIdx shr 3) and 1)
    }

    /**
     * Render all loaded tiles as an ARGB pixel grid arranged in [cols] columns.
     * Palette index 0 = transparent. Returns (pixels, width, height) or null if not loaded.
     */
    fun renderSheet(palette: IntArray, cols: Int = 8): Triple<IntArray, Int, Int>? {
        val blocks = rawBlocks ?: return null
        val total = getTileCount()
        if (total == 0) return null
        val rows = (total + cols - 1) / cols
        val w = cols * 8
        val h = rows * 8
        val pixels = IntArray(w * h)

        var globalTile = 0
        for (block in blocks) {
            val blockTileCount = block.size / BYTES_PER_TILE
            for (t in 0 until blockTileCount) {
                val tileOffset = t * BYTES_PER_TILE
                val col = globalTile % cols
                val row = globalTile / cols
                val baseX = col * 8
                val baseY = row * 8
                for (py in 0 until 8) {
                    val bp0 = block[tileOffset + py * 2].toInt() and 0xFF
                    val bp1 = block[tileOffset + py * 2 + 1].toInt() and 0xFF
                    val bp2 = block[tileOffset + py * 2 + 16].toInt() and 0xFF
                    val bp3 = block[tileOffset + py * 2 + 17].toInt() and 0xFF
                    for (px in 0 until 8) {
                        val bit = 7 - px
                        val ci = ((bp0 shr bit) and 1) or
                            (((bp1 shr bit) and 1) shl 1) or
                            (((bp2 shr bit) and 1) shl 2) or
                            (((bp3 shr bit) and 1) shl 3)
                        pixels[(baseY + py) * w + (baseX + px)] =
                            if (ci == 0) 0x00000000 else (palette[ci.coerceIn(0, palette.size - 1)] or (0xFF shl 24))
                    }
                }
                globalTile++
            }
        }
        return Triple(pixels, w, h)
    }

    /** Return a copy of each raw block's 4bpp byte data. */
    fun getRawBlocks(): List<ByteArray>? = rawBlocks?.map { it.copyOf() }

    /**
     * Re-encode an ARGB pixel grid back into the loaded tile blocks using nearest-color
     * palette matching. Call [load] or [loadFromRaw] before this.
     */
    fun importFromArgb(pixels: IntArray, w: Int, h: Int, palette: IntArray, cols: Int = 8) {
        val blocks = rawBlocks ?: return
        var globalTile = 0
        for (block in blocks) {
            val blockTileCount = block.size / BYTES_PER_TILE
            for (t in 0 until blockTileCount) {
                val col = globalTile % cols
                val row = globalTile / cols
                val baseX = col * 8
                val baseY = row * 8
                if (baseX + 8 > w || baseY + 8 > h) { globalTile++; continue }
                val tileOffset = t * BYTES_PER_TILE
                for (py in 0 until 8) {
                    var bp0 = 0; var bp1 = 0; var bp2 = 0; var bp3 = 0
                    for (px in 0 until 8) {
                        val argb = pixels[(baseY + py) * w + (baseX + px)]
                        val alpha = (argb ushr 24) and 0xFF
                        val ci = if (alpha < 128) 0 else findNearestPaletteIndex(argb, palette)
                        val bit = 7 - px
                        if (ci and 1 != 0) bp0 = bp0 or (1 shl bit)
                        if (ci and 2 != 0) bp1 = bp1 or (1 shl bit)
                        if (ci and 4 != 0) bp2 = bp2 or (1 shl bit)
                        if (ci and 8 != 0) bp3 = bp3 or (1 shl bit)
                    }
                    block[tileOffset + py * 2] = bp0.toByte()
                    block[tileOffset + py * 2 + 1] = bp1.toByte()
                    block[tileOffset + py * 2 + 16] = bp2.toByte()
                    block[tileOffset + py * 2 + 17] = bp3.toByte()
                }
                globalTile++
            }
        }
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
}
