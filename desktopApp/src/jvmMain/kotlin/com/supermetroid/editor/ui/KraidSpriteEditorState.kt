package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.BossSpriteExportSafety
import com.supermetroid.editor.rom.KraidSpritemap
import com.supermetroid.editor.rom.RomParser

/**
 * Manages Kraid's assembled preview state and reset of quarantined legacy pixel data.
 *
 * All reads and writes go through the [customGfx] accessor so that calls always
 * reflect the current project without holding a stale reference across project loads.
 */
class KraidSpriteEditorState(
    private val customGfx: () -> TilesetGfxData,
    private val onDirty: () -> Unit,
) {
    private var spritemap: KraidSpritemap? = null

    fun getSpritemap(romParser: RomParser): KraidSpritemap? {
        spritemap?.let { return it }
        val sm = KraidSpritemap(romParser)
        val loaded = sm.load()
        if (!loaded) return null
        spritemap = sm
        return sm
    }

    fun renderFullBody(romParser: RomParser): KraidSpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderFullBody()

    fun renderBodyTilemap(
        romParser: RomParser,
        def: KraidSpritemap.BodyTilemapDef,
    ): KraidSpritemap.AssembledSprite? = getSpritemap(romParser)?.renderBodyTilemap(def)

    fun renderBigSprmap(
        romParser: RomParser,
        def: KraidSpritemap.ComponentDef,
    ): KraidSpritemap.AssembledSprite? = getSpritemap(romParser)?.renderBigSprmap(def)

    fun getPalette(romParser: RomParser): IntArray? = getSpritemap(romParser)?.getPalette()

    fun hasCustomTileSheet(): Boolean =
        customGfx().spriteTileBlocks.keys.any {
            it.startsWith(BossSpriteExportSafety.KRAID_KEY_PREFIX)
        }

    fun resetTileSheet() {
        customGfx().spriteTileBlocks.keys
            .filter { it.startsWith(BossSpriteExportSafety.KRAID_KEY_PREFIX) }
            .forEach { customGfx().spriteTileBlocks.remove(it) }
        spritemap = null
        onDirty()
    }

    /** Invalidate cached ROM-derived data when a new ROM is loaded. */
    fun invalidate() {
        spritemap = null
    }
}
