package com.supermetroid.editor.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PatchEditorTest {
    @Test
    fun `imported IPS patch is selected and enabled`() {
        val ips = byteArrayOf(
            'P'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 'C'.code.toByte(), 'H'.code.toByte(),
            0x00, 0x12, 0x34, 0x00, 0x02, 0xAB.toByte(), 0xCD.toByte(),
            'E'.code.toByte(), 'O'.code.toByte(), 'F'.code.toByte(),
        )
        val state = EditorState().also { it.testMode = true }

        val patch = importIpsPatch(state, "echolocation_beam.ips", ips)

        assertEquals("Echolocation beam", patch.name)
        assertEquals("Imported from echolocation_beam.ips", patch.description)
        assertTrue(patch.enabled)
        assertEquals(patch.id, state.selectedPatchId)
        assertSame(patch, state.project.patches.single())
        assertEquals(0x1234L, patch.writes.single().offset)
        assertEquals(listOf(0xAB, 0xCD), patch.writes.single().bytes)
    }
}
