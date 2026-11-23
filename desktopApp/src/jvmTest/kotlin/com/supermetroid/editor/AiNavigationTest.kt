package com.supermetroid.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class AiNavigationTest {
    @Test
    fun `AI tab is conditional and follows ASM without reordering editor tabs`() {
        val regular = mainTabLabels(aiConfigured = false)
        val configured = mainTabLabels(aiConfigured = true)

        assertEquals(listOf("Rooms", "Items", "Tiles", "Patches", "Sound", "Sprites", "Map", "Text", "Enemy", "Boss", "ASM"), regular)
        assertEquals("AI", configured.last())
        assertEquals(regular, configured.dropLast(1))
    }

    @Test
    fun `AI navigation launches floating helper without changing editor tab`() {
        val activation = mainTabActivation(currentTab = 4, clickedTab = TAB_AI)

        assertEquals(4, activation.selectedTab)
        assertEquals(true, activation.openFloatingAi)
    }
}
