package com.supermetroid.editor.rom

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Source-backed renderer for Mother Brain's two very different drawing systems.
 *
 * Phase 1 draws only the brain/head as enemy OAM; the glass case, tubes, and machinery
 * are room-owned level/BG art. Phase 2 combines an extended spritemap (BG2 torso plus
 * OAM limbs), the independently animated head, and five runtime-positioned neck segments.
 */
class MotherBrainSpritemap(private val romParser: RomParser) {

    companion object {
        const val HEAD_SPECIES_ID = 0xEC3F
        const val BODY_SPECIES_ID = 0xEC7F
        const val ROOM_SNES = 0x8FDD58
        const val TILESET_ID = 0x0E
        const val PALETTE_SNES = 0xA99472
        const val BACK_LEG_PALETTE_SNES = 0xA99492

        private const val BODY_STANDING = 0xA99FA0
        // Body OAM is already enemy-origin-relative. The BG2 scroll math makes the
        // enemy origin land at ($20,$3E) in the tilemap while standing, with small
        // deliberate X changes during walking/crouching transitions.
        private const val BG2_CENTER_X_STANDING = 0x20
        private const val BG2_CENTER_X_WALKING = 0x22
        private const val BG2_CENTER_X_CROUCH_SHIFT = 0x26
        private const val BG2_CENTER_Y = 0x3E
        private val BODY_WALKING = listOf(
            0xA99FEA, 0xA9A03C, 0xA9A08E, 0xA9A0E0,
            0xA9A12A, 0xA9A174, 0xA9A1BE, 0xA9A208,
        )
        private const val BODY_CROUCHED = 0xA9A252
        private const val BODY_UNCROUCHING = 0xA9A28C
        private const val BODY_LEANING = 0xA9A2D6
        private val BODY_DEATH_BEAM = listOf(0xA9A384, 0xA9A3CE, 0xA9A418, 0xA9A462)

        private val STANDARD_COMPONENT_ADDRESSES = listOf(
            0x00 to 0xA9A586, 0x01 to 0xA9A5BF, 0x02 to 0xA9A5F8,
            0x03 to 0xA9A62C, 0x04 to 0xA9A660, 0x05 to 0xA9A694,
            0x06 to 0xA9A69B, 0x07 to 0xA9A6D9, 0x08 to 0xA9A717,
            0x09 to 0xA9A750, 0x0A to 0xA9A789, 0x0B to 0xA9A7C2,
            0x0C to 0xA9A7F1, 0x0D to 0xA9A811, 0x0E to 0xA9A83B,
            0x0F to 0xA9A85B, 0x10 to 0xA9A862, 0x11 to 0xA9A86E,
            0x18 to 0xA9AD3E, 0x19 to 0xA9AD6D,
        )

        val HEAD_COMPONENTS: List<HeadDef> = STANDARD_COMPONENT_ADDRESSES.map { (sourceIndex, address) ->
            val name = when (sourceIndex) {
                0x00 -> "Head 0 · phase 1 / phase 2 neutral"
                0x01 -> "Head 1 · mouth opening"
                0x02 -> "Head 2 · mouth open / rainbow beam"
                0x03 -> "Head 3 · attack transition"
                0x04 -> "Head 4 · attack open"
                0x05 -> "Neck 5 · runtime segment"
                0x06 -> "Head 6 · phase 3 neutral"
                0x07 -> "Head 7 · phase 3 mouth opening"
                0x08 -> "Head 8 · phase 3 mouth open"
                0x09 -> "Head 9 · phase 3 attack transition"
                0x0A -> "Head A · phase 3 attack open"
                0x18 -> "Head 18 · corpse"
                0x19 -> "Head 19 · fallen corpse"
                else -> "Body OBJ ${sourceIndex.toString(16).uppercase()} · internal part"
            }
            HeadDef(sourceIndex, name, address)
        }

        val BODY_COMPONENTS = buildList {
            add(BodyDef("standing", "Body · standing", BODY_STANDING, BG2_CENTER_X_STANDING))
            BODY_WALKING.forEachIndexed { index, address ->
                add(BodyDef("walk-$index", "Body · walk ${index + 1}/8", address, BG2_CENTER_X_WALKING))
            }
            add(BodyDef("leaning", "Body · leaning down", BODY_LEANING, BG2_CENTER_X_CROUCH_SHIFT))
            add(BodyDef("uncrouching", "Body · crouch transition", BODY_UNCROUCHING, BG2_CENTER_X_STANDING))
            add(BodyDef("crouched", "Body · crouched", BODY_CROUCHED, BG2_CENTER_X_STANDING))
            BODY_DEATH_BEAM.forEachIndexed { index, address ->
                add(BodyDef(
                    "death-beam-$index",
                    "Body · death beam ${index + 1}/4",
                    address,
                    BG2_CENTER_X_STANDING,
                    includesHead = true,
                ))
            }
        }

        val PALETTE_STAGES = listOf(
            PaletteStageDef("default", "Default / full health", PALETTE_SNES, BACK_LEG_PALETTE_SNES),
            PaletteStageDef("health-1", "Health ≥50%", 0xADE6AC, 0xADE74C, colorsStartAtOne = true),
            PaletteStageDef("health-2", "Health 30–49%", 0xADE6CA, 0xADE76A, colorsStartAtOne = true),
            PaletteStageDef("health-3", "Health 10–29%", 0xADE6E8, 0xADE788, colorsStartAtOne = true),
            PaletteStageDef("health-4", "Health <10%", 0xADE706, 0xADE7A6, colorsStartAtOne = true),
            // Each 0x3C-byte rainbow record stores 15 main colors followed by
            // 15 independently written back-leg colors.
            PaletteStageDef("rainbow-1", "Rainbow beam 1/10", 0xADE44A, 0xADE468, colorsStartAtOne = true),
            PaletteStageDef("rainbow-2", "Rainbow beam 2/10", 0xADE486, 0xADE4A4, colorsStartAtOne = true),
            PaletteStageDef("rainbow-3", "Rainbow beam 3/10", 0xADE4C2, 0xADE4E0, colorsStartAtOne = true),
            PaletteStageDef("rainbow-4", "Rainbow beam 4/10", 0xADE4FE, 0xADE51C, colorsStartAtOne = true),
            PaletteStageDef("rainbow-5", "Rainbow beam 5/10", 0xADE53A, 0xADE558, colorsStartAtOne = true),
            PaletteStageDef("rainbow-6", "Rainbow beam 6/10", 0xADE576, 0xADE594, colorsStartAtOne = true),
            PaletteStageDef("rainbow-7", "Rainbow beam 7/10", 0xADE5B2, 0xADE5D0, colorsStartAtOne = true),
            PaletteStageDef("rainbow-8", "Rainbow beam 8/10", 0xADE5EE, 0xADE60C, colorsStartAtOne = true),
            PaletteStageDef("rainbow-9", "Rainbow beam 9/10", 0xADE62A, 0xADE648, colorsStartAtOne = true),
            PaletteStageDef("rainbow-10", "Rainbow beam 10/10", 0xADE666, 0xADE684, colorsStartAtOne = true),
        )

        private fun hf(index: Int, duration: Int) = HeadFrameDef(index, duration)
        private fun bf(
            address: Int,
            duration: Int,
            bg2CenterX: Int = BG2_CENTER_X_STANDING,
            bodyOffsetX: Int = 0,
            bodyOffsetY: Int = 0,
        ) = BodyFrameDef(address, duration, bg2CenterX, bodyOffsetX, bodyOffsetY)

        val ANIMATIONS = listOf(
            AnimationDef("phase-1-idle", "Phase 1 · idle", Phase.PHASE_1, listOf(hf(0, 4)), loop = true,
                sourceLabels = listOf("InstList_MotherBrainHead_Initial")),
            AnimationDef("phase-2-neutral", "Phase 2 · neutral", Phase.PHASE_2,
                listOf(hf(0, 4), hf(1, 4), hf(2, 8), hf(1, 4), hf(0, 4), hf(1, 4), hf(2, 8), hf(1, 4)),
                loop = true, sourceLabels = listOf(
                    "InstList_MotherBrainHead_Neutral_Phase2_0",
                    "InstList_MotherBrainHead_Neutral_Phase2_1",
                )),
            AnimationDef("walk-forward", "Phase 2 · walk forward", Phase.PHASE_2,
                listOf(hf(0, 6), hf(1, 6), hf(2, 6), hf(1, 6), hf(0, 6), hf(1, 6), hf(2, 6), hf(1, 6), hf(0, 6)),
                listOf(
                    bf(BODY_STANDING, 6),
                    bf(BODY_WALKING[0], 6, BG2_CENTER_X_WALKING, 1, -2),
                    bf(BODY_WALKING[1], 6, BG2_CENTER_X_WALKING, 3, -2),
                    bf(BODY_WALKING[2], 6, BG2_CENTER_X_WALKING, 3, -1),
                    bf(BODY_WALKING[3], 6, BG2_CENTER_X_WALKING, 6, 0),
                    bf(BODY_WALKING[4], 6, BG2_CENTER_X_WALKING, 21, -2),
                    bf(BODY_WALKING[5], 6, BG2_CENTER_X_WALKING, 27, -6),
                    bf(BODY_WALKING[6], 6, BG2_CENTER_X_WALKING, 25, -2),
                    bf(BODY_WALKING[7], 6, BG2_CENTER_X_WALKING, 24, 0),
                ),
                sourceLabels = listOf("InstList_MotherBrainBody_WalkingForwards_Medium")),
            AnimationDef("walk-backward", "Phase 2 · walk backward", Phase.PHASE_2,
                List(9) { hf(listOf(0, 1, 2, 1)[it % 4], 6) },
                listOf(
                    bf(BODY_WALKING[7], 6),
                    bf(BODY_WALKING[6], 6, BG2_CENTER_X_WALKING, 1, -2),
                    bf(BODY_WALKING[5], 6, BG2_CENTER_X_WALKING, 3, -6),
                    bf(BODY_WALKING[4], 6, BG2_CENTER_X_WALKING, -3, -2),
                    bf(BODY_WALKING[3], 6, BG2_CENTER_X_WALKING, -18, 0),
                    bf(BODY_WALKING[2], 6, BG2_CENTER_X_WALKING, -21, -1),
                    bf(BODY_WALKING[1], 6, BG2_CENTER_X_WALKING, -21, -2),
                    bf(BODY_WALKING[0], 6, BG2_CENTER_X_WALKING, -23, -2),
                    bf(BODY_STANDING, 6, BG2_CENTER_X_WALKING, -24, 0),
                ),
                sourceLabels = listOf("InstList_MotherBrainBody_WalkingBackwards_Medium")),
            AnimationDef("crouch", "Phase 2 · crouch", Phase.PHASE_2,
                List(4) { hf(0, 8) },
                listOf(
                    bf(BODY_STANDING, 8),
                    bf(BODY_LEANING, 8, BG2_CENTER_X_CROUCH_SHIFT, bodyOffsetY = 12),
                    bf(BODY_UNCROUCHING, 8, bodyOffsetY = 28),
                    bf(BODY_CROUCHED, 8, bodyOffsetY = 38),
                ),
                sourceLabels = listOf("InstList_MotherBrainBody_Crouch_Slow")),
            AnimationDef("stand-up", "Phase 2 · stand up", Phase.PHASE_2,
                List(4) { hf(0, 8) },
                listOf(
                    bf(BODY_CROUCHED, 8),
                    bf(BODY_UNCROUCHING, 8, BG2_CENTER_X_CROUCH_SHIFT, bodyOffsetY = -10),
                    bf(BODY_LEANING, 8, BG2_CENTER_X_CROUCH_SHIFT, bodyOffsetY = -26),
                    bf(BODY_STANDING, 8, bodyOffsetY = -38),
                ),
                sourceLabels = listOf("InstList_MotherBrainBody_StandingUpAfterCrouching_Fast")),
            AnimationDef("blue-rings", "Phase 2 · blue rings", Phase.PHASE_2,
                listOf(hf(2, 4), hf(3, 4), hf(4, 8), hf(4, 3), hf(4, 3), hf(4, 3), hf(4, 16), hf(3, 4), hf(2, 16)),
                sourceLabels = listOf("InstList_MotherBrainHead_Attacking_4OnionRings_Phase2")),
            AnimationDef("bomb", "Phase 2 · bomb", Phase.PHASE_2,
                listOf(hf(0, 4), hf(1, 4), hf(2, 8), hf(2, 4), hf(3, 4), hf(4, 8), hf(4, 32), hf(3, 4), hf(2, 16)),
                sourceLabels = listOf("InstList_MotherBrainHead_Attacking_Bomb_Phase2")),
            AnimationDef("laser", "Phase 2 · laser", Phase.PHASE_2,
                listOf(hf(1, 16), hf(2, 4), hf(2, 32)),
                sourceLabels = listOf("InstList_MotherBrainHead_Attacking_Laser")),
            AnimationDef("rainbow-charge", "Phase 2 · rainbow charge", Phase.PHASE_2,
                listOf(hf(2, 4), hf(1, 4), hf(0, 2), hf(0, 30)), loop = true,
                sourceLabels = listOf(
                    "InstList_MotherBrainHead_ChargingRainbowBeam_0",
                    "InstList_MotherBrainHead_ChargingRainbowBeam_1",
                )),
            AnimationDef("death-beam", "Phase 2 · death beam", Phase.PHASE_2,
                List(15) { hf(2, 1) },
                listOf(bf(BODY_STANDING, 1), bf(BODY_DEATH_BEAM[0], 1)) +
                    List(10) { bf(BODY_DEATH_BEAM[1], 1) } +
                    listOf(bf(BODY_DEATH_BEAM[2], 1), bf(BODY_DEATH_BEAM[3], 1), bf(BODY_STANDING, 240)),
                includeNeck = false, includeSeparateHead = false,
                sourceLabels = listOf("InstList_MotherBrainBody_DeathBeamMode")),
            AnimationDef("phase-3-neutral", "Phase 3 · neutral", Phase.PHASE_2,
                listOf(hf(6, 4), hf(7, 4), hf(8, 8), hf(7, 4), hf(6, 4), hf(7, 4), hf(8, 8), hf(7, 8), hf(6, 4)), loop = true,
                sourceLabels = listOf(
                    "InstList_MotherBrainHead_Neutral_Phase3_0",
                    "InstList_MotherBrainHead_Neutral_Phase3_1",
                )),
            AnimationDef("hyper-recoil", "Phase 3 · hyper beam recoil", Phase.PHASE_2,
                listOf(hf(8, 2), hf(9, 2), hf(9, 2), hf(10, 16), hf(10, 16), hf(10, 32), hf(9, 4)),
                sourceLabels = listOf("InstList_MotherBrainHead_HyperBeamRecoil_0")),
            AnimationDef("death", "Phase 3 · death / corpse", Phase.PHASE_2,
                listOf(hf(6, 2), hf(7, 2), hf(8, 64), hf(18, 64), hf(19, 2)), loop = false,
                sourceLabels = listOf(
                    "InstList_MotherBrainHead_Corpse_0",
                    "InstList_MotherBrainHead_Corpse_1",
                )),
        )

        val COMPOSITIONS = listOf(
            CompositionDef("phase-1", "Phase 1 · brain/head", Phase.PHASE_1, headIndex = 0),
            CompositionDef("phase-2", "Phase 2 · standing", Phase.PHASE_2, headIndex = 0),
            CompositionDef("phase-2-open", "Phase 2 · attacking", Phase.PHASE_2, headIndex = 4),
            CompositionDef("phase-2-crouched", "Phase 2 · crouched", Phase.PHASE_2, headIndex = 0, bodyAddress = BODY_CROUCHED),
            CompositionDef("phase-3", "Phase 3 · standing", Phase.PHASE_2, headIndex = 8),
            CompositionDef("corpse", "Phase 3 · corpse", Phase.PHASE_2, headIndex = 18),
            CompositionDef("death-beam", "Phase 2 · death beam", Phase.PHASE_2,
                headIndex = 2, bodyAddress = BODY_DEATH_BEAM[2], includeNeck = false, includeSeparateHead = false),
        )

        internal fun defaultBodyBg2CenterX(snesAddress: Int): Int = when (snesAddress) {
            in BODY_WALKING -> BG2_CENTER_X_WALKING
            BODY_LEANING -> BG2_CENTER_X_CROUCH_SHIFT
            else -> BG2_CENTER_X_STANDING
        }

        internal fun bodyRenderOptions(
            bg2CenterX: Int = BG2_CENTER_X_STANDING,
        ) = EnemySpritemap.RenderOptions(
            normalizeExtendedTilemaps = true,
            extendedTilemapOriginX = -bg2CenterX,
            extendedTilemapOriginY = -BG2_CENTER_Y,
            oamTileNumberMode = EnemySpritemap.OamTileNumberMode.LOW_9,
            extendedOamOriginX = 0,
            extendedOamOriginY = 0,
            // ProcessExtendedTilemap uses the BG2 destinations embedded in each
            // tilemap and ignores the extended child's X/Y fields. Applying those
            // fields visually splits Mother Brain's upper and lower torso in motion.
            ignoreExtendedTilemapChildOffsets = true,
            // Mother Brain's high-priority BG2 torso sits between OBJ priority 2
            // (dark rear leg) and OBJ priority 3 (bright front leg and arms).
            extendedTilemapOamPrioritySplit = 3,
            extendedTilemapBlankTiles = setOf(0x0338),
        )
    }

    enum class Phase { PHASE_1, PHASE_2 }
    data class HeadDef(val index: Int, val name: String, val snesAddress: Int)
    data class BodyDef(
        val key: String,
        val name: String,
        val snesAddress: Int,
        val bg2CenterX: Int,
        val includesHead: Boolean = false,
    )
    data class PaletteStageDef(
        val key: String,
        val name: String,
        val mainPaletteSnes: Int,
        val backLegPaletteSnes: Int,
        val colorsStartAtOne: Boolean = false,
    )
    data class HeadFrameDef(val headIndex: Int, val duration: Int)
    data class BodyFrameDef(
        val snesAddress: Int,
        val duration: Int,
        val bg2CenterX: Int,
        val bodyOffsetX: Int,
        val bodyOffsetY: Int,
    )
    data class AnimationDef(
        val key: String,
        val name: String,
        val phase: Phase,
        val headFrames: List<HeadFrameDef>,
        val bodyFrames: List<BodyFrameDef> = emptyList(),
        val loop: Boolean = false,
        val includeNeck: Boolean = true,
        val includeSeparateHead: Boolean = true,
        val sourceLabels: List<String> = emptyList(),
    )
    data class CompositionDef(
        val key: String,
        val name: String,
        val phase: Phase,
        val headIndex: Int,
        val bodyAddress: Int = BODY_STANDING,
        val includeNeck: Boolean = true,
        val includeSeparateHead: Boolean = true,
    )

    private data class PositionedSprite(
        val sprite: EnemySpritemap.AssembledSprite,
        val x: Int = 0,
        val y: Int = 0,
    )

    private val renderer = EnemySpritemap(romParser)
    private var headTileData: ByteArray? = null
    private var bodyRawTileData: ByteArray? = null
    private var bodyRenderTileData: ByteArray? = null
    private var roomTileData: ByteArray? = null

    fun load(headTiles: ByteArray? = null, bodyTiles: ByteArray? = null): Boolean {
        headTileData = headTiles ?: EnemySpriteGraphics.loadEnemyTileData(romParser, HEAD_SPECIES_ID)
        bodyRawTileData = bodyTiles ?: EnemySpriteGraphics.loadEnemyTileData(romParser, BODY_SPECIES_ID)
        val rawBody = bodyRawTileData ?: return false
        bodyRenderTileData = EnemySpriteGraphics.loadEnemyRenderTileData(romParser, BODY_SPECIES_ID, rawBody)
        roomTileData = EnemySpriteGraphics.loadMotherBrainRoomTileData(romParser)
        return headTileData != null && bodyRenderTileData != null && roomTileData != null
    }

    fun getHeadTileData(): ByteArray? = headTileData?.copyOf()
    fun getBodyRawTileData(): ByteArray? = bodyRawTileData?.copyOf()
    fun getBodySourceTileData(): ByteArray? = bodyRawTileData?.let {
        EnemySpriteGraphics.loadMotherBrainBodySourceTileData(romParser, it)
    }

    fun readPalette(stage: PaletteStageDef = PALETTE_STAGES.first()): IntArray? =
        readPalette(stage.mainPaletteSnes, stage.colorsStartAtOne)

    fun renderHead(
        definition: HeadDef,
        paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? {
        val tiles = headTileData ?: return null
        val palette = readPalette(paletteStage) ?: return null
        val spritemap = renderer.parseSpritemap(definition.snesAddress) ?: return null
        return renderer.renderSpritemap(spritemap, tiles, palette)
    }

    fun renderBody(
        definition: BodyDef,
        paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? = renderBodyAddress(
        definition.snesAddress,
        paletteStage,
        definition.bg2CenterX,
    )

    fun renderNeckSegment(
        paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? = renderHead(HEAD_COMPONENTS[5], paletteStage)

    fun renderComposition(
        definition: CompositionDef,
        paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? {
        val head = HEAD_COMPONENTS.getOrNull(definition.headIndex) ?: return null
        if (definition.phase == Phase.PHASE_1) {
            return renderHead(head, paletteStage)
        }
        return renderPhase2(
            bodyAddress = definition.bodyAddress,
            head = head,
            paletteStage = paletteStage,
            includeNeck = definition.includeNeck,
            includeSeparateHead = definition.includeSeparateHead,
        )
    }

    fun renderAnimation(
        definition: AnimationDef,
        paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): SpriteAnimation? {
        val rendered = definition.headFrames.mapIndexedNotNull { index, headFrame ->
            val head = HEAD_COMPONENTS.getOrNull(headFrame.headIndex) ?: return@mapIndexedNotNull null
            val bodyFrame = definition.bodyFrames.getOrNull(index)
            val sprite = if (definition.phase == Phase.PHASE_1) {
                renderHead(head, paletteStage)
            } else {
                renderPhase2(
                    bodyFrame?.snesAddress ?: BODY_STANDING,
                    head,
                    paletteStage,
                    definition.includeNeck,
                    definition.includeSeparateHead,
                    bodyFrame?.bg2CenterX ?: BG2_CENTER_X_STANDING,
                )
            } ?: return@mapIndexedNotNull null
            RenderedAnimationFrame(
                sprite = sprite,
                duration = bodyFrame?.duration ?: headFrame.duration,
                bodyOffsetX = bodyFrame?.bodyOffsetX ?: 0,
                bodyOffsetY = bodyFrame?.bodyOffsetY ?: 0,
            )
        }
        if (rendered.isEmpty()) return null

        val minX = rendered.minOf { it.bodyOffsetX - it.sprite.originX }
        val minY = rendered.minOf { it.bodyOffsetY - it.sprite.originY }
        val maxX = rendered.maxOf { it.bodyOffsetX - it.sprite.originX + it.sprite.width }
        val maxY = rendered.maxOf { it.bodyOffsetY - it.sprite.originY + it.sprite.height }
        val width = maxX - minX
        val height = maxY - minY
        val frames = rendered.mapIndexed { index, renderedFrame ->
            val sprite = renderedFrame.sprite
            val pixels = IntArray(width * height)
            blit(
                pixels,
                width,
                height,
                sprite,
                renderedFrame.bodyOffsetX - sprite.originX - minX,
                renderedFrame.bodyOffsetY - sprite.originY - minY,
            )
            SpriteAnimationFrame(
                pixels,
                width,
                height,
                renderedFrame.duration,
                "${definition.name} ${index + 1}/${rendered.size}",
            )
        }
        return SpriteAnimation(definition.name, frames, definition.loop)
    }

    private data class RenderedAnimationFrame(
        val sprite: EnemySpritemap.AssembledSprite,
        val duration: Int,
        val bodyOffsetX: Int,
        val bodyOffsetY: Int,
    )

    private fun renderPhase2(
        bodyAddress: Int,
        head: HeadDef,
        paletteStage: PaletteStageDef,
        includeNeck: Boolean,
        includeSeparateHead: Boolean,
        bg2CenterX: Int = BG2_CENTER_X_STANDING,
    ): EnemySpritemap.AssembledSprite? {
        val parts = mutableListOf<PositionedSprite>()
        renderBodyAddress(bodyAddress, paletteStage, bg2CenterX)?.let { parts += PositionedSprite(it) }
        val neckPositions = representativeNeckPositions()
        if (includeNeck) {
            val neck = renderNeckSegment(paletteStage)
            if (neck != null) neckPositions.forEach { (x, y) ->
                parts += PositionedSprite(neck, x, y)
            }
        }
        if (includeSeparateHead) {
            // MainAI_HurtAI_MotherBrainHead pins enemy slot 1 to neck segment 4 and
            // subtracts $15 from Y before DrawMotherBrainHead runs.
            val (headX, neckEndY) = neckPositions.first()
            renderHead(head, paletteStage)?.let {
                parts += PositionedSprite(it, headX, neckEndY - 0x15)
            }
        }
        return compose(parts)
    }

    private fun renderBodyAddress(
        snesAddress: Int,
        paletteStage: PaletteStageDef,
        bg2CenterX: Int = BG2_CENTER_X_STANDING,
    ): EnemySpritemap.AssembledSprite? {
        val tiles = bodyRenderTileData ?: return null
        val roomTiles = roomTileData ?: return null
        val palette = readPalette(paletteStage) ?: return null
        val backLeg = readPalette(paletteStage.backLegPaletteSnes, paletteStage.colorsStartAtOne) ?: palette
        val extended = renderer.parseExtendedSpritemap(snesAddress) ?: return null
        return renderer.renderRenderableFrame(
            EnemySpritemap.RenderableFrame.Extended(extended),
            tiles,
            palette,
            bodyRenderOptions(bg2CenterX).copy(
                oamPaletteRows = mapOf(1 to palette, 3 to backLeg),
            ),
            roomTiles,
        )
    }

    /** Source initialization values from SetupMotherBrainsNeckForFakeDeathAscent. */
    private fun representativeNeckPositions(): List<Pair<Int, Int>> {
        val lowerAngle = 0x48
        val upperAngle = 0x50
        val baseX = 0x20
        val baseY = -0x32
        val segment0 = polar(baseX, baseY, 0x02, lowerAngle)
        val segment1 = polar(baseX, baseY, 0x0A, lowerAngle)
        val segment2 = polar(baseX, baseY, 0x14, lowerAngle)
        val segment3 = polar(segment2.first, segment2.second, 0x0A, upperAngle)
        val segment4 = polar(segment2.first, segment2.second, 0x14, upperAngle)
        // Runtime draws 4 -> 0; preserve that painter order before drawing the head.
        return listOf(segment4, segment3, segment2, segment1, segment0)
    }

    private fun polar(x: Int, y: Int, distance: Int, angle: Int): Pair<Int, Int> {
        val radians = angle * 2.0 * PI / 256.0
        return (x + sin(radians) * distance).roundToInt() to
            (y + cos(radians) * distance).roundToInt()
    }

    private fun compose(parts: List<PositionedSprite>): EnemySpritemap.AssembledSprite? {
        if (parts.isEmpty()) return null
        val minX = parts.minOf { it.x - it.sprite.originX }
        val minY = parts.minOf { it.y - it.sprite.originY }
        val maxX = parts.maxOf { it.x - it.sprite.originX + it.sprite.width }
        val maxY = parts.maxOf { it.y - it.sprite.originY + it.sprite.height }
        val width = maxX - minX
        val height = maxY - minY
        val pixels = IntArray(width * height)
        parts.forEach { part ->
            blit(
                pixels,
                width,
                height,
                part.sprite,
                part.x - part.sprite.originX - minX,
                part.y - part.sprite.originY - minY,
            )
        }
        val entries = parts.flatMap { part ->
            part.sprite.spritemap.entries.map { entry ->
                entry.copy(xOffset = entry.xOffset + part.x, yOffset = entry.yOffset + part.y)
            }
        }
        return EnemySpritemap.AssembledSprite(
            width,
            height,
            pixels,
            originX = -minX,
            originY = -minY,
            spritemap = EnemySpritemap.Spritemap(entries, BODY_STANDING),
        )
    }

    private fun blit(
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

    private fun readPalette(snesAddress: Int, colorsStartAtOne: Boolean): IntArray? {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(snesAddress)
        val colorCount = if (colorsStartAtOne) 15 else 16
        if (pc < 0 || pc + colorCount * 2 > rom.size) return null
        return IntArray(16) { index ->
            if (index == 0) 0 else {
                val sourceIndex = if (colorsStartAtOne) index - 1 else index
                val offset = pc + sourceIndex * 2
                val bgr = (rom[offset].toInt() and 0xFF) or ((rom[offset + 1].toInt() and 0xFF) shl 8)
                EnemySpriteGraphics.snesColorToArgb(bgr)
            }
        }
    }
}
