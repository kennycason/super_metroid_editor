package com.supermetroid.editor.rom

/**
 * Source-bounded renderer for Draygon's four independently animated enemy slots.
 *
 * Draygon mixes two graphics owners: BG2 tilemaps use room tileset $1C, while OAM
 * children use the shared raw `Tiles_Draygon` payload loaded at physical tile $100.
 * The body, eye, tail, and arms each advance their own bank-$A5 instruction list.
 */
class DraygonSpritemap(private val romParser: RomParser) {

    companion object {
        const val ROOM_SNES = 0x8FDA60
        const val TILESET_ID = 0x1C
        const val BODY_SPECIES_ID = 0xDE3F
        const val EYE_SPECIES_ID = 0xDE7F
        const val TAIL_SPECIES_ID = 0xDEBF
        const val ARMS_SPECIES_ID = 0xDEFF
        const val RAW_TILES_SNES = 0xB0C800
        const val RAW_TILES_SIZE = 0x2000
        const val BASE_PALETTE_SNES = 0xA5A1F7
        const val HEALTH_TABLE_SNES = 0xA596AF
        const val WHITE_FLASH_PALETTE_SNES = 0xA5A297

        private val LEFT_DEFAULTS = SlotFrames(
            body = 0xA5A3BB,
            eye = 0xA5A36B,
            tail = 0xA5A40B,
            arms = 0xA5A2DF,
        )
        private val RIGHT_DEFAULTS = SlotFrames(
            body = 0xA5A6E3,
            eye = 0xA5A693,
            tail = 0xA5A779,
            arms = 0xA5A607,
        )

        val PALETTE_STAGES = listOf(
            PaletteStageDef("health-7", "Health 8/8 · ≥5250 HP", 0, 5250),
            PaletteStageDef("health-6", "Health 7/8 · ≥4500 HP", 1, 4500),
            PaletteStageDef("health-5", "Health 6/8 · ≥3750 HP", 2, 3750),
            PaletteStageDef("health-4", "Health 5/8 · ≥3000 HP", 3, 3000),
            PaletteStageDef("health-3", "Health 4/8 · ≥2250 HP", 4, 2250),
            PaletteStageDef("health-2", "Health 3/8 · ≥1500 HP", 5, 1500),
            PaletteStageDef("health-1", "Health 2/8 · ≥750 HP", 6, 750),
            PaletteStageDef("health-0", "Health 1/8 · <750 HP", 7, 0),
        )
        val WHITE_FLASH = PaletteStageDef("white-flash", "Hurt · white flash", null, null)

        val COMPOSITIONS = listOf(
            CompositionDef("left-idle", "Facing left · idle", Side.LEFT, LEFT_DEFAULTS.eye),
            CompositionDef("left-look-left", "Facing left · look left", Side.LEFT, 0xA5A393),
            CompositionDef("left-look-right", "Facing left · look right", Side.LEFT, 0xA5A39D),
            CompositionDef("left-look-up", "Facing left · look up", Side.LEFT, 0xA5A3A7),
            CompositionDef("left-look-down", "Facing left · look down", Side.LEFT, 0xA5A3B1),
            CompositionDef("right-idle", "Facing right · idle", Side.RIGHT, RIGHT_DEFAULTS.eye),
            CompositionDef("right-look-right", "Facing right · look right", Side.RIGHT, 0xA5A6BB),
            CompositionDef("right-look-left", "Facing right · look left", Side.RIGHT, 0xA5A6C5),
            CompositionDef("right-look-up", "Facing right · look up", Side.RIGHT, 0xA5A6CF),
            CompositionDef("right-look-down", "Facing right · look down", Side.RIGHT, 0xA5A6D9),
        )

        /** The four independently positioned runtime enemy slots. */
        val COMPONENTS = listOf(
            ComponentDef(
                "body", "Body", BODY_SPECIES_ID, AnimationPart.BODY_BASE,
                LEFT_DEFAULTS.body, RIGHT_DEFAULTS.body,
                "BG2 body regions plus OBJ details",
            ),
            ComponentDef(
                "eye", "Eye", EYE_SPECIES_ID, AnimationPart.EYE,
                LEFT_DEFAULTS.eye, RIGHT_DEFAULTS.eye,
                "Independent eye position and expression slot",
            ),
            ComponentDef(
                "tail", "Tail", TAIL_SPECIES_ID, AnimationPart.TAIL,
                LEFT_DEFAULTS.tail, RIGHT_DEFAULTS.tail,
                "Independent idle, whip, and flail slot",
            ),
            ComponentDef(
                "arms", "Arms", ARMS_SPECIES_ID, AnimationPart.ARMS,
                LEFT_DEFAULTS.arms, RIGHT_DEFAULTS.arms,
                "Independent idle, grab, and dying slot",
            ),
        )

        /** Every active, source-named Draygon instruction list that contains frames. */
        val ANIMATIONS = listOf(
            AnimationDef("body-left-reset", "Body · reset", Side.LEFT, AnimationPart.BODY_BASE, 0xA597BB, 0xA597D1, false, "InstList_DraygonBody_FacingLeft_Reset"),
            AnimationDef("body-right-reset", "Body · reset", Side.RIGHT, AnimationPart.BODY_BASE, 0xA597D1, 0xA597E7, false, "InstList_DraygonBody_FacingRight_Reset"),
            AnimationDef("arms-left-idle", "Arms · idle", Side.LEFT, AnimationPart.ARMS, 0xA597E7, 0xA59803, true, "InstList_DraygonArms_FacingLeft_Idle_0"),
            AnimationDef("arms-left-apex", "Arms · near swoop apex", Side.LEFT, AnimationPart.ARMS, 0xA59813, 0xA59825, false, "InstList_DraygonArms_FacingLeft_NearSwoopApex"),
            AnimationDef("arms-left-grab", "Arms · grab", Side.LEFT, AnimationPart.ARMS, 0xA59845, 0xA59867, false, "InstList_DraygonArms_FacingLeft_Grab"),
            AnimationDef("arms-left-dying", "Arms · dying", Side.LEFT, AnimationPart.ARMS, 0xA59867, 0xA5987B, false, "InstList_DraygonArms_FacingLeft_Dying"),
            AnimationDef("body-left-idle", "Body · idle", Side.LEFT, AnimationPart.BODY_BASE, 0xA59889, 0xA59895, false, "InstList_DraygonBody_FacingLeft_Idle"),
            AnimationDef("body-left-goop", "Body · fire goop", Side.LEFT, AnimationPart.BODY_FACE, 0xA598FE, 0xA59922, false, "InstList_DraygonBody_FacingLeft_FireGoop"),
            AnimationDef("body-left-roar", "Body · roar", Side.LEFT, AnimationPart.BODY_FACE, 0xA59922, 0xA59944, false, "InstList_DraygonBody_FacingLeft_Roar"),
            AnimationDef("eye-left-idle", "Eye · idle", Side.LEFT, AnimationPart.EYE, 0xA59944, 0xA5997A, false, "InstList_DraygonEye_FacingLeft_Idle"),
            AnimationDef("eye-left-dying", "Eye · dying", Side.LEFT, AnimationPart.EYE, 0xA5997E, 0xA5999C, false, "InstList_DraygonEye_FacingLeft_Dying_1"),
            AnimationDef("eye-left-dead", "Eye · dead", Side.LEFT, AnimationPart.EYE, 0xA5999C, 0xA599AE, false, "InstList_DraygonEye_FacingLeft_Dead"),
            AnimationDef("eye-left-look-left", "Eye · look left", Side.LEFT, AnimationPart.EYE, 0xA599AE, 0xA599B4, false, "InstList_DraygonEye_FacingLeft_LookingLeft"),
            AnimationDef("eye-left-look-right", "Eye · look right", Side.LEFT, AnimationPart.EYE, 0xA599B4, 0xA599BA, false, "InstList_DraygonEye_FacingLeft_LookingRight"),
            AnimationDef("eye-left-look-up", "Eye · look up", Side.LEFT, AnimationPart.EYE, 0xA599BA, 0xA599C0, false, "InstList_DraygonEye_FacingLeft_LookingUp"),
            AnimationDef("eye-left-look-down", "Eye · look down", Side.LEFT, AnimationPart.EYE, 0xA599C0, 0xA599C6, false, "InstList_DraygonEye_FacingLeft_LookingDown"),
            AnimationDef("tail-left-idle", "Tail · idle", Side.LEFT, AnimationPart.TAIL, 0xA599C6, 0xA599FA, true, "InstList_DraygonTail_FacingLeft_Idle_0"),
            AnimationDef("tail-left-fake-whip", "Tail · fake whip", Side.LEFT, AnimationPart.TAIL, 0xA599FC, 0xA59A68, false, "InstList_DraygonTail_FacingLeft_FakeTailWhip"),
            AnimationDef("tail-left-final-whips", "Tail · final whips", Side.LEFT, AnimationPart.TAIL, 0xA59A6C, 0xA59AE6, false, "InstList_DraygonTail_FacingLeft_FinalTailWhips_1"),
            AnimationDef("tail-left-whip", "Tail · whip", Side.LEFT, AnimationPart.TAIL, 0xA59AE8, 0xA59B5A, false, "InstList_DraygonTail_FacingLeft_TailWhip"),
            AnimationDef("tail-left-flail", "Tail · flail", Side.LEFT, AnimationPart.TAIL, 0xA59B5A, 0xA59B9A, false, "InstList_DraygonTail_FacingLeft_TailFlail"),
            AnimationDef("arms-right-idle", "Arms · idle", Side.RIGHT, AnimationPart.ARMS, 0xA59BDA, 0xA59BF6, true, "InstList_DraygonArms_FacingRight_Idle_0"),
            AnimationDef("arms-right-apex", "Arms · near swoop apex", Side.RIGHT, AnimationPart.ARMS, 0xA59C06, 0xA59C18, false, "InstList_DraygonArms_FacingRight_NearSwoopApex"),
            AnimationDef("arms-right-grab", "Arms · grab", Side.RIGHT, AnimationPart.ARMS, 0xA59C38, 0xA59C5A, false, "InstList_DraygonArms_FacingRight_Grab"),
            AnimationDef("arms-right-dying", "Arms · dying", Side.RIGHT, AnimationPart.ARMS, 0xA59C5A, 0xA59C6E, false, "InstList_DraygonArms_FacingRight_Dying_0"),
            AnimationDef("body-right-idle", "Body · idle", Side.RIGHT, AnimationPart.BODY_BASE, 0xA59C7E, 0xA59C8A, false, "InstList_DraygonBody_FacingRight_Idle"),
            AnimationDef("body-right-goop", "Body · fire goop", Side.RIGHT, AnimationPart.BODY_FACE, 0xA59C90, 0xA59CB4, false, "InstList_DraygonBody_FacingRight_FireGoop"),
            AnimationDef("body-right-roar", "Body · roar", Side.RIGHT, AnimationPart.BODY_FACE, 0xA59CB4, 0xA59CD6, false, "InstList_DraygonBody_FacingRight_Roar"),
            AnimationDef("eye-right-idle", "Eye · idle", Side.RIGHT, AnimationPart.EYE, 0xA59CD6, 0xA59D0C, false, "InstList_DraygonEye_FacingRight_Idle"),
            AnimationDef("eye-right-dying", "Eye · dying", Side.RIGHT, AnimationPart.EYE, 0xA59D20, 0xA59D3E, false, "InstList_DraygonEye_FacingRight_Dying_1"),
            AnimationDef("eye-right-dead", "Eye · dead", Side.RIGHT, AnimationPart.EYE, 0xA59D3E, 0xA59D50, false, "InstList_DraygonEye_FacingRight_Dead"),
            AnimationDef("eye-right-look-right", "Eye · look right", Side.RIGHT, AnimationPart.EYE, 0xA59D50, 0xA59D56, false, "InstList_DraygonEye_FacingRight_LookingRight"),
            AnimationDef("eye-right-look-left", "Eye · look left", Side.RIGHT, AnimationPart.EYE, 0xA59D56, 0xA59D5C, false, "InstList_DraygonEye_FacingRight_LookingLeft"),
            AnimationDef("eye-right-look-up", "Eye · look up", Side.RIGHT, AnimationPart.EYE, 0xA59D5C, 0xA59D62, false, "InstList_DraygonEye_FacingRight_LookingUp"),
            AnimationDef("eye-right-look-down", "Eye · look down", Side.RIGHT, AnimationPart.EYE, 0xA59D62, 0xA59D68, false, "InstList_DraygonEye_FacingRight_LookingDown"),
            AnimationDef("tail-right-idle", "Tail · idle", Side.RIGHT, AnimationPart.TAIL, 0xA59D68, 0xA59D9C, true, "InstList_DraygonTail_FacingRight_Idle_0"),
            AnimationDef("tail-right-final-whips", "Tail · final whips", Side.RIGHT, AnimationPart.TAIL, 0xA59E25, 0xA59E9F, false, "InstList_DraygonTail_FacingRight_FinalTailWhips_1"),
            AnimationDef("tail-right-whip", "Tail · whip", Side.RIGHT, AnimationPart.TAIL, 0xA59EA1, 0xA59F13, false, "InstList_DraygonTail_FacingRight_TailWhip_0"),
            AnimationDef("tail-right-flail", "Tail · flail", Side.RIGHT, AnimationPart.TAIL, 0xA59F15, 0xA59F55, false, "InstList_DraygonTail_FacingRight_TailFlail_0"),
        )

        private val HANDLER_RECORD_BYTES = mapOf(
            0x80ED to 4,
            0x8110 to 4,
            0x812F to 2,
            0x94DD to 10,
            0x9736 to 4,
            0x9895 to 2,
            0x9B9A to 2,
            0x9C8A to 2,
            0x9E0A to 6,
            0x9F57 to 4,
            0x9F60 to 4,
            0x9F7C to 2,
            0x9FAE to 2,
            0xC47B to 4,
        )
    }

    enum class Side(val displayName: String) { LEFT("Left"), RIGHT("Right") }
    enum class AnimationPart { BODY_BASE, BODY_FACE, EYE, TAIL, ARMS }

    data class PaletteStageDef(
        val key: String,
        val name: String,
        val healthTableIndex: Int?,
        val minimumHealth: Int?,
    )

    data class CompositionDef(
        val key: String,
        val name: String,
        val side: Side,
        val eyeFrameSnes: Int,
    )

    data class ComponentDef(
        val key: String,
        val name: String,
        val speciesId: Int,
        val part: AnimationPart,
        val leftFrameSnes: Int,
        val rightFrameSnes: Int,
        val detail: String,
    ) {
        fun frameSnes(side: Side): Int = if (side == Side.LEFT) leftFrameSnes else rightFrameSnes
    }

    data class AnimationDef(
        val key: String,
        val name: String,
        val side: Side,
        val part: AnimationPart,
        val snesAddr: Int,
        val endSnesAddrExclusive: Int,
        val loop: Boolean,
        val sourceLabel: String,
    )

    data class SourceFrame(val duration: Int, val spritemapSnes: Int)
    data class SourceAnimation(
        val definition: AnimationDef,
        val frames: List<SourceFrame>,
        val handlerSnesAddresses: List<Int>,
    )

    private data class SlotFrames(val body: Int, val eye: Int, val tail: Int, val arms: Int)

    private val scanner = BossPoseScanner(romParser)
    private val tileGraphics = TileGraphics(romParser)
    private var rawEnemyTileData: ByteArray? = null
    private var renderTileData: ByteArray? = null

    fun load(enemyTileData: ByteArray? = null): Boolean {
        val raw = enemyTileData ?: EnemySpriteGraphics.loadEnemyTileData(romParser, BODY_SPECIES_ID)
            ?: return false
        if (raw.size != RAW_TILES_SIZE) return false
        val render = EnemySpriteGraphics.loadEnemyRenderTileData(romParser, BODY_SPECIES_ID, raw)
            ?: return false
        if (!tileGraphics.loadTileset(TILESET_ID)) return false
        rawEnemyTileData = raw.copyOf()
        renderTileData = render
        return readPalette(PALETTE_STAGES.first()) != null
    }

    fun getTileGraphics(): TileGraphics = tileGraphics
    fun getRawEnemyTileData(): ByteArray? = rawEnemyTileData?.copyOf()
    fun getRoomTileData(): ByteArray? = tileGraphics.extractRawTileData(0, TileGraphics.TOTAL_TILES)

    fun readPalette(stage: PaletteStageDef): IntArray? {
        if (stage == WHITE_FLASH) return readPaletteAt(WHITE_FLASH_PALETTE_SNES)
        val palette = readPaletteAt(BASE_PALETTE_SNES) ?: return null
        val tableIndex = stage.healthTableIndex ?: return palette
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(HEALTH_TABLE_SNES + tableIndex * 8)
        if (pc < 0 || pc + 8 > rom.size) return null
        for (index in 0 until 4) {
            palette[9 + index] = EnemySpriteGraphics.snesColorToArgb(readWord(rom, pc + index * 2))
        }
        return palette
    }

    fun renderComposition(
        def: CompositionDef,
        paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? {
        val defaults = defaults(def.side)
        return renderFrames(
            def.name,
            listOf(defaults.body, def.eyeFrameSnes, defaults.tail, defaults.arms),
            paletteStage,
        )
    }

    /** Render one runtime slot by itself, normalized to that component's bounds. */
    fun renderComponent(
        def: ComponentDef,
        side: Side,
        paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? = renderFrames(
        "${def.name} · facing ${side.displayName.lowercase()}",
        listOf(def.frameSnes(side)),
        paletteStage,
    )

    fun loadAnimation(def: AnimationDef): SourceAnimation? {
        val rom = romParser.getRomData()
        val frames = mutableListOf<SourceFrame>()
        val handlers = mutableListOf<Int>()
        var snes = def.snesAddr
        while (snes < def.endSnesAddrExclusive) {
            val pc = romParser.snesToPc(snes)
            if (pc < 0 || pc + 2 > rom.size) return null
            val first = readWord(rom, pc)
            if (first < 0x8000) {
                if (pc + 4 > rom.size) return null
                val frameSnes = 0xA50000 or readWord(rom, pc + 2)
                if (EnemySpritemap(romParser).parseExtendedSpritemap(frameSnes) == null) return null
                frames += SourceFrame(first, frameSnes)
                snes += 4
            } else {
                val recordBytes = HANDLER_RECORD_BYTES[first] ?: return null
                handlers += 0xA50000 or first
                snes += recordBytes
            }
        }
        if (snes != def.endSnesAddrExclusive || frames.isEmpty()) return null
        return SourceAnimation(def, frames, handlers)
    }

    fun renderAnimation(
        def: AnimationDef,
        paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): SpriteAnimation? {
        val source = loadAnimation(def) ?: return null
        val rendered = source.frames.mapIndexed { index, frame ->
            val sprite = renderFrames(
                "${def.side.displayName} · ${def.name} ${index + 1}",
                compositionFrames(def, frame.spritemapSnes),
                paletteStage,
            ) ?: return null
            frame to sprite
        }
        val normalized = normalizeFrames(rendered.map { it.second }) ?: return null
        val frames = rendered.zip(normalized.pixelsByFrame).mapIndexed { index, (sourceAndSprite, pixels) ->
            SpriteAnimationFrame(
                pixels = pixels,
                width = normalized.width,
                height = normalized.height,
                durationTicks = sourceAndSprite.first.duration,
                label = "${def.name} ${index + 1}",
            )
        }
        return SpriteAnimation("${def.side.displayName} · ${def.name}", frames, def.loop)
    }

    private data class NormalizedFrames(
        val width: Int,
        val height: Int,
        val pixelsByFrame: List<IntArray>,
    )

    private fun normalizeFrames(
        sprites: List<EnemySpritemap.AssembledSprite>,
    ): NormalizedFrames? {
        if (sprites.isEmpty()) return null
        val minX = sprites.minOf { -it.originX }
        val minY = sprites.minOf { -it.originY }
        val maxX = sprites.maxOf { it.width - it.originX }
        val maxY = sprites.maxOf { it.height - it.originY }
        val width = maxX - minX
        val height = maxY - minY
        if (width <= 0 || height <= 0) return null
        val frames = sprites.map { sprite ->
            IntArray(width * height).also { output ->
                val offsetX = -sprite.originX - minX
                val offsetY = -sprite.originY - minY
                for (y in 0 until sprite.height) {
                    for (x in 0 until sprite.width) {
                        val argb = sprite.pixels[y * sprite.width + x]
                        if ((argb ushr 24) == 0) continue
                        val dx = offsetX + x
                        val dy = offsetY + y
                        if (dx in 0 until width && dy in 0 until height) output[dy * width + dx] = argb
                    }
                }
            }
        }
        return NormalizedFrames(width, height, frames)
    }

    private fun renderFrames(
        name: String,
        addresses: List<Int>,
        paletteStage: PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? {
        val tiles = renderTileData ?: return null
        val roomTiles = getRoomTileData() ?: return null
        val palette = readPalette(paletteStage) ?: return null
        return scanner.renderDraygonComposition(name, addresses, tiles, palette, roomTiles)
    }

    private fun compositionFrames(def: AnimationDef, frameSnes: Int): List<Int> {
        val defaults = defaults(def.side)
        return when (def.part) {
            AnimationPart.BODY_BASE -> listOf(frameSnes, defaults.eye, defaults.tail, defaults.arms)
            AnimationPart.BODY_FACE -> listOf(defaults.body, frameSnes, defaults.eye, defaults.tail, defaults.arms)
            AnimationPart.EYE -> listOf(defaults.body, frameSnes, defaults.tail, defaults.arms)
            AnimationPart.TAIL -> listOf(defaults.body, defaults.eye, frameSnes, defaults.arms)
            AnimationPart.ARMS -> listOf(defaults.body, defaults.eye, defaults.tail, frameSnes)
        }
    }

    private fun defaults(side: Side): SlotFrames = if (side == Side.LEFT) LEFT_DEFAULTS else RIGHT_DEFAULTS

    private fun readPaletteAt(snesAddress: Int): IntArray? {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(snesAddress)
        if (pc < 0 || pc + 32 > rom.size) return null
        return IntArray(16) { index ->
            if (index == 0) 0x00000000 else EnemySpriteGraphics.snesColorToArgb(readWord(rom, pc + index * 2))
        }
    }

    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
