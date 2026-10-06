package com.supermetroid.editor.rom

import org.junit.jupiter.api.Tag
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SamusCommunitySheetTest {
    @Test
    fun bundledLayoutMatchesPinnedFormatContract() {
        val layout = SamusCommunityLayout.loadBundled()

        assertEquals(SamusCommunitySheetDecoder.ROW_COUNT, layout.rows.size)
        assertEquals(SamusCommunitySheetDecoder.IMAGE_COUNT, layout.rows.flatten().size)
        assertEquals(SamusCommunitySheetDecoder.DMA_SEQUENCE_COUNT, layout.dmaSequence.size)
        assertEquals(SamusCommunitySheetDecoder.FORMAT_WIDTH, layout.expectedWidth())
        assertEquals(SamusCommunitySheetDecoder.FORMAT_HEIGHT, layout.expectedHeight())
        assertTrue(layout.validate().isEmpty())
    }

    @Test
    fun fullyTransparentSheetIsAValidEdgeCase() {
        val image = BufferedImage(
            SamusCommunitySheetDecoder.FORMAT_WIDTH,
            SamusCommunitySheetDecoder.FORMAT_HEIGHT,
            BufferedImage.TYPE_INT_ARGB,
        )

        val result = SamusCommunitySheetDecoder().decode(image, "transparent-test.png")

        assertTrue(result.isValid, result.issues.joinToString { it.message })
        val sheet = assertNotNull(result.sheet)
        assertEquals(SamusCommunitySheetDecoder.IMAGE_COUNT, sheet.images.size)
        assertEquals(0, sheet.opaqueIndexedPixelCount)
        assertTrue(sheet.images.values.filter { it.paletteIndices != null }.all { decoded ->
            decoded.paletteIndices!!.all { it == 0.toByte() }
        })
        assertTrue(result.issues.any { it.code == "FULLY_TRANSPARENT" })
    }

    @Test
    fun wrongCanvasSizeIsRejected() {
        val result = SamusCommunitySheetDecoder().decode(
            BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB),
            "wrong.png",
        )

        assertTrue(!result.isValid)
        assertEquals(null, result.sheet)
        assertTrue(result.issues.any { it.code == "WRONG_DIMENSIONS" })
    }
}

@Tag("community-samus")
class SamusCommunitySheetFixtureTest {
    private val fixtureDir: File = File(
        System.getProperty("smedit.communitySamusDir")
            ?: error("communitySamusTest must provide smedit.communitySamusDir"),
    )

    @Test
    fun catalogIdentifiesTheRealInvisibleSamusSheet() {
        val sprites = MapRandoSamusCatalog.parse(File(fixtureDir, "manifest.json"))
        val invisible = sprites.single { it.name == "samus_invisible" }

        assertEquals("Invisible Samus", invisible.displayName)
        assertEquals(listOf("TarThoron"), invisible.authors)
        assertEquals("Transformed Samus", invisible.category)
    }

    @Test
    fun pinnedCommunitySamplesMatchSpriteSomethingExtraction() {
        EXPECTED.forEach { (fileName, expected) ->
            val result = SamusCommunitySheetDecoder().decode(File(fixtureDir, fileName))
            assertTrue(result.isValid, "$fileName: ${result.issues.joinToString { it.message }}")
            val sheet = assertNotNull(result.sheet)

            assertEquals(SamusCommunitySheetDecoder.IMAGE_COUNT, sheet.images.size, fileName)
            assertEquals(105, sheet.masterPaletteRgb.size, fileName)
            assertEquals(expected.semanticHash, semanticSha256(sheet), "$fileName/all named regions")
            assertEquals(
                expected.paletteRgba,
                rgbaSha256(sheet.images.getValue("palette_block").pixels),
                "$fileName palette=${sheet.masterPaletteRgb.take(20).joinToString { "#%06X".format(it) }}",
            )
            expected.indices.forEach { (imageName, expectedHash) ->
                val image = sheet.images.getValue(imageName)
                assertEquals(EXPECTED_DIMENSIONS.getValue(imageName), image.width to image.height, "$fileName/$imageName")
                assertEquals(expectedHash, sha256(assertNotNull(image.paletteIndices)), "$fileName/$imageName")
            }
        }
    }

    private fun rgbaSha256(pixels: IntArray): String {
        val bytes = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { index, argb ->
            bytes[index * 4] = (argb ushr 16).toByte()
            bytes[index * 4 + 1] = (argb ushr 8).toByte()
            bytes[index * 4 + 2] = argb.toByte()
            bytes[index * 4 + 3] = (argb ushr 24).toByte()
        }
        return sha256(bytes)
    }

    private fun semanticSha256(sheet: SamusCommunitySheetDecoder.DecodedSheet): String {
        val digest = MessageDigest.getInstance("SHA-256")
        sheet.images.toSortedMap().forEach { (name, image) ->
            digest.update(name.toByteArray())
            digest.update(0)
            digest.update(ByteBuffer.allocate(8).putInt(image.width).putInt(image.height).array())
            if (image.paletteIndices != null) {
                digest.update(image.paletteIndices)
            } else {
                val rgba = ByteArray(image.pixels.size * 4)
                image.pixels.forEachIndexed { index, argb ->
                    rgba[index * 4] = (argb ushr 16).toByte()
                    rgba[index * 4 + 1] = (argb ushr 8).toByte()
                    rgba[index * 4 + 2] = argb.toByte()
                    rgba[index * 4 + 3] = (argb ushr 24).toByte()
                }
                digest.update(rgba)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private data class ExpectedSheet(
        val semanticHash: String,
        val paletteRgba: String,
        val indices: Map<String, String>,
    )

    companion object {
        private val EXPECTED_DIMENSIONS = mapOf(
            "stand_right" to (32 to 48),
            "run_right" to (40 to 48),
            "morph_right" to (32 to 32),
            "death_right" to (32 to 64),
            "file_select_head" to (24 to 24),
        )

        private val EXPECTED = mapOf(
            "samus_vanilla.png" to ExpectedSheet(
                "f8b981810923f27124a220aacdcf64e5c92cc0165d0c0be4622ee8fe7fa3086a",
                "5b2db3f9a0ce354f5646fd20bb5b98c6fe63e723dd480bb91fc164ab5ae8c528",
                mapOf(
                    "stand_right" to "7e699ffec4f704c3f0ea009f8f4df3ff81a06d63c478928013a8d98c8f1a50f7",
                    "run_right" to "f057e44665ef2ac0859d1880533fabcc1c8817de30b7f7d690e2bdad700723b2",
                    "morph_right" to "2c3ed3c778a56b1a5df022daaf2c3e5d31b45322870839c689dad182896a918a",
                    "death_right" to "6a88dd097fe9a1fd2e79a2a14c431ab7e05ac5e512b3a57a7344acd1703c1326",
                    "file_select_head" to "24892d03990efa75183fa1e8c5d930623d0a430e135f9311bc7711644dc82183",
                ),
            ),
            "samus_invisible.png" to ExpectedSheet(
                "b769a1f80140ecbbd4eb15dd036286fb1f4cb0618913d6fc4c342f070bd509bf",
                "de1da8f3791deb5ea5a11c9a67179b1e240aa14979524a3cc28add0e3612c0fe",
                mapOf(
                    "stand_right" to "80422bc3d307b4a25bdafcc84ac7fb01cb55a09810e8b0f37bb12e0edb5c48ca",
                    "run_right" to "155e437b946ac82ae591ff382b8d19efda9397b2282672dbabd91ec31ce8a651",
                    "morph_right" to "5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef",
                    "death_right" to "e5a00aa9991ac8a5ee3109844d84a55583bd20572ad3ffcd42792f3c36b183ad",
                    "file_select_head" to "1a0295f4bf5986c5f74eca9153a6a4cb10b073a01a76ba4a457fd862c78966a4",
                ),
            ),
            "samus_outline.png" to ExpectedSheet(
                "4e20425fd9943052ee20bcf1f3061434c9fb1d1f2d60c2c655258664f746c95a",
                "dbd660cb7e2a47418f95884246a21982c6052b987857d5f3ccebb43a5323c6b1",
                mapOf(
                    "stand_right" to "d76c77329c5e654b6bece8a4582dc22c1f0079abcb270e4ed32525f39d16d67f",
                    "run_right" to "6b37f3c71f94f5e15b6a6e0df51c301e6b77d9bf842879d61ff43096dad55771",
                    "morph_right" to "ab7684e32166819b0bd97587c396ca60e478a5c85cb08cebe1c625716111d9fa",
                    "death_right" to "e9419e63e893f70de48a0458828860ab874d98d7cdefc903b8f070df2d02cf7e",
                    "file_select_head" to "ad131e2ea8be9045af68c876a466e7f60e871a09d34bf7a790a970668248cf6d",
                ),
            ),
            "samus_zero-mission.png" to ExpectedSheet(
                "ea681ebb234320e072afe3dbb0232f52d86aa09835b4a784112b52a5dfb4c996",
                "248cb3ae8b78dec3fa4b3cfcb7004e1d388bc2012c26bcaa5253536fa09b7022",
                mapOf(
                    "stand_right" to "775f6044e48383d1561d8afeef5107333e26e6c2ce53f704a6b9da55b8d964fa",
                    "run_right" to "f8c4df8a1babb32f05315ed6726419204fb933046458aebb284801c0678f22be",
                    "morph_right" to "225b7a97a4aed17a7d577f4e0e77dc12ffbb37abb2bd919fe8f84b5edac59d58",
                    "death_right" to "6a88dd097fe9a1fd2e79a2a14c431ab7e05ac5e512b3a57a7344acd1703c1326",
                    "file_select_head" to "c0144168882c144ba3ef32741d173058d273e3cd362fe0689a42f86bc396cc9f",
                ),
            ),
        )
    }
}
