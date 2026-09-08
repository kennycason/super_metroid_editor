package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class RetroArchBackendTest {

    @Test
    fun `default path is platform-appropriate`() {
        val path = RetroArchBackend.defaultRetroArchPath()
        // Should return a non-null path on any supported platform
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("mac") || os.contains("win") || os.contains("linux")) {
            assertNotNull(path)
        }
    }

    @Test
    fun `backend starts disconnected`() {
        val backend = RetroArchBackend()
        assertEquals("retroarch", backend.name)
        assertFalse(backend.isConnected)
    }

    @Test
    fun `registry includes retroarch backend`() {
        val backends = EmulatorRegistry.availableBackends()
        assert(backends.contains("retroarch")) { "Expected 'retroarch' in available backends: $backends" }
        assert(backends.contains("libretro")) { "Expected 'libretro' in available backends: $backends" }
        assert(backends.contains("lsnes-b25")) { "Expected 'lsnes-b25' in available backends: $backends" }
    }

    @Test
    fun `listStates returns empty for retroarch`() {
        val backend = RetroArchBackend()
        // listStates should work even when disconnected (returns empty)
        kotlinx.coroutines.runBlocking {
            val states = backend.listStates()
            assertEquals(0, states.size)
        }
    }
}
