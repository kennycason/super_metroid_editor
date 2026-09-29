package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ScrollCommand
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScrollRuntimeDiagnosticsTest {

    @Test
    fun `vanilla Parlor exposes all competing camera outcomes without setup errors`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val room = parser.readRoomHeader(0x92FD)!!
        val plms = parser.parsePlmSet(room.plmSetPtr)
        val diagnostics = buildScrollRuntimeDiagnostics(plms, room.width, room.height) { trigger ->
            RomParser.decodeScrollCommands(parser, trigger.param, room.width)
                .map { (screenIndex, _, scrollValue) -> ScrollCommand(screenIndex, scrollValue) }
        }

        assertEquals(10, diagnostics.triggerCount)
        assertEquals(5, diagnostics.affectedScreenCount)
        assertEquals(5, diagnostics.competingScreenCount)
        assertEquals(0, diagnostics.issueCount)
    }

    @Test
    fun `summarizes runtime overrides and competing outcomes`() {
        val first = RomParser.PlmEntry(0xB703, 4, 4, 0x9000)
        val second = RomParser.PlmEntry(0xB703, 8, 4, 0x9010)

        val diagnostics = buildScrollRuntimeDiagnostics(
            plms = listOf(first, second),
            roomWidth = 3,
            roomHeight = 2,
        ) { trigger ->
            when (trigger.param) {
                0x9000 -> listOf(ScrollCommand(1, 0), ScrollCommand(4, 2))
                else -> listOf(ScrollCommand(1, 1))
            }
        }

        assertEquals(2, diagnostics.triggerCount)
        assertEquals(setOf(0, 1), diagnostics.valuesByScreen[1])
        assertEquals(setOf(2), diagnostics.valuesByScreen[4])
        assertEquals(1, diagnostics.competingScreenCount)
        assertEquals(0, diagnostics.issueCount)
    }

    @Test
    fun `reports invalid command entries without presenting them as screen overrides`() {
        val trigger = RomParser.PlmEntry(0xB703, 4, 4, 0x9000)
        val diagnostics = buildScrollRuntimeDiagnostics(
            plms = listOf(trigger),
            roomWidth = 2,
            roomHeight = 2,
        ) {
            listOf(ScrollCommand(4, 1), ScrollCommand(2, 9))
        }

        assertEquals(1, diagnostics.invalidTargetCount)
        assertEquals(1, diagnostics.invalidValueCount)
        assertEquals(2, diagnostics.issueCount)
        assertEquals(emptyMap<Int, Set<Int>>(), diagnostics.valuesByScreen)
    }

    @Test
    fun `extension chain must lead back to a trigger`() {
        val trigger = RomParser.PlmEntry(0xB703, 10, 8, 0x9000)
        val connectedA = RomParser.PlmEntry(0xB63B, 11, 8, 0x8000)
        val connectedB = RomParser.PlmEntry(0xB63B, 12, 8, 0x8000)
        val orphan = RomParser.PlmEntry(0xB647, 20, 5, 0x8000)

        val diagnostics = buildScrollRuntimeDiagnostics(
            plms = listOf(trigger, connectedA, connectedB, orphan),
            roomWidth = 2,
            roomHeight = 2,
        ) { listOf(ScrollCommand(0, 1)) }

        assertEquals(3, diagnostics.extensionCount)
        assertEquals(1, diagnostics.orphanExtensionCount)
    }

    @Test
    fun `extension placement is enabled only when it continues a valid direction`() {
        val trigger = RomParser.PlmEntry(0xB703, 10, 8, 0x9000)
        val firstRight = RomParser.PlmEntry(0xB63B, 11, 8, 0x8000)
        val plms = listOf(trigger, firstRight)

        assertEquals(true, canPlaceScrollExtension(0xB63B, 12, 8, plms))
        assertEquals(false, canPlaceScrollExtension(0xB63B, 12, 9, plms))
        assertEquals(false, canPlaceScrollExtension(0xB63F, 12, 8, plms))
    }
}
