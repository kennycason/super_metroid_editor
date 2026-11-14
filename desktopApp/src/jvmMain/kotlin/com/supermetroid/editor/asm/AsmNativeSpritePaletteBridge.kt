package com.supermetroid.editor.asm

import java.io.File

internal sealed interface AsmNativeSpritePaletteAccess {
    val address: Int

    data class Editable(
        override val address: Int,
        val sourceFileId: String,
        val rawPalette: ByteArray,
        val baselineRawPalette: ByteArray,
        val sourceText: String,
        val modified: Boolean,
    ) : AsmNativeSpritePaletteAccess

    data class AsmOnly(
        override val address: Int,
        val reason: String,
    ) : AsmNativeSpritePaletteAccess
}

internal data class AsmNativeSpritePaletteSnapshot(
    val address: Int,
    val sourceFileId: String,
    val rawPalette: ByteArray,
    val sourceText: String,
)

internal fun AsmNativeSpritePaletteAccess.Editable.snapshot(): AsmNativeSpritePaletteSnapshot =
    AsmNativeSpritePaletteSnapshot(address, sourceFileId, rawPalette.copyOf(), sourceText)

/**
 * Fixed-size reciprocal lens for native 16-colour palette rows. A row remains
 * visual-editable only while all 16 words are literal `dw $hhhh` values with
 * contiguous six-digit ROM-address comments. Labels and blank/comment lines
 * may divide the row; expressions, macros, missing comments, or extra emitted
 * data fail closed instead of being rewritten heuristically.
 */
internal class AsmNativeSpritePaletteBridge(
    private val repository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
) {
    fun access(projectFilePath: String, address: Int): AsmNativeSpritePaletteAccess =
        accessAll(projectFilePath, setOf(address)).getValue(address)

    fun accessAll(projectFilePath: String, addresses: Set<Int>): Map<Int, AsmNativeSpritePaletteAccess> {
        if (addresses.isEmpty()) return emptyMap()
        addresses.forEach { address ->
            require(address in 0x800000..0xFFFFFF) { "Native palette address must be a 24-bit ROM address" }
        }
        val workspace = repository.loadForRead(projectFilePath)
            ?: return addresses.associateWith { address ->
                AsmNativeSpritePaletteAccess.AsmOnly(address, "Project ASM workspace is unavailable.")
            }
        val currentSources = readAsmSourceTree(File(workspace.workingDirectory, "src"))
        val baselineSources = readAsmSourceTree(File(workspace.originalDirectory, "src"))
        val current = resolveAll(currentSources, addresses)
        val baseline = resolveAll(baselineSources, addresses)
        return addresses.associateWith { address ->
            access(currentSources, address, current.getValue(address), baseline.getValue(address))
        }
    }

    private fun access(
        currentSources: Map<String, String>,
        baselineSources: Map<String, String>,
        address: Int,
    ): AsmNativeSpritePaletteAccess = access(
        currentSources,
        address,
        resolve(currentSources, address),
        resolve(baselineSources, address),
    )

    private fun access(
        currentSources: Map<String, String>,
        address: Int,
        current: Resolution?,
        baseline: Resolution?,
    ): AsmNativeSpritePaletteAccess {
        require(address in 0x800000..0xFFFFFF) { "Native palette address must be a 24-bit ROM address" }
        current ?: return AsmNativeSpritePaletteAccess.AsmOnly(
                address,
                "This palette row is not one exact 16-colour literal ASM table. Edit it in ASM.",
            )
        baseline ?: return AsmNativeSpritePaletteAccess.AsmOnly(
                address,
                "The frozen palette row is not one exact 16-colour literal ASM table. Edit it in ASM.",
            )
        if (current.fileId != baseline.fileId) {
            return AsmNativeSpritePaletteAccess.AsmOnly(
                address,
                "This palette row moved to a custom source file. Edit it in ASM.",
            )
        }
        return AsmNativeSpritePaletteAccess.Editable(
            address = address,
            sourceFileId = current.fileId,
            rawPalette = current.bytes,
            baselineRawPalette = baseline.bytes,
            sourceText = currentSources.getValue(current.fileId),
            modified = !current.bytes.contentEquals(baseline.bytes),
        )
    }

    fun save(
        projectFilePath: String,
        address: Int,
        rawPalette: ByteArray,
        expectedSourceText: String,
    ): AsmNativeSpritePaletteAccess.Editable {
        require(rawPalette.size == PALETTE_BYTES) { "A native palette row requires exactly $PALETTE_BYTES bytes" }
        val current = access(projectFilePath, address).editableOrThrow()
        require(current.sourceText == expectedSourceText) {
            "${current.sourceFileId} changed after the palette was opened. Reload it before saving."
        }
        if (current.rawPalette.contentEquals(rawPalette)) return current
        val resolution = requireNotNull(resolve(mapOf(current.sourceFileId to current.sourceText), address))
        repository.persistSource(
            projectFilePath,
            current.sourceFileId,
            render(current.sourceText, resolution, rawPalette),
            expectedSourceText,
        )
        return access(projectFilePath, address).editableOrThrow()
    }

    fun restore(
        projectFilePath: String,
        address: Int,
        expectedSourceText: String,
    ): AsmNativeSpritePaletteAccess.Editable {
        val current = access(projectFilePath, address).editableOrThrow()
        require(current.sourceText == expectedSourceText) {
            "${current.sourceFileId} changed after the palette was opened. Reload it before restoring."
        }
        if (!current.modified) return current
        val resolution = requireNotNull(resolve(mapOf(current.sourceFileId to current.sourceText), address))
        repository.persistSource(
            projectFilePath,
            current.sourceFileId,
            render(current.sourceText, resolution, current.baselineRawPalette),
            expectedSourceText,
        )
        return access(projectFilePath, address).editableOrThrow()
    }

    fun applySnapshot(
        projectFilePath: String,
        target: AsmNativeSpritePaletteSnapshot,
        expected: AsmNativeSpritePaletteSnapshot,
    ): AsmNativeSpritePaletteAccess.Editable {
        require(target.address == expected.address && target.sourceFileId == expected.sourceFileId) {
            "Palette source history identity changed"
        }
        val current = access(projectFilePath, expected.address).editableOrThrow()
        require(current.sourceFileId == expected.sourceFileId &&
            current.sourceText == expected.sourceText &&
            current.rawPalette.contentEquals(expected.rawPalette)
        ) {
            "${current.sourceFileId} changed after this visual palette edit. Reload it before Undo/Redo."
        }
        val targetResolution = requireNotNull(resolve(
            mapOf(target.sourceFileId to target.sourceText),
            target.address,
        )) { "The saved palette history no longer has one exact literal row" }
        require(targetResolution.bytes.contentEquals(target.rawPalette)) {
            "The saved palette history does not match its literal row"
        }
        repository.persistSource(
            projectFilePath,
            target.sourceFileId,
            target.sourceText,
            expected.sourceText,
        )
        return access(projectFilePath, target.address).editableOrThrow()
    }

    private data class Directive(
        val lineIndex: Int,
        val wordRanges: List<IntRange>,
        val address: Int,
        val words: List<Int>,
    )

    private data class Resolution(
        val fileId: String,
        val directives: List<Directive>,
        val bytes: ByteArray,
    )

    private data class ParsedSource(
        val fileId: String,
        val lines: List<String>,
        val directives: List<Directive>,
    )

    private fun resolve(sources: Map<String, String>, address: Int): Resolution? =
        resolveAll(sources, setOf(address)).getValue(address)

    /** Parse each bank once for a complete palette request. The former
     * address-first loop split and parsed the multi-megabyte source tree once
     * per palette row, blocking the Compose UI thread during project open. */
    private fun resolveAll(
        sources: Map<String, String>,
        addresses: Set<Int>,
    ): Map<Int, Resolution?> {
        val candidates = addresses.associateWith { mutableListOf<Resolution>() }
        sources.forEach { (fileId, text) ->
            val lines = text.split('\n')
            val parsed = lines.mapIndexedNotNull { index, line -> parseDirective(index, line.removeSuffix("\r")) }
            val indicesByAddress = parsed.withIndex()
                .filter { it.value.address in addresses }
                .groupBy({ it.value.address }, { it.index })
            indicesByAddress.forEach { (address, indices) ->
                if (indices.size == 1) resolve(ParsedSource(fileId, lines, parsed), address, indices.single())
                    ?.let(candidates.getValue(address)::add)
            }
        }
        return addresses.associateWith { candidates.getValue(it).singleOrNull() }
    }

    private fun resolve(source: ParsedSource, address: Int, startIndex: Int): Resolution? {
        val selected = mutableListOf<Directive>()
        var expectedAddress = address
        var words = 0
        for (directive in source.directives.drop(startIndex)) {
            if (directive.address != expectedAddress || words + directive.words.size > PALETTE_WORDS) return null
            selected.lastOrNull()?.let { previous ->
                val hasEmittingGap = (previous.lineIndex + 1 until directive.lineIndex).any { lineIndex ->
                    val code = asmExecutableCode(source.lines[lineIndex]).trim()
                    code.isNotEmpty() && !LABEL.matches(code)
                }
                if (hasEmittingGap) return null
            }
            selected += directive
            words += directive.words.size
            expectedAddress += directive.words.size * 2
            if (words == PALETTE_WORDS) break
        }
        if (words != PALETTE_WORDS) return null
        val raw = ByteArray(PALETTE_BYTES)
        selected.flatMap(Directive::words).forEachIndexed { index, word ->
            raw[index * 2] = word.toByte()
            raw[index * 2 + 1] = (word ushr 8).toByte()
        }
        return Resolution(source.fileId, selected, raw)
    }

    private fun parseDirective(lineIndex: Int, line: String): Directive? {
        val match = DIRECTIVE.matchEntire(line) ?: return null
        val operands = match.groups[1] ?: return null
        val tokens = WORD.findAll(operands.value).toList()
        if (tokens.isEmpty()) return null
        val separators = WORD.replace(operands.value, "").replace(",", "").trim()
        if (separators.isNotEmpty() || operands.value.count { it == ',' } != tokens.size - 1) return null
        val ranges = tokens.map { token ->
            val start = operands.range.first + token.range.first
            start..(start + token.value.length - 1)
        }
        return Directive(
            lineIndex,
            ranges,
            match.groupValues[2].toInt(16),
            tokens.map { it.value.drop(1).toInt(16) },
        )
    }

    private fun render(text: String, resolution: Resolution, rawPalette: ByteArray): String {
        require(rawPalette.size == PALETTE_BYTES)
        val lines = text.split('\n').toMutableList()
        var wordIndex = 0
        resolution.directives.forEach { directive ->
            var line = lines[directive.lineIndex]
            directive.wordRanges.asReversed().forEach { range ->
                val index = wordIndex + directive.wordRanges.indexOf(range)
                val word = (rawPalette[index * 2].toInt() and 0xFF) or
                    ((rawPalette[index * 2 + 1].toInt() and 0xFF) shl 8)
                line = line.replaceRange(range, "\$${word.toString(16).uppercase().padStart(4, '0')}")
            }
            lines[directive.lineIndex] = line
            wordIndex += directive.wordRanges.size
        }
        require(wordIndex == PALETTE_WORDS)
        return lines.joinToString("\n")
    }

    private fun AsmNativeSpritePaletteAccess.editableOrThrow(): AsmNativeSpritePaletteAccess.Editable = when (this) {
        is AsmNativeSpritePaletteAccess.Editable -> this
        is AsmNativeSpritePaletteAccess.AsmOnly -> throw IllegalStateException(reason)
    }

    private companion object {
        const val PALETTE_WORDS = 16
        const val PALETTE_BYTES = 32
        val DIRECTIVE = Regex("^\\s*dw\\s+([^;]+);\\s*([0-9A-Fa-f]{6})\\s*;.*$", RegexOption.IGNORE_CASE)
        val WORD = Regex("\\$[0-9A-Fa-f]{4}")
        val LABEL = Regex("^(?:[A-Za-z_.?][A-Za-z0-9_.?]*|[+-]+):$")
    }
}
