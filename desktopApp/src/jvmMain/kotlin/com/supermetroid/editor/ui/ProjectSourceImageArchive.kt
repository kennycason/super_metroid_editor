package com.supermetroid.editor.ui

import com.supermetroid.editor.data.ProjectPaths
import com.supermetroid.editor.data.ProjectSourceImageReference
import java.io.File
import java.security.MessageDigest

/** Exact imported-image provenance. No preview or build path reads these files. */
internal object ProjectSourceImageArchive {
    fun store(
        projectFilePath: String,
        assetKey: String,
        originalFileName: String,
        bytes: ByteArray,
    ): ProjectSourceImageReference? {
        if (projectFilePath.isBlank()) return null
        require(assetKey.isNotBlank()) { "Source-image asset identity is required" }
        require(bytes.isNotEmpty()) { "Imported image is empty" }

        val paths = ProjectPaths.forProject(projectFilePath)
        require(paths.root.isDirectory) { "Project directory does not exist: ${paths.root}" }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val extension = imageExtension(bytes, originalFileName)
        val assetDirectory = File(paths.sourceImages, safeAssetName(assetKey))
        require(assetDirectory.mkdirs() || assetDirectory.isDirectory) {
            "Could not create source-image directory: $assetDirectory"
        }
        val target = File(assetDirectory, "$sha256.$extension")
        require(target.canonicalFile.toPath().startsWith(paths.sourceImages.canonicalFile.toPath())) {
            "Source-image path escaped the project"
        }
        if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
            writeBytesAtomically(target, bytes)
        }
        return ProjectSourceImageReference(
            relativePath = target.relativeTo(paths.root).invariantSeparatorsPath,
            originalFileName = portableFileName(originalFileName, extension),
            sha256 = sha256,
            byteSize = bytes.size,
        )
    }

    fun store(projectFilePath: String, assetKey: String, file: File): ProjectSourceImageReference? =
        store(projectFilePath, assetKey, file.name, file.readBytes())

    private fun safeAssetName(assetKey: String): String = assetKey.lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .trim('.', '_', '-')
        .take(96)
        .ifBlank { "image" }

    private fun portableFileName(originalFileName: String, extension: String): String = originalFileName
        .replace('\\', '/')
        .substringAfterLast('/')
        .takeIf { it.isNotBlank() }
        ?: "source-image.$extension"

    private fun imageExtension(bytes: ByteArray, originalFileName: String): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        ) -> "png"
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "bmp"
        bytes.size >= 6 && bytes.copyOfRange(0, 3).contentEquals("GIF".encodeToByteArray()) -> "gif"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
        else -> File(originalFileName).extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "img"
    }
}
