package com.supermetroid.editor.emulator

import java.io.File
import java.security.MessageDigest

data class LsnesTape(
    val id: String,
    val displayName: String,
    val resourcePath: String,
    val sha256: String,
    val skipToFrame: Int? = null,
) {
    val fileName: String get() = resourcePath.substringAfterLast('/')
}

/** Bundled TASVideos Super Metroid tapes authored on lsnes. */
object LsnesTapes {
    val SNIQ_100 = LsnesTape(
        id = "sniq-100",
        displayName = "Sniq 100%",
        resourcePath = "lsnes/sniq_100_4010M.lsmv",
        sha256 = "1bd065d89b70c16efb6f9276e82b1c07fd57ec40095f030cc6efe6663458929c",
        skipToFrame = 8319,
    )
    val SNIQ_ANY = LsnesTape(
        id = "sniq-any",
        displayName = "Sniq any%",
        resourcePath = "lsnes/sniq_any_3653M.lsmv",
        sha256 = "e53b1ce15b3f01516eb64c68a6db2911f91bd97197eb05960779561be5c7cbe9",
    )

    fun all(): List<LsnesTape> = listOf(SNIQ_100, SNIQ_ANY)

    fun match(path: String?): LsnesTape? {
        val name = path?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it).name } ?: return null
        return all().firstOrNull { it.fileName.equals(name, ignoreCase = true) }
    }

    fun extract(
        tape: LsnesTape,
        userHome: String = System.getProperty("user.home", ""),
        classLoader: ClassLoader = LsnesTapes::class.java.classLoader,
    ): File {
        val dest = File(File(userHome, ".smedit/lsnes/tapes"), tape.fileName)
        if (dest.isFile && sha256Hex(dest.readBytes()) == tape.sha256) return dest
        dest.parentFile.mkdirs()
        val input = classLoader.getResourceAsStream(tape.resourcePath)
            ?: error("Bundled TAS tape missing: ${tape.resourcePath}")
        input.use { stream -> dest.outputStream().use { stream.copyTo(it) } }
        val actual = sha256Hex(dest.readBytes())
        check(actual == tape.sha256) {
            "Tape hash mismatch for ${tape.fileName}: $actual"
        }
        return dest
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
