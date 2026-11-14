package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.TextData
import com.supermetroid.editor.rom.TextEntry
import com.supermetroid.editor.rom.TypewriterTextStream
import java.io.File
import java.util.Base64

internal sealed interface AsmTypewriterTextAccess {
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
        val capacity: Int,
        val modified: Boolean,
        val managedAssetId: String? = null,
        val relocation: TypewriterRelocationCandidate? = null,
    ) : AsmTypewriterTextAccess

    data class AsmOnly(override val entryId: String, val reason: String) : AsmTypewriterTextAccess
}

internal data class AsmTypewriterTextSnapshot(
    val entryId: String,
    val address: Int,
    val sourceFileId: String,
    val rawData: ByteArray,
    val sourceText: String,
    val managedAssetId: String?,
)

internal fun AsmTypewriterTextAccess.Editable.snapshot(): AsmTypewriterTextSnapshot =
    AsmTypewriterTextSnapshot(entryId, address, sourceFileId, rawData.copyOf(), sourceText, managedAssetId)

internal data class TypewriterRelocationCandidate(val record: AsmManagedAssetRecord)

/** Typed source lens for the bank-$A6 Ceres and Zebes typewriter streams. The
 * timer, per-line VRAM destinations and terminal command are immutable visual
 * controls; only the quoted runtime text is authored by the Text Editor. */
internal class AsmTypewriterTextBridge(
    private val repository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
) {
    fun accessAll(projectFilePath: String, entries: Collection<TextEntry>): Map<String, AsmTypewriterTextAccess> {
        val supported = entries.filter(TextData::isTypewriterEntry)
        if (supported.isEmpty()) return emptyMap()
        val workspace = repository.loadForRead(projectFilePath)
            ?: return supported.associate { it.id to AsmTypewriterTextAccess.AsmOnly(it.id, "Project ASM workspace is unavailable.") }
        val currentSources = readAsmSourceTree(File(workspace.workingDirectory, "src"))
        val baselineSources = readAsmSourceTree(File(workspace.originalDirectory, "src"))
        val managed = AsmManagedAssets.load(workspace.root)
        return supported.associate { entry ->
            entry.id to access(workspace, currentSources, baselineSources, managed, entry)
        }
    }

    fun save(
        projectFilePath: String,
        entry: TextEntry,
        newText: String,
        expected: AsmTypewriterTextSnapshot,
    ): AsmTypewriterTextAccess.Editable {
        val current = accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        requireMatches(current, expected, "saving")
        val variable = TextData.encodeTypewriterText(newText, current.rawData, preserveLineSlots = false)
        if (current.managedAssetId != null) {
            if (!variable.contentEquals(current.rawData)) {
                repository.saveManagedAsset(projectFilePath, current.managedAssetId, variable, current.rawData)
            }
            return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        }
        val fixed = runCatching {
            TextData.encodeTypewriterText(newText, current.rawData, preserveLineSlots = true)
        }.getOrElse {
            require(current.relocation != null) {
                "${entry.label} no longer fits its native slots and has no complete same-bank pointer contract. Edit it in ASM."
            }
            throw AsmAssetRelocationRequiredException(
                "inline ${current.sourceLabel}",
                variable.size,
                current.capacity,
            )
        }
        if (fixed.contentEquals(current.rawData)) return current
        val resolution = requireNotNull(resolveDirect(current.sourceFileId, current.sourceText, definition(entry)))
        val replacement = renderFixed(current.sourceText, resolution, fixed)
        repository.persistSource(projectFilePath, current.sourceFileId, replacement, current.sourceText)
        return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
    }

    fun relocate(
        projectFilePath: String,
        entry: TextEntry,
        rawData: ByteArray,
        expected: AsmTypewriterTextSnapshot,
    ): AsmTypewriterTextAccess.Editable {
        val current = accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        requireMatches(current, expected, "relocating")
        require(current.managedAssetId == null) { "${entry.label} is already in managed project space" }
        requireNotNull(TextData.decodeTypewriterStream(rawData)) { "${entry.label} has invalid typewriter controls" }
        val candidate = requireNotNull(current.relocation) {
            "${entry.label} has no complete same-bank pointer contract. Edit it in ASM."
        }
        repository.installManagedAsset(
            projectFilePath,
            candidate.record,
            rawData,
            current.sourceText,
        )
        return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
    }

    fun restore(
        projectFilePath: String,
        entry: TextEntry,
        expected: AsmTypewriterTextSnapshot,
    ): AsmTypewriterTextAccess.Editable {
        val current = accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        requireMatches(current, expected, "restoring")
        if (!current.modified) return current
        val workspace = requireNotNull(repository.loadForRead(projectFilePath))
        val baselineText = File(workspace.originalDirectory, "src/${current.sourceFileId}").readText()
        val baseline = requireNotNull(resolveDirect(current.sourceFileId, baselineText, definition(entry)))
        if (current.managedAssetId != null) {
            repository.restoreManagedAsset(
                projectFilePath,
                current.managedAssetId,
                current.rawData,
                replacementInlineDeclaration = baseline.declaration,
            )
        } else {
            val direct = requireNotNull(resolveDirect(current.sourceFileId, current.sourceText, definition(entry)))
            repository.persistSource(
                projectFilePath,
                current.sourceFileId,
                current.sourceText.replaceRange(direct.declarationRange, baseline.declaration),
                current.sourceText,
            )
        }
        return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
    }

    fun applySnapshot(
        projectFilePath: String,
        entry: TextEntry,
        target: AsmTypewriterTextSnapshot,
        expected: AsmTypewriterTextSnapshot,
    ): AsmTypewriterTextAccess.Editable {
        require(target.entryId == expected.entryId && target.entryId == entry.id &&
            target.address == expected.address && target.sourceFileId == expected.sourceFileId
        ) { "Typewriter source history identity changed" }
        val current = accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
        requireMatches(current, expected, "Undo/Redo")
        requireNotNull(TextData.decodeTypewriterStream(target.rawData)) { "Saved typewriter history has invalid controls" }
        when {
            current.managedAssetId == null && target.managedAssetId == null ->
                repository.persistSource(projectFilePath, current.sourceFileId, target.sourceText, current.sourceText)
            current.managedAssetId != null && target.managedAssetId != null -> {
                require(current.managedAssetId == target.managedAssetId) { "Managed typewriter identity changed" }
                repository.saveManagedAsset(projectFilePath, current.managedAssetId, target.rawData, current.rawData)
            }
            current.managedAssetId == null && target.managedAssetId != null -> {
                val candidate = requireNotNull(current.relocation) { "Typewriter relocation contract changed" }
                require(candidate.record.id == target.managedAssetId) { "Managed typewriter identity changed" }
                repository.installManagedAsset(
                    projectFilePath,
                    candidate.record,
                    target.rawData,
                    current.sourceText,
                )
            }
            else -> {
                val targetDirect = requireNotNull(resolveDirect(target.sourceFileId, target.sourceText, definition(entry))) {
                    "Saved direct typewriter declaration is no longer supported"
                }
                repository.restoreManagedAsset(
                    projectFilePath,
                    requireNotNull(current.managedAssetId),
                    current.rawData,
                    replacementInlineDeclaration = targetDirect.declaration,
                )
            }
        }
        return accessAll(projectFilePath, listOf(entry)).getValue(entry.id).editableOrThrow()
    }

    private fun access(
        workspace: AsmProjectBuildWorkspace,
        currentSources: Map<String, String>,
        baselineSources: Map<String, String>,
        managed: AsmManagedAssetManifest,
        entry: TextEntry,
    ): AsmTypewriterTextAccess {
        val definition = definition(entry)
        val baseline = resolveUnique(baselineSources, definition)
            ?: return AsmTypewriterTextAccess.AsmOnly(entry.id, "The frozen ${entry.label} stream is not one exact typed declaration.")
        if (!baseline.rawData.contentEquals(entry.rawBytes)) {
            return AsmTypewriterTextAccess.AsmOnly(entry.id, "The frozen ${entry.label} stream does not match the loaded ROM.")
        }
        val record = managed.assets.singleOrNull { it.kind == KIND && it.sourceLabel == definition.label }
        if (record != null) {
            if (!record.inlineSource || record.sourceFileId != baseline.fileId || record.originalPcOffset != entry.pcOffset ||
                record.allowedBanks != listOf(definition.bank)
            ) return AsmTypewriterTextAccess.AsmOnly(entry.id, "${entry.label} has an incompatible managed-source contract.")
            val data = AsmManagedAssets.assetFile(workspace.root, record).takeIf(File::isFile)?.readBytes()
                ?: return AsmTypewriterTextAccess.AsmOnly(entry.id, "${entry.label}'s managed data is missing.")
            val stream = TextData.decodeTypewriterStream(data)
                ?: return AsmTypewriterTextAccess.AsmOnly(entry.id, "${entry.label}'s managed controls are invalid.")
            return editable(entry, baseline, stream, data, currentSources.getValue(record.sourceFileId), record, null)
        }
        val current = resolveUnique(currentSources, definition)
            ?: return AsmTypewriterTextAccess.AsmOnly(
                entry.id,
                "${entry.label} is not one exact literal control-and-string stream. Edit it in ASM.",
            )
        if (current.fileId != baseline.fileId || current.rawData.size != baseline.rawData.size) {
            return AsmTypewriterTextAccess.AsmOnly(
                entry.id,
                "${entry.label} changed source file or native size without checked relocation. Edit it in ASM.",
            )
        }
        val bindings = AsmManagedAssets.pointerBindings(currentSources, definition.label)
            ?.takeIf { values -> values.all { it.kind == AsmManagedPointerKind.IMMEDIATE_LOW16 } }
        val relocation = bindings?.let {
            val originalPath = "inline/${definition.label}.bin"
            val id = AsmManagedAssets.stableId(KIND, definition.label, originalPath)
            TypewriterRelocationCandidate(
                AsmManagedAssetRecord(
                    id = id,
                    kind = KIND,
                    sourceFileId = current.fileId,
                    sourceLabel = definition.label,
                    originalAssetPath = originalPath,
                    managedAssetPath = "$ASM_MANAGED_ASSET_DATA_DIRECTORY/$id.bin",
                    originalPcOffset = entry.pcOffset,
                    originalLength = baseline.rawData.size,
                    originalDeclaration = current.declaration,
                    pointerBindings = it,
                    inlineSource = true,
                    inlineOriginalBytesBase64 = Base64.getEncoder().encodeToString(baseline.rawData),
                    allowedBanks = listOf(definition.bank),
                ),
            )
        }
        return editable(entry, baseline, current.stream, current.rawData, currentSources.getValue(current.fileId), null, relocation)
    }

    private fun editable(
        entry: TextEntry,
        baseline: Resolution,
        stream: TypewriterTextStream,
        rawData: ByteArray,
        sourceText: String,
        managed: AsmManagedAssetRecord?,
        relocation: TypewriterRelocationCandidate?,
    ) = AsmTypewriterTextAccess.Editable(
        entryId = entry.id,
        address = entry.snesAddress,
        sourceFileId = baseline.fileId,
        sourceLabel = definition(entry).label,
        rawData = rawData,
        baselineRawData = baseline.rawData,
        text = stream.displayText,
        baselineText = baseline.stream.displayText,
        sourceText = sourceText,
        capacity = baseline.rawData.size,
        modified = managed != null || !rawData.contentEquals(baseline.rawData),
        managedAssetId = managed?.id,
        relocation = relocation,
    )

    private data class Definition(val label: String, val address: Int) {
        val bank: Int get() = address ushr 16 and 0xFF
    }

    private data class SourceLine(val start: Int, val contentEnd: Int, val fullEnd: Int, val content: String)
    private data class StringToken(val range: IntRange)
    private data class Resolution(
        val fileId: String,
        val declarationRange: IntRange,
        val declaration: String,
        val stream: TypewriterTextStream,
        val rawData: ByteArray,
        val strings: List<StringToken>,
    )

    private fun resolveUnique(sources: Map<String, String>, definition: Definition): Resolution? =
        sources.mapNotNull { (fileId, text) -> resolveDirect(fileId, text, definition) }.singleOrNull()

    private fun resolveDirect(fileId: String, text: String, definition: Definition): Resolution? {
        val lines = sourceLines(text)
        val labels = lines.withIndex().filter { (_, line) ->
            asmExecutableCode(line.content).trim() == "${definition.label}:"
        }
        val label = labels.singleOrNull() ?: return null
        val statements = mutableListOf<Pair<SourceLine, String>>()
        for (index in label.index + 1 until lines.size) {
            val line = lines[index]
            val code = asmExecutableCode(line.content).trim()
            if (code.isBlank()) continue
            if (GLOBAL_LABEL.matches(code)) return null
            statements += line to code
            if (parseWords(code) == listOf(0)) break
        }
        if (statements.isEmpty()) return null
        val first = statements.first()
        val firstWords = parseWords(first.second) ?: return null
        if (firstWords.size != 2 || firstWords[0] != 1 ||
            !Regex(";${definition.address.toString(16).uppercase().padStart(6, '0')};", RegexOption.IGNORE_CASE)
                .containsMatchIn(first.first.content)
        ) return null
        val timer = firstWords[1]
        var cursor = 1
        val streamLines = mutableListOf<com.supermetroid.editor.rom.TypewriterTextLine>()
        val strings = mutableListOf<StringToken>()
        while (cursor < statements.size) {
            val (controlLine, controlCode) = statements[cursor]
            val words = parseWords(controlCode) ?: return null
            if (words == listOf(0)) {
                if (cursor != statements.lastIndex || streamLines.isEmpty()) return null
                val stream = TypewriterTextStream(timer, streamLines)
                val raw = TextData.encodeTypewriterStream(stream)
                val range = label.value.start..controlLine.contentEnd - 1
                return Resolution(fileId, range, text.substring(range), stream, raw, strings)
            }
            if (words.size != 2 || words[0] != 0x000D || cursor + 1 >= statements.size) return null
            val (stringLine, stringCode) = statements[cursor + 1]
            val match = DB_STRING.matchEntire(stringCode) ?: return null
            val value = match.groupValues[1]
            if (value.isEmpty() || value.any { it !in TYPEWRITER_CHARACTERS }) return null
            val quoteStart = stringLine.start + stringLine.content.indexOf('"') + 1
            streamLines += com.supermetroid.editor.rom.TypewriterTextLine(words[1], value)
            strings += StringToken(quoteStart..quoteStart + value.length - 1)
            cursor += 2
        }
        return null
    }

    private fun renderFixed(text: String, resolution: Resolution, rawData: ByteArray): String {
        val stream = requireNotNull(TextData.decodeTypewriterStream(rawData))
        require(stream.lines.size == resolution.strings.size)
        var rendered = text
        resolution.strings.zip(stream.lines).asReversed().forEach { (token, line) ->
            require(line.text.length == token.range.last - token.range.first + 1)
            rendered = rendered.replaceRange(token.range, line.text)
        }
        return rendered
    }

    private fun parseWords(code: String): List<Int>? {
        val match = DW.matchEntire(code) ?: return null
        val operands = match.groupValues[1].split(',').map(String::trim)
        if (operands.isEmpty()) return null
        return operands.map { WORD.matchEntire(it)?.groupValues?.get(1)?.toInt(16) ?: return null }
    }

    private fun sourceLines(text: String): List<SourceLine> {
        val result = mutableListOf<SourceLine>()
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            val fullEnd = if (newline < 0) text.length else newline + 1
            var contentEnd = if (newline < 0) text.length else newline
            if (contentEnd > start && text[contentEnd - 1] == '\r') contentEnd--
            result += SourceLine(start, contentEnd, fullEnd, text.substring(start, contentEnd))
            start = fullEnd
        }
        return result
    }

    private fun requireMatches(
        current: AsmTypewriterTextAccess.Editable,
        expected: AsmTypewriterTextSnapshot,
        action: String,
    ) {
        require(current.entryId == expected.entryId && current.address == expected.address &&
            current.sourceFileId == expected.sourceFileId && current.sourceText == expected.sourceText &&
            current.managedAssetId == expected.managedAssetId && current.rawData.contentEquals(expected.rawData)
        ) { "${current.sourceFileId} changed before $action ${current.entryId}. Reload it first." }
    }

    private fun definition(entry: TextEntry): Definition = when (entry.id) {
        "ceres_escape" -> Definition("TypewriterText_CeresEscapeTimer", TextData.CERES_ESCAPE_SNES)
        "tourian_escape" -> Definition("TypewriterText_ZebesEscapeTimer", TextData.TOURIAN_ESCAPE_SNES)
        else -> error("Unsupported typewriter entry ${entry.id}")
    }

    private fun AsmTypewriterTextAccess.editableOrThrow(): AsmTypewriterTextAccess.Editable = when (this) {
        is AsmTypewriterTextAccess.Editable -> this
        is AsmTypewriterTextAccess.AsmOnly -> throw IllegalStateException(reason)
    }

    private companion object {
        const val KIND = "typewriter-text"
        val GLOBAL_LABEL = Regex("^[A-Za-z_][A-Za-z0-9_]*:$")
        val DW = Regex("^dw\\s+(.+)$", RegexOption.IGNORE_CASE)
        val WORD = Regex("\\$([0-9A-Fa-f]{4})")
        val DB_STRING = Regex("^db\\s+\"([^\"]+)\"$", RegexOption.IGNORE_CASE)
        val TYPEWRITER_CHARACTERS: Set<Char> = ('A'..'Z').toSet() + setOf(' ', '!')
    }
}
