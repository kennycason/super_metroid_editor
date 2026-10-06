package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SporeSpawnSpritemap
import com.supermetroid.editor.rom.SpriteAnimation

/** Keeps Spore Spawn's body and separately-owned runtime projectiles in one workspace. */
class SporeSpawnSpriteEditorState(
    private val loadEnemyTileData: (RomParser, Int) -> ByteArray?,
) {
    data class SourceSheet(
        val definition: SporeSpawnSpritemap.PixelSourceDef,
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val palette: IntArray,
    )

    private var spritemap: SporeSpawnSpritemap? = null

    fun getSpritemap(romParser: RomParser): SporeSpawnSpritemap? {
        spritemap?.let { return it }
        val renderer = SporeSpawnSpritemap(romParser)
        val tiles = loadEnemyTileData(romParser, SporeSpawnSpritemap.SPECIES_ID)
        if (!renderer.load(tiles)) return null
        spritemap = renderer
        return renderer
    }

    fun renderComposition(
        romParser: RomParser,
        definition: SporeSpawnSpritemap.CompositionDef,
        palette: SporeSpawnSpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComposition(definition, palette)

    fun renderComponent(
        romParser: RomParser,
        definition: SporeSpawnSpritemap.ComponentDef,
        palette: SporeSpawnSpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComponent(definition, palette)

    fun renderAnimation(
        romParser: RomParser,
        definition: SporeSpawnSpritemap.InstructionListDef,
        palette: SporeSpawnSpritemap.PaletteStageDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderAnimation(definition, palette)

    fun renderSpawnerAnimation(
        romParser: RomParser,
        palette: SporeSpawnSpritemap.PaletteStageDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderSpawnerAnimation(palette)

    fun renderSporeAnimation(romParser: RomParser): SpriteAnimation? =
        getSpritemap(romParser)?.renderSporeAnimation()

    fun loadSourceSheet(
        romParser: RomParser,
        definition: SporeSpawnSpritemap.PixelSourceDef,
        palette: SporeSpawnSpritemap.PaletteStageDef,
    ): SourceSheet? {
        val renderer = getSpritemap(romParser) ?: return null
        val bytes = renderer.readPixelSource(definition) ?: return null
        val colors = renderer.readPalette(palette) ?: return null
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(bytes))
        val (pixels, width, height) = graphics.renderSheet(colors, cols = 16) ?: return null
        return SourceSheet(definition, pixels, width, height, colors)
    }

    fun renderEditedCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: SporeSpawnSpritemap.PaletteStageDef,
    ): List<EnemySpritemap.AssembledSprite> {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return emptyList()
        val current = getSpritemap(romParser) ?: return emptyList()
        val raw = current.getRawTileData() ?: return emptyList()
        val colors = current.readPalette(palette) ?: return emptyList()
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(raw))
        graphics.importFromArgb(pixels, width, height, colors, cols = 16)
        val edited = graphics.getRawBlocks()?.firstOrNull() ?: return emptyList()
        val preview = SporeSpawnSpritemap(romParser)
        if (!preview.load(edited)) return emptyList()
        return SporeSpawnSpritemap.COMPOSITIONS.take(4).mapNotNull {
            preview.renderComposition(it, palette)
        }
    }

    fun getEditingPalette(
        romParser: RomParser,
        palette: SporeSpawnSpritemap.PaletteStageDef,
    ): IntArray? = getSpritemap(romParser)?.readPalette(palette)

    fun invalidate() {
        spritemap = null
    }
}
