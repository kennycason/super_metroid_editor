package com.supermetroid.editor.rom

/**
 * Source-backed Spore Spawn renderer.
 *
 * The visible boss is split across two engine systems: bank-$A5 extended
 * spritemaps draw the head/body, while four bank-$86 enemy projectiles draw
 * the stalk. [renderComposition] and [renderAnimation] reproduce the exact
 * quarter/half/three-quarter stalk interpolation used by $A5:EC49.
 */
class SporeSpawnSpritemap(private val romParser: RomParser) {

    companion object {
        const val SPECIES_ID = 0xDF3F
        const val STALK_SPECIES_ID = 0xDF7F
        const val TILE_DATA_SNES = 0xAC9C00
        const val TILE_DATA_SIZE = 0x0E00

        const val BASE_PALETTE_SNES = 0xA5E359
        const val HEALTH_PALETTE_SNES = 0xA5E379
        const val DEATH_PALETTE_SNES = 0xA5E3F9
        const val DEATH_LEVEL_PALETTE_SNES = 0xA5E4F9
        const val DEATH_BACKGROUND_PALETTE_SNES = 0xA5E5D9

        const val STALK_SPRITEMAP_SNES = 0x8DA994
        const val SPAWNER_0_SPRITEMAP_SNES = 0x8DA99B
        const val SPAWNER_1_SPRITEMAP_SNES = 0x8DA9A2
        const val SPAWNER_2_SPRITEMAP_SNES = 0x8DA9A9
        const val SPORE_0_SPRITEMAP_SNES = 0x8DA9B0
        const val SPORE_1_SPRITEMAP_SNES = 0x8DA9B7
        const val SPORE_2_SPRITEMAP_SNES = 0x8DA9BE

        private const val STALK_X_ORIGIN = 0x80
        private const val STALK_Y_ORIGIN = 0x228
        private const val STALK_BASE_X = 0x80
        private const val STALK_BASE_Y = 0x230

        private val HANDLER_OPERAND_BYTES = mapOf(
            0x80ED to 2,
            0x8110 to 2,
            0x8123 to 2,
            0x812F to 0,
            0x813A to 2,
            0xE75F to 0,
            0xE771 to 0,
            0xE82D to 4,
            0xE872 to 2,
            0xE87C to 0,
            0xE895 to 2,
            0xE8B1 to 0,
            0xE8BA to 2,
            0xE8CA to 2,
            0xE91C to 2,
            0xE96E to 0,
            0xE9B1 to 0,
        )

        /** Every active source-declared Spore Spawn instruction list. */
        val INSTRUCTION_LISTS = listOf(
            InstructionListDef("initial-dead", "Initial · defeated", 0xA5E6B9, 14, 1, false),
            InstructionListDef("initial-alive", "Descent", 0xA5E6C7, 14, 2, false),
            InstructionListDef("fight-started", "Fight starts", 0xA5E6D5, 14, 1, false),
            InstructionListDef("open", "Open and stop", 0xA5E6E3, 50, 8, false),
            InstructionListDef("fully-open", "Fully open", 0xA5E715, 20, 4, true),
            InstructionListDef("close", "Close and move", 0xA5E729, 54, 9, false),
            InstructionListDef("death-start", "Death · start", 0xA5E77D, 16, 1, false),
            InstructionListDef("death-close", "Death · close", 0xA5E78D, 48, 8, false),
            InstructionListDef("death-harden", "Death · harden", 0xA5E7BD, 84, 7, false),
        )

        val ANIMATIONS = INSTRUCTION_LISTS.filter { it.expectedFrameCount > 1 }

        /** Representative engine positions; stalk placement remains exact for each position. */
        val COMPOSITIONS = listOf(
            CompositionDef("descent", "Descent", 0xA5EE6F, 0x80, 0x1F0),
            CompositionDef("closed-center", "Closed · fight center", 0xA5EE6F, 0x80, 0x270),
            CompositionDef("opening", "Opening", 0xA5EEAF, 0x80, 0x270),
            CompositionDef("open", "Fully open", 0xA5EF3D, 0x80, 0x270),
            CompositionDef("left", "Closed · left extent", 0xA5EE6F, 0x40, 0x270),
            CompositionDef("right", "Closed · right extent", 0xA5EE6F, 0xC0, 0x270),
            CompositionDef("dead", "Defeated", 0xA5EE65, 0x80, 0x270, deathPaletteIndex = 7),
        )

        val COMPONENTS = listOf(
            ComponentDef("body-closed", "Body · closed", ComponentKind.BODY, 0xA5EE6F),
            ComponentDef("body-open", "Body · fully open", ComponentKind.BODY, 0xA5EF3D),
            ComponentDef("body-dead", "Body · defeated", ComponentKind.BODY, 0xA5EE65, deathPaletteIndex = 7),
            ComponentDef("stalk", "Stalk segment", ComponentKind.PROJECTILE, STALK_SPRITEMAP_SNES),
            ComponentDef("spawner-0", "Spore spawner · closed", ComponentKind.PROJECTILE, SPAWNER_0_SPRITEMAP_SNES),
            ComponentDef("spawner-1", "Spore spawner · opening", ComponentKind.PROJECTILE, SPAWNER_1_SPRITEMAP_SNES),
            ComponentDef("spawner-2", "Spore spawner · open", ComponentKind.PROJECTILE, SPAWNER_2_SPRITEMAP_SNES),
            ComponentDef("spore-0", "Spore · frame 1", ComponentKind.PROJECTILE, SPORE_0_SPRITEMAP_SNES, useSporePalette = true),
            ComponentDef("spore-1", "Spore · frame 2", ComponentKind.PROJECTILE, SPORE_1_SPRITEMAP_SNES, useSporePalette = true),
            ComponentDef("spore-2", "Spore · frame 3", ComponentKind.PROJECTILE, SPORE_2_SPRITEMAP_SNES, useSporePalette = true),
        )

        val PALETTE_STAGES = listOf(
            PaletteStageDef("healthy", "Healthy · ≥ 770 HP", HEALTH_PALETTE_SNES),
            PaletteStageDef("hurt-1", "Hurt · < 770 HP", HEALTH_PALETTE_SNES + 0x20),
            PaletteStageDef("hurt-2", "Hurt · < 410 HP", HEALTH_PALETTE_SNES + 0x40),
            PaletteStageDef("critical", "Critical · < 70 HP", HEALTH_PALETTE_SNES + 0x60),
            *Array(8) { index ->
                PaletteStageDef("death-$index", "Death palette ${index + 1}", DEATH_PALETTE_SNES + index * 0x20, index)
            },
        )

        val PIXEL_SOURCES = listOf(
            PixelSourceDef(
                key = "shared-obj",
                name = "Body, stalk, spawners, and spores",
                snesAddress = TILE_DATA_SNES,
                byteCount = TILE_DATA_SIZE,
                editable = true,
                sourceLabel = "Tiles_SporeSpawn",
            ),
        )
    }

    data class InstructionListDef(
        val key: String,
        val name: String,
        val snesAddress: Int,
        val byteCount: Int,
        val expectedFrameCount: Int,
        val loop: Boolean,
    )

    data class SourceFrame(
        val durationTicks: Int,
        val snesAddress: Int,
        val deathPaletteIndex: Int? = null,
    )

    data class CompositionDef(
        val key: String,
        val name: String,
        val snesAddress: Int,
        val bodyX: Int,
        val bodyY: Int,
        val deathPaletteIndex: Int? = null,
    )

    enum class ComponentKind { BODY, PROJECTILE }

    data class ComponentDef(
        val key: String,
        val name: String,
        val kind: ComponentKind,
        val snesAddress: Int,
        val useSporePalette: Boolean = false,
        val deathPaletteIndex: Int? = null,
    )

    data class PaletteStageDef(
        val key: String,
        val name: String,
        val snesAddress: Int,
        val deathPaletteIndex: Int? = null,
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

    fun readPalette(definition: PaletteStageDef): IntArray? = readPaletteAt(definition.snesAddress)

    fun readBaseSporePalette(): IntArray? = readPaletteAt(BASE_PALETTE_SNES)

    fun readPixelSource(definition: PixelSourceDef): ByteArray? = when (definition.key) {
        "shared-obj" -> getRawTileData()
        else -> null
    }

    /** Byte-bounded decoder: it never follows a branch into another source list. */
    fun sourceFrames(definition: InstructionListDef): List<SourceFrame> {
        val rom = romParser.getRomData()
        val start = romParser.snesToPc(definition.snesAddress)
        if (start < 0 || start + definition.byteCount > rom.size) return emptyList()
        val frames = mutableListOf<SourceFrame>()
        var deathPaletteIndex: Int? = if (definition.key == "initial-dead") 7 else null
        var offset = 0
        while (offset < definition.byteCount) {
            val word = readU16(rom, start + offset)
            if (word < 0x8000) {
                if (offset + 4 > definition.byteCount) return emptyList()
                val pointer = readU16(rom, start + offset + 2)
                frames += SourceFrame(
                    durationTicks = word,
                    snesAddress = (definition.snesAddress and 0xFF0000) or pointer,
                    deathPaletteIndex = deathPaletteIndex,
                )
                offset += 4
            } else {
                val operandBytes = HANDLER_OPERAND_BYTES[word] ?: return emptyList()
                if (offset + 2 + operandBytes > definition.byteCount) return emptyList()
                if (word == 0xE8CA && operandBytes == 2) {
                    deathPaletteIndex = readU16(rom, start + offset + 2) / 0x20
                }
                offset += 2 + operandBytes
            }
        }
        return frames
    }

    fun renderComposition(
        definition: CompositionDef,
        paletteDefinition: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? {
        val palette = definition.deathPaletteIndex?.let(::readDeathPalette)
            ?: readPalette(paletteDefinition)
            ?: return null
        return renderFullBoss(definition.snesAddress, definition.bodyX, definition.bodyY, palette)
    }

    fun renderComponent(
        definition: ComponentDef,
        paletteDefinition: PaletteStageDef = PALETTE_STAGES.first(),
    ): EnemySpritemap.AssembledSprite? {
        val tiles = rawTiles ?: return null
        val palette = when {
            definition.useSporePalette -> readBaseSporePalette()
            definition.deathPaletteIndex != null -> readDeathPalette(definition.deathPaletteIndex)
            else -> readPalette(paletteDefinition)
        } ?: return null
        return when (definition.kind) {
            ComponentKind.BODY -> renderBody(definition.snesAddress, tiles, palette)
            ComponentKind.PROJECTILE -> spritemap.parseSpritemap(definition.snesAddress)?.let {
                spritemap.renderSpritemap(it, tiles, palette)
            }
        }
    }

    fun renderAnimation(
        definition: InstructionListDef,
        paletteDefinition: PaletteStageDef = PALETTE_STAGES.first(),
    ): SpriteAnimation? {
        val frames = sourceFrames(definition)
        if (frames.size != definition.expectedFrameCount) return null
        val palette = readPalette(paletteDefinition) ?: return null
        val position = if (definition.key == "initial-alive") 0x80 to 0x1F0 else 0x80 to 0x270
        val rendered = frames.mapIndexed { index, source ->
            val framePalette = source.deathPaletteIndex?.let(::readDeathPalette) ?: palette
            val image = renderFullBoss(source.snesAddress, position.first, position.second, framePalette)
                ?: return null
            SpriteAnimationFrame(
                pixels = image.pixels,
                width = image.width,
                height = image.height,
                durationTicks = source.durationTicks.takeIf { it in 1..120 } ?: 8,
                label = "${definition.name} ${index + 1}",
            )
        }
        return SpriteAnimation(definition.name, rendered, definition.loop)
    }

    fun renderSpawnerAnimation(
        paletteDefinition: PaletteStageDef = PALETTE_STAGES.first(),
    ): SpriteAnimation? {
        val palette = readPalette(paletteDefinition) ?: return null
        return renderProjectileAnimation(
            name = "Spore spawner",
            addresses = listOf(SPAWNER_0_SPRITEMAP_SNES, SPAWNER_1_SPRITEMAP_SNES, SPAWNER_2_SPRITEMAP_SNES,
                SPAWNER_1_SPRITEMAP_SNES, SPAWNER_0_SPRITEMAP_SNES),
            durations = listOf(1, 6, 16, 6, 1),
            loop = false,
            palette = palette,
        )
    }

    fun renderSporeAnimation(): SpriteAnimation? {
        val palette = readBaseSporePalette() ?: return null
        return renderProjectileAnimation(
            name = "Spore",
            addresses = listOf(SPORE_0_SPRITEMAP_SNES, SPORE_1_SPRITEMAP_SNES, SPORE_2_SPRITEMAP_SNES),
            durations = listOf(5, 5, 5),
            loop = true,
            palette = palette,
        )
    }

    private fun renderProjectileAnimation(
        name: String,
        addresses: List<Int>,
        durations: List<Int>,
        loop: Boolean,
        palette: IntArray,
    ): SpriteAnimation? {
        val tiles = rawTiles ?: return null
        val frames = addresses.mapIndexed { index, address ->
            val map = spritemap.parseSpritemap(address) ?: return null
            val image = spritemap.renderSpritemap(map, tiles, palette) ?: return null
            SpriteAnimationFrame(image.pixels, image.width, image.height, durations[index], "$name ${index + 1}")
        }
        return SpriteAnimation(name, frames, loop)
    }

    private fun renderFullBoss(
        frameAddress: Int,
        bodyX: Int,
        bodyY: Int,
        palette: IntArray,
    ): EnemySpritemap.AssembledSprite? {
        val tiles = rawTiles ?: return null
        val frame = spritemap.parseRenderableFrame(frameAddress) ?: return null
        val body = spritemap.flattenRenderableFrame(frame)
        val stalk = spritemap.parseSpritemap(STALK_SPRITEMAP_SNES) ?: return null
        val entries = body.entries + stalkPositions(bodyX, bodyY).flatMap { (x, y) ->
            stalk.entries.map { entry ->
                entry.copy(
                    xOffset = entry.xOffset + x - bodyX,
                    yOffset = entry.yOffset + y - bodyY,
                )
            }
        }
        return spritemap.renderSpritemap(
            EnemySpritemap.Spritemap(entries, frameAddress),
            tiles,
            palette,
        )
    }

    private fun renderBody(
        frameAddress: Int,
        tiles: ByteArray,
        palette: IntArray,
    ): EnemySpritemap.AssembledSprite? {
        val frame = spritemap.parseRenderableFrame(frameAddress) ?: return null
        val flattened = spritemap.flattenRenderableFrame(frame)
        return spritemap.renderSpritemap(flattened, tiles, palette)
    }

    /** Runtime order: fixed base, quarter, half, and three-quarter segments. */
    internal fun stalkPositions(bodyX: Int, bodyY: Int): List<Pair<Int, Int>> {
        val dx = bodyX - STALK_X_ORIGIN
        val dy = (bodyY - 0x28) - STALK_Y_ORIGIN
        return listOf(
            STALK_BASE_X to STALK_BASE_Y,
            STALK_X_ORIGIN + signedFraction(dx, 2) to STALK_Y_ORIGIN + signedFraction(dy, 2),
            STALK_X_ORIGIN + signedFraction(dx, 1) to STALK_Y_ORIGIN + signedFraction(dy, 1),
            STALK_X_ORIGIN + signedThreeQuarters(dx) to STALK_Y_ORIGIN + signedThreeQuarters(dy),
        )
    }

    private fun signedFraction(value: Int, shift: Int): Int =
        if (value < 0) -((-value) shr shift) else value shr shift

    private fun signedThreeQuarters(value: Int): Int =
        signedFraction(value, 1) + signedFraction(value, 2)

    private fun readDeathPalette(index: Int): IntArray? =
        if (index in 0..7) readPaletteAt(DEATH_PALETTE_SNES + index * 0x20) else null

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
