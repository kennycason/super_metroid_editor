package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.DraygonSpritemap
import com.supermetroid.editor.rom.EnemySpriteGraphics
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
}
