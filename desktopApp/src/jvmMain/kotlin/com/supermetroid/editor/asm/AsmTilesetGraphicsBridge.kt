package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.LZ5Codec
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.TileGraphics
import java.io.File

internal enum class AsmTilesetGraphicsKind {
    AREA_TILES,
    AREA_METATILES,
    COMMON_TILES,
    COMMON_METATILES,
}

internal data class AsmTilesetGraphicsKey(
    val kind: AsmTilesetGraphicsKind,
    val tilesetId: Int? = null,
)

internal sealed interface AsmTilesetGraphicsAccess {
    val key: AsmTilesetGraphicsKey

    data class Editable(
        override val key: AsmTilesetGraphicsKey,
        val assetPath: String,
        val rawData: ByteArray,
        val baselineRawData: ByteArray,
        /** Exact compressed source bytes used for optimistic save/restore guards. */
        val sourceBytes: ByteArray,
        val encodedSize: Int,
        val capacity: Int,
        val affectedTilesetIds: List<Int>,
        val modified: Boolean,
        val managedAssetId: String? = null,
        val managedSourceFileId: String? = null,
        val relocation: AsmAssetRelocationCandidate? = null,
    ) : AsmTilesetGraphicsAccess

    data class AsmOnly(
        override val key: AsmTilesetGraphicsKey,
        val reason: String,
    ) : AsmTilesetGraphicsAccess
}

internal data class AsmAssetRelocationCandidate(
    val id: String,
    val kind: String,
    val sourceFileId: String,
    val sourceLabel: String,
    val originalAssetPath: String,
    val managedAssetPath: String,
    val originalPcOffset: Int,
    val originalLength: Int,
    val originalDeclaration: String,
    val pointerBindings: List<AsmManagedPointerBinding>,
) {
    fun record(): AsmManagedAssetRecord = AsmManagedAssetRecord(
        id = id,
        kind = kind,
        sourceFileId = sourceFileId,
        sourceLabel = sourceLabel,
        originalAssetPath = originalAssetPath,
        managedAssetPath = managedAssetPath,
        originalPcOffset = originalPcOffset,
        originalLength = originalLength,
        originalDeclaration = originalDeclaration,
        pointerBindings = pointerBindings,
    )
}

internal class AsmAssetRelocationRequiredException(
    val assetPath: String,
    val requiredBytes: Int,
    val inPlaceCapacity: Int,
) : IllegalArgumentException(
    "$assetPath needs $requiredBytes compressed bytes, but its frozen source envelope has $inPlaceCapacity bytes. " +
        "Move it to checked project space to keep editing visually.",
)

/**
 * Reciprocal authority for visual tileset graphics/metatile editors and their
 * direct ASM `incbin` assets. Visual saves preserve the frozen asset path,
 * decompressed shape and compressed envelope; structural changes stay ASM-only.
 */
internal class AsmTilesetGraphicsBridge(
    private val repository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
) {
    fun access(
        projectFilePath: String,
        kind: AsmTilesetGraphicsKind,
        tilesetId: Int? = null,
    ): AsmTilesetGraphicsAccess {
        val key = key(kind, tilesetId)
        return accessAll(projectFilePath).getValue(key)
    }

    fun accessAll(projectFilePath: String): Map<AsmTilesetGraphicsKey, AsmTilesetGraphicsAccess> {
        val keys = allKeys()
        val workspace = repository.loadForRead(projectFilePath)
            ?: return keys.associateWith { AsmTilesetGraphicsAccess.AsmOnly(it, "Project ASM workspace is unavailable.") }
        val sources = readAsmSourceTree(File(workspace.workingDirectory, "src"))
        val managed = AsmManagedAssets.load(workspace.root)
        val resolutions = resolveAssets(sources, workspace.assets, managed)
        return keys.associateWith { key ->
            val resolution = resolutions[key]
                ?: return@associateWith AsmTilesetGraphicsAccess.AsmOnly(
                    key,
                    if (key.tilesetId == null) {
                        "The common source pointer is custom or is not a direct source asset. Edit it in ASM."
                    } else {
                        "This tileset's source pointer is custom or is not a direct source asset. Edit it in ASM."
                    },
                )
            access(workspace, key, resolution, resolutions)
        }
    }

    private fun access(
        workspace: AsmProjectBuildWorkspace,
        key: AsmTilesetGraphicsKey,
        resolution: AssetResolution,
        resolutions: Map<AsmTilesetGraphicsKey, AssetResolution>,
    ): AsmTilesetGraphicsAccess {
        val sourcePath = resolution.managed?.managedAssetPath ?: resolution.asset.path
        val source = File(workspace.workingDirectory, "data/$sourcePath")
        val original = File(workspace.originalDirectory, "data/${resolution.asset.path}")
        if (!source.isFile || source.length() <= 0L ||
            !original.isFile || original.length() != resolution.asset.length.toLong()
        ) {
            return AsmTilesetGraphicsAccess.AsmOnly(
                key,
                "The source asset is missing or no longer matches its frozen manifest.",
            )
        }
        val sourceBytes = source.readBytes()
        val originalBytes = original.readBytes()
        val decoded = decodeFixedEnvelope(key, sourceBytes)
            ?: return AsmTilesetGraphicsAccess.AsmOnly(key, "The source asset has an unsupported LZ5 shape. Edit it in ASM.")
        val baseline = decodeFixedEnvelope(key, originalBytes)
            ?: return AsmTilesetGraphicsAccess.AsmOnly(key, "The frozen source asset has an unsupported LZ5 shape. Edit it in ASM.")
        if (decoded.data.size != baseline.data.size) {
            return AsmTilesetGraphicsAccess.AsmOnly(
                key,
                "The source asset changed decompressed size. Edit it in ASM.",
            )
        }
        val aliases = if (key.tilesetId == null) {
            (0 until TileGraphics.NUM_TILESETS).toList()
        } else {
            resolutions.entries.asSequence()
                .filter { (candidate, value) -> candidate.kind == key.kind && value.asset.path == resolution.asset.path }
                .mapNotNull { it.key.tilesetId }
                .sorted()
                .toList()
        }
        return AsmTilesetGraphicsAccess.Editable(
            key = key,
            assetPath = sourcePath,
            rawData = decoded.data,
            baselineRawData = baseline.data,
            sourceBytes = sourceBytes,
            encodedSize = decoded.consumed,
            capacity = resolution.asset.length,
            affectedTilesetIds = aliases,
            modified = resolution.managed != null || !sourceBytes.contentEquals(originalBytes),
            managedAssetId = resolution.managed?.id,
            managedSourceFileId = resolution.managed?.sourceFileId,
            relocation = resolution.relocation,
        )
    }

    fun save(
        projectFilePath: String,
        kind: AsmTilesetGraphicsKind,
        tilesetId: Int?,
        rawData: ByteArray,
        expectedSourceBytes: ByteArray,
    ): AsmTilesetGraphicsAccess.Editable {
        val current = access(projectFilePath, kind, tilesetId).editableOrThrow()
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the visual resource was opened. Reload it before saving."
        }
        require(rawData.size == current.rawData.size) {
            "${current.assetPath} must keep its ${current.rawData.size}-byte decompressed shape; received ${rawData.size} bytes."
        }
        validateRaw(current.key, rawData)
        if (current.rawData.contentEquals(rawData)) return current
        val sourceBytes = encode(current, rawData)
        if (current.managedAssetId == null) {
            repository.saveAsset(projectFilePath, current.assetPath, sourceBytes, expectedSourceBytes)
        } else {
            repository.saveManagedAsset(projectFilePath, current.managedAssetId, sourceBytes, expectedSourceBytes)
        }
        return access(projectFilePath, kind, tilesetId).editableOrThrow()
    }

    fun relocate(
        projectFilePath: String,
        kind: AsmTilesetGraphicsKind,
        tilesetId: Int?,
        rawData: ByteArray,
        expectedSourceBytes: ByteArray,
    ): AsmTilesetGraphicsAccess.Editable {
        val current = access(projectFilePath, kind, tilesetId).editableOrThrow()
        require(current.managedAssetId == null) { "${current.assetPath} is already in managed project space" }
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the visual resource was opened. Reload it before relocating."
        }
        require(rawData.size == current.rawData.size) {
            "${current.assetPath} must keep its ${current.rawData.size}-byte decompressed shape"
        }
        validateRaw(current.key, rawData)
        val candidate = requireNotNull(current.relocation) {
            "${current.assetPath} does not have a safe direct source declaration to relocate"
        }
        val compressed = encodeUnbounded(current.assetPath, current.key, rawData)
        repository.installManagedAsset(
            projectFilePath = projectFilePath,
            record = candidate.record(),
            bytes = compressed,
            expectedSourceText = File(
                requireNotNull(repository.loadForRead(projectFilePath)).workingDirectory,
                "src/${candidate.sourceFileId}",
            ).readText(),
        )
        return access(projectFilePath, kind, tilesetId).editableOrThrow()
    }

    fun restore(
        projectFilePath: String,
        kind: AsmTilesetGraphicsKind,
        tilesetId: Int?,
        expectedSourceBytes: ByteArray,
    ): AsmTilesetGraphicsAccess.Editable {
        val current = access(projectFilePath, kind, tilesetId).editableOrThrow()
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the visual resource was opened. Reload it before restoring."
        }
        if (current.managedAssetId == null) {
            repository.restoreAsset(projectFilePath, current.assetPath, expectedSourceBytes)
        } else {
            repository.restoreManagedAsset(projectFilePath, current.managedAssetId, expectedSourceBytes)
        }
        return access(projectFilePath, kind, tilesetId).editableOrThrow()
    }

    private fun encode(access: AsmTilesetGraphicsAccess.Editable, rawData: ByteArray): ByteArray {
        val compressed = encodeUnbounded(access.assetPath, access.key, rawData)
        if (access.managedAssetId != null) return compressed
        if (compressed.size > access.capacity) {
            require(access.relocation != null) {
                "${access.assetPath} needs ${compressed.size} compressed bytes, but its frozen source envelope has " +
                    "${access.capacity} bytes and its pointer consumers are not a supported relocatable shape. Edit it in ASM."
            }
            throw AsmAssetRelocationRequiredException(access.assetPath, compressed.size, access.capacity)
        }
        return ByteArray(access.capacity) { 0xFF.toByte() }.also(compressed::copyInto)
    }

    private fun encodeUnbounded(assetPath: String, key: AsmTilesetGraphicsKey, rawData: ByteArray): ByteArray =
        LZ5Compressor.compress(rawData).also { compressed ->
            require(LZ5Codec.decompress(compressed, maxOutputSize = rawLimit(key.kind)).data.contentEquals(rawData)) {
                "$assetPath failed LZ5 round-trip validation"
            }
        }

    private fun decodeFixedEnvelope(
        key: AsmTilesetGraphicsKey,
        bytes: ByteArray,
    ): LZ5Codec.DecodeResult? = runCatching {
        LZ5Codec.decompress(bytes, maxOutputSize = rawLimit(key.kind))
    }.getOrNull()?.takeIf { decoded ->
        runCatching { validateRaw(key, decoded.data) }.isSuccess &&
            bytes.copyOfRange(decoded.consumed, bytes.size).all { it == 0xFF.toByte() }
    }

    private fun validateRaw(key: AsmTilesetGraphicsKey, rawData: ByteArray) {
        val multiple = when (key.kind) {
            AsmTilesetGraphicsKind.AREA_TILES,
            AsmTilesetGraphicsKind.COMMON_TILES -> RomConstants.BYTES_PER_4BPP_TILE
            AsmTilesetGraphicsKind.AREA_METATILES,
            AsmTilesetGraphicsKind.COMMON_METATILES -> 8
        }
        require(rawData.isNotEmpty() && rawData.size % multiple == 0 && rawData.size <= rawLimit(key.kind)) {
            "${key.kind} requires 1-${rawLimit(key.kind)} bytes in complete $multiple-byte records"
        }
    }

    private fun rawLimit(kind: AsmTilesetGraphicsKind): Int = when (kind) {
        AsmTilesetGraphicsKind.AREA_TILES -> TileGraphics.ROOM_GFX_MAX_BYTES
        AsmTilesetGraphicsKind.AREA_METATILES -> TileGraphics.CERES_VAR_TILE_TABLE_MAX_BYTES
        AsmTilesetGraphicsKind.COMMON_TILES -> TileGraphics.CRE_GFX_MAX_BYTES
        AsmTilesetGraphicsKind.COMMON_METATILES -> TileGraphics.CRE_TILE_TABLE_MAX_BYTES
    }

    private fun AsmTilesetGraphicsAccess.editableOrThrow(): AsmTilesetGraphicsAccess.Editable = when (this) {
        is AsmTilesetGraphicsAccess.Editable -> this
        is AsmTilesetGraphicsAccess.AsmOnly -> throw IllegalStateException(reason)
    }

    private data class AssetResolution(
        val asset: AsmAssetRange,
        val relocation: AsmAssetRelocationCandidate?,
        val managed: AsmManagedAssetRecord? = null,
    )
    private data class SourceLine(val fileId: String, val lineIndex: Int, val lines: List<String>)

    private fun resolveAssets(
        sources: Map<String, String>,
        assets: List<AsmAssetRange>,
        managed: AsmManagedAssetManifest,
    ): Map<AsmTilesetGraphicsKey, AssetResolution> {
        val labels = linkedMapOf<String, MutableList<SourceLine>>()
        sources.forEach { (fileId, text) ->
            val lines = text.lines()
            lines.forEachIndexed { lineIndex, line ->
                GLOBAL_LABEL.matchEntire(asmExecutableCode(line).trim())?.groupValues?.get(1)?.let { label ->
                    labels.getOrPut(label) { mutableListOf() } += SourceLine(fileId, lineIndex, lines)
                }
            }
        }
        val assetsByPath = assets.associateBy(AsmAssetRange::path)
        val directLabels = buildSet {
            add(CRE_TILES_LABEL)
            add(CRE_METATILES_LABEL)
            labels.forEach { (label, definitions) ->
                val table = TILESET_TABLE_LABEL.matchEntire(label) ?: return@forEach
                val tilesetId = table.groupValues[1].toInt(16)
                if (tilesetId !in 0 until TileGraphics.NUM_TILESETS || definitions.size != 1) return@forEach
                val fields = followingStatements(definitions.single(), limit = 3)
                if (fields.size == 3) fields.take(2).mapNotNullTo(this) {
                    DATA_LONG.matchEntire(it)?.groupValues?.get(1)
                }
            }
        }
        val pointerBindings = repository.pointerBindings(sources, directLabels)
        fun direct(label: String): AssetResolution? {
            managed.assets.singleOrNull { it.sourceLabel == label }?.let { record ->
                val asset = assetsByPath[record.originalAssetPath] ?: return null
                if (asset.pcOffset != record.originalPcOffset || asset.length != record.originalLength) return null
                return AssetResolution(asset, relocation = null, managed = record)
            }
            val definition = labels[label]?.singleOrNull() ?: return null
            val statement = followingStatement(definition) ?: return null
            val reference = DIRECT_INCBIN.matchEntire(statement.second)?.groupValues?.get(1) ?: return null
            val asset = assetsByPath[normalizeAssetReference(reference)] ?: return null
            val declaration = definition.lines.subList(definition.lineIndex, statement.first + 1).joinToString("\n")
            val id = AsmManagedAssets.stableId("tileset-graphics", label, asset.path)
            val bindings = pointerBindings[label]
            return AssetResolution(
                asset = asset,
                relocation = bindings?.let { AsmAssetRelocationCandidate(
                    id = id,
                    kind = "tileset-graphics",
                    sourceFileId = definition.fileId,
                    sourceLabel = label,
                    originalAssetPath = asset.path,
                    managedAssetPath = "$ASM_MANAGED_ASSET_DATA_DIRECTORY/$id.bin",
                    originalPcOffset = asset.pcOffset,
                    originalLength = asset.length,
                    originalDeclaration = declaration,
                    pointerBindings = bindings,
                ) },
            )
        }
        val result = linkedMapOf<AsmTilesetGraphicsKey, AssetResolution>()
        labels.forEach { (label, definitions) ->
            val table = TILESET_TABLE_LABEL.matchEntire(label) ?: return@forEach
            val tilesetId = table.groupValues[1].toInt(16)
            if (tilesetId !in 0 until TileGraphics.NUM_TILESETS || definitions.size != 1) return@forEach
            val fields = followingStatements(definitions.single(), limit = 3)
            if (fields.size != 3) return@forEach
            val tableLabel = DATA_LONG.matchEntire(fields[0])?.groupValues?.get(1)
            val tilesLabel = DATA_LONG.matchEntire(fields[1])?.groupValues?.get(1)
            tableLabel?.let(::direct)?.let { result[AsmTilesetGraphicsKey(AsmTilesetGraphicsKind.AREA_METATILES, tilesetId)] = it }
            tilesLabel?.let(::direct)?.let { result[AsmTilesetGraphicsKey(AsmTilesetGraphicsKind.AREA_TILES, tilesetId)] = it }
        }
        direct(CRE_TILES_LABEL)?.let { result[AsmTilesetGraphicsKey(AsmTilesetGraphicsKind.COMMON_TILES)] = it }
        direct(CRE_METATILES_LABEL)?.let { result[AsmTilesetGraphicsKey(AsmTilesetGraphicsKind.COMMON_METATILES)] = it }
        return result
    }

    private fun followingStatements(definition: SourceLine, limit: Int): List<String> {
        val statements = mutableListOf<String>()
        for (index in definition.lineIndex + 1 until definition.lines.size) {
            val code = asmExecutableCode(definition.lines[index]).trim()
            if (code.isBlank()) continue
            if (GLOBAL_LABEL.matches(code)) break
            statements += code
            if (statements.size == limit) break
        }
        return statements
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

    private fun key(kind: AsmTilesetGraphicsKind, tilesetId: Int?): AsmTilesetGraphicsKey {
        val normalized = when (kind) {
            AsmTilesetGraphicsKind.AREA_TILES,
            AsmTilesetGraphicsKind.AREA_METATILES -> requireNotNull(tilesetId) { "Area resources require a tileset ID" }
                .also { require(it in 0 until TileGraphics.NUM_TILESETS) { "Unsupported tileset ID $it" } }
            AsmTilesetGraphicsKind.COMMON_TILES,
            AsmTilesetGraphicsKind.COMMON_METATILES -> null
        }
        return AsmTilesetGraphicsKey(kind, normalized)
    }

    private fun allKeys(): List<AsmTilesetGraphicsKey> = buildList {
        for (tilesetId in 0 until TileGraphics.NUM_TILESETS) {
            add(AsmTilesetGraphicsKey(AsmTilesetGraphicsKind.AREA_TILES, tilesetId))
            add(AsmTilesetGraphicsKey(AsmTilesetGraphicsKind.AREA_METATILES, tilesetId))
        }
        add(AsmTilesetGraphicsKey(AsmTilesetGraphicsKind.COMMON_TILES))
        add(AsmTilesetGraphicsKey(AsmTilesetGraphicsKind.COMMON_METATILES))
    }

    private companion object {
        const val CRE_TILES_LABEL = "CRE_Tiles_Compressed"
        const val CRE_METATILES_LABEL = "CRE_TileTable_Compressed"
        val GLOBAL_LABEL = Regex("^([A-Za-z_][A-Za-z0-9_]*):$")
        val TILESET_TABLE_LABEL = Regex("^Tileset_Table_([0-9A-Fa-f]+)_[A-Za-z0-9_]+$")
        val DATA_LONG = Regex("^dl\\s+([A-Za-z_][A-Za-z0-9_]*)$", RegexOption.IGNORE_CASE)
        val DIRECT_INCBIN = Regex("^incbin\\s+\"([^\"]+)\"$", RegexOption.IGNORE_CASE)
    }
}
