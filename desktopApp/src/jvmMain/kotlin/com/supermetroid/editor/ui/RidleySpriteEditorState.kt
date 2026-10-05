package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RidleySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation

/** Coordinates Ridley's body, independently drawn wings/tail, and runtime DMA tile swaps. */
class RidleySpriteEditorState(
    private val loadEnemyTileData: (RomParser, Int) -> ByteArray?,
) {
    data class SourceSheet(
        val name: String,
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val palette: IntArray,
        val editable: Boolean,
    )

    private var spritemap: RidleySpritemap? = null

    fun getSpritemap(romParser: RomParser): RidleySpritemap? {
        spritemap?.let { return it }
        val renderer = RidleySpritemap(romParser)
        val raw = loadEnemyTileData(romParser, RidleySpritemap.RIDLEY_SPECIES_ID)
        if (!renderer.load(raw)) return null
        spritemap = renderer
        return renderer
    }

    fun renderComposition(
        romParser: RomParser,
        definition: RidleySpritemap.CompositionDef,
        palette: RidleySpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComposition(definition, palette)

    fun renderAnimation(
        romParser: RomParser,
        definition: RidleySpritemap.AnimationDef,
        palette: RidleySpritemap.PaletteStageDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderAnimation(definition, palette)

    fun renderBody(
        romParser: RomParser,
        definition: RidleySpritemap.BodyDef,
        palette: RidleySpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? = getSpritemap(romParser)?.renderBody(definition, palette)

    fun renderWing(
        romParser: RomParser,
        definition: RidleySpritemap.WingDef,
        palette: RidleySpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? = getSpritemap(romParser)?.renderWing(definition, palette)

    fun renderTail(
        romParser: RomParser,
        definition: RidleySpritemap.OamComponentDef,
        palette: RidleySpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? = getSpritemap(romParser)?.renderTailComponent(definition, palette)

    fun loadBaseSource(romParser: RomParser): SourceSheet? {
        val renderer = getSpritemap(romParser) ?: return null
        val raw = renderer.getRawTileData() ?: return null
        val palette = renderer.readPalette() ?: return null
        return renderSheet(romParser, "Ridley OBJ · 256 tiles", raw, palette, editable = true)
    }

    fun loadRuntimeSource(
        romParser: RomParser,
        definition: RidleySpritemap.RuntimeSourceDef,
    ): SourceSheet? {
        val renderer = getSpritemap(romParser) ?: return null
        val raw = renderer.readRuntimeSource(definition) ?: return null
        val palette = renderer.readPalette() ?: return null
        return renderSheet(romParser, definition.name, raw, palette, editable = false)
    }

    fun renderEditedCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        paletteStage: RidleySpritemap.PaletteStageDef,
    ): List<EnemySpritemap.AssembledSprite> {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return emptyList()
        val renderer = getSpritemap(romParser) ?: return emptyList()
        val raw = renderer.getRawTileData() ?: return emptyList()
        val palette = renderer.readPalette() ?: return emptyList()
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(raw))
        graphics.importFromArgb(pixels, width, height, palette, cols = 16)
        val edited = graphics.getRawBlocks()?.firstOrNull() ?: return emptyList()
        val preview = RidleySpritemap(romParser)
        if (!preview.load(edited)) return emptyList()
        return RidleySpritemap.COMPOSITIONS.mapNotNull { preview.renderComposition(it, paletteStage) }
    }

    fun getPalette(
        romParser: RomParser,
        definition: RidleySpritemap.PaletteStageDef,
    ): IntArray? = getSpritemap(romParser)?.readPalette(definition)

    fun invalidate() {
        spritemap = null
    }

    private fun renderSheet(
        romParser: RomParser,
        name: String,
        raw: ByteArray,
        palette: IntArray,
        editable: Boolean,
    ): SourceSheet? {
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(raw))
        val columns = if (raw.size >= RidleySpritemap.RAW_TILES_SIZE) 16 else 8
        val (pixels, width, height) = graphics.renderSheet(palette, cols = columns) ?: return null
        return SourceSheet(name, pixels, width, height, palette, editable)
    }
}
