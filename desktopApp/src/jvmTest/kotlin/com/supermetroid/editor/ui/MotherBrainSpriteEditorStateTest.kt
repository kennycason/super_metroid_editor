package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.MotherBrainSpritemap
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotherBrainSpriteEditorStateTest {

    @Test
    fun `editable head sheet updates phase one and assembled phase two previews`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val state = MotherBrainSpriteEditorState { romParser, speciesId ->
            EnemySpriteGraphics.loadEnemyTileData(romParser, speciesId)
        }

        val head = assertNotNull(state.loadHeadSourceSheet(parser))
        assertTrue(head.editable)
        assertEquals(128, head.width)
        assertEquals(64, head.height)
        val body = assertNotNull(state.loadBodySourceSheet(parser))
        assertFalse(body.editable)

        val previews = state.renderEditedHeadCompositions(
            parser,
            head.pixels,
            head.width,
            head.height,
            MotherBrainSpritemap.PALETTE_STAGES.first(),
        )
        assertEquals(2, previews.size)
        assertTrue(previews.all { preview -> preview.pixels.any { (it ushr 24) != 0 } })
    }
}
