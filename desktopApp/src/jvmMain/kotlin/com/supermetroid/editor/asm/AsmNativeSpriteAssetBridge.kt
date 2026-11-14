package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.LZ5Codec
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.NativeSpriteAssets
import java.io.File

internal sealed interface AsmNativeSpriteAssetAccess {
    val sourceKey: String

    data class Editable(
        override val sourceKey: String,
        val sourceName: String,
        val sourceFileId: String,
        val sourceLabel: String,
        val assetPath: String,
        val rawData: ByteArray,
        val baselineRawData: ByteArray,
        val sourceBytes: ByteArray,
        val encodedSize: Int,
        val capacity: Int,
        val compressed: Boolean,
        val modified: Boolean,
        val managedAssetId: String? = null,
        val relocation: AsmAssetRelocationCandidate? = null,
    ) : AsmNativeSpriteAssetAccess

    data class AsmOnly(
        override val sourceKey: String,
        val reason: String,
    ) : AsmNativeSpriteAssetAccess
}

internal data class AsmNativeSpriteAssetSnapshot(
    val sourceKey: String,
    val sourceFileId: String,
    val assetPath: String,
    val rawData: ByteArray,
    val sourceBytes: ByteArray,
    val managedAssetId: String?,
)

internal fun AsmNativeSpriteAssetAccess.Editable.snapshot(): AsmNativeSpriteAssetSnapshot =
    AsmNativeSpriteAssetSnapshot(
        sourceKey,
        sourceFileId,
        assetPath,
        rawData.copyOf(),
        sourceBytes.copyOf(),
        managedAssetId,
    )

/** Reciprocal source authority for the generic native-sprite pixel editor.
 * Only one direct manifest-backed `incbin` declaration is accepted. */
internal class AsmNativeSpriteAssetBridge(
    private val repository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
) {
    fun access(projectFilePath: String, sourceKey: String): AsmNativeSpriteAssetAccess =
        accessAll(projectFilePath).getValue(sourceKey)

    fun accessAll(projectFilePath: String): Map<String, AsmNativeSpriteAssetAccess> {
        val spriteSources = NativeSpriteAssets.SOURCES.associateBy(NativeSpriteAssets.Source::key)
        val workspace = repository.loadForRead(projectFilePath)
            ?: return spriteSources.mapValues { (key, _) ->
                AsmNativeSpriteAssetAccess.AsmOnly(key, "Project ASM workspace is unavailable.")
            }
        val sourceTree = readAsmSourceTree(File(workspace.workingDirectory, "src"))
        val managed = AsmManagedAssets.load(workspace.root)
        val resolutions = resolve(sourceTree, workspace.assets, managed)
        return spriteSources.mapValues { (key, source) ->
            val resolution = resolutions[key]
                ?: return@mapValues AsmNativeSpriteAssetAccess.AsmOnly(
                    key,
                    "${source.name} is not one direct manifest-backed source asset. Edit it in ASM.",
                )
            editable(workspace, source, resolution)
        }
    }

    fun save(
        projectFilePath: String,
        sourceKey: String,
        rawData: ByteArray,
        expectedSourceBytes: ByteArray,
    ): AsmNativeSpriteAssetAccess.Editable {
        val current = access(projectFilePath, sourceKey).editableOrThrow()
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the sprite source was opened. Reload it before saving."
        }
        validateRaw(current, rawData)
        if (current.rawData.contentEquals(rawData)) return current
        val encoded = encode(current, rawData)
        if (current.managedAssetId == null) {
            repository.saveAsset(projectFilePath, current.assetPath, encoded, expectedSourceBytes)
        } else {
            repository.saveManagedAsset(projectFilePath, current.managedAssetId, encoded, expectedSourceBytes)
        }
        return access(projectFilePath, sourceKey).editableOrThrow()
    }

    fun relocate(
        projectFilePath: String,
        sourceKey: String,
        rawData: ByteArray,
        expectedSourceBytes: ByteArray,
    ): AsmNativeSpriteAssetAccess.Editable {
        val current = access(projectFilePath, sourceKey).editableOrThrow()
        require(current.managedAssetId == null) { "${current.assetPath} is already in managed project space" }
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the sprite source was opened. Reload it before relocating."
        }
        validateRaw(current, rawData)
        val candidate = requireNotNull(current.relocation) {
            "${current.assetPath} has no complete supported pointer-consumer contract; edit it in ASM."
        }
        val encoded = encodeUnbounded(current, rawData)
        val workspace = requireNotNull(repository.loadForRead(projectFilePath)) {
            "Project ASM workspace is unavailable"
        }
        repository.installManagedAsset(
            projectFilePath = projectFilePath,
            record = candidate.record(),
            bytes = encoded,
            expectedSourceText = File(workspace.workingDirectory, "src/${candidate.sourceFileId}").readText(),
        )
        return access(projectFilePath, sourceKey).editableOrThrow()
    }

    fun restore(
        projectFilePath: String,
        sourceKey: String,
        expectedSourceBytes: ByteArray,
    ): AsmNativeSpriteAssetAccess.Editable {
        val current = access(projectFilePath, sourceKey).editableOrThrow()
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the sprite source was opened. Reload it before restoring."
        }
        if (current.managedAssetId == null) {
            repository.restoreAsset(projectFilePath, current.assetPath, expectedSourceBytes)
        } else {
            repository.restoreManagedAsset(projectFilePath, current.managedAssetId, expectedSourceBytes)
        }
        return access(projectFilePath, sourceKey).editableOrThrow()
    }

    /** Applies an exact previously observed authority snapshot for guarded
     * visual Undo/Redo, including direct ↔ managed ownership transitions. */
    fun applySnapshot(
        projectFilePath: String,
        target: AsmNativeSpriteAssetSnapshot,
        expected: AsmNativeSpriteAssetSnapshot,
    ): AsmNativeSpriteAssetAccess.Editable {
        require(target.sourceKey == expected.sourceKey) { "Sprite source history keys do not match" }
        val current = access(projectFilePath, expected.sourceKey).editableOrThrow()
        require(current.sourceBytes.contentEquals(expected.sourceBytes) &&
            current.rawData.contentEquals(expected.rawData) &&
            current.managedAssetId == expected.managedAssetId
        ) {
            "${current.assetPath} changed after this visual edit. Reload it before Undo/Redo."
        }
        val source = NativeSpriteAssets.source(target.sourceKey)
        val decoded = requireNotNull(decode(source, target.sourceBytes, requireEnvelope = target.managedAssetId == null)) {
            "${target.assetPath} history contains an invalid encoded sprite asset"
        }
        require(decoded.first.contentEquals(target.rawData)) {
            "${target.assetPath} history does not match its decoded sprite pixels"
        }
        when {
            current.managedAssetId == null && target.managedAssetId == null ->
                repository.saveAsset(projectFilePath, current.assetPath, target.sourceBytes, current.sourceBytes)
            current.managedAssetId != null && target.managedAssetId != null -> {
                require(current.managedAssetId == target.managedAssetId) { "Managed sprite ownership changed" }
                repository.saveManagedAsset(
                    projectFilePath,
                    current.managedAssetId,
                    target.sourceBytes,
                    current.sourceBytes,
                )
            }
            current.managedAssetId == null && target.managedAssetId != null -> {
                val candidate = requireNotNull(current.relocation) {
                    "${current.assetPath} no longer has a complete managed relocation contract"
                }
                require(candidate.id == target.managedAssetId) { "Managed sprite identity changed" }
                val workspace = requireNotNull(repository.loadForRead(projectFilePath)) {
                    "Project ASM workspace is unavailable"
                }
                repository.installManagedAsset(
                    projectFilePath,
                    candidate.record(),
                    target.sourceBytes,
                    File(workspace.workingDirectory, "src/${candidate.sourceFileId}").readText(),
                )
            }
            else -> repository.restoreManagedAsset(
                projectFilePath,
                requireNotNull(current.managedAssetId),
                current.sourceBytes,
                replacementOriginalBytes = target.sourceBytes,
            )
        }
        return access(projectFilePath, target.sourceKey).editableOrThrow()
    }

    private fun editable(
        workspace: AsmProjectBuildWorkspace,
        source: NativeSpriteAssets.Source,
        resolution: Resolution,
    ): AsmNativeSpriteAssetAccess {
        val sourcePath = resolution.managed?.managedAssetPath ?: resolution.asset.path
        val assetFile = File(workspace.workingDirectory, "data/$sourcePath")
        val originalFile = File(workspace.originalDirectory, "data/${resolution.asset.path}")
        if (!assetFile.isFile || assetFile.length() <= 0L ||
            !originalFile.isFile || originalFile.length() != resolution.asset.length.toLong()
        ) return AsmNativeSpriteAssetAccess.AsmOnly(
            source.key,
            "The source asset is missing or no longer matches its frozen manifest.",
        )
        val sourceBytes = assetFile.readBytes()
        val originalBytes = originalFile.readBytes()
        val decoded = decode(source, sourceBytes, requireEnvelope = resolution.managed == null)
            ?: return AsmNativeSpriteAssetAccess.AsmOnly(
                source.key,
                "The source asset has an unsupported encoded shape. Edit it in ASM.",
            )
        val baseline = decode(source, originalBytes, requireEnvelope = true)
            ?: return AsmNativeSpriteAssetAccess.AsmOnly(
                source.key,
                "The frozen source asset has an unsupported encoded shape. Edit it in ASM.",
            )
        if (decoded.first.size != baseline.first.size) return AsmNativeSpriteAssetAccess.AsmOnly(
            source.key,
            "The source asset changed decoded size. Edit it in ASM.",
        )
        return AsmNativeSpriteAssetAccess.Editable(
            sourceKey = source.key,
            sourceName = source.name,
            sourceFileId = resolution.managed?.sourceFileId ?: resolution.sourceFileId,
            sourceLabel = resolution.managed?.sourceLabel ?: resolution.sourceLabel,
            assetPath = sourcePath,
            rawData = decoded.first,
            baselineRawData = baseline.first,
            sourceBytes = sourceBytes,
            encodedSize = decoded.second,
            capacity = resolution.asset.length,
            compressed = source.compressed,
            modified = resolution.managed != null || !sourceBytes.contentEquals(originalBytes),
            managedAssetId = resolution.managed?.id,
            relocation = resolution.relocation,
        )
    }

    private fun validateRaw(access: AsmNativeSpriteAssetAccess.Editable, rawData: ByteArray) {
        require(rawData.size == access.rawData.size) {
            "${access.assetPath} must keep its ${access.rawData.size}-byte decoded shape; received ${rawData.size} bytes."
        }
        val recordBytes = if (NativeSpriteAssets.source(access.sourceKey).mode7) 64 else 32
        require(rawData.isNotEmpty() && rawData.size % recordBytes == 0) {
            "${access.assetPath} must contain complete $recordBytes-byte tiles."
        }
    }

    private fun encode(access: AsmNativeSpriteAssetAccess.Editable, rawData: ByteArray): ByteArray {
        val encoded = encodeUnbounded(access, rawData)
        if (access.managedAssetId != null || !access.compressed) return encoded
        if (encoded.size > access.capacity) {
            require(access.relocation != null) {
                "${access.assetPath} needs ${encoded.size} compressed bytes, but its frozen source envelope has " +
                    "${access.capacity} bytes and its pointer consumers are not a supported relocatable shape. Edit it in ASM."
            }
            throw AsmAssetRelocationRequiredException(access.assetPath, encoded.size, access.capacity)
        }
        return ByteArray(access.capacity) { 0xFF.toByte() }.also(encoded::copyInto)
    }

    private fun encodeUnbounded(access: AsmNativeSpriteAssetAccess.Editable, rawData: ByteArray): ByteArray {
        if (!access.compressed) return rawData.copyOf()
        return LZ5Compressor.compress(rawData).also { encoded ->
            require(LZ5Codec.decompress(encoded, maxOutputSize = rawData.size).data.contentEquals(rawData)) {
                "${access.assetPath} failed LZ5 round-trip validation"
            }
        }
    }

    private fun decode(
        source: NativeSpriteAssets.Source,
        bytes: ByteArray,
        requireEnvelope: Boolean,
    ): Pair<ByteArray, Int>? {
        if (!source.compressed) {
            return bytes.takeIf { it.size == source.bytes }?.let { it.copyOf() to it.size }
        }
        val decoded = runCatching { LZ5Codec.decompress(bytes) }.getOrNull() ?: return null
        val tileBytes = if (source.mode7) 64 else 32
        if (decoded.data.isEmpty() || decoded.data.size % tileBytes != 0) return null
        if (requireEnvelope && bytes.copyOfRange(decoded.consumed, bytes.size).any { it != 0xFF.toByte() }) return null
        if (!requireEnvelope && decoded.consumed != bytes.size) return null
        return decoded.data to decoded.consumed
    }

    private data class SourceLine(val fileId: String, val lineIndex: Int, val lines: List<String>)
    private data class Resolution(
        val asset: AsmAssetRange,
        val sourceFileId: String,
        val sourceLabel: String,
        val relocation: AsmAssetRelocationCandidate?,
        val managed: AsmManagedAssetRecord? = null,
    )

    private fun resolve(
        sources: Map<String, String>,
        assets: List<AsmAssetRange>,
        managed: AsmManagedAssetManifest,
    ): Map<String, Resolution> {
        val labels = linkedMapOf<String, MutableList<SourceLine>>()
        sources.forEach { (fileId, text) ->
            val lines = text.lines()
            lines.forEachIndexed { index, line ->
                GLOBAL_LABEL.matchEntire(asmExecutableCode(line).trim())?.groupValues?.get(1)?.let { label ->
                    labels.getOrPut(label) { mutableListOf() } += SourceLine(fileId, index, lines)
                }
            }
        }
        val assetsByPath = assets.associateBy(AsmAssetRange::path)
        data class DirectDeclaration(val label: String, val definition: SourceLine, val statementLine: Int)
        val declarationsByAsset = linkedMapOf<AsmAssetRange, MutableList<DirectDeclaration>>()
        labels.forEach { (label, definitions) ->
            val definition = definitions.singleOrNull() ?: return@forEach
            val statement = followingStatement(definition) ?: return@forEach
            val path = DIRECT_INCBIN.matchEntire(statement.second)?.groupValues?.get(1)
                ?.let(::normalizeAssetReference) ?: return@forEach
            val asset = assetsByPath[path] ?: return@forEach
            declarationsByAsset.getOrPut(asset) { mutableListOf() } +=
                DirectDeclaration(label, definition, statement.first)
        }
        data class Candidate(
            val source: NativeSpriteAssets.Source,
            val asset: AsmAssetRange,
            val declaration: DirectDeclaration,
        )
        val result = linkedMapOf<String, Resolution>()
        val candidates = mutableListOf<Candidate>()
        NativeSpriteAssets.SOURCES.forEach { source ->
            val pc = snesLoRomToPc(source.address) ?: return@forEach
            val asset = assets.singleOrNull { it.pcOffset == pc } ?: return@forEach
            managed.assets.singleOrNull { record ->
                record.kind == KIND && record.originalPcOffset == asset.pcOffset &&
                    record.originalAssetPath == asset.path
            }?.let { record ->
                result[source.key] = Resolution(
                    asset, record.sourceFileId, record.sourceLabel, relocation = null, managed = record,
                )
                return@forEach
            }
            val declaration = declarationsByAsset[asset]?.singleOrNull() ?: return@forEach
            candidates += Candidate(source, asset, declaration)
        }
        val pointerBindings = repository.pointerBindings(
            sources,
            candidates.filter { it.source.compressed }.mapTo(linkedSetOf()) { it.declaration.label },
        )
        candidates.forEach { (source, asset, direct) ->
            val declaration = direct.definition.lines
                .subList(direct.definition.lineIndex, direct.statementLine + 1).joinToString("\n")
            val label = direct.label
            val id = AsmManagedAssets.stableId(KIND, label, asset.path)
            val relocation = if (source.compressed) {
                pointerBindings[label]?.let { bindings ->
                    AsmAssetRelocationCandidate(
                        id = id,
                        kind = KIND,
                        sourceFileId = direct.definition.fileId,
                        sourceLabel = label,
                        originalAssetPath = asset.path,
                        managedAssetPath = "$ASM_MANAGED_ASSET_DATA_DIRECTORY/$id.bin",
                        originalPcOffset = asset.pcOffset,
                        originalLength = asset.length,
                        originalDeclaration = declaration,
                        pointerBindings = bindings,
                    )
                }
            } else null
            result[source.key] = Resolution(asset, direct.definition.fileId, label, relocation)
        }
        return result
    }

    private fun followingStatement(definition: SourceLine): Pair<Int, String>? {
        for (index in definition.lineIndex + 1 until definition.lines.size) {
            val code = asmExecutableCode(definition.lines[index]).trim()
            if (code.isBlank()) continue
            if (GLOBAL_LABEL.matches(code)) return null
            return index to code
        }
        return null
    }

    private fun normalizeAssetReference(reference: String): String = reference.replace('\\', '/')
        .substringAfterLast("/data/")
        .removePrefix("data/")
        .removePrefix("../data/")
        .removePrefix("./data/")

    private fun AsmNativeSpriteAssetAccess.editableOrThrow(): AsmNativeSpriteAssetAccess.Editable = when (this) {
        is AsmNativeSpriteAssetAccess.Editable -> this
        is AsmNativeSpriteAssetAccess.AsmOnly -> throw IllegalStateException(reason)
    }

    private companion object {
        const val KIND = "native-sprite-graphics"
        val GLOBAL_LABEL = Regex("^([A-Za-z_][A-Za-z0-9_]*):$")
        val DIRECT_INCBIN = Regex("^incbin\\s+\"([^\"]+)\"$", RegexOption.IGNORE_CASE)
    }
}
