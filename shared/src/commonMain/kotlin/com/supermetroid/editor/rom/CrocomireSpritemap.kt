package com.supermetroid.editor.rom

/**
 * Source-backed renderer for Crocomire's split BG2/OBJ graphics system.
 *
 * Living Crocomire mixes room tileset $1B BG2 tilemaps with the enemy-owned
 * `Tiles_Crocomire` OBJ transfer. The death sequence replaces part of that OBJ
 * page twice: first with one of two melting payloads, then with six skeleton
 * DMA chunks. Keeping those owners separate is essential for both an accurate
 * preview and safe pixel editing.
 */
class CrocomireSpritemap(private val romParser: RomParser) {

    companion object {
        const val SPECIES_ID = 0xDDBF
        const val TONGUE_SPECIES_ID = 0xDDFF
        const val TILESET_ID = 0x1B

        const val BASE_TILES_SNES = 0xAD8000
        const val BASE_TILES_SIZE = 0x2600
        const val BASE_PHYSICAL_TILE = 0xD0
        const val MELTING_1_TILES_SNES = 0xA4A07D
        const val MELTING_2_TILES_SNES = 0xA4AC7D
        const val MELTING_TILES_SIZE = 0x0C00
        const val MELTING_PHYSICAL_TILE = 0x130
        const val SKELETON_TILES_SNES = 0xADA600
        const val SKELETON_CHUNK_SIZE = 0x0200

        const val PALETTE_MAIN_SNES = 0xA4B87D
        const val PALETTE_BG12_SNES = 0xA4B89D
        const val PALETTE_SPRITE_2_SNES = 0xA4B8BD
        const val PALETTE_SPRITE_5_SNES = 0xA4B8DD
        const val PALETTE_SPRITE_1_SNES = 0xA4B8FD
        const val PALETTE_SPRITE_3_SNES = 0xA4B91D

        private const val BG2_ORIGIN_X = -0x33
        private const val BG2_ORIGIN_Y = -0x43
        private const val COMMON_GOTO_Y = 0x80ED
        private val SKELETON_DESTINATION_TILES = intArrayOf(0x160, 0x170, 0x180, 0x190, 0x1E0, 0x1F0)
        private val BG2_Y_ADJUST_FRAMES = setOf(
            0xA4BFC4, 0xA4BFF6, 0xA4C028, 0xA4C05A,
            0xA4C08C, 0xA4C0BE, 0xA4C0F0, 0xA4C122,
            0xA4C154, 0xA4C186, 0xA4C1B8, 0xA4C1EA,
            0xA4C47A, 0xA4C4AC, 0xA4C4DE, 0xA4C510, 0xA4C542,
        )

        /** Every active Crocomire enemy instruction list, including static phase selectors. */
        val INSTRUCTION_LISTS = listOf(
            InstructionListDef("initial", "Initial", 0xA4BADE, 12, 1, Phase.LIVING, false),
            InstructionListDef("projectile-attack", "Projectile attack", 0xA4BB36, 94, 18, Phase.LIVING, false),
            InstructionListDef("projectile-recover", "Projectile attack · recover", 0xA4BB94, 26, 5, Phase.LIVING, true),
            InstructionListDef("step-forward-delay", "Step forward · delay", 0xA4BBCA, 4, 1, Phase.LIVING, false),
            InstructionListDef("step-forward", "Step forward", 0xA4BBCE, 98, 12, Phase.LIVING, false),
            InstructionListDef("step-back", "Step back · start", 0xA4BC30, 4, 1, Phase.LIVING, false),
            InstructionListDef("stepping-back", "Stepping back", 0xA4BC34, 34, 5, Phase.LIVING, false),
            InstructionListDef("wait-damage", "Wait for damage", 0xA4BC56, 130, 32, Phase.LIVING, true),
            InstructionListDef("moving-claws", "Moving claws", 0xA4BCD8, 82, 15, Phase.LIVING, false),
            InstructionListDef("roar", "Roar", 0xA4BD2A, 100, 17, Phase.LIVING, false),
            InstructionListDef("close-mouth", "Close mouth", 0xA4BD8E, 20, 4, Phase.LIVING, false),
            InstructionListDef("closed-mouth-idle", "Closed-mouth idle", 0xA4BDA2, 12, 2, Phase.LIVING, true),
            InstructionListDef("power-bomb-open", "Power bomb · mouth open", 0xA4BDAE, 4, 1, Phase.LIVING, false),
            InstructionListDef("power-bomb-half", "Power bomb · mouth half open", 0xA4BDB2, 4, 1, Phase.LIVING, false),
            InstructionListDef("power-bomb-claws", "Power bomb · moving claws", 0xA4BDB6, 80, 15, Phase.LIVING, false),
            InstructionListDef("power-bomb-charge", "Power bomb · charge", 0xA4BE06, 80, 12, Phase.LIVING, true),
            InstructionListDef("tongue", "Tongue", 0xA4BE56, 20, 4, Phase.LIVING, true),
            InstructionListDef("near-wall-roar", "Near spike wall · roar", 0xA4BE7E, 110, 18, Phase.LIVING, false),
            InstructionListDef("near-wall-charge", "Near spike wall · charge", 0xA4BEEC, 80, 12, Phase.LIVING, true),
            InstructionListDef("back-off-wall", "Back away from spike wall", 0xA4BF3C, 38, 5, Phase.LIVING, true),
            InstructionListDef("sleep", "Sleep", 0xA4BF62, 2, 0, Phase.LIVING, false),
            InstructionListDef("melt-1-row-1", "Melt 1 · top row", 0xA4BF64, 8, 1, Phase.MELTING_1, true),
            InstructionListDef("melt-1-row-2", "Melt 1 · top 2 rows", 0xA4BF6C, 6, 1, Phase.MELTING_1, false),
            InstructionListDef("melt-1-row-3", "Melt 1 · top 3 rows", 0xA4BF72, 6, 1, Phase.MELTING_1, false),
            InstructionListDef("melt-1-row-4", "Melt 1 · top 4 rows", 0xA4BF78, 6, 1, Phase.MELTING_1, false),
            InstructionListDef("melt-2-row-1", "Melt 2 · top row", 0xA4BF7E, 8, 1, Phase.MELTING_2, true),
            InstructionListDef("melt-2-row-2", "Melt 2 · top 2 rows", 0xA4BF86, 6, 1, Phase.MELTING_2, false),
            InstructionListDef("melt-2-row-3", "Melt 2 · top 3 rows", 0xA4BF8C, 6, 1, Phase.MELTING_2, false),
            InstructionListDef("melt-2-row-4", "Melt 2 · top 4 rows", 0xA4BF92, 6, 1, Phase.MELTING_2, false),
            InstructionListDef("tongue-melting", "Tongue · melting", 0xA4BF98, 24, 5, Phase.LIVING, true),
            InstructionListDef("bridge-collapsed", "Bridge collapsed", 0xA4BFB0, 20, 4, Phase.LIVING, false),
            InstructionListDef("skeleton-falling", "Skeleton · falling", 0xA4E14A, 14, 3, Phase.SKELETON, false),
            InstructionListDef("skeleton-collapse", "Skeleton · collapse", 0xA4E158, 110, 20, Phase.SKELETON, false),
            InstructionListDef("skeleton-apart", "Skeleton · fallen apart", 0xA4E1C6, 6, 1, Phase.SKELETON, false),
            InstructionListDef("skeleton-dead", "Skeleton · dead", 0xA4E1CC, 6, 1, Phase.SKELETON, false),
            InstructionListDef("skeleton-river", "Skeleton · river", 0xA4E1D2, 44, 10, Phase.SKELETON, true),
        )

        /** Multi-frame sequences presented in the guided animation UI. */
        val ANIMATIONS = INSTRUCTION_LISTS.filter { it.expectedFrameCount > 1 }

        val COMPOSITIONS = listOf(
            CompositionDef("initial", "Initial stance", 0xA4C2EC, Phase.LIVING),
            CompositionDef("mouth-closed", "Mouth closed", 0xA4C574, Phase.LIVING),
            CompositionDef("mouth-half", "Mouth half open", 0xA4C5AE, Phase.LIVING),
            CompositionDef("mouth-open", "Mouth fully open", 0xA4C5E8, Phase.LIVING),
            CompositionDef("claws", "Moving claws", 0xA4C47A, Phase.LIVING),
            CompositionDef("charge", "Charge / step back", 0xA4BFC4, Phase.LIVING),
            CompositionDef("melt-1", "Melting · first pass", 0xA4CA9C, Phase.MELTING_1),
            CompositionDef("melt-2", "Melting · second pass", 0xA4CAC4, Phase.MELTING_2),
            CompositionDef("skeleton", "Skeleton", 0xA4E1FE, Phase.SKELETON),
            CompositionDef("skeleton-dead", "Skeleton · fallen apart", 0xA4E6B2, Phase.SKELETON),
        )

        val COMPONENTS = listOf(
            ComponentDef("body-bg2", "Body / tail · BG2", 0xA4C2EC, Phase.LIVING, ComponentLayer.BG2),
            ComponentDef("head-limbs", "Head / limbs · OBJ", 0xA4C2EC, Phase.LIVING, ComponentLayer.OBJ),
            ComponentDef("tongue", "Tongue · OBJ", 0xA4C65E, Phase.LIVING, ComponentLayer.ALL),
            ComponentDef("melting", "Melting body · OBJ", 0xA4CA9C, Phase.MELTING_1, ComponentLayer.ALL),
            ComponentDef("skeleton", "Skeleton · OBJ", 0xA4E1FE, Phase.SKELETON, ComponentLayer.ALL),
        )

        val PIXEL_SOURCES = listOf(
            PixelSourceDef("base", "Enemy OBJ", BASE_TILES_SNES, BASE_TILES_SIZE, true, "Tiles_Crocomire"),
            PixelSourceDef("room", "Room BG2", 0xBDFE2A, 0, false, "Tiles_1B_Crocomire · tileset \$1B"),
            PixelSourceDef("melt-1", "Melting pass 1", MELTING_1_TILES_SNES, MELTING_TILES_SIZE, false, "Tiles_Crocomire_Melting1"),
            PixelSourceDef("melt-2", "Melting pass 2", MELTING_2_TILES_SNES, MELTING_TILES_SIZE, false, "Tiles_Crocomire_Melting2"),
            PixelSourceDef("skeleton", "Skeleton DMA", SKELETON_TILES_SNES, SKELETON_CHUNK_SIZE * 6, false, "Tiles_CrocomireSkeleton_0..5"),
        )
    }

    enum class Phase { LIVING, MELTING_1, MELTING_2, SKELETON }
    enum class ComponentLayer { ALL, BG2, OBJ }

    data class InstructionListDef(
        val key: String,
        val name: String,
        val snesAddress: Int,
        val byteCount: Int,
        val expectedFrameCount: Int,
        val phase: Phase,
        val loop: Boolean,
    )

    data class CompositionDef(val key: String, val name: String, val snesAddress: Int, val phase: Phase)
    data class ComponentDef(
        val key: String,
        val name: String,
        val snesAddress: Int,
        val phase: Phase,
        val layer: ComponentLayer,
    )
    data class PixelSourceDef(
        val key: String,
        val name: String,
        val snesAddress: Int,
        val byteCount: Int,
        val editable: Boolean,
        val sourceLabel: String,
    )
    data class SourceFrame(val durationTicks: Int, val snesAddress: Int)

    private val spritemap = EnemySpritemap(romParser)
    private val tileGraphics = TileGraphics(romParser)
    private var rawEnemyTiles: ByteArray? = null

    fun load(enemyTiles: ByteArray?): Boolean {
        if (enemyTiles == null || enemyTiles.size != BASE_TILES_SIZE) return false
        if (!tileGraphics.loadTileset(TILESET_ID)) return false
        rawEnemyTiles = enemyTiles.copyOf()
        return true
    }

    fun getTileGraphics(): TileGraphics = tileGraphics
    fun getRawEnemyTileData(): ByteArray? = rawEnemyTiles?.copyOf()

    fun readPalette(): IntArray? = readPaletteAt(PALETTE_MAIN_SNES)

    fun readPixelSource(definition: PixelSourceDef): ByteArray? = when (definition.key) {
        "base" -> getRawEnemyTileData()
        "room" -> tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES)
        else -> readBytes(definition.snesAddress, definition.byteCount)
    }

    fun sourceFrames(definition: InstructionListDef): List<SourceFrame> {
        val rom = romParser.getRomData()
        val start = romParser.snesToPc(definition.snesAddress)
        if (start < 0 || start + definition.byteCount > rom.size) return emptyList()
        val frames = mutableListOf<SourceFrame>()
        var offset = 0
        while (offset < definition.byteCount) {
            val word = readU16(rom, start + offset)
            if (word < 0x8000) {
                if (offset + 4 > definition.byteCount) return emptyList()
                val pointer = readU16(rom, start + offset + 2)
                frames += SourceFrame(word, (definition.snesAddress and 0xFF0000) or pointer)
                offset += 4
            } else {
                offset += if (word == COMMON_GOTO_Y) 4 else 2
            }
        }
        return frames
    }

    fun renderComposition(
        definition: CompositionDef,
        mainPaletteOverride: IntArray? = null,
    ): EnemySpritemap.AssembledSprite? =
        renderFrame(definition.snesAddress, definition.phase, mainPaletteOverride)

    fun renderComponent(
        definition: ComponentDef,
        mainPaletteOverride: IntArray? = null,
    ): EnemySpritemap.AssembledSprite? {
        val frame = spritemap.parseRenderableFrame(definition.snesAddress) ?: return null
        val filtered = if (frame is EnemySpritemap.RenderableFrame.Extended && definition.layer != ComponentLayer.ALL) {
            val children = frame.spritemap.children.filter { child ->
                when (definition.layer) {
                    ComponentLayer.ALL -> true
                    ComponentLayer.BG2 -> child is EnemySpritemap.ExtendedChild.Tilemap
                    ComponentLayer.OBJ -> child is EnemySpritemap.ExtendedChild.Oam
                }
            }
            EnemySpritemap.RenderableFrame.Extended(frame.spritemap.copy(children = children))
        } else {
            frame
        }
        return renderParsedFrame(filtered, definition.phase, mainPaletteOverride)
    }

    fun renderAnimation(
        definition: InstructionListDef,
        mainPaletteOverride: IntArray? = null,
    ): SpriteAnimation? {
        val frames = sourceFrames(definition)
        if (frames.isEmpty()) return null
        if (frames.size != definition.expectedFrameCount) return null
        val rendered = frames.mapIndexedNotNull { index, source ->
            val image = renderFrame(source.snesAddress, definition.phase, mainPaletteOverride)
                ?: return@mapIndexedNotNull null
            SpriteAnimationFrame(
                pixels = image.pixels,
                width = image.width,
                height = image.height,
                durationTicks = source.durationTicks.takeIf { it in 1..120 } ?: 8,
                label = "${definition.name} ${index + 1}",
            )
        }
        if (rendered.size != frames.size) return null
        return SpriteAnimation(definition.name, rendered, definition.loop)
    }

    private fun renderFrame(
        snesAddress: Int,
        phase: Phase,
        mainPaletteOverride: IntArray?,
    ): EnemySpritemap.AssembledSprite? {
        val frame = spritemap.parseRenderableFrame(snesAddress) ?: return null
        return renderParsedFrame(frame, phase, mainPaletteOverride)
    }

    private fun renderParsedFrame(
        frame: EnemySpritemap.RenderableFrame,
        phase: Phase,
        mainPaletteOverride: IntArray?,
    ): EnemySpritemap.AssembledSprite? {
        val palette = mainPaletteOverride ?: readPalette() ?: return null
        val objTiles = physicalObjTiles(phase) ?: return null
        val roomTiles = tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES) ?: return null
        return spritemap.renderRenderableFrame(
            frame = frame,
            tileData = objTiles,
            palette = palette,
            options = renderOptions(frame.snesAddress, palette),
            extendedTilemapTileData = roomTiles,
        )
    }

    private fun physicalObjTiles(phase: Phase): ByteArray? {
        val base = rawEnemyTiles ?: return null
        val roomTiles = tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES) ?: return null
        val out = roomTiles.copyOf()
        val baseDestination = BASE_PHYSICAL_TILE * RomConstants.BYTES_PER_4BPP_TILE
        if (baseDestination + base.size > out.size) return null
        base.copyInto(out, destinationOffset = baseDestination)

        when (phase) {
            Phase.LIVING -> Unit
            Phase.MELTING_1, Phase.MELTING_2 -> {
                val source = if (phase == Phase.MELTING_1) MELTING_1_TILES_SNES else MELTING_2_TILES_SNES
                val bytes = readBytes(source, MELTING_TILES_SIZE) ?: return null
                val destination = MELTING_PHYSICAL_TILE * RomConstants.BYTES_PER_4BPP_TILE
                if (destination + bytes.size > out.size) return null
                bytes.copyInto(out, destinationOffset = destination)
            }
            Phase.SKELETON -> {
                for (index in SKELETON_DESTINATION_TILES.indices) {
                    val bytes = readBytes(
                        SKELETON_TILES_SNES + index * SKELETON_CHUNK_SIZE,
                        SKELETON_CHUNK_SIZE,
                    ) ?: return null
                    val destination = SKELETON_DESTINATION_TILES[index] * RomConstants.BYTES_PER_4BPP_TILE
                    if (destination + bytes.size > out.size) return null
                    bytes.copyInto(out, destinationOffset = destination)
                }
            }
        }
        return out
    }

    private fun renderOptions(frameAddress: Int, mainPalette: IntArray): EnemySpritemap.RenderOptions {
        val yAdjust = if (frameAddress in BG2_Y_ADJUST_FRAMES) {
            readS16(frameAddress + 0x1C) ?: 0
        } else {
            0
        }
        val palettes = buildMap {
            put(7, mainPalette)
            readPaletteAt(PALETTE_SPRITE_1_SNES)?.let { put(1, it) }
            readPaletteAt(PALETTE_SPRITE_2_SNES)?.let { put(2, it) }
            readPaletteAt(PALETTE_SPRITE_3_SNES)?.let { put(3, it) }
            readPaletteAt(PALETTE_SPRITE_5_SNES)?.let { put(5, it) }
        }
        return EnemySpritemap.RenderOptions(
            normalizeExtendedTilemaps = false,
            extendedTilemapOriginX = BG2_ORIGIN_X,
            extendedTilemapOriginY = BG2_ORIGIN_Y - yAdjust,
            wrapExtendedTilemapTilePage = false,
            extendedTilemapsBehindOam = true,
            reverseExtendedOamDrawOrder = true,
            oamTileNumberMode = EnemySpritemap.OamTileNumberMode.LOW_9,
            oamPaletteRows = palettes,
            extendedTilemapBlankTiles = setOf(0x0338, 0x0147, 0x02FF),
        )
    }

    private fun readPaletteAt(snesAddress: Int): IntArray? {
        val bytes = readBytes(snesAddress, 32) ?: return null
        return IntArray(16) { index ->
            if (index == 0) 0 else EnemySpriteGraphics.snesColorToArgb(readU16(bytes, index * 2))
        }
    }

    private fun readBytes(snesAddress: Int, size: Int): ByteArray? {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(snesAddress)
        if (pc < 0 || size < 0 || pc + size > rom.size) return null
        return rom.copyOfRange(pc, pc + size)
    }

    private fun readS16(snesAddress: Int): Int? {
        val bytes = readBytes(snesAddress, 2) ?: return null
        val raw = readU16(bytes, 0)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
