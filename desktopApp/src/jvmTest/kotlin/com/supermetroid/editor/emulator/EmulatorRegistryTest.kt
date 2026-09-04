package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EmulatorRegistryTest {

    @Test
    fun `registry returns libretro backend`() {
        val backend = EmulatorRegistry.create("libretro")
        assertNotNull(backend)
        assertEquals("libretro", backend.name)
        assertTrue(backend is LibretroBackend)
        backend.close()
    }

    @Test
    fun `registry lists available backends`() {
        val backends = EmulatorRegistry.availableBackends()
        assertTrue(backends.contains("libretro"))
        assertTrue(backends.contains("lsnes-b25"))
    }

    @Test
    fun `lsnes descriptor is optional extra headless child`() {
        val descriptor = EmulatorRegistry.descriptor("lsnes-b25")
        assertEquals("lsnes-b25", descriptor.id)
        assertEquals("lsnes b25 extra", descriptor.displayName)
        assertEquals(EmulatorInstallKind.OptionalExtra, descriptor.installKind)
        assertEquals(EmulatorPresentation.HeadlessChild, descriptor.presentation)
        assertTrue(EmulatorRegistry.descriptors().any { it.id == "lsnes-b25" })
    }

    @Test
    fun `unknown backend throws`() {
        assertThrows<IllegalArgumentException> {
            EmulatorRegistry.create("nonexistent")
        }
    }
}
