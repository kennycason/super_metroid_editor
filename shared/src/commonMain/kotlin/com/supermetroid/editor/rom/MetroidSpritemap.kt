package com.supermetroid.editor.rom

/**
 * Source-backed renderer for the normal Metroid enemy.
 *
 * A live Metroid is three independently timed OAM owners sharing one enemy
 * graphics transfer and palette:
 *
 *  - bank $A3 draws the animated insides as the enemy;
 *  - sprite object $32 draws electricity from bank $B4;
 *  - sprite object $34 draws the translucent shell from bank $B4.
 *
 * The two sprite-object entry lists intentionally fall through into the lists
 * labelled as unused object $33/$35, then loop there. Those continuations are
 * therefore active parts of a normal Metroid even though the corresponding
 * object IDs are never created directly.
 */
class MetroidSpritemap(private val romParser: RomParser) {

    companion object {
        const val SPECIES_ID = 0xDD7F
        const val TILE_DATA_SNES = 0xAE9000
        const val TILE_DATA_SIZE = 0x1000
        const val PALETTE_SNES = 0xA3E9AF

        const val CHASE_LIST_SNES = 0xA3E9CF
        const val CHASE_LIST_END_SNES = 0xA3EA25
        const val DRAIN_LIST_SNES = 0xA3EA25
        const val DRAIN_LIST_END_SNES = 0xA3EA3F

        const val ELECTRICITY_INTRO_LIST_SNES = 0xB4C3BA
        const val ELECTRICITY_STEADY_LIST_SNES = 0xB4C436
        const val ELECTRICITY_STEADY_END_SNES = 0xB4C4B6
        const val SHELL_INTRO_LIST_SNES = 0xB4C4B6
        const val SHELL_STEADY_LIST_SNES = 0xB4C536
        const val SHELL_STEADY_END_SNES = 0xB4C5B2
        const val EMPTY_DRAW_SNES = 0xB4BDA6

        private const val DRAIN_VISUAL_TICKS = 0x40
        private const val ELECTRICITY_INTRO_TICKS = 93
        private const val ELECTRICITY_STEADY_TICKS = 115

        val COMPOSITIONS = listOf(
            CompositionDef("spawn", "Spawn · electricity + shell", 0xA3F10D, 0xB4D5B7, 0xB4D6F1),
            CompositionDef("rounded", "Rounded body", 0xA3F137, 0xB4D5EB, null),
            CompositionDef("extended", "Extended body", 0xA3F157, 0xB4D61F, 0xB4D724),
            CompositionDef("draining", "Draining reference", 0xA3F181, 0xB4D61F, 0xB4D799),
        )

        val ANIMATIONS = listOf(
            AnimationDef("runtime-intro", "Runtime · spawn window", AnimationKind.RUNTIME_INTRO),
            AnimationDef("runtime-steady", "Runtime · steady window", AnimationKind.RUNTIME_STEADY),
            AnimationDef("draining", "Draining Samus", AnimationKind.DRAINING),
            AnimationDef("shell-intro", "Shell · initial shimmer", AnimationKind.SHELL_INTRO),
            AnimationDef("shell-steady", "Shell · steady shimmer", AnimationKind.SHELL_STEADY),
            AnimationDef("electricity-intro", "Electricity · initial cycle", AnimationKind.ELECTRICITY_INTRO),
            AnimationDef("electricity-steady", "Electricity · steady cycle", AnimationKind.ELECTRICITY_STEADY),
        )

        val COMPONENTS = buildList {
            listOf(0xA3F10D, 0xA3F137, 0xA3F157, 0xA3F181).forEachIndexed { index, address ->
                add(ComponentDef("inside-$index", "Insides · ${index + 1}", ComponentKind.INSIDES, address))
            }
            listOf(0xB4D5B7, 0xB4D5EB, 0xB4D61F).forEachIndexed { index, address ->
                add(ComponentDef("shell-$index", "Shell · ${index + 1}", ComponentKind.SHELL, address))
            }
            listOf(
                0xB4D6F1, 0xB4D702, 0xB4D713, 0xB4D724, 0xB4D73A, 0xB4D750,
                0xB4D766, 0xB4D777, 0xB4D788, 0xB4D799, 0xB4D7AF, 0xB4D7C5,
                0xB4D7DB, 0xB4D7E7, 0xB4D7EE, 0xB4D7FA, 0xB4D806, 0xB4D812,
                0xB4D81E, 0xB4D82A, 0xB4D836, 0xB4D842, 0xB4D849, 0xB4D850,
            ).forEachIndexed { index, address ->
                add(ComponentDef("electricity-$index", "Electricity · ${index + 1}", ComponentKind.ELECTRICITY, address))
            }
        }

        val PIXEL_SOURCES = listOf(
            PixelSourceDef(
                key = "metroid-obj",
                name = "Insides, shell, and electricity",
                snesAddress = TILE_DATA_SNES,
                byteCount = TILE_DATA_SIZE,
                editable = true,
                sourceLabel = "Tiles_Metroid",
            ),
        )
    }

    enum class ComponentKind(val displayName: String) {
        INSIDES("Insides"),
        SHELL("Shell"),
        ELECTRICITY("Electricity"),
    }

    enum class AnimationKind {
        RUNTIME_INTRO,
        RUNTIME_STEADY,
        DRAINING,
        SHELL_INTRO,
        SHELL_STEADY,
        ELECTRICITY_INTRO,
        ELECTRICITY_STEADY,
    }

    data class TimedMap(val durationTicks: Int, val snesAddress: Int?)
    data class CompositionDef(
        val key: String,
        val name: String,
        val insideSnes: Int,
        val shellSnes: Int?,
        val electricitySnes: Int?,
    )
    data class AnimationDef(val key: String, val name: String, val kind: AnimationKind)
    data class ComponentDef(val key: String, val name: String, val kind: ComponentKind, val snesAddress: Int)
    data class PixelSourceDef(
        val key: String,
        val name: String,
        val snesAddress: Int,
        val byteCount: Int,
        val editable: Boolean,
        val sourceLabel: String,
    )

    private data class Track(val prefix: List<TimedMap>, val loop: List<TimedMap>)
    private data class TrackState(val frame: TimedMap, val remainingTicks: Int)
    private data class PendingFrame(
        val assembled: EnemySpritemap.AssembledSprite?,
        val durationTicks: Int,
        val label: String,
    )
    private data class NormalizedFrames(val width: Int, val height: Int, val pixels: List<IntArray>)

    private val spritemap = EnemySpritemap(romParser)
    private var rawTiles: ByteArray? = null

    fun load(enemyTiles: ByteArray?): Boolean {
        if (enemyTiles == null || enemyTiles.size != TILE_DATA_SIZE) return false
        rawTiles = enemyTiles.copyOf()
        return readPalette() != null
    }

    fun getRawTileData(): ByteArray? = rawTiles?.copyOf()

    fun readPixelSource(definition: PixelSourceDef): ByteArray? = when (definition.key) {
        "metroid-obj" -> getRawTileData()
        else -> null
    }

    fun readPalette(): IntArray? {
        val start = romParser.snesToPc(PALETTE_SNES)
        val rom = romParser.getRomData()
        if (start < 0 || start + 0x20 > rom.size) return null
        return IntArray(16) { index ->
            if (index == 0) 0 else EnemySpriteGraphics.snesColorToArgb(readU16(rom, start + index * 2))
        }
    }

    fun chasingFrames(): List<TimedMap> = readEnemyFrames(CHASE_LIST_SNES, CHASE_LIST_END_SNES)

    fun drainingFrames(): List<TimedMap> = readEnemyFrames(DRAIN_LIST_SNES, DRAIN_LIST_END_SNES)

    fun electricityIntroFrames(): List<TimedMap> =
        readSpriteObjectFrames(ELECTRICITY_INTRO_LIST_SNES, ELECTRICITY_STEADY_LIST_SNES)

    fun electricitySteadyFrames(): List<TimedMap> =
        readSpriteObjectFrames(ELECTRICITY_STEADY_LIST_SNES, ELECTRICITY_STEADY_END_SNES)

    fun shellIntroFrames(): List<TimedMap> =
        readSpriteObjectFrames(SHELL_INTRO_LIST_SNES, SHELL_STEADY_LIST_SNES)

    fun shellSteadyFrames(): List<TimedMap> =
        readSpriteObjectFrames(SHELL_STEADY_LIST_SNES, SHELL_STEADY_END_SNES)

    fun renderComposition(definition: CompositionDef): EnemySpritemap.AssembledSprite? =
        renderLayers(definition.insideSnes, definition.shellSnes, definition.electricitySnes)

    fun renderComponent(definition: ComponentDef): EnemySpritemap.AssembledSprite? {
        val tiles = rawTiles ?: return null
        val palette = readPalette() ?: return null
        val map = spritemap.parseSpritemap(definition.snesAddress) ?: return null
        return spritemap.renderSpritemap(map, tiles, palette)
    }

    fun renderAnimation(definition: AnimationDef): SpriteAnimation? {
        val pending = when (definition.kind) {
            AnimationKind.RUNTIME_INTRO -> runtimeWindow(
                startTick = 0,
                durationTicks = ELECTRICITY_INTRO_TICKS,
                inside = Track(emptyList(), chasingFrames()),
                resetInsideAtWindow = false,
            )
            AnimationKind.RUNTIME_STEADY -> runtimeWindow(
                startTick = ELECTRICITY_INTRO_TICKS,
                durationTicks = ELECTRICITY_STEADY_TICKS,
                inside = Track(emptyList(), chasingFrames()),
                resetInsideAtWindow = false,
            )
            AnimationKind.DRAINING -> runtimeWindow(
                startTick = ELECTRICITY_INTRO_TICKS,
                durationTicks = DRAIN_VISUAL_TICKS,
                inside = Track(emptyList(), drainingFrames()),
                resetInsideAtWindow = true,
            )
            AnimationKind.SHELL_INTRO -> componentFrames(shellIntroFrames(), "Shell intro")
            AnimationKind.SHELL_STEADY -> componentFrames(shellSteadyFrames(), "Shell steady")
            AnimationKind.ELECTRICITY_INTRO -> componentFrames(electricityIntroFrames(), "Electricity intro")
            AnimationKind.ELECTRICITY_STEADY -> componentFrames(electricitySteadyFrames(), "Electricity steady")
        }
        if (pending.isEmpty()) return null
        val normalized = normalize(pending.map { it.assembled }) ?: return null
        return SpriteAnimation(
            name = definition.name,
            frames = pending.mapIndexed { index, frame ->
                SpriteAnimationFrame(
                    pixels = normalized.pixels[index],
                    width = normalized.width,
                    height = normalized.height,
                    durationTicks = frame.durationTicks,
                    label = frame.label,
                )
            },
            loop = definition.kind == AnimationKind.SHELL_STEADY ||
                definition.kind == AnimationKind.ELECTRICITY_STEADY,
        )
    }

    private fun runtimeWindow(
        startTick: Int,
        durationTicks: Int,
        inside: Track,
        resetInsideAtWindow: Boolean,
    ): List<PendingFrame> {
        val shell = Track(shellIntroFrames(), shellSteadyFrames())
        val electricity = Track(electricityIntroFrames(), electricitySteadyFrames())
        if (inside.loop.isEmpty() || shell.prefix.isEmpty() || shell.loop.isEmpty() ||
            electricity.prefix.isEmpty() || electricity.loop.isEmpty()) return emptyList()

        val output = mutableListOf<PendingFrame>()
        var elapsed = 0
        while (elapsed < durationTicks) {
            val runtimeTick = startTick + elapsed
            val insideTick = if (resetInsideAtWindow) elapsed else runtimeTick
            val insideState = stateAt(inside, insideTick) ?: return emptyList()
            val shellState = stateAt(shell, runtimeTick) ?: return emptyList()
            val electricityState = stateAt(electricity, runtimeTick) ?: return emptyList()
            val step = minOf(
                durationTicks - elapsed,
                insideState.remainingTicks,
                shellState.remainingTicks,
                electricityState.remainingTicks,
            )
            if (step <= 0) return emptyList()
            output += PendingFrame(
                assembled = renderLayers(
                    insideState.frame.snesAddress,
                    shellState.frame.snesAddress,
                    electricityState.frame.snesAddress,
                ),
                durationTicks = step,
                label = "${startTick + elapsed}–${startTick + elapsed + step - 1} ticks",
            )
            elapsed += step
        }
        return output
    }

    private fun componentFrames(frames: List<TimedMap>, label: String): List<PendingFrame> =
        frames.mapIndexed { index, frame ->
            PendingFrame(
                assembled = frame.snesAddress?.let { renderSingle(it) },
                durationTicks = frame.durationTicks,
                label = "$label ${index + 1}",
            )
        }

    private fun renderLayers(
        insideSnes: Int?,
        shellSnes: Int?,
        electricitySnes: Int?,
    ): EnemySpritemap.AssembledSprite? {
        val tiles = rawTiles ?: return null
        val palette = readPalette() ?: return null
        val entries = buildList {
            // Sprite objects are allocated/drawn electricity first, shell second;
            // the enemy insides sit below both. renderSpritemap reverses this list
            // so its first entries retain the lower-OAM-index foreground priority.
            electricitySnes?.let { address -> spritemap.parseSpritemap(address)?.entries?.let(::addAll) }
            shellSnes?.let { address -> spritemap.parseSpritemap(address)?.entries?.let(::addAll) }
            insideSnes?.let { address -> spritemap.parseSpritemap(address)?.entries?.let(::addAll) }
        }
        if (entries.isEmpty()) return null
        return spritemap.renderSpritemap(
            EnemySpritemap.Spritemap(entries, insideSnes ?: shellSnes ?: electricitySnes ?: 0),
            tiles,
            palette,
        )
    }

    private fun renderSingle(snesAddress: Int): EnemySpritemap.AssembledSprite? {
        val tiles = rawTiles ?: return null
        val palette = readPalette() ?: return null
        val map = spritemap.parseSpritemap(snesAddress) ?: return null
        return spritemap.renderSpritemap(map, tiles, palette)
    }

    private fun stateAt(track: Track, tick: Int): TrackState? {
        if (tick < 0) return null
        val prefixTicks = track.prefix.sumOf { it.durationTicks }
        if (tick < prefixTicks) return stateIn(track.prefix, tick)
        val loopTicks = track.loop.sumOf { it.durationTicks }
        if (loopTicks <= 0) return null
        return stateIn(track.loop, (tick - prefixTicks) % loopTicks)
    }

    private fun stateIn(frames: List<TimedMap>, tick: Int): TrackState? {
        var cursor = 0
        frames.forEach { frame ->
            val end = cursor + frame.durationTicks
            if (tick < end) return TrackState(frame, end - tick)
            cursor = end
        }
        return null
    }

    private fun readEnemyFrames(startSnes: Int, endSnes: Int): List<TimedMap> {
        val rom = romParser.getRomData()
        val bank = startSnes and 0xFF0000
        var pc = romParser.snesToPc(startSnes)
        val end = romParser.snesToPc(endSnes)
        val output = mutableListOf<TimedMap>()
        while (pc < end) {
            val word = readU16(rom, pc)
            when {
                word < 0x8000 -> {
                    if (pc + 4 > end) return emptyList()
                    output += TimedMap(word, bank or readU16(rom, pc + 2))
                    pc += 4
                }
                word == 0xEAA5 || word == 0xEAB1 -> pc += 2
                word == 0x80ED -> pc += 4
                else -> return emptyList()
            }
        }
        return output
    }

    private fun readSpriteObjectFrames(startSnes: Int, endSnes: Int): List<TimedMap> {
        val rom = romParser.getRomData()
        val bank = startSnes and 0xFF0000
        var pc = romParser.snesToPc(startSnes)
        val end = romParser.snesToPc(endSnes)
        val output = mutableListOf<TimedMap>()
        while (pc < end) {
            val duration = readU16(rom, pc)
            if (duration >= 0x8000) {
                // Steady lists terminate with GotoParameter + their own pointer.
                if (duration != 0xBD12 || pc + 4 != end) return emptyList()
                pc += 4
                continue
            }
            if (pc + 4 > end || duration <= 0) return emptyList()
            val address = bank or readU16(rom, pc + 2)
            output += TimedMap(duration, address.takeUnless { it == EMPTY_DRAW_SNES })
            pc += 4
        }
        return output
    }

    private fun normalize(sprites: List<EnemySpritemap.AssembledSprite?>): NormalizedFrames? {
        val visible = sprites.filterNotNull()
        if (visible.isEmpty()) return null
        val minX = visible.minOf { -it.originX }
        val minY = visible.minOf { -it.originY }
        val maxX = visible.maxOf { it.width - it.originX }
        val maxY = visible.maxOf { it.height - it.originY }
        val width = maxX - minX
        val height = maxY - minY
        if (width <= 0 || height <= 0) return null
        val frames = sprites.map { sprite ->
            IntArray(width * height).also { output ->
                if (sprite != null) {
                    for (y in 0 until sprite.height) for (x in 0 until sprite.width) {
                        val color = sprite.pixels[y * sprite.width + x]
                        if (color ushr 24 == 0) continue
                        val dx = x - sprite.originX - minX
                        val dy = y - sprite.originY - minY
                        if (dx in 0 until width && dy in 0 until height) output[dy * width + dx] = color
                    }
                }
            }
        }
        return NormalizedFrames(width, height, frames)
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
