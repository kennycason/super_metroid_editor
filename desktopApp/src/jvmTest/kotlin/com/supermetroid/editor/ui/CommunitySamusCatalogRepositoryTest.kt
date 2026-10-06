package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.SamusCommunitySheetDecoder
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommunitySamusCatalogRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `catalog pins downloads to revision and falls back to cache`() {
        val revision = CommunitySamusCatalogRepository.INJECTABLE_CATALOG_REVISION
        val manifest =
            """
            [
              {
                "category_name": "Samus",
                "sprites": [
                  {
                    "name": "samus_test",
                    "version": 2,
                    "display_name": "Test Samus",
                    "authors": ["Sprite Artist"]
                  }
                ]
              }
            ]
            """.trimIndent().toByteArray()
        val png = ByteArrayOutputStream().use { output ->
            ImageIO.write(
                BufferedImage(
                    SamusCommunitySheetDecoder.FORMAT_WIDTH,
                    SamusCommunitySheetDecoder.FORMAT_HEIGHT,
                    BufferedImage.TYPE_INT_ARGB,
                ),
                "png",
                output,
            )
            output.toByteArray()
        }
        val requests = mutableListOf<String>()
        val ips = "PATCH".toByteArray() + byteArrayOf(
            0, 0, 1,
            0, 1,
            0x7F,
        ) + "EOF".toByteArray()
        val repository = CommunitySamusCatalogRepository(temporaryDirectory, fetchBytes = { url ->
            requests += url
            when (url) {
                CommunitySamusCatalogRepository.REVISION_URL -> "{\"sha\":\"$revision\"}".toByteArray()
                CommunitySamusCatalogRepository.manifestUrl(revision) -> manifest
                CommunitySamusCatalogRepository.spriteUrl(revision, "samus_test") -> png
                CommunitySamusCatalogRepository.patchUrl("samus_test") -> ips
                else -> error("Unexpected URL $url")
            }
        })

        val snapshot = repository.refresh()
        val sprite = snapshot.sprites.single()
        val download = repository.download(snapshot, sprite)

        assertEquals(revision, snapshot.revision)
        assertEquals("Test Samus", sprite.displayName)
        assertTrue(download.file.isFile)
        assertContentEquals(png, download.file.readBytes())
        assertEquals(3, requests.size)
        val showcase = repository.loadShowcase(snapshot, sprite)
        assertEquals(
            listOf(
                "stand_right",
                "stand_right_aim_up",
                "run_right3",
                "run_right_aim_diag_up3",
                "jump_right2",
                "jump_right_aim_up1",
                "spin_jump_right2",
                "wall_jump_right",
                "crouch_right",
                "morph_right",
                "shine_spark_right",
                "death_right",
                "fall_right2",
                "moonwalk_right3",
                "crystal_flash_right2",
                "xray_right2",
            ),
            showcase?.images?.map { it.name },
        )
        assertFalse(requireNotNull(showcase).hasVisiblePixels)

        val artifact = repository.downloadInjectionArtifact(
            snapshot,
            sprite,
            sourceSheetSha256 = "ab".repeat(32),
        )
        assertEquals(CommunitySamusCatalogRepository.MAP_RANDOMIZER_REVISION, artifact.artifact.providerRevision)
        assertEquals("ab".repeat(32), artifact.artifact.sourceSheetSha256)
        assertEquals(4, requests.size)

        val cachedArtifact = repository.downloadInjectionArtifact(
            snapshot,
            sprite,
            sourceSheetSha256 = "ab".repeat(32),
        )
        assertTrue(cachedArtifact.usedCachedFile)
        assertEquals(4, requests.size)

        val cachedDownload = repository.download(snapshot, sprite)
        assertTrue(cachedDownload.usedCachedFile)
        assertEquals(4, requests.size)

        val offlineRepository = CommunitySamusCatalogRepository(temporaryDirectory, fetchBytes = { error("offline") })
        val offline = offlineRepository.refresh()
        assertTrue(offline.usedCachedCatalog)
        assertEquals(revision, offline.revision)
        assertTrue(offline.notice.orEmpty().contains("saved copy"))
        assertTrue(
            offlineRepository.downloadInjectionArtifact(
                offline,
                offline.sprites.single(),
                sourceSheetSha256 = "ab".repeat(32),
            ).usedCachedFile,
        )
    }
}
