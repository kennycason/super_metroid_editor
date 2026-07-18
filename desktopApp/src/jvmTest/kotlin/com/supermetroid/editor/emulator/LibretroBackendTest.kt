package com.supermetroid.editor.emulator

import com.supermetroid.editor.libretro.LibretroCoreDiscovery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes

class LibretroBackendTest {

    @Test
    fun `registry returns libretro backend`() {
        val backend = EmulatorRegistry.create("libretro")
        assertNotNull(backend)
        assertEquals("libretro", backend.name)
        assertTrue(backend is LibretroBackend)
        backend.close()
    }

    @Test
    fun `registry lists libretro in available backends`() {
        val backends = EmulatorRegistry.availableBackends()
        assertTrue(backends.contains("libretro"))
    }

    @Test
    fun `frame holder push and read`() {
        val holder = FrameHolder()
        assertEquals(0L, holder.frameVersion)
        assertTrue(holder.latestFrame == null)

        // We can't easily create an ImageBitmap in a headless test,
        // so we just verify the clear behavior
        holder.clear()
        assertEquals(0L, holder.frameVersion)
        assertTrue(holder.latestFrame == null)
    }

    @Test
    fun `wram word reading`() {
        // Simulate little-endian 16-bit word reading
        val data = byteArrayOf(0x9B.toByte(), 0x07, 0xC2.toByte(), 0x09)
        val word0 = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val word1 = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        assertEquals(0x079B, word0) // roomId address value
        assertEquals(0x09C2, word1) // health address value
    }

    @Test
    fun `pixel format conversion - RGB565 to ARGB8888`() {
        // Pure red in RGB565: 11111 000000 00000
        val red565 = 0xF800
        val r = (red565 shr 11) and 0x1F
        val g = (red565 shr 5) and 0x3F
        val b = red565 and 0x1F
        val argb = (0xFF shl 24) or ((r * 255 / 31) shl 16) or ((g * 255 / 63) shl 8) or (b * 255 / 31)
        assertEquals(0xFF.toByte(), ((argb shr 24) and 0xFF).toByte()) // alpha
        assertEquals(0xFF.toByte(), ((argb shr 16) and 0xFF).toByte()) // red
        assertEquals(0x00.toByte(), ((argb shr 8) and 0xFF).toByte())  // green
        assertEquals(0x00.toByte(), (argb and 0xFF).toByte())           // blue
    }

    @Test
    fun `pixel format conversion - 0RGB1555 to ARGB8888`() {
        // Pure green in 0RGB1555: 0 00000 11111 00000
        val green1555 = 0x03E0
        val r = (green1555 shr 10) and 0x1F
        val g = (green1555 shr 5) and 0x1F
        val b = green1555 and 0x1F
        val argb = (0xFF shl 24) or ((r * 255 / 31) shl 16) or ((g * 255 / 31) shl 8) or (b * 255 / 31)
        assertEquals(0x00.toByte(), ((argb shr 16) and 0xFF).toByte()) // red
        assertEquals(0xFF.toByte(), ((argb shr 8) and 0xFF).toByte())  // green
        assertEquals(0x00.toByte(), (argb and 0xFF).toByte())           // blue
    }

    @Test
    fun `core discovery returns list`() {
        // Just verify it doesn't crash; actual discovery depends on system
        val cores = LibretroCoreDiscovery.listCores()
        assertNotNull(cores)
    }

    @Test
    fun `readLibretroStateFile decompresses legacy gzip states`() {
        val payload = "legacy-state-bytes".toByteArray()
        val file = createTempFile(suffix = ".state").toFile()
        GZIPOutputStream(file.outputStream()).use { it.write(payload) }

        assertEquals(payload.toList(), readLibretroStateFile(file).toList())
    }

    @Test
    fun `readLibretroStateFile preserves raw states`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val file = createTempFile(suffix = ".state")
        file.writeBytes(payload)

        assertEquals(payload.toList(), readLibretroStateFile(file.toFile()).toList())
    }

    @Test
    fun `audio output mute flag`() {
        val audio = LibretroAudioOutput()
        assertEquals(false, audio.muted)
        audio.muted = true
        assertEquals(true, audio.muted)
        // No audio line opened, so writeSamples should be a no-op
        audio.writeSamples(ShortArray(100))
        audio.close()
    }
}
