package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.MotherBrainSpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation

/** Keeps Mother Brain's split head, body, limb-DMA, and room-BG sources coordinated. */
class MotherBrainSpriteEditorState(
    private val loadEnemyTileData: (RomParser, Int) -> ByteArray?,
) {
    data class SourceSheet(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val palette: IntArray,
        val editable: Boolean,
    )

    private var spritemap: MotherBrainSpritemap? = null

    fun getSpritemap(romParser: RomParser): MotherBrainSpritemap? {
        spritemap?.let { return it }
        val renderer = MotherBrainSpritemap(romParser)
        val headTiles = loadEnemyTileData(romParser, MotherBrainSpritemap.HEAD_SPECIES_ID)
        val bodyTiles = loadEnemyTileData(romParser, MotherBrainSpritemap.BODY_SPECIES_ID)
        if (!renderer.load(headTiles, bodyTiles)) return null
        spritemap = renderer
        return renderer
    }

    fun renderComposition(
        romParser: RomParser,
        definition: MotherBrainSpritemap.CompositionDef,
        palette: MotherBrainSpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? =
        getSpritemap(romParser)?.renderComposition(definition, palette)

    fun renderAnimation(
        romParser: RomParser,
        definition: MotherBrainSpritemap.AnimationDef,
        palette: MotherBrainSpritemap.PaletteStageDef,
    ): SpriteAnimation? = getSpritemap(romParser)?.renderAnimation(definition, palette)

    fun renderHead(
        romParser: RomParser,
        definition: MotherBrainSpritemap.HeadDef,
        palette: MotherBrainSpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? = getSpritemap(romParser)?.renderHead(definition, palette)

    fun renderBody(
        romParser: RomParser,
        definition: MotherBrainSpritemap.BodyDef,
        palette: MotherBrainSpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? = getSpritemap(romParser)?.renderBody(definition, palette)

    fun renderNeck(
        romParser: RomParser,
        palette: MotherBrainSpritemap.PaletteStageDef,
    ): EnemySpritemap.AssembledSprite? = getSpritemap(romParser)?.renderNeckSegment(palette)

    fun loadHeadSourceSheet(romParser: RomParser): SourceSheet? {
        val renderer = getSpritemap(romParser) ?: return null
        val raw = renderer.getHeadTileData() ?: return null
        val palette = renderer.readPalette() ?: return null
        return renderSheet(romParser, raw, palette, editable = true)
    }

    fun loadBodySourceSheet(romParser: RomParser): SourceSheet? {
        val renderer = getSpritemap(romParser) ?: return null
        val raw = renderer.getBodySourceTileData() ?: return null
        val palette = renderer.readPalette() ?: return null
        return renderSheet(romParser, raw, palette, editable = false)
    }

    /** Preview representative phase 1/2 compositions from an unsaved head-sheet edit. */
    fun renderEditedHeadCompositions(
        romParser: RomParser,
        pixels: IntArray,
        width: Int,
        height: Int,
        paletteStage: MotherBrainSpritemap.PaletteStageDef,
    ): List<EnemySpritemap.AssembledSprite> {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return emptyList()
        val current = getSpritemap(romParser) ?: return emptyList()
        val rawHead = current.getHeadTileData() ?: return emptyList()
        val body = current.getBodyRawTileData() ?: return emptyList()
        val editPalette = current.readPalette() ?: return emptyList()
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(rawHead))
        graphics.importFromArgb(pixels, width, height, editPalette, cols = 16)
        val editedHead = graphics.getRawBlocks()?.firstOrNull() ?: return emptyList()
        val preview = MotherBrainSpritemap(romParser)
        if (!preview.load(editedHead, body)) return emptyList()
        return listOf(
            MotherBrainSpritemap.COMPOSITIONS.first(),
            MotherBrainSpritemap.COMPOSITIONS.first { it.key == "phase-2" },
        ).mapNotNull { preview.renderComposition(it, paletteStage) }
    }

    private fun renderSheet(
        romParser: RomParser,
        raw: ByteArray,
        palette: IntArray,
        editable: Boolean,
    ): SourceSheet? {
        val graphics = EnemySpriteGraphics(romParser)
        graphics.loadFromRaw(listOf(raw))
        val (pixels, width, height) = graphics.renderSheet(palette, cols = 16) ?: return null
        return SourceSheet(pixels, width, height, palette, editable)
    }

    fun invalidate() {
        spritemap = null
    }
}
