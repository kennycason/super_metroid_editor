package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.CrocomireSpritemap
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.TileGraphics

/** Keeps Crocomire's room BG2, enemy OBJ, melting, and skeleton owners in one preview path. */
class CrocomireSpriteEditorState(
    private val loadEnemyTileData: (RomParser, Int) -> ByteArray?,
    private val loadEnemyPalette: (RomParser, Int) -> IntArray?,
    private val applyCustomGfx: (TileGraphics, Int) -> Unit,
) {
    data class SourceSheet(
        val definition: CrocomireSpritemap.PixelSourceDef,
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val palette: IntArray,
    )

    private var spritemap: CrocomireSpritemap? = null

    fun getSpritemap(romParser: RomParser): CrocomireSpritemap? {
        spritemap?.let { return it }
        val renderer = CrocomireSpritemap(romParser)
        val enemyTiles = loadEnemyTileData(romParser, CrocomireSpritemap.SPECIES_ID)
        if (!renderer.load(enemyTiles)) return null
        applyCustomGfx(renderer.getTileGraphics(), CrocomireSpritemap.TILESET_ID)
        spritemap = renderer
        return renderer
    }

    fun renderComposition(
        romParser: RomParser,
        definition: CrocomireSpritemap.CompositionDef,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComposition(definition, palette(romParser))

    fun renderComponent(
        romParser: RomParser,
        definition: CrocomireSpritemap.ComponentDef,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComponent(definition, palette(romParser))

    fun renderAnimation(
        romParser: RomParser,
        definition: CrocomireSpritemap.InstructionListDef,
    ): SpriteAnimation? =
        getSpritemap(romParser)?.renderAnimation(definition, palette(romParser))

    fun loadSourceSheet(
        romParser: RomParser,
        definition: CrocomireSpritemap.PixelSourceDef,
    ): SourceSheet? {
        val renderer = getSpritemap(romParser) ?: return null
        val bytes = renderer.readPixelSource(definition) ?: return null
        val colors = palette(romParser) ?: renderer.readPalette() ?: return null
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(bytes))
        val columns = if (bytes.size >= 0x1000) 16 else 8
        val (pixels, width, height) = graphics.renderSheet(colors, columns) ?: return null
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
        val raw = current.getRawEnemyTileData() ?: return emptyList()
        val colors = palette(romParser) ?: current.readPalette() ?: return emptyList()
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(raw))
        graphics.importFromArgb(pixels, width, height, colors, cols = 16)
        val edited = graphics.getRawBlocks()?.firstOrNull() ?: return emptyList()

        val preview = CrocomireSpritemap(romParser)
        if (!preview.load(edited)) return emptyList()
        applyCustomGfx(preview.getTileGraphics(), CrocomireSpritemap.TILESET_ID)
        return CrocomireSpritemap.COMPOSITIONS
            .filter { it.phase == CrocomireSpritemap.Phase.LIVING }
            .mapNotNull { preview.renderComposition(it, colors) }
    }

    fun getEditingPalette(romParser: RomParser): IntArray? =
        palette(romParser) ?: getSpritemap(romParser)?.readPalette()

    fun invalidate() {
        spritemap = null
    }

    private fun palette(romParser: RomParser): IntArray? =
        loadEnemyPalette(romParser, CrocomireSpritemap.SPECIES_ID)
}
