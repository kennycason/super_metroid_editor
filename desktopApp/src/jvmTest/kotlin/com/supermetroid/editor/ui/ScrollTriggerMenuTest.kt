package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScrollTriggerMenuTest {

    @Test
    fun `vanilla Landing Site trigger remains restorable after current trigger is removed`() {
        val romParser = TestRomHelper.loadRomParser() ?: return
        val landingSite = romParser.readRoomHeader(0x91F8) ?: error("Expected Landing Site")
        val originalPlms = romParser.parsePlmSet(landingSite.plmSetPtr)
        val currentPlmsWithoutTrigger = listOf(
            RomParser.PlmEntry(0xB647, 30, 39, 0x8000),
            RomParser.PlmEntry(0xB647, 30, 38, 0x8000),
            RomParser.PlmEntry(0xB647, 30, 37, 0x8000),
        )

        val originalHere = originalScrollTriggersAt(originalPlms, 30, 40)
        val reusableCommands = reusableScrollCommandPtrs(originalPlms, currentPlmsWithoutTrigger)

        assertEquals(listOf(0xB703), originalHere.map { it.id })
        assertEquals(listOf(0x92B0), originalHere.map { it.param })
        assertTrue(0x92B0 in reusableCommands)
    }

    @Test
    fun `scroll value labels describe camera behavior`() {
        assertEquals("Red (blocked)", RomParser.scrollValueLabel(0))
        assertEquals("Blue (normal)", RomParser.scrollValueLabel(1))
        assertEquals("Green (lower rows)", RomParser.scrollValueLabel(2))
    }
}
