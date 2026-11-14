package com.supermetroid.editor.asm

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.UUID

internal enum class AsmDiagnosticSeverity { ERROR, WARNING }

internal data class AsmBuildDiagnostic(
    val severity: AsmDiagnosticSeverity,
    val message: String,
    val fileId: String? = null,
    val lineIndex: Int? = null,
)

internal data class AsmCompiledLocation(
    val fileId: String,
    val lineIndex: Int,
    val snesAddress: Int,
)

/** Compact representation of Asar's WLA symbol map. Address/line pairs are
 * stored as primitive arrays: [SNES address, zero-based line, ...]. */
internal data class AsmCompiledSymbols(
    val labels: Map<String, Int>,
    val lineAddressesByFile: Map<String, IntArray>,
    /** Complete WLA address order. Unlike the compact per-file lookup this
     * retains the next emitted statement across include/file boundaries. */
    val emittedLocations: List<AsmCompiledLocation> = emptyList(),
) {
    private val compiledBoundaries: List<Int> by lazy {
        (orderedLocations().map(AsmCompiledLocation::snesAddress) + labels.values)
            .distinct()
            .sorted()
    }
    private val locationsByAddress: Map<Int, List<AsmCompiledLocation>> by lazy {
        orderedLocations().groupBy(AsmCompiledLocation::snesAddress)
    }
    private val labelsByAddress: Map<Int, List<String>> by lazy {
        labels.entries.groupBy(Map.Entry<String, Int>::value) { it.key }
            .mapValues { (_, names) -> names.sorted() }
    }
    private val orderedLabels: List<Map.Entry<String, Int>> by lazy {
        labels.entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    }
    val labelCount: Int get() = labels.size
    val mappedLineCount: Int get() = lineAddressesByFile.values.sumOf { it.size / 2 }

    fun addressAt(fileId: String, lineIndex: Int): Int? {
        val entries = lineAddressesByFile[fileId] ?: return null
        var index = 0
        while (index < entries.size) {
            if (entries[index + 1] == lineIndex) return entries[index]
            index += 2
        }
        return null
    }

    /** Returns the closest emitted address at or before the caret's source
     * context. Comments, labels and blank lines usually have no direct WLA
     * mapping, so bank-level UI must inherit the preceding emitted line. */
    fun addressAtOrBefore(fileId: String, lineIndex: Int): Int? {
        val entries = lineAddressesByFile[fileId] ?: return null
        var closestLine = -1
        var closestAddress: Int? = null
        var index = 0
        while (index < entries.size) {
            val mappedLine = entries[index + 1]
            if (mappedLine <= lineIndex && mappedLine >= closestLine) {
                closestLine = mappedLine
                closestAddress = entries[index]
            }
            index += 2
        }
        return closestAddress
    }

    fun addressesFor(fileId: String): Map<Int, Int> {
        val entries = lineAddressesByFile[fileId] ?: return emptyMap()
        return buildMap(entries.size / 2) {
            var index = 0
            while (index < entries.size) {
                putIfAbsent(entries[index + 1], entries[index])
                index += 2
            }
        }
    }

    fun addressesAt(fileId: String, lineIndex: Int): List<Int> {
        val entries = lineAddressesByFile[fileId] ?: return emptyList()
        return buildList {
            var index = 0
            while (index < entries.size) {
                if (entries[index + 1] == lineIndex) add(entries[index])
                index += 2
            }
        }.distinct()
    }

    fun locationsAt(snesAddress: Int): List<AsmCompiledLocation> =
        locationsByAddress[snesAddress].orEmpty()

    fun labelsAt(snesAddress: Int): List<String> = labelsByAddress[snesAddress].orEmpty()

    fun nearestLabelAtOrBefore(snesAddress: Int): Pair<String, Int>? {
        val bank = snesAddress ushr 16
        var low = 0
        var high = orderedLabels.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (orderedLabels[middle].value <= snesAddress) low = middle + 1 else high = middle
        }
        var index = low - 1
        while (index >= 0) {
            val candidate = orderedLabels[index--]
            val candidateBank = candidate.value ushr 16
            if (candidateBank < bank) return null
            if (candidateBank == bank) return candidate.key to candidate.value
        }
        return null
    }

    fun orderedLocations(): List<AsmCompiledLocation> = emittedLocations.ifEmpty {
        lineAddressesByFile.flatMap { (fileId, entries) ->
            buildList {
                var index = 0
                while (index < entries.size) {
                    add(AsmCompiledLocation(fileId, entries[index + 1], entries[index]))
                    index += 2
                }
            }
        }.sortedBy(AsmCompiledLocation::snesAddress)
    }

    fun nextBoundaryInBank(snesAddress: Int): Int? {
        val bank = snesAddress ushr 16
        var low = 0
        var high = compiledBoundaries.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compiledBoundaries[middle] <= snesAddress) low = middle + 1 else high = middle
        }
        var index = low
        while (index < compiledBoundaries.size) {
            val candidate = compiledBoundaries[index++]
            val candidateBank = candidate ushr 16
            if (candidateBank > bank) return null
            if (candidateBank == bank && snesLoRomToPc(candidate) != null) return candidate
        }
        return null
    }

    companion object {
        val EMPTY = AsmCompiledSymbols(emptyMap(), emptyMap())
    }
}

internal data class AsmBuildReport(
    val succeeded: Boolean,
    val generatedAt: String,
    val assemblerVersion: String,
    val output: List<String>,
    val diagnostics: List<AsmBuildDiagnostic>,
    val symbols: AsmCompiledSymbols,
    val validationOnly: Boolean = false,
    /** Disposable compiler inputs, retained only so generated-source errors can
     * show the exact failing statement without pretending it is authored. */
    val generatedSources: Map<String, List<String>> = emptyMap(),
)

internal object AsmSourceLinter {
    fun lintTree(
        sourceRoot: File,
        excludedFileIds: Set<String> = emptySet(),
    ): List<AsmBuildDiagnostic> {
        if (!sourceRoot.isDirectory) return emptyList()
        val sourceTexts = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
            .associate { file -> file.relativeTo(sourceRoot).invariantSeparatorsPath to file.readText() }
        val activeTexts = sourceTexts.filterKeys { it !in excludedFileIds }
        val macros = activeTexts.flatMap { (fileId, text) -> parseAsmMacros(fileId, text) }
        val index = if (File(sourceRoot, "main.asm").isFile) {
            runCatching {
                AsmSourceParser().parse(
                    sourceRoot,
                    File(sourceRoot.parentFile, "data"),
                    emptyList(),
                    sourceTexts = sourceTexts,
                )
            }.getOrNull()
        } else null
        val context = AsmEditorAnalysisContext(index, sourceTexts, excludedFileIds)
        return activeTexts.flatMap { (fileId, text) ->
            lintText(fileId, text, macros, context)
        }
    }

    fun lintText(
        fileId: String,
        text: String,
        macros: List<AsmMacro>? = null,
        context: AsmEditorAnalysisContext? = null,
    ): List<AsmBuildDiagnostic> {
        val analysis = context ?: AsmEditorAnalysisContext(
            index = null,
            sourceTexts = mapOf(fileId to text),
        )
        return AsmEditorAnalyzer.inspectFile(fileId, text, macros, analysis)
    }

    fun report(diagnostics: List<AsmBuildDiagnostic>): AsmBuildReport? {
        if (diagnostics.isEmpty()) return null
        return AsmBuildReport(
            // A validation snapshot is not compiled output, even when it contains
            // warnings only. Runtime/source-map consumers must keep requiring a
            // real successful Asar build.
            succeeded = false,
            generatedAt = Instant.now().toString(),
            assemblerVersion = "SMEDIT source validation",
            output = diagnostics.map(::format),
            diagnostics = diagnostics,
            symbols = AsmCompiledSymbols.EMPTY,
            validationOnly = true,
        )
    }

    fun format(diagnostic: AsmBuildDiagnostic): String {
        val file = diagnostic.fileId?.let { "src/$it" } ?: "source"
        val line = diagnostic.lineIndex?.plus(1) ?: 1
        return "$file:$line: ${diagnostic.severity.name.lowercase()}: (SMEDIT): ${diagnostic.message}"
    }
}

internal object AsmBuildOutputParser {
    private val locatedDiagnostic = Regex(
        "^(.+?):(\\d+):\\s*(warning|error):\\s*(?:\\([^)]+\\):\\s*)?(.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val pipelineLocation = Regex(
        "(?:^|:\\s+)((?:[A-Za-z]:)?[^:\\r\\n]+\\.asm):([1-9][0-9]*):\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** Planner/format checks can report an authored source location before Asar runs. */
    fun pipelineDiagnostic(message: String): AsmBuildDiagnostic {
        val match = pipelineLocation.find(message)
        val lineIndex = match?.groupValues?.get(2)?.toIntOrNull()?.minus(1)
        return if (match != null && lineIndex != null) {
            AsmBuildDiagnostic(AsmDiagnosticSeverity.ERROR, match.groupValues[3].trim(),
                sourceFileId(match.groupValues[1]), lineIndex)
        } else AsmBuildDiagnostic(AsmDiagnosticSeverity.ERROR, message)
    }

    private fun sourceFileId(sourcePath: String): String {
        val path = sourcePath.trim().replace('\\', '/')
        return path.substringAfterLast("/src/", path.substringAfter("src/"))
    }

    fun diagnostics(output: List<String>): List<AsmBuildDiagnostic> = output.mapNotNull { line ->
        // Keep explicit source locations; general pipeline checks remain unlocated.
        if (line.startsWith("SMEDIT: error: ")) {
            return@mapNotNull pipelineDiagnostic(line.removePrefix("SMEDIT: error: "))
        }
        val match = locatedDiagnostic.matchEntire(line.trim()) ?: return@mapNotNull null
        val fileId = sourceFileId(match.groupValues[1])
        val lineIndex = match.groupValues[2].toIntOrNull()?.minus(1)?.coerceAtLeast(0)
        val severity = if (match.groupValues[3].equals("error", ignoreCase = true)) {
            AsmDiagnosticSeverity.ERROR
        } else {
            AsmDiagnosticSeverity.WARNING
        }
        AsmBuildDiagnostic(
            severity = severity,
            message = match.groupValues[4].trim(),
            fileId = fileId.takeIf(String::isNotBlank),
            lineIndex = lineIndex,
        )
    }.distinct()
}

internal object AsmWlaSymbolParser {
    private enum class Section { NONE, LABELS, SOURCE_FILES, ADDRESS_LINES }

    private val labelLine = Regex("^([0-9A-Fa-f]{2}):([0-9A-Fa-f]{4})\\s+(.+)$")
    private val sourceFileLine = Regex("^([0-9A-Fa-f]{4})\\s+[0-9A-Fa-f]+\\s+(.+)$")
    private val addressLine = Regex(
        "^([0-9A-Fa-f]{2}):([0-9A-Fa-f]{4})\\s+([0-9A-Fa-f]{4}):([0-9A-Fa-f]{8})$",
    )

    fun parse(text: String): AsmCompiledSymbols {
        var section = Section.NONE
        val labels = linkedMapOf<String, Int>()
        val sourceFiles = mutableMapOf<Int, String>()
        val addresses = mutableMapOf<String, MutableList<Int>>()
        val emittedLocations = mutableListOf<AsmCompiledLocation>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when (line) {
                "[labels]" -> section = Section.LABELS
                "[source files]" -> section = Section.SOURCE_FILES
                "[addr-to-line mapping]" -> section = Section.ADDRESS_LINES
                "[rom checksum]" -> section = Section.NONE
                else -> when (section) {
                    Section.LABELS -> labelLine.matchEntire(line)?.let { match ->
                        val address = (match.groupValues[1].toInt(16) shl 16) or match.groupValues[2].toInt(16)
                        // WLA can list aliases. Keep the first definition for a
                        // stable name-to-address lookup while preserving the
                        // complete emitted line mapping below.
                        labels.putIfAbsent(match.groupValues[3], address)
                    }
                    Section.SOURCE_FILES -> sourceFileLine.matchEntire(line)?.let { match ->
                        val path = match.groupValues[2].replace('\\', '/')
                        sourceFiles[match.groupValues[1].toInt(16)] = path.substringAfter("src/")
                    }
                    Section.ADDRESS_LINES -> addressLine.matchEntire(line)?.let { match ->
                        val fileId = sourceFiles[match.groupValues[3].toInt(16)] ?: return@let
                        val address = (match.groupValues[1].toInt(16) shl 16) or match.groupValues[2].toInt(16)
                        val lineIndex = (match.groupValues[4].toLong(16) - 1L).coerceAtLeast(0L).toInt()
                        val entries = addresses.getOrPut(fileId) { mutableListOf() }
                        entries += address
                        entries += lineIndex
                        emittedLocations += AsmCompiledLocation(fileId, lineIndex, address)
                    }
                    Section.NONE -> Unit
                }
            }
        }
        return AsmCompiledSymbols(
            labels = labels,
            lineAddressesByFile = addresses.mapValues { (_, values) -> values.toIntArray() },
            emittedLocations = emittedLocations,
        )
    }
}

/** Retains the latest build feedback beside the generated project workspace.
 * The directory is ignored by Git and can be regenerated at any time. */
internal class AsmBuildArtifactRepository(
    private val workspaceRepository: AsmProjectWorkspaceRepository = AsmProjectWorkspaceRepository(),
    private val sourceFingerprintProvider: (String) -> String? = { projectFilePath ->
        fingerprintProjectSource(workspaceRepository, projectFilePath)
    },
) {
    fun publishSuccess(
        projectFilePath: String,
        symbols: ByteArray,
        output: List<String>,
        assemblerVersion: String,
        generatedRoomSource: String = "",
        generatedSources: Map<String, String> = emptyMap(),
    ) {
        val directory = writableBuildDirectory(projectFilePath) ?: return
        directory.mkdirs()
        writeBytesAtomically(File(directory, SYMBOLS_FILE), symbols)
        writeTextAtomically(
            File(directory, AsmGeneratedRoomSource.FILE_NAME),
            generatedRoomSource.ifBlank { "; This compilation has no generated room source.\n" },
        )
        publishGeneratedSources(directory, generatedSources)
        writeTextAtomically(
            File(directory, OUTPUT_FILE),
            output.joinToString("\n", postfix = if (output.isEmpty()) "" else "\n"),
        )
        writeMetadata(
            directory = directory,
            succeeded = true,
            assemblerVersion = assemblerVersion,
            sourceFingerprint = sourceFingerprintProvider(projectFilePath).orEmpty(),
        )
    }

    fun publishFailure(
        projectFilePath: String,
        output: List<String>,
        assemblerVersion: String = "",
        generatedSources: Map<String, String> = emptyMap(),
    ) {
        val directory = writableBuildDirectory(projectFilePath) ?: return
        directory.mkdirs()
        writeTextAtomically(
            File(directory, OUTPUT_FILE),
            output.joinToString("\n", postfix = if (output.isEmpty()) "" else "\n"),
        )
        publishGeneratedSources(directory, generatedSources)
        generatedSources["__smedit_generated_rooms.asm"]?.let { source ->
            writeTextAtomically(File(directory, AsmGeneratedRoomSource.FILE_NAME), source)
        }
        writeMetadata(
            directory = directory,
            succeeded = false,
            assemblerVersion = assemblerVersion,
            sourceFingerprint = sourceFingerprintProvider(projectFilePath).orEmpty(),
        )
    }

    fun publishPipelineFailure(projectFilePath: String, message: String) {
        val previous = load(projectFilePath)
        // Preserve Asar's line-specific diagnostics when compilation itself failed.
        if (previous?.succeeded == false && previous.diagnostics.any {
                it.fileId != null && it.severity == AsmDiagnosticSeverity.ERROR
            }
        ) return
        publishFailure(
            projectFilePath,
            listOf("SMEDIT: error: ${message.lineSequence().joinToString(" ")}"),
            previous?.assemblerVersion.orEmpty(),
        )
    }

    fun load(projectFilePath: String): AsmBuildReport? {
        val directory = buildDirectory(projectFilePath) ?: return null
        val metadataFile = File(directory, METADATA_FILE)
        if (!metadataFile.isFile) return null
        return runCatching {
            val properties = Properties().apply { metadataFile.inputStream().use(::load) }
            val recordedFingerprint = properties.getProperty("sourceFingerprint").orEmpty()
            val currentFingerprint = sourceFingerprintProvider(projectFilePath)
            if (recordedFingerprint.isBlank() || currentFingerprint == null || recordedFingerprint != currentFingerprint) {
                return@runCatching null
            }
            val output = File(directory, OUTPUT_FILE).takeIf(File::isFile)?.readLines().orEmpty()
            val symbols = File(directory, SYMBOLS_FILE).takeIf(File::isFile)
                ?.readText()
                ?.let(AsmWlaSymbolParser::parse)
                ?: AsmCompiledSymbols.EMPTY
            AsmBuildReport(
                succeeded = properties.getProperty("succeeded").toBooleanStrict(),
                generatedAt = properties.getProperty("generatedAt").orEmpty(),
                assemblerVersion = properties.getProperty("assemblerVersion").orEmpty(),
                output = output,
                diagnostics = AsmBuildOutputParser.diagnostics(output),
                symbols = symbols,
                generatedSources = loadGeneratedSources(directory),
            )
        }.getOrNull()
    }

    /** Unlike diagnostics this remains useful after source edits/failures. It is
     * explicitly a snapshot of a successful compilation, never live GUI data
     * or proof that the final ROM export succeeded. Empty compilations clear it. */
    fun loadGeneratedRooms(projectFilePath: String): AsmGeneratedRoomSource? {
        return loadRoomDocument(projectFilePath, AsmGeneratedRoomSource.FILE_NAME)
    }

    /** Published only after the complete ROM transaction succeeds, even with no GUI edits. */
    fun publishRoomData(projectFilePath: String, source: String) {
        val directory = writableBuildDirectory(projectFilePath) ?: return
        directory.mkdirs()
        writeTextAtomically(File(directory, AsmRoomDataSnapshot.FILE_NAME), source)
    }

    fun loadRoomData(projectFilePath: String): AsmGeneratedRoomSource? =
        loadRoomDocument(projectFilePath, AsmRoomDataSnapshot.FILE_NAME)

    private fun loadRoomDocument(projectFilePath: String, fileName: String): AsmGeneratedRoomSource? {
        val directory = buildDirectory(projectFilePath) ?: return null
        val file = File(directory, fileName)
        return runCatching {
            if (!file.isFile) return@runCatching null
            AsmGeneratedRoomSource.parse(file.readText(), Instant.ofEpochMilli(file.lastModified()).toString())
        }.getOrNull()
    }

    private fun buildDirectory(projectFilePath: String): File? =
        workspaceRepository.cacheFor(projectFilePath)?.let { File(it, BUILD_DIRECTORY) }

    private fun writableBuildDirectory(projectFilePath: String): File? {
        val root = workspaceRepository.cacheFor(projectFilePath) ?: return null
        ensureBuildIgnored(root)
        return File(root, BUILD_DIRECTORY)
    }

    private fun ensureBuildIgnored(root: File) {
        root.mkdirs()
        val ignore = File(root, ".gitignore")
        val existing = ignore.takeIf(File::isFile)?.readText().orEmpty()
        if (existing.lineSequence().any { it.trim() == "/$BUILD_DIRECTORY/" }) return
        val separator = if (existing.isEmpty() || existing.endsWith('\n')) "" else "\n"
        writeTextAtomically(ignore, existing + separator + "/$BUILD_DIRECTORY/\n")
    }

    private fun publishGeneratedSources(directory: File, sources: Map<String, String>) {
        val generated = File(directory, GENERATED_DIRECTORY)
        if (generated.isDirectory) {
            generated.listFiles()?.filter(File::isFile)?.forEach { Files.deleteIfExists(it.toPath()) }
        }
        if (sources.isEmpty()) return
        generated.mkdirs()
        sources.forEach { (fileId, source) ->
            require('/' !in fileId && '\\' !in fileId && fileId.endsWith(".asm", ignoreCase = true)) {
                "Invalid generated ASM artifact name: $fileId"
            }
            writeTextAtomically(File(generated, fileId), source)
        }
    }

    private fun loadGeneratedSources(directory: File): Map<String, List<String>> {
        val generated = File(directory, GENERATED_DIRECTORY)
        return generated.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
            .sortedBy { it.name }
            .associate { it.name to it.readLines() }
    }

    private fun writeMetadata(
        directory: File,
        succeeded: Boolean,
        assemblerVersion: String,
        sourceFingerprint: String,
    ) {
        val properties = Properties().apply {
            setProperty("succeeded", succeeded.toString())
            setProperty("generatedAt", Instant.now().toString())
            setProperty("assemblerVersion", assemblerVersion.lineSequence().firstOrNull().orEmpty())
            setProperty("sourceFingerprint", sourceFingerprint)
        }
        val target = File(directory, METADATA_FILE)
        val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output -> properties.store(output, "SMEDIT ASM build report") }
            moveAtomically(temporary, target)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun writeTextAtomically(target: File, text: String) = writeBytesAtomically(target, text.toByteArray())

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            moveAtomically(temporary, target)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val BUILD_DIRECTORY = "build"
        const val METADATA_FILE = "report.properties"
        const val OUTPUT_FILE = "asar.log"
        const val SYMBOLS_FILE = "symbols.sym"
        const val GENERATED_DIRECTORY = "generated"

        fun fingerprintProjectSource(
            workspaceRepository: AsmProjectWorkspaceRepository,
            projectFilePath: String,
        ): String? {
            val workspace = workspaceRepository.loadForBuild(projectFilePath) ?: return null
            val sourceRoot = File(workspace.workingDirectory, "src")
            if (!sourceRoot.isDirectory) return null
            val files = sourceRoot.walkTopDown()
                .filter(File::isFile)
                .sortedBy { it.relativeTo(sourceRoot).invariantSeparatorsPath }
                .toList()
            val digest = MessageDigest.getInstance("SHA-256")
            val separator = byteArrayOf(0)
            files.forEach { file ->
                digest.update(file.relativeTo(sourceRoot).invariantSeparatorsPath.toByteArray())
                digest.update(separator)
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.update(separator)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
