package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.DraygonSpritemap
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DraygonSpriteEditorStateTest {

    @Test
    fun `editable OBJ sheet live-renders every composition without changing source bytes`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val state = DraygonSpriteEditorState(
            customGfx = { TilesetGfxData() },
            loadEnemyTileData = { romParser, speciesId ->
                EnemySpriteGraphics.loadEnemyTileData(romParser, speciesId)
            },
            applyCustomGfx = { _, _ -> },
        )
        val sheet = assertNotNull(state.loadObjSheet(parser))
        assertEquals(128, sheet.width)
        assertEquals(128, sheet.height)

        val previews = state.renderEditedObjCompositions(
            parser,
            sheet.pixels,
            sheet.width,
            sheet.height,
            DraygonSpritemap.WHITE_FLASH,
        )
        assertEquals(DraygonSpritemap.COMPOSITIONS.size, previews.size)

        val direct = assertNotNull(
            state.renderComposition(
                parser,
                DraygonSpritemap.COMPOSITIONS.first(),
                DraygonSpritemap.WHITE_FLASH,
            ),
        )
        assertTrue(direct.pixels.contentEquals(previews.first().pixels))
    }

    @Test
    fun `project Draygon palette overrides feed the OBJ editor and assembled previews`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val custom = TilesetGfxData()
        val red = 0x001F
        val colors = IntArray(96) { red }
        custom.spritePalettes["boss_draygon"] = java.util.Base64.getEncoder()
            .encodeToString(SpritePalettes.colorsToBytes(colors))
        val state = DraygonSpriteEditorState(
            customGfx = { custom },
            loadEnemyTileData = { romParser, speciesId ->
                EnemySpriteGraphics.loadEnemyTileData(romParser, speciesId)
            },
            applyCustomGfx = { _, _ -> },
        )

        val sheet = assertNotNull(state.loadObjSheet(parser))
        assertEquals(EnemySpriteGraphics.snesColorToArgb(red), sheet.palette[1])
        val rendered = assertNotNull(
            state.renderComposition(
                parser,
                DraygonSpritemap.COMPOSITIONS.first(),
                DraygonSpritemap.PALETTE_STAGES.first(),
                DraygonSpritemap.PreviewPaletteMode.RUNTIME,
            ),
        )
        assertTrue(rendered.pixels.contains(EnemySpriteGraphics.snesColorToArgb(red)))
    }
}
