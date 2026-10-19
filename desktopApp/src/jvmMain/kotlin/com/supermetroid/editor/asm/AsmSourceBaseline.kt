package com.supermetroid.editor.asm

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable source authority. Extracted ROM assets and build products remain outside this snapshot. */
@Serializable
internal data class AsmSourceBaseline(
    val formatVersion: Int = 2,
    val reference: AsmReferenceMetadata,
    val augmentationRevision: String,
    val upstreamSourceHashes: Map<String, String>,
    val sourceHashes: Map<String, String>,
    val assets: List<AsmAssetRange>,
    val symbolNames: Map<String, String> = emptyMap(),
    val upstreamLines: Map<String, List<Int>> = emptyMap(),
    /** Null is reserved for format-1 documents; new snapshots always freeze an explicit inventory. */
    val nativeSymbols: List<AsmNativeSymbol>? = null,
) {
    @Transient @Volatile private var cachedIdentity: String? = null
    val id: String get() = cachedIdentity ?: AsmVerifiedCache.hash(json.encodeToString(this)).also { cachedIdentity = it }
    val symbols: AsmSourceSymbolNames get() = AsmSourceSymbolNames(symbolNames)
    val bindings: AsmSourceBindings get() = AsmSourceBindings(symbols,
        nativeSymbols ?: AsmNativeSymbolManifest.load(reference.commit))

    /** Source validation uses exact bytes, including comments and line endings. */
    fun matches(sourceRoot: File): Boolean {
        val files = sourceRoot.walkTopDown().filter { it.isFile }.toList()
        if (files.map { it.relativeTo(sourceRoot).invariantSeparatorsPath }.toSet() != sourceHashes.keys) return false
        return files.all { AsmVerifiedCache.hash(it.readBytes()) == sourceHashes[it.relativeTo(sourceRoot).invariantSeparatorsPath] }
    }

    fun extract(asmRoot: File, sourceRoot: File) {
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(File(asmRoot, "$DIRECTORY/$ARCHIVE").inputStream().buffered()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                require(!entry.isDirectory && entry.name in sourceHashes && seen.add(entry.name)) { "Unexpected source snapshot entry ${entry.name}" }
                val bytes = archive.readNBytes(MAX_SOURCE_BYTES + 1)
                total += bytes.size
                require(bytes.size <= MAX_SOURCE_BYTES && total <= MAX_TREE_BYTES) { "ASM source snapshot is too large" }
                require(AsmVerifiedCache.hash(bytes) == sourceHashes.getValue(entry.name)) { "ASM source snapshot changed: ${entry.name}" }
                val target = File(sourceRoot, entry.name)
                target.parentFile.mkdirs()
                target.writeBytes(bytes)
            }
        }
        require(seen == sourceHashes.keys) { "ASM source snapshot is incomplete" }
    }

    companion object {
        const val DIRECTORY = "base"
        private const val MANIFEST = "manifest.json"
        private const val ARCHIVE = "source.zip"
        private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        private const val MAX_TREE_BYTES = 128L * 1024 * 1024
        private val json = Json { encodeDefaults = true }
        private data class CachedManifest(val hash: String, val baseline: AsmSourceBaseline)
        private val manifests = object : LinkedHashMap<String, CachedManifest>(6, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedManifest>?) = size > 6
        }

        fun load(asmRoot: File): AsmSourceBaseline? {
            val directory = File(asmRoot, DIRECTORY)
            if (!directory.exists()) return null
            val manifest = File(directory, MANIFEST)
            require(manifest.isFile && manifest.length() in 1..16L * 1024 * 1024 && File(directory, ARCHIVE).isFile) {
                "The project's frozen ASM baseline is incomplete"
            }
            // Always read/hash the manifest. Timestamp-only caches can conceal external edits.
            val text = manifest.readText()
            val hash = AsmVerifiedCache.hash(text)
            val key = manifest.canonicalPath
            synchronized(manifests) { manifests[key]?.takeIf { it.hash == hash }?.let { return it.baseline } }
            val baseline = json.decodeFromString(serializer(), text)
            // Identity belongs to the saved document, not a re-encoding under this app's defaults.
            baseline.cachedIdentity = hash
            require(baseline.formatVersion in 1..2 && baseline.augmentationRevision.isNotBlank() &&
                (baseline.formatVersion == 1 || baseline.nativeSymbols != null)) { "Unsupported ASM baseline format" }
            require("main.asm" in baseline.sourceHashes && baseline.sourceHashes.size <= 4096) { "Invalid ASM baseline source inventory" }
            baseline.sourceHashes.forEach { (fileId, hash) ->
                require(isAsmSourceFileId(fileId) && hash.matches(Regex("[a-f0-9]{64}"))) {
                    "Invalid ASM baseline entry $fileId"
                }
            }
            require(baseline.sourceHashes.keys.map { it.lowercase() }.distinct().size == baseline.sourceHashes.size) { "ASM baseline has case-ambiguous paths" }
            require(baseline.upstreamSourceHashes.keys == baseline.sourceHashes.keys &&
                baseline.upstreamSourceHashes.values.all { it.matches(Regex("[a-f0-9]{64}")) }) { "Invalid upstream ASM source inventory" }
            require(baseline.assets.size == baseline.reference.assetCount && baseline.assets.map { it.path }.distinct().size == baseline.assets.size) {
                "Invalid frozen ASM asset inventory"
            }
            require(baseline.assets.all { asset ->
                asset.path.matches(Regex("[A-Za-z0-9_./-]+")) && !asset.path.startsWith('/') &&
                    asset.path.split('/').none { it == "." || it == ".." || it.isEmpty() } &&
                    asset.pcOffset >= 0 && asset.length > 0 && asset.pcOffset.toLong() + asset.length <= baseline.reference.romSize
            }) { "Invalid frozen ASM asset range" }
            val symbol = Regex("[A-Za-z_][A-Za-z0-9_]*")
            require(baseline.upstreamLines.keys.all { it in baseline.sourceHashes } &&
                baseline.upstreamLines.values.all { lines -> lines.all { it >= -1 } } &&
                baseline.symbolNames.values.distinct().size == baseline.symbolNames.size &&
                baseline.symbolNames.all { (old, new) -> symbol.matches(old) && symbol.matches(new) }) {
                "Invalid ASM baseline source mappings"
            }
            validateAsmNativeSymbols(baseline.nativeSymbols.orEmpty())
            require(baseline.nativeSymbols.orEmpty().all { it.fileId in baseline.sourceHashes }) {
                "Invalid frozen native symbol inventory"
            }
            synchronized(manifests) { manifests[key] = CachedManifest(hash, baseline) }
            return baseline
        }

        fun create(asmRoot: File, reference: AsmReferenceWorkspace, augmentation: AsmSourceAugmentation,
                   assets: List<AsmAssetRange>, nativeSymbols: List<AsmNativeSymbol>? = null): AsmSourceBaseline {
            require(!File(asmRoot, DIRECTORY).exists()) { "A frozen ASM baseline already exists" }
            require(reference.metadata.commit == augmentation.upstreamRevision) { "ASM augmentation targets a different upstream revision" }
            val identities = nativeSymbols ?: AsmNativeSymbolManifest.load(reference.metadata.commit)
            validateAsmNativeSymbols(identities)
            val source = AsmSourceImage.capture(File(reference.root, "src"), reference.index, rebuildIncompleteIndex = true)
            val augmented = augmentation.apply(source, identities)
            val baseline = AsmSourceBaseline(
                reference = reference.metadata,
                augmentationRevision = augmentation.revision,
                upstreamSourceHashes = augmented.upstreamSourceHashes,
                sourceHashes = augmented.texts.mapValues { AsmVerifiedCache.hash(it.value) },
                assets = assets,
                symbolNames = augmented.symbolNames,
                upstreamLines = augmented.upstreamLines,
                nativeSymbols = identities.filter { native ->
                    source.index.resolveUniqueLabel(native.fileId, 0, native.name)?.fileId == native.fileId
                },
            )
            asmRoot.mkdirs()
            val staging = Files.createTempDirectory(asmRoot.toPath(), ".base-installing-").toFile()
            try {
                ZipOutputStream(File(staging, ARCHIVE).outputStream().buffered()).use { archive ->
                    augmented.texts.toSortedMap().forEach { (fileId, text) ->
                        archive.putNextEntry(ZipEntry(fileId).apply { time = 0 })
                        archive.write(text.toByteArray())
                        archive.closeEntry()
                    }
                }
                File(staging, MANIFEST).writeText(json.encodeToString(baseline))
                try {
                    Files.move(staging.toPath(), File(asmRoot, DIRECTORY).toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(staging.toPath(), File(asmRoot, DIRECTORY).toPath())
                }
            } finally {
                // Only this newly-created staging directory is disposable.
                staging.deleteRecursively()
            }
            return baseline
        }

        fun forOriginalDirectory(originalDirectory: File): AsmSourceBaseline? {
            // originalDirectory is <project>/smedit/asm/original.
            if (originalDirectory.name != "original" || originalDirectory.parentFile?.name != "asm" ||
                originalDirectory.parentFile?.parentFile?.name != "smedit") return null
            val projectRoot = originalDirectory.parentFile?.parentFile?.parentFile ?: return null
            return requireNotNull(load(File(projectRoot, "asm"))) { "The project's frozen ASM baseline is missing" }
        }

        fun symbolsFor(originalDirectory: File): AsmSourceSymbolNames = forOriginalDirectory(originalDirectory)?.symbols ?: AsmSourceSymbolNames()
    }
}
