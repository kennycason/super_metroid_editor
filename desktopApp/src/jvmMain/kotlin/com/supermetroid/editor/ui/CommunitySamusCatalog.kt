package com.supermetroid.editor.ui

import com.supermetroid.editor.data.CommunitySamusInjectionArtifact
import com.supermetroid.editor.data.VANILLA_JU_SHA256
import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.rom.CommunitySamusSourceCodec
import com.supermetroid.editor.rom.MapRandoSamusCatalog
import com.supermetroid.editor.rom.MapRandoSamusSprite
import com.supermetroid.editor.rom.SamusCommunitySheetDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

internal data class CommunitySamusCatalogSnapshot(
    val revision: String,
    val sprites: List<MapRandoSamusSprite>,
    val usedCachedCatalog: Boolean = false,
    val notice: String? = null,
)

internal data class CommunitySamusCatalogDownload(
    val file: File,
    val sourceUrl: String,
    val usedCachedFile: Boolean,
)

internal data class CommunitySamusInjectionDownload(
    val artifact: CommunitySamusInjectionArtifact,
    val usedCachedFile: Boolean,
)

internal data class CommunitySamusCatalogShowcase(
    val images: List<SamusCommunitySheetDecoder.DecodedImage>,
) {
    val hasVisiblePixels: Boolean
        get() = images.any { image -> image.pixels.any { (it ushr 24) != 0 } }
}

/**
 * On-demand MapRandoSprites catalog/cache boundary.
 *
 * No community art ships with SMEDIT. The manifest and selected sheets are fetched from the
 * upstream repository at an exact commit, then kept in the user's global SMEDIT cache.
 */
internal class CommunitySamusCatalogRepository(
    private val cacheDirectory: File = File(
        File(System.getProperty("user.home"), ".smedit"),
        "cache/community-samus",
    ),
    private val fetchBytes: (String) -> ByteArray = ::fetchCommunityCatalogBytes,
    private val decoder: SamusCommunitySheetDecoder = SamusCommunitySheetDecoder(),
) {
    fun refresh(): CommunitySamusCatalogSnapshot {
        return try {
            val revisionPayload = fetchBytes(REVISION_URL).limited(MAX_MANIFEST_BYTES, "catalog revision")
            val revision = Json.parseToJsonElement(revisionPayload.decodeToString())
                .jsonObject.getValue("sha").jsonPrimitive.content
                .also(::requireRevision)
            val manifestUrl = manifestUrl(revision)
            val manifestBytes = fetchBytes(manifestUrl).limited(MAX_MANIFEST_BYTES, "catalog manifest")
            val manifestText = manifestBytes.decodeToString()
            val sprites = MapRandoSamusCatalog.parse(manifestText)
            require(sprites.isNotEmpty()) { "The community catalog contains no sprites" }
            writeCacheAtomically(File(cacheDirectory, MANIFEST_FILE), manifestBytes)
            writeCacheAtomically(File(cacheDirectory, REVISION_FILE), revision.toByteArray())
            CommunitySamusCatalogSnapshot(revision, sprites)
        } catch (problem: Exception) {
            loadCached()?.copy(
                usedCachedCatalog = true,
                notice = "Could not refresh the community catalog; showing the saved copy. " +
                    (problem.message ?: problem::class.simpleName),
            ) ?: throw IllegalStateException(
                "Could not download the community catalog and no saved copy is available: " +
                    (problem.message ?: problem::class.simpleName),
                problem,
            )
        }
    }

    fun loadCached(): CommunitySamusCatalogSnapshot? {
        val manifestFile = File(cacheDirectory, MANIFEST_FILE)
        val revisionFile = File(cacheDirectory, REVISION_FILE)
        if (!manifestFile.isFile || !revisionFile.isFile) return null
        return runCatching {
            val revision = revisionFile.readText().trim().also(::requireRevision)
            val sprites = MapRandoSamusCatalog.parse(manifestFile)
            require(sprites.isNotEmpty()) { "The saved community catalog contains no sprites" }
            CommunitySamusCatalogSnapshot(revision, sprites, usedCachedCatalog = true)
        }.getOrNull()
    }

    fun cachedFile(snapshot: CommunitySamusCatalogSnapshot, sprite: MapRandoSamusSprite): File {
        requireRevision(snapshot.revision)
        requireSpriteName(sprite.name)
        return File(File(cacheDirectory, "sheets/${snapshot.revision}"), "${sprite.name}.png")
    }

    fun isDownloaded(snapshot: CommunitySamusCatalogSnapshot, sprite: MapRandoSamusSprite): Boolean =
        cachedFile(snapshot, sprite).isFile

    /** Decode only the representative poses retained by the catalog card. */
    fun loadShowcase(
        snapshot: CommunitySamusCatalogSnapshot,
        sprite: MapRandoSamusSprite,
    ): CommunitySamusCatalogShowcase? {
        val file = cachedFile(snapshot, sprite)
        if (!file.isFile) return null
        val sheet = decoder.decode(file).sheet ?: return null
        val images = SHOWCASE_IMAGE_NAMES.mapNotNull(sheet.images::get)
        return if (images.isEmpty()) null else CommunitySamusCatalogShowcase(images)
    }

    fun download(
        snapshot: CommunitySamusCatalogSnapshot,
        sprite: MapRandoSamusSprite,
    ): CommunitySamusCatalogDownload {
        val output = cachedFile(snapshot, sprite)
        val url = spriteUrl(snapshot.revision, sprite.name)
        if (output.isFile) {
            val cachedBytes = runCatching { output.readBytes().limited(MAX_SHEET_BYTES, sprite.name) }.getOrNull()
            if (cachedBytes != null && decoder.decode(cachedBytes, output.name).isValid) {
                return CommunitySamusCatalogDownload(output, url, usedCachedFile = true)
            }
        }

        val bytes = fetchBytes(url).limited(MAX_SHEET_BYTES, "${sprite.displayName} sheet")
        val validation = decoder.decode(bytes, "${sprite.name}.png")
        require(validation.isValid) {
            "Downloaded ${sprite.displayName} is not a supported community sheet: " +
                validation.issues
                    .filter { it.severity == SamusCommunitySheetDecoder.Severity.ERROR }
                    .joinToString { it.message }
        }
        writeCacheAtomically(output, bytes)
        return CommunitySamusCatalogDownload(output, url, usedCachedFile = false)
    }

    fun sourceUrl(snapshot: CommunitySamusCatalogSnapshot, sprite: MapRandoSamusSprite): String =
        spriteUrl(snapshot.revision, sprite.name)

    /**
     * Fetch the exact Map Randomizer IPS generated for this catalog revision.
     * The provider repository pins MapRandoSprites as a submodule, so a later
     * catalog commit is intentionally source-only until SMEDIT verifies a new
     * matching provider revision.
     */
    fun downloadInjectionArtifact(
        snapshot: CommunitySamusCatalogSnapshot,
        sprite: MapRandoSamusSprite,
        sourceSheetSha256: String,
    ): CommunitySamusInjectionDownload {
        require(snapshot.revision.equals(INJECTABLE_CATALOG_REVISION, ignoreCase = true)) {
            "Catalog revision ${snapshot.revision.take(12)} does not have a verified Map Randomizer injector yet"
        }
        requireSpriteName(sprite.name)
        val output = cachedPatchFile(sprite.name)
        val url = patchUrl(sprite.name)
        var usedCachedFile = false
        val bytes = if (output.isFile) {
            runCatching { output.readBytes().limited(MAX_IPS_BYTES, "${sprite.displayName} IPS") }
                .getOrNull()
                ?.takeIf(::isValidIps)
                ?.also { usedCachedFile = true }
                ?: downloadAndCacheIps(url, output, sprite)
        } else {
            downloadAndCacheIps(url, output, sprite)
        }
        val artifact = CommunitySamusInjectionArtifact(
            formatId = CommunitySamusInjectionArtifact.MAP_RANDOMIZER_IPS_V1,
            ipsBase64 = java.util.Base64.getEncoder().encodeToString(bytes),
            sha256 = CommunitySamusSourceCodec.sha256(bytes),
            baseRomSha256 = VANILLA_JU_SHA256,
            baseRomSize = VANILLA_ROM_SIZE,
            outputRomSize = EXPANDED_ROM_SIZE,
            providerRevision = MAP_RANDOMIZER_REVISION,
            sourceSheetSha256 = sourceSheetSha256,
            sourceUrl = url,
        )
        return CommunitySamusInjectionDownload(artifact, usedCachedFile)
    }

    fun hasVerifiedInjector(snapshot: CommunitySamusCatalogSnapshot): Boolean =
        snapshot.revision.equals(INJECTABLE_CATALOG_REVISION, ignoreCase = true)

    private fun cachedPatchFile(spriteName: String): File =
        File(File(cacheDirectory, "patches/$MAP_RANDOMIZER_REVISION"), "$spriteName.ips")

    private fun downloadAndCacheIps(
        url: String,
        output: File,
        sprite: MapRandoSamusSprite,
    ): ByteArray {
        val bytes = fetchBytes(url).limited(MAX_IPS_BYTES, "${sprite.displayName} IPS")
        require(isValidIps(bytes)) { "Downloaded ${sprite.displayName} IPS is invalid" }
        writeCacheAtomically(output, bytes)
        return bytes
    }

    private fun isValidIps(bytes: ByteArray): Boolean = runCatching {
        val writes = PatchRepository.parseIps(bytes)
        writes.isNotEmpty() && writes.all { write ->
            write.offset >= 0 && write.offset + write.bytes.size <= EXPANDED_ROM_SIZE.toLong()
        }
    }.getOrDefault(false)

    private fun ByteArray.limited(maxBytes: Int, label: String): ByteArray {
        require(size <= maxBytes) { "$label is unexpectedly large ($size bytes; maximum $maxBytes)" }
        return this
    }

    private fun requireRevision(revision: String) {
        require(REVISION_REGEX.matches(revision)) { "Invalid catalog revision '$revision'" }
    }

    private fun requireSpriteName(name: String) {
        require(SPRITE_NAME_REGEX.matches(name)) { "Invalid catalog sprite name '$name'" }
    }

    private fun writeCacheAtomically(destination: File, bytes: ByteArray) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        temporary.writeBytes(bytes)
        try {
            runCatching {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        const val REVISION_URL = "https://api.github.com/repos/blkerby/MapRandoSprites/commits/main"
        private const val RAW_ROOT = "https://raw.githubusercontent.com/blkerby/MapRandoSprites"
        private const val MANIFEST_FILE = "manifest.json"
        private const val REVISION_FILE = "revision.txt"
        private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        private const val MAX_SHEET_BYTES = 12 * 1024 * 1024
        private const val MAX_IPS_BYTES = 2 * 1024 * 1024
        const val INJECTABLE_CATALOG_REVISION = "91fdbf43a4ccf41fc0bd4153eb98c5189bd38a25"
        const val MAP_RANDOMIZER_REVISION = "b243223ba3bafdb3223fe482aafbf4ca554b46c0"
        const val VANILLA_ROM_SIZE = 0x300000
        const val EXPANDED_ROM_SIZE = 0x400000
        private const val MAP_RANDOMIZER_RAW_ROOT = "https://raw.githubusercontent.com/blkerby/MapRandomizer"
        private val REVISION_REGEX = Regex("[0-9a-fA-F]{40}")
        private val SPRITE_NAME_REGEX = Regex("[a-zA-Z0-9_-]+")
        private val SHOWCASE_IMAGE_NAMES = listOf(
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
        )

        fun manifestUrl(revision: String): String =
            "$RAW_ROOT/$revision/samus_sprites/manifest.json"

        fun spriteUrl(revision: String, name: String): String =
            "$RAW_ROOT/$revision/samus_sprites/$name.png"

        fun patchUrl(name: String): String =
            "$MAP_RANDOMIZER_RAW_ROOT/$MAP_RANDOMIZER_REVISION/patches/samus_sprites/$name.ips"
    }
}

private fun fetchCommunityCatalogBytes(url: String): ByteArray {
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/vnd.github+json, image/png, application/json")
        .header("User-Agent", "SMEDIT-community-samus-catalog")
        .GET()
        .build()
    val response = CommunityCatalogHttp.client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    require(response.statusCode() in 200..299) {
        "${URI.create(url).host} returned HTTP ${response.statusCode()}"
    }
    return response.body()
}

private object CommunityCatalogHttp {
    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

internal fun communitySamusCatalogMatches(
    sprite: MapRandoSamusSprite,
    query: String,
    category: String?,
): Boolean {
    if (category != null && sprite.category != category) return false
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return true
    val searchable = buildString {
        append(sprite.displayName)
        append(' ')
        append(sprite.name)
        append(' ')
        append(sprite.category)
        append(' ')
        append(sprite.authors.joinToString(" "))
        sprite.creditsName?.let { append(' ').append(it) }
    }.lowercase()
    return terms.all(searchable::contains)
}
