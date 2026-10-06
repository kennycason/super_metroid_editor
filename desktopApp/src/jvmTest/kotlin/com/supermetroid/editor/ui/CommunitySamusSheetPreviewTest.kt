package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.SamusCommunitySheetDecoder
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommunitySamusSheetPreviewTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun loaderRecognizesCatalogMetadataWithoutMutatingAnything() {
        val png = temporaryDirectory.resolve("samus_invisible.png").toFile()
        ImageIO.write(
            BufferedImage(
                SamusCommunitySheetDecoder.FORMAT_WIDTH,
                SamusCommunitySheetDecoder.FORMAT_HEIGHT,
                BufferedImage.TYPE_INT_ARGB,
            ),
            "png",
            png,
        )
        temporaryDirectory.resolve("manifest.json").writeText(
            """
            [
              {
                "category_name": "Transformed Samus",
                "sprites": [
                  {
                    "name": "samus_invisible",
                    "version": 1,
                    "display_name": "Invisible Samus",
                    "authors": ["TarThoron"]
                  }
                ]
              }
            ]
            """.trimIndent(),
        )

        val session = CommunitySamusPreviewLoader().load(png)

        assertTrue(session.result.isValid, session.result.issues.joinToString { it.message })
        assertEquals(SamusCommunitySheetDecoder.IMAGE_COUNT, assertNotNull(session.result.sheet).images.size)
        assertEquals("Invisible Samus", session.metadata?.displayName)
        assertEquals(listOf("TarThoron"), session.metadata?.authors)
        assertEquals("Transformed Samus", session.metadata?.category)
        assertTrue(session.result.issues.any { it.code == "FULLY_TRANSPARENT" })
    }

    @Test
    fun loaderKeepsInvalidPngAvailableForGuidedErrors() {
        val png = temporaryDirectory.resolve("wrong-size.png").toFile()
        ImageIO.write(BufferedImage(32, 48, BufferedImage.TYPE_INT_ARGB), "png", png)

        val session = CommunitySamusPreviewLoader().load(png)

        assertTrue(!session.result.isValid)
        assertEquals(null, session.result.sheet)
        assertTrue(session.result.issues.any { it.code == "WRONG_DIMENSIONS" })
    }

    @Test
    fun regionFiltersUseCommunityNamesAndGuidedGroups() {
        val image = SamusCommunitySheetDecoder.DecodedImage(
            name = "file_select_head2",
            width = 24,
            height = 24,
            pixels = IntArray(24 * 24),
            paletteIndices = ByteArray(24 * 24),
            paletteInterval = 60 until 75,
        )

        assertEquals(CommunitySamusRegionCategory.FILE_SELECT, communitySamusRegionCategory(image.name))
        assertTrue(communitySamusRegionMatches(image, "select head", CommunitySamusRegionCategory.ALL))
        assertTrue(communitySamusRegionMatches(image, "head2", CommunitySamusRegionCategory.FILE_SELECT))
        assertTrue(!communitySamusRegionMatches(image, "head", CommunitySamusRegionCategory.GAMEPLAY))
    }
}
