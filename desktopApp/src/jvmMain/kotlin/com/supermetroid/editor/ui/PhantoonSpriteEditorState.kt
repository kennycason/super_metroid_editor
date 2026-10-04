package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.BossSpriteExportSafety
import com.supermetroid.editor.rom.PhantoonSpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.TileGraphics

/**
 * Manages Phantoon's assembled BG2 component path and reset of quarantined legacy sheet data.
 *
 * [applyCustomGfx] is a callback into EditorState so that project-level GFX overrides are
 * applied to the tilemap graphics without duplicating that logic here.
 */
class PhantoonSpriteEditorState(
    private val customGfx: () -> TilesetGfxData,
    private val applyCustomGfx: (TileGraphics, Int) -> Unit,
    private val onDirty: () -> Unit,
) {
    private var spritemap: PhantoonSpritemap? = null

    fun getSpritemap(romParser: RomParser): PhantoonSpritemap? {
        spritemap?.let { return it }
        val sm = PhantoonSpritemap(romParser)
        if (!sm.load()) return null
        applyCustomGfx(sm.getTileGraphics(), sm.getTilesetId())
        spritemap = sm
        return sm
    }

    fun renderComponent(
        romParser: RomParser,
        def: PhantoonSpritemap.ComponentDef,
        paletteStage: PhantoonSpritemap.PaletteStageDef = PhantoonSpritemap.PALETTE_STAGES.last(),
    ): PhantoonSpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComponent(def, paletteStage)

    fun renderFullBody(
        romParser: RomParser,
        paletteStage: PhantoonSpritemap.PaletteStageDef = PhantoonSpritemap.PALETTE_STAGES.last(),
        eyeball: PhantoonSpritemap.ComponentDef? = null,
    ): PhantoonSpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderFullBody(paletteStage, eyeball)

    fun renderAnimation(
        romParser: RomParser,
        def: PhantoonSpritemap.AnimationDef,
        paletteStage: PhantoonSpritemap.PaletteStageDef = PhantoonSpritemap.PALETTE_STAGES.last(),
    ): SpriteAnimation? = getSpritemap(romParser)?.renderAnimation(def, paletteStage)

    fun readPalette(
        romParser: RomParser,
        stage: PhantoonSpritemap.PaletteStageDef,
    ): IntArray? = getSpritemap(romParser)?.readPalette(stage)

    fun applyComponentEdits(
        romParser: RomParser,
        sprite: PhantoonSpritemap.AssembledSprite,
        editedPixels: IntArray,
    ) {
        val sm = getSpritemap(romParser) ?: return
        val tg = sm.getTileGraphics()
        sm.applyEdits(sprite, editedPixels, tg)
        val rawVarGfx = tg.getRawVarGfx() ?: return
        customGfx().varGfx[sm.getTilesetId().toString()] =
            java.util.Base64.getEncoder().encodeToString(rawVarGfx)
        onDirty()
        spritemap = null
    }

    fun getPalette(romParser: RomParser): IntArray? = getSpritemap(romParser)?.getPalette()

    fun hasCustomComponents(): Boolean {
        val sm = spritemap ?: return false
        return customGfx().varGfx.containsKey(sm.getTilesetId().toString())
    }

    fun hasCustomTileSheet(): Boolean =
        customGfx().spriteTileBlocks.keys.any {
            it.startsWith(BossSpriteExportSafety.PHANTOON_KEY_PREFIX)
        }

    fun resetTileSheet() {
        customGfx().spriteTileBlocks.keys
            .filter { it.startsWith(BossSpriteExportSafety.PHANTOON_KEY_PREFIX) }
            .forEach { customGfx().spriteTileBlocks.remove(it) }
        spritemap = null
        onDirty()
    }

    /** Invalidate cached ROM-derived data when a new ROM is loaded. */
    fun invalidate() {
        spritemap = null
    }
}
