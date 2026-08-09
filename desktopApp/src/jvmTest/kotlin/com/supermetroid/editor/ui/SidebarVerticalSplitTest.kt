package com.supermetroid.editor.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarVerticalSplitTest {

    @Test
    fun `unset bottom height defaults to an even split around the divider`() {
        val split = computeSidebarSplitHeights(
            totalHeightDp = 810f,
            requestedBottomPaneDp = Float.NaN,
        )

        assertEquals(400f, split.topPaneDp, 0.001f)
        assertEquals(400f, split.bottomPaneDp, 0.001f)
    }

    @Test
    fun `explicit bottom height leaves the remaining space for the top pane`() {
        val split = computeSidebarSplitHeights(
            totalHeightDp = 810f,
            requestedBottomPaneDp = 500f,
        )

        assertEquals(300f, split.topPaneDp, 0.001f)
        assertEquals(500f, split.bottomPaneDp, 0.001f)
    }

    @Test
    fun `bottom height clamps to keep both panes reachable`() {
        val tooSmall = computeSidebarSplitHeights(
            totalHeightDp = 810f,
            requestedBottomPaneDp = 20f,
        )
        val tooLarge = computeSidebarSplitHeights(
            totalHeightDp = 810f,
            requestedBottomPaneDp = 760f,
        )

        assertEquals(680f, tooSmall.topPaneDp, 0.001f)
        assertEquals(120f, tooSmall.bottomPaneDp, 0.001f)
        assertEquals(120f, tooLarge.topPaneDp, 0.001f)
        assertEquals(680f, tooLarge.bottomPaneDp, 0.001f)
    }

    @Test
    fun `minimum pane height shrinks when the sidebar is too short`() {
        val split = computeSidebarSplitHeights(
            totalHeightDp = 210f,
            requestedBottomPaneDp = 400f,
        )

        assertEquals(100f, split.topPaneDp, 0.001f)
        assertEquals(100f, split.bottomPaneDp, 0.001f)
    }

    @Test
    fun `split state only updates for invalid or meaningful height changes`() {
        assertTrue(shouldUpdateSidebarSplitState(Float.NaN, 400f))
        assertTrue(shouldUpdateSidebarSplitState(500f, 400f))
        assertFalse(shouldUpdateSidebarSplitState(400.25f, 400f))
    }
}
