package com.supermetroid.editor.rom

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Source-backed renderer for Ridley's complete runtime assembly.
 *
 * The normal enemy renderer owns only Ridley's four-child extended body map.
 * Bank $A6 draws the wings and seven articulated tail pieces in separate OAM
 * passes, while small bank-$B0 DMA transfers replace ribs and claws inside the
 * 256-tile species sheet. The forward turn deliberately selects physical OBJ
 * tiles $E0..FF, populated by the Ridley-explosion enemy set entry, while the
 * main sheet occupies $100..1FF. Both Norfair and Ceres Ridley use this recipe.
 */
class RidleySpritemap(private val romParser: RomParser) {

    companion object {
        const val RIDLEY_SPECIES_ID = 0xE17F
        const val CERES_RIDLEY_SPECIES_ID = 0xE13F
        const val RAW_TILES_SNES = 0xB09400
        const val RAW_TILES_SIZE = 0x2000
        const val FORWARD_TILES_SNES = 0xB0B400
        const val FORWARD_TILES_SIZE = 0x0400
        const val FORWARD_TILES_PHYSICAL_BASE = 0xE0
        const val BASE_PALETTE_SNES = 0xA6E14F
        const val HEALTH_PALETTE_SNES = 0xA6E46A

        private const val SPECIES_TILES_PHYSICAL_BASE = 0x100
        private const val PHYSICAL_OBJ_TILE_COUNT = 0x200

        private const val RIBS_A_TOP_SNES = 0xB0B800
        private const val RIBS_B_TOP_SNES = 0xB0B840
        private const val CLAWS_TOP_SNES = 0xB0B880
        private const val RIBS_A_BOTTOM_SNES = 0xB0B900
        private const val RIBS_B_BOTTOM_SNES = 0xB0B940
        private const val CLAWS_BOTTOM_SNES = 0xB0B980
        private const val RIBS_TOP_TILE = 0x22
        private const val RIBS_BOTTOM_TILE = 0x32
        private const val CLAWS_TOP_TILE = 0xAC
        private const val CLAWS_BOTTOM_TILE = 0xBC

        val PALETTE_STAGES = listOf(
            PaletteStageDef("full-health", "Full health", null, 9000),
            PaletteStageDef("below-9000", "Damage stage 1", 0, 5400),
            PaletteStageDef("below-5400", "Damage stage 2", 1, 1800),
            PaletteStageDef("below-1800", "Damage stage 3", 2, 0),
        )

        val BODY_COMPONENTS = listOf(
            BodyDef("left-neutral", "Left · neutral", Side.LEFT, 0xA6E983),
            BodyDef("left-mouth-half", "Left · mouth half open", Side.LEFT, 0xA6E9C7),
            BodyDef("left-mouth-open", "Left · mouth open", Side.LEFT, 0xA6E9E9),
            BodyDef("left-legs-half", "Left · legs half extended", Side.LEFT, 0xA6EA4F),
            BodyDef("left-legs-extended", "Left · legs extended", Side.LEFT, 0xA6EA71),
            BodyDef("forward", "Facing forward", Side.FORWARD, 0xA6EAD7),
            BodyDef("right-neutral", "Right · neutral", Side.RIGHT, 0xA6E9A5),
            BodyDef("right-mouth-half", "Right · mouth half open", Side.RIGHT, 0xA6EA0B),
            BodyDef("right-mouth-open", "Right · mouth open", Side.RIGHT, 0xA6EA2D),
            BodyDef("right-legs-half", "Right · legs half extended", Side.RIGHT, 0xA6EA93),
            BodyDef("right-legs-extended", "Right · legs extended", Side.RIGHT, 0xA6EAB5),
        )

        val WING_COMPONENTS = listOf(
            WingDef("left-raised", "Left · fully raised", Side.LEFT, 0, 0xA6DD4A),
            WingDef("left-mostly-raised", "Left · mostly raised", Side.LEFT, 1, 0xA6DD6A),
            WingDef("left-slightly-raised", "Left · slightly raised", Side.LEFT, 2, 0xA6DD85),
            WingDef("left-slightly-lowered", "Left · slightly lowered", Side.LEFT, 3, 0xA6DD96),
            WingDef("left-mostly-lowered", "Left · mostly lowered", Side.LEFT, 4, 0xA6DDA7),
            WingDef("left-lowered", "Left · fully lowered", Side.LEFT, 5, 0xA6DDC2),
            WingDef("right-raised", "Right · fully raised", Side.RIGHT, 0, 0xA6DDE2),
            WingDef("right-mostly-raised", "Right · mostly raised", Side.RIGHT, 1, 0xA6DE02),
            WingDef("right-slightly-raised", "Right · slightly raised", Side.RIGHT, 2, 0xA6DE1D),
            WingDef("right-slightly-lowered", "Right · slightly lowered", Side.RIGHT, 3, 0xA6DE2E),
            WingDef("right-mostly-lowered", "Right · mostly lowered", Side.RIGHT, 4, 0xA6DE3F),
            WingDef("right-lowered", "Right · fully lowered", Side.RIGHT, 5, 0xA6DE5A),
        )

        val TAIL_COMPONENTS = listOf(
            OamComponentDef("tail-large", "Tail segment · large", ComponentGroup.TAIL, 0xA6DC90),
            OamComponentDef("tail-medium", "Tail segment · medium", ComponentGroup.TAIL, 0xA6DC97),
            OamComponentDef("tail-small", "Tail segment · small", ComponentGroup.TAIL, 0xA6DC9E),
            OamComponentDef("tip-down", "Tail tip · down", ComponentGroup.TAIL_TIPS, 0xA6DD2E),
            OamComponentDef("tip-down-right", "Tail tip · down-right", ComponentGroup.TAIL_TIPS, 0xA6DD20),
            OamComponentDef("tip-right", "Tail tip · right", ComponentGroup.TAIL_TIPS, 0xA6DD12),
            OamComponentDef("tip-up-right", "Tail tip · up-right", ComponentGroup.TAIL_TIPS, 0xA6DD04),
            OamComponentDef("tip-up", "Tail tip · up", ComponentGroup.TAIL_TIPS, 0xA6DCF6),
            OamComponentDef("tip-up-left", "Tail tip · up-left", ComponentGroup.TAIL_TIPS, 0xA6DCE8),
            OamComponentDef("tip-left", "Tail tip · left", ComponentGroup.TAIL_TIPS, 0xA6DCDA),
            OamComponentDef("tip-down-left", "Tail tip · down-left", ComponentGroup.TAIL_TIPS, 0xA6DD3C),
        )

        private val TIP_ADDRESSES_BY_ANGLE = intArrayOf(
            0xA6DD2E, 0xA6DD27, 0xA6DD20, 0xA6DD19,
            0xA6DD12, 0xA6DD0B, 0xA6DD04, 0xA6DCFD,
            0xA6DCF6, 0xA6DCEF, 0xA6DCE8, 0xA6DCE1,
            0xA6DCDA, 0xA6DD43, 0xA6DD3C, 0xA6DD35,
        )

        val COMPOSITIONS = listOf(
            CompositionDef("left-idle", "Left · idle", Side.LEFT, body("left-neutral"), 2, TailPose.NEUTRAL),
            CompositionDef("left-roar", "Left · roar", Side.LEFT, body("left-mouth-open"), 3, TailPose.NEUTRAL),
            CompositionDef("left-lunge", "Left · lunge", Side.LEFT, body("left-legs-extended"), 1, TailPose.NEUTRAL),
            CompositionDef("left-pogo", "Left · pogo", Side.LEFT, body("left-legs-extended"), 0, TailPose.POGO),
            CompositionDef("right-idle", "Right · idle", Side.RIGHT, body("right-neutral"), 2, TailPose.NEUTRAL),
            CompositionDef("right-roar", "Right · roar", Side.RIGHT, body("right-mouth-open"), 3, TailPose.NEUTRAL),
            CompositionDef("right-lunge", "Right · lunge", Side.RIGHT, body("right-legs-extended"), 1, TailPose.NEUTRAL),
            CompositionDef("right-pogo", "Right · pogo", Side.RIGHT, body("right-legs-extended"), 0, TailPose.POGO),
            CompositionDef("forward", "Facing forward", Side.FORWARD, body("forward"), null, TailPose.POGO),
            CompositionDef(
                "left-clenched", "Left · claws clenched", Side.LEFT, body("left-legs-extended"), 2,
                TailPose.NEUTRAL, RuntimeTiles(RibStage.OPEN_B, ClawStage.CLENCHED),
            ),
        )

        val ANIMATIONS = listOf(
            AnimationDef("wing-left", "Wing flap", Side.LEFT, wingFrames(Side.LEFT), true, "UpdateRidleyWingsAnimation"),
            AnimationDef("roar-left", "Opening roar", Side.LEFT, roarFrames(Side.LEFT), false, "InstList_Ridley_FacingLeft_OpeningRoar"),
            AnimationDef("lunge-left", "Ceres · lunge", Side.LEFT, lungeFrames(Side.LEFT), false, "InstList_RidleyCeres_FacingLeft_Lunging"),
            AnimationDef("retrieve-left", "Ceres · retrieve baby", Side.LEFT, retrieveFrames(), false, "InstList_RidleyCeres_RetrieveBabyMetroid"),
            AnimationDef("turn-right", "Turn to face right", Side.LEFT, turnFrames(Side.LEFT), false, "InstList_Ridley_TurnFromLeftToRight"),
            AnimationDef("ribs-left", "Ribs pulse", Side.LEFT, ribsFrames(Side.LEFT), true, "RidleyRibsAnimationTable"),
            AnimationDef("wing-right", "Wing flap", Side.RIGHT, wingFrames(Side.RIGHT), true, "UpdateRidleyWingsAnimation"),
            AnimationDef("roar-right", "Opening roar", Side.RIGHT, roarFrames(Side.RIGHT), false, "InstList_Ridley_FacingRight_OpeningRoar"),
            AnimationDef("lunge-right", "Ceres · lunge", Side.RIGHT, lungeFrames(Side.RIGHT), false, "UNUSED_InstList_RidleyCeres_FacingRight_Lunging_A6E576"),
            AnimationDef("turn-left", "Turn to face left", Side.RIGHT, turnFrames(Side.RIGHT), false, "InstList_Ridley_TurnFromRightToLeft"),
            AnimationDef("ribs-right", "Ribs pulse", Side.RIGHT, ribsFrames(Side.RIGHT), true, "RidleyRibsAnimationTable"),
        )

        val RUNTIME_SOURCES = listOf(
            RuntimeSourceDef("ribs-a-top", "Ribs A · top", RIBS_A_TOP_SNES, 0x40),
            RuntimeSourceDef("ribs-b-top", "Ribs B · top", RIBS_B_TOP_SNES, 0x40),
            RuntimeSourceDef("claws-top", "Clenched claws · top", CLAWS_TOP_SNES, 0x80),
            RuntimeSourceDef("ribs-a-bottom", "Ribs A · bottom", RIBS_A_BOTTOM_SNES, 0x40),
            RuntimeSourceDef("ribs-b-bottom", "Ribs B · bottom", RIBS_B_BOTTOM_SNES, 0x40),
            RuntimeSourceDef("claws-bottom", "Clenched claws · bottom", CLAWS_BOTTOM_SNES, 0x80),
        )

        val SHARED_VRAM_SOURCES = listOf(
            RuntimeSourceDef(
                "forward-explosion-obj",
                "Facing-forward / explosion OBJ",
                FORWARD_TILES_SNES,
                FORWARD_TILES_SIZE,
            ),
        )

        private fun body(key: String): Int = BODY_COMPONENTS.first { it.key == key }.snesAddress
        private fun bodyAddress(side: Side, state: String): Int = body("${side.key}-$state")

        private fun wingFrames(side: Side): List<FrameDef> =
            listOf(0, 1, 2, 3, 4, 5, 4, 3, 2, 1).map {
                FrameDef(side, bodyAddress(side, "neutral"), it, TailPose.NEUTRAL, 4)
            }

        private fun roarFrames(side: Side): List<FrameDef> = listOf(
            FrameDef(side, bodyAddress(side, "neutral"), 2, TailPose.NEUTRAL, 6),
            FrameDef(side, bodyAddress(side, "mouth-half"), 2, TailPose.NEUTRAL, 8),
            FrameDef(side, bodyAddress(side, "mouth-open"), 3, TailPose.NEUTRAL, 0x60),
            FrameDef(side, bodyAddress(side, "mouth-half"), 2, TailPose.NEUTRAL, 8),
            FrameDef(side, bodyAddress(side, "neutral"), 2, TailPose.NEUTRAL, 1),
        )

        private fun lungeFrames(side: Side): List<FrameDef> = listOf(
            FrameDef(side, bodyAddress(side, "neutral"), 2, TailPose.NEUTRAL, 4),
            FrameDef(side, bodyAddress(side, "legs-half"), 1, TailPose.NEUTRAL, 6),
            FrameDef(side, bodyAddress(side, "legs-extended"), 0, TailPose.NEUTRAL, 0x50),
            FrameDef(side, bodyAddress(side, "legs-half"), 1, TailPose.NEUTRAL, 6),
            FrameDef(side, bodyAddress(side, "neutral"), 2, TailPose.NEUTRAL, 4),
        )

        /** Active Ceres route; the source's mirrored right-facing list is unreferenced. */
        private fun retrieveFrames(): List<FrameDef> = listOf(
            FrameDef(Side.LEFT, body("left-neutral"), 2, TailPose.NEUTRAL, 4),
            FrameDef(Side.LEFT, body("left-legs-half"), 1, TailPose.NEUTRAL, 6),
            FrameDef(Side.LEFT, body("left-legs-extended"), 0, TailPose.NEUTRAL, 1),
        )

        private fun turnFrames(from: Side): List<FrameDef> {
            val to = if (from == Side.LEFT) Side.RIGHT else Side.LEFT
            return listOf(
                FrameDef(from, bodyAddress(from, "neutral"), 2, TailPose.NEUTRAL, 1),
                FrameDef(Side.FORWARD, body("forward"), null, TailPose.POGO, 8),
                FrameDef(Side.FORWARD, body("forward"), null, TailPose.POGO, 1),
                FrameDef(to, bodyAddress(to, "neutral"), 2, TailPose.NEUTRAL, 1),
            )
        }

        private fun ribsFrames(side: Side): List<FrameDef> = listOf(
            FrameDef(side, bodyAddress(side, "neutral"), 2, TailPose.NEUTRAL, 0x14),
            FrameDef(side, bodyAddress(side, "neutral"), 2, TailPose.NEUTRAL, 0x14, RuntimeTiles(RibStage.OPEN_A)),
            FrameDef(side, bodyAddress(side, "neutral"), 2, TailPose.NEUTRAL, 0x14, RuntimeTiles(RibStage.OPEN_B)),
            FrameDef(side, bodyAddress(side, "neutral"), 2, TailPose.NEUTRAL, 0x14, RuntimeTiles(RibStage.OPEN_A)),
        )
    }

    enum class Side(val key: String, val displayName: String) {
        LEFT("left", "Left"), FORWARD("forward", "Forward"), RIGHT("right", "Right")
    }
    enum class TailPose { NEUTRAL, POGO }
    enum class RibStage { BASE, OPEN_A, OPEN_B }
    enum class ClawStage { OPEN, CLENCHED }
    enum class ComponentGroup(val displayName: String) {
        BODY("Body"), WINGS("Wings"), TAIL("Tail segments"), TAIL_TIPS("Tail tips")
    }

    data class PaletteStageDef(val key: String, val name: String, val healthTableIndex: Int?, val minimumHealth: Int)
    data class RuntimeTiles(val ribs: RibStage = RibStage.BASE, val claws: ClawStage = ClawStage.OPEN)
    data class BodyDef(val key: String, val name: String, val side: Side, val snesAddress: Int)
    data class WingDef(val key: String, val name: String, val side: Side, val index: Int, val snesAddress: Int)
    data class OamComponentDef(val key: String, val name: String, val group: ComponentGroup, val snesAddress: Int)
    data class RuntimeSourceDef(val key: String, val name: String, val snesAddress: Int, val byteCount: Int)
    data class CompositionDef(
        val key: String, val name: String, val side: Side, val bodySnes: Int, val wingIndex: Int?,
        val tailPose: TailPose, val runtimeTiles: RuntimeTiles = RuntimeTiles(),
    )
    data class FrameDef(
        val side: Side, val bodySnes: Int, val wingIndex: Int?, val tailPose: TailPose, val duration: Int,
        val runtimeTiles: RuntimeTiles = RuntimeTiles(),
    )
    data class AnimationDef(
        val key: String, val name: String, val side: Side, val frames: List<FrameDef>, val loop: Boolean,
        val sourceLabel: String,
    )

    private data class PositionedSprite(val sprite: EnemySpritemap.AssembledSprite, val x: Int = 0, val y: Int = 0)
    private data class TailPoint(val x: Int, val y: Int, val angle: Int)
    private data class NormalizedFrames(val width: Int, val height: Int, val pixels: List<IntArray>)

    private val renderer = EnemySpritemap(romParser)
    private val scanner = BossPoseScanner(romParser)
    private var rawTileData: ByteArray? = null
    private val physicalObjOptions = EnemySpritemap.RenderOptions(
        oamTileNumberMode = EnemySpritemap.OamTileNumberMode.LOW_9,
    )

    fun load(enemyTileData: ByteArray? = null): Boolean {
        val raw = enemyTileData ?: EnemySpriteGraphics.loadEnemyTileData(romParser, RIDLEY_SPECIES_ID) ?: return false
        if (raw.size != RAW_TILES_SIZE) return false
        rawTileData = raw.copyOf()
        return readPalette(PALETTE_STAGES.first()) != null
    }

    fun getRawTileData(): ByteArray? = rawTileData?.copyOf()

    /** Compatibility surface retained for body-only diagnostics. */
    fun loadPoses(speciesId: Int = RIDLEY_SPECIES_ID): List<BossPoseScanner.BossPose> =
        scanner.scanPoses(speciesId, minEntries = 4)

    fun renderPose(
        pose: BossPoseScanner.BossPose, tileData: ByteArray, palette: IntArray,
    ): EnemySpritemap.AssembledSprite? = scanner.renderPose(pose, tileData, palette)

    fun readPalette(stage: PaletteStageDef = PALETTE_STAGES.first()): IntArray? {
        val palette = readPaletteAt(BASE_PALETTE_SNES) ?: return null
        val tableIndex = stage.healthTableIndex ?: return palette
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(HEALTH_PALETTE_SNES + tableIndex * 0x1C)
        if (pc < 0 || pc + 0x1C > rom.size) return null
        for (index in 0 until 14) {
            palette[index + 1] = EnemySpriteGraphics.snesColorToArgb(readWord(rom, pc + index * 2))
        }
        return palette
    }

    fun renderComposition(
        definition: CompositionDef, paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? = renderFrame(
        FrameDef(
            definition.side, definition.bodySnes, definition.wingIndex, definition.tailPose, 1,
            definition.runtimeTiles,
        ),
        paletteStage,
    )

    fun renderBody(
        definition: BodyDef,
        paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
        runtimeTiles: RuntimeTiles = RuntimeTiles(),
    ): EnemySpritemap.AssembledSprite? {
        val tiles = buildRuntimeTileData(runtimeTiles) ?: return null
        val palette = readPalette(paletteStage) ?: return null
        val extended = renderer.parseExtendedSpritemap(definition.snesAddress) ?: return null
        return renderer.renderRenderableFrame(
            EnemySpritemap.RenderableFrame.Extended(extended),
            tiles,
            palette,
            physicalObjOptions,
        )
    }

    fun renderWing(
        definition: WingDef, paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? = renderOam(definition.snesAddress, paletteStage)

    fun renderTailComponent(
        definition: OamComponentDef, paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? = renderOam(definition.snesAddress, paletteStage)

    fun renderAnimation(
        definition: AnimationDef, paletteStage: PaletteStageDef = PALETTE_STAGES.first(),
    ): SpriteAnimation? {
        val rendered = definition.frames.map { frame -> renderFrame(frame, paletteStage) ?: return null }
        val normalized = normalize(rendered) ?: return null
        return SpriteAnimation(
            "${definition.side.displayName} · ${definition.name}",
            definition.frames.zip(normalized.pixels).mapIndexed { index, (source, pixels) ->
                SpriteAnimationFrame(
                    pixels, normalized.width, normalized.height, source.duration,
                    "${definition.name} ${index + 1}/${definition.frames.size}",
                )
            },
            definition.loop,
        )
    }

    fun readRuntimeSource(definition: RuntimeSourceDef): ByteArray? =
        readBytes(definition.snesAddress, definition.byteCount)

    private fun renderFrame(frame: FrameDef, paletteStage: PaletteStageDef): EnemySpritemap.AssembledSprite? {
        val tiles = buildRuntimeTileData(frame.runtimeTiles) ?: return null
        val palette = readPalette(paletteStage) ?: return null
        val parts = mutableListOf<PositionedSprite>()
        // Ridley's custom draw passes append tail, then wings, before the queued
        // body. Lower OAM indexes win overlaps, so the equivalent painter order
        // is body first, then wings, then tail.
        val body = renderer.parseExtendedSpritemap(frame.bodySnes) ?: return null
        renderer.renderRenderableFrame(
            EnemySpritemap.RenderableFrame.Extended(body),
            tiles,
            palette,
            physicalObjOptions,
        )
            ?.let { parts += PositionedSprite(it) }
        if (frame.side != Side.FORWARD && frame.wingIndex != null) {
            val wing = WING_COMPONENTS.firstOrNull { it.side == frame.side && it.index == frame.wingIndex }
            if (wing != null) renderOam(wing.snesAddress, tiles, palette)?.let { parts += PositionedSprite(it) }
        }
        parts += renderTail(frame.side, frame.tailPose, tiles, palette)
        return compose(parts)
    }

    private fun renderTail(
        side: Side, pose: TailPose, tiles: ByteArray, palette: IntArray,
    ): List<PositionedSprite> {
        val points = tailPoints(side, pose)
        val addresses = intArrayOf(0xA6DC90, 0xA6DC90, 0xA6DC97, 0xA6DC97, 0xA6DC9E, 0xA6DC9E)
        val parts = mutableListOf<PositionedSprite>()
        for (index in addresses.indices) {
            renderOam(addresses[index], tiles, palette)?.let {
                parts += PositionedSprite(it, points[index].x, points[index].y)
            }
        }
        val tipAngle = (points[6].angle + points[5].angle + 8) and 0xF0
        renderOam(TIP_ADDRESSES_BY_ANGLE[tipAngle ushr 4], tiles, palette)?.let {
            parts += PositionedSprite(it, points[6].x, points[6].y)
        }
        return parts
    }

    /** Mirrors CalculateRidleyTailSegmentPositions using source initialization values. */
    private fun tailPoints(side: Side, pose: TailPose): List<TailPoint> {
        val distances = intArrayOf(2, 8, 8, 8, 8, 8, 5)
        val leftAngles = if (pose == TailPose.POGO) IntArray(7) else
            intArrayOf(0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
        val angles = when (side) {
            Side.LEFT -> leftAngles
            Side.RIGHT -> IntArray(7) { index -> (-leftAngles[index]) and 0xFF }
            Side.FORWARD -> IntArray(7)
        }
        var x = when (side) { Side.LEFT -> 0x20; Side.RIGHT -> -0x20; Side.FORWARD -> 0 }
        var y = 0x10
        return List(7) { index ->
            val radians = angles[index] * 2.0 * PI / 256.0
            val xOffset = (sin(radians) * distances[index]).roundToInt()
            val yOffset = (cos(radians) * distances[index]).roundToInt()
            if (side != Side.FORWARD || index == 0) x += xOffset
            y += yOffset
            TailPoint(x, y, angles[index])
        }
    }

    private fun renderOam(
        snesAddress: Int, paletteStage: PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? {
        val tiles = buildRuntimeTileData(RuntimeTiles()) ?: return null
        val palette = readPalette(paletteStage) ?: return null
        return renderOam(snesAddress, tiles, palette)
    }

    private fun renderOam(
        snesAddress: Int, tiles: ByteArray, palette: IntArray,
    ): EnemySpritemap.AssembledSprite? =
        renderer.parseSpritemap(snesAddress)?.let {
            renderer.renderRenderableFrame(
                EnemySpritemap.RenderableFrame.Oam(it),
                tiles,
                palette,
                physicalObjOptions,
            )
        }

    private fun buildRuntimeTileData(runtime: RuntimeTiles): ByteArray? {
        val speciesTiles = rawTileData ?: return null
        val forwardTiles = readBytes(FORWARD_TILES_SNES, FORWARD_TILES_SIZE) ?: return null
        val out = ByteArray(PHYSICAL_OBJ_TILE_COUNT * EnemySpriteGraphics.BYTES_PER_TILE)
        forwardTiles.copyInto(
            out,
            destinationOffset = FORWARD_TILES_PHYSICAL_BASE * EnemySpriteGraphics.BYTES_PER_TILE,
        )
        speciesTiles.copyInto(
            out,
            destinationOffset = SPECIES_TILES_PHYSICAL_BASE * EnemySpriteGraphics.BYTES_PER_TILE,
        )
        when (runtime.ribs) {
            RibStage.BASE -> Unit
            RibStage.OPEN_A -> {
                copyRuntimeBlock(out, RIBS_A_TOP_SNES, 0x40, RIBS_TOP_TILE)
                copyRuntimeBlock(out, RIBS_A_BOTTOM_SNES, 0x40, RIBS_BOTTOM_TILE)
            }
            RibStage.OPEN_B -> {
                copyRuntimeBlock(out, RIBS_B_TOP_SNES, 0x40, RIBS_TOP_TILE)
                copyRuntimeBlock(out, RIBS_B_BOTTOM_SNES, 0x40, RIBS_BOTTOM_TILE)
            }
        }
        if (runtime.claws == ClawStage.CLENCHED) {
            copyRuntimeBlock(out, CLAWS_TOP_SNES, 0x80, CLAWS_TOP_TILE)
            copyRuntimeBlock(out, CLAWS_BOTTOM_SNES, 0x80, CLAWS_BOTTOM_TILE)
        }
        return out
    }

    private fun copyRuntimeBlock(destination: ByteArray, snes: Int, count: Int, tile: Int) {
        readBytes(snes, count)?.copyInto(
            destination,
            destinationOffset = (SPECIES_TILES_PHYSICAL_BASE + tile) * EnemySpriteGraphics.BYTES_PER_TILE,
        )
    }

    private fun normalize(sprites: List<EnemySpritemap.AssembledSprite>): NormalizedFrames? {
        if (sprites.isEmpty()) return null
        val minX = sprites.minOf { -it.originX }
        val minY = sprites.minOf { -it.originY }
        val maxX = sprites.maxOf { it.width - it.originX }
        val maxY = sprites.maxOf { it.height - it.originY }
        val width = maxX - minX
        val height = maxY - minY
        if (width <= 0 || height <= 0) return null
        return NormalizedFrames(width, height, sprites.map { sprite ->
            IntArray(width * height).also {
                output -> blit(output, width, height, sprite, -sprite.originX - minX, -sprite.originY - minY)
            }
        })
    }

    private fun compose(parts: List<PositionedSprite>): EnemySpritemap.AssembledSprite? {
        if (parts.isEmpty()) return null
        val minX = parts.minOf { it.x - it.sprite.originX }
        val minY = parts.minOf { it.y - it.sprite.originY }
        val maxX = parts.maxOf { it.x - it.sprite.originX + it.sprite.width }
        val maxY = parts.maxOf { it.y - it.sprite.originY + it.sprite.height }
        val width = maxX - minX
        val height = maxY - minY
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        parts.forEach { part ->
            blit(pixels, width, height, part.sprite, part.x - part.sprite.originX - minX, part.y - part.sprite.originY - minY)
        }
        val entries = parts.flatMap { part ->
            part.sprite.spritemap.entries.map { it.copy(xOffset = it.xOffset + part.x, yOffset = it.yOffset + part.y) }
        }
        return EnemySpritemap.AssembledSprite(
            width, height, pixels, -minX, -minY,
            EnemySpritemap.Spritemap(entries, parts.last().sprite.spritemap.snesAddress),
        )
    }

    private fun blit(
        destination: IntArray, width: Int, height: Int, source: EnemySpritemap.AssembledSprite,
        left: Int, top: Int,
    ) {
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val color = source.pixels[y * source.width + x]
            if ((color ushr 24) == 0) continue
            val dx = left + x
            val dy = top + y
            if (dx in 0 until width && dy in 0 until height) destination[dy * width + dx] = color
        }
    }

    private fun readPaletteAt(snesAddress: Int): IntArray? {
        val raw = readBytes(snesAddress, 32) ?: return null
        return IntArray(16) { index ->
            if (index == 0) 0 else EnemySpriteGraphics.snesColorToArgb(readWord(raw, index * 2))
        }
    }

    private fun readBytes(snesAddress: Int, count: Int): ByteArray? {
        val pc = romParser.snesToPc(snesAddress)
        val rom = romParser.getRomData()
        if (pc < 0 || pc + count > rom.size) return null
        return rom.copyOfRange(pc, pc + count)
    }

    private fun readWord(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
