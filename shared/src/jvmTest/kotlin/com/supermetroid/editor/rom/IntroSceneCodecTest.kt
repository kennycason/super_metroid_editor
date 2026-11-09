package com.supermetroid.editor.rom

import com.supermetroid.editor.data.IntroScene
import com.supermetroid.editor.data.IntroSceneLayout
import com.supermetroid.editor.data.IntroImageSource
import com.supermetroid.editor.data.IntroPortraitAnimation
import com.supermetroid.editor.data.IntroTextReveal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertNotNull

class IntroSceneCodecTest {
    @Test
    fun `native cinematic objects use bank 8B screen anchors and counter pans`() {
        assertEquals(0x38 to 0x6F, IntroRomImageResolver.nativeObjectPosition(IntroImageSource.ROM_MOTHER_BRAIN, 99, -44))
        assertEquals(0x70 to 0x9B, IntroRomImageResolver.nativeObjectPosition(IntroImageSource.ROM_EGG_HATCHING, 99, -44))

        assertEquals(0x54 to 0x8B, IntroRomImageResolver.nativeObjectPosition(IntroImageSource.ROM_BABY_DELIVERED, 0x20, 8))
        assertEquals(0x74 to 0x8B, IntroRomImageResolver.nativeObjectPosition(IntroImageSource.ROM_BABY_DELIVERED, 0, 8))

        assertEquals(0x70 to 0x6F, IntroRomImageResolver.nativeObjectPosition(IntroImageSource.ROM_BABY_EXAMINED, 0, -0x18))
        assertEquals(0x70 to 0x4F, IntroRomImageResolver.nativeObjectPosition(IntroImageSource.ROM_BABY_EXAMINED, 0, 8))
    }

    @Test
    fun `typewriter mask reveals characters without changing final wrapping`() {
        val text = "I FIRST BATTLED THE METROIDS ON PLANET ZEBES. IT WAS THERE THAT I FOLLOWED THE PLANS."
        val hidden = IntroSceneCodec.maskTextForReveal(text, 0)
        val partial = IntroSceneCodec.maskTextForReveal(text, 17)

        assertEquals(IntroSceneCodec.textLineCount(text), IntroSceneCodec.textLineCount(hidden))
        assertEquals(IntroSceneCodec.textLineCount(text), IntroSceneCodec.textLineCount(partial))
        assertEquals(text, IntroSceneCodec.maskTextForReveal(text, text.length))

        val scene = IntroScene(text = text, layout = IntroSceneLayout.TEXT_ONLY)
        val hiddenPixels = IntroSceneCodec.render(scene.copy(text = hidden))
            .getRGB(0, 0, IntroSceneCodec.WIDTH, IntroSceneCodec.HEIGHT, null, 0, IntroSceneCodec.WIDTH)
        val partialPixels = IntroSceneCodec.render(scene.copy(text = partial))
            .getRGB(0, 0, IntroSceneCodec.WIDTH, IntroSceneCodec.HEIGHT, null, 0, IntroSceneCodec.WIDTH)
        assertTrue(hiddenPixels.all { it == scene.backgroundColor })
        assertTrue(partialPixels.any { it == scene.textColor })
    }

    @Test
    fun `native intro atlas resolves into four complete distinct vignette viewports`() {
        val parser = TestRomHelper.requireRomParser()
        val sources = listOf(
            IntroImageSource.ROM_MOTHER_BRAIN,
            IntroImageSource.ROM_EGG_HATCHING,
            IntroImageSource.ROM_BABY_DELIVERED,
            IntroImageSource.ROM_BABY_EXAMINED,
        )
        val hashes = sources.map { source ->
            val image = assertNotNull(IntroRomImageResolver.render(parser, source, 0))
            assertEquals(256, image.width)
            assertEquals(224, image.height)
            image.getRGB(0, 0, image.width, image.height, null, 0, image.width).contentHashCode()
        }
        assertEquals(4, hashes.distinct().size, "Each $800-byte BG1 map must render as its own screen")
    }

    @Test
    fun `native portrait exposes the real open half closed and deadpan eye tiles`() {
        val parser = TestRomHelper.requireRomParser()
        val hashes = (0..3).map { frame ->
            val mode = if (frame == 3) IntroPortraitAnimation.DEADPAN else IntroPortraitAnimation.NATIVE_BLINK
            val image = assertNotNull(IntroRomImageResolver.renderPortrait(parser, mode, frame))
            image.getRGB(17 * 8, 13 * 8, 24, 16, null, 0, 24).contentHashCode()
        }
        assertEquals(4, hashes.distinct().size)
    }

    @Test
    fun `portrait animation frames keep typewriter text tile words compatible`() {
        val parser = TestRomHelper.requireRomParser()
        val scene = IntroScene(
            text = "I FIRST BATTLED THE METROIDS ON PLANET ZEBES.",
            imageSource = IntroImageSource.ROM_SAMUS_PORTRAIT,
            layout = IntroSceneLayout.TEXT_OVER_IMAGE,
            portraitAnimation = IntroPortraitAnimation.NATIVE_BLINK,
            textReveal = IntroTextReveal.NATIVE_TYPEWRITER,
        )
        val images = (0..3).map { assertNotNull(IntroRomImageResolver.renderPortrait(parser, scene.portraitAnimation, it)) }
        val compiled = images.map { IntroSceneCodec.compile(scene, it) }
        val blank = IntroSceneCodec.render(scene.copy(text = ""), images.first())
        val full = IntroSceneCodec.render(scene, images.first())
        val textPositions = buildList {
            for (tileY in 0 until IntroSceneCodec.HEIGHT / 8) for (tileX in 0 until IntroSceneCodec.WIDTH / 8) {
                if ((0..7).any { y -> (0..7).any { x ->
                        full.getRGB(tileX * 8 + x, tileY * 8 + y) != blank.getRGB(tileX * 8 + x, tileY * 8 + y)
                    } }) add(tileY * 32 + tileX)
            }
        }

        val firstWords = textPositions.associateWith { position ->
            val offset = position * 2
            (compiled.first().tilemap[offset].toInt() and 0xFF) or
                ((compiled.first().tilemap[offset + 1].toInt() and 0xFF) shl 8)
        }
        compiled.drop(1).forEach { frame ->
            val remapped = frame.tilemap.copyOf()
            firstWords.forEach { (position, word) ->
                remapped[position * 2] = word.toByte()
                remapped[position * 2 + 1] = (word ushr 8).toByte()
            }
            val expected = IntroSceneCodec.decode(frame)
            val actual = IntroSceneCodec.decode(frame.copy(tilemap = remapped))
            textPositions.forEach { position ->
                val tileX = position % 32 * 8
                val tileY = position / 32 * 8
                assertEquals(
                    expected.getRGB(tileX, tileY, 8, 8, null, 0, 8).toList(),
                    actual.getRGB(tileX, tileY, 8, 8, null, 0, 8).toList(),
                    "Text tile $position must survive a portrait frame DMA",
                )
            }
        }
    }

    @Test
    fun `ROM cinematic sources render through the managed still compiler`() {
        val parser = TestRomHelper.requireRomParser()
        IntroRomImageResolver.sources.forEach { source ->
            val image = assertNotNull(IntroRomImageResolver.render(parser, source, IntroRomImageResolver.defaultFrame(source)))
            assertTrue(
                image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                    .any { it ushr 24 != 0 && it and 0x00FFFFFF != 0 },
                "${source.name} source frame should contain visible color",
            )
            val scene = IntroScene(imageSource = source, layout = IntroSceneLayout.IMAGE_ONLY)
            val decoded = IntroSceneCodec.decode(IntroSceneCodec.compile(scene, image))
            assertTrue(
                decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width)
                    .any { it and 0x00FFFFFF != 0 },
                "${source.name} should remain visible after SNES compilation",
            )
        }
    }

    @Test
    fun `validated PNG remains portable and hash checked after project storage`() {
        val source = BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setRGB(x, y, if ((x + y) % 2 == 0) 0xFFFF8000.toInt() else 0xFF102030.toInt())
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(source, "PNG", it) }.toByteArray()
        val imported = IntroSceneCodec.importPng(bytes)
        val scene = IntroScene(
            imagePngBase64 = imported.base64,
            imageFileName = "portable.png",
            imageSha256 = imported.sha256,
            layout = IntroSceneLayout.IMAGE_ONLY,
        )

        assertEquals(256, IntroSceneCodec.render(scene).width)
        assertEquals(imported.sha256, IntroSceneCodec.importPng(java.util.Base64.getDecoder().decode(imported.base64)).sha256)
    }

    @Test
    fun `full frame always fits tile and palette hardware limits`() {
        val image = BufferedImage(IntroSceneCodec.WIDTH, IntroSceneCodec.HEIGHT, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val r = (x * 17 + y * 3) and 255
            val g = (x * 5 + y * 19) and 255
            val b = (x * 11 xor y * 23) and 255
            image.setRGB(x, y, 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
        }

        val compiled = IntroSceneCodec.compile(image)

        assertTrue(compiled.tileCount <= 897, "896 visible tiles plus the guaranteed blank tile")
        assertTrue(compiled.tileCount <= 1024)
        assertEquals(compiled.tileCount * 32, compiled.tiles.size)
        assertEquals(32 * 32 * 2, compiled.tilemap.size)
        assertEquals(256, compiled.palette.size)
        assertEquals(256, IntroSceneCodec.decode(compiled).width)
        assertEquals(224, IntroSceneCodec.decode(compiled).height)
    }

    @Test
    fun `tilemap palettes follow legal 32 pixel bands`() {
        val scene = IntroScene(
            text = "PALETTE BAND TEST",
            layout = IntroSceneLayout.TEXT_ONLY,
            backgroundColor = 0xFF102030.toInt(),
        )
        val compiled = IntroSceneCodec.compile(scene)

        for (tileY in 0 until 28) for (tileX in 0 until 32) {
            val offset = (tileY * 32 + tileX) * 2
            val word = (compiled.tilemap[offset].toInt() and 255) or
                ((compiled.tilemap[offset + 1].toInt() and 255) shl 8)
            assertEquals(tileY / 4, word ushr 10 and 7)
            assertTrue((word and 0x3FF) < compiled.tileCount)
        }
    }

    @Test
    fun `identical and flipped tiles deduplicate`() {
        val image = BufferedImage(256, 224, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 224) for (x in 0 until 256) {
            val localX = x and 7
            val localY = y and 7
            val lit = localX == localY || localX + localY == 7
            image.setRGB(x, y, if (lit) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        }

        val compiled = IntroSceneCodec.compile(image)

        assertTrue(compiled.tileCount <= 2)
    }
}
