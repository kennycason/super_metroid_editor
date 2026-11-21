package com.supermetroid.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class AiNavigationTest {
    @Test
    fun `AI tab is conditional without shifting or renaming editor tabs`() {
        val regular = mainTabLabels(aiConfigured = false)
        val configured = mainTabLabels(aiConfigured = true)

        assertEquals(listOf("Rooms", "Items", "Tiles", "Patches", "Sound", "Sprites", "Map", "Text", "Enemy", "Boss", "ASM"), regular)
        assertEquals("AI", configured.first())
        assertEquals(regular, configured.drop(1))
    }
}
