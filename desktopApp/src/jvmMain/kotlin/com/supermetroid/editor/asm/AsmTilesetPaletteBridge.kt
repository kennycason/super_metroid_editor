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
        val encodedSize: Int,
        val capacity: Int,
        val affectedTilesetIds: List<Int>,
        val modified: Boolean,
        val managedAssetId: String? = null,
        val managedSourceFileId: String? = null,
        val relocation: AsmAssetRelocationCandidate? = null,
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
        val workspace = repository.loadForRead(projectFilePath)
            ?: return (0 until TileGraphics.NUM_TILESETS).associateWith { tilesetId ->
                AsmTilesetPaletteAccess.AsmOnly(tilesetId, "Project ASM workspace is unavailable.")
            }
        val sources = readAsmSourceTree(File(workspace.workingDirectory, "src"))
        val resolutions = resolvePaletteAssets(sources, workspace.assets, AsmManagedAssets.load(workspace.root))
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
        val sourcePath = resolution.managed?.managedAssetPath ?: resolution.asset.path
        val source = File(workspace.workingDirectory, "data/$sourcePath")
        val original = File(workspace.originalDirectory, "data/${resolution.asset.path}")
        if (!source.isFile || source.length() <= 0L ||
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
            assetPath = sourcePath,
            rawPalette = decoded.data,
            baselineRawPalette = baseline.data,
            sourceBytes = sourceBytes,
            encodedSize = decoded.consumed,
            capacity = resolution.asset.length,
            affectedTilesetIds = resolutions.entries
                .filter { it.value.asset.path == resolution.asset.path }
                .map(Map.Entry<Int, PaletteAssetResolution>::key)
                .sorted(),
            modified = resolution.managed != null || !sourceBytes.contentEquals(originalBytes),
            managedAssetId = resolution.managed?.id,
            managedSourceFileId = resolution.managed?.sourceFileId,
            relocation = resolution.relocation,
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
        if (current.managedAssetId == null) {
            repository.saveAsset(projectFilePath, current.assetPath, sourceBytes, expectedSourceBytes)
        } else {
            repository.saveManagedAsset(projectFilePath, current.managedAssetId, sourceBytes, expectedSourceBytes)
        }
        return access(projectFilePath, tilesetId).editableOrThrow()
    }

    fun relocate(
        projectFilePath: String,
        tilesetId: Int,
        rawPalette: ByteArray,
        expectedSourceBytes: ByteArray,
    ): AsmTilesetPaletteAccess.Editable {
        require(rawPalette.size == PALETTE_BYTES) { "Tileset $tilesetId palette must contain $PALETTE_BYTES bytes" }
        val current = access(projectFilePath, tilesetId).editableOrThrow()
        require(current.managedAssetId == null) { "${current.assetPath} is already in managed project space" }
        require(current.sourceBytes.contentEquals(expectedSourceBytes)) {
            "${current.assetPath} changed after the palette was opened. Reload it before relocating."
        }
        val candidate = requireNotNull(current.relocation) {
            "${current.assetPath} does not have a safe direct source declaration to relocate"
        }
        val compressed = encodeUnbounded(tilesetId, rawPalette)
        val workspace = requireNotNull(repository.loadForRead(projectFilePath)) { "Project ASM workspace is unavailable" }
        repository.installManagedAsset(
            projectFilePath = projectFilePath,
            record = candidate.record(),
            bytes = compressed,
            expectedSourceText = File(workspace.workingDirectory, "src/${candidate.sourceFileId}").readText(),
        )
        return access(projectFilePath, tilesetId).editableOrThrow()
    }

    fun encode(access: AsmTilesetPaletteAccess.Editable, rawPalette: ByteArray): ByteArray {
        require(rawPalette.size == PALETTE_BYTES) {
            "Tileset ${access.tilesetId} palette must contain exactly $PALETTE_BYTES bytes"
        }
        val compressed = encodeUnbounded(access.tilesetId, rawPalette)
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

    private fun encodeUnbounded(tilesetId: Int, rawPalette: ByteArray): ByteArray =
        LZ5Compressor.compress(rawPalette).also { compressed ->
            require(LZ5Codec.decompress(compressed, maxOutputSize = PALETTE_BYTES).data.contentEquals(rawPalette)) {
                "Tileset $tilesetId palette failed LZ5 round-trip validation"
            }
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
        require(current.managedAssetId != null || sourceBytes.size == current.capacity) {
            "${current.assetPath} must remain exactly ${current.capacity} bytes until it is moved to project space"
        }
        val decoded = LZ5Codec.decompress(sourceBytes, maxOutputSize = PALETTE_BYTES)
        require(decoded.data.size == PALETTE_BYTES &&
            sourceBytes.copyOfRange(decoded.consumed, sourceBytes.size).all { it == 0xFF.toByte() }
        ) {
            "${current.assetPath} is not a supported fixed-envelope palette asset"
        }
        if (current.managedAssetId == null) {
            repository.saveAsset(projectFilePath, current.assetPath, sourceBytes, expectedSourceBytes)
        } else {
            repository.saveManagedAsset(projectFilePath, current.managedAssetId, sourceBytes, expectedSourceBytes)
        }
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
        if (current.managedAssetId == null) {
            repository.restoreAsset(projectFilePath, current.assetPath, expectedSourceBytes)
        } else {
            repository.restoreManagedAsset(projectFilePath, current.managedAssetId, expectedSourceBytes)
        }
        return access(projectFilePath, tilesetId).editableOrThrow()
    }

    private fun AsmTilesetPaletteAccess.editableOrThrow(): AsmTilesetPaletteAccess.Editable = when (this) {
        is AsmTilesetPaletteAccess.Editable -> this
        is AsmTilesetPaletteAccess.AsmOnly -> throw IllegalStateException(reason)
    }

    private data class PaletteAssetResolution(
        val targetLabel: String,
        val asset: AsmAssetRange,
        val relocation: AsmAssetRelocationCandidate?,
        val managed: AsmManagedAssetRecord? = null,
    )

    private data class SourceLine(
        val fileId: String,
        val lineIndex: Int,
        val lines: List<String>,
    )

    private fun resolvePaletteAssets(
        sources: Map<String, String>,
        assets: List<AsmAssetRange>,
        managed: AsmManagedAssetManifest,
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
        val paletteLabels = labels.mapNotNullTo(linkedSetOf()) { (label, definitions) ->
            val table = TILESET_TABLE_LABEL.matchEntire(label) ?: return@mapNotNullTo null
            val tilesetId = table.groupValues[1].toInt(16)
            if (tilesetId !in 0 until TileGraphics.NUM_TILESETS || definitions.size != 1) return@mapNotNullTo null
            followingStatements(definitions.single(), limit = 3).takeIf { it.size == 3 }
                ?.let { DATA_LONG.matchEntire(it[2])?.groupValues?.get(1) }
        }
        val pointerBindings = repository.pointerBindings(sources, paletteLabels)
        val result = linkedMapOf<Int, PaletteAssetResolution>()
        labels.forEach { (label, definitions) ->
            val table = TILESET_TABLE_LABEL.matchEntire(label) ?: return@forEach
            val tilesetId = table.groupValues[1].toInt(16)
            if (tilesetId !in 0 until TileGraphics.NUM_TILESETS || definitions.size != 1) return@forEach
            val fields = followingStatements(definitions.single(), limit = 3)
            if (fields.size != 3) return@forEach
            val paletteLabel = DATA_LONG.matchEntire(fields[2])?.groupValues?.get(1) ?: return@forEach
            managed.assets.singleOrNull { it.sourceLabel == paletteLabel }?.let { record ->
                val asset = assetsByPath[record.originalAssetPath] ?: return@forEach
                if (asset.pcOffset != record.originalPcOffset || asset.length != record.originalLength) return@forEach
                result[tilesetId] = PaletteAssetResolution(paletteLabel, asset, relocation = null, managed = record)
                return@forEach
            }
            val paletteDefinition = labels[paletteLabel]?.singleOrNull() ?: return@forEach
            val statement = followingStatement(paletteDefinition) ?: return@forEach
            val reference = DIRECT_INCBIN.matchEntire(statement.second)?.groupValues?.get(1) ?: return@forEach
            val path = normalizeAssetReference(reference)
            val asset = assetsByPath[path] ?: return@forEach
            val declaration = paletteDefinition.lines
                .subList(paletteDefinition.lineIndex, statement.first + 1).joinToString("\n")
            val id = AsmManagedAssets.stableId("tileset-palette", paletteLabel, asset.path)
            val bindings = pointerBindings[paletteLabel] ?: return@forEach
            result[tilesetId] = PaletteAssetResolution(
                paletteLabel,
                asset,
                AsmAssetRelocationCandidate(
                    id = id,
                    kind = "tileset-palette",
                    sourceFileId = paletteDefinition.fileId,
                    sourceLabel = paletteLabel,
                    originalAssetPath = asset.path,
                    managedAssetPath = "$ASM_MANAGED_ASSET_DATA_DIRECTORY/$id.bin",
                    originalPcOffset = asset.pcOffset,
                    originalLength = asset.length,
                    originalDeclaration = declaration,
                    pointerBindings = bindings,
                ),
            )
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

    private companion object {
        const val PALETTE_BYTES = 256
        val GLOBAL_LABEL = Regex("^([A-Za-z_][A-Za-z0-9_]*):$")
        val TILESET_TABLE_LABEL = Regex("^Tileset_Table_([0-9A-Fa-f]+)_[A-Za-z0-9_]+$")
        val DATA_LONG = Regex("^dl\\s+([A-Za-z_][A-Za-z0-9_]*)$", RegexOption.IGNORE_CASE)
        val DIRECT_INCBIN = Regex("^incbin\\s+\"([^\"]+)\"$", RegexOption.IGNORE_CASE)
    }
}
