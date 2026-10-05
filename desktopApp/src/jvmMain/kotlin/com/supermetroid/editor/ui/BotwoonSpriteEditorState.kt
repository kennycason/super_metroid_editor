package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.BotwoonSpritemap
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation

/** Keeps Botwoon's enemy head and thirteen body projectiles in one editor workspace. */
class BotwoonSpriteEditorState(
    private val loadEnemyTileData: (RomParser, Int) -> ByteArray?,
) {
    data class SourceSheet(
        val definition: BotwoonSpritemap.PixelSourceDef,
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val palette: IntArray,
    )

    private var spritemap: BotwoonSpritemap? = null

    fun getSpritemap(romParser: RomParser): BotwoonSpritemap? {
        spritemap?.let { return it }
        val renderer = BotwoonSpritemap(romParser)
        val tiles = loadEnemyTileData(romParser, BotwoonSpritemap.SPECIES_ID)
        if (!renderer.load(tiles)) return null
        spritemap = renderer
        return renderer
    }

    fun renderComposition(
        romParser: RomParser,
        definition: BotwoonSpritemap.CompositionDef,
        palette: BotwoonSpritemap.PaletteStageDef,
        mouthOpen: Boolean,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComposition(definition, palette, mouthOpen)

    fun renderComponent(
        romParser: RomParser,
        definition: BotwoonSpritemap.ComponentDef,
        palette: BotwoonSpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComponent(definition, palette)

    fun renderSwimAnimation(
        romParser: RomParser,
        direction: BotwoonSpritemap.DirectionDef,
        palette: BotwoonSpritemap.PaletteStageDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderSwimAnimation(direction, palette)

    fun renderSpitAnimation(
        romParser: RomParser,
        direction: BotwoonSpritemap.DirectionDef,
        palette: BotwoonSpritemap.PaletteStageDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderSpitAnimation(direction, palette)

    fun renderSpitProjectileAnimation(
        romParser: RomParser,
        palette: BotwoonSpritemap.PaletteStageDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderSpitProjectileAnimation(palette)

    fun loadSourceSheet(
        romParser: RomParser,
        definition: BotwoonSpritemap.PixelSourceDef,
        palette: BotwoonSpritemap.PaletteStageDef,
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
        palette: BotwoonSpritemap.PaletteStageDef,
    ): List<EnemySpritemap.AssembledSprite> {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return emptyList()
        val current = getSpritemap(romParser) ?: return emptyList()
        val raw = current.getRawTileData() ?: return emptyList()
        val colors = current.readPalette(palette) ?: return emptyList()
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(raw))
        graphics.importFromArgb(pixels, width, height, colors, cols = 16)
        val edited = graphics.getRawBlocks()?.firstOrNull() ?: return emptyList()
        val preview = BotwoonSpritemap(romParser)
        if (!preview.load(edited)) return emptyList()
        return BotwoonSpritemap.COMPOSITIONS.take(4).mapNotNull {
            preview.renderComposition(it, palette)
        }
    }

    fun getEditingPalette(
        romParser: RomParser,
        palette: BotwoonSpritemap.PaletteStageDef,
    ): IntArray? = getSpritemap(romParser)?.readPalette(palette)

    fun invalidate() {
        spritemap = null
    }
}
