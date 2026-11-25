package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.DraygonSpritemap
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TileGraphics

/** Keeps Draygon's room-owned BG2 tiles and enemy-owned OBJ tiles in one exact preview path. */
class DraygonSpriteEditorState(
    private val customGfx: () -> TilesetGfxData,
    private val loadEnemyTileData: (RomParser, Int) -> ByteArray?,
    private val applyCustomGfx: (TileGraphics, Int) -> Unit,
) {
    data class EditableObjSheet(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val palette: IntArray,
    )

    private var spritemap: DraygonSpritemap? = null

    fun getSpritemap(romParser: RomParser): DraygonSpritemap? {
        spritemap?.let { return it }
        val renderer = DraygonSpritemap(romParser)
        val enemyTiles = loadEnemyTileData(romParser, DraygonSpritemap.BODY_SPECIES_ID)
        if (!renderer.load(enemyTiles)) return null
        applyCustomGfx(renderer.getTileGraphics(), DraygonSpritemap.TILESET_ID)
        renderer.applyPaletteRegionOverrides(
            customGfx().spritePalettes.mapNotNull { (regionId, encoded) ->
                runCatching {
                    regionId to SpritePalettes.bytesToColors(java.util.Base64.getDecoder().decode(encoded))
                }.getOrNull()
            }.toMap(),
        )
        spritemap = renderer
        return renderer
    }

    fun renderComposition(
        romParser: RomParser,
        definition: DraygonSpritemap.CompositionDef,
        palette: DraygonSpritemap.PaletteStageDef,
        paletteMode: DraygonSpritemap.PreviewPaletteMode = DraygonSpritemap.PreviewPaletteMode.RUNTIME,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComposition(definition, palette, paletteMode)

    fun renderComponent(
        romParser: RomParser,
        definition: DraygonSpritemap.ComponentDef,
        side: DraygonSpritemap.Side,
        palette: DraygonSpritemap.PaletteStageDef,
        paletteMode: DraygonSpritemap.PreviewPaletteMode = DraygonSpritemap.PreviewPaletteMode.RUNTIME,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComponent(definition, side, palette, paletteMode)

    fun renderAnimation(
        romParser: RomParser,
        definition: DraygonSpritemap.AnimationDef,
        palette: DraygonSpritemap.PaletteStageDef,
        paletteMode: DraygonSpritemap.PreviewPaletteMode = DraygonSpritemap.PreviewPaletteMode.RUNTIME,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderAnimation(definition, palette, paletteMode)

    fun loadObjSheet(romParser: RomParser): EditableObjSheet? {
        val renderer = getSpritemap(romParser) ?: return null
        val raw = renderer.getRawEnemyTileData() ?: return null
        // Source pixels must use a stable, non-flashing palette. The white-flash
        // runtime palette intentionally collapses several indexes to one color and
        // therefore cannot safely round-trip an indexed 4bpp sheet.
        val palette = renderer.readObjPalette(DraygonSpritemap.PALETTE_STAGES.first()) ?: return null
        val gfx = EnemySpriteGraphics(romParser)
        gfx.loadFromRaw(listOf(raw))
        val (pixels, width, height) = gfx.renderSheet(palette, cols = 16) ?: return null
        return EditableObjSheet(pixels, width, height, palette)
    }

    /** Render every named composition from an in-progress edit without mutating the project. */
    fun renderEditedObjCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        paletteStage: DraygonSpritemap.PaletteStageDef,
        paletteMode: DraygonSpritemap.PreviewPaletteMode = DraygonSpritemap.PreviewPaletteMode.RUNTIME,
    ): List<EnemySpritemap.AssembledSprite> {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return emptyList()
        val current = getSpritemap(romParser) ?: return emptyList()
        val raw = current.getRawEnemyTileData() ?: return emptyList()
        val editPalette = current.readObjPalette(DraygonSpritemap.PALETTE_STAGES.first()) ?: return emptyList()
        val gfx = EnemySpriteGraphics(romParser)
        gfx.loadFromRaw(listOf(raw))
        gfx.importFromArgb(pixels, width, height, editPalette, cols = 16)
        val editedRaw = gfx.getRawBlocks()?.firstOrNull() ?: return emptyList()

        val preview = DraygonSpritemap(romParser)
        if (!preview.load(editedRaw)) return emptyList()
        applyCustomGfx(preview.getTileGraphics(), DraygonSpritemap.TILESET_ID)
        preview.applyPaletteRegionOverrides(
            customGfx().spritePalettes.mapNotNull { (regionId, encoded) ->
                runCatching {
                    regionId to SpritePalettes.bytesToColors(java.util.Base64.getDecoder().decode(encoded))
                }.getOrNull()
            }.toMap(),
        )
        return DraygonSpritemap.COMPOSITIONS.mapNotNull { definition ->
            preview.renderComposition(definition, paletteStage, paletteMode)
        }
    }

    fun getPalette(
        romParser: RomParser,
        paletteStage: DraygonSpritemap.PaletteStageDef,
    ): IntArray? = getSpritemap(romParser)?.readObjPalette(paletteStage)

    fun invalidate() {
        spritemap = null
    }
}
