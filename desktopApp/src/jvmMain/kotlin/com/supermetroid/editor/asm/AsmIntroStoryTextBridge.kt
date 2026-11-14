package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TextCategory
import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TextEntry
import java.io.File

internal sealed interface AsmIntroStoryTextAccess {
    val entryId: String

    data class Editable(
        override val entryId: String,
        val address: Int,
        val sourceFileId: String,
        val sourceLabel: String,
        val rawData: ByteArray,
        val baselineRawData: ByteArray,
        val text: String,
        val baselineText: String,
        val sourceText: String,
        val lineCapacities: List<Int>,
        val modified: Boolean,
    ) : AsmIntroStoryTextAccess

    data class AsmOnly(override val entryId: String, val reason: String) : AsmIntroStoryTextAccess
}

internal data class AsmIntroStoryTextSnapshot(
    val entryId: String,
    val address: Int,
    val sourceFileId: String,
    val rawData: ByteArray,
    val sourceText: String,
)

internal fun AsmIntroStoryTextAccess.Editable.snapshot(): AsmIntroStoryTextSnapshot =
    AsmIntroStoryTextSnapshot(entryId, address, sourceFileId, rawData.copyOf(), sourceText)

/**
 * Fixed-slot source lens for the six English NTSC intro-story pages. Only the
 * symbolic character routine in each proven six-byte record is editable. The
 * page setup, timing expression, X/Y position, wait/movie/finish controls and
 * record count are deliberately outside the visual editor's authority.
 */
internal class AsmIntroStoryTextBridge(
    private val repository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
) {
    fun accessAll(projectFilePath: String, entries: Collection<TextEntry>): Map<String, AsmIntroStoryTextAccess> {
        val supported = entries.filter(::supports)
        if (supported.isEmpty()) return emptyMap()
        val workspace = repository.loadForRead(projectFilePath)
            ?: return supported.associate { entry ->
                entry.id to AsmIntroStoryTextAccess.AsmOnly(entry.id, "Project ASM workspace is unavailable.")
            }
        val current = readAsmSourceTree(File(workspace.workingDirectory, "src"))
        val baseline = readAsmSourceTree(File(workspace.originalDirectory, "src"))
        return supported.associate { entry -> entry.id to access(current, baseline, entry) }
    }

    fun save(
        projectFilePath: String,
        entry: TextEntry,
        newText: String,
        expected: AsmIntroStoryTextSnapshot,
    ): AsmIntroStoryTextAccess.Editable {
        val current = accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        requireMatches(current, expected, "saving")
        val resolution = requireNotNull(resolve(mapOf(current.sourceFileId to current.sourceText), entry))
        val characters = encodeLines(newText, resolution)
        if (characters == resolution.records.map(GlyphRecord::character)) return current
        repository.persistSource(
            projectFilePath,
            current.sourceFileId,
            render(current.sourceText, resolution, characters),
            current.sourceText,
        )
        return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
    }

    fun restore(
        projectFilePath: String,
        entry: TextEntry,
        expected: AsmIntroStoryTextSnapshot,
    ): AsmIntroStoryTextAccess.Editable {
        val current = accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        requireMatches(current, expected, "restoring")
        if (!current.modified) return current
        val workspace = requireNotNull(repository.loadForRead(projectFilePath))
        val baselineText = File(workspace.originalDirectory, "src/${current.sourceFileId}").readText()
        val direct = requireNotNull(resolve(mapOf(current.sourceFileId to current.sourceText), entry))
        val baseline = requireNotNull(resolve(mapOf(current.sourceFileId to baselineText), entry))
        repository.persistSource(
            projectFilePath,
            current.sourceFileId,
            current.sourceText.replaceRange(direct.pageRange, baseline.pageText),
            current.sourceText,
        )
        return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
    }

    fun applySnapshot(
        projectFilePath: String,
        entry: TextEntry,
        target: AsmIntroStoryTextSnapshot,
        expected: AsmIntroStoryTextSnapshot,
    ): AsmIntroStoryTextAccess.Editable {
        require(target.entryId == expected.entryId && target.entryId == entry.id &&
            target.address == expected.address && target.sourceFileId == expected.sourceFileId
        ) { "Intro-story source history identity changed" }
        val current = accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        requireMatches(current, expected, "Undo/Redo")
        val targetResolution = requireNotNull(resolve(mapOf(target.sourceFileId to target.sourceText), entry)) {
            "Saved intro-story history no longer has one exact native page"
        }
        require(targetResolution.rawData.contentEquals(target.rawData)) {
            "Saved intro-story history does not match its symbolic glyph records"
        }
        repository.persistSource(projectFilePath, target.sourceFileId, target.sourceText, expected.sourceText)
        return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
    }

    private fun access(
        currentSources: Map<String, String>,
        baselineSources: Map<String, String>,
        entry: TextEntry,
    ): AsmIntroStoryTextAccess {
        val definition = definition(entry)
            ?: return AsmIntroStoryTextAccess.AsmOnly(
                entry.id,
                "Only the six English NTSC intro-story pages are supported; regional text remains ASM only.",
            )
        val baseline = resolve(baselineSources, entry)
            ?: return AsmIntroStoryTextAccess.AsmOnly(
                entry.id,
                "The frozen ${entry.label} source is not one exact native timing/position/glyph page.",
            )
        if (!baseline.rawData.contentEquals(entry.rawBytes)) {
            return AsmIntroStoryTextAccess.AsmOnly(
                entry.id,
                "The frozen ${entry.label} glyph records do not match the loaded English NTSC ROM.",
            )
        }
        val current = resolve(currentSources, entry)
            ?: return AsmIntroStoryTextAccess.AsmOnly(
                entry.id,
                "${entry.label} changed its native records, timing, positions, controls, glyph expressions, or location. Edit it in ASM.",
            )
        if (current.fileId != baseline.fileId || current.records.map(GlyphRecord::address) != baseline.records.map(GlyphRecord::address)) {
            return AsmIntroStoryTextAccess.AsmOnly(
                entry.id,
                "${entry.label} moved or changed its fixed record layout. Edit it in ASM.",
            )
        }
        return AsmIntroStoryTextAccess.Editable(
            entryId = entry.id,
            address = definition.address + 2,
            sourceFileId = current.fileId,
            sourceLabel = definition.label,
            rawData = current.rawData,
            baselineRawData = baseline.rawData,
            text = current.displayText,
            baselineText = baseline.displayText,
            sourceText = currentSources.getValue(current.fileId),
            lineCapacities = current.lineGroups.map(List<GlyphRecord>::size),
            modified = !current.rawData.contentEquals(baseline.rawData),
        )
    }

    private data class Definition(val index: Int, val label: String, val address: Int)

    private data class SourceLine(val start: Int, val endExclusive: Int, val text: String)

    private data class GlyphRecord(
        val address: Int,
        val x: Int,
        val y: Int,
        val character: Char,
        val symbolRange: IntRange,
        val quotedCharacterRange: IntRange,
    )

    private data class Resolution(
        val fileId: String,
        val pageRange: IntRange,
        val pageText: String,
        val records: List<GlyphRecord>,
        val summaryRanges: List<IntRange>,
        val rawData: ByteArray,
    ) {
        val lineGroups: List<List<GlyphRecord>> = records.groupBy(GlyphRecord::y).values.toList()
        val displayText: String = lineGroups.joinToString("\n") { line ->
            line.joinToString("") { it.character.toString() }.trimEnd()
        }
    }

    private fun resolve(sources: Map<String, String>, entry: TextEntry): Resolution? {
        val definition = definition(entry) ?: return null
        val matches = sources.mapNotNull { (fileId, text) -> resolveFile(fileId, text, entry, definition) }
        return matches.singleOrNull()
    }

    private fun resolveFile(
        fileId: String,
        text: String,
        entry: TextEntry,
        definition: Definition,
    ): Resolution? {
        val lines = sourceLines(text)
        val labelIndexes = lines.indices.filter { lines[it].text.trim() == "${definition.label}:" }
        if (labelIndexes.size != 1) return null
        val labelIndex = labelIndexes.single()
        val meaningful = mutableListOf<SourceLine>()
        val summaries = mutableListOf<SourceLine>()
        var index = labelIndex + 1
        var sawDirective = false
        while (index < lines.size) {
            val trimmed = lines[index].text.trim()
            if (GLOBAL_LABEL.matches(trimmed)) break
            if (trimmed.isNotEmpty()) {
                if (trimmed.startsWith(";")) {
                    if (!sawDirective && SUMMARY.matches(trimmed)) summaries += lines[index]
                } else {
                    sawDirective = true
                    meaningful += lines[index]
                }
            }
            index++
        }
        if (meaningful.size < 6) return null

        var directiveIndex = 0
        var address = definition.address
        fun consume(expectedCode: String): SourceLine? {
            val line = meaningful.getOrNull(directiveIndex++) ?: return null
            val match = DIRECTIVE.matchEntire(line.text) ?: return null
            if (match.groupValues[1].trim() != expectedCode || match.groupValues[2].toInt(16) != address) return null
            address += when {
                expectedCode.startsWith("db ", true) -> 2
                else -> 2
            }
            return line
        }

        consume("dw Instruction_HandleCreatingSubtitle_Page${definition.index + 1}") ?: return null
        consume("dw \$0001") ?: return null
        consume("db \$01,\$01") ?: return null
        consume("dw IndirectInstructions_IntroText_Nothing") ?: return null

        val records = mutableListOf<GlyphRecord>()
        while (directiveIndex < meaningful.size) {
            val timing = DIRECTIVE.matchEntire(meaningful[directiveIndex].text) ?: return null
            if (timing.groupValues[1].trim() != "dw \$0005*!FPS") break
            if (timing.groupValues[2].toInt(16) != address) return null
            directiveIndex++
            address += 2

            val coordinateLine = meaningful.getOrNull(directiveIndex++) ?: return null
            val coordinate = COORDINATES.matchEntire(coordinateLine.text) ?: return null
            if (coordinate.groupValues[3].toInt(16) != address) return null
            val x = coordinate.groupValues[1].toInt(16)
            val y = coordinate.groupValues[2].toInt(16)
            address += 2

            val glyphLine = meaningful.getOrNull(directiveIndex++) ?: return null
            val glyph = GLYPH.matchEntire(glyphLine.text) ?: return null
            if (glyph.groupValues[2].toInt(16) != address) return null
            val character = SYMBOL_TO_CHARACTER[glyph.groupValues[1]] ?: return null
            val symbolStart = glyphLine.start + glyph.groups[1]!!.range.first
            val quoteStart = glyphLine.start + glyph.groups[3]!!.range.first
            records += GlyphRecord(
                address = address,
                x = x,
                y = y,
                character = character,
                symbolRange = symbolStart..(symbolStart + glyph.groupValues[1].length - 1),
                quotedCharacterRange = quoteStart..quoteStart,
            )
            address += 2
        }
        if (records.isEmpty()) return null
        val groups = records.groupBy(GlyphRecord::y).values.toList()
        if (groups.any { line ->
                line.zipWithNext().any { (left, right) -> right.x <= left.x }
            }
        ) return null

        val tail = if (definition.index < 5) listOf(
            "dw Instruction_SpawnBlinkingMarkers_WaitForInput_Page${definition.index + 1}",
            "dw CinematicBGObject_Instruction_Delete",
        ) else listOf(
            "dw Instruction_SetCaretToBlink",
            "dw \$0080",
            "db \$01,\$01",
            "dw IndirectInstructions_IntroText_Nothing",
            "dw Instruction_FinishIntro",
            "dw CinematicBGObject_Instruction_Delete",
        )
        for (code in tail) consume(code) ?: return null
        if (directiveIndex != meaningful.size) return null

        val raw = entry.rawBytes.copyOf()
        for (record in records) {
            val offset = record.address - entry.snesAddress
            if (offset < 0 || offset + 1 >= raw.size) return null
            val pointer = TextData.introStoryPointer(record.character) ?: return null
            raw[offset] = (pointer and 0xFF).toByte()
            raw[offset + 1] = (pointer ushr 8).toByte()
        }
        val last = meaningful.last()
        val pageEnd = last.endExclusive
        return Resolution(
            fileId = fileId,
            pageRange = lines[labelIndex].start until pageEnd,
            pageText = text.substring(lines[labelIndex].start, pageEnd),
            records = records,
            summaryRanges = if (summaries.size == groups.size) {
                summaries.map { it.start until it.endExclusive }
            } else emptyList(),
            rawData = raw,
        )
    }

    private fun encodeLines(text: String, resolution: Resolution): List<Char> {
        val requested = text.uppercase().replace("\r\n", "\n").split('\n')
        require(requested.size == resolution.lineGroups.size) {
            "Intro story text requires exactly ${resolution.lineGroups.size} positioned lines"
        }
        return requested.zip(resolution.lineGroups).flatMapIndexed { index, (line, slots) ->
            require(line.all(SUPPORTED_CHARACTERS::contains)) {
                "Intro story line ${index + 1} supports A-Z, 0-9, spaces, period, comma, apostrophe, and ! only"
            }
            require(line.length <= slots.size) {
                "Intro story line ${index + 1} has ${line.length} characters; its native row has ${slots.size} fixed slots"
            }
            line.padEnd(slots.size).toList()
        }
    }

    private fun render(text: String, resolution: Resolution, characters: List<Char>): String {
        require(characters.size == resolution.records.size)
        val changes = mutableListOf<Replacement>()
        resolution.records.zip(characters).forEach { (record, character) ->
            changes += Replacement(record.symbolRange, CHARACTER_TO_SYMBOL.getValue(character))
            changes += Replacement(record.quotedCharacterRange, character.toString())
        }
        var characterIndex = 0
        if (resolution.summaryRanges.size == resolution.lineGroups.size) {
            resolution.lineGroups.zip(resolution.summaryRanges).forEach { (line, range) ->
                val value = characters.subList(characterIndex, characterIndex + line.size).joinToString("").trimEnd()
                characterIndex += line.size
                changes += Replacement(range, "; $value")
            }
        }
        var result = text
        changes.sortedByDescending { it.range.first }.forEach { change ->
            result = result.replaceRange(change.range, change.text)
        }
        return result
    }

    private fun requireMatches(
        current: AsmIntroStoryTextAccess.Editable,
        expected: AsmIntroStoryTextSnapshot,
        operation: String,
    ) {
        require(current.entryId == expected.entryId && current.address == expected.address &&
            current.sourceFileId == expected.sourceFileId && current.sourceText == expected.sourceText &&
            current.rawData.contentEquals(expected.rawData)
        ) { "${current.sourceFileId} changed after ${current.entryId} was opened. Reload it before $operation." }
    }

    private fun definition(entry: TextEntry): Definition? {
        if (!supports(entry)) return null
        val index = entry.id.removePrefix("intro_").toIntOrNull() ?: return null
        return DEFINITIONS.getOrNull(index)
    }

    private fun supports(entry: TextEntry): Boolean =
        entry.writable && entry.category == TextCategory.INTRO_STORY &&
            entry.id in DEFINITIONS.indices.map { "intro_$it" }

    private fun sourceLines(text: String): List<SourceLine> {
        val result = mutableListOf<SourceLine>()
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
            val end = if (newline > start && text[newline - 1] == '\r') newline - 1 else newline
            result += SourceLine(start, end, text.substring(start, end))
            if (newline == text.length) break
            start = newline + 1
        }
        return result
    }

    private fun AsmIntroStoryTextAccess.editableOrThrow(): AsmIntroStoryTextAccess.Editable = when (this) {
        is AsmIntroStoryTextAccess.Editable -> this
        is AsmIntroStoryTextAccess.AsmOnly -> throw IllegalStateException(reason)
    }

    private data class Replacement(val range: IntRange, val text: String)

    private companion object {
        val DEFINITIONS = listOf(
            Definition(0, "CinematicBGObjectInstLists_IntroTextPage1", 0x8CC383),
            Definition(1, "CinematicBGObjectInstLists_IntroTextPage2", 0x8CC797),
            Definition(2, "CinematicBGObjectInstLists_IntroTextPage3", 0x8CCB45),
            Definition(3, "CinematicBGObjectInstLists_IntroTextPage4", 0x8CCE33),
            Definition(4, "CinematicBGObjectInstLists_IntroTextPage5", 0x8CD15D),
            Definition(5, "CinematicBGObjectInstLists_IntroTextPage6", 0x8CD511),
        )
        val SYMBOL_TO_CHARACTER = buildMap {
            ('A'..'Z').forEach { put(it.toString(), it) }
            ('0'..'9').forEach { put(it.toString(), it) }
            put("Space", ' ')
            put("Period", '.')
            put("Comma", ',')
            put("Apostrophe", '\'')
            put("ExclamationPoint", '!')
        }
        val CHARACTER_TO_SYMBOL = SYMBOL_TO_CHARACTER.entries.associate { (symbol, character) -> character to symbol }
        val SUPPORTED_CHARACTERS = CHARACTER_TO_SYMBOL.keys
        val GLOBAL_LABEL = Regex("[A-Za-z_][A-Za-z0-9_]*:\\s*(?:;.*)?")
        val SUMMARY = Regex("; ?[A-Z0-9 .,\\'!]*")
        val DIRECTIVE = Regex("^\\s*((?:dw|db)\\s+[^;]+?)\\s*;([0-9A-Fa-f]{6});.*$", RegexOption.IGNORE_CASE)
        val COORDINATES = Regex(
            "^\\s*db\\s+\\$([0-9A-Fa-f]{2}),\\$([0-9A-Fa-f]{2})\\s*;([0-9A-Fa-f]{6});.*$",
            RegexOption.IGNORE_CASE,
        )
        val GLYPH = Regex(
            "^\\s*dw\\s+IndirectInstructions_IntroText_([A-Za-z0-9]+)\\s*;([0-9A-Fa-f]{6});\\s*\"(.)\"\\s*$",
            RegexOption.IGNORE_CASE,
        )
    }
}
