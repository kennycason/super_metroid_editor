package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.KraidSpritemap
import com.supermetroid.editor.rom.RomParser

/**
 * Manages Kraid assembled-sprite and tile-sheet state for the editor.
 *
 * All reads and writes go through the [customGfx] accessor so that calls always
 * reflect the current project without holding a stale reference across project loads.
 */
class KraidSpriteEditorState(
    private val customGfx: () -> TilesetGfxData,
    private val onDirty: () -> Unit,
) {
    private var spritemap: KraidSpritemap? = null
    private var sheetGfx: EnemySpriteGraphics? = null
    private var sheetPalette: IntArray? = null

    fun getSpritemap(romParser: RomParser): KraidSpritemap? {
        spritemap?.let { return it }
        val sm = KraidSpritemap(romParser)
        val b64 = customGfx().spriteTileBlocks["kraid:0"]
        val loaded = if (b64 != null) {
            try {
                val custom = java.util.Base64.getDecoder().decode(b64)
                sm.loadWithCustomTiles(custom)
            } catch (_: Exception) { sm.load() }
        } else {
            sm.load()
        }
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

    fun applyComponentEdits(
        sprite: KraidSpritemap.AssembledSprite,
        editedPixels: IntArray,
    ) {
        val sm = spritemap ?: return
        sm.applyEdits(sprite, editedPixels)
        val tiles = sm.getTileData() ?: return
        customGfx().spriteTileBlocks["kraid:0"] =
            java.util.Base64.getEncoder().encodeToString(tiles)
        onDirty()
        spritemap = null
    }

    fun loadTileSheet(romParser: RomParser): Triple<IntArray, Int, Int>? {
        val gfx = EnemySpriteGraphics(romParser)
        val b64 = customGfx().spriteTileBlocks["kraid:0"]
        val loaded = if (b64 != null) {
            try {
                val custom = java.util.Base64.getDecoder().decode(b64)
                gfx.loadFromRaw(listOf(custom))
                true
            } catch (_: Exception) { false }
        } else {
            false
        }
        if (!loaded) {
            if (!gfx.load(EnemySpriteGraphics.KRAID_BLOCKS)) return null
        }
        val sm = getSpritemap(romParser) ?: return null
        val palette = sm.getPalette() ?: return null
        sheetGfx = gfx
        sheetPalette = palette
        return gfx.renderSheet(palette, cols = 16)
    }

    fun getSheetPalette(): IntArray? = sheetPalette

    fun applyTileSheetEdits(pixels: IntArray, w: Int, h: Int) {
        val gfx = sheetGfx ?: return
        val palette = sheetPalette ?: return
        gfx.importFromArgb(pixels, w, h, palette, cols = 16)
        val rawBlocks = gfx.getRawBlocks() ?: return
        for ((i, raw) in rawBlocks.withIndex()) {
            customGfx().spriteTileBlocks["kraid:$i"] =
                java.util.Base64.getEncoder().encodeToString(raw)
        }
        onDirty()
        spritemap = null
    }

    fun hasCustomTileSheet(): Boolean =
        customGfx().spriteTileBlocks.containsKey("kraid:0")

    fun resetTileSheet() {
        customGfx().spriteTileBlocks.remove("kraid:0")
        sheetGfx = null
        sheetPalette = null
        spritemap = null
        onDirty()
    }

    /** Invalidate cached ROM-derived data when a new ROM is loaded. */
    fun invalidate() {
        spritemap = null
        sheetGfx = null
        sheetPalette = null
    }
}
