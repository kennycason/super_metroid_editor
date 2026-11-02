package com.supermetroid.editor.rom

import com.sun.jna.Platform
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Release prerequisites: these checks never need a Super Metroid ROM or an audio device. */
class NativeSpcRuntimeTest {
    @Test fun `native SPC library is packaged for this JVM and loads through JNA`() {
        val resource = "/${Platform.RESOURCE_PREFIX}/${System.mapLibraryName("spc")}"
        assertNotNull(javaClass.getResource(resource), "Missing packaged native library: $resource")
        assertTrue(NativeSpcEmulator.isAvailable(),
            "Packaged SPC runtime cannot load on ${Platform.RESOURCE_PREFIX}; check native compiler/runtime dependencies")
    }

    @Test fun `native SPC renders and saves a synthetic idle program without a ROM fixture`() {
        assertTrue(NativeSpcEmulator.isAvailable(), "Native SPC runtime is required")
        val ram = ByteArray(65536).apply {
            this[0x1500] = 0x2F // BRA -2: a complete, inert SPC700 program.
            this[0x1501] = 0xFE.toByte()
            this[0x6000] = 0x5A
        }
        NativeSpcEmulator().use { emulator ->
            emulator.loadFromRam(ram, emptyList(), 0, playbackWarmupSeconds = 0)
            val pcm = emulator.renderMono(1)
            assertEquals(NativeSpcEmulator.SAMPLE_RATE, pcm.size)
            assertTrue(pcm.all { it == 0.toShort() }, "An idle program must remain silent")
            val saved = NativeSpcEmulator.readSpcRam(emulator.saveSpcSnapshot("CI runtime smoke"))
            assertEquals(0x2F, saved[0x1500].toInt() and 255)
            assertEquals(0xFE, saved[0x1501].toInt() and 255)
            assertEquals(0x5A, saved[0x6000].toInt() and 255)
        }
    }
}
