package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BossSpriteEditorStateSafetyTest {

    @Test
    fun `Phantoon reset removes every quarantined legacy block only`() {
        val gfx = TilesetGfxData().also {
            it.spriteTileBlocks["phantoon:0"] = "first"
            it.spriteTileBlocks["phantoon:unexpected"] = "second"
            it.spriteTileBlocks["enemy:DCFF"] = "ordinary"
        }
        val state = PhantoonSpriteEditorState(
            customGfx = { gfx },
            applyCustomGfx = { _, _ -> },
            onDirty = {},
        )

        assertTrue(state.hasCustomTileSheet())
        state.resetTileSheet()

        assertFalse(state.hasCustomTileSheet())
        assertTrue(gfx.spriteTileBlocks.containsKey("enemy:DCFF"))
    }

    @Test
    fun `Kraid reset removes every quarantined pixel block only`() {
        val gfx = TilesetGfxData().also {
            it.spriteTileBlocks["kraid:0"] = "first"
            it.spriteTileBlocks["kraid:unexpected"] = "second"
            it.spriteTileBlocks["enemy:DCFF"] = "ordinary"
        }
        val state = KraidSpriteEditorState(
            customGfx = { gfx },
            applyCustomGfx = { _, _ -> },
            onDirty = {},
        )

        assertTrue(state.hasCustomTileSheet())
        state.resetTileSheet()

        assertFalse(state.hasCustomTileSheet())
        assertTrue(gfx.spriteTileBlocks.containsKey("enemy:DCFF"))
    }
}
