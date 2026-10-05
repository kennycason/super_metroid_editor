package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.BossSpriteExportSafety
import com.supermetroid.editor.rom.KraidSpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TileGraphics

/**
 * Manages Kraid's source-backed BG2 previews, tileset edits, and legacy-data cleanup.
 *
 * All reads and writes go through the [customGfx] accessor so that calls always
 * reflect the current project without holding a stale reference across project loads.
 */
class KraidSpriteEditorState(
    private val customGfx: () -> TilesetGfxData,
    private val loadEnemyTileData: (RomParser, Int) -> ByteArray? = { parser, speciesId ->
        com.supermetroid.editor.rom.EnemySpriteGraphics.loadEnemyTileData(parser, speciesId)
    },
    private val applyCustomGfx: (TileGraphics, Int) -> Unit,
    private val onDirty: () -> Unit,
) {
    private var spritemap: KraidSpritemap? = null

    fun getSpritemap(romParser: RomParser): KraidSpritemap? {
        spritemap?.let { return it }
        val sm = KraidSpritemap(romParser)
        val loaded = sm.load()
        if (!loaded) return null
        val tileGraphics = sm.getTileGraphics() ?: return null
        applyCustomGfx(tileGraphics, sm.getTilesetId())
        spritemap = sm
        return sm
    }

    fun renderFullBody(
        romParser: RomParser,
        head: KraidSpritemap.HeadTilemapDef = KraidSpritemap.HEAD_TILEMAPS.first(),
        paletteStage: KraidSpritemap.PaletteStageDef =
            KraidSpritemap.PALETTE_STAGES.first { it.key == "health-8" },
    ): KraidSpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderCompleteBody(
            head,
            paletteStage,
            loadEnemyTileData(romParser, KraidSpritemap.OAM_SEQUENCES.first().speciesId),
        )

    fun renderFullBodyAnimation(
        romParser: RomParser,
        def: KraidSpritemap.HeadSequenceDef,
        paletteStage: KraidSpritemap.PaletteStageDef,
    ) = getSpritemap(romParser)?.renderFullBodyAnimation(
        def,
        paletteStage,
        loadEnemyTileData(romParser, KraidSpritemap.OAM_SEQUENCES.first().speciesId),
    )

    fun renderOamAnimation(
        romParser: RomParser,
        def: KraidSpritemap.OamSequenceDef,
        tileData: ByteArray,
        paletteStage: KraidSpritemap.PaletteStageDef,
    ) = getSpritemap(romParser)?.renderOamAnimation(def, tileData, paletteStage)

    fun renderHeadTilemap(
        romParser: RomParser,
        def: KraidSpritemap.HeadTilemapDef,
    ): KraidSpritemap.AssembledSprite? = getSpritemap(romParser)?.renderHeadTilemap(def)

    fun applyHeadEdits(
        romParser: RomParser,
        sprite: KraidSpritemap.AssembledSprite,
        editedPixels: IntArray,
    ) {
        val sm = getSpritemap(romParser) ?: return
        if (sm.applyEdits(sprite, editedPixels).isEmpty()) return
        val rawVarGfx = sm.getTileGraphics()?.getRawVarGfx() ?: return
        customGfx().varGfx[sm.getTilesetId().toString()] =
            java.util.Base64.getEncoder().encodeToString(rawVarGfx)
        onDirty()
        spritemap = null
    }

    fun getPalette(romParser: RomParser): IntArray? = getSpritemap(romParser)?.getPalette()

    fun hasCustomComponents(): Boolean =
        customGfx().varGfx.containsKey(KraidSpritemap.KRAID_TILESET_ID.toString())

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
