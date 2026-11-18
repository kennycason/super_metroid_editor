package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmRuntimeSourceLocation
import com.supermetroid.editor.asm.AsmRuntimeWatchTarget
import com.supermetroid.editor.asm.AsmWorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsmDebuggerUiTest {
    @Test
    fun `debugger gives watches most of wide layouts while retaining a usable cpu pane`() {
        assertEquals(0.28f, asmDebuggerCpuPaneWeight(1200f))
        assertEquals(0.34f, asmDebuggerCpuPaneWeight(1000f))
        assertEquals(0.40f, asmDebuggerCpuPaneWeight(800f))
    }

    @Test
    fun `source context actions expose exact fresh debugger operations`() {
        val invoked = mutableListOf<String>()
        val items = asmDebuggerContextMenuItems(
            address = 0x81A0AE,
            available = true,
            mappingFresh = true,
            hasBreakpoint = { false },
            onToggleBreakpoint = { invoked += "toggle:${it.toString(16)}" },
            onRunToAddress = { invoked += "run:${it.toString(16)}" },
            onOpenDebugger = { invoked += "open" },
        )

        assertEquals(listOf("Add Breakpoint", "Run to Cursor", "Open Debugger"), items.map { it.label })
        items.forEach { it.onClick() }
        assertEquals(listOf("toggle:81a0ae", "run:81a0ae", "open"), invoked)
        assertEquals(
            "Remove Breakpoint",
            asmDebuggerContextMenuItems(
                0x81A0AE, true, true, { true }, {}, {}, {},
            ).first().label,
        )
    }

    @Test
    fun `stale or unavailable source only offers the debugger explanation`() {
        for ((address, available, fresh) in listOf(
            Triple<Int?, Boolean, Boolean>(null, true, true),
            Triple(0x81A0AE, false, true),
            Triple(0x81A0AE, true, false),
        )) {
            val items = asmDebuggerContextMenuItems(address, available, fresh, { false }, {}, {}, {})
            assertEquals(listOf("Open Debugger"), items.map { it.label })
        }
    }

    @Test
    fun `fresh resolved source symbols share the canonical watch action`() {
        val target = AsmRuntimeWatchTarget("Energy", "Energy", 0x7E09C2)
        var selected: AsmRuntimeWatchTarget? = null
        val items = asmDebuggerContextMenuItems(
            address = null,
            available = true,
            mappingFresh = true,
            hasBreakpoint = { false },
            onToggleBreakpoint = {},
            onRunToAddress = {},
            onOpenDebugger = {},
            watchTarget = target,
            onAddWatch = { selected = it },
        )

        assertEquals(listOf("Add Energy to Watches…", "Open Debugger"), items.map { it.label })
        items.first().onClick()
        assertEquals(target, selected)
        assertEquals(
            listOf("Open Debugger"),
            asmDebuggerContextMenuItems(null, true, false, { false }, {}, {}, {}, target).map { it.label },
        )

        val longTarget = AsmRuntimeWatchTarget(
            "PauseMenu_MapScrollLeftArrowAnimationFrame",
            "PauseMenu_MapScrollLeftArrowAnimationFrame",
            0x7E0751,
        )
        assertEquals(
            listOf("Add PauseMenu_MapScrollLeft… to Watches…", "Open Debugger"),
            asmDebuggerContextMenuItems(
                null, true, true, { false }, {}, {}, {}, longTarget,
            ).map { it.label },
        )
    }

    @Test
    fun `breakpoint markers require an exact mapped line and visibly retain stale state`() {
        val addresses = mapOf(4 to 0x81A0A8, 8 to 0x81A0AE, 12 to 0x81A0B5)
        assertEquals(
            mapOf(8 to AsmBreakpointMarker.RESOLVED),
            asmBreakpointMarkers(addresses, setOf(0x81A0AE, 0xE08000), mappingFresh = true),
        )
        assertEquals(
            mapOf(8 to AsmBreakpointMarker.STALE),
            asmBreakpointMarkers(addresses, setOf(0x81A0AE), mappingFresh = false),
        )
    }

    @Test
    fun `breakpoint viewer sorts addresses and retains authored navigation evidence`() {
        val authored = AsmRuntimeSourceLocation(
            fileId = "bank_81.asm",
            lineIndex = 42,
            sourceLine = "JML SMEDIT_Helper",
            address = 0x81A0AE,
            symbols = listOf("NativeReturn"),
            generated = false,
        )
        val generated = AsmRuntimeSourceLocation(
            fileId = "__smedit_generated_managed_code.asm",
            lineIndex = 8,
            sourceLine = "RTL",
            address = 0xE08010,
            symbols = emptyList(),
            generated = true,
        )
        val rows = asmBreakpointRows(listOf(0xE08010, 0x81A0AE, 0x81A0AE, 0x908000)) {
            when (it) {
                authored.address -> authored
                generated.address -> generated
                else -> null
            }
        }

        assertEquals(listOf(0x81A0AE, 0x908000, 0xE08010), rows.map { it.address })
        assertEquals("NativeReturn", rows[0].title)
        assertEquals("bank_81.asm:43", rows[0].detail)
        assertEquals("Source mapping unavailable", rows[1].detail)
        assertEquals("RTL", rows[2].title)
        assertTrue(rows[2].detail.endsWith(" · generated"))
    }

    @Test
    fun `editor actions can request the debugger tab repeatedly`() {
        val state = AsmWorkspaceState()
        state.requestDebugger()
        state.requestDebugger()
        assertEquals(2L, state.debuggerRequestSerial)
    }
}
