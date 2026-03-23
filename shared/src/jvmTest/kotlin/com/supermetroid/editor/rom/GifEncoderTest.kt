package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

class GifEncoderTest {

    @Test
    fun `encode single frame GIF has correct header`() {
        val pixels = IntArray(4 * 4) { 0xFFFF0000.toInt() } // 4x4 red
        val frame = SpriteAnimationFrame(pixels, 4, 4, durationTicks = 4)
        val gif = GifEncoder.encode(listOf(frame))

        // GIF89a header
        assertEquals('G'.code.toByte(), gif[0])
        assertEquals('I'.code.toByte(), gif[1])
        assertEquals('F'.code.toByte(), gif[2])
        assertEquals('8'.code.toByte(), gif[3])
        assertEquals('9'.code.toByte(), gif[4])
        assertEquals('a'.code.toByte(), gif[5])

        // Trailer
        assertEquals(0x3B.toByte(), gif.last())
    }

    @Test
    fun `encode GIF logical screen dimensions match max frame size`() {
        val frame1 = SpriteAnimationFrame(IntArray(8 * 8), 8, 8, 4)
        val frame2 = SpriteAnimationFrame(IntArray(16 * 12), 16, 12, 4)
        val gif = GifEncoder.encode(listOf(frame1, frame2))

        // Logical screen width at bytes 6-7 (LE)
        val width = (gif[6].toInt() and 0xFF) or ((gif[7].toInt() and 0xFF) shl 8)
        val height = (gif[8].toInt() and 0xFF) or ((gif[9].toInt() and 0xFF) shl 8)
        assertEquals(16, width, "Logical screen width should match widest frame")
        assertEquals(12, height, "Logical screen height should match tallest frame")
    }

    @Test
    fun `encode animated GIF has NETSCAPE extension for looping`() {
        val frames = listOf(
            SpriteAnimationFrame(IntArray(4), 2, 2, 4),
            SpriteAnimationFrame(IntArray(4), 2, 2, 4)
        )
        val gif = GifEncoder.encode(frames, loop = 0)
        val gifStr = String(gif, Charsets.US_ASCII)

        assertTrue(gifStr.contains("NETSCAPE2.0"), "Animated GIF should contain NETSCAPE extension")
    }

    @Test
    fun `encode GIF with transparency`() {
        // 2x2: one transparent, three colored
        val pixels = intArrayOf(
            0x00000000,        // transparent
            0xFFFF0000.toInt(), // red
            0xFF00FF00.toInt(), // green
            0xFF0000FF.toInt()  // blue
        )
        val frame = SpriteAnimationFrame(pixels, 2, 2, 4)
        val gif = GifEncoder.encode(listOf(frame))

        // Should contain graphic control extension with transparency flag
        var hasGce = false
        for (i in 0 until gif.size - 2) {
            if (gif[i] == 0x21.toByte() && gif[i + 1] == 0xF9.toByte()) {
                hasGce = true
                // Byte i+3 is packed field: bit 0 should be set (transparent flag)
                val packed = gif[i + 3].toInt() and 0xFF
                assertTrue(packed and 0x01 != 0, "Transparent flag should be set")
                break
            }
        }
        assertTrue(hasGce, "GIF should contain graphic control extension")
    }

    @Test
    fun `encode GIF has correct frame count via image separators`() {
        val frames = (0 until 5).map {
            SpriteAnimationFrame(IntArray(4), 2, 2, durationTicks = 4)
        }
        val gif = GifEncoder.encode(frames)

        // Count image separators (0x2C)
        var count = 0
        for (b in gif) {
            if (b == 0x2C.toByte()) count++
        }
        assertEquals(5, count, "Should have 5 image separator markers for 5 frames")
    }

    @Test
    fun `encodeSingle produces valid single frame GIF`() {
        val pixels = IntArray(8 * 8) { 0xFF00FF00.toInt() } // green
        val gif = GifEncoder.encodeSingle(pixels, 8, 8)

        assertTrue(gif.size > 20, "Single frame GIF should have non-trivial size")
        assertEquals('G'.code.toByte(), gif[0])
        assertEquals(0x3B.toByte(), gif.last())

        // Only 1 image separator
        val separators = gif.count { it == 0x2C.toByte() }
        assertEquals(1, separators)
    }

    @Test
    fun `encode GIF frame delays convert ticks correctly`() {
        // 6 ticks = 100ms = 10 centiseconds
        val frame = SpriteAnimationFrame(IntArray(4), 2, 2, durationTicks = 6)
        val gif = GifEncoder.encode(listOf(frame))

        // Find graphic control extension, delay is at offset +4,+5 from 0x21 0xF9
        for (i in 0 until gif.size - 6) {
            if (gif[i] == 0x21.toByte() && gif[i + 1] == 0xF9.toByte()) {
                val delayCenti = (gif[i + 4].toInt() and 0xFF) or ((gif[i + 5].toInt() and 0xFF) shl 8)
                assertEquals(10, delayCenti, "6 ticks should convert to 10 centiseconds")
                return
            }
        }
        assertTrue(false, "Should have found graphic control extension")
    }

    @Test
    fun `encode GIF roundtrip is readable by ImageIO`() {
        // Generate a multicolor test image
        val w = 16
        val h = 16
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = when {
                    x < 8 && y < 8 -> 0xFFFF0000.toInt()
                    x >= 8 && y < 8 -> 0xFF00FF00.toInt()
                    x < 8 -> 0xFF0000FF.toInt()
                    else -> 0x00000000 // transparent
                }
            }
        }

        val frames = listOf(
            SpriteAnimationFrame(pixels, w, h, 4),
            SpriteAnimationFrame(pixels, w, h, 6)
        )
        val gifBytes = GifEncoder.encode(frames)

        // Write to temp file and verify ImageIO can read it
        val tmpFile = File.createTempFile("test_animation", ".gif")
        try {
            tmpFile.writeBytes(gifBytes)
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            reader.input = ImageIO.createImageInputStream(tmpFile)

            val frameCount = reader.getNumImages(true)
            assertEquals(2, frameCount, "ImageIO should read 2 frames")

            val img0 = reader.read(0)
            assertNotNull(img0, "First frame should be readable")
            assertEquals(w, img0.width)
            assertEquals(h, img0.height)

            reader.dispose()
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `encode GIF with many colors builds valid palette`() {
        // 15 distinct colors + transparent
        val colors = (1..15).map { i ->
            0xFF000000.toInt() or (i * 17 shl 16) or (i * 11 shl 8) or (i * 7)
        }
        val w = 15
        val h = 1
        val pixels = IntArray(w) { i -> colors[i] }
        val frame = SpriteAnimationFrame(pixels, w, h, 4)
        val gif = GifEncoder.encode(listOf(frame))

        // Should have a global color table (bit 7 of packed byte at offset 10)
        val packed = gif[10].toInt() and 0xFF
        assertTrue(packed and 0x80 != 0, "Should have global color table flag set")

        // Should be a valid GIF
        assertEquals('G'.code.toByte(), gif[0])
        assertEquals(0x3B.toByte(), gif.last())
    }
}
