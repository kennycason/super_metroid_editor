package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.MetroidSpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation

/** Keeps the enemy-owned insides and both sprite-object companions in one workspace. */
class MetroidSpriteEditorState(
    private val loadEnemyTileData: (RomParser, Int) -> ByteArray?,
) {
    data class SourceSheet(
        val definition: MetroidSpritemap.PixelSourceDef,
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val palette: IntArray,
    )

    private var spritemap: MetroidSpritemap? = null

    fun getSpritemap(romParser: RomParser): MetroidSpritemap? {
        spritemap?.let { return it }
        val renderer = MetroidSpritemap(romParser)
        val tiles = loadEnemyTileData(romParser, MetroidSpritemap.SPECIES_ID)
        if (!renderer.load(tiles)) return null
        spritemap = renderer
        return renderer
    }

    fun renderComposition(
        romParser: RomParser,
        definition: MetroidSpritemap.CompositionDef,
    ): EnemySpritemap.AssembledSprite? = getSpritemap(romParser)?.renderComposition(definition)

    fun renderComponent(
        romParser: RomParser,
        definition: MetroidSpritemap.ComponentDef,
    ): EnemySpritemap.AssembledSprite? = getSpritemap(romParser)?.renderComponent(definition)

    fun renderAnimation(
        romParser: RomParser,
        definition: MetroidSpritemap.AnimationDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderAnimation(definition)

    fun loadSourceSheet(
        romParser: RomParser,
        definition: MetroidSpritemap.PixelSourceDef,
    ): SourceSheet? {
        val renderer = getSpritemap(romParser) ?: return null
        val bytes = renderer.readPixelSource(definition) ?: return null
        val colors = renderer.readPalette() ?: return null
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
    ): List<EnemySpritemap.AssembledSprite> {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return emptyList()
        val current = getSpritemap(romParser) ?: return emptyList()
        val raw = current.getRawTileData() ?: return emptyList()
        val colors = current.readPalette() ?: return emptyList()
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(raw))
        graphics.importFromArgb(pixels, width, height, colors, cols = 16)
        val edited = graphics.getRawBlocks()?.firstOrNull() ?: return emptyList()
        val preview = MetroidSpritemap(romParser)
        if (!preview.load(edited)) return emptyList()
        return MetroidSpritemap.COMPOSITIONS.mapNotNull(preview::renderComposition)
    }

    fun getEditingPalette(romParser: RomParser): IntArray? =
        getSpritemap(romParser)?.readPalette()

    fun invalidate() {
        spritemap = null
    }
}
