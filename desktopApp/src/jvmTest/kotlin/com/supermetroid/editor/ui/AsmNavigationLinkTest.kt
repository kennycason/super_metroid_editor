package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame
import com.supermetroid.editor.data.ProjectRomBuildMode
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver

class AsmNavigationLinkTest {
    @Test fun `all editor shortcut shapes require enabled project ASM and source build mode`() {
        val editor = EditorState().apply { testMode = true }
        var calls = 0
        val address: (Int) -> Unit = { calls++ }
        val placement: (com.supermetroid.editor.rom.RomParser.PlmEntry) -> Unit = { calls++ }
        val initialScrolls: () -> Unit = { calls++ }
        val effects: (String, Int?) -> Unit = { _, _ -> calls++ }
        for (enabled in listOf(false, true)) for (mode in ProjectRomBuildMode.entries) {
            val project = editor.project
            project.asmWorkspace.enabled = enabled
            project.asmWorkspace.buildMode = mode
            val allowed = enabled && mode == ProjectRomBuildMode.ASM_SOURCE
            assertEquals(allowed, projectAsmNavigationEnabled(project))
            assertEquals(allowed, editor.asmNavigationEnabled)
            if (allowed) {
                assertSame(address, editor.projectAsmNavigation(address))
                assertSame(placement, editor.projectAsmNavigation(placement))
                assertSame(initialScrolls, editor.projectAsmNavigation(initialScrolls))
                assertSame(effects, editor.projectAsmNavigation(effects))
            } else {
                assertNull(editor.projectAsmNavigation(address))
                assertNull(editor.projectAsmNavigation(placement))
                assertNull(editor.projectAsmNavigation(initialScrolls))
                assertNull(editor.projectAsmNavigation(effects))
            }
            assertNull(editor.projectAsmNavigation<() -> Unit>(null))
        }
        assertEquals(0, calls, "Rendering or checking link availability must not navigate, enable ASM or edit data")
    }

    @Test fun `mode toggles invalidate shortcuts even when the project is already dirty`() {
        val editor = EditorState().apply { testMode = true; markDirty() }
        // State created since the last global snapshot advance is treated as initialization,
        // so a same-snapshot write is not reported to apply observers. Make the boundary
        // explicit instead of depending on whichever Compose test happened to run first.
        Snapshot.notifyObjectsInitialized()
        val observer = SnapshotStateObserver { it() }
        var invalidations = 0
        val scope = Any()
        observer.start()
        try {
            fun observe() = observer.observeReads(scope, { _: Any -> invalidations++ }) { editor.asmNavigationEnabled }
            observe()
            assertFalse(editor.asmNavigationEnabled)
            editor.enableProjectAsmWorkspace("fixture-source")
            Snapshot.sendApplyNotifications()
            assertTrue(editor.asmNavigationEnabled)
            assertTrue(invalidations > 0, "Source links need an observable mode dependency, not only dirty false-to-true")
            observe()
            val count = invalidations
            editor.setProjectRomBuildMode(ProjectRomBuildMode.PATCHED_ROM)
            Snapshot.sendApplyNotifications()
            assertFalse(editor.asmNavigationEnabled, "An installed editable workspace alone must not expose mapped links")
            assertTrue(invalidations > count)
            assertTrue(editor.project.asmWorkspace.enabled, "Returning to ROM mode keeps the optional browser/workspace")
            assertTrue(editor.dirty)
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun `switching between projects does not retain another projects ASM shortcut availability`() {
        val editor = EditorState().apply { testMode = true }
        val shortcut: (Int) -> Unit = {}
        editor.enableProjectAsmWorkspace("fixture-source")
        assertSame(shortcut, editor.projectAsmNavigation(shortcut))
        editor.initForReadOnlyRom("plain.sfc")
        assertNull(editor.projectAsmNavigation(shortcut))
        assertFalse(editor.project.asmWorkspace.enabled)
    }

    @Test
    fun `semantic pointers resolve in their runtime bank`() {
        assertEquals(0x83A123, semanticAsmAddress(RomConstants.BANK_FX, 0xA123))
        assertEquals(0x84B703, semanticAsmAddress(RomConstants.BANK_PLM, 0xB703))
        assertEquals(0xA0DCFF, semanticAsmAddress(RomConstants.BANK_ENEMY_AI, 0xDCFF))
    }

    @Test
    fun `null and non LoROM pointers do not become navigation links`() {
        assertNull(semanticAsmAddress(RomConstants.BANK_FX, 0))
        assertNull(semanticAsmAddress(RomConstants.BANK_FX, 0x7FFF))
        assertNull(semanticAsmAddress(RomConstants.BANK_FX, 0xFFFF))
    }
}
