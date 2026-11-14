package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.CinematicMapAssets
import com.supermetroid.editor.rom.LZ5Codec
import com.supermetroid.editor.rom.LZ5Compressor
import java.io.File

internal sealed interface AsmCinematicMapAssetAccess {
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
        val modified: Boolean,
        val managedAssetId: String? = null,
        val relocation: AsmAssetRelocationCandidate? = null,
    ) : AsmCinematicMapAssetAccess

    data class AsmOnly(
        override val sourceKey: String,
        val reason: String,
    ) : AsmCinematicMapAssetAccess
}

internal data class AsmCinematicMapAssetSnapshot(
    val sourceKey: String,
    val sourceFileId: String,
    val assetPath: String,
    val rawData: ByteArray,
    val sourceBytes: ByteArray,
    val managedAssetId: String?,
)

internal fun AsmCinematicMapAssetAccess.Editable.snapshot(): AsmCinematicMapAssetSnapshot =
    AsmCinematicMapAssetSnapshot(
        sourceKey,
        sourceFileId,
        assetPath,
        rawData.copyOf(),
        sourceBytes.copyOf(),
        managedAssetId,
    )

/** Reciprocal source authority for the exact scene tilemaps selected by the Scenes workspace. */
internal class AsmCinematicMapAssetBridge(
    private val repository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
) {
    fun access(projectFilePath: String, sourceKey: String): AsmCinematicMapAssetAccess =
        accessAll(projectFilePath).getValue(sourceKey)

    fun accessAll(projectFilePath: String): Map<String, AsmCinematicMapAssetAccess> {
        val workspace = repository.loadForRead(projectFilePath)
            ?: return CinematicMapAssets.SUPPORTED.associate { asset ->
                asset.key to AsmCinematicMapAssetAccess.AsmOnly(asset.key, "Project ASM workspace is unavailable.")
            }
        val sources = readAsmSourceTree(File(workspace.workingDirectory, "src"))
        val managed = AsmManagedAssets.load(workspace.root)
        val resolutions = resolve(sources, workspace.assets, managed)
        return CinematicMapAssets.SUPPORTED.associate { asset ->
            asset.key to (resolutions[asset.key]?.let { editable(workspace, asset, it) }
                ?: AsmCinematicMapAssetAccess.AsmOnly(
                    asset.key,
                    "${asset.name} is not one direct manifest-backed source asset. Edit it in ASM.",
                ))
        }
    }

    fun save(
        projectFilePath: String,
        sourceKey: String,
        rawData: ByteArray,
        expectedSourceBytes: ByteArray,
    ): AsmCinematicMapAssetAccess.Editable {
        val current = access(projectFilePath, sourceKey).editableOrThrow()
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the scene layout was opened. Reload it before saving."
        }
        val asset = CinematicMapAssets.source(sourceKey)
        asset.validate(rawData)
        if (current.rawData.contentEquals(rawData)) return current
        val encoded = encode(current, asset, rawData)
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
    ): AsmCinematicMapAssetAccess.Editable {
        val current = access(projectFilePath, sourceKey).editableOrThrow()
        require(current.managedAssetId == null) { "${current.assetPath} is already in managed project space" }
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the scene layout was opened. Reload it before relocating."
        }
        val asset = CinematicMapAssets.source(sourceKey)
        asset.validate(rawData)
        val candidate = requireNotNull(current.relocation) {
            "${current.assetPath} has no complete supported pointer-consumer contract; edit it in ASM."
        }
        val encoded = encodeUnbounded(current.assetPath, asset, rawData)
        val workspace = requireNotNull(repository.loadForRead(projectFilePath)) {
            "Project ASM workspace is unavailable"
        }
        repository.installManagedAsset(
            projectFilePath,
            candidate.record(),
            encoded,
            File(workspace.workingDirectory, "src/${candidate.sourceFileId}").readText(),
        )
        return access(projectFilePath, sourceKey).editableOrThrow()
    }

    fun restore(
        projectFilePath: String,
        sourceKey: String,
        expectedSourceBytes: ByteArray,
    ): AsmCinematicMapAssetAccess.Editable {
        val current = access(projectFilePath, sourceKey).editableOrThrow()
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the scene layout was opened. Reload it before restoring."
        }
        if (current.managedAssetId == null) {
            repository.restoreAsset(projectFilePath, current.assetPath, expectedSourceBytes)
        } else {
            repository.restoreManagedAsset(projectFilePath, current.managedAssetId, expectedSourceBytes)
        }
        return access(projectFilePath, sourceKey).editableOrThrow()
    }

    fun applySnapshot(
        projectFilePath: String,
        target: AsmCinematicMapAssetSnapshot,
        expected: AsmCinematicMapAssetSnapshot,
    ): AsmCinematicMapAssetAccess.Editable {
        require(target.sourceKey == expected.sourceKey) { "Scene-map history keys do not match" }
        val current = access(projectFilePath, expected.sourceKey).editableOrThrow()
        require(current.sourceBytes.contentEquals(expected.sourceBytes) &&
            current.rawData.contentEquals(expected.rawData) &&
            current.managedAssetId == expected.managedAssetId
        ) {
            "${current.assetPath} changed after this visual edit. Reload it before Undo/Redo."
        }
        val asset = CinematicMapAssets.source(target.sourceKey)
        val decoded = requireNotNull(decode(asset, target.sourceBytes, target.managedAssetId == null)) {
            "${target.assetPath} history contains an invalid encoded scene map"
        }
        require(decoded.first.contentEquals(target.rawData)) {
            "${target.assetPath} history does not match its decoded scene map"
        }
        when {
            current.managedAssetId == null && target.managedAssetId == null ->
                repository.saveAsset(projectFilePath, current.assetPath, target.sourceBytes, current.sourceBytes)
            current.managedAssetId != null && target.managedAssetId != null -> {
                require(current.managedAssetId == target.managedAssetId) { "Managed scene-map ownership changed" }
                repository.saveManagedAsset(projectFilePath, current.managedAssetId, target.sourceBytes, current.sourceBytes)
            }
            current.managedAssetId == null && target.managedAssetId != null -> {
                val candidate = requireNotNull(current.relocation) {
                    "${current.assetPath} no longer has a complete managed relocation contract"
                }
                require(candidate.id == target.managedAssetId) { "Managed scene-map identity changed" }
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
        asset: CinematicMapAssets.Asset,
        resolution: Resolution,
    ): AsmCinematicMapAssetAccess {
        val sourcePath = resolution.managed?.managedAssetPath ?: resolution.asset.path
        val currentFile = File(workspace.workingDirectory, "data/$sourcePath")
        val originalFile = File(workspace.originalDirectory, "data/${resolution.asset.path}")
        if (!currentFile.isFile || currentFile.length() <= 0L ||
            !originalFile.isFile || originalFile.length() != resolution.asset.length.toLong()
        ) return AsmCinematicMapAssetAccess.AsmOnly(
            asset.key,
            "The source asset is missing or no longer matches its frozen manifest.",
        )
        val sourceBytes = currentFile.readBytes()
        val originalBytes = originalFile.readBytes()
        val decoded = decode(asset, sourceBytes, resolution.managed == null)
            ?: return AsmCinematicMapAssetAccess.AsmOnly(
                asset.key,
                "The scene map has an unsupported compressed shape. Edit it in ASM.",
            )
        val baseline = decode(asset, originalBytes, true)
            ?: return AsmCinematicMapAssetAccess.AsmOnly(
                asset.key,
                "The frozen scene map has an unsupported compressed shape. Edit it in ASM.",
            )
        return AsmCinematicMapAssetAccess.Editable(
            sourceKey = asset.key,
            sourceName = asset.name,
            sourceFileId = resolution.managed?.sourceFileId ?: resolution.sourceFileId,
            sourceLabel = resolution.managed?.sourceLabel ?: resolution.sourceLabel,
            assetPath = sourcePath,
            rawData = decoded.first,
            baselineRawData = baseline.first,
            sourceBytes = sourceBytes,
            encodedSize = decoded.second,
            capacity = resolution.asset.length,
            modified = resolution.managed != null || !sourceBytes.contentEquals(originalBytes),
            managedAssetId = resolution.managed?.id,
            relocation = resolution.relocation,
        )
    }

    private fun encode(
        access: AsmCinematicMapAssetAccess.Editable,
        asset: CinematicMapAssets.Asset,
        rawData: ByteArray,
    ): ByteArray {
        val encoded = encodeUnbounded(access.assetPath, asset, rawData)
        if (access.managedAssetId != null) return encoded
        if (encoded.size > access.capacity) {
            require(access.relocation != null) {
                "${access.assetPath} needs ${encoded.size} compressed bytes, but its frozen source envelope has " +
                    "${access.capacity} bytes and its pointer consumers are not a supported relocatable shape. Edit it in ASM."
            }
            throw AsmAssetRelocationRequiredException(access.assetPath, encoded.size, access.capacity)
        }
        return ByteArray(access.capacity) { 0xFF.toByte() }.also(encoded::copyInto)
    }

    private fun encodeUnbounded(
        assetPath: String,
        asset: CinematicMapAssets.Asset,
        rawData: ByteArray,
    ): ByteArray = LZ5Compressor.compress(rawData).also { encoded ->
        require(LZ5Codec.decompress(encoded, maxOutputSize = asset.rawBytes).data.contentEquals(rawData)) {
            "$assetPath failed LZ5 round-trip validation"
        }
    }

    private fun decode(
        asset: CinematicMapAssets.Asset,
        bytes: ByteArray,
        requireEnvelope: Boolean,
    ): Pair<ByteArray, Int>? {
        val decoded = runCatching { LZ5Codec.decompress(bytes, maxOutputSize = asset.rawBytes) }.getOrNull()
            ?: return null
        if (runCatching { asset.validate(decoded.data) }.isFailure) return null
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
        val pointerBindings = repository.pointerBindings(
            sources,
            CinematicMapAssets.SUPPORTED.mapTo(linkedSetOf()) { it.sourceLabel },
        )
        return CinematicMapAssets.SUPPORTED.mapNotNull { map ->
            val pc = snesLoRomToPc(map.address) ?: return@mapNotNull null
            managed.assets.singleOrNull { record ->
                record.kind == KIND && record.originalPcOffset == pc && record.sourceLabel == map.sourceLabel
            }?.let { record ->
                val asset = assetsByPath[record.originalAssetPath] ?: return@mapNotNull null
                return@mapNotNull map.key to Resolution(
                    asset,
                    record.sourceFileId,
                    record.sourceLabel,
                    relocation = null,
                    managed = record,
                )
            }
            val definition = labels[map.sourceLabel]?.singleOrNull() ?: return@mapNotNull null
            val statement = followingStatement(definition) ?: return@mapNotNull null
            val path = DIRECT_INCBIN.matchEntire(statement.second)?.groupValues?.get(1)
                ?.let(::normalizeAssetReference) ?: return@mapNotNull null
            val asset = assetsByPath[path]?.takeIf { it.pcOffset == pc } ?: return@mapNotNull null
            val declaration = definition.lines.subList(definition.lineIndex, statement.first + 1).joinToString("\n")
            val id = AsmManagedAssets.stableId(KIND, map.sourceLabel, asset.path)
            val relocation = pointerBindings[map.sourceLabel]?.let { bindings ->
                AsmAssetRelocationCandidate(
                    id,
                    KIND,
                    definition.fileId,
                    map.sourceLabel,
                    asset.path,
                    "$ASM_MANAGED_ASSET_DATA_DIRECTORY/$id.bin",
                    asset.pcOffset,
                    asset.length,
                    declaration,
                    bindings,
                )
            }
            map.key to Resolution(asset, definition.fileId, map.sourceLabel, relocation)
        }.toMap()
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

    private fun AsmCinematicMapAssetAccess.editableOrThrow(): AsmCinematicMapAssetAccess.Editable = when (this) {
        is AsmCinematicMapAssetAccess.Editable -> this
        is AsmCinematicMapAssetAccess.AsmOnly -> throw IllegalStateException(reason)
    }

    private companion object {
        const val KIND = "cinematic-map"
        val GLOBAL_LABEL = Regex("^([A-Za-z_][A-Za-z0-9_]*):$")
        val DIRECT_INCBIN = Regex("^incbin\\s+\"([^\"]+)\"$", RegexOption.IGNORE_CASE)
    }
}
