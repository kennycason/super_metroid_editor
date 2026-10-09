package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectRomBuildMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsmModeContractTest {
    @Test
    fun `enabling project ASM selects the ASM source build`() {
        val state = EditorState()
        assertEquals(ProjectRomBuildMode.PATCHED_ROM, state.project.asmWorkspace.buildMode)

        state.enableProjectAsmWorkspace("pinned-revision")

        assertTrue(state.project.asmWorkspace.enabled)
        assertEquals("pinned-revision", state.project.asmWorkspace.sourceRevision)
        assertEquals(ProjectRomBuildMode.ASM_SOURCE, state.project.asmWorkspace.buildMode)
    }
}
