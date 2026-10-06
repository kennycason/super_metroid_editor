package com.supermetroid.editor.rom

/**
 * Source-backed renderer for Phantoon's independently animated BG2 parts.
 *
 * The four enemy slots are body ($E4BF), eye ($E4FF), tentacles ($E53F), and mouth
 * ($E57F). Their enemy headers all point at the raw `Tiles_Phantoon` OBJ payload, but
 * the visible boss is drawn with 22 extended BG2 tilemaps in bank $A7. Those tilemaps
 * reference tiles in the live Wrecked Ship room tileset and write into the 32x32 BG2
 * map beginning at destination $2000.
 *
 * Phantoon's parts have separate instruction lists, so the engine can combine their
 * frames independently. [renderFullBody] and [renderAnimation] preserve their shared
 * BG2 coordinates while using representative source-valid states for the other parts.
 */
class PhantoonSpritemap(private val romParser: RomParser) {

    companion object {
        const val PHANTOON_ROOM_SNES = 0x8FCD13
        const val PHANTOON_TILESET_ID = 0x05
        private const val PHANTOON_ROOM_HANDLE = "phantoon"
        private const val PHANTOON_ROOM_NAME = "Phantoon's Room"
        private const val PHANTOON_SPECIES_ID = 0xE4BF
        private const val TILEMAP_BASE_DEST = 0x2000
        const val COMPOSITE_COLUMNS = 10
        const val COMPOSITE_ROWS = 14
        const val EMPTY_TILE = RomConstants.EMPTY_TILE
        const val PALETTE_ROW = 7

        /** Active full-health palette (`Palette_Phantoon_HealthBased_7`). */
        const val PALETTE_SNES = 0xA7CC21

        val PALETTE_STAGES = listOf(
            PaletteStageDef("health-0", "Health 1/8 · ≤312 HP", 0xA7CB41),
            PaletteStageDef("health-1", "Health 2/8", 0xA7CB61),
            PaletteStageDef("health-2", "Health 3/8", 0xA7CB81),
            PaletteStageDef("health-3", "Health 4/8", 0xA7CBA1),
            PaletteStageDef("health-4", "Health 5/8", 0xA7CBC1),
            PaletteStageDef("health-5", "Health 6/8", 0xA7CBE1),
            PaletteStageDef("health-6", "Health 7/8", 0xA7CC01),
            PaletteStageDef("health-7", "Health 8/8 · full", PALETTE_SNES),
        )

        val BODY_TILEMAPS = listOf(
            ComponentDef("Body", "E4BF", ComponentGroup.BODY, "ExtendedTilemap_Phantoon_Body", 0xA7E0AA),
        )

        val EYE_TILEMAPS = listOf(
            ComponentDef("Eye · open", "E4FF", ComponentGroup.EYE, "ExtendedTilemap_Phantoon_Eye_Open", 0xA7E1CE),
            ComponentDef("Eye · opening / closing", "E4FF", ComponentGroup.EYE, "ExtendedTilemap_Phantoon_Eye_OpeningClosing", 0xA7E202),
            ComponentDef("Eye · closed", "E4FF", ComponentGroup.EYE, "ExtendedTilemap_Phantoon_Eye_Closed", 0xA7E236),
        )

        val EYEBALL_TILEMAPS = listOf(
            ComponentDef("Eyeball · centered", "E4FF", ComponentGroup.EYEBALL, "ExtendedTilemap_Phantoon_Eyeball_Centered", 0xA7E26A),
            ComponentDef("Eyeball · up", "E4FF", ComponentGroup.EYEBALL, "ExtendedTilemap_Phantoon_Eyeball_LookingUp", 0xA7E27E),
            ComponentDef("Eyeball · up-right", "E4FF", ComponentGroup.EYEBALL, "ExtendedTilemap_Phantoon_Eyeball_LookingUpRight", 0xA7E30A),
            ComponentDef("Eyeball · right", "E4FF", ComponentGroup.EYEBALL, "ExtendedTilemap_Phantoon_Eyeball_LookingRight", 0xA7E2BA),
            ComponentDef("Eyeball · down-right", "E4FF", ComponentGroup.EYEBALL, "ExtendedTilemap_Phantoon_Eyeball_LookingDownRight", 0xA7E2E2),
            ComponentDef("Eyeball · down", "E4FF", ComponentGroup.EYEBALL, "ExtendedTilemap_Phantoon_Eyeball_LookingDown", 0xA7E292),
            ComponentDef("Eyeball · down-left", "E4FF", ComponentGroup.EYEBALL, "ExtendedTilemap_Phantoon_Eyeball_LookingDownLeft", 0xA7E2CE),
            ComponentDef("Eyeball · left", "E4FF", ComponentGroup.EYEBALL, "ExtendedTilemap_Phantoon_Eyeball_LookingLeft", 0xA7E2A6),
            ComponentDef("Eyeball · up-left", "E4FF", ComponentGroup.EYEBALL, "ExtendedTilemap_Phantoon_Eyeball_LookingUpLeft", 0xA7E2F6),
        )

        val TENTACLE_TILEMAPS = listOf(
            ComponentDef("Tentacle · left 0", "E53F", ComponentGroup.TENTACLE, "ExtendedTilemap_Phantoon_Tentacle_Left_0", 0xA7E31E),
            ComponentDef("Tentacle · left 1", "E53F", ComponentGroup.TENTACLE, "ExtendedTilemap_Phantoon_Tentacle_Left_1", 0xA7E32E),
            ComponentDef("Tentacle · left 2", "E53F", ComponentGroup.TENTACLE, "ExtendedTilemap_Phantoon_Tentacle_Left_2", 0xA7E33E),
            ComponentDef("Tentacle · right 0", "E53F", ComponentGroup.TENTACLE, "ExtendedTilemap_Phantoon_Tentacle_Right_0", 0xA7E34E),
            ComponentDef("Tentacle · right 1", "E53F", ComponentGroup.TENTACLE, "ExtendedTilemap_Phantoon_Tentacle_Right_1", 0xA7E35E),
            ComponentDef("Tentacle · right 2", "E53F", ComponentGroup.TENTACLE, "ExtendedTilemap_Phantoon_Tentacle_Right_2", 0xA7E36E),
        )

        val MOUTH_TILEMAPS = listOf(
            ComponentDef("Mouth · normal", "E57F", ComponentGroup.MOUTH, "ExtendedTilemap_Phantoon_Mouth_0", 0xA7E37E),
            ComponentDef("Mouth · spawning flame 0", "E57F", ComponentGroup.MOUTH, "ExtendedTilemap_Phantoon_Mouth_1", 0xA7E39A),
            ComponentDef("Mouth · spawning flame 1", "E57F", ComponentGroup.MOUTH, "ExtendedTilemap_Phantoon_Mouth_2", 0xA7E3B6),
        )

        /** Every active source-named Phantoon BG2 tilemap. */
        val COMPONENT_TILEMAPS =
            BODY_TILEMAPS + EYE_TILEMAPS + EYEBALL_TILEMAPS + TENTACLE_TILEMAPS + MOUTH_TILEMAPS

        val ANIMATIONS = listOf(
            AnimationDef("eye-open", "Eye · open", 0xA7CC53, 0xA7CC69, false, AnimationPart.EYE),
            AnimationDef("eye-close-pattern", "Eye · close and choose pattern", 0xA7CC81, 0xA7CC91, false, AnimationPart.EYE),
            AnimationDef("eye-close", "Eye · close", 0xA7CC91, 0xA7CC9D, false, AnimationPart.EYE),
            AnimationDef("tentacles", "Tentacles · loop", 0xA7CCD7, 0xA7CCEB, true, AnimationPart.TENTACLES),
            AnimationDef("mouth-flame", "Mouth · spawn flame", 0xA7CCEB, 0xA7CCF7, false, AnimationPart.MOUTH),
        )

        private val IDLE_COMPONENTS = listOf(0xA7E0AA, 0xA7E236, 0xA7E31E, 0xA7E34E, 0xA7E37E)

        /** X offset used only by the historical cropped E4BF PNG comparison. */
        const val BODY_PNG_X_OFFSET = 5
    }

    enum class ComponentGroup(val displayName: String) {
        BODY("Body"),
        EYE("Eye"),
        EYEBALL("Eyeball"),
        TENTACLE("Tentacles"),
        MOUTH("Mouth"),
    }

    enum class AnimationPart { EYE, TENTACLES, MOUTH }

    data class PaletteStageDef(val key: String, val name: String, val snesAddr: Int)

    data class ComponentDef(
        val name: String,
        val speciesId: String,
        val group: ComponentGroup,
        val sourceLabel: String,
        val tilemapSnes: Int,
    )

    data class AnimationDef(
        val key: String,
        val name: String,
        val snesAddr: Int,
        val endSnesAddrExclusive: Int,
        val loop: Boolean,
        val part: AnimationPart,
    )

    data class SourceFrame(
        val duration: Int,
        val extendedSpritemapSnes: Int,
        val tilemapSnesAddresses: List<Int>,
    )

    data class SourceAnimation(
        val definition: AnimationDef,
        val frames: List<SourceFrame>,
        val handlerSnesAddresses: List<Int>,
    )

    data class TilemapEntry(
        val gridX: Int,
        val gridY: Int,
        val tileNum: Int,
        val hFlip: Boolean,
        val vFlip: Boolean,
        val paletteRow: Int,
    )

    data class AssembledSprite(
        val name: String,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        val entries: List<TilemapEntry>,
        val tilesCols: Int,
        val tilesRows: Int,
    ) {
        fun pixelToTile(px: Int, py: Int): Triple<Int, Int, Int>? {
            if (px < 0 || py < 0 || px >= width || py >= height) return null
            val gx = px / 8
            val gy = py / 8
            val entry = entries.lastOrNull { it.gridX == gx && it.gridY == gy } ?: return null
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

    /** Load Phantoon's live room tileset and active full-health palette. */
    fun load(): Boolean {
        val rom = romParser.getRomData()
        val stateOffset = findPhantoonStateOffset() ?: return false
        tilesetId = rom[stateOffset + 3].toInt() and 0xFF
        if (tilesetId != PHANTOON_TILESET_ID) return false

        val tg = getTileGraphics()
        if (!tg.loadTileset(tilesetId)) return false
        palette = readPalette(PALETTE_STAGES.last()) ?: return false
        return true
    }

    private fun findPhantoonStateOffset(): Int? =
        findStateOffsetByPhantoonEnemyData()
            ?: findRoomStateOffsets().firstOrNull { offset ->
                romParser.getRomData()[offset + 3].toInt() and 0xFF == PHANTOON_TILESET_ID
            }

    private fun findStateOffsetByPhantoonEnemyData(): Int? {
        for (roomInfo in romParser.roomCatalog.rooms) {
            val roomId = roomInfo.getRoomIdAsInt()
            for (state in romParser.parseRoomStatesWithData(roomId)) {
                if (state.tileset !in 0 until TileGraphics.NUM_TILESETS) continue
                val hasPhantoonGfx = romParser.parseEnemyGfxSet(state.enemyGfxPtr)
                    .any { it.speciesId == PHANTOON_SPECIES_ID }
                val hasPhantoonEnemy = romParser.parseEnemyPopulation(state.enemySetPtr)
                    .any { it.id == PHANTOON_SPECIES_ID }
                if ((hasPhantoonGfx || hasPhantoonEnemy) && state.tileset == PHANTOON_TILESET_ID) {
                    return state.stateInfo.stateDataPcOffset
                }
            }
        }
        return null
    }

    private fun findRoomStateOffsets(): List<Int> {
        val catalog = romParser.roomCatalog
        if (catalog.source != RomRoomCatalogSource.STANDARD_LAYOUT) {
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

    fun readPalette(stage: PaletteStageDef): IntArray? {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(stage.snesAddr)
        if (pc < 0 || pc + 32 > rom.size) return null
        return IntArray(16) { index ->
            if (index == 0) 0x00000000 else EnemySpriteGraphics.snesColorToArgb(readWord(rom, pc + index * 2))
        }
    }

    /** Parse an extended tilemap and normalize it to the component's own top-left corner. */
    fun parseTilemap(tilemapSnes: Int): List<TilemapEntry> {
        val absolute = parseAbsoluteTilemap(tilemapSnes)
        if (absolute.isEmpty()) return emptyList()
        val minX = absolute.minOf { it.gridX }
        val minY = absolute.minOf { it.gridY }
        return absolute.map { it.copy(gridX = it.gridX - minX, gridY = it.gridY - minY) }
    }

    private fun parseAbsoluteTilemap(tilemapSnes: Int): List<TilemapEntry> {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(tilemapSnes)
        if (pc < 0 || pc + 2 > rom.size || readWord(rom, pc) != 0xFFFE) return emptyList()

        val entries = mutableListOf<TilemapEntry>()
        var offset = pc + 2
        repeat(128) {
            if (offset + 2 > rom.size) return emptyList()
            val dest = readWord(rom, offset)
            offset += 2
            if (dest == 0xFFFF) return entries
            if (dest < TILEMAP_BASE_DEST || offset + 2 > rom.size) return emptyList()
            val count = readWord(rom, offset)
            offset += 2
            if (count !in 1..32 || offset + count * 2 > rom.size) return emptyList()

            val destOffset = dest - TILEMAP_BASE_DEST
            val row = destOffset / 0x40
            val startCol = (destOffset and 0x3F) / 2
            for (index in 0 until count) {
                val word = readWord(rom, offset)
                offset += 2
                entries += TilemapEntry(
                    gridX = startCol + index,
                    gridY = row,
                    tileNum = word and 0x03FF,
                    hFlip = (word shr 14) and 1 != 0,
                    vFlip = (word shr 15) and 1 != 0,
                    paletteRow = (word shr 10) and 7,
                )
            }
        }
        return emptyList()
    }

    fun renderComponent(
        def: ComponentDef,
        paletteStage: PaletteStageDef = PALETTE_STAGES.last(),
    ): AssembledSprite? {
        val entries = parseTilemap(def.tilemapSnes)
        if (entries.isEmpty()) return null
        val columns = entries.maxOf { it.gridX } + 1
        val rows = entries.maxOf { it.gridY } + 1
        return renderEntries(def.name, entries, columns, rows, paletteStage)
    }

    /** Render a representative complete body using source-exact shared BG2 coordinates. */
    fun renderFullBody(
        paletteStage: PaletteStageDef = PALETTE_STAGES.last(),
        eyeball: ComponentDef? = null,
    ): AssembledSprite? = renderComposition(
        name = if (eyeball == null) "Full body · eye closed" else "Full body · ${eyeball.name}",
        tilemapSnesAddresses = if (eyeball == null) {
            IDLE_COMPONENTS
        } else {
            listOf(0xA7E0AA, 0xA7E1CE, eyeball.tilemapSnes, 0xA7E31E, 0xA7E34E, 0xA7E37E)
        },
        paletteStage = paletteStage,
    )

    fun renderComposition(
        name: String,
        tilemapSnesAddresses: List<Int>,
        paletteStage: PaletteStageDef = PALETTE_STAGES.last(),
    ): AssembledSprite? {
        val layer = linkedMapOf<Pair<Int, Int>, TilemapEntry>()
        for (address in tilemapSnesAddresses) {
            val entries = parseAbsoluteTilemap(address)
            if (entries.isEmpty()) return null
            for (entry in entries) {
                val key = entry.gridX to entry.gridY
                if (entry.tileNum == EMPTY_TILE) layer.remove(key) else layer[key] = entry
            }
        }
        if (layer.isEmpty()) return null
        return renderEntries(name, layer.values.toList(), COMPOSITE_COLUMNS, COMPOSITE_ROWS, paletteStage)
    }

    /** Parse one exactly bounded standard enemy instruction list used by a Phantoon part. */
    fun loadAnimation(def: AnimationDef): SourceAnimation? {
        val rom = romParser.getRomData()
        val frameParser = EnemySpritemap(romParser)
        val frames = mutableListOf<SourceFrame>()
        val handlers = mutableListOf<Int>()
        var snes = def.snesAddr
        while (snes < def.endSnesAddrExclusive) {
            val pc = romParser.snesToPc(snes)
            if (pc < 0 || pc + 2 > rom.size) return null
            val first = readWord(rom, pc)
            if (first < 0x8000) {
                if (pc + 4 > rom.size) return null
                val extendedSnes = 0xA70000 or readWord(rom, pc + 2)
                val extended = frameParser.parseExtendedSpritemap(extendedSnes) ?: return null
                val tilemaps = extended.children.mapNotNull { child ->
                    (child as? EnemySpritemap.ExtendedChild.Tilemap)?.tilemap?.snesAddress
                }
                if (tilemaps.isEmpty()) return null
                frames += SourceFrame(first, extendedSnes, tilemaps)
                snes += 4
            } else {
                handlers += 0xA70000 or first
                snes += when (first) {
                    0x808A, // Instruction_Common_CallFunctionInY
                    0x80ED, // Instruction_Common_GotoY
                    -> 4
                    else -> 2
                }
            }
        }
        if (snes != def.endSnesAddrExclusive || frames.isEmpty()) return null
        return SourceAnimation(def, frames, handlers)
    }

    fun renderAnimation(
        def: AnimationDef,
        paletteStage: PaletteStageDef = PALETTE_STAGES.last(),
    ): SpriteAnimation? {
        val source = loadAnimation(def) ?: return null
        val frames = source.frames.mapIndexedNotNull { index, frame ->
            val components = when (def.part) {
                AnimationPart.EYE -> listOf(0xA7E0AA, 0xA7E31E, 0xA7E34E, 0xA7E37E) + frame.tilemapSnesAddresses
                AnimationPart.TENTACLES -> listOf(0xA7E0AA, 0xA7E236, 0xA7E37E) + frame.tilemapSnesAddresses
                AnimationPart.MOUTH -> listOf(0xA7E0AA, 0xA7E236, 0xA7E31E, 0xA7E34E) + frame.tilemapSnesAddresses
            }.let { addresses ->
                if (def.part == AnimationPart.EYE && 0xA7E1CE in addresses) {
                    addresses + 0xA7E26A
                } else {
                    addresses
                }
            }
            val rendered = renderComposition("${def.name} ${index + 1}", components, paletteStage)
                ?: return@mapIndexedNotNull null
            SpriteAnimationFrame(
                pixels = rendered.pixels,
                width = rendered.width,
                height = rendered.height,
                durationTicks = frame.duration,
                label = "${def.name} ${index + 1}",
            )
        }
        return frames.takeIf { it.size == source.frames.size }?.let {
            SpriteAnimation(def.name, it, loop = def.loop)
        }
    }

    private fun renderEntries(
        name: String,
        entries: List<TilemapEntry>,
        columns: Int,
        rows: Int,
        paletteStage: PaletteStageDef,
    ): AssembledSprite? {
        if (columns <= 0 || rows <= 0) return null
        val tg = getTileGraphics()
        val pal = readPalette(paletteStage) ?: return null
        val width = columns * 8
        val height = rows * 8
        val pixels = IntArray(width * height)

        for (entry in entries) {
            if (entry.tileNum == EMPTY_TILE) continue
            val indices = tg.readTileIndices(entry.tileNum) ?: continue
            for (py in 0 until 8) {
                for (px in 0 until 8) {
                    val sx = if (entry.hFlip) 7 - px else px
                    val sy = if (entry.vFlip) 7 - py else py
                    val colorIndex = indices[sy * 8 + sx]
                    val argb = if (colorIndex == 0) 0x00000000 else pal[colorIndex.coerceIn(0, pal.lastIndex)]
                    val dx = entry.gridX * 8 + px
                    val dy = entry.gridY * 8 + py
                    if (dx in 0 until width && dy in 0 until height) pixels[dy * width + dx] = argb
                }
            }
        }
        return AssembledSprite(name, width, height, pixels, entries, columns, rows)
    }

    /**
     * Apply component pixel edits to the room tileset. Tilemap placement remains read-only;
     * the complete variable tileset graphics resource is persisted by the editor state.
     */
    fun applyEdits(sprite: AssembledSprite, editedPixels: IntArray, tileGraphics: TileGraphics): Set<Int> {
        val pal = palette ?: return emptySet()
        if (editedPixels.size != sprite.pixels.size) return emptySet()
        val modified = mutableSetOf<Int>()

        for (py in 0 until sprite.height) {
            for (px in 0 until sprite.width) {
                val index = py * sprite.width + px
                if (sprite.pixels[index] == editedPixels[index]) continue
                val (tileNum, tileX, tileY) = sprite.pixelToTile(px, py) ?: continue
                val argb = editedPixels[index]
                val alpha = (argb ushr 24) and 0xFF
                val colorIndex = if (alpha < 128) 0 else findNearestPaletteIndex(argb, pal)
                tileGraphics.writePixelIndex(tileNum, tileX, tileY, colorIndex)
                modified += tileNum
            }
        }
        return modified
    }

    private fun findNearestPaletteIndex(argb: Int, palette: IntArray): Int {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        var best = 1
        var bestDistance = Int.MAX_VALUE
        for (index in 1 until palette.size) {
            val paletteRed = (palette[index] shr 16) and 0xFF
            val paletteGreen = (palette[index] shr 8) and 0xFF
            val paletteBlue = palette[index] and 0xFF
            val distance =
                (red - paletteRed) * (red - paletteRed) +
                    (green - paletteGreen) * (green - paletteGreen) +
                    (blue - paletteBlue) * (blue - paletteBlue)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
            if (distance == 0) break
        }
        return best
    }

    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
