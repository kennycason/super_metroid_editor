package com.supermetroid.editor.ui

import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectSourceImageArchiveTest {
    @Test fun `store preserves exact bytes under a hash addressed safe project path`() {
        val root = createTempDirectory("smedit-source-images-").toFile()
        try {
            val projectFile = File(root, "test.smedit")
            val bytes = pngBytes(1)
            val expectedHash = sha256(bytes)

            val reference = assertNotNull(ProjectSourceImageArchive.store(
                projectFile.absolutePath,
                "../../Title Logo:Main",
                "my-logo.weird-name",
                bytes,
            ))

            assertEquals("data/source-images/title_logo_main/$expectedHash.png", reference.relativePath)
            assertEquals("my-logo.weird-name", reference.originalFileName)
            assertEquals(expectedHash, reference.sha256)
            assertEquals(bytes.size, reference.byteSize)
            val archived = File(root, reference.relativePath)
            assertTrue(archived.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
            assertContentEquals(bytes, archived.readBytes())

            val portableName = assertNotNull(ProjectSourceImageArchive.store(
                projectFile.absolutePath,
                "title:alternate",
                "C:\\art\\alternate.png",
                pngBytes(2),
            ))
            assertEquals("alternate.png", portableName.originalFileName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `new imports update the reference without deleting older hashes`() {
        val root = createTempDirectory("smedit-source-history-").toFile()
        try {
            val projectPath = File(root, "test.smedit").absolutePath
            val first = assertNotNull(ProjectSourceImageArchive.store(projectPath, "enemy:DCFF", "first.png", pngBytes(1)))
            val second = assertNotNull(ProjectSourceImageArchive.store(projectPath, "enemy:DCFF", "second.png", pngBytes(2)))

            assertNotEquals(first.relativePath, second.relativePath)
            assertTrue(File(root, first.relativePath).isFile)
            assertTrue(File(root, second.relativePath).isFile)
            assertEquals(2, File(root, "data/source-images/enemy_dcff").listFiles()?.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `content detection wins over a misleading file extension`() {
        val root = createTempDirectory("smedit-source-format-").toFile()
        try {
            val reference = assertNotNull(ProjectSourceImageArchive.store(
                File(root, "test.smedit").absolutePath,
                "tileset:cre",
                "tiles.bmp",
                pngBytes(3),
            ))

            assertTrue(reference.relativePath.endsWith(".png"))
            assertFalse(reference.relativePath.endsWith(".bmp"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `unsaved project has no archive destination`() {
        assertNull(ProjectSourceImageArchive.store("", "title", "title.png", pngBytes(4)))
    }

    private fun pngBytes(marker: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        marker.toByte(),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
