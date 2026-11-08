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

/** Compact representation of Asar's WLA symbol map. Address/line pairs are
 * stored as primitive arrays: [SNES address, zero-based line, ...]. */
internal data class AsmCompiledSymbols(
    val labels: Map<String, Int>,
    val lineAddressesByFile: Map<String, IntArray>,
) {
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
)

internal object AsmSourceLinter {
    private val dataDirective = Regex("^\\s*(db|dw|dl|dd)\\b(.*)$", RegexOption.IGNORE_CASE)
    private val hexLiteral = Regex("\\$([0-9A-Fa-f]+)")
    private val widths = mapOf("db" to 2, "dw" to 4, "dl" to 6, "dd" to 8)

    fun lintTree(
        sourceRoot: File,
        excludedFileIds: Set<String> = emptySet(),
    ): List<AsmBuildDiagnostic> {
        if (!sourceRoot.isDirectory) return emptyList()
        val sourceFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
            .filterNot { it.relativeTo(sourceRoot).invariantSeparatorsPath in excludedFileIds }
            .map { file -> file.relativeTo(sourceRoot).invariantSeparatorsPath to file.readText() }
            .toList()
        val macros = sourceFiles.flatMap { (fileId, text) -> parseAsmMacros(fileId, text) }
        return sourceFiles.flatMap { (fileId, text) ->
            lintText(fileId, text, macros)
        }
    }

    fun lintText(
        fileId: String,
        text: String,
        macros: List<AsmMacro>? = null,
    ): List<AsmBuildDiagnostic> = buildList {
        val macrosByName = macros?.associateBy(AsmMacro::name)
        text.lineSequence().forEachIndexed { lineIndex, sourceLine ->
            val code = asmCodeBeforeComment(sourceLine)
            splitStatements(code).forEach statementLoop@{ statement ->
                val match = dataDirective.matchEntire(statement) ?: return@statementLoop
                val directive = match.groupValues[1].lowercase()
                val digits = checkNotNull(widths[directive])
                val operand = match.groupValues[2]
                val explicitMask = "&\\s*\\$${"F".repeat(digits)}\\b".toRegex(RegexOption.IGNORE_CASE)
                if (explicitMask.containsMatchIn(operand)) return@statementLoop
                hexLiteral.findAll(operand).firstOrNull { it.groupValues[1].length > digits }?.let { literal ->
                    val bits = digits * 4
                    add(
                        AsmBuildDiagnostic(
                            severity = AsmDiagnosticSeverity.ERROR,
                            message = "${literal.value} does not fit $directive ($bits bits); Asar would silently keep only the low $bits bits. Use an in-range value or mask explicitly with &\$${"F".repeat(digits)} if truncation is intentional.",
                            fileId = fileId,
                            lineIndex = lineIndex,
                        ),
                    )
                }
            }
            if (macrosByName != null) {
                parseMacroCalls(code).forEach { call ->
                    val macro = macrosByName[call.name]
                        ?: macros.firstOrNull { it.name.equals(call.name, ignoreCase = true) }
                    if (macro == null) {
                        add(
                            AsmBuildDiagnostic(
                                severity = AsmDiagnosticSeverity.ERROR,
                                message = "Unknown macro %${call.name}(...).",
                                fileId = fileId,
                                lineIndex = lineIndex,
                            ),
                        )
                        return@forEach
                    }
                    val fixedParameters = macro.parameters.count { it != "..." }
                    val validArity = if (macro.variadic) {
                        call.argumentCount >= fixedParameters
                    } else {
                        call.argumentCount == fixedParameters
                    }
                    if (validArity) return@forEach
                    val expectation = if (macro.variadic) "at least $fixedParameters" else fixedParameters.toString()
                    add(
                        AsmBuildDiagnostic(
                            severity = AsmDiagnosticSeverity.ERROR,
                            message = "${macro.signature} expects $expectation argument${if (fixedParameters == 1 && !macro.variadic) "" else "s"}; found ${call.argumentCount}.",
                            fileId = fileId,
                            lineIndex = lineIndex,
                        ),
                    )
                }
            }
        }
    }

    fun report(diagnostics: List<AsmBuildDiagnostic>): AsmBuildReport? {
        if (diagnostics.isEmpty()) return null
        return AsmBuildReport(
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

    private fun splitStatements(code: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        code.forEachIndexed { index, character ->
            if (character == '"') quoted = !quoted
            val addressColon = character == ':' && !quoted &&
                current.takeLast(3).matches(Regex("\\$[0-9A-Fa-f]{2}")) &&
                code.drop(index + 1).take(4).matches(Regex("[0-9A-Fa-f]{4}"))
            if (character == ':' && !quoted && !addressColon) {
                result += current.toString()
                current.clear()
            } else {
                current.append(character)
            }
        }
        result += current.toString()
        return result
    }

    private data class MacroCall(val name: String, val argumentCount: Int)

    private val macroCallStart = Regex("%([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")

    private fun parseMacroCalls(code: String): List<MacroCall> = macroCallStart.findAll(code).mapNotNull { match ->
        if (asmOffsetInsideString(code, match.range.first)) return@mapNotNull null
        val open = match.range.last
        var depth = 1
        var quoted = false
        var escaped = false
        var close = -1
        var cursor = open + 1
        while (cursor < code.length && close < 0) {
            val character = code[cursor]
            when {
                escaped -> escaped = false
                character == '\\' && quoted -> escaped = true
                character == '"' -> quoted = !quoted
                !quoted && character == '(' -> depth++
                !quoted && character == ')' -> {
                    depth--
                    if (depth == 0) close = cursor
                }
            }
            cursor++
        }
        if (close < 0) return@mapNotNull null
        val arguments = code.substring(open + 1, close)
        MacroCall(match.groupValues[1], countMacroArguments(arguments))
    }.toList()

    private fun countMacroArguments(arguments: String): Int {
        if (arguments.isBlank()) return 0
        var count = 1
        var depth = 0
        var quoted = false
        var escaped = false
        arguments.forEach { character ->
            when {
                escaped -> escaped = false
                character == '\\' && quoted -> escaped = true
                character == '"' -> quoted = !quoted
                !quoted && character == '(' -> depth++
                !quoted && character == ')' -> depth--
                !quoted && character == ',' && depth == 0 -> count++
            }
        }
        return count
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
                    }
                    Section.NONE -> Unit
                }
            }
        }
        return AsmCompiledSymbols(
            labels = labels,
            lineAddressesByFile = addresses.mapValues { (_, values) -> values.toIntArray() },
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
    ) {
        val directory = writableBuildDirectory(projectFilePath) ?: return
        directory.mkdirs()
        writeBytesAtomically(File(directory, SYMBOLS_FILE), symbols)
        writeTextAtomically(
            File(directory, AsmGeneratedRoomSource.FILE_NAME),
            generatedRoomSource.ifBlank { "; This compilation has no generated room source.\n" },
        )
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

    fun publishFailure(projectFilePath: String, output: List<String>, assemblerVersion: String = "") {
        val directory = writableBuildDirectory(projectFilePath) ?: return
        directory.mkdirs()
        writeTextAtomically(
            File(directory, OUTPUT_FILE),
            output.joinToString("\n", postfix = if (output.isEmpty()) "" else "\n"),
        )
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
