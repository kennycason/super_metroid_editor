package com.supermetroid.editor.rom

/**
 * Parses OAM-based spritemaps for regular (non-boss) enemies and assembles
 * them into renderable sprites.
 *
 * SM enemy sprites use OAM spritemaps stored in the enemy's AI bank.
 * Each spritemap defines a list of OAM entries (8x8 or 16x16 tiles) at
 * signed X/Y offsets from the enemy center.
 *
 * Spritemap format (per entry = 5 bytes):
 *   Bytes 0-1 (LE word): [s___ ____ XXXX XXXX  __XX XXXX X___ ____]
 *     - Bit 15: size flag (1 = 16x16, 0 = 8x8)
 *     - Bits 8-0: signed 9-bit X displacement from center
 *   Byte 2: signed 8-bit Y displacement from center
 *   Bytes 3-4 (LE word): [VHoo pppn cccc cccc]
 *     - V: vertical flip
 *     - H: horizontal flip
 *     - oo: OBJ priority
 *     - ppp: palette row
 *     - n: name table select (tile number bit 8)
 *     - cccccccc: tile number low 8 bits
 *
 * The instruction list at $0F92,x uses 4-byte entries:
 *   [timer(2)] [spritemap_ptr(2)]
 * Entries with timer >= 0x8000 are control opcodes (skipped).
 */
class EnemySpritemap(private val romParser: RomParser) {

    data class OamEntry(
        val xOffset: Int,
        val yOffset: Int,
        val tileNum: Int,
        val palRow: Int,
        val hFlip: Boolean,
        val vFlip: Boolean,
        val is16x16: Boolean
    )

    data class Spritemap(
        val entries: List<OamEntry>,
        val snesAddress: Int
    )

    data class AnimationFrame(
        val duration: Int,
        val spritemap: Spritemap
    )

    data class AssembledSprite(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        val originX: Int,
        val originY: Int,
        val spritemap: Spritemap
    )

    data class RenderOptions(
        val normalizeExtendedTilemaps: Boolean = true,
        val extendedTilemapOriginX: Int = 0,
        val extendedTilemapOriginY: Int = 0,
        val wrapExtendedTilemapTilePage: Boolean = false,
        val extendedTilemapsBehindOam: Boolean = false,
        val oamTileNumberMode: OamTileNumberMode = OamTileNumberMode.LOW_8,
        val oamTileNumberBase: Int = 0,
        val extendedOamOriginX: Int = 0,
        val extendedOamOriginY: Int = 0,
        val reverseExtendedOamDrawOrder: Boolean = false,
        val extendedTilemapBlankTiles: Set<Int> = setOf(0x0338)
    )

    enum class OamTileNumberMode {
        LOW_8,
        LOW_9,
        OR_BASE_LOW_9
    }

    sealed class RenderableFrame {
        abstract val snesAddress: Int

        data class Oam(
            val spritemap: Spritemap
        ) : RenderableFrame() {
            override val snesAddress: Int = spritemap.snesAddress
        }

        data class Extended(
            val spritemap: ExtendedSpritemap
        ) : RenderableFrame() {
            override val snesAddress: Int = spritemap.snesAddress
        }
    }

    data class ExtendedSpritemap(
        val children: List<ExtendedChild>,
        val snesAddress: Int
    )

    sealed class ExtendedChild {
        abstract val xOffset: Int
        abstract val yOffset: Int
        abstract val hitboxPtr: Int
        abstract val snesAddress: Int

        data class Oam(
            override val xOffset: Int,
            override val yOffset: Int,
            val spritemap: Spritemap,
            override val hitboxPtr: Int
        ) : ExtendedChild() {
            override val snesAddress: Int = spritemap.snesAddress
        }

        data class Tilemap(
            override val xOffset: Int,
            override val yOffset: Int,
            val tilemap: ExtendedTilemap,
            override val hitboxPtr: Int
        ) : ExtendedChild() {
            override val snesAddress: Int = tilemap.snesAddress
        }
    }

    data class ExtendedTilemap(
        val runs: List<ExtendedTilemapRun>,
        val snesAddress: Int
    ) {
        val tileCount: Int get() = runs.sumOf { it.tiles.size }
    }

    data class ExtendedTilemapRun(
        val dest: Int,
        val tiles: List<Int>
    )

    private data class TileDrawCommand(
        val x: Int,
        val y: Int,
        val tileNum: Int,
        val hFlip: Boolean,
        val vFlip: Boolean,
        val is16x16: Boolean
    )

    /**
     * Parse a spritemap at the given SNES address.
     * @return parsed Spritemap or null if the data doesn't look valid
     */
    fun parseSpritemap(snesAddr: Int): Spritemap? {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(snesAddr)
        if (pc < 0 || pc + 2 > rom.size) return null

        val count = readU16(rom, pc)
        if (count !in 1..64) return null
        if (pc + 2 + count * 5 > rom.size) return null

        val entries = mutableListOf<OamEntry>()
        for (i in 0 until count) {
            val ePc = pc + 2 + i * 5
            val xWord = readU16(rom, ePc)
            val yByte = rom[ePc + 2].toInt() and 0xFF
            val attr = readU16(rom, ePc + 3)

            val is16x16 = (xWord and 0x8000) != 0
            val xRaw9 = xWord and 0x01FF
            val xOffset = if ((xRaw9 and 0x100) != 0) xRaw9 or 0xFFFFFF00.toInt() else xRaw9
            val yOffset = if (yByte > 127) yByte - 256 else yByte

            val tileNum = attr and 0x01FF
            val palRow = (attr shr 9) and 7
            val hFlip = (attr shr 14) and 1 != 0
            val vFlip = (attr shr 15) and 1 != 0

            entries.add(OamEntry(xOffset, yOffset, tileNum, palRow, hFlip, vFlip, is16x16))
        }
        return Spritemap(entries, snesAddr)
    }

    fun parseRenderableFrame(snesAddr: Int): RenderableFrame? {
        parseExtendedSpritemap(snesAddr)?.let { return RenderableFrame.Extended(it) }
        return parseSpritemap(snesAddr)?.let { RenderableFrame.Oam(it) }
    }

    /**
     * Parse a vanilla multibox/extended enemy spritemap:
     *
     *   count
     *   x, y, spritemapOrExtendedTilemapPtr, hitboxPtr
     *   ...
     *
     * The pointed child is an extended tilemap when its first word is $FFFE;
     * otherwise it is a normal OAM spritemap.
     */
    fun parseExtendedSpritemap(snesAddr: Int): ExtendedSpritemap? {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(snesAddr)
        if (pc < 0 || pc + 2 > rom.size) return null

        val count = readU16(rom, pc)
        if (count !in 1..64) return null
        if (pc + 2 + count * 8 > rom.size) return null

        val bank = snesAddr and 0xFF0000
        val children = mutableListOf<ExtendedChild>()

        for (i in 0 until count) {
            val ePc = pc + 2 + i * 8
            val x = readS16(rom, ePc)
            val y = readS16(rom, ePc + 2)
            val childPtr = readU16(rom, ePc + 4)
            val hitboxPtr = readU16(rom, ePc + 6)
            if (childPtr < 0x8000) return null

            val childSnes = bank or childPtr
            val childPc = romParser.snesToPc(childSnes)
            if (childPc < 0 || childPc + 2 > rom.size) return null

            val firstWord = readU16(rom, childPc)
            val child = if (firstWord == 0xFFFE) {
                val tilemap = parseExtendedTilemap(childSnes) ?: return null
                ExtendedChild.Tilemap(x, y, tilemap, hitboxPtr)
            } else {
                val spritemap = parseSpritemap(childSnes) ?: return null
                ExtendedChild.Oam(x, y, spritemap, hitboxPtr)
            }
            children.add(child)
        }

        return ExtendedSpritemap(children, snesAddr)
    }

    fun parseExtendedTilemap(snesAddr: Int): ExtendedTilemap? {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(snesAddr)
        if (pc < 0 || pc + 2 > rom.size) return null
        if (readU16(rom, pc) != 0xFFFE) return null

        val runs = mutableListOf<ExtendedTilemapRun>()
        var offset = pc + 2
        var totalTiles = 0

        for (run in 0 until 128) {
            if (offset + 2 > rom.size) return null
            val dest = readU16(rom, offset)
            offset += 2
            if (dest == 0xFFFF) {
                return if (runs.isEmpty()) null else ExtendedTilemap(runs, snesAddr)
            }
            if (offset + 2 > rom.size) return null
            val count = readU16(rom, offset)
            offset += 2
            if (count !in 1..1024) return null
            if (offset + count * 2 > rom.size) return null

            val tiles = MutableList(count) { idx -> readU16(rom, offset + idx * 2) }
            offset += count * 2
            totalTiles += count
            if (totalTiles > 4096) return null
            runs.add(ExtendedTilemapRun(dest, tiles))
        }
        return null
    }

    /**
     * Find the best default spritemap for an enemy species by tracing the
     * init function's instruction list setup.
     *
     * Strategy:
     * 1. Scan init function for `STA $0F92,x` (9D 92 0F)
     * 2. Trace back to find the source value (immediate, table lookup, or Y register)
     * 3. If a direction table is found, try ALL entries and pick the widest sprite
     *    (floor-facing directions are wider than wall-crawling directions)
     * 4. Follow the instruction list to find the first animation frame with a valid spritemap
     */
    fun findDefaultSpritemap(speciesId: Int): Spritemap? {
        val rom = romParser.getRomData()
        val headerPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        if (headerPc < 0 || headerPc + 0x3A > rom.size) return null

        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
        val initAiPtr = readU16(rom, headerPc + 0x12)
        val initPc = romParser.snesToPc((aiBank shl 16) or initAiPtr)
        if (initPc < 0 || initPc + 20 > rom.size) return null

        // Read tile data size to validate spritemap results
        val rawTileSize = readU16(rom, headerPc)
        val tileCount = (rawTileSize and 0x7FFF) / RomConstants.BYTES_PER_4BPP_TILE

        // Check if there's a direction table — if so, try all directions and pick best
        val dirTableAddr = findDirectionTableAddress(rom, initPc)
        if (dirTableAddr != null) {
            val tablePc = romParser.snesToPc((aiBank shl 16) or dirTableAddr)
            if (tablePc >= 0 && tablePc + 8 <= rom.size) {
                val dirSmap = findBestDirectionSpritemap(rom, tablePc, aiBank)
                // Validate that tile indices fit within the species' tile data
                if (dirSmap != null && spritemapFitsTileData(dirSmap, tileCount)) {
                    return dirSmap
                }
            }
        }

        // No direction table or invalid results — use direct instruction list pointer
        val instrListPtr = findInstructionListPointer(rom, initPc, aiBank) ?: return null
        return findFirstSpritemap(rom, instrListPtr, aiBank, tileCount)
    }

    /**
     * Get all animation frames for a species (first direction/mode).
     */
    fun findAnimationFrames(speciesId: Int, maxFrames: Int = 32): List<AnimationFrame> {
        val rom = romParser.getRomData()
        val headerPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        if (headerPc < 0 || headerPc + 0x3A > rom.size) return emptyList()

        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
        val initAiPtr = readU16(rom, headerPc + 0x12)
        val initPc = romParser.snesToPc((aiBank shl 16) or initAiPtr)
        if (initPc < 0 || initPc + 20 > rom.size) return emptyList()

        val instrListPtr = findInstructionListPointer(rom, initPc, aiBank) ?: return emptyList()
        return parseAnimationFrames(rom, instrListPtr, aiBank, maxFrames)
    }

    /**
     * Find the direction table address from the init function, if one exists.
     * Matches the specific pattern: `TAY; LDA $xxxx,y; STA $0F92,x`
     * which is the standard SNES enemy direction table lookup.
     * Returns the 16-bit SNES offset of the table within the AI bank, or null.
     */
    private fun findDirectionTableAddress(rom: ByteArray, initPc: Int): Int? {
        val scanLimit = minOf(120, rom.size - initPc - 5)
        for (i in 0 until scanLimit) {
            val pc = initPc + i
            if (rom[pc].toInt() and 0xFF != 0x9D) continue
            if (rom[pc + 1].toInt() and 0xFF != 0x92) continue
            if (rom[pc + 2].toInt() and 0xFF != 0x0F) continue

            // Found STA $0F92,x — look for the exact pattern:
            // A8       TAY
            // B9 xx xx LDA $xxxx,y
            // 9D 92 0F STA $0F92,x  (we're here)
            if (i >= 4 &&
                rom[pc - 4].toInt() and 0xFF == 0xA8 &&  // TAY
                rom[pc - 3].toInt() and 0xFF == 0xB9) {  // LDA abs,y
                return readU16(rom, pc - 2)
            }
        }
        return null
    }

    /**
     * Try all entries in a direction table and return the spritemap with the
     * widest bounding box. Floor-facing directions produce wider sprites than
     * wall-crawling directions, so this picks the most recognizable orientation.
     */
    private fun findBestDirectionSpritemap(rom: ByteArray, tablePc: Int, aiBank: Int): Spritemap? {
        var bestSmap: Spritemap? = null
        var bestWidth = -1

        for (dir in 0 until 4) {
            if (tablePc + dir * 2 + 1 >= rom.size) break
            val instrListPtr = readU16(rom, tablePc + dir * 2)
            if (instrListPtr == 0) continue

            val smap = findFirstSpritemap(rom, instrListPtr, aiBank) ?: continue

            // Calculate bounding box width
            val minX = smap.entries.minOf { it.xOffset }
            val maxX = smap.entries.maxOf { it.xOffset + (if (it.is16x16) 16 else 8) }
            val width = maxX - minX

            if (width > bestWidth) {
                bestWidth = width
                bestSmap = smap
            }
        }

        return bestSmap
    }

    /**
     * Render a spritemap into an ARGB pixel array using the given tile data and palette.
     *
     * @param spritemap the parsed spritemap to render
     * @param tileData raw decompressed 4bpp tile bytes
     * @param palette 16-color ARGB palette (index 0 = transparent)
     * @return assembled sprite or null on failure
     */
    fun renderSpritemap(
        spritemap: Spritemap,
        tileData: ByteArray,
        palette: IntArray
    ): AssembledSprite? {
        if (spritemap.entries.isEmpty()) return null

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE

        for (entry in spritemap.entries) {
            val size = if (entry.is16x16) 16 else 8
            minX = minOf(minX, entry.xOffset)
            maxX = maxOf(maxX, entry.xOffset + size)
            minY = minOf(minY, entry.yOffset)
            maxY = maxOf(maxY, entry.yOffset + size)
        }

        val w = maxX - minX
        val h = maxY - minY
        if (w <= 0 || h <= 0) return null

        val pixels = IntArray(w * h)

        for (entry in spritemap.entries) {
            val localTile = entry.tileNum and 0xFF

            if (entry.is16x16) {
                render16x16Tile(pixels, w, h, minX, minY, entry, localTile, tileData, palette)
            } else {
                render8x8Tile(pixels, w, h, minX, minY, entry, localTile, tileData, palette)
            }
        }

        return AssembledSprite(w, h, pixels, -minX, -minY, spritemap)
    }

    fun renderRenderableFrame(
        frame: RenderableFrame,
        tileData: ByteArray,
        palette: IntArray,
        options: RenderOptions = RenderOptions(),
        extendedTilemapTileData: ByteArray? = null
    ): AssembledSprite? {
        return when (frame) {
            is RenderableFrame.Oam -> renderSpritemap(frame.spritemap, tileData, palette)
            is RenderableFrame.Extended -> renderExtendedSpritemap(
                frame.spritemap,
                tileData,
                palette,
                options,
                extendedTilemapTileData
            )
        }
    }

    fun flattenRenderableFrame(frame: RenderableFrame): Spritemap {
        return when (frame) {
            is RenderableFrame.Oam -> frame.spritemap
            is RenderableFrame.Extended -> flattenExtendedSpritemap(frame.spritemap)
        }
    }

    fun flattenExtendedSpritemap(ext: ExtendedSpritemap): Spritemap {
        val entries = ext.children.flatMap { child ->
            when (child) {
                is ExtendedChild.Oam -> child.spritemap.entries.map { entry ->
                    entry.copy(
                        xOffset = entry.xOffset + child.xOffset,
                        yOffset = entry.yOffset + child.yOffset
                    )
                }
                is ExtendedChild.Tilemap -> emptyList()
            }
        }
        return Spritemap(entries, ext.snesAddress)
    }

    fun renderExtendedSpritemap(
        ext: ExtendedSpritemap,
        tileData: ByteArray,
        palette: IntArray,
        options: RenderOptions = RenderOptions(),
        extendedTilemapTileData: ByteArray? = null
    ): AssembledSprite? {
        val flattened = flattenExtendedSpritemap(ext)
        val oamChildren = ext.children.filterIsInstance<ExtendedChild.Oam>().let { children ->
            if (options.reverseExtendedOamDrawOrder) children.asReversed() else children
        }
        val oamCommands = oamChildren.flatMap { buildOamCommands(it, options) }
        val tilemapCommands = buildExtendedTilemapCommands(ext, options)
        val commands = oamCommands + tilemapCommands
        if (commands.isEmpty()) return null

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE

        for (cmd in commands) {
            val size = if (cmd.is16x16) 16 else 8
            minX = minOf(minX, cmd.x)
            maxX = maxOf(maxX, cmd.x + size)
            minY = minOf(minY, cmd.y)
            maxY = maxOf(maxY, cmd.y + size)
        }

        val w = maxX - minX
        val h = maxY - minY
        if (w <= 0 || h <= 0) return null

        val pixels = IntArray(w * h)
        val tilemapData = extendedTilemapTileData ?: tileData

        fun renderCommands(cmds: List<TileDrawCommand>, data: ByteArray) {
            for (cmd in cmds) {
                renderTileCommand(pixels, w, h, minX, minY, cmd, data, palette)
            }
        }

        if (options.extendedTilemapsBehindOam) {
            renderCommands(tilemapCommands, tilemapData)
            renderCommands(oamCommands, tileData)
        } else {
            renderCommands(oamCommands, tileData)
            renderCommands(tilemapCommands, tilemapData)
        }

        return AssembledSprite(w, h, pixels, -minX, -minY, flattened)
    }

    private fun buildOamCommands(
        child: ExtendedChild.Oam,
        options: RenderOptions
    ): List<TileDrawCommand> {
        return child.spritemap.entries.map { entry ->
            TileDrawCommand(
                x = entry.xOffset + child.xOffset + options.extendedOamOriginX,
                y = entry.yOffset + child.yOffset + options.extendedOamOriginY,
                tileNum = oamTileNumber(entry, options),
                hFlip = entry.hFlip,
                vFlip = entry.vFlip,
                is16x16 = entry.is16x16
            )
        }
    }

    private fun oamTileNumber(entry: OamEntry, options: RenderOptions): Int {
        return when (options.oamTileNumberMode) {
            OamTileNumberMode.LOW_8 -> entry.tileNum and 0xFF
            OamTileNumberMode.LOW_9 -> entry.tileNum and 0x1FF
            OamTileNumberMode.OR_BASE_LOW_9 -> options.oamTileNumberBase or (entry.tileNum and 0x1FF)
        }
    }

    private fun buildExtendedTilemapCommands(
        ext: ExtendedSpritemap,
        options: RenderOptions
    ): List<TileDrawCommand> {
        val tilemapChildren = ext.children.filterIsInstance<ExtendedChild.Tilemap>()
        if (tilemapChildren.isEmpty()) return emptyList()

        data class PositionedTilemap(val child: ExtendedChild.Tilemap, val row: Int, val col: Int, val run: ExtendedTilemapRun)

        val positionedRuns = tilemapChildren.flatMap { child ->
            child.tilemap.runs.map { run ->
                PositionedTilemap(
                    child = child,
                    row = tilemapDestRow(run.dest),
                    col = tilemapDestCol(run.dest),
                    run = run
                )
            }
        }
        if (positionedRuns.isEmpty()) return emptyList()

        val minRow = if (options.normalizeExtendedTilemaps) positionedRuns.minOf { it.row } else 0
        val minCol = if (options.normalizeExtendedTilemaps) positionedRuns.minOf { it.col } else 0
        val tileLayer = linkedMapOf<Pair<Int, Int>, TileDrawCommand>()

        for (run in positionedRuns) {
            val y = options.extendedTilemapOriginY + run.child.yOffset + (run.row - minRow) * 8
            val startX = options.extendedTilemapOriginX + run.child.xOffset + (run.col - minCol) * 8
            for ((idx, tileWord) in run.run.tiles.withIndex()) {
                val x = startX + idx * 8
                val key = x to y
                if (isExtendedTilemapBlank(tileWord, options)) {
                    tileLayer.remove(key)
                    continue
                }
                tileLayer[key] = TileDrawCommand(
                    x = x,
                    y = y,
                    tileNum = extendedTilemapTileNumber(tileWord, options),
                    hFlip = (tileWord shr 14) and 1 != 0,
                    vFlip = (tileWord shr 15) and 1 != 0,
                    is16x16 = false
                )
            }
        }
        return tileLayer.values.toList()
    }

    private fun isExtendedTilemapBlank(tileWord: Int, options: RenderOptions): Boolean =
        (tileWord and 0x03FF) in options.extendedTilemapBlankTiles

    private fun extendedTilemapTileNumber(
        tileWord: Int,
        options: RenderOptions
    ): Int {
        val tileNum = tileWord and 0x03FF
        return if (options.wrapExtendedTilemapTilePage && tileNum in 0x100..0x1FF) {
            tileNum and 0xFF
        } else {
            tileNum
        }
    }

    private fun renderTileCommand(
        pixels: IntArray,
        w: Int,
        h: Int,
        originX: Int,
        originY: Int,
        cmd: TileDrawCommand,
        tileData: ByteArray,
        palette: IntArray
    ) {
        if (cmd.is16x16) {
            val subTiles = intArrayOf(
                cmd.tileNum, cmd.tileNum + 1,
                cmd.tileNum + 16, cmd.tileNum + 17
            )
            val subOffsets = arrayOf(
                0 to 0, 8 to 0,
                0 to 8, 8 to 8
            )
            for (si in 0 until 4) {
                val subTile = subTiles[si]
                val (subDx, subDy) = subOffsets[si]
                val adjDx = if (cmd.hFlip) 8 - subDx else subDx
                val adjDy = if (cmd.vFlip) 8 - subDy else subDy
                render8x8TileCommand(
                    pixels, w, h, originX, originY,
                    cmd.copy(
                        x = cmd.x + adjDx,
                        y = cmd.y + adjDy,
                        tileNum = subTile,
                        is16x16 = false
                    ),
                    tileData,
                    palette
                )
            }
        } else {
            render8x8TileCommand(pixels, w, h, originX, originY, cmd, tileData, palette)
        }
    }

    private fun render8x8TileCommand(
        pixels: IntArray,
        w: Int,
        h: Int,
        originX: Int,
        originY: Int,
        cmd: TileDrawCommand,
        tileData: ByteArray,
        palette: IntArray
    ) {
        val tileOffset = cmd.tileNum * RomConstants.BYTES_PER_4BPP_TILE
        if (tileOffset < 0 || tileOffset + RomConstants.BYTES_PER_4BPP_TILE > tileData.size) return

        for (py in 0 until 8) {
            for (px in 0 until 8) {
                val srcX = if (cmd.hFlip) 7 - px else px
                val srcY = if (cmd.vFlip) 7 - py else py
                val ci = readPixelFromTile(tileData, tileOffset, srcX, srcY)
                if (ci == 0) continue

                val dx = cmd.x - originX + px
                val dy = cmd.y - originY + py
                if (dx in 0 until w && dy in 0 until h) {
                    pixels[dy * w + dx] = palette[ci.coerceIn(0, palette.size - 1)] or (0xFF shl 24)
                }
            }
        }
    }

    private fun render8x8Tile(
        pixels: IntArray, w: Int, h: Int,
        originX: Int, originY: Int,
        entry: OamEntry, localTile: Int,
        tileData: ByteArray, palette: IntArray
    ) {
        val tileOffset = localTile * RomConstants.BYTES_PER_4BPP_TILE
        if (tileOffset + RomConstants.BYTES_PER_4BPP_TILE > tileData.size) return

        for (py in 0 until 8) {
            for (px in 0 until 8) {
                val srcX = if (entry.hFlip) 7 - px else px
                val srcY = if (entry.vFlip) 7 - py else py
                val ci = readPixelFromTile(tileData, tileOffset, srcX, srcY)
                if (ci == 0) continue

                val dx = entry.xOffset - originX + px
                val dy = entry.yOffset - originY + py
                if (dx in 0 until w && dy in 0 until h) {
                    pixels[dy * w + dx] = palette[ci.coerceIn(0, palette.size - 1)] or (0xFF shl 24)
                }
            }
        }
    }

    private fun render16x16Tile(
        pixels: IntArray, w: Int, h: Int,
        originX: Int, originY: Int,
        entry: OamEntry, localTile: Int,
        tileData: ByteArray, palette: IntArray
    ) {
        // 16x16 OBJ = 2x2 grid of 8x8 tiles:
        // [N, N+1] top row
        // [N+16, N+17] bottom row
        val subTiles = intArrayOf(
            localTile,      localTile + 1,
            localTile + 16, localTile + 17
        )
        val subOffsets = arrayOf(
            0 to 0, 8 to 0,
            0 to 8, 8 to 8
        )

        for (si in 0 until 4) {
            val subTile = subTiles[si]
            val tileOffset = subTile * RomConstants.BYTES_PER_4BPP_TILE
            if (tileOffset + RomConstants.BYTES_PER_4BPP_TILE > tileData.size) continue

            val (subDx, subDy) = subOffsets[si]
            // With flipping, the sub-tile positions are mirrored
            val adjDx = if (entry.hFlip) 8 - subDx else subDx
            val adjDy = if (entry.vFlip) 8 - subDy else subDy

            for (py in 0 until 8) {
                for (px in 0 until 8) {
                    val srcX = if (entry.hFlip) 7 - px else px
                    val srcY = if (entry.vFlip) 7 - py else py
                    val ci = readPixelFromTile(tileData, tileOffset, srcX, srcY)
                    if (ci == 0) continue

                    val dx = entry.xOffset - originX + adjDx + px
                    val dy = entry.yOffset - originY + adjDy + py
                    if (dx in 0 until w && dy in 0 until h) {
                        pixels[dy * w + dx] = palette[ci.coerceIn(0, palette.size - 1)] or (0xFF shl 24)
                    }
                }
            }
        }
    }

    private fun readPixelFromTile(tileData: ByteArray, tileOffset: Int, px: Int, py: Int): Int {
        val bit = 7 - px
        val bp0 = (tileData[tileOffset + py * 2].toInt() shr bit) and 1
        val bp1 = (tileData[tileOffset + py * 2 + 1].toInt() shr bit) and 1
        val bp2 = (tileData[tileOffset + py * 2 + 16].toInt() shr bit) and 1
        val bp3 = (tileData[tileOffset + py * 2 + 17].toInt() shr bit) and 1
        return bp0 or (bp1 shl 1) or (bp2 shl 2) or (bp3 shl 3)
    }

    private fun tilemapDestOffset(dest: Int): Int = (dest - EXTENDED_TILEMAP_BASE_DEST).coerceAtLeast(0)

    private fun tilemapDestRow(dest: Int): Int = tilemapDestOffset(dest) / 0x40

    private fun tilemapDestCol(dest: Int): Int = (tilemapDestOffset(dest) and 0x3F) / 2

    private companion object {
        const val EXTENDED_TILEMAP_BASE_DEST = 0x2000
    }

    private fun readS16(data: ByteArray, offset: Int): Int {
        val word = readU16(data, offset)
        return if (word >= 0x8000) word - 0x10000 else word
    }

    /**
     * Scan the init function for `STA $0F92,x` and trace back to find the
     * instruction list pointer value. Also follows JSR calls within the AI bank,
     * including cross-function tracing for indirect stores (LDA abs,x pattern).
     */
    private fun findInstructionListPointer(rom: ByteArray, initPc: Int, aiBank: Int): Int? {
        // First try the main init function
        val result = scanForInstrListPtr(rom, initPc, aiBank)
        if (result != null) return result

        // If not found, follow BRA (80 xx) at the start — boss sub-entities
        // may branch into a sibling entity's init code
        if (rom[initPc].toInt() and 0xFF == 0xAE && // LDX $xxxx
            rom[initPc + 3].toInt() and 0xFF == 0x80) { // BRA rel8
            val rel = rom[initPc + 4].toInt() // signed byte
            val braTarget = initPc + 5 + rel
            if (braTarget > 0 && braTarget + 10 < rom.size) {
                val braResult = scanForInstrListPtr(rom, braTarget, aiBank)
                if (braResult != null) return braResult
                val braAbsResult = scanForAbsoluteInstrListStore(rom, braTarget, aiBank)
                if (braAbsResult != null) return braAbsResult
            }
        }

        // If not found, follow JSR calls in the first 80 bytes
        val jsrLimit = minOf(80, rom.size - initPc - 2)
        for (i in 0 until jsrLimit) {
            val pc = initPc + i
            if (rom[pc].toInt() and 0xFF == 0x20) { // JSR abs
                val jsrTarget = readU16(rom, pc + 1)
                val jsrPc = romParser.snesToPc((aiBank shl 16) or jsrTarget)
                if (jsrPc >= 0 && jsrPc + 10 < rom.size) {
                    val jsrResult = scanForInstrListPtr(rom, jsrPc, aiBank)
                    if (jsrResult != null) return jsrResult

                    // Cross-function trace: the JSR target may load from an
                    // enemy instance variable (LDA abs,x; STA $0F92,x).
                    // Trace back to the caller to find where that var was set.
                    val varOffset = scanForIndirectVar(rom, jsrPc)
                    if (varOffset != null) {
                        val crossRef = traceVarSetInCaller(rom, initPc, aiBank, varOffset)
                        if (crossRef != null) return crossRef
                    }
                }
            }
        }

        // Some init functions store to an enemy variable (e.g. $7E:7800,x) early,
        // then load from it and store to $0F92,x much later (past 120 bytes).
        // Scan with a wider range for indirect var patterns in the init function itself.
        val varRef = scanForIndirectVar(rom, initPc, scanRange = 200)
        if (varRef != null) {
            val crossRef = traceVarSetInCaller(rom, initPc, aiBank, varRef)
            if (crossRef != null) return crossRef
        }

        // Boss sub-entities: init code may use absolute STA $xxxx (opcode 8D)
        // to a fixed enemy slot address instead of indexed STA $0F92,x.
        // The instruction list is at offset +$1A within each 64-byte enemy slot
        // (slots at $0F78, $0FB8, $0FF8, $1038, ...).
        val absResult = scanForAbsoluteInstrListStore(rom, initPc, aiBank)
        if (absResult != null) return absResult

        return null
    }

    /**
     * Scan for `LDA #imm; STA $xxxx` where $xxxx is the instruction list
     * address in a fixed enemy slot (offset +$1A from a slot base).
     * Used by boss sub-entities that know their slot assignment at compile time.
     */
    private fun scanForAbsoluteInstrListStore(rom: ByteArray, startPc: Int, aiBank: Int): Int? {
        val scanLimit = minOf(120, rom.size - startPc - 5)
        for (i in 0 until scanLimit) {
            val pc = startPc + i
            // Look for STA $xxxx (8D xx xx)
            if (rom[pc].toInt() and 0xFF != 0x8D) continue
            val dest = readU16(rom, pc + 1)
            // Check if dest is at instruction list offset (+$1A) within an enemy slot
            // Enemy slots: base $0F78 + n * $40, instruction list at base + $1A
            if (dest < 0x0F92 || dest > 0x1200) continue
            if ((dest - 0x0F78) % 0x40 != 0x1A) continue
            // Found a store to an instruction list slot — trace back for the value
            val result = traceValueSource(rom, pc, i, aiBank)
            if (result != null) return result
        }
        return null
    }

    private data class IndirectVarRef(val address: Int, val isLong: Boolean)

    /**
     * In a subroutine, find a load-then-store pattern to $0F92,x and return
     * the source variable address. Handles both absolute (BD) and long (BF)
     * addressing modes — bank $7E enemy vars use long addressing.
     */
    private fun scanForIndirectVar(rom: ByteArray, startPc: Int, scanRange: Int = 60): IndirectVarRef? {
        val scanLimit = minOf(scanRange, rom.size - startPc - 7)
        for (i in 0 until scanLimit) {
            val pc = startPc + i
            if (rom[pc].toInt() and 0xFF != 0x9D) continue
            if (rom[pc + 1].toInt() and 0xFF != 0x92) continue
            if (rom[pc + 2].toInt() and 0xFF != 0x0F) continue

            // BF xx xx xx = LDA long,x (4-byte instruction, bank $7E enemy vars)
            for (back in 4..8) {
                if (i >= back && rom[pc - back].toInt() and 0xFF == 0xBF) {
                    val addr = readU16(rom, pc - back + 1) or
                        ((rom[pc - back + 3].toInt() and 0xFF) shl 16)
                    return IndirectVarRef(addr, isLong = true)
                }
            }
            // BD xx xx = LDA abs,x (3-byte instruction)
            for (back in 3..6) {
                if (i >= back && rom[pc - back].toInt() and 0xFF == 0xBD) {
                    return IndirectVarRef(readU16(rom, pc - back + 1), isLong = false)
                }
            }
        }
        return null
    }

    /**
     * In the caller (init function), find a store to the same variable
     * and trace back to find the source value. Handles both STA abs,x (9D)
     * and STA long,x (9F) addressing modes.
     */
    private fun traceVarSetInCaller(rom: ByteArray, callerPc: Int, aiBank: Int, varRef: IndirectVarRef): Int? {
        val scanLimit = minOf(160, rom.size - callerPc - 7)

        for (i in 0 until scanLimit) {
            val pc = callerPc + i
            if (varRef.isLong) {
                if (rom[pc].toInt() and 0xFF != 0x9F) continue
                if (pc + 3 >= rom.size) continue
                val addr = readU16(rom, pc + 1) or
                    ((rom[pc + 3].toInt() and 0xFF) shl 16)
                if (addr != varRef.address) continue
            } else {
                if (rom[pc].toInt() and 0xFF != 0x9D) continue
                val addr = readU16(rom, pc + 1)
                if (addr != varRef.address) continue
            }

            val result = traceValueSource(rom, pc, i, aiBank)
            if (result != null) return result
        }
        return null
    }

    /**
     * Common value-source tracing: given a STA instruction at [staPc],
     * search the preceding bytes for an LDA pattern that reveals the value.
     */
    private fun traceValueSource(rom: ByteArray, staPc: Int, offsetFromStart: Int, aiBank: Int): Int? {
        for (back in 3..10) {
            if (offsetFromStart < back) continue
            val src = staPc - back
            val op = rom[src].toInt() and 0xFF
            when (op) {
                0xA9 -> return readU16(rom, src + 1)          // LDA #$xxxx
                0xB9 -> {                                      // LDA $xxxx,y (table)
                    val tableAddr = readU16(rom, src + 1)
                    val tablePc = romParser.snesToPc((aiBank shl 16) or tableAddr)
                    if (tablePc >= 0 && tablePc + 1 < rom.size)
                        return readU16(rom, tablePc)
                }
                0xA0 -> {                                      // LDY #$xxxx then TYA
                    val possibleTya = rom.getOrNull(staPc - 1)?.toInt()?.and(0xFF)
                    if (possibleTya == 0x98 || back == 3)
                        return readU16(rom, src + 1)
                }
            }
        }
        // Also check TYA (98) immediately before the STA, with LDY #imm earlier
        if (offsetFromStart >= 1 && rom[staPc - 1].toInt() and 0xFF == 0x98) {
            for (j in 2..30) {
                if (offsetFromStart >= j && rom[staPc - j].toInt() and 0xFF == 0xA0) {
                    return readU16(rom, staPc - j + 1)
                }
            }
        }
        return null
    }

    private fun scanForInstrListPtr(rom: ByteArray, startPc: Int, aiBank: Int): Int? {
        val scanLimit = minOf(120, rom.size - startPc - 5)

        for (i in 0 until scanLimit) {
            val pc = startPc + i
            // Look for STA $0F92,x = 9D 92 0F
            if (rom[pc].toInt() and 0xFF != 0x9D) continue
            if (rom[pc + 1].toInt() and 0xFF != 0x92) continue
            if (rom[pc + 2].toInt() and 0xFF != 0x0F) continue

            // Found STA $0F92,x. Trace back for the value source.
            val result = traceValueSource(rom, pc, i, aiBank)
            if (result != null) return result
        }

        return null
    }

    private fun findFirstSpritemap(
        rom: ByteArray, instrListPtr: Int, aiBank: Int, tileCount: Int = Int.MAX_VALUE
    ): Spritemap? {
        return findFirstSpritemapImpl(rom, instrListPtr, aiBank, tileCount, depth = 0)
    }

    private fun findFirstSpritemapImpl(
        rom: ByteArray, instrListPtr: Int, aiBank: Int, tileCount: Int, depth: Int
    ): Spritemap? {
        val ilPc = romParser.snesToPc((aiBank shl 16) or instrListPtr)
        if (ilPc < 0) return null

        // Format A: [timer(2), spritemap_ptr(2)] — standard
        // Entries with timer >= 0x8000 are handler/control opcodes (skipped).
        // Spritemap pointers must be in the LoROM ROM range ($8000-$FFFF) since
        // addresses $0000-$7FFF are WRAM/hardware on SNES, not valid data locations.
        var offset = 0
        for (frame in 0 until 64) {
            if (ilPc + offset + 3 >= rom.size) break
            val word0 = readU16(rom, ilPc + offset)
            val word1 = readU16(rom, ilPc + offset + 2)
            offset += 4

            if (word0 == 0 && word1 == 0) break
            if (word0 >= 0x8000) continue
            if (word0 == 0) continue
            if (word1 < 0x8000) continue

            val smapSnes = (aiBank shl 16) or word1
            val smap = parseSpritemap(smapSnes)
            if (smap != null && spritemapFitsTileData(smap, tileCount)) return smap
        }

        // Some enemies (e.g. Sidehopper) use a 2-level AI structure where handler
        // params in the first instruction list point to secondary instruction lists
        // that contain the actual animation frames. Follow handler params one level deep.
        if (depth < 1) {
            offset = 0
            for (frame in 0 until 16) {
                if (ilPc + offset + 3 >= rom.size) break
                val word0 = readU16(rom, ilPc + offset)
                val word1 = readU16(rom, ilPc + offset + 2)
                offset += 4

                if (word0 == 0 && word1 == 0) break
                // Handler entry: word0 >= 0x8000, param (word1) is a valid LoROM address
                if (word0 >= 0x8000 && word1 in 0x8000..0xFFFF) {
                    val sub = findFirstSpritemapImpl(rom, word1, aiBank, tileCount, depth + 1)
                    if (sub != null && sub.entries.size >= 3 && isPlausibleSpritemap(sub)
                        && spritemapFitsTileData(sub, tileCount)) return sub
                }
            }
        }

        // Format B fallback: try word1/word0 as direct spritemap addresses.
        // Use strict validation to avoid matching random ROM data.
        offset = 0
        for (frame in 0 until 64) {
            if (ilPc + offset + 3 >= rom.size) break
            val word0 = readU16(rom, ilPc + offset)
            val word1 = readU16(rom, ilPc + offset + 2)
            offset += 4

            if (word0 == 0 && word1 == 0) break

            // Try word1 as spritemap (handler's param may be a spritemap pointer)
            if (word1 > 0 && word1 < 0x8000) {
                val smapSnes = (aiBank shl 16) or word1
                val smap = parseSpritemap(smapSnes)
                if (smap != null && isPlausibleSpritemap(smap)) return smap
            }
            // Try word0 as spritemap
            if (word0 > 0) {
                val smapSnes = (aiBank shl 16) or word0
                val smap = parseSpritemap(smapSnes)
                if (smap != null && isPlausibleSpritemap(smap)) return smap
            }
        }

        return null
    }

    /**
     * Check if all tile indices in the spritemap would fit within [tileCount] tiles
     * arranged in a 16-tile-wide VRAM grid. Used to validate direction table results.
     */
    private fun spritemapFitsTileData(smap: Spritemap, tileCount: Int): Boolean {
        for (entry in smap.entries) {
            val local = entry.tileNum and 0xFF
            if (entry.is16x16) {
                // 16x16 uses tiles [N, N+1, N+16, N+17]
                val maxTile = maxOf(local + 1, local + 17)
                if (maxTile >= tileCount) return false
            } else {
                if (local >= tileCount) return false
            }
        }
        return true
    }

    /**
     * Validate that a parsed spritemap looks like real sprite data, not random code bytes.
     * Used to filter false positives in Format B fallback.
     */
    private fun isPlausibleSpritemap(smap: Spritemap): Boolean {
        if (smap.entries.size > 24) return false
        val nameTableBits = smap.entries.map { it.tileNum and 0x100 }.toSet()
        // All entries should use the same name table (0 or 1)
        if (nameTableBits.size > 1) return false
        // Bounding box should be reasonable
        val minX = smap.entries.minOf { it.xOffset }
        val maxX = smap.entries.maxOf { it.xOffset + (if (it.is16x16) 16 else 8) }
        val minY = smap.entries.minOf { it.yOffset }
        val maxY = smap.entries.maxOf { it.yOffset + (if (it.is16x16) 16 else 8) }
        if (maxX - minX > 128 || maxY - minY > 128) return false
        return true
    }

    /**
     * Build a [SpriteAnimation] from an enemy's instruction list frames.
     * Renders each frame's spritemap using the provided tile data and palette.
     *
     * @param speciesId  Enemy species ID (hex offset in bank $A0)
     * @param tileData   Raw 4bpp tile bytes for this enemy
     * @param palette    16-color ARGB palette (index 0 = transparent)
     * @param enemyName  Human-readable name for the animation
     * @return SpriteAnimation with rendered frames, or null if no frames found
     */
    fun buildAnimation(
        speciesId: Int,
        tileData: ByteArray,
        palette: IntArray,
        enemyName: String = "Enemy 0x${speciesId.toString(16).uppercase()}"
    ): SpriteAnimation? {
        val rawFrames = findAnimationFrames(speciesId)
        if (rawFrames.isEmpty()) return null

        val animFrames = rawFrames.mapNotNull { frame ->
            val assembled = renderSpritemap(frame.spritemap, tileData, palette) ?: return@mapNotNull null
            SpriteAnimationFrame(
                pixels = assembled.pixels,
                width = assembled.width,
                height = assembled.height,
                durationTicks = frame.duration,
                label = "$enemyName Frame"
            )
        }
        if (animFrames.isEmpty()) return null

        return SpriteAnimation(
            name = enemyName,
            frames = animFrames,
            loop = true
        )
    }

    private fun parseAnimationFrames(
        rom: ByteArray, instrListPtr: Int, aiBank: Int, maxFrames: Int
    ): List<AnimationFrame> {
        val ilPc = romParser.snesToPc((aiBank shl 16) or instrListPtr)
        if (ilPc < 0) return emptyList()

        val frames = mutableListOf<AnimationFrame>()
        val seenPtrs = mutableSetOf<Int>()
        var offset = 0

        for (frame in 0 until maxFrames) {
            if (ilPc + offset + 3 >= rom.size) break
            val word0 = readU16(rom, ilPc + offset)
            val word1 = readU16(rom, ilPc + offset + 2)
            offset += 4

            if (word0 == 0 && word1 == 0) break
            // GOTO opcode — stop to avoid infinite loop
            if (word0 == 0x8000) break
            if (word0 >= 0x8000) continue

            if (word1 in seenPtrs) continue
            seenPtrs.add(word1)

            val smapSnes = (aiBank shl 16) or word1
            val smap = parseSpritemap(smapSnes)
            if (smap != null) {
                frames.add(AnimationFrame(word0, smap))
            }
        }
        return frames
    }

}
