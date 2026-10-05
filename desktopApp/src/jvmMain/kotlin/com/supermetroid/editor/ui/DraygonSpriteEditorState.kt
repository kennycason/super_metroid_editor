package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.DraygonSpritemap
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.TileGraphics

/** Keeps Draygon's room-owned BG2 tiles and enemy-owned OBJ tiles in one exact preview path. */
class DraygonSpriteEditorState(
    private val loadEnemyTileData: (RomParser, Int) -> ByteArray?,
    private val applyCustomGfx: (TileGraphics, Int) -> Unit,
) {
    private var spritemap: DraygonSpritemap? = null

    fun getSpritemap(romParser: RomParser): DraygonSpritemap? {
        spritemap?.let { return it }
        val renderer = DraygonSpritemap(romParser)
        val enemyTiles = loadEnemyTileData(romParser, DraygonSpritemap.BODY_SPECIES_ID)
        if (!renderer.load(enemyTiles)) return null
        applyCustomGfx(renderer.getTileGraphics(), DraygonSpritemap.TILESET_ID)
        spritemap = renderer
        return renderer
    }

    fun renderComposition(
        romParser: RomParser,
        definition: DraygonSpritemap.CompositionDef,
        palette: DraygonSpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComposition(definition, palette)

    fun renderAnimation(
        romParser: RomParser,
        definition: DraygonSpritemap.AnimationDef,
        palette: DraygonSpritemap.PaletteStageDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderAnimation(definition, palette)

    fun invalidate() {
        spritemap = null
    }
}
