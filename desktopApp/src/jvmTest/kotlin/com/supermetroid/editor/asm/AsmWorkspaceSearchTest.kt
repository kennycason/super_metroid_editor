package com.supermetroid.editor.asm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsmWorkspaceSearchTest {
    @Test
    fun `sidebar search stays shared across every browser switch including explicit clearing`() {
        val state = AsmWorkspaceState()
        val modes = listOf(
            AsmBrowserMode.SOURCE to state::showSourceBrowser,
            AsmBrowserMode.ASSETS to state::showAssetBrowser,
            AsmBrowserMode.LIBRARY to state::showLibraryBrowser,
            AsmBrowserMode.ROM to state::showRomBrowser,
        )

        for ((origin, showOrigin) in modes) {
            showOrigin()
            for (query in listOf("  Flyway  ", "\$8F:9879", "")) {
                state.query = query
                for ((target, showTarget) in modes) {
                    showTarget()
                    assertEquals(target, state.browserMode)
                    assertEquals(query, state.query, "Search entered in $origin must survive switching to $target")
                }
                showOrigin()
                assertEquals(query, state.query, "Returning to $origin must not restore an older search")
            }
        }
    }

    @Test
    fun `library and ROM navigation history keep the latest shared search rather than an old query`() {
        val state = AsmWorkspaceState()
        state.query = "Flyway"
        state.openLibraryGuide("widths-and-suffixes")
        state.openLibraryInstruction("lda.w")
        for (view in AsmRomPreviewView.entries) {
            state.selectRomPreviewView(view)
            assertEquals("Flyway", state.query)
        }
        state.showInstruction("sta.l")
        assertEquals("Flyway", state.query)

        // Search is global UI state, not a property of a page in Back/Forward history.
        state.query = "  new search  "
        var backCount = 0
        while (state.canGoBack) {
            state.goBack()
            assertEquals("  new search  ", state.query)
            assertTrue(++backCount <= 10, "Back must not append new history entries")
        }
        assertEquals(6, backCount)
        var forwardCount = 0
        while (state.canGoForward) {
            state.goForward()
            assertEquals("  new search  ", state.query)
            assertTrue(++forwardCount <= backCount, "Forward must not append new history entries")
        }
        assertEquals(backCount, forwardCount)
        assertEquals("STA.L", state.selectedInstructionToken)
        assertFalse(state.canGoForward)
    }
}
