package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.LZ5Codec
import com.supermetroid.editor.rom.LZ5Compressor
import com.supermetroid.editor.rom.TileGraphics
import java.io.File

internal sealed interface AsmTilesetPaletteAccess {
    val tilesetId: Int

    data class Editable(
        override val tilesetId: Int,
        val assetPath: String,
        val rawPalette: ByteArray,
        /** Decoded palette from the project's frozen source baseline. */
        val baselineRawPalette: ByteArray,
        /** Exact compressed source bytes used for optimistic save/restore guards. */
        val sourceBytes: ByteArray,
        val capacity: Int,
        val affectedTilesetIds: List<Int>,
        val modified: Boolean,
    ) : AsmTilesetPaletteAccess

    data class AsmOnly(
        override val tilesetId: Int,
        val reason: String,
    ) : AsmTilesetPaletteAccess
}

/**
 * Reciprocal authority for the tileset palette editor and direct ASM `incbin`
 * assets. A palette is visual-editable only when its source table resolves to
 * one complete, fixed-size asset from the project's frozen manifest.
 */
internal class AsmTilesetPaletteBridge(
    private val repository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
) {
    fun access(projectFilePath: String, tilesetId: Int): AsmTilesetPaletteAccess {
        require(tilesetId in 0 until TileGraphics.NUM_TILESETS) {
            "Tileset ID $tilesetId is outside 0-${TileGraphics.NUM_TILESETS - 1}"
        }
        return accessAll(projectFilePath).getValue(tilesetId)
    }

    fun accessAll(projectFilePath: String): Map<Int, AsmTilesetPaletteAccess> {
        val workspace = repository.loadForBuild(projectFilePath)
            ?: return (0 until TileGraphics.NUM_TILESETS).associateWith { tilesetId ->
                AsmTilesetPaletteAccess.AsmOnly(tilesetId, "Project ASM workspace is unavailable.")
            }
        val sources = readAsmSourceTree(File(workspace.workingDirectory, "src"))
        val resolutions = resolvePaletteAssets(sources, workspace.assets)
        return (0 until TileGraphics.NUM_TILESETS).associateWith { tilesetId ->
            access(workspace, resolutions, tilesetId)
        }
    }

    private fun access(
        workspace: AsmProjectBuildWorkspace,
        resolutions: Map<Int, PaletteAssetResolution>,
        tilesetId: Int,
    ): AsmTilesetPaletteAccess {
        val resolution = resolutions[tilesetId]
            ?: return AsmTilesetPaletteAccess.AsmOnly(
                tilesetId,
                "This tileset's palette pointer is custom or is not a direct source asset. Edit it in ASM.",
            )
        val source = File(workspace.workingDirectory, "data/${resolution.asset.path}")
        val original = File(workspace.originalDirectory, "data/${resolution.asset.path}")
        if (!source.isFile || source.length() != resolution.asset.length.toLong() ||
            !original.isFile || original.length() != resolution.asset.length.toLong()
        ) {
            return AsmTilesetPaletteAccess.AsmOnly(
                tilesetId,
                "The source palette asset is missing or no longer matches its frozen manifest.",
            )
        }
        val sourceBytes = source.readBytes()
        val originalBytes = original.readBytes()
        val decoded = runCatching {
            LZ5Codec.decompress(sourceBytes, maxOutputSize = PALETTE_BYTES)
        }.getOrElse { problem ->
            return AsmTilesetPaletteAccess.AsmOnly(
                tilesetId,
                "The source palette asset is not a supported LZ5 stream: ${problem.message}",
            )
        }
        if (decoded.data.size != PALETTE_BYTES ||
            sourceBytes.copyOfRange(decoded.consumed, sourceBytes.size).any { it != 0xFF.toByte() }
        ) {
            return AsmTilesetPaletteAccess.AsmOnly(
                tilesetId,
                "The source palette asset has a custom shape. Edit it in ASM.",
            )
        }
        val baseline = runCatching {
            LZ5Codec.decompress(originalBytes, maxOutputSize = PALETTE_BYTES)
        }.getOrElse { problem ->
            return AsmTilesetPaletteAccess.AsmOnly(
                tilesetId,
                "The frozen source palette is not a supported LZ5 stream: ${problem.message}",
            )
        }
        if (baseline.data.size != PALETTE_BYTES ||
            originalBytes.copyOfRange(baseline.consumed, originalBytes.size).any { it != 0xFF.toByte() }
        ) {
            return AsmTilesetPaletteAccess.AsmOnly(
                tilesetId,
                "The frozen source palette has a custom shape. Edit it in ASM.",
            )
        }
        return AsmTilesetPaletteAccess.Editable(
            tilesetId = tilesetId,
            assetPath = resolution.asset.path,
            rawPalette = decoded.data,
            baselineRawPalette = baseline.data,
            sourceBytes = sourceBytes,
            capacity = resolution.asset.length,
            affectedTilesetIds = resolutions.entries
                .filter { it.value.asset.path == resolution.asset.path }
                .map(Map.Entry<Int, PaletteAssetResolution>::key)
                .sorted(),
            modified = !sourceBytes.contentEquals(originalBytes),
        )
    }

    fun save(
        projectFilePath: String,
        tilesetId: Int,
        rawPalette: ByteArray,
        expectedSourceBytes: ByteArray,
    ): AsmTilesetPaletteAccess.Editable {
        require(rawPalette.size == PALETTE_BYTES) {
            "Tileset $tilesetId palette must contain exactly $PALETTE_BYTES bytes"
        }
        val current = access(projectFilePath, tilesetId).editableOrThrow()
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the palette was opened. Reload it before saving."
        }
        if (current.rawPalette.contentEquals(rawPalette)) return current
        val sourceBytes = encode(current, rawPalette)
        repository.saveAsset(projectFilePath, current.assetPath, sourceBytes, expectedSourceBytes)
        return access(projectFilePath, tilesetId).editableOrThrow()
    }

    fun encode(access: AsmTilesetPaletteAccess.Editable, rawPalette: ByteArray): ByteArray {
        require(rawPalette.size == PALETTE_BYTES) {
            "Tileset ${access.tilesetId} palette must contain exactly $PALETTE_BYTES bytes"
        }
        val compressed = LZ5Compressor.compress(rawPalette)
        require(LZ5Codec.decompress(compressed, maxOutputSize = PALETTE_BYTES).data.contentEquals(rawPalette)) {
            "Tileset ${access.tilesetId} palette failed LZ5 round-trip validation"
        }
        require(compressed.size <= access.capacity) {
            "Tileset ${access.tilesetId} palette needs ${compressed.size} compressed bytes, but its source asset " +
                "${access.assetPath} has a fixed ${access.capacity}-byte envelope. Edit its pointer/allocation in ASM."
        }
        return ByteArray(access.capacity) { 0xFF.toByte() }.also(compressed::copyInto)
    }

    fun replaceSourceBytes(
        projectFilePath: String,
        tilesetId: Int,
        sourceBytes: ByteArray,
        expectedSourceBytes: ByteArray,
    ): AsmTilesetPaletteAccess.Editable {
        val current = access(projectFilePath, tilesetId).editableOrThrow()
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the palette transaction began. Reload it before continuing."
        }
        require(sourceBytes.size == current.capacity) {
            "${current.assetPath} must remain exactly ${current.capacity} bytes"
        }
        val decoded = LZ5Codec.decompress(sourceBytes, maxOutputSize = PALETTE_BYTES)
        require(decoded.data.size == PALETTE_BYTES &&
            sourceBytes.copyOfRange(decoded.consumed, sourceBytes.size).all { it == 0xFF.toByte() }
        ) {
            "${current.assetPath} is not a supported fixed-envelope palette asset"
        }
        repository.saveAsset(projectFilePath, current.assetPath, sourceBytes, expectedSourceBytes)
        return access(projectFilePath, tilesetId).editableOrThrow()
    }

    fun restore(
        projectFilePath: String,
        tilesetId: Int,
        expectedSourceBytes: ByteArray,
    ): AsmTilesetPaletteAccess.Editable {
        val current = access(projectFilePath, tilesetId).editableOrThrow()
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the palette was opened. Reload it before restoring."
        }
        repository.restoreAsset(projectFilePath, current.assetPath, expectedSourceBytes)
        return access(projectFilePath, tilesetId).editableOrThrow()
    }

    private fun AsmTilesetPaletteAccess.editableOrThrow(): AsmTilesetPaletteAccess.Editable = when (this) {
        is AsmTilesetPaletteAccess.Editable -> this
        is AsmTilesetPaletteAccess.AsmOnly -> throw IllegalStateException(reason)
    }

    private data class PaletteAssetResolution(
        val targetLabel: String,
        val asset: AsmAssetRange,
    )

    private data class SourceLine(
        val fileId: String,
        val lineIndex: Int,
        val lines: List<String>,
    )

    private fun resolvePaletteAssets(
        sources: Map<String, String>,
        assets: List<AsmAssetRange>,
    ): Map<Int, PaletteAssetResolution> {
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
        val result = linkedMapOf<Int, PaletteAssetResolution>()
        labels.forEach { (label, definitions) ->
            val table = TILESET_TABLE_LABEL.matchEntire(label) ?: return@forEach
            val tilesetId = table.groupValues[1].toInt(16)
            if (tilesetId !in 0 until TileGraphics.NUM_TILESETS || definitions.size != 1) return@forEach
            val fields = followingStatements(definitions.single(), limit = 3)
            if (fields.size != 3) return@forEach
            val paletteLabel = DATA_LONG.matchEntire(fields[2])?.groupValues?.get(1) ?: return@forEach
            val paletteDefinition = labels[paletteLabel]?.singleOrNull() ?: return@forEach
            val statement = followingStatements(paletteDefinition, limit = 1).singleOrNull() ?: return@forEach
            val reference = DIRECT_INCBIN.matchEntire(statement)?.groupValues?.get(1) ?: return@forEach
            val path = normalizeAssetReference(reference)
            val asset = assetsByPath[path] ?: return@forEach
            result[tilesetId] = PaletteAssetResolution(paletteLabel, asset)
        }
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

    private companion object {
        const val PALETTE_BYTES = 256
        val GLOBAL_LABEL = Regex("^([A-Za-z_][A-Za-z0-9_]*):$")
        val TILESET_TABLE_LABEL = Regex("^Tileset_Table_([0-9A-Fa-f]+)_[A-Za-z0-9_]+$")
        val DATA_LONG = Regex("^dl\\s+([A-Za-z_][A-Za-z0-9_]*)$", RegexOption.IGNORE_CASE)
        val DIRECT_INCBIN = Regex("^incbin\\s+\"([^\"]+)\"$", RegexOption.IGNORE_CASE)
    }
}
