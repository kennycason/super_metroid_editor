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

    /** Standard weapon tiles: 8 tiles (0x100 bytes) loaded at tile index 0x30 */
    private val WEAPON_TILES_STANDARD = 0x9AF200
    private val WEAPON_TILES_OFFSET = 0x30  // tile index
    private val WEAPON_TILES_SIZE = 0x100   // 8 tiles × 32 bytes

    /**
     * Base gameplay suit palette addresses.
     *
     * Verified from SM disassembly:
     *   $91:DEBA Samus_LoadSuitPalette copies $9B:9400 / $9B:9520 / $9B:9800
     *   depending on equipped suit bits.
     */
    private val POWER_SUIT_PALETTE = 0x9B9400
    private val VARIA_SUIT_PALETTE = 0x9B9520
    private val GRAVITY_SUIT_PALETTE = 0x9B9800

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
     * Sorted unique frame-progression pointers, used to determine animation
     * boundaries (many animations share overlapping data with no terminators).
     */
    private val sortedFramePointers: IntArray by lazy {
        val ptrs = mutableSetOf<Int>()
        for (id in 0 until animationCount) {
            val off = romParser.snesToPc(FRAME_PROG_PTRS + 2 * id)
            ptrs.add(readU16(rom, off))
        }
        ptrs.sorted().toIntArray()
    }

    /**
     * Get the number of source-defined graphics frames for a pose.
     *
     * These four-byte definition lists have no terminators. Their exact boundaries
     * are the next distinct source pointer (or `$92:ED24` for the final list).
     * This is deliberately separate from bank `$91`'s runtime delay/control stream:
     * externally driven poses such as facing forward and grapple can expose many
     * graphics frames even though their ordinary delay loop is shorter.
     */
    fun getFrameCount(animationId: Int): Int {
        if (animationId < 0 || animationId >= animationCount) return 0
        val fpPtrOff = romParser.snesToPc(FRAME_PROG_PTRS + 2 * animationId)
        val currentPtr = readU16(rom, fpPtrOff)

        // Binary search for current pointer, use the next exact definition boundary.
        val idx = sortedFramePointers.binarySearch(currentPtr)
        if (idx >= 0) {
            val nextPtr = if (idx + 1 < sortedFramePointers.size) {
                sortedFramePointers[idx + 1]
            } else {
                ANIMATION_DEFINITIONS_END and 0xFFFF
            }
            val byteCount = nextPtr - currentPtr
            if (byteCount > 0 && byteCount % 4 == 0) return byteCount / 4
        }
        return 0
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

        // 2. Build VRAM: start with default, overlay weapon tiles, then DMA writes
        val vram = ByteArray(VRAM_SIZE)
        val defaultVramPc = romParser.snesToPc(DEFAULT_VRAM)
        System.arraycopy(rom, defaultVramPc, vram, 0, DEFAULT_VRAM_SIZE.coerceAtMost(VRAM_SIZE))

        // Load standard weapon tiles at tile index 0x30
        val weaponPc = romParser.snesToPc(WEAPON_TILES_STANDARD)
        val weaponDst = WEAPON_TILES_OFFSET * TILE_SIZE
        if (weaponDst + WEAPON_TILES_SIZE <= vram.size && weaponPc + WEAPON_TILES_SIZE <= rom.size) {
            System.arraycopy(rom, weaponPc, vram, weaponDst, WEAPON_TILES_SIZE)
        }

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
            val bx = centerX + entry.xOffset
            val by = centerY + entry.yOffset
            if (entry.is16x16) {
                // 16x16 = 4 sub-tiles; swap positions when flipped
                val lx = if (entry.xFlip) 8 else 0
                val rx = if (entry.xFlip) 0 else 8
                val ty = if (entry.yFlip) 8 else 0
                val boty = if (entry.yFlip) 0 else 8
                renderTile(pixels, width, height, pose.vram, entry.tileNum, palette,
                    bx + lx, by + ty, entry.xFlip, entry.yFlip)
                renderTile(pixels, width, height, pose.vram, entry.tileNum + 1, palette,
                    bx + rx, by + ty, entry.xFlip, entry.yFlip)
                renderTile(pixels, width, height, pose.vram, entry.tileNum + 16, palette,
                    bx + lx, by + boty, entry.xFlip, entry.yFlip)
                renderTile(pixels, width, height, pose.vram, entry.tileNum + 17, palette,
                    bx + rx, by + boty, entry.xFlip, entry.yFlip)
            } else {
                renderTile(pixels, width, height, pose.vram, entry.tileNum, palette,
                    bx, by, entry.xFlip, entry.yFlip)
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

    // ─── Animation building ───────────────────────────────────────────

    /**
     * Build a [SpriteAnimation] for a specific animation ID.
     * Extracts all frames, renders each at the given size, and assigns default timing.
     *
     * @param animationId Animation index (0-252)
     * @param suit        Suit palette to use
     * @param defaultTicks Default frame duration if ROM has no timing (4 ticks ~ 67ms)
     * @param renderSize  Width/height of each rendered frame
     * @return SpriteAnimation with rendered frames, or null if no valid frames
     */
    fun buildAnimation(
        animationId: Int,
        suit: SuitType = SuitType.POWER,
        defaultTicks: Int = 4,
        renderSize: Int = 64
    ): SpriteAnimation? {
        val count = getFrameCount(animationId)
        if (count <= 0) return null

        val palette = readPalette(suit)
        val frames = mutableListOf<SpriteAnimationFrame>()

        for (f in 0 until count) {
            val pose = getPose(animationId, f) ?: break // null = end marker (0xFF in DMA table)

            val pixels = renderPose(pose, palette, renderSize, renderSize)

            // Some exact source-definition slots intentionally have no spritemap for
            // either half (they are selected or advanced by external state logic).
            // Keep them in parity evidence, but omit them from this visual preview.
            val nonTransparent = pixels.count { (it ushr 24) > 0 }
            if (nonTransparent == 0) continue

            frames.add(SpriteAnimationFrame(
                pixels = pixels,
                width = renderSize,
                height = renderSize,
                durationTicks = defaultTicks,
                label = "Frame ${frames.size}"
            ))
        }

        if (frames.isEmpty()) return null

        val groupName = ANIMATION_GROUPS.find { animationId in it.animationIds }?.name ?: "Anim"
        val name = "$groupName 0x${animationId.toString(16).uppercase()}"

        return SpriteAnimation(name = name, frames = frames, loop = true)
    }

    /**
     * Build [SpriteAnimation]s for all animations in a group (e.g., all Run directions).
     */
    fun buildGroupAnimations(
        groupIdx: Int,
        suit: SuitType = SuitType.POWER,
        defaultTicks: Int = 4,
        renderSize: Int = 64
    ): List<SpriteAnimation> {
        val group = ANIMATION_GROUPS.getOrNull(groupIdx) ?: return emptyList()
        return group.animationIds.mapNotNull { animId ->
            buildAnimation(animId, suit, defaultTicks, renderSize)
        }
    }

    /**
     * Build animations for ALL Samus animation groups.
     * Returns one SpriteAnimation per animation ID.
     * Useful for mega sprite sheet export.
     */
    fun buildAllAnimations(
        suit: SuitType = SuitType.POWER,
        defaultTicks: Int = 4,
        renderSize: Int = 64
    ): List<SpriteAnimation> {
        return ANIMATION_GROUPS.flatMap { group ->
            group.animationIds.mapNotNull { animId ->
                buildAnimation(animId, suit, defaultTicks, renderSize)
            }
        }
    }

    // ─── Animation names ─────────────────────────────────────────────

    companion object {
        /** Source-backed address constants exposed internally for parity drift tests. */
        internal const val FRAME_PROG_PTRS = 0x92D94E
        internal const val TOP_DMA_PTRS = 0x92D91E
        internal const val BOT_DMA_PTRS = 0x92D938
        internal const val UPPER_TILEMAP_INDEX = 0x929263
        internal const val LOWER_TILEMAP_INDEX = 0x92945D
        internal const val TILEMAP_PTRS = 0x92808D
        internal const val ANIMATION_DELAY_PTRS = 0x91B010
        internal const val ANIMATION_DEFINITIONS_END = 0x92ED24

        /**
         * Source-correct pose families, named to match SpriteSomething/community usage.
         * Death is intentionally absent: it uses a separate death-sequence graphics path,
         * not pose IDs E7/E8 (which are landing-from-jump poses).
         */
        val ANIMATION_GROUPS = listOf(
            AnimGroup("Facing Forward", listOf(0x00, 0x9B), "Power and Varia/Gravity loading poses"),
            AnimGroup("Stand", listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08), "Standing and aiming"),
            AnimGroup("Run", listOf(0x09, 0x0A, 0x0B, 0x0C, 0x0F, 0x10, 0x11, 0x12), "Running and aiming"),
            AnimGroup("Moonwalk", listOf(0x49, 0x4A, 0x75, 0x76, 0x77, 0x78), "Moonwalking and aiming"),
            AnimGroup("Crouch", listOf(0x27, 0x28, 0x71, 0x72, 0x73, 0x74, 0x85, 0x86), "Crouching and aiming"),
            AnimGroup("Jump", listOf(0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x4D, 0x4E, 0x51, 0x52, 0x69, 0x6A, 0x6B, 0x6C), "Normal jump variants"),
            AnimGroup("Spin Jump", listOf(0x19, 0x1A), "Spin jump right/left"),
            AnimGroup("Space Jump", listOf(0x1B, 0x1C), "Space jump right/left"),
            AnimGroup("Screw Attack", listOf(0x81, 0x82), "Screw attack right/left"),
            AnimGroup("Wall Jump", listOf(0x83, 0x84), "Wall jump right/left"),
            AnimGroup("Fall", listOf(0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x67, 0x68, 0x6D, 0x6E, 0x6F, 0x70), "Falling and aiming"),
            AnimGroup("Morph Transition", listOf(0x37, 0x38, 0x3D, 0x3E), "Morph and unmorph transitions"),
            AnimGroup("Morph Ball — Stationary", listOf(0x1D, 0x41, 0x79, 0x7A), "Normal and spring-ball stationary poses"),
            AnimGroup("Morph Ball — Moving", listOf(0x1E, 0x1F, 0x7B, 0x7C), "Normal and spring-ball rolling poses"),
            AnimGroup("Morph Ball — Airborne", listOf(0x31, 0x32, 0x7D, 0x7E, 0x7F, 0x80), "Normal and spring-ball airborne poses"),
            AnimGroup("Damage Boost", listOf(0x4F, 0x50), "Damage boost left/right"),
            AnimGroup("Knockback", listOf(0x53, 0x54), "Knockback left/right"),
            AnimGroup("Grapple", (0xA8..0xB9).toList(), "Grapple poses, swing, and wall hang"),
            AnimGroup("Shinespark", (0xC9..0xCE).toList(), "Horizontal, vertical, and diagonal shinespark"),
            AnimGroup("Crystal Flash", listOf(0xD3, 0xD4, 0xD7, 0xD8), "Crystal flash and ending"),
            AnimGroup("X-Ray", listOf(0xD5, 0xD6, 0xD9, 0xDA), "Standing and crouched X-Ray"),
            AnimGroup("Landing", listOf(0xA4, 0xA5, 0xA6, 0xA7, 0xE0, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7), "Normal, spin, aimed, and firing landings"),
            AnimGroup("Grabbed by Draygon", (0xBA..0xBE).toList() + (0xEC..0xF0).toList(), "Held and struggling poses"),
            AnimGroup("Drained", listOf(0xE8, 0xE9, 0xEA, 0xEB), "Mother Brain drain sequence poses"),
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
     * Flip is applied within the 8x8 tile; for 16x16 sprites the caller
     * swaps sub-tile positions.
     */
    private fun renderTile(
        pixels: IntArray, imgW: Int, imgH: Int,
        vram: ByteArray, tileNum: Int, palette: IntArray,
        baseX: Int, baseY: Int,
        xFlip: Boolean, yFlip: Boolean
    ) {
        val tileOffset = tileNum * 32
        if (tileOffset < 0 || tileOffset + 32 > vram.size) return

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

                val fx = if (xFlip) 7 - px else px
                val fy = if (yFlip) 7 - py else py

                val sx = baseX + fx
                val sy = baseY + fy
                if (sx in 0 until imgW && sy in 0 until imgH) {
                    pixels[sy * imgW + sx] = palette[colorIdx]
                }
            }
        }
    }
}
