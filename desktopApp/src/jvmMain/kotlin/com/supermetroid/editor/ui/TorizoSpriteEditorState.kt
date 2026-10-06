package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.TorizoSpritemap

/** One editor state for both Torizo encounters and their source-owned projectiles. */
class TorizoSpriteEditorState(
    private val loadEnemyTileData: (RomParser, Int) -> ByteArray?,
) {
    data class SourceSheet(
        val definition: TorizoSpritemap.PixelSourceDef,
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val palette: IntArray,
    )

    private var spritemap: TorizoSpritemap? = null

    fun getSpritemap(romParser: RomParser): TorizoSpritemap? {
        spritemap?.let { return it }
        val renderer = TorizoSpritemap(romParser)
        val tiles = loadEnemyTileData(romParser, TorizoSpritemap.BOMB_SPECIES_ID)
        if (!renderer.load(tiles)) return null
        spritemap = renderer
        return renderer
    }

    fun renderComposition(
        romParser: RomParser,
        definition: TorizoSpritemap.CompositionDef,
        palette: TorizoSpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? = getSpritemap(romParser)?.renderComposition(definition, palette)

    fun renderComponent(
        romParser: RomParser,
        definition: TorizoSpritemap.ComponentDef,
        palette: TorizoSpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? = getSpritemap(romParser)?.renderComponent(definition, palette)

    fun renderAnimation(
        romParser: RomParser,
        definition: TorizoSpritemap.AnimationDef,
        palette: TorizoSpritemap.PaletteStageDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderAnimation(definition, palette)

    fun renderProjectileAnimation(
        romParser: RomParser,
        definition: TorizoSpritemap.ProjectileAnimationDef,
        palette: TorizoSpritemap.PaletteStageDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderProjectileAnimation(definition, palette)

    fun loadSourceSheet(
        romParser: RomParser,
        definition: TorizoSpritemap.PixelSourceDef,
        palette: TorizoSpritemap.PaletteStageDef,
    ): SourceSheet? {
        val renderer = getSpritemap(romParser) ?: return null
        val bytes = renderer.readPixelSource(definition) ?: return null
        val colors = renderer.readPalette(palette) ?: return null
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(bytes))
        val columns = if (definition.byteCount >= 0x1000) 16 else 8
        val (pixels, width, height) = graphics.renderSheet(colors, cols = columns) ?: return null
        return SourceSheet(definition, pixels, width, height, colors)
    }

    fun renderEditedCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: TorizoSpritemap.PaletteStageDef,
    ): List<EnemySpritemap.AssembledSprite> {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return emptyList()
        val current = getSpritemap(romParser) ?: return emptyList()
        val raw = current.getRawTileData() ?: return emptyList()
        val colors = current.readPalette(palette) ?: return emptyList()
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(raw))
        graphics.importFromArgb(pixels, width, height, colors, cols = 16)
        val edited = graphics.getRawBlocks()?.firstOrNull() ?: return emptyList()
        val preview = TorizoSpritemap(romParser)
        if (!preview.load(edited)) return emptyList()
        return TorizoSpritemap.COMPOSITIONS.filter { it.runtimeTiles == TorizoSpritemap.RuntimeTiles.BASE }
            .take(6).mapNotNull { preview.renderComposition(it, palette) }
    }

    fun getEditingPalette(
        romParser: RomParser,
        palette: TorizoSpritemap.PaletteStageDef,
    ): IntArray? = getSpritemap(romParser)?.readPalette(palette)

    fun invalidate() {
        spritemap = null
    }
}
