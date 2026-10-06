package com.supermetroid.editor.rom

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SamusCommunityAnimationsTest {
    @Test
    fun bundledManifestMatchesPinnedSpriteSomethingVocabulary() {
        val catalog = SamusCommunityAnimationCatalog.loadBundled()

        assertEquals(41, catalog.groups.size)
        assertEquals(202, catalog.variantCount)
        assertEquals(1_747, catalog.frameCount)
        val stand = assertNotNull(catalog.groups.firstOrNull { it.name == "Stand" })
        val right = assertNotNull(stand.variants.firstOrNull { it.name == "right" })
        assertEquals(4, right.frames.size)
        assertEquals(listOf(10, 10, 10, 10), right.frames.map { it.durationTicks })
    }

    @Test
    fun everySheetOwnedManifestImageResolves() {
        val image = BufferedImage(
            SamusCommunitySheetDecoder.FORMAT_WIDTH,
            SamusCommunitySheetDecoder.FORMAT_HEIGHT,
            BufferedImage.TYPE_INT_ARGB,
        )
        val sheet = assertNotNull(SamusCommunitySheetDecoder().decode(image, "transparent-fixture.png").sheet)

        assertEquals(
            setOf("optional_ship_body", "optional_ship_structure", "optional_ship_thrusters", "optional_ship_window"),
            SamusCommunityAnimationCatalog.loadBundled().unresolvedImageNames(sheet),
        )
    }

    @Test
    fun compositionUsesSpriteSomethingLayerOrderAndOptionalSheetArt() {
        val catalog = SamusCommunityAnimationCatalog.parse(
            """
            {
              "Test": {
                "right": [
                  {
                    "frames": 3,
                    "tiles": [
                      {"image": "optional_gun", "pos": [0, 0]},
                      {"image": "body", "pos": [0, 0]}
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val sheet = SamusCommunitySheetDecoder.DecodedSheet(
            sourceName = "fixture",
            width = 1,
            height = 1,
            images = linkedMapOf(
                "gun" to decodedImage("gun", red),
                "body" to decodedImage("body", blue),
            ),
            masterPaletteRgb = IntArray(105),
            opaqueIndexedPixelCount = 2,
            quantizedPixelCount = 0,
        )

        val animation = catalog.buildAnimation(sheet, catalog.groups.single(), catalog.groups.single().variants.single())
        val frame = animation.frames.single()

        assertEquals(3, frame.durationTicks)
        assertEquals(red, frame.pixels[2 * frame.width + 2])
        assertTrue(frame.width >= 5 && frame.height >= 5)
    }

    @Test
    fun standardGameplayArtUsesTheSelectedCommunitySuitPalette() {
        val catalog = SamusCommunityAnimationCatalog.parse(
            """
            {
              "Stand": {
                "right": [
                  {"frames": 1, "tiles": [{"image": "body", "pos": [0, 0]}]}
                ]
              }
            }
            """.trimIndent(),
        )
        val power = 0xFFCC3300.toInt()
        val varia = 0xFF33CC00.toInt()
        val gravity = 0xFF6633CC.toInt()
        val palettes = IntArray(105).also {
            it[0] = power and 0x00FFFFFF
            it[15] = varia and 0x00FFFFFF
            it[30] = gravity and 0x00FFFFFF
        }
        val body = SamusCommunitySheetDecoder.DecodedImage(
            name = "body",
            width = 2,
            height = 1,
            pixels = intArrayOf(power, 0),
            paletteIndices = byteArrayOf(1, 0),
            paletteInterval = 0 until 15,
        )
        val sheet = SamusCommunitySheetDecoder.DecodedSheet(
            sourceName = "fixture",
            width = 2,
            height = 1,
            images = mapOf("body" to body),
            masterPaletteRgb = palettes,
            opaqueIndexedPixelCount = 1,
            quantizedPixelCount = 0,
        )
        val group = catalog.groups.single()
        val variant = group.variants.single()

        fun renderedColor(suit: SamusSpriteDecoder.SuitType): Int =
            catalog.buildAnimation(sheet, group, variant, suit).frames.single().pixels
                .first { (it ushr 24) != 0 }

        assertEquals(power, renderedColor(SamusSpriteDecoder.SuitType.POWER))
        assertEquals(varia, renderedColor(SamusSpriteDecoder.SuitType.VARIA))
        assertEquals(gravity, renderedColor(SamusSpriteDecoder.SuitType.GRAVITY))
        assertEquals(15 until 30, sheet.paletteIntervalForSuit(body, SamusSpriteDecoder.SuitType.VARIA))
        assertEquals(30 until 45, sheet.paletteIntervalForSuit(body, SamusSpriteDecoder.SuitType.GRAVITY))
        assertEquals(0, sheet.pixelsForSuit(body, SamusSpriteDecoder.SuitType.GRAVITY)[1])
    }

    private fun decodedImage(name: String, color: Int) = SamusCommunitySheetDecoder.DecodedImage(
        name = name,
        width = 1,
        height = 1,
        pixels = intArrayOf(color),
        paletteIndices = byteArrayOf(1),
        paletteInterval = 0..0,
    )
}
