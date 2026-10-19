package com.supermetroid.editor.asm

import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream

/** The same immutable source revision used by SMEDIT's parity harness. */
internal object AsmReferenceContract {
    const val REPOSITORY_URL = "https://github.com/InsaneFirebat/sm_disassembly.git"
    const val COMMIT = "11c906f547edc1b57f5a5923cf977fe7b50a3694"
    const val ASSET_COUNT = 1130
    const val MINIMUM_ROM_SIZE = 0x300000
    const val MANIFEST_RESOURCE = "/asm-reference/asset-manifest.tsv"

    val archiveUrl: String
        get() = "${REPOSITORY_URL.removeSuffix(".git")}/archive/$COMMIT.zip"
}

@Serializable
internal data class AsmAssetRange(
    val path: String,
    val pcOffset: Int,
    val length: Int,
) {
    val endExclusive: Int get() = pcOffset + length
    val snesAddress: Int
        get() = checkNotNull(pcToSnesLoRom(pcOffset)) { "PC offset is outside LoROM range: $pcOffset" }
}

internal object AsmAssetManifest {
    fun loadBundled(): List<AsmAssetRange> {
        val stream = checkNotNull(AsmAssetManifest::class.java.getResourceAsStream(AsmReferenceContract.MANIFEST_RESOURCE)) {
            "Missing ${AsmReferenceContract.MANIFEST_RESOURCE}"
        }
        val lines = stream.bufferedReader().use { it.readLines() }
        val generatedCommit = lines.firstOrNull { it.startsWith("# sm_disassembly.commit=") }
            ?.substringAfter('=')
        require(generatedCommit == AsmReferenceContract.COMMIT) {
            "ASM asset manifest revision $generatedCommit does not match ${AsmReferenceContract.COMMIT}"
        }
        return lines.asSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .map { line ->
                val fields = line.split('\t')
                require(fields.size == 3) { "Invalid ASM asset manifest row: $line" }
                AsmAssetRange(
                    path = fields[0],
                    pcOffset = fields[1].removePrefix("0x").toInt(16),
                    length = fields[2].removePrefix("0x").toInt(16),
                )
            }
            .toList()
            .also { ranges ->
                require(ranges.size == AsmReferenceContract.ASSET_COUNT) {
                    "Expected ${AsmReferenceContract.ASSET_COUNT} ASM assets, found ${ranges.size}"
                }
                require(ranges.map { it.path }.distinct().size == ranges.size) { "Duplicate ASM asset paths" }
            }
    }
}

internal data class AsmSection(
    val title: String,
    val lineIndex: Int,
    val address: String? = null,
)

internal data class AsmLabel(
    val name: String,
    val fileId: String,
    val lineIndex: Int,
    val localScope: String? = null,
) {
    val symbolId: AsmSymbolId get() = AsmSymbolId(fileId, lineIndex, name)
}

internal data class AsmSymbolId(
    val fileId: String,
    val lineIndex: Int,
    val name: String,
)

internal data class AsmLabelUsage(
    val symbolId: AsmSymbolId,
    override val fileId: String,
    override val lineIndex: Int,
    override val column: Int,
    val token: String,
    override val sourceLine: String,
) : AsmSourceUsage

internal interface AsmSourceUsage {
    val fileId: String
    val lineIndex: Int
    val column: Int
    val sourceLine: String
}

internal data class AsmMacroId(
    val fileId: String,
    val lineIndex: Int,
    val name: String,
)

internal data class AsmMacroUsage(
    val macroId: AsmMacroId,
    override val fileId: String,
    override val lineIndex: Int,
    override val column: Int,
    val token: String,
    override val sourceLine: String,
) : AsmSourceUsage

internal data class AsmMacro(
    val name: String,
    val parameters: List<String>,
    val variadic: Boolean,
    val fileId: String,
    val lineIndex: Int,
) {
    val id: AsmMacroId get() = AsmMacroId(fileId, lineIndex, name)
    val signature: String
        get() = "%$name(${parameters.joinToString(", ")})"
}

private val ASM_MACRO_DEFINITION_REGEX =
    Regex("^\\s*macro\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)

internal fun parseAsmMacroDefinition(fileId: String, lineIndex: Int, line: String): AsmMacro? {
    val match = ASM_MACRO_DEFINITION_REGEX.find(asmCodeBeforeComment(line)) ?: return null
    val parameters = match.groupValues[2].split(',').map(String::trim).filter(String::isNotEmpty)
    return AsmMacro(
        name = match.groupValues[1],
        parameters = parameters,
        variadic = parameters.lastOrNull() == "...",
        fileId = fileId,
        lineIndex = lineIndex,
    )
}

internal fun parseAsmMacros(fileId: String, text: String): List<AsmMacro> =
    text.lineSequence().mapIndexedNotNull { lineIndex, line ->
        parseAsmMacroDefinition(fileId, lineIndex, line)
    }.toList()

internal data class AsmSourceFile(
    val id: String,
    val file: File,
    val displayName: String,
    val description: String,
    val isBank: Boolean,
    val lines: List<String>,
    val sections: List<AsmSection>,
    /** Active Asar label hierarchy at each line. The first component is the
     * global label; following components are the most recent `.local`,
     * `..nested`, and deeper declarations. */
    val scopePathByLine: List<List<String>>,
)

internal data class AsmAsset(
    val range: AsmAssetRange,
    val file: File,
) {
    val category: String get() = assetCategory(range.path)
}

internal data class AsmReferenceIndex(
    val files: List<AsmSourceFile>,
    val labels: List<AsmLabel>,
    val assets: List<AsmAsset>,
    val addressAtlas: AsmAddressAtlas,
    val macros: List<AsmMacro> = emptyList(),
) {
    private val filesById = files.associateBy { it.id }
    private val globalLabels = labels.filter { it.localScope == null }.groupBy { it.name }
    private val localLabels = labels.filter { it.localScope != null }
        .groupBy { Triple(it.fileId, it.localScope, it.name) }
    private val assetsByPath = assets.associateBy { it.range.path }
    private val labelsByLocation = labels.groupBy { it.fileId to it.lineIndex }
    private val labelsBySymbol = labels.associateBy(AsmLabel::symbolId)
    private val usagesBySymbol = buildLabelUsages()
    private val macrosByName = macros.associateBy(AsmMacro::name)
    private val macrosByLocation = macros.groupBy { it.fileId to it.lineIndex }
    private val macrosById = macros.associateBy(AsmMacro::id)
    private val macroUsagesById = buildMacroUsages()

    fun file(id: String?): AsmSourceFile? = id?.let(filesById::get)

    fun asset(path: String?): AsmAsset? = path?.let(assetsByPath::get)

    fun labelsAt(fileId: String, lineIndex: Int): List<AsmLabel> =
        labelsByLocation[fileId to lineIndex].orEmpty()

    fun label(symbolId: AsmSymbolId?): AsmLabel? = symbolId?.let(labelsBySymbol::get)

    fun definition(fileId: String, lineIndex: Int, token: String): AsmLabel? =
        labelsAt(fileId, lineIndex).firstOrNull { label ->
            val canonical = asmCanonicalSymbolToken(token)
            label.name == canonical || label.name.substringAfterLast('.') == canonical.removePrefix(".")
        }

    fun usagesFor(label: AsmLabel): List<AsmLabelUsage> =
        labelsAt(label.fileId, label.lineIndex)
            .flatMap { declaration -> usagesBySymbol[declaration.symbolId].orEmpty() }
            .distinctBy { usage -> Triple(usage.fileId, usage.lineIndex, usage.column) }
            .sortedWith(compareBy(AsmLabelUsage::fileId, AsmLabelUsage::lineIndex, AsmLabelUsage::column))

    fun macrosAt(fileId: String, lineIndex: Int): List<AsmMacro> =
        macrosByLocation[fileId to lineIndex].orEmpty()

    fun macro(id: AsmMacroId?): AsmMacro? = id?.let(macrosById::get)

    fun usagesFor(macro: AsmMacro): List<AsmMacroUsage> = macroUsagesById[macro.id].orEmpty()

    fun resolveLabel(fileId: String, lineIndex: Int, token: String): AsmLabel? {
        val canonical = asmCanonicalSymbolToken(token)
        return if (canonical.startsWith('.')) {
            val scope = localScopeAt(fileId, lineIndex, asmLocalLabelDepth(canonical)) ?: return null
            localLabels[Triple(fileId, scope, canonical)]?.lastOrNull()
        } else {
            globalLabels[canonical]?.singleOrNull() ?: globalLabels[canonical]?.firstOrNull()
        }
    }

    fun localScopeAt(fileId: String, lineIndex: Int, depth: Int): String? {
        if (depth <= 0) return null
        val path = filesById[fileId]?.scopePathByLine?.getOrNull(lineIndex).orEmpty()
        if (path.size < depth) return null
        return path.take(depth).joinToString("_")
    }

    /** Editing must never inherit navigation's best-effort choice among ambiguous declarations. */
    fun resolveUniqueLabel(fileId: String, lineIndex: Int, token: String): AsmLabel? {
        val canonical = asmCanonicalSymbolToken(token)
        return if (canonical.startsWith('.')) {
            val scope = localScopeAt(fileId, lineIndex, asmLocalLabelDepth(canonical)) ?: return null
            localLabels[Triple(fileId, scope, canonical)]?.singleOrNull()
        } else globalLabels[canonical]?.singleOrNull()
    }

    fun resolveAsset(reference: String): AsmAsset? {
        val normalized = reference.replace('\\', '/').substringAfterLast("/data/")
            .removePrefix("data/")
            .removePrefix("../data/")
            .removePrefix("./data/")
        return assetsByPath[normalized]
    }

    fun resolveMacro(token: String): AsmMacro? {
        val name = token.removePrefix("%").substringBefore('(')
        return macrosByName[name] ?: macros.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun buildLabelUsages(): Map<AsmSymbolId, List<AsmLabelUsage>> {
        val bySymbol = linkedMapOf<AsmSymbolId, MutableList<AsmLabelUsage>>()
        files.forEach { source ->
            source.lines.forEachIndexed { lineIndex, sourceLine ->
                val code = asmCodeBeforeComment(sourceLine)
                if (ASM_FILE_DIRECTIVE_REGEX.containsMatchIn(code)) {
                    return@forEachIndexed
                }
                AsmSourceLexing.references(code).forEach tokenLoop@{ match ->
                    val token = match.value
                    if (token.startsWith('"')) return@tokenLoop
                    val target = resolveLabel(source.id, lineIndex, token) ?: return@tokenLoop
                    // Do not count the name that declares a symbol as a use, but retain
                    // legitimate same-line references such as `Table: dw Table`.
                    if (
                        target.fileId == source.id &&
                        target.lineIndex == lineIndex &&
                        isAsmDefinitionOccurrence(code, match.range)
                    ) return@tokenLoop
                    bySymbol.getOrPut(target.symbolId) { mutableListOf() } += AsmLabelUsage(
                        symbolId = target.symbolId,
                        fileId = source.id,
                        lineIndex = lineIndex,
                        column = match.range.first,
                        token = token,
                        sourceLine = sourceLine.trim(),
                    )
                }
            }
        }
        return bySymbol.mapValues { (_, usages) ->
            usages.sortedWith(compareBy(AsmLabelUsage::fileId, AsmLabelUsage::lineIndex, AsmLabelUsage::column))
        }
    }

    private fun buildMacroUsages(): Map<AsmMacroId, List<AsmMacroUsage>> {
        val byMacro = linkedMapOf<AsmMacroId, MutableList<AsmMacroUsage>>()
        files.forEach { source ->
            source.lines.forEachIndexed { lineIndex, sourceLine ->
                val code = asmCodeBeforeComment(sourceLine)
                AsmSourceLexing.references(code).forEach tokenLoop@{ match ->
                    val token = match.value
                    if (!token.startsWith('%')) return@tokenLoop
                    val macro = resolveMacro(token) ?: return@tokenLoop
                    byMacro.getOrPut(macro.id) { mutableListOf() } += AsmMacroUsage(
                        macroId = macro.id,
                        fileId = source.id,
                        lineIndex = lineIndex,
                        column = match.range.first,
                        token = token,
                        sourceLine = sourceLine.trim(),
                    )
                }
            }
        }
        return byMacro.mapValues { (_, usages) ->
            usages.sortedWith(compareBy(AsmMacroUsage::fileId, AsmMacroUsage::lineIndex, AsmMacroUsage::column))
        }
    }
}

private val ASM_FILE_DIRECTIVE_REGEX = Regex("^\\s*(?:incsrc|incbin)\\b", RegexOption.IGNORE_CASE)

internal fun asmLocalLabelDepth(token: String): Int = token.indexOfFirst { it != '.' }
    .let { firstNonDot -> if (firstNonDot < 0) token.length else firstNonDot }

internal fun asmCanonicalSymbolToken(token: String): String =
    token.replace(Regex("\\[[^]\\r\\n]+]"), "")

internal data class AsmUnresolvedSourceReference(
    val fileId: String,
    val lineIndex: Int,
    val column: Int,
    val token: String,
    val sourceLine: String,
)

internal data class AsmSourceReferenceCoverage(
    val referenceCount: Int,
    val unresolved: List<AsmUnresolvedSourceReference>,
)

/**
 * Audits the source references that Asar proves are real emitted symbols, plus
 * source-local and define references whose identities do not appear verbatim in
 * WLA output. Macro-local labels are deliberately excluded: their concrete
 * scope exists only at each expansion site.
 */
internal object AsmSourceReferenceAuditor {
    fun audit(index: AsmReferenceIndex, compiled: AsmCompiledSymbols): AsmSourceReferenceCoverage {
        var referenceCount = 0
        val unresolved = mutableListOf<AsmUnresolvedSourceReference>()
        index.files.forEach { source ->
            var insideMacro = false
            source.lines.forEachIndexed { lineIndex, sourceLine ->
                if (parseAsmMacroDefinition(source.id, lineIndex, sourceLine) != null) insideMacro = true
                val code = asmCodeBeforeComment(sourceLine)
                if (ASM_FILE_DIRECTIVE_REGEX.containsMatchIn(code)) return@forEachIndexed
                AsmSourceLexing.references(code).forEach tokenLoop@{ match ->
                    val token = match.value
                    if (token.startsWith('"') || token.startsWith('%')) return@tokenLoop
                    if (isAsmDefinitionOccurrence(code, match.range)) return@tokenLoop
                    if (insideMacro && token.startsWith('.')) return@tokenLoop
                    val canonical = asmCanonicalSymbolToken(token)
                    val navigable = token.startsWith('.') ||
                        token.startsWith('!') ||
                        canonical in compiled.labels
                    if (!navigable) return@tokenLoop
                    referenceCount++
                    if (index.resolveLabel(source.id, lineIndex, token) == null) {
                        unresolved += AsmUnresolvedSourceReference(
                            fileId = source.id,
                            lineIndex = lineIndex,
                            column = match.range.first,
                            token = token,
                            sourceLine = sourceLine.trim(),
                        )
                    }
                }
                if (sourceLine.trimStart().startsWith("endmacro", ignoreCase = true)) insideMacro = false
            }
        }
        return AsmSourceReferenceCoverage(
            referenceCount = referenceCount,
            unresolved = unresolved.distinctBy { Triple(it.fileId, it.lineIndex, it.column) },
        )
    }
}

internal fun asmCodeBeforeComment(line: String): String {
    var quoted = false
    var escaped = false
    line.forEachIndexed { index, character ->
        when {
            escaped -> escaped = false
            character == '\\' && quoted -> escaped = true
            character == '"' -> quoted = !quoted
            character == ';' && !quoted -> return line.substring(0, index)
        }
    }
    return line
}

internal fun asmOffsetInsideString(text: String, requestedOffset: Int): Boolean {
    var quoted = false
    var escaped = false
    text.take(requestedOffset.coerceIn(0, text.length)).forEach { character ->
        when {
            escaped -> escaped = false
            character == '\\' && quoted -> escaped = true
            character == '"' -> quoted = !quoted
        }
    }
    return quoted
}

private fun isAsmDefinitionOccurrence(code: String, range: IntRange): Boolean {
    val suffix = code.substring(range.last + 1).trimStart()
    if (suffix.startsWith(':')) return true
    if (
        code.substring(range.first).startsWith('!') &&
        (suffix.startsWith('=') || suffix.startsWith("?=") || suffix.startsWith("#="))
    ) return true
    val prefix = code.substring(0, range.first).trimEnd()
    if (
        prefix.isEmpty() &&
        (suffix.isEmpty() || suffix.matches(Regex("(?:skip|db|dw|dl|dd)\\b.*", RegexOption.IGNORE_CASE)))
    ) return true
    return prefix.endsWith("struct", ignoreCase = true) &&
        prefix.dropLast("struct".length).lastOrNull()?.let { it.isLetterOrDigit() || it == '_' } != true
}

@Serializable
internal data class AsmReferenceMetadata(
    val repositoryUrl: String,
    val commit: String,
    val installedAt: String,
    val romName: String,
    val romSha256: String,
    val romSize: Int,
    val assetCount: Int,
)

internal data class AsmReferenceWorkspace(
    val root: File,
    val metadata: AsmReferenceMetadata,
    val index: AsmReferenceIndex,
)

internal class AsmSourceParser {
    private val includeRegex = Regex("""^\s*incsrc\s+\"?([^\";\s]+)\"?\s*(?:;\s*(.*))?$""", RegexOption.IGNORE_CASE)
    private val sectionRegex = Regex("""^\s*;;;\s*(.*?)\s*;;;\s*$""")
    private val sectionAddressRegex = Regex("""^\$([0-9A-Fa-f]{4,6}):\s*(.*)$""")
    private val labelRegex = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*|\.+[A-Za-z0-9_]+):""")
    private val bareLocalLabelRegex = Regex("""^\s*(\.+[A-Za-z0-9_]+)\s*(?:;.*)?$""")
    private val structMemberRegex = Regex(
        """^\s*(\.[A-Za-z0-9_]+)\s+(?:skip|db|dw|dl|dd)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val defineRegex = Regex("""^\s*(![A-Za-z_][A-Za-z0-9_]*)\s*(?:\?=|#=|=)""")
    private val structRegex = Regex("""^\s*struct\s+([A-Za-z_][A-Za-z0-9_]*)\b""", RegexOption.IGNORE_CASE)
    private val endStructRegex = Regex("""^\s*endstruct\b""", RegexOption.IGNORE_CASE)

    fun parse(sourceDirectory: File, dataDirectory: File, ranges: List<AsmAssetRange>,
              fileIds: Set<String>? = null, sourceTexts: Map<String, String>? = null): AsmReferenceIndex {
        require(sourceDirectory.isDirectory) { "ASM source directory is missing: $sourceDirectory" }
        val mainFile = File(sourceDirectory, "main.asm")
        require(mainFile.isFile) { "ASM entry point is missing: $mainFile" }
        fun lines(fileId: String, file: File) = sourceTexts?.get(fileId)?.reader()?.readLines() ?: file.readLines()
        val mainLines = lines("main.asm", mainFile)
        val descriptions = linkedMapOf<String, String>()
        val orderedNames = mutableListOf<String>()
        mainLines.forEach { line ->
            includeRegex.matchEntire(line)?.let { match ->
                val name = match.groupValues[1]
                orderedNames += name
                match.groupValues[2].trim().takeIf(String::isNotEmpty)?.let { descriptions[name] = it }
            }
        }

        val allAsm = sourceDirectory.walkTopDown()
            .filter { it.isFile && it.extension.equals("asm", ignoreCase = true) }
            .filter { fileIds == null || it.relativeTo(sourceDirectory).invariantSeparatorsPath.let { id -> id in fileIds || id == "main.asm" } }
            .toList()
        val relativeNames = allAsm.associateBy { it.relativeTo(sourceDirectory).invariantSeparatorsPath }
        val sourceNames = buildList {
            add("main.asm")
            addAll(orderedNames.filterNot { it.substringAfterLast('/').startsWith("bank_", ignoreCase = true) })
            addAll(relativeNames.keys.filter { it.substringAfterLast('/').startsWith("bank_", ignoreCase = true) }.sorted())
            addAll(relativeNames.keys.sorted().filterNot { name ->
                name == "main.asm" || name in orderedNames || name.substringAfterLast('/').startsWith("bank_", ignoreCase = true)
            })
        }.distinct()

        val labels = mutableListOf<AsmLabel>()
        val macros = mutableListOf<AsmMacro>()
        val files = sourceNames.mapNotNull { relativeName ->
            val source = relativeNames[relativeName] ?: return@mapNotNull null
            val lines = lines(relativeName, source)
            val sections = mutableListOf<AsmSection>()
            val scopePaths = MutableList<List<String>>(lines.size) { emptyList() }
            val scopePath = mutableListOf<String>()
            var structScope: String? = null
            var insideMacro = false
            lines.forEachIndexed { lineIndex, line ->
                val macro = parseAsmMacroDefinition(relativeName, lineIndex, line)
                macro?.let(macros::add)
                if (macro != null) insideMacro = true
                structRegex.find(line)?.groupValues?.get(1)?.takeUnless { insideMacro }?.let { name ->
                    structScope = name
                    scopePath.clear()
                    scopePath += name
                    labels += AsmLabel(name, relativeName, lineIndex)
                }
                val labelName = if (insideMacro) {
                    null
                } else {
                    labelRegex.find(line)?.groupValues?.get(1)
                        ?: bareLocalLabelRegex.matchEntire(line)?.groupValues?.get(1)
                        ?: structScope?.let { structMemberRegex.find(line)?.groupValues?.get(1) }
                }
                if (labelName != null) {
                    if (!labelName.startsWith('.')) {
                        scopePath.clear()
                        scopePath += labelName
                        labels += AsmLabel(labelName, relativeName, lineIndex)
                    } else {
                        val depth = asmLocalLabelDepth(labelName)
                        val parent = scopePath.take(depth)
                        val localScope = parent.takeIf { it.size == depth }?.joinToString("_")
                        labels += AsmLabel(labelName, relativeName, lineIndex, localScope)
                        val leaf = labelName.drop(depth)
                        if (localScope != null) {
                            val exportedName = if (structScope != null && depth == 1) {
                                "$structScope.$leaf"
                            } else {
                                (parent + leaf).joinToString("_")
                            }
                            labels += AsmLabel(exportedName, relativeName, lineIndex)
                            if (structScope == null && depth > 1) {
                                // Asar also accepts a nested descendant flattened
                                // relative to any ancestor: `.Table_row` names the
                                // same declaration as `.Table:` / `..row:`.
                                for (aliasDepth in 1 until depth) {
                                    labels += AsmLabel(
                                        name = ".".repeat(aliasDepth) +
                                            (parent.drop(aliasDepth) + leaf).joinToString("_"),
                                        fileId = relativeName,
                                        lineIndex = lineIndex,
                                        localScope = parent.take(aliasDepth).joinToString("_"),
                                    )
                                }
                            }
                            scopePath.clear()
                            scopePath += parent
                            scopePath += leaf
                        }
                    }
                }
                defineRegex.find(line)?.groupValues?.get(1)?.let { name ->
                    labels += AsmLabel(name, relativeName, lineIndex)
                }
                scopePaths[lineIndex] = scopePath.toList()
                sectionRegex.matchEntire(line)?.groupValues?.get(1)?.trim()?.let { heading ->
                    val addressed = sectionAddressRegex.matchEntire(heading)
                    val title = addressed?.groupValues?.get(2)?.trim().orEmpty().ifEmpty { heading }
                    sections += AsmSection(
                        title = title,
                        lineIndex = lineIndex,
                        address = addressed?.groupValues?.get(1)?.uppercase()?.let { "\$$it" },
                    )
                }
                if (endStructRegex.containsMatchIn(line)) {
                    structScope = null
                    scopePath.clear()
                }
                if (line.trimStart().startsWith("endmacro", ignoreCase = true)) insideMacro = false
            }
            if (sections.firstOrNull()?.lineIndex != 0) {
                sections.add(0, AsmSection("Overview", 0))
            }
            val inferredDescription = lines.asSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith(";") && !it.startsWith(";;;") }
                ?.removePrefix(";")?.trim()
                .orEmpty()
            val moduleDescription = if (relativeName.startsWith(ASM_PROJECT_MODULE_PREFIX)) {
                projectModuleDescription(lines.joinToString("\n"))
            } else null
            val fallbackDescription = inferredDescription.takeUnless {
                relativeName.startsWith(ASM_PROJECT_MODULE_PREFIX) &&
                    it.startsWith("Project module:", ignoreCase = true)
            }.orEmpty()
            AsmSourceFile(
                id = relativeName,
                file = source,
                displayName = sourceDisplayName(relativeName),
                description = descriptions[relativeName] ?: moduleDescription ?: fallbackDescription,
                isBank = relativeName.substringAfterLast('/').startsWith("bank_", ignoreCase = true),
                lines = lines,
                sections = sections,
                scopePathByLine = scopePaths,
            )
        }
        val assets = ranges.map { range -> AsmAsset(range, File(dataDirectory, range.path)) }
        return AsmReferenceIndex(files, labels, assets, AsmAddressAtlas.build(files, labels, assets), macros)
    }
}

internal class AsmReferenceRepository(
    private val referenceRoot: File = File(File(System.getProperty("user.home"), ".smedit"), "asm"),
    private val fetchBytes: (String) -> ByteArray = ::downloadReferenceArchive,
    private val assetRanges: List<AsmAssetRange> = AsmAssetManifest.loadBundled(),
) {
    private val activeDirectory = File(referenceRoot, "sm_disassembly")
    private val metadataFileName = ".smedit-reference.properties"

    fun loadInstalled(): AsmReferenceWorkspace? {
        if (!activeDirectory.isDirectory) return null
        val metadata = readMetadata(File(activeDirectory, metadataFileName)) ?: return null
        if (metadata.commit != AsmReferenceContract.COMMIT) return null
        val src = File(activeDirectory, "src")
        if (!File(src, "main.asm").isFile) return null
        val index = AsmSourceParser().parse(src, File(activeDirectory, "data"), assetRanges)
        return AsmReferenceWorkspace(activeDirectory, metadata, index)
    }

    fun installOrRefresh(
        romBytes: ByteArray,
        romName: String,
        onProgress: (String) -> Unit = {},
    ): AsmReferenceWorkspace {
        referenceRoot.mkdirs()
        val staging = File(referenceRoot, "sm_disassembly.installing-${UUID.randomUUID()}")
        requireManagedChild(staging)
        try {
            onProgress("Downloading pinned ASM source…")
            val archive = fetchBytes(AsmReferenceContract.archiveUrl)
            require(archive.size <= MAX_ARCHIVE_BYTES) { "ASM source archive is unexpectedly large" }
            extractArchive(archive, staging.toPath())
            require(File(staging, "src/main.asm").isFile) { "Downloaded archive has no src/main.asm" }
            onProgress("Extracting ${assetRanges.size} assets from $romName…")
            val metadata = writeAssets(staging, romBytes, romName)
            writeMetadata(File(staging, metadataFileName), metadata)
            onProgress("Activating ASM reference…")
            replaceActive(staging)
            return checkNotNull(loadInstalled()) { "ASM reference did not validate after installation" }
        } catch (problem: Exception) {
            deleteManagedTree(staging)
            throw problem
        }
    }

    fun refreshAssets(
        romBytes: ByteArray,
        romName: String,
        onProgress: (String) -> Unit = {},
    ): AsmReferenceWorkspace {
        require(activeDirectory.isDirectory && File(activeDirectory, "src/main.asm").isFile) {
            "Download the ASM reference first"
        }
        onProgress("Extracting ${assetRanges.size} assets from $romName…")
        val stagedData = File(referenceRoot, "data.installing-${UUID.randomUUID()}")
        requireManagedChild(stagedData)
        try {
            val normalized = normalizeRom(romBytes)
            extractAssets(normalized, stagedData)
            val current = readMetadata(File(activeDirectory, metadataFileName))
            val metadata = metadataFor(normalized, romName, current?.installedAt ?: Instant.now().toString())
            replaceDirectory(stagedData, File(activeDirectory, "data"))
            writeMetadata(File(activeDirectory, metadataFileName), metadata)
            return checkNotNull(loadInstalled())
        } catch (problem: Exception) {
            deleteManagedTree(stagedData)
            throw problem
        }
    }

    fun romSha256(romBytes: ByteArray): String = sha256(normalizeRom(romBytes))

    private fun writeAssets(root: File, romBytes: ByteArray, romName: String): AsmReferenceMetadata {
        val normalized = normalizeRom(romBytes)
        val dataDirectory = File(root, "data")
        deleteManagedTree(dataDirectory)
        extractAssets(normalized, dataDirectory)
        return metadataFor(normalized, romName, Instant.now().toString())
    }

    private fun normalizeRom(bytes: ByteArray): ByteArray {
        val headerSize = if (bytes.size % 0x8000 == 0x200) 0x200 else 0
        require(bytes.size - headerSize >= AsmReferenceContract.MINIMUM_ROM_SIZE) {
            "The loaded ROM is too small for the Super Metroid ASM asset map (${bytes.size} bytes)"
        }
        return bytes.copyOfRange(headerSize, bytes.size)
    }

    private fun extractAssets(rom: ByteArray, destination: File) {
        destination.mkdirs()
        assetRanges.forEach { range ->
            require(range.endExclusive <= rom.size) { "${range.path} extends beyond the loaded ROM" }
            val output = File(destination, range.path)
            val normalizedOutput = output.toPath().normalize()
            require(normalizedOutput.startsWith(destination.toPath().normalize())) { "Unsafe asset path: ${range.path}" }
            output.parentFile?.mkdirs()
            Files.write(
                normalizedOutput,
                rom.copyOfRange(range.pcOffset, range.endExclusive),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
        val count = assetRanges.count { range -> File(destination, range.path).isFile }
        require(count == assetRanges.size) {
            "Expected ${assetRanges.size} extracted assets, wrote $count"
        }
    }

    private fun metadataFor(rom: ByteArray, romName: String, installedAt: String) = AsmReferenceMetadata(
        repositoryUrl = AsmReferenceContract.REPOSITORY_URL,
        commit = AsmReferenceContract.COMMIT,
        installedAt = installedAt,
        romName = romName,
        romSha256 = sha256(rom),
        romSize = rom.size,
        assetCount = assetRanges.size,
    )

    private fun extractArchive(archive: ByteArray, destination: Path) {
        Files.createDirectories(destination)
        var totalBytes = 0L
        var entries = 0
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ARCHIVE_ENTRIES) { "ASM source archive has too many entries" }
                val rawParts = entry.name.replace('\\', '/').split('/').filter(String::isNotEmpty)
                if (rawParts.size <= 1) continue
                val relative = rawParts.drop(1).joinToString("/")
                val output = destination.resolve(relative).normalize()
                require(output.startsWith(destination.normalize())) { "Unsafe path in ASM source archive" }
                if (entry.isDirectory) {
                    Files.createDirectories(output)
                } else {
                    Files.createDirectories(output.parent)
                    Files.newOutputStream(output).use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            require(totalBytes <= MAX_EXTRACTED_BYTES) { "ASM source archive expands beyond the safety limit" }
                            stream.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun replaceActive(staging: File) = replaceDirectory(staging, activeDirectory)

    private fun replaceDirectory(staging: File, target: File) {
        requireManagedChild(staging)
        requireManagedTarget(target)
        val backup = File(referenceRoot, "${target.name}.backup-${UUID.randomUUID()}")
        requireManagedChild(backup)
        var backedUp = false
        try {
            if (target.exists()) {
                moveDirectory(target.toPath(), backup.toPath())
                backedUp = true
            }
            moveDirectory(staging.toPath(), target.toPath())
            if (backedUp) deleteManagedTree(backup)
        } catch (problem: Exception) {
            if (!target.exists() && backedUp && backup.exists()) {
                runCatching { moveDirectory(backup.toPath(), target.toPath()) }
            }
            throw problem
        }
    }

    private fun moveDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun writeMetadata(file: File, metadata: AsmReferenceMetadata) {
        val properties = Properties().apply {
            setProperty("repositoryUrl", metadata.repositoryUrl)
            setProperty("commit", metadata.commit)
            setProperty("installedAt", metadata.installedAt)
            setProperty("romName", metadata.romName)
            setProperty("romSha256", metadata.romSha256)
            setProperty("romSize", metadata.romSize.toString())
            setProperty("assetCount", metadata.assetCount.toString())
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        requireManagedChild(temporary)
        try {
            temporary.outputStream().use {
                properties.store(it, "SMEDIT ASM provenance; extracted assets live in data/")
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun readMetadata(file: File): AsmReferenceMetadata? = runCatching {
        if (!file.isFile) return null
        val properties = Properties().apply { file.inputStream().use(::load) }
        AsmReferenceMetadata(
            repositoryUrl = properties.getProperty("repositoryUrl"),
            commit = properties.getProperty("commit"),
            installedAt = properties.getProperty("installedAt"),
            romName = properties.getProperty("romName"),
            romSha256 = properties.getProperty("romSha256"),
            romSize = properties.getProperty("romSize").toInt(),
            assetCount = properties.getProperty("assetCount").toInt(),
        )
    }.getOrNull()

    private fun requireManagedChild(file: File) {
        val root = referenceRoot.canonicalFile.toPath()
        val child = file.canonicalFile.toPath()
        require(child.startsWith(root) && child != root) { "Refusing unmanaged ASM path: $file" }
    }

    private fun requireManagedTarget(file: File) {
        requireManagedChild(file)
        require(file == activeDirectory || file.parentFile == activeDirectory) { "Refusing ASM replacement target: $file" }
    }

    private fun deleteManagedTree(file: File) {
        if (!file.exists()) return
        requireManagedChild(file)
        Files.walk(file.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
        private const val MAX_EXTRACTED_BYTES = 128L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 10_000
    }
}

private fun downloadReferenceArchive(url: String): ByteArray {
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(2))
        .header("User-Agent", "SMEDIT-ASM-Reference")
        .GET()
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    require(response.statusCode() in 200..299) { "ASM download failed with HTTP ${response.statusCode()}" }
    return response.body()
}

internal fun sourceDisplayName(relativeName: String): String {
    val name = relativeName.substringAfterLast('/').removeSuffix(".asm")
    return when {
        name.matches(Regex("bank_[0-9A-Fa-f]{2}")) -> "Bank \$${name.takeLast(2).uppercase()}"
        name.matches(Regex("bank_[0-9A-Fa-f]{2}_[A-Za-z0-9]+")) ->
            "Bank \$${name.substring(5, 7).uppercase()} · ${name.substring(8).replaceFirstChar(Char::uppercase)}"
        name.startsWith("bank_", ignoreCase = true) -> "Bank \$${name.removePrefix("bank_").uppercase().replace("..", "–\$")}"
        name == "main" -> "Build entry point"
        else -> name.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}

internal fun assetCategory(path: String): String {
    val stem = path.substringAfterLast('/').removePrefix("UNUSED_").substringBefore('_').substringBefore('.')
    return stem.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replaceFirstChar(Char::uppercase)
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
