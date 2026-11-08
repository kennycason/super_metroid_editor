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
        val capacity: Int,
        val affectedTilesetIds: List<Int>,
        val modified: Boolean,
    ) : AsmTilesetGraphicsAccess

    data class AsmOnly(
        override val key: AsmTilesetGraphicsKey,
        val reason: String,
    ) : AsmTilesetGraphicsAccess
}

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
        val workspace = repository.loadForBuild(projectFilePath)
            ?: return keys.associateWith { AsmTilesetGraphicsAccess.AsmOnly(it, "Project ASM workspace is unavailable.") }
        val sources = readAsmSourceTree(File(workspace.workingDirectory, "src"))
        val resolutions = resolveAssets(sources, workspace.assets)
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
        val source = File(workspace.workingDirectory, "data/${resolution.asset.path}")
        val original = File(workspace.originalDirectory, "data/${resolution.asset.path}")
        if (!source.isFile || source.length() != resolution.asset.length.toLong() ||
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
            assetPath = resolution.asset.path,
            rawData = decoded.data,
            baselineRawData = baseline.data,
            sourceBytes = sourceBytes,
            capacity = resolution.asset.length,
            affectedTilesetIds = aliases,
            modified = !sourceBytes.contentEquals(originalBytes),
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
        repository.saveAsset(projectFilePath, current.assetPath, sourceBytes, expectedSourceBytes)
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
        repository.restoreAsset(projectFilePath, current.assetPath, expectedSourceBytes)
        return access(projectFilePath, kind, tilesetId).editableOrThrow()
    }

    private fun encode(access: AsmTilesetGraphicsAccess.Editable, rawData: ByteArray): ByteArray {
        val compressed = LZ5Compressor.compress(rawData)
        require(LZ5Codec.decompress(compressed, maxOutputSize = rawLimit(access.key.kind)).data.contentEquals(rawData)) {
            "${access.assetPath} failed LZ5 round-trip validation"
        }
        require(compressed.size <= access.capacity) {
            "${access.assetPath} needs ${compressed.size} compressed bytes, but its source asset has a fixed " +
                "${access.capacity}-byte envelope. Edit its pointer/allocation in ASM."
        }
        return ByteArray(access.capacity) { 0xFF.toByte() }.also(compressed::copyInto)
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

    private data class AssetResolution(val asset: AsmAssetRange)
    private data class SourceLine(val lineIndex: Int, val lines: List<String>)

    private fun resolveAssets(
        sources: Map<String, String>,
        assets: List<AsmAssetRange>,
    ): Map<AsmTilesetGraphicsKey, AssetResolution> {
        val labels = linkedMapOf<String, MutableList<SourceLine>>()
        sources.values.forEach { text ->
            val lines = text.lines()
            lines.forEachIndexed { lineIndex, line ->
                GLOBAL_LABEL.matchEntire(asmExecutableCode(line).trim())?.groupValues?.get(1)?.let { label ->
                    labels.getOrPut(label) { mutableListOf() } += SourceLine(lineIndex, lines)
                }
            }
        }
        val assetsByPath = assets.associateBy(AsmAssetRange::path)
        fun direct(label: String): AssetResolution? {
            val definition = labels[label]?.singleOrNull() ?: return null
            val statement = followingStatements(definition, limit = 1).singleOrNull() ?: return null
            val reference = DIRECT_INCBIN.matchEntire(statement)?.groupValues?.get(1) ?: return null
            return assetsByPath[normalizeAssetReference(reference)]?.let(::AssetResolution)
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
