package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TextEntry
import java.io.File

internal sealed interface AsmTextSourceAccess {
    val entryId: String

    data class Editable(
        override val entryId: String,
        val address: Int,
        val sourceFileId: String,
        val rawData: ByteArray,
        val baselineRawData: ByteArray,
        val text: String,
        val baselineText: String,
        val sourceText: String,
        val modified: Boolean,
    ) : AsmTextSourceAccess

    data class AsmOnly(
        override val entryId: String,
        val reason: String,
    ) : AsmTextSourceAccess
}

internal data class AsmTextSourceSnapshot(
    val entryId: String,
    val address: Int,
    val sourceFileId: String,
    val rawData: ByteArray,
    val sourceText: String,
)

internal fun AsmTextSourceAccess.Editable.snapshot(): AsmTextSourceSnapshot =
    AsmTextSourceSnapshot(entryId, address, sourceFileId, rawData.copyOf(), sourceText)

/**
 * Fixed-size source lens for the tilemap text already represented by the Text
 * Editor. Every owned word must remain a literal `dw $hhhh` token with exact
 * contiguous ROM-address comments. Expressions, macros, missing address
 * evidence and resized fields fail closed instead of being rewritten.
 */
internal class AsmTextSourceBridge(
    private val repository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
) {
    fun accessAll(projectFilePath: String, entries: Collection<TextEntry>): Map<String, AsmTextSourceAccess> {
        val supported = entries.filter(::supports)
        if (supported.isEmpty()) return emptyMap()
        val workspace = repository.loadForRead(projectFilePath)
            ?: return supported.associate { entry ->
                entry.id to AsmTextSourceAccess.AsmOnly(entry.id, "Project ASM workspace is unavailable.")
            }
        val current = readAsmSourceTree(File(workspace.workingDirectory, "src"))
        val baseline = readAsmSourceTree(File(workspace.originalDirectory, "src"))
        return supported.associate { entry -> entry.id to access(current, baseline, entry) }
    }

    fun save(
        projectFilePath: String,
        entry: TextEntry,
        newText: String,
        expectedSourceText: String,
    ): AsmTextSourceAccess.Editable {
        require(supports(entry)) { "${entry.label} is not a fixed tilemap-text source lens" }
        val current = accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        require(current.sourceText == expectedSourceText) {
            "${current.sourceFileId} changed after ${entry.label} was opened. Reload it before saving."
        }
        val encoded = TextData.encodeEntry(entry, newText, current.rawData)
        require(encoded.size == current.rawData.size) { "${entry.label} changed its fixed source size" }
        if (encoded.contentEquals(current.rawData)) return current
        val resolution = requireNotNull(resolve(mapOf(current.sourceFileId to current.sourceText), entry))
        repository.persistSource(
            projectFilePath,
            current.sourceFileId,
            render(current.sourceText, resolution, encoded),
            expectedSourceText,
        )
        return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
    }

    fun restore(
        projectFilePath: String,
        entry: TextEntry,
        expectedSourceText: String,
    ): AsmTextSourceAccess.Editable {
        val current = accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        require(current.sourceText == expectedSourceText) {
            "${current.sourceFileId} changed after ${entry.label} was opened. Reload it before restoring."
        }
        if (!current.modified) return current
        val resolution = requireNotNull(resolve(mapOf(current.sourceFileId to current.sourceText), entry))
        repository.persistSource(
            projectFilePath,
            current.sourceFileId,
            render(current.sourceText, resolution, current.baselineRawData),
            expectedSourceText,
        )
        return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
    }

    fun applySnapshot(
        projectFilePath: String,
        entry: TextEntry,
        target: AsmTextSourceSnapshot,
        expected: AsmTextSourceSnapshot,
    ): AsmTextSourceAccess.Editable {
        require(target.entryId == expected.entryId && target.entryId == entry.id &&
            target.address == expected.address && target.sourceFileId == expected.sourceFileId
        ) { "Text source history identity changed" }
        val current = accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        require(current.address == expected.address && current.sourceFileId == expected.sourceFileId &&
            current.sourceText == expected.sourceText && current.rawData.contentEquals(expected.rawData)
        ) {
            "${current.sourceFileId} changed after this visual text edit. Reload it before Undo/Redo."
        }
        val resolution = requireNotNull(resolve(mapOf(target.sourceFileId to target.sourceText), entry)) {
            "Saved text history no longer has one exact literal-word field"
        }
        require(resolution.bytes.contentEquals(target.rawData)) {
            "Saved text history does not match its literal words"
        }
        repository.persistSource(projectFilePath, target.sourceFileId, target.sourceText, expected.sourceText)
        return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
    }

    private fun access(
        currentSources: Map<String, String>,
        baselineSources: Map<String, String>,
        entry: TextEntry,
    ): AsmTextSourceAccess {
        val current = resolve(currentSources, entry)
            ?: return AsmTextSourceAccess.AsmOnly(
                entry.id,
                "${entry.label} is not one exact fixed literal-word ASM field. Edit it in ASM.",
            )
        val baseline = resolve(baselineSources, entry)
            ?: return AsmTextSourceAccess.AsmOnly(
                entry.id,
                "The frozen ${entry.label} field is not one exact literal-word table. Edit it in ASM.",
            )
        if (current.fileId != baseline.fileId) {
            return AsmTextSourceAccess.AsmOnly(
                entry.id,
                "${entry.label} moved to a custom source file. Edit it in ASM.",
            )
        }
        return AsmTextSourceAccess.Editable(
            entryId = entry.id,
            address = entry.snesAddress,
            sourceFileId = current.fileId,
            rawData = current.bytes,
            baselineRawData = baseline.bytes,
            text = TextData.decodeFixedEntry(entry, current.bytes),
            baselineText = TextData.decodeFixedEntry(entry, baseline.bytes),
            sourceText = currentSources.getValue(current.fileId),
            modified = !current.bytes.contentEquals(baseline.bytes),
        )
    }

    private data class WordToken(
        val lineIndex: Int,
        val range: IntRange,
        val address: Int,
        val value: Int,
    )

    private data class Resolution(
        val fileId: String,
        val tokens: List<WordToken>,
        val bytes: ByteArray,
    )

    private fun resolve(sources: Map<String, String>, entry: TextEntry): Resolution? {
        if (entry.snesAddress !in 0x800000..0xFFFFFF || entry.rawBytes.isEmpty() || entry.rawBytes.size % 2 != 0) {
            return null
        }
        return sources.mapNotNull { (fileId, text) -> resolve(fileId, text, entry) }.singleOrNull()
    }

    private fun resolve(fileId: String, text: String, entry: TextEntry): Resolution? {
        val tokens = text.split('\n').flatMapIndexed { lineIndex, rawLine ->
            parseDirective(lineIndex, rawLine.removeSuffix("\r"))
        }
        val expected = (entry.rawBytes.indices step 2).map { entry.snesAddress + it }
        val selected = expected.map { address -> tokens.singleOrNull { it.address == address } ?: return null }
        if (selected.map(WordToken::address) != expected || selected.map(WordToken::lineIndex).zipWithNext().any { (a, b) -> b < a }) {
            return null
        }
        val bytes = ByteArray(entry.rawBytes.size)
        selected.forEachIndexed { index, token ->
            bytes[index * 2] = token.value.toByte()
            bytes[index * 2 + 1] = (token.value ushr 8).toByte()
        }
        return Resolution(fileId, selected, bytes)
    }

    private fun parseDirective(lineIndex: Int, line: String): List<WordToken> {
        val match = DIRECTIVE.matchEntire(line) ?: return emptyList()
        val operands = match.groups[1] ?: return emptyList()
        val words = WORD.findAll(operands.value).toList()
        if (words.isEmpty()) return emptyList()
        val separators = WORD.replace(operands.value, "").replace(",", "").trim()
        if (separators.isNotEmpty() || operands.value.count { it == ',' } != words.size - 1) return emptyList()
        val address = match.groupValues[2].toInt(16)
        return words.mapIndexed { index, word ->
            val start = operands.range.first + word.range.first
            WordToken(
                lineIndex,
                start..(start + word.value.length - 1),
                address + index * 2,
                word.value.drop(1).toInt(16),
            )
        }
    }

    private fun render(text: String, resolution: Resolution, bytes: ByteArray): String {
        require(bytes.size == resolution.tokens.size * 2)
        val lines = text.split('\n').toMutableList()
        resolution.tokens.groupBy(WordToken::lineIndex).forEach { (lineIndex, lineTokens) ->
            var line = lines[lineIndex]
            lineTokens.asReversed().forEach { token ->
                val index = resolution.tokens.indexOf(token)
                val value = (bytes[index * 2].toInt() and 0xFF) or
                    ((bytes[index * 2 + 1].toInt() and 0xFF) shl 8)
                line = line.replaceRange(token.range, "\$${value.toString(16).uppercase().padStart(4, '0')}")
            }
            lines[lineIndex] = line
        }
        return lines.joinToString("\n")
    }

    private fun supports(entry: TextEntry): Boolean = TextData.isFixedTilemapEntry(entry)

    private fun AsmTextSourceAccess.editableOrThrow(): AsmTextSourceAccess.Editable = when (this) {
        is AsmTextSourceAccess.Editable -> this
        is AsmTextSourceAccess.AsmOnly -> throw IllegalStateException(reason)
    }

    private companion object {
        val DIRECTIVE = Regex("^\\s*dw\\s+([^;]+);\\s*([0-9A-Fa-f]{6})\\s*;.*$", RegexOption.IGNORE_CASE)
        val WORD = Regex("\\$[0-9A-Fa-f]{4}")
    }
}
