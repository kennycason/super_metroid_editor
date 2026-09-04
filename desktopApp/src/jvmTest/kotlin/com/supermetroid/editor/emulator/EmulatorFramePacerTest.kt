package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmulatorFramePacerTest {

    @Test
    fun `headless live play does not catch up more than one frame`() {
        val max = EmulatorFramePacer.maxCatchUp(EmulatorPresentation.HeadlessChild)
        assertEquals(1, max)
        assertEquals(1, EmulatorFramePacer.repeat(3.7, max))
    }

    @Test
    fun `bundled emulator may catch up several frames`() {
        val max = EmulatorFramePacer.maxCatchUp(EmulatorPresentation.InProcess)
        assertEquals(4, max)
        assertEquals(3, EmulatorFramePacer.repeat(3.7, max))
    }

    @Test
    fun `headless always requests a framebuffer`() {
        assertTrue(EmulatorFramePacer.includeFrame(EmulatorPresentation.HeadlessChild, 1L))
        assertTrue(EmulatorFramePacer.includeFrame(EmulatorPresentation.HeadlessChild, 2L))
        assertFalse(EmulatorFramePacer.includeFrame(EmulatorPresentation.InProcess, 1L))
        assertTrue(EmulatorFramePacer.includeFrame(EmulatorPresentation.InProcess, 2L))
    }

    @Test
    fun `in-process 16x may step 16 frames per tick`() {
        assertEquals(16, EmulatorFramePacer.maxCatchUp(EmulatorPresentation.InProcess, speed = 16))
        assertEquals(16, EmulatorFramePacer.repeat(16.4, 16))
    }

    @Test
    fun `headless skips full WRAM dumps between HUD refreshes`() {
        assertTrue(EmulatorFramePacer.includeWram(EmulatorPresentation.HeadlessChild, 0L))
        assertFalse(EmulatorFramePacer.includeWram(EmulatorPresentation.HeadlessChild, 1L))
        assertTrue(EmulatorFramePacer.includeWram(EmulatorPresentation.HeadlessChild, 10L))
        assertTrue(EmulatorFramePacer.includeWram(EmulatorPresentation.InProcess, 1L))
    }
}
