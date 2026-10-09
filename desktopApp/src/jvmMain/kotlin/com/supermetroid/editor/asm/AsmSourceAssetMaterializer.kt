package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomWriteKind
import com.supermetroid.editor.rom.RomWritePlanReport
import java.io.File

internal data class AsmGeneratedAssetClaim(
    val owner: String,
    val label: String,
    val assetPath: String,
    val pcOffset: Int,
    val bytes: ByteArray,
) {
    val length: Int get() = bytes.size
    val endExclusive: Int get() = pcOffset + length

    override fun equals(other: Any?): Boolean =
        other is AsmGeneratedAssetClaim &&
            owner == other.owner &&
            label == other.label &&
            assetPath == other.assetPath &&
            pcOffset == other.pcOffset &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = owner.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + assetPath.hashCode()
        result = 31 * result + pcOffset
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

internal data class AsmAssetMaterialization(
    val assetOverrides: Map<String, ByteArray>,
    val claims: List<AsmGeneratedAssetClaim>,
    val eligibleWrites: Int,
    val skippedWrites: Int,
) {
    val isEmpty: Boolean get() = claims.isEmpty()

    companion object {
        val EMPTY = AsmAssetMaterialization(emptyMap(), emptyList(), 0, 0)
    }
}

/**
 * Projects fixed-range semantic ROM writes back into the exact `incbin` assets
 * that own those bytes. Writes without one complete source-asset owner remain
 * in the normal post-compile exporter; nothing is guessed or split across files.
 */
internal class AsmSourceAssetMaterializer(
    private val workspaceRepository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
    private val assetRanges: List<AsmAssetRange> = AsmAssetManifest.loadBundled(),
) {
    fun materialize(
        projectFilePath: String,
        report: RomWritePlanReport,
    ): AsmAssetMaterialization {
        val workspace = workspaceRepository.load(projectFilePath)
            ?: throw AsmCompilationException("Project ASM workspace is unavailable for asset generation")
        return materializeDataRoot(File(workspace.workingDirectory, "data"), report)
    }

    internal fun materializeDataRoot(
        dataRoot: File,
        report: RomWritePlanReport,
    ): AsmAssetMaterialization {
        val overrides = linkedMapOf<String, ByteArray>()
        val baselines = linkedMapOf<String, ByteArray>()
        var eligible = 0
        var skipped = 0
        val allocatedRanges = report.writes
            .filter { it.kind == RomWriteKind.ALLOCATION }
            .map { it.offset..it.endInclusive }

        report.writes.forEach { write ->
            if (write.kind !in MATERIALIZABLE_KINDS || write.owner == COMMUNITY_SAMUS_OWNER) return@forEach
            eligible++
            if (allocatedRanges.any { write.offset <= it.last && it.first <= write.endInclusive }) {
                skipped++
                return@forEach
            }
            val asset = assetRanges.singleOrNull { range ->
                write.offset >= range.pcOffset && write.endInclusive < range.endExclusive
            }
            if (asset == null) {
                skipped++
                return@forEach
            }
            val baselineFile = File(dataRoot, asset.path)
            if (!baselineFile.isFile || baselineFile.length() != asset.length.toLong()) {
                throw AsmCompilationException(
                    "Source asset ${asset.path} is missing or has the wrong size for ${write.owner}/${write.label}"
                )
            }
            val baseline = baselines.getOrPut(asset.path) { baselineFile.readBytes() }
            val bytes = overrides.getOrPut(asset.path) { baseline.copyOf() }
            val relativeStart = write.offset - asset.pcOffset
            write.bytes.forEachIndexed { index, value ->
                val target = relativeStart + index
                bytes[target] = value.toByte()
            }
        }

        val claims = overrides.flatMap { (path, bytes) ->
            val baseline = checkNotNull(baselines[path])
            val asset = checkNotNull(assetRanges.singleOrNull { it.path == path })
            val changed = bytes.indices.filter { baseline[it] != bytes[it] }
            val ranges = contiguousRanges(changed)
            ranges.mapIndexed { index, relativeRange ->
                AsmGeneratedAssetClaim(
                    owner = "asm-asset:$path",
                    label = "Generated source asset · $path" +
                        if (ranges.size > 1) " · ${index + 1}" else "",
                    assetPath = path,
                    pcOffset = asset.pcOffset + relativeRange.first,
                    bytes = bytes.copyOfRange(relativeRange.first, relativeRange.last + 1),
                )
            }
        }
        val usedPaths = claims.mapTo(linkedSetOf(), AsmGeneratedAssetClaim::assetPath)
        return AsmAssetMaterialization(
            assetOverrides = overrides.filterKeys { it in usedPaths },
            claims = claims,
            eligibleWrites = eligible,
            skippedWrites = skipped,
        )
    }

    private fun contiguousRanges(indices: List<Int>): List<IntRange> {
        if (indices.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        var start = indices.first()
        var previous = start
        for (index in indices.drop(1)) {
            if (index != previous + 1) {
                result += start..previous
                start = index
            }
            previous = index
        }
        result += start..previous
        return result
    }

    private companion object {
        const val COMMUNITY_SAMUS_OWNER = "graphics:community-samus"
        val MATERIALIZABLE_KINDS = setOf(
            RomWriteKind.GRAPHICS,
            RomWriteKind.MUSIC,
            RomWriteKind.TEXT,
            RomWriteKind.MINIMAP,
            RomWriteKind.ROOM,
        )
    }
}
