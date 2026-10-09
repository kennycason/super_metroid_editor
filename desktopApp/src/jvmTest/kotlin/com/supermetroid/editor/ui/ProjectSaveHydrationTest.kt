package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmPatch
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectSaveHydrationTest {
    @TempDir
    lateinit var tempDirectory: File

    @Test
    fun `save persists deterministic patch hydration before clearing dirty state`() {
        val rom = File(tempDirectory, "Hydration.smc").apply { writeBytes(ByteArray(0)) }
        val state = EditorState().also {
            it.testMode = true
            it.initForRom(rom.absolutePath)
        }
        val configuredPatch = SmPatch(
            id = "save-hydration-test",
            name = "Save hydration test",
            enabled = false,
            configType = "enemy_stats",
            configData = mutableMapOf("fixture" to 1),
        )
        state.project.patches += configuredPatch
        state.markDirty()

        assertTrue(state.saveProject())
        assertTrue(configuredPatch.enabled)
        assertFalse(state.dirty)

        // A later explicit Build, emulator launch, preview, or export runs the
        // same refresh again; it must be a no-op rather than restoring the
        // top-level Save* indicator.
        state.seedDefaultPatches(forceRefreshBundled = true)
        assertFalse(state.dirty)
        assertTrue(File(tempDirectory, "Hydration.smedit").isFile)
    }
}
