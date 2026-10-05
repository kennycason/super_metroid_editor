package com.supermetroid.editor.rom

/**
 * Handles boss/enemy sprite tile graphics for the sprite editor.
 *
 * Super Metroid enemy sprites are stored as 4bpp tile blocks. Regular enemy sprites use raw
 * (uncompressed) 4bpp data at the GRAPHADR address in the species header. Bosses may combine
 * raw OAM graphics, room tilesets, variable tiles, and BG tilemaps; they must be mapped from the
 * exact assembly rather than treated as one generic compressed format.
 * Each 8x8 tile = 32 bytes (standard SNES 4bpp interleaved format):
 *   - Bytes  0-15: bitplanes 0+1 interleaved (2 bytes per row × 8 rows)
 *   - Bytes 16-31: bitplanes 2+3 interleaved (2 bytes per row × 8 rows)
 *
 * The legacy Phantoon/Kraid block constants below are retained only to read/reset old project data
 * and support investigation tests. They are disproved export mappings and must never be written.
 */
data class EnemyTileEditValidation(
    val speciesId: Int,
    val actualSize: Int,
    val expectedSize: Int?,
    val tileCount: Int,
    val pcAddress: Int?,
    val snesAddress: Int?,
    val errors: List<String>,
    val warnings: List<String>,
) {
    val isExportable: Boolean get() = errors.isEmpty()
    val expectedTileCount: Int? get() = expectedSize?.div(EnemySpriteGraphics.BYTES_PER_TILE)
}

/** Who owns bytes borrowed only for an assembled enemy preview. */
enum class EnemyPreviewAssetOwnership {
    SPECIES_HEADER,
    SHARED_SPECIES,
    GLOBAL_RUNTIME,
}

/**
 * A render-only tile source. [editable] is deliberately false for graphics
 * which the species header does not transfer through ProcessEnemyTilesets.
 */
data class EnemyPreviewTileSource(
    val bytes: ByteArray,
    val snesAddress: Int,
    val byteCount: Int,
    val label: String,
    val ownership: EnemyPreviewAssetOwnership,
    val sourceSpeciesId: Int? = null,
    val compressed: Boolean = false,
) {
    val editable: Boolean get() = ownership == EnemyPreviewAssetOwnership.SPECIES_HEADER
}

/** Palette selected for preview, which can also be global/runtime-owned. */
data class EnemyPreviewPaletteSource(
    val colors: IntArray,
    val snesAddress: Int,
    val label: String,
    val ownership: EnemyPreviewAssetOwnership,
) {
    val editable: Boolean get() = ownership == EnemyPreviewAssetOwnership.SPECIES_HEADER
}

/** Complete 64-byte bank-$A0 enemy species header. */
data class EnemySpeciesHeader(
    val speciesId: Int,
    val rawTileDataSize: Int,
    val palettePointer: Int,
    val health: Int,
    val damage: Int,
    val width: Int,
    val height: Int,
    val aiBank: Int,
    val hurtAiTime: Int,
    val cry: Int,
    val bossId: Int,
    val initAi: Int,
    val parts: Int,
    val unused: Int,
    val mainAi: Int,
    val grappleAi: Int,
    val hurtAi: Int,
    val frozenAi: Int,
    val timeIsFrozen: Int,
    val deathAnimation: Int,
    val deathAnimationUnused: Long,
    val powerBombReaction: Int,
    val variantIndex: Int,
    val variantUnused: Long,
    val enemyTouch: Int,
    val enemyShot: Int,
    val spritemap: Int,
    val tileDataAddress: Int,
    val layer: Int,
    val dropsPointer: Int,
    val vulnerabilitiesPointer: Int,
    val namePointer: Int,
) {
    val tileDataSize: Int get() = rawTileDataSize and 0x7FFF
    val alternateVramLayout: Boolean get() = rawTileDataSize and 0x8000 != 0
}

class EnemySpriteGraphics(private val romParser: RomParser) {

    companion object {
        /** @see RomConstants.BYTES_PER_4BPP_TILE */
        const val BYTES_PER_TILE = RomConstants.BYTES_PER_4BPP_TILE

        private const val CROCOMIRE_SPECIES_ID = 0xDDBF
        private const val MOTHER_BRAIN_BODY_SPECIES_ID = 0xEC7F
        private const val CROCOMIRE_TILESET_ID = 0x1B
        private const val DRAYGON_TILESET_ID = 0x1C
        private const val MOTHER_BRAIN_TILESET_ID = 0x0E
        private const val CROCOMIRE_BG_ENEMY_TILE_BASE = 0xD0
        private const val DRAYGON_BODY_SPECIES_ID = 0xDE3F
        private const val DRAYGON_BG_ENEMY_TILE_BASE = 0x100
        private const val MOTHER_BRAIN_BODY_RAW_TILE_BASE = 0xD0
        private const val MOTHER_BRAIN_LEGS_TILE_BASE = 0x140
        private const val MOTHER_BRAIN_RENDER_TILE_COUNT = 0x200
        private const val MOTHER_BRAIN_BODY_ROOM_TILE_START = 0x160
        private const val MOTHER_BRAIN_BODY_ROOM_TILE_COUNT = 0xA0
        private const val MOTHER_BRAIN_HEAD_TILES_SNES = 0xB78000
        private const val MOTHER_BRAIN_LEGS_TILES_SNES = 0xB79000
        private const val MOTHER_BRAIN_HEAD_TILES_SIZE = 0x1000
        private const val MOTHER_BRAIN_LEGS_TILES_SIZE = 0x1000
        private const val STANDARD_SPRITE_TILES_SNES = 0x9AD200
        private const val STANDARD_SPRITE_TILES_SIZE = 0x2000
        private const val BABY_METROID_TILES_SNES = 0xB18400
        private const val BABY_METROID_TILES_SIZE = 0x0C00
        private const val CORPSE_COMMON_TILES_SNES = 0xB7C000
        private const val CORPSE_COMMON_TILES_SIZE = 0x0E00
        private const val COMMON_SPRITE_PALETTE_2_SNES = 0x9A8140
        private const val COMMON_SPRITE_PALETTE_5_SNES = 0x9A81A0

        private const val ELEVATOR_SPECIES_ID = 0xD73F
        private const val CERES_STEAM_SPECIES_ID = 0xE1FF
        private const val ZEBETITE_SPECIES_ID = 0xE27F
        private const val MOTHER_BRAIN_HEAD_SPECIES_ID = 0xEC3F
        private const val BABY_METROID_CUTSCENE_SPECIES_ID = 0xECBF
        private const val MOTHER_BRAIN_TUBES_SPECIES_ID = 0xECFF
        private const val CORPSE_SIDEHOPPER_SPECIES_ID = 0xED7F
        private const val CORPSE_ZOOMER_SPECIES_ID = 0xEDFF
        private const val CORPSE_RIPPER_SPECIES_ID = 0xEE3F
        private const val CORPSE_SKREE_SPECIES_ID = 0xEE7F
        private const val BABY_METROID_SPECIES_ID = 0xEEBF
        private const val CROCOMIRE_SKELETON_CHUNK_SIZE_BYTES = 0x200
        private const val TORIZO_SPECIES_ID = 0xEEFF
        private const val TORIZO_ORBS_SPECIES_ID = 0xEF3F
        private const val GOLD_TORIZO_SPECIES_ID = 0xEF7F
        private const val GOLD_TORIZO_ORBS_SPECIES_ID = 0xEFBF
        private const val TORIZO_ORB_PALETTE_SNES = 0xAA8687
        private const val TORIZO_NORMAL_PALETTE_SNES = 0xAA8707
        private const val TORIZO_NORMAL_PALETTE_2_SNES = 0xAA8727
        private const val GOLD_TORIZO_PALETTE_SNES = 0xAA8787
        private const val GOLD_TORIZO_PALETTE_2_SNES = 0xAA87A7
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
         * Disproved legacy Phantoon blocks. Their PC offsets resolve to $B7:970F/$B7:9808,
         * inside the exact assembly's Mother Brain leg graphics ($B7:9000..$B7:9FFF).
         * Kept temporarily so old project data can be detected and reset.
         */
        @Deprecated("Incorrect legacy mapping into Mother Brain leg graphics; never use for export")
        val PHANTOON_BLOCKS = listOf(
            SpriteBlock(0x1B970F, 0xB7970F, 0x0300, "Legacy Phantoon Block A (unsafe)"),
            SpriteBlock(0x1B9808, 0xB79808, 0x0380, "Legacy Phantoon Block B (unsafe)")
        )

        /**
         * Disproved legacy Kraid block. $B9:FA38 is a compressed BG2 tilemap, not 4bpp pixels.
         * Kept temporarily so old project data can be detected and reset.
         */
        @Deprecated("Incorrect legacy mapping to a compressed BG2 tilemap; never use for export")
        val KRAID_BLOCKS = listOf(
            SpriteBlock(0x1CFA38, 0xB9FA38, 0x0100, "Legacy Kraid BG2 Tilemap (unsafe)")
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

        /** Disproved legacy boss mappings, retained for old-data investigation only. */
        @Deprecated("Contains disproved boss mappings; use exact per-owner source models")
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
            0xDEBF to "Boss", 0xDEFF to "Boss", 0xE17F to "Boss",
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
            0xE4BF, 0xE2BF, 0xDDBF,
            0xDE3F, 0xE17F,
            0xEC3F, 0xEEBF, 0xE27F,
            // Mini-Bosses
            0xDF3F, 0xF293, 0xE0FF, 0xEEFF, 0xED3F,
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
            torizoDisplayPaletteAddress(speciesId)?.let { snesAddress ->
                readPaletteAt(romParser, snesAddress)?.let { return it }
            }

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
         * Resolve the palette that is actually present when the species is
         * drawn. Elevator, Ceres steam, and zebetites bypass the palette field
         * in their headers and select already-loaded global sprite rows in AI.
         */
        fun loadEnemyPreviewPaletteSource(
            romParser: RomParser,
            speciesId: Int,
        ): EnemyPreviewPaletteSource? {
            val global = when (speciesId and 0xFFFF) {
                ELEVATOR_SPECIES_ID,
                CERES_STEAM_SPECIES_ID -> Triple(
                    COMMON_SPRITE_PALETTE_5_SNES,
                    "Common sprite palette 5",
                    EnemyPreviewAssetOwnership.GLOBAL_RUNTIME,
                )
                ZEBETITE_SPECIES_ID -> Triple(
                    COMMON_SPRITE_PALETTE_2_SNES,
                    "Common sprite palette 2",
                    EnemyPreviewAssetOwnership.GLOBAL_RUNTIME,
                )
                else -> null
            }
            if (global != null) {
                val colors = readPaletteAt(romParser, global.first) ?: return null
                return EnemyPreviewPaletteSource(colors, global.first, global.second, global.third)
            }

            val header = readSpeciesHeader(romParser, speciesId) ?: return null
            val colors = readEnemyPalette(romParser, speciesId) ?: return null
            return EnemyPreviewPaletteSource(
                colors = colors,
                snesAddress = (header.aiBank shl 16) or header.palettePointer,
                label = "Species palette",
                ownership = EnemyPreviewAssetOwnership.SPECIES_HEADER,
            )
        }

        private fun torizoDisplayPaletteAddress(speciesId: Int): Int? =
            when (speciesId) {
                TORIZO_SPECIES_ID -> TORIZO_NORMAL_PALETTE_SNES
                GOLD_TORIZO_SPECIES_ID -> GOLD_TORIZO_PALETTE_SNES
                TORIZO_ORBS_SPECIES_ID,
                GOLD_TORIZO_ORBS_SPECIES_ID -> TORIZO_ORB_PALETTE_SNES
                else -> null
            }

        private fun readPaletteAt(romParser: RomParser, snesAddress: Int): IntArray? {
            val rom = romParser.getRomData()
            val palPc = romParser.snesToPc(snesAddress)
            if (palPc < 0 || palPc + 32 > rom.size) return null

            val pal = IntArray(RomConstants.COLORS_PER_PALETTE)
            pal[0] = 0x00000000
            for (i in 1 until RomConstants.COLORS_PER_PALETTE) {
                val bgr = readU16(rom, palPc + i * 2)
                pal[i] = snesColorToArgb(bgr)
            }
            return pal
        }

        fun readEnemyPaletteRows(romParser: RomParser, speciesId: Int): Map<Int, IntArray> =
            when (speciesId) {
                TORIZO_SPECIES_ID -> buildPaletteRows(
                    romParser,
                    1 to TORIZO_NORMAL_PALETTE_SNES,
                    2 to TORIZO_NORMAL_PALETTE_2_SNES
                )
                GOLD_TORIZO_SPECIES_ID -> buildPaletteRows(
                    romParser,
                    1 to GOLD_TORIZO_PALETTE_SNES,
                    2 to GOLD_TORIZO_PALETTE_2_SNES
                )
                TORIZO_ORBS_SPECIES_ID,
                GOLD_TORIZO_ORBS_SPECIES_ID -> buildPaletteRows(
                    romParser,
                    3 to TORIZO_ORB_PALETTE_SNES
                )
                else -> emptyMap()
            }

        private fun buildPaletteRows(
            romParser: RomParser,
            vararg rows: Pair<Int, Int>
        ): Map<Int, IntArray> =
            rows.mapNotNull { (row, snesAddress) ->
                readPaletteAt(romParser, snesAddress)?.let { row to it }
            }.toMap()

        /**
         * Read species header stats.
         * @return Triple(tileDataSize, hp, damage) or null
         */
        fun readSpeciesStats(romParser: RomParser, speciesId: Int): Triple<Int, Int, Int>? {
            val header = readSpeciesHeader(romParser, speciesId) ?: return null
            return Triple(header.tileDataSize, header.health, header.damage)
        }

        /** Parse every field in one assembled 64-byte `EnemyHeader` macro. */
        fun readSpeciesHeader(romParser: RomParser, speciesId: Int): EnemySpeciesHeader? {
            val rom = romParser.getRomData()
            val pc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            if (pc < 0 || pc + 0x40 > rom.size) return null
            fun u16(offset: Int): Int = readU16(rom, pc + offset)
            fun u32(offset: Int): Long =
                (readU24(rom, pc + offset).toLong() and 0xFFFFFFL) or
                    ((readU8(rom, pc + offset + 3).toLong() and 0xFFL) shl 24)

            return EnemySpeciesHeader(
                speciesId = speciesId and 0xFFFF,
                rawTileDataSize = u16(0x00),
                palettePointer = u16(0x02),
                health = u16(0x04),
                damage = u16(0x06),
                width = u16(0x08),
                height = u16(0x0A),
                aiBank = readU8(rom, pc + 0x0C),
                hurtAiTime = readU8(rom, pc + 0x0D),
                cry = u16(0x0E),
                bossId = u16(0x10),
                initAi = u16(0x12),
                parts = u16(0x14),
                unused = u16(0x16),
                mainAi = u16(0x18),
                grappleAi = u16(0x1A),
                hurtAi = u16(0x1C),
                frozenAi = u16(0x1E),
                timeIsFrozen = u16(0x20),
                deathAnimation = u16(0x22),
                deathAnimationUnused = u32(0x24),
                powerBombReaction = u16(0x28),
                variantIndex = u16(0x2A),
                variantUnused = u32(0x2C),
                enemyTouch = u16(0x30),
                enemyShot = u16(0x32),
                spritemap = u16(0x34),
                tileDataAddress = readU24(rom, pc + 0x36),
                layer = readU8(rom, pc + 0x39),
                dropsPointer = u16(0x3A),
                vulnerabilitiesPointer = u16(0x3C),
                namePointer = u16(0x3E),
            )
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
            val header = readSpeciesHeader(romParser, speciesId) ?: return null
            val snesAddr = header.tileDataAddress
            if (snesAddr == 0) return null
            val pcAddr = romParser.snesToPc(snesAddr)
            val rom = romParser.getRomData()
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
         * Resolve the bytes needed to draw a species without changing the
         * species' edit/export ownership. A zero tile-data size means the game
         * does not transfer graphics for that header; these explicit mappings
         * reproduce the bytes which are already in VRAM at draw time.
         */
        fun loadEnemyPreviewTileSource(
            romParser: RomParser,
            speciesId: Int,
            enemyTileData: ByteArray? = null,
        ): EnemyPreviewTileSource? {
            val id = speciesId and 0xFFFF
            val header = readSpeciesHeader(romParser, id) ?: return null
            // Ignore stale/custom project blocks for zero-transfer headers. They
            // are not species-owned merely because a project contains that key.
            val owned = if (header.tileDataSize > 0) {
                enemyTileData ?: loadEnemyTileData(romParser, id)
            } else {
                null
            }
            if (owned != null) {
                return EnemyPreviewTileSource(
                    bytes = owned,
                    snesAddress = header.tileDataAddress,
                    byteCount = owned.size,
                    label = "Species GRAPHADR",
                    ownership = EnemyPreviewAssetOwnership.SPECIES_HEADER,
                    sourceSpeciesId = id,
                )
            }

            return when (id) {
                ELEVATOR_SPECIES_ID -> rawPreviewSource(
                    romParser,
                    STANDARD_SPRITE_TILES_SNES,
                    STANDARD_SPRITE_TILES_SIZE,
                    "Standard sprite tiles",
                    EnemyPreviewAssetOwnership.GLOBAL_RUNTIME,
                )
                CERES_STEAM_SPECIES_ID -> rawPreviewSource(
                    romParser,
                    STANDARD_SPRITE_TILES_SNES,
                    STANDARD_SPRITE_TILES_SIZE,
                    "Standard sprite tiles",
                    EnemyPreviewAssetOwnership.GLOBAL_RUNTIME,
                )
                ZEBETITE_SPECIES_ID,
                MOTHER_BRAIN_TUBES_SPECIES_ID -> rawPreviewSource(
                    romParser,
                    MOTHER_BRAIN_HEAD_TILES_SNES,
                    MOTHER_BRAIN_HEAD_TILES_SIZE,
                    "Mother Brain head tiles",
                    EnemyPreviewAssetOwnership.SHARED_SPECIES,
                    MOTHER_BRAIN_HEAD_SPECIES_ID,
                )
                BABY_METROID_CUTSCENE_SPECIES_ID -> rawPreviewSource(
                    romParser,
                    BABY_METROID_TILES_SNES,
                    BABY_METROID_TILES_SIZE,
                    "Baby Metroid tiles",
                    EnemyPreviewAssetOwnership.SHARED_SPECIES,
                    BABY_METROID_SPECIES_ID,
                )
                CORPSE_ZOOMER_SPECIES_ID,
                CORPSE_RIPPER_SPECIES_ID,
                CORPSE_SKREE_SPECIES_ID -> rawPreviewSource(
                    romParser,
                    CORPSE_COMMON_TILES_SNES,
                    CORPSE_COMMON_TILES_SIZE,
                    "Sidehopper/Zoomer/Ripper/Skree corpse tiles",
                    EnemyPreviewAssetOwnership.SHARED_SPECIES,
                    CORPSE_SIDEHOPPER_SPECIES_ID,
                )
                else -> null
            }
        }

        private fun rawPreviewSource(
            romParser: RomParser,
            snesAddress: Int,
            byteCount: Int,
            label: String,
            ownership: EnemyPreviewAssetOwnership,
            sourceSpeciesId: Int? = null,
        ): EnemyPreviewTileSource? {
            val pc = romParser.snesToPc(snesAddress)
            val rom = romParser.getRomData()
            if (pc < 0 || pc + byteCount > rom.size) return null
            return EnemyPreviewTileSource(
                bytes = rom.copyOfRange(pc, pc + byteCount),
                snesAddress = snesAddress,
                byteCount = byteCount,
                label = label,
                ownership = ownership,
                sourceSpeciesId = sourceSpeciesId,
            )
        }

        fun validateEnemyTileEdit(
            romParser: RomParser,
            speciesId: Int,
            rawBytes: ByteArray
        ): EnemyTileEditValidation {
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val rom = romParser.getRomData()
            val stats = readSpeciesStats(romParser, speciesId)
            val expectedSize = stats?.first
            val block = readGraphicsBlock(romParser, speciesId)

            if (stats == null) {
                errors += "Could not read enemy species stats."
            } else {
                if (expectedSize == null || expectedSize <= 0) {
                    errors += "Species tileDataSize is not a positive byte count."
                } else if (rawBytes.size != expectedSize) {
                    errors += "Raw tile data is ${rawBytes.size} bytes; species expects $expectedSize bytes."
                }
                if (expectedSize != null && expectedSize % BYTES_PER_TILE != 0) {
                    warnings += "Species tileDataSize includes ${expectedSize % BYTES_PER_TILE} trailing non-tile bytes."
                }
            }

            if (rawBytes.isEmpty()) {
                errors += "Raw tile data is empty."
            }
            if (rawBytes.size % BYTES_PER_TILE != 0) {
                warnings += "Raw tile data includes ${rawBytes.size % BYTES_PER_TILE} trailing non-tile bytes."
            }

            if (block == null) {
                errors += "Could not resolve enemy GRAPHADR."
            } else {
                val endExclusive = block.pcAddress.toLong() + rawBytes.size.toLong()
                if (block.pcAddress < 0 || endExclusive > rom.size.toLong()) {
                    errors += "Writing ${rawBytes.size} bytes at PC 0x${block.pcAddress.toString(16)} would exceed ROM bounds."
                }
            }

            return EnemyTileEditValidation(
                speciesId = speciesId,
                actualSize = rawBytes.size,
                expectedSize = expectedSize,
                tileCount = rawBytes.size / BYTES_PER_TILE,
                pcAddress = block?.pcAddress,
                snesAddress = block?.snesAddress,
                errors = errors,
                warnings = warnings
            )
        }

        /**
         * Build tile data for pose rendering when the game composes sprites from
         * multiple VRAM sources. Most enemies render directly from their raw enemy
         * graphics. A few species need the same runtime tile placement/composition
         * that the game builds before drawing:
         *
         * - Crocomire's BG2 body also references room tileset $1B, while its enemy
         *   graphics are injected at physical room/VRAM tile $D0. Crocomire's OAM
         *   child spritemaps use those same physical tile IDs; SNES OBJ palette and
         *   name-table bits are not additional editor tile-index offsets.
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
            if (speciesId == MOTHER_BRAIN_BODY_SPECIES_ID) {
                return loadMotherBrainBodyRenderTileData(romParser, rawEnemyTiles)
            }
            if (speciesId != CROCOMIRE_SPECIES_ID) return rawEnemyTiles

            val tileGraphics = TileGraphics(romParser)
            if (!tileGraphics.loadTileset(CROCOMIRE_TILESET_ID)) return rawEnemyTiles
            val roomTileData = tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES) ?: rawEnemyTiles
            tileGraphics.injectRawTileData(CROCOMIRE_BG_ENEMY_TILE_BASE, rawEnemyTiles)
            return tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES) ?: roomTileData
        }

        /**
         * Build tile data for ordinary OAM spritemaps rendered with low-8 tile
         * numbers. Boss render buffers can require physical tile IDs and custom
         * render options, so keep this path to standard spritemap-compatible fixes.
         */
        fun loadStandardOamRenderTileData(
            romParser: RomParser,
            speciesId: Int,
            enemyTileData: ByteArray? = null
        ): ByteArray? {
            return loadEnemyPreviewTileSource(romParser, speciesId, enemyTileData)?.bytes
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

        fun loadMotherBrainRoomTileData(romParser: RomParser): ByteArray? {
            val tileGraphics = TileGraphics(romParser)
            if (!tileGraphics.loadTileset(MOTHER_BRAIN_TILESET_ID)) return null
            return tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES)
        }

        /**
         * Build a compact sheet of the tile sources Mother Brain phase 2 uses at
         * runtime. This is intentionally not the same as GRAPHADR: the torso is
         * drawn from room tileset $0E BG tiles, while head/limbs are DMA-loaded
         * from bank $B7 and $EC7F contributes only a small supplemental block.
         */
        fun loadMotherBrainBodySourceTileData(
            romParser: RomParser,
            bodyRawTiles: ByteArray? = null
        ): ByteArray? {
            val roomTiles = loadMotherBrainRoomTileData(romParser) ?: return null
            val headTiles = readRawBytes(romParser, MOTHER_BRAIN_HEAD_TILES_SNES, MOTHER_BRAIN_HEAD_TILES_SIZE)
                ?: return null
            val legTiles = readRawBytes(romParser, MOTHER_BRAIN_LEGS_TILES_SNES, MOTHER_BRAIN_LEGS_TILES_SIZE)
                ?: return null
            val rawBodyTiles = bodyRawTiles ?: loadEnemyTileData(romParser, MOTHER_BRAIN_BODY_SPECIES_ID)
                ?: return null

            val roomStart = MOTHER_BRAIN_BODY_ROOM_TILE_START * BYTES_PER_TILE
            val roomEnd = roomStart + MOTHER_BRAIN_BODY_ROOM_TILE_COUNT * BYTES_PER_TILE
            if (roomEnd > roomTiles.size) return null
            val roomBodyTiles = roomTiles.copyOfRange(roomStart, roomEnd)

            val totalSize = roomBodyTiles.size + headTiles.size + legTiles.size + rawBodyTiles.size
            val out = ByteArray(totalSize)
            var dest = 0
            for (block in listOf(roomBodyTiles, headTiles, legTiles, rawBodyTiles)) {
                block.copyInto(out, destinationOffset = dest)
                dest += block.size
            }
            return out
        }

        /**
         * Mother Brain phase 2 uses the room/BG tilemap for the torso and a
         * runtime sprite DMA block for limbs. The $EC7F GRAPHADR block is only
         * supplemental data at fixed page $D0, so pose rendering needs the same
         * composed OBJ tile space the fight builds at runtime.
         */
        private fun loadMotherBrainBodyRenderTileData(
            romParser: RomParser,
            bodyRawTiles: ByteArray
        ): ByteArray? {
            val headTiles = readRawBytes(romParser, MOTHER_BRAIN_HEAD_TILES_SNES, MOTHER_BRAIN_HEAD_TILES_SIZE)
                ?: return bodyRawTiles
            val legTiles = readRawBytes(romParser, MOTHER_BRAIN_LEGS_TILES_SNES, MOTHER_BRAIN_LEGS_TILES_SIZE)
                ?: return bodyRawTiles

            val out = ByteArray(MOTHER_BRAIN_RENDER_TILE_COUNT * BYTES_PER_TILE)
            headTiles.copyInto(out, destinationOffset = 0)
            bodyRawTiles.copyInto(out, destinationOffset = MOTHER_BRAIN_BODY_RAW_TILE_BASE * BYTES_PER_TILE)
            legTiles.copyInto(out, destinationOffset = MOTHER_BRAIN_LEGS_TILE_BASE * BYTES_PER_TILE)
            return out
        }

        private fun readRawBytes(romParser: RomParser, snesAddress: Int, size: Int): ByteArray? {
            val rom = romParser.getRomData()
            val pc = romParser.snesToPc(snesAddress)
            if (pc < 0 || pc + size > rom.size) return null
            return rom.copyOfRange(pc, pc + size)
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
