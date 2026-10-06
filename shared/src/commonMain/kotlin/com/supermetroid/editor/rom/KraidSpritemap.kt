package com.supermetroid.editor.rom

/**
 * Handles Kraid's BG2 composition from room tileset $1A (decimal 26). Kraid's linked OAM
 * parts use a separate raw sheet at $AB:CC00 and are rendered by [EnemySpritemap].
 *
 * The upper/lower room maps ($B9:FA38/$B9:FE3E) form a 64x64 BG2 map. The four maps at
 * $A7:97C8+ are 32x12 stored head frames; the engine copies only their first 32x11 words
 * over the upper-left BG2 screen. None of these addresses contains pixel graphics.
 *
 * Palette row 6 is overwritten with Palette_KraidRoomBackground ($A7:86C7) during the
 * fight. Each nametable entry specifies which palette row to use.
 */
class KraidSpritemap(private val romParser: RomParser) {

    companion object {
        const val KRAID_ROOM_SNES = 0x8FA59F
        const val KRAID_TILESET_ID = 0x1A
        private const val KRAID_ROOM_HANDLE = "kraid"
        private const val KRAID_ROOM_NAME = "Kraid's Room"
        const val UPPER_BG2_TILEMAP_SNES = 0xB9FA38
        const val UPPER_BG2_TILEMAP_PC = 0x1CFA38
        const val LOWER_BG2_TILEMAP_SNES = 0xB9FE3E
        const val LOWER_BG2_TILEMAP_PC = 0x1CFE3E
        @Deprecated("Use LOWER_BG2_TILEMAP_SNES")
        const val NAMETABLE_SNES = LOWER_BG2_TILEMAP_SNES
        @Deprecated("Use LOWER_BG2_TILEMAP_PC")
        const val NAMETABLE_PC = LOWER_BG2_TILEMAP_PC
        /** Palette_KraidRoomBackground — loaded to BG palette row 6 during the fight. */
        const val PALETTE_SNES = 0xA786C7
        const val PALETTE_ROW = 6
        /** Kraid's head/body words use BG palette row 7 from the room tileset. */
        const val BODY_PALETTE_ROW = 7
        /** Tileset $1A owns all 1024 BG tile slots; it has no CRE graphics overlay. */
        const val TILE_INDEX_BASE = 0
        const val TILE_COUNT = 1024
        const val EMPTY_TILE = RomConstants.EMPTY_TILE
        const val BYTES_PER_TILE = RomConstants.BYTES_PER_4BPP_TILE
        const val HEAD_STORED_ROWS = 12
        const val HEAD_VISIBLE_ROWS = 11
        const val HEAD_COLUMNS = 32
        const val HEAD_STORED_BYTES = HEAD_COLUMNS * HEAD_STORED_ROWS * 2
        const val HEAD_VISIBLE_BYTES = HEAD_COLUMNS * HEAD_VISIBLE_ROWS * 2
        /** BG2 screen size 3: four 32x32 screen blocks presented as a 64x64 map. */
        const val BG2_STRIDE = 64

        // The body origin in Kraid's live BG2 map follows MainAI_Kraid's scroll formula:
        // X = collision half-width ($38), Y = $98. The linked entities are positioned
        // relative to that body origin by their live AI, not merely by their population data.
        private const val BODY_BG2_ORIGIN_X = 0x38
        private const val BODY_BG2_ORIGIN_Y = 0x98
        // MainAI_KraidArm overwrites the population's spawn position every frame.
        private const val ARM_BODY_OFFSET_X = 0
        private const val ARM_BODY_OFFSET_Y = -0x2C
        private const val FOOT_BODY_OFFSET_X = 0
        private const val FOOT_BODY_OFFSET_Y = 0x64

        val HEAD_TILEMAPS = listOf(
            HeadTilemapDef("Head 0 · mouth closed", 0xA797C8),
            HeadTilemapDef("Head 1 · mouth opening", 0xA79AC8),
            HeadTilemapDef("Head 2 · mouth open", 0xA79DC8),
            HeadTilemapDef("Head 3 · roar", 0xA7A0C8),
        )

        val HEAD_SEQUENCES = listOf(
            HeadSequenceDef("roar", "Roar", 0xA796D2, 0xA7970E),
            HeadSequenceDef("dying-roar", "Dying roar", 0xA7970E, 0xA7974A),
            HeadSequenceDef("eye-glowing", "Eye glowing", 0xA7974A, 0xA79764),
            HeadSequenceDef("dying", "Dying", 0xA79764, 0xA79788),
        )

        /** The paired BG/OAM palette stages selected by Kraid's health handler. */
        val PALETTE_STAGES = listOf(
            PaletteStageDef("hurt", "Hurt flash", 0xA7B3D3, 0xA7B513),
            PaletteStageDef("health-1", "Health 1/8", 0xA7B3F3, 0xA7B533),
            PaletteStageDef("health-2", "Health 2/8", 0xA7B413, 0xA7B553),
            PaletteStageDef("health-3", "Health 3/8", 0xA7B433, 0xA7B573),
            PaletteStageDef("health-4", "Health 4/8", 0xA7B453, 0xA7B593),
            PaletteStageDef("health-5", "Health 5/8", 0xA7B473, 0xA7B5B3),
            PaletteStageDef("health-6", "Health 6/8", 0xA7B493, 0xA7B5D3),
            PaletteStageDef("health-7", "Health 7/8", 0xA7B4B3, 0xA7B5F3),
            PaletteStageDef("health-8", "Health 8/8 · full", 0xA7B4D3, 0xA7B613),
            // The death palette is a BG-only table. The OAM entities retain the lowest-health palette.
            PaletteStageDef("death", "Death", 0xA7B4F3, 0xA7B533),
        )

        /**
         * Every active source-named OAM instruction list used by Kraid's linked entities.
         * Bounds end at the next source object, avoiding the old whole-bank pose scan.
         */
        val OAM_SEQUENCES = listOf(
            OamSequenceDef("foot-initial", "Foot · initial", 0xE3FF, 0xA786E7, 0xA786ED, true, false),
            OamSequenceDef("foot-neutral", "Foot · neutral", 0xE3FF, 0xA786ED, 0xA786F3, true, false),
            OamSequenceDef("foot-walk-forward", "Foot · walk forward", 0xE3FF, 0xA786F3, 0xA787BD, true, true),
            OamSequenceDef("foot-lunge", "Foot · lunge", 0xE3FF, 0xA787BD, 0xA78887, true, false),
            OamSequenceDef("foot-walk-backward", "Foot · walk backward", 0xE3FF, 0xA78887, 0xA7893D, true, true),
            OamSequenceDef("arm-normal", "Arm · normal", 0xE2FF, 0xA789F3, 0xA78A41, true, true),
            OamSequenceDef("arm-slow", "Arm · slow", 0xE2FF, 0xA78A41, 0xA78A8F, true, true),
            OamSequenceDef("arm-rising", "Arm · rising / sinking", 0xE2FF, 0xA78AA4, 0xA78AF0, true, true),
            OamSequenceDef("arm-dying", "Arm · dying / pre-lunge", 0xE2FF, 0xA78AF0, 0xA78AFE, true, false),
            OamSequenceDef("lint-initial", "Lint · initial", 0xE33F, 0xA78AFE, 0xA78B04, false, false),
            OamSequenceDef("lint-big", "Lint · Kraid is big", 0xE33F, 0xA78B04, 0xA78B0A, false, false),
            OamSequenceDef("nail", "Nail · spin", 0xE43F, 0xA78B0A, 0xA78B2E, false, true),
        )
    }

    data class HeadTilemapDef(
        val name: String,
        val snesAddr: Int,
        val cols: Int = HEAD_COLUMNS,
        val storedRows: Int = HEAD_STORED_ROWS,
        val visibleRows: Int = HEAD_VISIBLE_ROWS,
    )

    data class HeadSequenceDef(
        val key: String,
        val name: String,
        val snesAddr: Int,
        val endSnesAddrExclusive: Int,
    )

    data class HeadFrame(
        val duration: Int,
        val tilemap: HeadTilemapDef,
        val vulnerableHitboxSnes: Int,
        val invulnerableHitboxSnes: Int?,
    )

    data class HeadAnimation(
        val definition: HeadSequenceDef,
        val frames: List<HeadFrame>,
        val handlerSnesAddresses: List<Int>,
    )

    data class PaletteStageDef(
        val key: String,
        val name: String,
        val bgPaletteSnes: Int,
        val oamPaletteSnes: Int,
    )

    data class OamSequenceDef(
        val key: String,
        val name: String,
        val speciesId: Int,
        val snesAddr: Int,
        val endSnesAddrExclusive: Int,
        val extended: Boolean,
        val loop: Boolean,
    )

    data class OamFrame(
        val duration: Int,
        val sourceSnes: Int,
        val renderableFrame: EnemySpritemap.RenderableFrame,
    )

    data class OamAnimation(
        val definition: OamSequenceDef,
        val frames: List<OamFrame>,
        val handlerSnesAddresses: List<Int>,
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
            val rawIdx = entry.tileNum
            if (rawIdx < 0 || rawIdx >= TILE_COUNT) return null
            val lpx = px % 8
            val lpy = py % 8
            val tpx = if (entry.hFlip) 7 - lpx else lpx
            val tpy = if (entry.vFlip) 7 - lpy else lpy
            return Triple(rawIdx, tpx, tpy)
        }
    }

    /** Exact decompressed `Tiles_1A_Kraid` bytes (the entire no-CRE tileset resource). */
    private var tileData: ByteArray? = null
    /** In-game full-health BG palette row 7, used by the head and body. */
    private var palette: IntArray? = null
    /** Room tileset handler with the active resource overrides and palette row 6 applied. */
    private var cachedTileGfx: TileGraphics? = null
    private var tilesetId: Int = -1

    fun load(): Boolean {
        return try {
            val tg = setupTileGraphics() ?: return false

            // Tileset $1A has no CRE overlay, so its safe export unit is the full 32 KiB asset.
            tileData = tg.extractRawTileData(TILE_INDEX_BASE, TILE_COUNT)

            palette = extractInGamePalette(tg)
            palette != null
        } catch (_: Exception) {
            false
        }
    }

    fun loadWithCustomTiles(customTileData: ByteArray): Boolean {
        if (customTileData.size != TILE_COUNT * BYTES_PER_TILE) return false
        tileData = customTileData.copyOf()
        val tg = setupTileGraphics() ?: return false
        tg.injectRawTileData(TILE_INDEX_BASE, customTileData)
        palette = extractInGamePalette(tg)
        return palette != null
    }

    /**
     * Load room tileset $1A and apply the fight's room-background palette to row 6.
     * The whole decompressed 32 KiB graphics resource is variable tileset data; tileset $1A
     * deliberately has no CRE graphics overlay.
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
        if (catalog.source != RomRoomCatalogSource.STANDARD_LAYOUT) {
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

    fun getTileData(): ByteArray? =
        cachedTileGfx?.extractRawTileData(TILE_INDEX_BASE, TILE_COUNT) ?: tileData?.copyOf()

    fun getPalette(): IntArray? = cachedTileGfx?.let(::extractInGamePalette) ?: palette?.copyOf()

    fun readBgPalette(stage: PaletteStageDef): IntArray? = readPalette(stage.bgPaletteSnes)

    fun readOamPalette(stage: PaletteStageDef): IntArray? = readPalette(stage.oamPaletteSnes)

    fun getTilesetId(): Int = tilesetId

    /** Read Palette_KraidRoomBackground at $A7:86C7 (BG row 6, environment). */
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

    /**
     * Render the complete live 64x64 BG2 composition.
     *
     * The room loader places the upper two 32x32 screen blocks at VRAM $4000 and the lower
     * two at $4800. [ProcessKraidInstList] then overlays 0x2C0 bytes (32x11 words) of the
     * selected head frame at the beginning of the upper-left screen block.
     */
    fun renderFullBody(
        head: HeadTilemapDef = HEAD_TILEMAPS.first(),
        paletteStage: PaletteStageDef = PALETTE_STAGES.first { it.key == "health-8" },
    ): AssembledSprite? {
        val tg = cachedTileGfx ?: return null
        val upper = romParser.decompressLZ5AtPc(romParser.snesToPc(UPPER_BG2_TILEMAP_SNES))
        val lower = romParser.decompressLZ5AtPc(romParser.snesToPc(LOWER_BG2_TILEMAP_SNES))
        if (upper.size != 0x1000 || lower.size != 0x1000) return null

        val headPc = romParser.snesToPc(head.snesAddr)
        if (headPc < 0 || headPc + HEAD_VISIBLE_BYTES > romParser.getRomData().size) return null
        romParser.getRomData().copyInto(upper, 0, headPc, headPc + HEAD_VISIBLE_BYTES)

        val linearData = ByteArray(BG2_STRIDE * BG2_STRIDE * 2)
        copyScreenPairToLinear(upper, linearData, destinationRow = 0)
        copyScreenPairToLinear(lower, linearData, destinationRow = 32)
        val bodyPalette = readBgPalette(paletteStage) ?: return null
        return renderFromTilemap(
            tg,
            linearData,
            BG2_STRIDE,
            BG2_STRIDE,
            "Live BG2 · ${head.name} · ${paletteStage.name}",
            paletteOverrides = mapOf(BODY_PALETTE_ROW to bodyPalette),
        )
    }

    /**
     * Render the representative complete live boss, including the independently drawn
     * arm/claw and foot entities that are absent from the raw BG2 composition.
     *
     * [renderFullBody] intentionally remains the exact BG2 parity surface. This higher-level
     * composition mirrors the live linked-entity AI anchors and Kraid's BG2 scroll anchor.
     */
    fun renderCompleteBody(
        head: HeadTilemapDef = HEAD_TILEMAPS.first(),
        paletteStage: PaletteStageDef = PALETTE_STAGES.first { it.key == "health-8" },
        oamTileData: ByteArray? = null,
    ): AssembledSprite? {
        val body = renderFullBody(head, paletteStage) ?: return null
        val tiles = oamTileData
            ?: EnemySpriteGraphics.loadEnemyTileData(romParser, OAM_SEQUENCES.first().speciesId)
            ?: return body
        val arm = renderRepresentativeOamPart("arm-normal", tiles, paletteStage)
        val foot = renderRepresentativeOamPart("foot-neutral", tiles, paletteStage)
        val positionedParts = listOfNotNull(
            arm?.let {
                PositionedOamPart(
                    it,
                    BODY_BG2_ORIGIN_X + ARM_BODY_OFFSET_X - it.originX,
                    BODY_BG2_ORIGIN_Y + ARM_BODY_OFFSET_Y - it.originY,
                )
            },
            foot?.let {
                PositionedOamPart(
                    it,
                    BODY_BG2_ORIGIN_X + FOOT_BODY_OFFSET_X - it.originX,
                    BODY_BG2_ORIGIN_Y + FOOT_BODY_OFFSET_Y - it.originY,
                )
            },
        )
        val minX = minOf(0, positionedParts.minOfOrNull { it.left } ?: 0)
        val minY = minOf(0, positionedParts.minOfOrNull { it.top } ?: 0)
        val maxX = maxOf(body.width, positionedParts.maxOfOrNull { it.left + it.sprite.width } ?: body.width)
        val maxY = maxOf(body.height, positionedParts.maxOfOrNull { it.top + it.sprite.height } ?: body.height)
        val width = maxX - minX
        val height = maxY - minY
        val output = IntArray(width * height)
        blitPixels(output, width, height, body.pixels, body.width, body.height, -minX, -minY)
        positionedParts.forEach { part ->
            overlayAt(
                output, width, height, part.sprite,
                part.left - minX,
                part.top - minY,
            )
        }

        return body.copy(
            name = "Complete boss · ${head.name} · arm/claw + foot · ${paletteStage.name}",
            width = width,
            height = height,
            pixels = output,
        )
    }

    /** Render only the 32x11 bytes the custom Kraid interpreter actually uploads. */
    fun renderHeadTilemap(def: HeadTilemapDef): AssembledSprite? {
        val tg = cachedTileGfx ?: return null
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(def.snesAddr)
        val dataSize = def.cols * def.visibleRows * 2
        val tmData = ByteArray(dataSize)
        System.arraycopy(rom, pc, tmData, 0, dataSize)
        return renderFromTilemap(tg, tmData, def.cols, def.visibleRows, def.name)
    }

    /** Parse one bounded list in Kraid's custom eight-byte head instruction format. */
    fun loadHeadAnimation(def: HeadSequenceDef): HeadAnimation? {
        val rom = romParser.getRomData()
        var snes = def.snesAddr
        val frames = mutableListOf<HeadFrame>()
        val handlers = mutableListOf<Int>()
        var terminated = false
        while (snes < def.endSnesAddrExclusive) {
            val pc = romParser.snesToPc(snes)
            if (pc < 0 || pc + 2 > rom.size) return null
            val first = readWord(rom, pc)
            when {
                first == 0xFFFF -> {
                    snes += 2
                    terminated = true
                    break
                }
                first and 0x8000 != 0 -> {
                    handlers.add(0xA70000 or first)
                    snes += 2
                }
                else -> {
                    if (pc + 8 > rom.size) return null
                    val tilemapSnes = 0xA70000 or readWord(rom, pc + 2)
                    val tilemap = HEAD_TILEMAPS.firstOrNull { it.snesAddr == tilemapSnes }
                        ?: return null
                    val invulnerable = readWord(rom, pc + 6)
                    frames.add(
                        HeadFrame(
                            duration = first,
                            tilemap = tilemap,
                            vulnerableHitboxSnes = 0xA70000 or readWord(rom, pc + 4),
                            invulnerableHitboxSnes = if (invulnerable == 0xFFFF) null else 0xA70000 or invulnerable,
                        )
                    )
                    snes += 8
                }
            }
        }
        if (!terminated || snes != def.endSnesAddrExclusive) return null
        return HeadAnimation(def, frames, handlers)
    }

    fun loadHeadAnimations(): List<HeadAnimation>? =
        HEAD_SEQUENCES.map { loadHeadAnimation(it) ?: return null }

    /** Render a custom Kraid head list over the complete 64x64 BG2 body. */
    fun renderFullBodyAnimation(
        def: HeadSequenceDef,
        paletteStage: PaletteStageDef = PALETTE_STAGES.first { it.key == "health-8" },
        oamTileData: ByteArray? = null,
    ): SpriteAnimation? {
        val animation = loadHeadAnimation(def) ?: return null
        val frames = animation.frames.mapIndexedNotNull { index, frame ->
            val body = renderCompleteBody(frame.tilemap, paletteStage, oamTileData)
                ?: return@mapIndexedNotNull null
            SpriteAnimationFrame(
                pixels = body.pixels,
                width = body.width,
                height = body.height,
                durationTicks = frame.duration,
                label = "${def.name} ${index + 1} · ${frame.tilemap.name}",
            )
        }
        return frames.takeIf { it.isNotEmpty() }?.let {
            SpriteAnimation(def.name, it, loop = def.key != "dying")
        }
    }

    private fun renderRepresentativeOamPart(
        sequenceKey: String,
        tileData: ByteArray,
        paletteStage: PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? {
        val definition = OAM_SEQUENCES.firstOrNull { it.key == sequenceKey } ?: return null
        val frame = loadOamAnimation(definition)?.frames?.firstOrNull() ?: return null
        val partPalette = readOamPalette(paletteStage) ?: return null
        return EnemySpritemap(romParser).renderRenderableFrame(
            frame.renderableFrame,
            tileData,
            partPalette,
        )
    }

    private data class PositionedOamPart(
        val sprite: EnemySpritemap.AssembledSprite,
        val left: Int,
        val top: Int,
    )

    private fun overlayAt(
        destination: IntArray,
        destinationWidth: Int,
        destinationHeight: Int,
        sprite: EnemySpritemap.AssembledSprite,
        left: Int,
        top: Int,
    ) {
        for (sourceY in 0 until sprite.height) {
            val destinationY = top + sourceY
            if (destinationY !in 0 until destinationHeight) continue
            for (sourceX in 0 until sprite.width) {
                val destinationX = left + sourceX
                if (destinationX !in 0 until destinationWidth) continue
                val color = sprite.pixels[sourceY * sprite.width + sourceX]
                if ((color ushr 24) != 0) {
                    destination[destinationY * destinationWidth + destinationX] = color
                }
            }
        }
    }

    private fun blitPixels(
        destination: IntArray,
        destinationWidth: Int,
        destinationHeight: Int,
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        left: Int,
        top: Int,
    ) {
        for (sourceY in 0 until sourceHeight) {
            val destinationY = top + sourceY
            if (destinationY !in 0 until destinationHeight) continue
            for (sourceX in 0 until sourceWidth) {
                val destinationX = left + sourceX
                if (destinationX !in 0 until destinationWidth) continue
                val color = source[sourceY * sourceWidth + sourceX]
                if ((color ushr 24) != 0) {
                    destination[destinationY * destinationWidth + destinationX] = color
                }
            }
        }
    }

    /** Parse one exactly bounded standard/extended OAM list from Kraid's source manifest. */
    fun loadOamAnimation(def: OamSequenceDef): OamAnimation? {
        val rom = romParser.getRomData()
        val parser = EnemySpritemap(romParser)
        val frames = mutableListOf<OamFrame>()
        val handlers = mutableListOf<Int>()
        var snes = def.snesAddr
        while (snes < def.endSnesAddrExclusive) {
            val pc = romParser.snesToPc(snes)
            if (pc < 0 || pc + 2 > rom.size) return null
            val word = readWord(rom, pc)
            if (word < 0x8000) {
                if (pc + 4 > rom.size) return null
                val frameSnes = 0xA70000 or readWord(rom, pc + 2)
                val renderable = if (def.extended) {
                    parser.parseExtendedSpritemap(frameSnes)?.let(EnemySpritemap.RenderableFrame::Extended)
                } else {
                    parser.parseSpritemap(frameSnes)?.let(EnemySpritemap.RenderableFrame::Oam)
                } ?: return null
                frames += OamFrame(word, frameSnes, renderable)
                snes += 4
            } else {
                val handler = 0xA70000 or word
                handlers += handler
                // Common goto consumes one target word. All Kraid-specific handlers in these
                // bounded lists are zero-operand RTL instructions.
                snes += if (word == 0x80ED) 4 else 2
            }
        }
        if (snes != def.endSnesAddrExclusive || frames.isEmpty()) return null
        return OamAnimation(def, frames, handlers)
    }

    fun renderOamAnimation(
        def: OamSequenceDef,
        tileData: ByteArray,
        paletteStage: PaletteStageDef = PALETTE_STAGES.first { it.key == "health-8" },
    ): SpriteAnimation? {
        val animation = loadOamAnimation(def) ?: return null
        val palette = readOamPalette(paletteStage) ?: return null
        val renderer = EnemySpritemap(romParser)
        val rendered = animation.frames.mapNotNull { frame ->
            renderer.renderRenderableFrame(frame.renderableFrame, tileData, palette)?.let { frame to it }
        }
        if (rendered.isEmpty()) return null

        // Preserve each frame's enemy-origin coordinates on one stable canvas so limbs do not
        // appear to jump merely because a frame's transparent bounds changed.
        val minX = rendered.minOf { (_, sprite) -> -sprite.originX }
        val minY = rendered.minOf { (_, sprite) -> -sprite.originY }
        val maxX = rendered.maxOf { (_, sprite) -> -sprite.originX + sprite.width }
        val maxY = rendered.maxOf { (_, sprite) -> -sprite.originY + sprite.height }
        val width = maxX - minX
        val height = maxY - minY
        val frames = rendered.mapIndexed { index, (frame, sprite) ->
            val pixels = IntArray(width * height)
            val offsetX = -sprite.originX - minX
            val offsetY = -sprite.originY - minY
            for (y in 0 until sprite.height) {
                for (x in 0 until sprite.width) {
                    val color = sprite.pixels[y * sprite.width + x]
                    if (color != 0) pixels[(offsetY + y) * width + offsetX + x] = color
                }
            }
            SpriteAnimationFrame(
                pixels = pixels,
                width = width,
                height = height,
                durationTicks = frame.duration,
                label = "${def.name} ${index + 1} · $${frame.sourceSnes.toString(16).uppercase()}",
            )
        }
        return SpriteAnimation(def.name, frames, loop = def.loop)
    }

    fun applyEdits(sprite: AssembledSprite, editedPixels: IntArray): Set<Int> {
        if (editedPixels.size != sprite.pixels.size) return emptySet()
        val tg = cachedTileGfx ?: return emptySet()
        val palettes = tg.getPalettes() ?: return emptySet()
        val modified = mutableSetOf<Int>()

        for (py in 0 until sprite.height) {
            for (px in 0 until sprite.width) {
                val idx = py * sprite.width + px
                if (sprite.pixels[idx] == editedPixels[idx]) continue
                val mapping = sprite.pixelToTile(px, py) ?: continue
                val (rawTileIdx, tpx, tpy) = mapping
                if (rawTileIdx !in 0 until TILE_COUNT) continue
                val argb = editedPixels[idx]
                val alpha = (argb ushr 24) and 0xFF
                val entry = sprite.entries.firstOrNull { it.gridX == px / 8 && it.gridY == py / 8 }
                    ?: continue
                val pal = palettes[entry.paletteRow.coerceIn(0, palettes.lastIndex)]
                val ci = if (alpha < 128) 0 else findNearestPaletteIndex(argb, pal)
                tg.writePixelIndex(rawTileIdx, tpx, tpy, ci)
                modified.add(rawTileIdx)
            }
        }

        if (modified.isNotEmpty()) {
            tileData = tg.extractRawTileData(TILE_INDEX_BASE, TILE_COUNT)
        }

        return modified
    }

    private fun renderFromTilemap(
        tg: TileGraphics,
        tmData: ByteArray,
        cols: Int,
        rows: Int,
        name: String,
        paletteOverrides: Map<Int, IntArray> = emptyMap(),
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
                renderTileToPixels(tg, palettes, entry, pixels, w, h, paletteOverrides)
            }
        }

        return AssembledSprite(name, w, h, pixels, entries, cols, rows)
    }

    private fun renderTileToPixels(
        tg: TileGraphics, palettes: Array<IntArray>,
        entry: TilemapEntry,
        pixels: IntArray,
        w: Int,
        h: Int,
        paletteOverrides: Map<Int, IntArray> = emptyMap(),
    ) {
        // Tile 0 is real Kraid artwork (for example head word $3C00). Only the
        // room's explicit blank tile $338 is empty.
        if (entry.tileNum == EMPTY_TILE) return

        val indices = tg.readTileIndices(entry.tileNum) ?: return
        val pal = paletteOverrides[entry.paletteRow]
            ?: palettes[entry.paletteRow.coerceIn(0, palettes.size - 1)]

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

    private fun copyScreenPairToLinear(
        source: ByteArray,
        destination: ByteArray,
        destinationRow: Int,
    ) {
        for (row in 0 until 32) {
            source.copyInto(destination, ((destinationRow + row) * BG2_STRIDE) * 2, row * 32 * 2, (row + 1) * 32 * 2)
            source.copyInto(
                destination,
                ((destinationRow + row) * BG2_STRIDE + 32) * 2,
                (1024 + row * 32) * 2,
                (1024 + (row + 1) * 32) * 2,
            )
        }
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

    private fun readPalette(snesAddr: Int): IntArray? {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(snesAddr)
        if (pc < 0 || pc + 32 > rom.size) return null
        return IntArray(16) { index ->
            if (index == 0) 0 else EnemySpriteGraphics.snesColorToArgb(readWord(rom, pc + index * 2))
        }
    }

    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
