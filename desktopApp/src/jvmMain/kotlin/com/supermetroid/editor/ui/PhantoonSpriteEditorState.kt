package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.PhantoonSpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics
import io.github.oshai.kotlinlogging.KotlinLogging

private val phantoonSpriteLog = KotlinLogging.logger {}

/**
 * Manages Phantoon assembled-sprite (BG2 tilemap path) and tile-sheet state for the editor.
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
    private var sheetGfx: EnemySpriteGraphics? = null
    private var sheetPalette: IntArray? = null

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
    ): PhantoonSpritemap.AssembledSprite? = getSpritemap(romParser)?.renderComponent(def)

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

    fun loadTileSheet(romParser: RomParser): Triple<IntArray, Int, Int>? {
        val gfx = EnemySpriteGraphics(romParser)
        val customOverrides = mutableMapOf<Int, ByteArray>()
        for ((i, _) in EnemySpriteGraphics.PHANTOON_BLOCKS.withIndex()) {
            val b64 = customGfx().spriteTileBlocks["phantoon:$i"] ?: continue
            try { customOverrides[i] = java.util.Base64.getDecoder().decode(b64) } catch (_: Exception) {}
        }
        val loaded = if (customOverrides.isEmpty()) {
            gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS)
        } else {
            gfx.loadWithOverrides(EnemySpriteGraphics.PHANTOON_BLOCKS, customOverrides)
        }
        if (!loaded) return null
        val palette = EnemySpriteGraphics.PHANTOON_PALETTE
        sheetGfx = gfx
        sheetPalette = palette
        phantoonSpriteLog.debug { "[SPRITE] loadPhantoonTileSheet: loaded ${gfx.getTileCount()} tiles, palette=${palette.size} colors" }
        return gfx.renderSheet(palette)
    }

    fun getSheetPalette(): IntArray? = sheetPalette

    fun applyTileSheetEdits(pixels: IntArray, w: Int, h: Int) {
        val gfx = sheetGfx ?: run {
            phantoonSpriteLog.warn { "[SPRITE] ABORT: sheetGfx is null — was loadTileSheet() called first?" }
            return
        }
        val palette = sheetPalette ?: run {
            phantoonSpriteLog.warn { "[SPRITE] ABORT: sheetPalette is null" }
            return
        }
        gfx.importFromArgb(pixels, w, h, palette)
        val rawBlocks = gfx.getRawBlocks() ?: run {
            phantoonSpriteLog.warn { "[SPRITE] ABORT: getRawBlocks() returned null after importFromArgb" }
            return
        }
        for ((i, raw) in rawBlocks.withIndex()) {
            customGfx().spriteTileBlocks["phantoon:$i"] =
                java.util.Base64.getEncoder().encodeToString(raw)
        }
        onDirty()
    }

    fun hasCustomTileSheet(): Boolean =
        EnemySpriteGraphics.PHANTOON_BLOCKS.indices.any { i ->
            customGfx().spriteTileBlocks.containsKey("phantoon:$i")
        }

    fun resetTileSheet() {
        EnemySpriteGraphics.PHANTOON_BLOCKS.indices.forEach { i ->
            customGfx().spriteTileBlocks.remove("phantoon:$i")
        }
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
