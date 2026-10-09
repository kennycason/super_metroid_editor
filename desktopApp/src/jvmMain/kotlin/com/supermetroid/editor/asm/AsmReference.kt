package com.supermetroid.editor.asm

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
    val fileId: String,
    val lineIndex: Int,
    val column: Int,
    val token: String,
    val sourceLine: String,
)

internal data class AsmSourceFile(
    val id: String,
    val file: File,
    val displayName: String,
    val description: String,
    val isBank: Boolean,
    val lines: List<String>,
    val sections: List<AsmSection>,
    val scopeByLine: List<String?>,
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
) {
    private val filesById = files.associateBy { it.id }
    private val globalLabels = labels.filter { it.localScope == null }.groupBy { it.name }
    private val localLabels = labels.filter { it.localScope != null }
        .associateBy { Triple(it.fileId, it.localScope, it.name) }
    private val assetsByPath = assets.associateBy { it.range.path }
    private val labelsByLocation = labels.groupBy { it.fileId to it.lineIndex }
    private val labelsBySymbol = labels.associateBy(AsmLabel::symbolId)
    private val usagesBySymbol = buildLabelUsages()

    fun file(id: String?): AsmSourceFile? = id?.let(filesById::get)

    fun asset(path: String?): AsmAsset? = path?.let(assetsByPath::get)

    fun labelsAt(fileId: String, lineIndex: Int): List<AsmLabel> =
        labelsByLocation[fileId to lineIndex].orEmpty()

    fun label(symbolId: AsmSymbolId?): AsmLabel? = symbolId?.let(labelsBySymbol::get)

    fun definition(fileId: String, lineIndex: Int, token: String): AsmLabel? =
        labelsAt(fileId, lineIndex).firstOrNull { label ->
            label.name == token || label.name.substringAfterLast('.') == token.removePrefix(".")
        }

    fun usagesFor(label: AsmLabel): List<AsmLabelUsage> = usagesBySymbol[label.symbolId].orEmpty()

    fun resolveLabel(fileId: String, lineIndex: Int, token: String): AsmLabel? {
        val source = filesById[fileId] ?: return null
        return if (token.startsWith('.')) {
            val scope = source.scopeByLine.getOrNull(lineIndex) ?: return null
            localLabels[Triple(fileId, scope, token)]
        } else {
            globalLabels[token]?.singleOrNull() ?: globalLabels[token]?.firstOrNull()
        }
    }

    fun resolveAsset(reference: String): AsmAsset? {
        val normalized = reference.replace('\\', '/').substringAfterLast("/data/")
            .removePrefix("data/")
            .removePrefix("../data/")
            .removePrefix("./data/")
        return assetsByPath[normalized]
    }

    private fun buildLabelUsages(): Map<AsmSymbolId, List<AsmLabelUsage>> {
        val bySymbol = linkedMapOf<AsmSymbolId, MutableList<AsmLabelUsage>>()
        files.forEach { source ->
            source.lines.forEachIndexed { lineIndex, sourceLine ->
                val code = asmCodeBeforeComment(sourceLine)
                ASM_REFERENCE_TOKEN_REGEX.findAll(code).forEach tokenLoop@{ match ->
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
}

private val ASM_REFERENCE_TOKEN_REGEX =
    Regex("\"(?:\\\\.|[^\"])*\"|\\.?[A-Za-z_][A-Za-z0-9_.]*")

private fun asmCodeBeforeComment(line: String): String {
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

private fun isAsmDefinitionOccurrence(code: String, range: IntRange): Boolean {
    val suffix = code.substring(range.last + 1).trimStart()
    if (suffix.startsWith(':')) return true
    val prefix = code.substring(0, range.first).trimEnd()
    return prefix.endsWith("struct", ignoreCase = true) &&
        prefix.dropLast("struct".length).lastOrNull()?.let { it.isLetterOrDigit() || it == '_' } != true
}

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
    private val labelRegex = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*|\.[A-Za-z0-9_]+):""")
    private val structRegex = Regex("""^\s*struct\s+([A-Za-z_][A-Za-z0-9_]*)\b""", RegexOption.IGNORE_CASE)
    private val endStructRegex = Regex("""^\s*endstruct\b""", RegexOption.IGNORE_CASE)

    fun parse(sourceDirectory: File, dataDirectory: File, ranges: List<AsmAssetRange>): AsmReferenceIndex {
        require(sourceDirectory.isDirectory) { "ASM source directory is missing: $sourceDirectory" }
        val mainFile = File(sourceDirectory, "main.asm")
        require(mainFile.isFile) { "ASM entry point is missing: $mainFile" }
        val mainLines = mainFile.readLines()
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
        val files = sourceNames.mapNotNull { relativeName ->
            val source = relativeNames[relativeName] ?: return@mapNotNull null
            val lines = source.readLines()
            val sections = mutableListOf<AsmSection>()
            val scopes = MutableList<String?>(lines.size) { null }
            var globalScope: String? = null
            var structScope: String? = null
            lines.forEachIndexed { lineIndex, line ->
                structRegex.find(line)?.groupValues?.get(1)?.let { name ->
                    structScope = name
                    globalScope = name
                    labels += AsmLabel(name, relativeName, lineIndex)
                }
                val labelName = labelRegex.find(line)?.groupValues?.get(1)
                if (labelName != null) {
                    if (!labelName.startsWith('.')) globalScope = labelName
                    labels += AsmLabel(labelName, relativeName, lineIndex, globalScope.takeIf { labelName.startsWith('.') })
                    if (labelName.startsWith('.') && structScope != null) {
                        labels += AsmLabel("$structScope$labelName", relativeName, lineIndex)
                    }
                }
                scopes[lineIndex] = globalScope
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
                    globalScope = null
                }
            }
            if (sections.firstOrNull()?.lineIndex != 0) {
                sections.add(0, AsmSection("Overview", 0))
            }
            val inferredDescription = lines.asSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith(";") && !it.startsWith(";;;") }
                ?.removePrefix(";")?.trim()
                .orEmpty()
            AsmSourceFile(
                id = relativeName,
                file = source,
                displayName = sourceDisplayName(relativeName),
                description = descriptions[relativeName].orEmpty().ifEmpty { inferredDescription },
                isBank = relativeName.substringAfterLast('/').startsWith("bank_", ignoreCase = true),
                lines = lines,
                sections = sections,
                scopeByLine = scopes,
            )
        }
        val assets = ranges.map { range -> AsmAsset(range, File(dataDirectory, range.path)) }
        return AsmReferenceIndex(files, labels, assets, AsmAddressAtlas.build(files, labels, assets))
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
