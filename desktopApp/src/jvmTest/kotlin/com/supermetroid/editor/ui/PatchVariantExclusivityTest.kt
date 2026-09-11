package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmPatch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatchVariantExclusivityTest {
    @Test
    fun `enabling one patch variant disables its exclusive sibling`() {
        val state = EditorState()
        val directional = SmPatch(
            id = "directional",
            name = "Directional",
            exclusiveGroup = "spider_ball_activation",
        )
        val holdAimDown = SmPatch(
            id = "hold_aim_down",
            name = "Hold Aim Down",
            exclusiveGroup = "spider_ball_activation",
        )
        val unrelated = SmPatch(id = "unrelated", name = "Unrelated", enabled = true)
        state.project.patches.addAll(listOf(directional, holdAimDown, unrelated))

        state.togglePatch(directional.id)
        assertTrue(directional.enabled)
        assertFalse(holdAimDown.enabled)

        state.togglePatch(holdAimDown.id)
        assertFalse(directional.enabled)
        assertTrue(holdAimDown.enabled)
        assertTrue(unrelated.enabled)

        state.updatePatch(directional.id, enabled = true)
        assertTrue(directional.enabled)
        assertFalse(holdAimDown.enabled)
        assertTrue(unrelated.enabled)
    }

    @Test
    fun `disabling a variant does not enable or disable its siblings`() {
        val state = EditorState()
        val directional = SmPatch(
            id = "directional",
            name = "Directional",
            enabled = true,
            exclusiveGroup = "spider_ball_activation",
        )
        val holdAimDown = SmPatch(
            id = "hold_aim_down",
            name = "Hold Aim Down",
            exclusiveGroup = "spider_ball_activation",
        )
        state.project.patches.addAll(listOf(directional, holdAimDown))

        state.togglePatch(directional.id)

        assertFalse(directional.enabled)
        assertFalse(holdAimDown.enabled)
        assertEquals(2, state.project.patches.size)
    }
}
