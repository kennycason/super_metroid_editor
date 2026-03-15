package com.supermetroid.editor.rom

/**
 * Decodes Samus sprites from the ROM using the 4-tier indirection system:
 *   Pose ID → Frame Progression → DMA Transfer Tables → Raw 4bpp Tile Data
 * Plus tilemap assembly from separate tilemap tables.
 *
 * See docs/graphics/samus_sprites.md for full format documentation.
 */
class SamusSpriteDecoder(private val romParser: RomParser) {

    private val rom = romParser.getRomData()

    // ─── ROM address constants (SNES addresses) ─────────────────────

    /** Frame progression pointer table: 2-byte ptrs indexed by animation ID */
    private val FRAME_PROG_PTRS = 0x92D94E

    /** Top-half DMA table pointers: 13 × 2-byte ptrs */
    private val TOP_DMA_PTRS = 0x92D91E

    /** Bottom-half DMA table pointers: 13 × 2-byte ptrs */
    private val BOT_DMA_PTRS = 0x92D938

    /** Upper body tilemap index: 2-byte ptrs indexed by animation ID */
    private val UPPER_TILEMAP_INDEX = 0x929263

    /** Lower body tilemap index: 2-byte ptrs indexed by animation ID */
    private val LOWER_TILEMAP_INDEX = 0x92945D

    /** Tilemap pointer table base */
    private val TILEMAP_PTRS = 0x92808D

    /** Default VRAM population (256 tiles = 8KB) at $9A:D200 */
    private val DEFAULT_VRAM = 0x9AD200

    /** Default VRAM size: 0x2000 bytes = 256 tiles × 32 bytes */
    private val DEFAULT_VRAM_SIZE = 0x2000

    /**
     * VRAM total size: 512 tiles × 32 bytes = 16384 bytes.
     * Only tiles 0-255 are populated by default VRAM (8KB from $9A:D200).
     * DMA writes overlay specific tile ranges:
     *   Top half:    tiles 0x00+ and 0x10+
     *   Bottom half: tiles 0x08+ and 0x18+
     */
    private val VRAM_SIZE = 512 * 32  // 16384 bytes

    /** Bytes per 4bpp 8×8 tile */
    private val TILE_SIZE = 32

    /** Suit palette addresses */
    private val POWER_SUIT_PALETTE = 0x9B9400
    private val VARIA_SUIT_PALETTE = 0x9B9820
    private val GRAVITY_SUIT_PALETTE = 0x9B9C40

    // ─── Data classes ────────────────────────────────────────────────

    data class TilemapEntry(
        val xOffset: Int,     // signed X from center
        val yOffset: Int,     // signed Y from center
        val tileNum: Int,     // VRAM tile number (9-bit)
        val palette: Int,     // OAM palette (0-7)
        val xFlip: Boolean,
        val yFlip: Boolean,
        val is16x16: Boolean  // true = 16x16, false = 8x8
    )

    data class SamusPose(
        val vram: ByteArray,           // 8KB VRAM with DMA overlaid
        val tilemaps: List<TilemapEntry>,
        val animationId: Int,
        val poseIndex: Int
    )

    // ─── Animation info ──────────────────────────────────────────────

    /** Number of animation entries in the frame progression pointer table */
    val animationCount: Int get() = 253

    /**
     * Get the number of frames (poses) for an animation.
     * Scans until end marker (0xFF in top_tiles_table byte) or next animation boundary.
     */
    fun getFrameCount(animationId: Int): Int {
        if (animationId < 0 || animationId >= animationCount) return 0
        val fpPtrOff = romParser.snesToPc(FRAME_PROG_PTRS + 2 * animationId)
        val fpBase = readU16(rom, fpPtrOff)
        val tableAddr = romParser.snesToPc(0x920000 + fpBase)

        var count = 0
        while (count < 64) { // safety limit
            val entry0 = rom[tableAddr + count * 4].toInt() and 0xFF
            if (entry0 == 0xFF) break
            count++
        }
        return count
    }

    // ─── Pose extraction ─────────────────────────────────────────────

    /**
     * Extract a single Samus pose (tile data + tilemaps).
     *
     * @param animationId Animation index (0-252)
     * @param poseIndex   Frame index within the animation
     * @return SamusPose with VRAM data and tilemap entries, or null on failure
     */
    fun getPose(animationId: Int, poseIndex: Int): SamusPose? {
        if (animationId < 0 || animationId >= animationCount) return null

        // 1. Read frame progression entry
        val fpPtrOff = romParser.snesToPc(FRAME_PROG_PTRS + 2 * animationId)
        val fpBase = readU16(rom, fpPtrOff)
        val entryAddr = romParser.snesToPc(0x920000 + fpBase + 4 * poseIndex)

        val topTbl = rom[entryAddr].toInt() and 0xFF
        val topEnt = rom[entryAddr + 1].toInt() and 0xFF
        val botTbl = rom[entryAddr + 2].toInt() and 0xFF
        val botEnt = rom[entryAddr + 3].toInt() and 0xFF

        if (topTbl == 0xFF) return null // end marker

        // 2. Build VRAM: start with default, overlay DMA writes
        val vram = ByteArray(VRAM_SIZE)
        val defaultVramPc = romParser.snesToPc(DEFAULT_VRAM)
        System.arraycopy(rom, defaultVramPc, vram, 0, DEFAULT_VRAM_SIZE.coerceAtMost(VRAM_SIZE))

        // Bottom half first (vram offset 0x08), then top half (vram offset 0x00)
        applyDma(vram, BOT_DMA_PTRS, botTbl, botEnt, 0x08)
        applyDma(vram, TOP_DMA_PTRS, topTbl, topEnt, 0x00)

        // 3. Get tilemaps (lower body then upper body, reversed at the end)
        val tilemaps = mutableListOf<TilemapEntry>()
        for (baseAddr in intArrayOf(LOWER_TILEMAP_INDEX, UPPER_TILEMAP_INDEX)) {
            val idxOff = romParser.snesToPc(baseAddr + 2 * animationId)
            val idx = readU16(rom, idxOff)
            val ptrTableOff = TILEMAP_PTRS + 2 * idx + 2 * poseIndex
            // Bounds check: pointer must be within bank $92
            if ((ptrTableOff and 0xFF0000) != 0x920000) continue
            val tmPtrOff = romParser.snesToPc(ptrTableOff)
            if (tmPtrOff + 2 > rom.size) continue
            val tmPtr = readU16(rom, tmPtrOff)
            val tmAddr = romParser.snesToPc(0x920000 + tmPtr)
            if (tmAddr + 2 > rom.size) continue

            val count = readU16(rom, tmAddr)
            // Sanity check: Samus sprites never have more than ~20 tilemap entries per half
            if (count > 128) continue
            for (i in 0 until count) {
                val base = tmAddr + 2 + 5 * i
                if (base + 5 > rom.size) break
                tilemaps.add(parseTilemapEntry(base))
            }
        }
        tilemaps.reverse()

        return SamusPose(vram, tilemaps, animationId, poseIndex)
    }

    /**
     * Render a pose to an ARGB pixel array.
     *
     * @param pose    The extracted pose data
     * @param palette 16-color ARGB palette array
     * @param width   Output image width
     * @param height  Output image height
     * @param centerX X origin for Samus center in output
     * @param centerY Y origin for Samus center in output
     * @return ARGB pixel array of size width × height
     */
    fun renderPose(
        pose: SamusPose,
        palette: IntArray,
        width: Int = 64,
        height: Int = 64,
        centerX: Int = width / 2,
        centerY: Int = height / 2 + 8
    ): IntArray {
        val pixels = IntArray(width * height) // transparent black

        for (entry in pose.tilemaps) {
            if (entry.is16x16) {
                // 16x16 = 4 tiles: tileNum, tileNum+1, tileNum+16, tileNum+17
                renderTile(pixels, width, height, pose.vram, entry.tileNum, palette,
                    centerX + entry.xOffset, centerY + entry.yOffset,
                    entry.xFlip, entry.yFlip, 0, 0)
                renderTile(pixels, width, height, pose.vram, entry.tileNum + 1, palette,
                    centerX + entry.xOffset, centerY + entry.yOffset,
                    entry.xFlip, entry.yFlip, 8, 0)
                renderTile(pixels, width, height, pose.vram, entry.tileNum + 16, palette,
                    centerX + entry.xOffset, centerY + entry.yOffset,
                    entry.xFlip, entry.yFlip, 0, 8)
                renderTile(pixels, width, height, pose.vram, entry.tileNum + 17, palette,
                    centerX + entry.xOffset, centerY + entry.yOffset,
                    entry.xFlip, entry.yFlip, 8, 8)
            } else {
                // 8x8 single tile
                renderTile(pixels, width, height, pose.vram, entry.tileNum, palette,
                    centerX + entry.xOffset, centerY + entry.yOffset,
                    entry.xFlip, entry.yFlip, 0, 0)
            }
        }
        return pixels
    }

    // ─── Palette reading ─────────────────────────────────────────────

    /** Read a 16-color Samus palette from ROM as ARGB values. */
    fun readPalette(suit: SuitType = SuitType.POWER): IntArray {
        val snesAddr = when (suit) {
            SuitType.POWER -> POWER_SUIT_PALETTE
            SuitType.VARIA -> VARIA_SUIT_PALETTE
            SuitType.GRAVITY -> GRAVITY_SUIT_PALETTE
        }
        val pc = romParser.snesToPc(snesAddr)
        val palette = IntArray(16)
        for (i in 0 until 16) {
            val bgr555 = readU16(rom, pc + i * 2)
            palette[i] = EnemySpriteGraphics.snesColorToArgb(bgr555)
        }
        palette[0] = 0x00000000 // index 0 is always transparent
        return palette
    }

    enum class SuitType { POWER, VARIA, GRAVITY }

    // ─── Animation names ─────────────────────────────────────────────

    companion object {
        /** Named animation groups with their animation IDs and descriptions */
        val ANIMATION_GROUPS = listOf(
            AnimGroup("Stand", listOf(0, 1, 2, 3, 4, 5, 6, 7, 8), "Idle standing"),
            AnimGroup("Run", listOf(9, 10, 11, 12, 13, 14, 15, 16, 17), "Running"),
            AnimGroup("Jump", listOf(0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24), "Jumping"),
            AnimGroup("Spin Jump", listOf(0x25, 0x26), "Spin jump L/R"),
            AnimGroup("Screw Attack", listOf(0x29, 0x2A), "Screw attack L/R"),
            AnimGroup("Wall Jump", listOf(0x2B, 0x2C), "Wall jump L/R"),
            AnimGroup("Fall", listOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38), "Falling"),
            AnimGroup("Crouch", listOf(0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x41), "Crouching"),
            AnimGroup("Morph Ball", listOf(0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x1D5), "Morph ball"),
            AnimGroup("Moonwalk", listOf(0x55, 0x56, 0x57, 0x58, 0x59, 0x5A), "Moonwalk backwards"),
            AnimGroup("Shinespark", listOf(0x69, 0x6A, 0x6B, 0x6C, 0x6D), "Speed boost / shinespark"),
            AnimGroup("Grapple", listOf(0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C), "Grapple beam"),
            AnimGroup("Crystal Flash", listOf(0xDB,), "Crystal flash"),
            AnimGroup("Death", listOf(0xE7, 0xE8), "Death sequence"),
        )

        /** Quick lookup: first animation of each group for preview */
        val PREVIEW_ANIMATIONS = ANIMATION_GROUPS.map { it.name to it.animationIds.first() }
    }

    data class AnimGroup(val name: String, val animationIds: List<Int>, val description: String)

    // ─── Internal helpers ────────────────────────────────────────────

    private fun applyDma(vram: ByteArray, ptrsBase: Int, tableIdx: Int, entryIdx: Int, vramRowOffset: Int) {
        val ptrsOff = romParser.snesToPc(ptrsBase + 2 * tableIdx)
        val tablePtr = 0x920000 + readU16(rom, ptrsOff)
        val entryOff = romParser.snesToPc(tablePtr + 7 * entryIdx)

        val srcPtr = readU24(rom, entryOff)
        val row1Size = readU16(rom, entryOff + 3)
        val row2Size = readU16(rom, entryOff + 5)

        val srcPc = romParser.snesToPc(srcPtr)

        // Row 1 → vram at tile index vramRowOffset × 32 bytes per tile
        val dst1 = vramRowOffset * TILE_SIZE
        if (row1Size > 0 && dst1 + row1Size <= vram.size && srcPc + row1Size <= rom.size) {
            System.arraycopy(rom, srcPc, vram, dst1, row1Size)
        }

        // Row 2 → vram at tile index (0x10 + vramRowOffset) × 32 bytes per tile
        val dst2 = (0x10 + vramRowOffset) * TILE_SIZE
        if (row2Size > 0 && dst2 + row2Size <= vram.size && srcPc + row1Size + row2Size <= rom.size) {
            System.arraycopy(rom, srcPc + row1Size, vram, dst2, row2Size)
        }
    }

    private fun parseTilemapEntry(addr: Int): TilemapEntry {
        val word0 = readU16(rom, addr)
        val yByte = rom[addr + 2].toInt()
        val word1 = readU16(rom, addr + 3)

        val is16x16 = (word0 and 0x8000) != 0
        // X offset is 9-bit signed (bits 8:0 of word0)
        var xOff = word0 and 0x01FF
        if (xOff >= 0x100) xOff -= 0x200 // sign extend 9-bit

        // Y offset is 8-bit signed
        val yOff = if (yByte >= 0x80) yByte - 0x100 else yByte

        val yFlip = (word1 and 0x8000) != 0
        val xFlip = (word1 and 0x4000) != 0
        val palette = (word1 shr 9) and 7
        val tileNum = word1 and 0x01FF

        return TilemapEntry(xOff, yOff, tileNum, palette, xFlip, yFlip, is16x16)
    }

    /**
     * Render a single 8x8 tile from VRAM into the pixel buffer.
     * @param subX, subY offset within the 16x16 tile (0 or 8 for each)
     */
    private fun renderTile(
        pixels: IntArray, imgW: Int, imgH: Int,
        vram: ByteArray, tileNum: Int, palette: IntArray,
        baseX: Int, baseY: Int,
        xFlip: Boolean, yFlip: Boolean,
        subX: Int, subY: Int
    ) {
        val tileOffset = tileNum * 32
        if (tileOffset + 32 > vram.size) return

        for (py in 0 until 8) {
            val row = tileOffset + py * 2
            if (row + 17 > vram.size) continue

            val bp0 = vram[row].toInt() and 0xFF
            val bp1 = vram[row + 1].toInt() and 0xFF
            val bp2 = vram[row + 16].toInt() and 0xFF
            val bp3 = vram[row + 17].toInt() and 0xFF

            for (px in 0 until 8) {
                val bit = 7 - px
                val colorIdx = ((bp0 shr bit) and 1) or
                        (((bp1 shr bit) and 1) shl 1) or
                        (((bp2 shr bit) and 1) shl 2) or
                        (((bp3 shr bit) and 1) shl 3)

                if (colorIdx == 0) continue // transparent

                // Apply flip
                val fx = if (xFlip) {
                    if (subX == 0) 8 + (7 - px) else (7 - px)
                } else {
                    subX + px
                }
                val fy = if (yFlip) {
                    if (subY == 0) 8 + (7 - py) else (7 - py)
                } else {
                    subY + py
                }

                val sx = baseX + fx
                val sy = baseY + fy
                if (sx in 0 until imgW && sy in 0 until imgH) {
                    pixels[sy * imgW + sx] = palette[colorIdx]
                }
            }
        }
    }
}
