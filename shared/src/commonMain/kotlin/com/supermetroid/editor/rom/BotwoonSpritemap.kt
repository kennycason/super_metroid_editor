package com.supermetroid.editor.rom

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Source-backed Botwoon renderer.
 *
 * Botwoon is not one large spritemap. The enemy owns only the head; thirteen
 * bank-$86 enemy projectiles sample the head's circular position history to
 * draw twelve body segments and one tail. The projectile instruction-list
 * table selects each segment's direction from the vector to the preceding
 * segment. This renderer keeps those ownership and placement rules intact.
 */
class BotwoonSpritemap(private val romParser: RomParser) {

    companion object {
        const val SPECIES_ID = 0xF293
        const val TILE_DATA_SNES = 0xB7E300
        const val TILE_DATA_SIZE = 0x1800
        const val BASE_PALETTE_SNES = 0xB39319
        const val HEALTH_PALETTES_SNES = 0xB3971B
        const val HEALTH_THRESHOLDS_SNES = 0xB3981B
        const val SPEED_TABLE_SNES = 0xB394BB
        const val BODY_PROJECTILE_COUNT = 13
        const val BODY_SEGMENT_COUNT = 12
        const val SEGMENT_DISTANCE = 12.0

        val DIRECTIONS = listOf(
            DirectionDef(
                "up", "Up", 0, -1,
                0xB3E389, 0xB3E415,
                intArrayOf(0x8DB70E, 0x8DB715, 0x8DB71C, 0x8DB723), 0x8DB72A,
                0xB39381, 0xB3941F,
            ),
            DirectionDef(
                "up-right", "Up-right", 1, -1,
                0xB3E37D, 0xB3E404,
                intArrayOf(0x8DB6F2, 0x8DB6F9, 0x8DB700, 0x8DB707), 0x8DB75B,
                0xB39379, 0xB3940F,
            ),
            DirectionDef(
                "right", "Right", 1, 0,
                0xB3E371, 0xB3E3F8,
                intArrayOf(0x8DB6D6, 0x8DB6DD, 0x8DB6E4, 0x8DB6EB), 0x8DB754,
                0xB39371, 0xB393FF,
            ),
            DirectionDef(
                "down-right", "Down-right", 1, 1,
                0xB3E365, 0xB3E3E7,
                intArrayOf(0x8DB6BA, 0x8DB6C1, 0x8DB6C8, 0x8DB6CF), 0x8DB74D,
                0xB39369, 0xB393EF,
            ),
            DirectionDef(
                "down", "Down", 0, 1,
                0xB3E359, 0xB3E3DB,
                intArrayOf(0x8DB69E, 0x8DB6A5, 0x8DB6AC, 0x8DB6B3), 0x8DB746,
                0xB39361, 0xB393DF,
            ),
            DirectionDef(
                "down-left", "Down-left", -1, 1,
                0xB3E341, 0xB3E3BE,
                intArrayOf(0x8DB666, 0x8DB66D, 0x8DB674, 0x8DB67B), 0x8DB73F,
                0xB39351, 0xB393BF,
            ),
            DirectionDef(
                "left", "Left", -1, 0,
                0xB3E335, 0xB3E3B2,
                intArrayOf(0x8DB64A, 0x8DB651, 0x8DB658, 0x8DB65F), 0x8DB738,
                0xB39349, 0xB393AF, spitOpenTicks = 25,
            ),
            DirectionDef(
                "up-left", "Up-left", -1, -1,
                0xB3E329, 0xB3E3A1,
                intArrayOf(0x8DB62E, 0x8DB635, 0x8DB63C, 0x8DB643), 0x8DB731,
                0xB39341, 0xB3939F,
            ),
        )

        val PALETTE_STAGES = listOf(
            PaletteStageDef("100", "100% · 3000–2625 HP", HEALTH_PALETTES_SNES, 3000),
            PaletteStageDef("87", "87% · 2624–2250 HP", HEALTH_PALETTES_SNES + 0x20, 2625),
            PaletteStageDef("75", "75% · 2249–1875 HP", HEALTH_PALETTES_SNES + 0x40, 2250),
            PaletteStageDef("62", "62% · 1874–1500 HP", HEALTH_PALETTES_SNES + 0x60, 1875),
            PaletteStageDef("50", "50% · 1499–1125 HP", HEALTH_PALETTES_SNES + 0x80, 1500),
            PaletteStageDef("37", "37% · 1124–750 HP", HEALTH_PALETTES_SNES + 0xA0, 1125),
            PaletteStageDef("25", "25% · 749–375 HP", HEALTH_PALETTES_SNES + 0xC0, 750),
            PaletteStageDef("12", "12% · below 375 HP", HEALTH_PALETTES_SNES + 0xE0, 375),
        )

        val SPEED_STAGES = listOf(
            SpeedStageDef("healthy", "Healthy · ≥ 50%", speed = 2, historyByteDistance = 0x18),
            SpeedStageDef("hurt", "Hurt · 25–49%", speed = 3, historyByteDistance = 0x10),
            SpeedStageDef("critical", "Critical · < 25%", speed = 4, historyByteDistance = 0x0C),
        )

        val COMPOSITIONS = DIRECTIONS.map { direction ->
            CompositionDef("straight-${direction.key}", "Straight · ${direction.name}", direction)
        } + listOf(
            CompositionDef(
                "turning", "Position-history turn", DIRECTIONS[2],
                points = listOf(
                    Point(0, 0), Point(-12, 0), Point(-24, 0), Point(-36, 1),
                    Point(-47, 5), Point(-57, 12), Point(-64, 22), Point(-68, 33),
                    Point(-68, 45), Point(-64, 56), Point(-56, 65), Point(-45, 71),
                    Point(-33, 73), Point(-21, 72),
                ),
            ),
            CompositionDef(
                "emerging", "Emerging from a hole", DIRECTIONS[0], visibleProjectileCount = 7,
            ),
        )

        val COMPONENTS = DIRECTIONS.flatMap { direction ->
            listOf(
                ComponentDef("head-closed-${direction.key}", "Head · ${direction.name} · closed", ComponentKind.HEAD, direction.closedHeadMap),
                ComponentDef("head-open-${direction.key}", "Head · ${direction.name} · open", ComponentKind.HEAD, direction.openHeadMap),
                ComponentDef("body-${direction.key}", "Body segment · ${direction.name}", ComponentKind.BODY, direction.bodyMaps.first()),
                ComponentDef("tail-${direction.key}", "Tail · ${direction.name}", ComponentKind.TAIL, direction.tailMap),
            )
        } + Array(5) { index ->
            ComponentDef("spit-$index", "Spit · frame ${index + 1}", ComponentKind.SPIT, 0x8DB8B4 + index * 7)
        }

        val PIXEL_SOURCES = listOf(
            PixelSourceDef(
                key = "shared-obj",
                name = "Head, body, tail, and spit",
                snesAddress = TILE_DATA_SNES,
                byteCount = TILE_DATA_SIZE,
                editable = true,
                sourceLabel = "Tiles_Botwoon",
            ),
        )
    }

    data class DirectionDef(
        val key: String,
        val name: String,
        val dx: Int,
        val dy: Int,
        val closedHeadMap: Int,
        val openHeadMap: Int,
        val bodyMaps: IntArray,
        val tailMap: Int,
        val closedInstructionList: Int,
        val spitInstructionList: Int,
        val spitOpenTicks: Int = 16,
    )

    data class PaletteStageDef(
        val key: String,
        val name: String,
        val snesAddress: Int,
        val thresholdHp: Int,
    )

    data class SpeedStageDef(
        val key: String,
        val name: String,
        val speed: Int,
        val historyByteDistance: Int,
    ) {
        val historyFrames: Int get() = historyByteDistance / 4
        val segmentDistance: Int get() = speed * historyFrames
    }

    data class Point(val x: Int, val y: Int)

    data class CompositionDef(
        val key: String,
        val name: String,
        val direction: DirectionDef,
        val points: List<Point>? = null,
        val visibleProjectileCount: Int = BODY_PROJECTILE_COUNT,
    )

    enum class ComponentKind { HEAD, BODY, TAIL, SPIT }

    data class ComponentDef(
        val key: String,
        val name: String,
        val kind: ComponentKind,
        val snesAddress: Int,
    )

    data class PixelSourceDef(
        val key: String,
        val name: String,
        val snesAddress: Int,
        val byteCount: Int,
        val editable: Boolean,
        val sourceLabel: String,
    )

    private val spritemap = EnemySpritemap(romParser)
    private var rawTiles: ByteArray? = null

    fun load(enemyTiles: ByteArray?): Boolean {
        if (enemyTiles == null || enemyTiles.size != TILE_DATA_SIZE) return false
        rawTiles = enemyTiles.copyOf()
        return true
    }

    fun getRawTileData(): ByteArray? = rawTiles?.copyOf()

    fun readPalette(definition: PaletteStageDef = PALETTE_STAGES.first()): IntArray? =
        readPaletteAt(definition.snesAddress)

    fun readHeaderPalette(): IntArray? = readPaletteAt(BASE_PALETTE_SNES)

    fun readPixelSource(definition: PixelSourceDef): ByteArray? = when (definition.key) {
        "shared-obj" -> getRawTileData()
        else -> null
    }

    fun renderComposition(
        definition: CompositionDef,
        paletteDefinition: PaletteStageDef = PALETTE_STAGES.first(),
        mouthOpen: Boolean = false,
        bodyPhase: Int = 0,
    ): EnemySpritemap.AssembledSprite? {
        val points = definition.points ?: straightTrail(definition.direction)
        return renderTrail(
            points = points,
            headDirection = definition.direction,
            mouthOpen = mouthOpen,
            bodyPhase = bodyPhase,
            visibleProjectileCount = definition.visibleProjectileCount,
            paletteDefinition = paletteDefinition,
        )
    }

    fun renderComponent(
        definition: ComponentDef,
        paletteDefinition: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? {
        val tiles = rawTiles ?: return null
        val palette = readPalette(paletteDefinition) ?: return null
        val map = spritemap.parseSpritemap(definition.snesAddress) ?: return null
        return spritemap.renderSpritemap(map, tiles, palette)
    }

    fun renderSwimAnimation(
        direction: DirectionDef,
        paletteDefinition: PaletteStageDef = PALETTE_STAGES.first(),
    ): SpriteAnimation? {
        val points = straightTrail(direction)
        val frames = (0..3).map { phase ->
            val image = renderTrail(points, direction, false, phase, BODY_PROJECTILE_COUNT, paletteDefinition)
                ?: return null
            SpriteAnimationFrame(image.pixels, image.width, image.height, 8, "${direction.name} · body ${phase + 1}")
        }
        return SpriteAnimation("Swim · ${direction.name}", frames, loop = true)
    }

    /** Combines the head's source timings with the independently looping 4x8-tick body list. */
    fun renderSpitAnimation(
        direction: DirectionDef,
        paletteDefinition: PaletteStageDef = PALETTE_STAGES.first(),
    ): SpriteAnimation? {
        val points = straightTrail(direction)
        val states = timedBodyPhases(32, mouthOpen = false) +
            timedBodyPhases(direction.spitOpenTicks, mouthOpen = true, startingTick = 32)
        val frames = states.mapIndexed { index, state ->
            val image = renderTrail(
                points, direction, state.mouthOpen, state.bodyPhase,
                BODY_PROJECTILE_COUNT, paletteDefinition,
            ) ?: return null
            SpriteAnimationFrame(
                image.pixels, image.width, image.height, state.durationTicks,
                "${direction.name} · ${if (state.mouthOpen) "open" else "closed"} ${index + 1}",
            )
        }
        return SpriteAnimation("Spit · ${direction.name}", frames, loop = false)
    }

    fun renderSpitProjectileAnimation(
        paletteDefinition: PaletteStageDef = PALETTE_STAGES.first(),
    ): SpriteAnimation? {
        val tiles = rawTiles ?: return null
        val palette = readPalette(paletteDefinition) ?: return null
        val frames = (0..4).map { index ->
            val address = 0x8DB8B4 + index * 7
            val map = spritemap.parseSpritemap(address) ?: return null
            val image = spritemap.renderSpritemap(map, tiles, palette) ?: return null
            SpriteAnimationFrame(image.pixels, image.width, image.height, 3, "Spit · ${index + 1}")
        }
        return SpriteAnimation("Spit projectile", frames, loop = true)
    }

    internal fun straightTrail(direction: DirectionDef): List<Point> {
        val diagonal = direction.dx != 0 && direction.dy != 0
        val axisDistance = if (diagonal) SEGMENT_DISTANCE / 1.4142135623730951 else SEGMENT_DISTANCE
        return (0..BODY_PROJECTILE_COUNT).map { index ->
            Point(
                x = (-direction.dx * axisDistance * index).roundToInt(),
                y = (-direction.dy * axisDistance * index).roundToInt(),
            )
        }
    }

    private fun renderTrail(
        points: List<Point>,
        headDirection: DirectionDef,
        mouthOpen: Boolean,
        bodyPhase: Int,
        visibleProjectileCount: Int,
        paletteDefinition: PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? {
        if (points.size != BODY_PROJECTILE_COUNT + 1) return null
        val tiles = rawTiles ?: return null
        val palette = readPalette(paletteDefinition) ?: return null
        val entries = mutableListOf<EnemySpritemap.OamEntry>()

        val headAddress = if (mouthOpen) headDirection.openHeadMap else headDirection.closedHeadMap
        val head = spritemap.parseSpritemap(headAddress) ?: return null
        entries += head.entries

        val visible = visibleProjectileCount.coerceIn(0, BODY_PROJECTILE_COUNT)
        for (segmentIndex in 1..visible) {
            val point = points[segmentIndex]
            val direction = directionForVector(
                points[segmentIndex - 1].x - point.x,
                points[segmentIndex - 1].y - point.y,
            )
            val address = if (segmentIndex == BODY_PROJECTILE_COUNT) {
                direction.tailMap
            } else {
                direction.bodyMaps[bodyPhase.mod(direction.bodyMaps.size)]
            }
            val map = spritemap.parseSpritemap(address) ?: return null
            entries += map.entries.map { entry ->
                entry.copy(
                    xOffset = entry.xOffset + point.x,
                    yOffset = entry.yOffset + point.y,
                )
            }
        }
        val combined = EnemySpritemap.Spritemap(entries, headAddress)
        return spritemap.renderSpritemap(combined, tiles, palette)
    }

    internal fun directionForVector(dx: Int, dy: Int): DirectionDef {
        if (dx == 0 && dy == 0) return DIRECTIONS.first()
        val ax = abs(dx)
        val ay = abs(dy)
        val horizontal = ax >= ay * 2
        val vertical = ay >= ax * 2
        val sx = when {
            horizontal || !vertical -> dx.compareTo(0)
            else -> 0
        }
        val sy = when {
            vertical || !horizontal -> dy.compareTo(0)
            else -> 0
        }
        return DIRECTIONS.first { it.dx == sx && it.dy == sy }
    }

    private data class TimedBodyPhase(
        val mouthOpen: Boolean,
        val bodyPhase: Int,
        val durationTicks: Int,
    )

    private fun timedBodyPhases(
        ticks: Int,
        mouthOpen: Boolean,
        startingTick: Int = 0,
    ): List<TimedBodyPhase> {
        val result = mutableListOf<TimedBodyPhase>()
        var elapsed = 0
        while (elapsed < ticks) {
            val duration = minOf(8, ticks - elapsed)
            result += TimedBodyPhase(mouthOpen, ((startingTick + elapsed) / 8) % 4, duration)
            elapsed += duration
        }
        return result
    }

    private fun readPaletteAt(snesAddress: Int): IntArray? {
        val rom = romParser.getRomData()
        val pc = romParser.snesToPc(snesAddress)
        if (pc < 0 || pc + 32 > rom.size) return null
        return IntArray(16) { index ->
            if (index == 0) 0 else EnemySpriteGraphics.snesColorToArgb(readU16(rom, pc + index * 2))
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
