package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomAllocation
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomSegmentAllocationException
import com.supermetroid.editor.rom.RomSegmentAllocationRegistry
import com.supermetroid.editor.rom.RomSegmentKind
import com.supermetroid.editor.rom.RomSegmentRequest
import com.supermetroid.editor.rom.RomVerifiedFreeRange
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class AsmManagedPointerKind {
    LONG24,
    IMMEDIATE_LOW16,
    IMMEDIATE_BANK16,
}

@Serializable
internal data class AsmManagedPointerBinding(
    val sourceFileId: String,
    val kind: AsmManagedPointerKind,
    val occurrences: Int,
    val consumerLabel: String? = null,
    val byteOffset: Int = 0,
) {
    fun validate(assetId: String) {
        require(sourceFileId.endsWith(".asm", true) && managedAssetSafeRelative(sourceFileId)) {
            "$assetId has an unsafe pointer-consumer source file"
        }
        require(occurrences > 0) { "$assetId has an empty pointer-consumer binding" }
        if (kind == AsmManagedPointerKind.LONG24) {
            require(consumerLabel?.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) == true && byteOffset >= 0) {
                "$assetId has an invalid long-pointer anchor"
            }
        } else {
            require(consumerLabel == null && byteOffset == 0) { "$assetId has unexpected immediate-pointer metadata" }
        }
    }
}

internal const val ASM_MANAGED_ASSET_DIRECTORY = "managed-assets"
internal const val ASM_MANAGED_ASSET_DATA_DIRECTORY = "smedit-managed"
internal const val ASM_MANAGED_ASSET_MANIFEST = "managed-assets.json"

@Serializable
internal data class AsmManagedAssetRecord(
    val id: String,
    val kind: String,
    val sourceFileId: String,
    val sourceLabel: String,
    val originalAssetPath: String,
    val managedAssetPath: String,
    val originalPcOffset: Int,
    val originalLength: Int,
    val originalDeclaration: String,
    val alignment: Int = 1,
    val pointerBindings: List<AsmManagedPointerBinding>,
    /** Inline source data has no frozen incbin manifest entry. Its original
     * declaration remains emitted without the public label after relocation. */
    val inlineSource: Boolean = false,
    val inlineOriginalBytesBase64: String? = null,
    /** Empty means any verified compatible bank. Low-word consumers set one
     * or more explicit banks here. */
    val allowedBanks: List<Int> = emptyList(),
) {
    val endLabel: String get() = "SMEDIT_ManagedAsset_${managedAssetShortId(id)}_End"

    fun validate() {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,95}"))) { "Invalid managed ASM asset ID: $id" }
        require(kind.isNotBlank() && kind.length <= 80) { "$id has an invalid managed asset kind" }
        require(sourceFileId.endsWith(".asm", ignoreCase = true) && managedAssetSafeRelative(sourceFileId)) {
            "$id has an unsafe source file"
        }
        require(sourceLabel.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "$id has an invalid source label" }
        require(managedAssetSafeRelative(originalAssetPath) && originalAssetPath.endsWith(".bin", true)) {
            "$id has an unsafe original asset path"
        }
        require(managedAssetSafeRelative(managedAssetPath) && managedAssetPath.startsWith("$ASM_MANAGED_ASSET_DATA_DIRECTORY/") &&
            managedAssetPath.endsWith(".bin", true)) { "$id has an unsafe managed asset path" }
        require(originalPcOffset >= 0 && originalLength > 0) { "$id has an invalid original asset range" }
        require(originalDeclaration.isNotBlank() && sourceLabel in originalDeclaration &&
            (inlineSource || originalAssetPath in originalDeclaration)) {
            "$id cannot restore its original source declaration"
        }
        require(alignment > 0 && alignment <= 0x8000) { "$id has an invalid alignment" }
        require(allowedBanks.distinct().size == allowedBanks.size && allowedBanks.all { it in 0x80..0xFF }) {
            "$id has invalid allowed banks"
        }
        if (inlineSource) {
            val original = inlineOriginalBytes()
            require(original.size == originalLength) { "$id has invalid frozen inline bytes" }
        } else {
            require(inlineOriginalBytesBase64 == null) { "$id has unexpected inline bytes" }
        }
        pointerBindings.forEach { it.validate(id) }
        require(pointerBindings.isNotEmpty()) { "$id has no checked pointer consumers" }
        require(pointerBindings.distinctBy { it.copy(occurrences = 0) }.size == pointerBindings.size) {
            "$id has duplicate pointer-consumer bindings"
        }
    }

    fun inlineOriginalBytes(): ByteArray {
        require(inlineSource) { "$id is not inline source data" }
        return try {
            Base64.getDecoder().decode(requireNotNull(inlineOriginalBytesBase64))
        } catch (problem: Exception) {
            throw IllegalArgumentException("$id has malformed frozen inline bytes", problem)
        }
    }
}

@Serializable
internal data class AsmManagedAssetManifest(
    val formatVersion: Int = 1,
    val assets: List<AsmManagedAssetRecord> = emptyList(),
) {
    fun validate(): AsmManagedAssetManifest = apply {
        require(formatVersion == 1) { "Unsupported managed ASM asset manifest version $formatVersion" }
        assets.forEach(AsmManagedAssetRecord::validate)
        require(assets.map(AsmManagedAssetRecord::id).distinct().size == assets.size) {
            "Managed ASM asset IDs must be unique"
        }
        require(assets.map(AsmManagedAssetRecord::managedAssetPath).distinct().size == assets.size) {
            "Managed ASM asset paths must be unique"
        }
        require(assets.map(AsmManagedAssetRecord::sourceLabel).distinct().size == assets.size) {
            "A source label cannot be owned by more than one managed ASM asset"
        }
        require(assets.map { it.originalPcOffset until it.originalPcOffset + it.originalLength }
            .sortedBy(IntRange::first).zipWithNext().all { (a, b) -> a.last < b.first }) {
            "Managed ASM source envelopes overlap"
        }
    }
}

internal data class AsmManagedAssetPlacement(
    val record: AsmManagedAssetRecord,
    val allocation: RomAllocation,
    val bytes: ByteArray,
) {
    val claim: AsmGeneratedAssetClaim get() = AsmGeneratedAssetClaim(
        owner = "asm-managed-asset:${record.id}",
        label = "Managed ASM asset · ${record.sourceLabel}",
        assetPath = record.managedAssetPath,
        pcOffset = allocation.pcOffset,
        bytes = bytes,
    )
}

internal object AsmManagedAssets {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }

    fun load(asmRoot: File): AsmManagedAssetManifest {
        val file = File(asmRoot, ASM_MANAGED_ASSET_MANIFEST)
        if (!file.isFile) return AsmManagedAssetManifest()
        require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Managed ASM asset manifest cannot be a symlink" }
        return json.decodeFromString<AsmManagedAssetManifest>(file.readText()).validate()
    }

    fun encode(manifest: AsmManagedAssetManifest): String =
        json.encodeToString(manifest.validate()).trimEnd() + "\n"

    fun assetFile(asmRoot: File, record: AsmManagedAssetRecord): File =
        resolveInside(File(asmRoot, ASM_MANAGED_ASSET_DIRECTORY), record.managedAssetPath)

    fun workingAssetFile(workingDirectory: File, record: AsmManagedAssetRecord): File =
        resolveInside(File(workingDirectory, "data"), record.managedAssetPath)

    fun marker(record: AsmManagedAssetRecord): String = buildString {
        appendLine("; smedit:managed-asset-v1 begin ${record.id}")
        appendLine("; ${record.sourceLabel} is emitted from ${record.managedAssetPath} in checked project space.")
        append(record.originalDeclaration.lineSequence().drop(1).joinToString("\n"))
        if (!endsWith("\n")) appendLine()
        append("; smedit:managed-asset-v1 end ${record.id}")
    }

    fun markerRegex(record: AsmManagedAssetRecord): Regex = Regex(
        "(?ms)^; smedit:managed-asset-v1 begin ${Regex.escape(record.id)}\\R.*?" +
            "^; smedit:managed-asset-v1 end ${Regex.escape(record.id)}$",
    )

    fun validateWorkspace(
        asmRoot: File,
        workingDirectory: File,
        manifest: AsmManagedAssetManifest = load(asmRoot),
    ): AsmManagedAssetManifest = manifest.validate().also { checked ->
        checked.assets.forEach { record ->
            val authored = assetFile(asmRoot, record)
            val mirror = workingAssetFile(workingDirectory, record)
            require(authored.isFile && !java.nio.file.Files.isSymbolicLink(authored.toPath()) && authored.length() > 0) {
                "Managed ASM asset ${record.id} is missing its authored data"
            }
            require(mirror.isFile && mirror.readBytes().contentEquals(authored.readBytes())) {
                "Managed ASM asset ${record.id} has a stale generated mirror"
            }
            val source = File(workingDirectory, "src/${record.sourceFileId}")
            require(source.isFile && markerRegex(record).findAll(source.readText()).count() == 1) {
                "Managed ASM asset ${record.id} is missing its exact source ownership marker"
            }
        }
    }

    fun mutedSource(records: List<AsmManagedAssetRecord>): String = buildString {
        if (records.isEmpty()) return@buildString
        appendLine("; SMEDIT managed assets — planning symbols only; emits no payload bytes")
        records.sortedBy(AsmManagedAssetRecord::id).forEach { record ->
            val original = requireNotNull(pcToSnesLoRom(record.originalPcOffset)) {
                "${record.id} has an original range outside LoROM"
            }
            appendLine("pushpc")
            appendLine("org ${hexAddress(original)}")
            appendLine("${record.sourceLabel}:")
            appendLine("pullpc")
        }
    }

    fun plan(
        records: List<AsmManagedAssetRecord>,
        asmRoot: File,
        authoredRom: ByteArray,
        verifiedFreeRanges: List<RomVerifiedFreeRange>,
        excludedRanges: List<IntRange>,
        expandedFillByte: Int,
    ): List<AsmManagedAssetPlacement> {
        if (records.isEmpty()) return emptyList()
        val byBank = verifiedFreeRanges.groupBy(RomVerifiedFreeRange::bank)
        val bankOrder = byBank.keys.sortedWith(
            compareByDescending<Int> { it >= 0xE0 }
                .thenByDescending { byBank.getValue(it).maxOf(RomVerifiedFreeRange::length) }
                .thenBy { it },
        )
        val registry = RomSegmentAllocationRegistry(RomFreeSpaceAllocator(
            romData = authoredRom.copyOf(),
            snesToPc = { snesLoRomToPc(it) ?: -1 },
            pcToSnes = { pcToSnesLoRom(it) ?: -1 },
            guardBytes = 1,
            freeFillByteForBank = { bank ->
                byBank[bank]?.firstOrNull()?.fillByte ?: expandedFillByte
            },
            excludedRanges = excludedRanges,
            verifiedFreeRanges = verifiedFreeRanges,
        ))
        return planWithRegistry(records, asmRoot, registry, bankOrder)
    }

    fun planWithRegistry(
        records: List<AsmManagedAssetRecord>,
        asmRoot: File,
        registry: RomSegmentAllocationRegistry,
        bankOrder: List<Int>,
    ): List<AsmManagedAssetPlacement> = records.sortedBy(AsmManagedAssetRecord::id).map { record ->
            val bytes = assetFile(asmRoot, record).readBytes()
            require(bytes.isNotEmpty()) { "Managed ASM asset ${record.id} is empty" }
            if (bankOrder.isEmpty()) error(
                "Managed ASM asset ${record.sourceLabel} needs ${bytes.size} contiguous bytes; " +
                    "no verified compatible free range is available. Increase project ROM capacity or free/relocate another owner.",
            )
            val compatibleBanks = if (record.allowedBanks.isEmpty()) bankOrder else bankOrder.filter(record.allowedBanks::contains)
            require(compatibleBanks.isNotEmpty()) {
                "Managed ASM asset ${record.sourceLabel} needs ${bytes.size} contiguous bytes in " +
                    (if (record.allowedBanks.isEmpty()) "a verified bank" else "bank(s) ${record.allowedBanks.joinToString { "\$${it.toString(16).uppercase()}" }}")
            }
            val request = RomSegmentRequest(
                id = "asm-managed-asset:${record.id}",
                owner = "asm-managed-asset:${record.id}",
                kind = RomSegmentKind.SOURCE_ASSET,
                label = "Managed ASM asset · ${record.sourceLabel}",
                size = bytes.size,
                alignment = record.alignment,
                allowedBanks = compatibleBanks,
                constraint = if (record.allowedBanks.isEmpty()) {
                    "All ${record.pointerBindings.size} supported pointer-consumer binding(s) must resolve to one LoROM address"
                } else {
                    "Low-word pointer consumers require bank(s) ${record.allowedBanks.joinToString { "\$${it.toString(16).uppercase()}" }}"
                },
            )
            val allocation = try {
                registry.reserve(request).allocation
            } catch (problem: RomSegmentAllocationException) {
                error(problem.message + ". Increase project ROM capacity or free/relocate another owner.")
            }
            AsmManagedAssetPlacement(record, allocation, bytes)
        }

    fun source(placements: List<AsmManagedAssetPlacement>): String = buildString {
        if (placements.isEmpty()) return@buildString
        appendLine("; SMEDIT managed assets — generated placement from authored project intent")
        placements.sortedBy { it.record.id }.forEach { placement ->
            val record = placement.record
            val allocation = placement.allocation
            appendLine("pushpc")
            appendLine("org ${hexAddress(allocation.snesAddress)}")
            appendLine("${record.sourceLabel}:")
            appendLine("incbin \"../data/${record.managedAssetPath}\"")
            appendLine("${record.endLabel}:")
            appendLine("warnpc ${hexAddress(allocation.snesAddress + allocation.size)}")
            appendLine("pullpc")
        }
    }

    /** Returns every supported executable reference to [sourceLabel]. Any
     * unrecognized consumer makes relocation unavailable instead of leaving a
     * stale native pointer behind. */
    fun pointerBindings(
        sources: Map<String, String>,
        sourceLabel: String,
    ): List<AsmManagedPointerBinding>? = pointerBindings(sources, setOf(sourceLabel)).getValue(sourceLabel)

    /** Resolve several relocation contracts in one source-tree pass. Asset
     * bridges ask about dozens of labels at startup; rescanning every bank for
     * every label made project open quadratic in source size. */
    fun pointerBindings(
        sources: Map<String, String>,
        sourceLabels: Set<String>,
    ): Map<String, List<AsmManagedPointerBinding>?> = PointerIndex(sources).bindings(sourceLabels)

    /** Parsed once and queried by all visual asset bridges for one saved source
     * revision. Unsupported references remain indexed so relocation still
     * fails closed rather than overlooking a consumer. */
    internal class PointerIndex(sources: Map<String, String>) {
        private data class Occurrence(
            val sourceFileId: String,
            val code: String,
            val consumerLabel: String?,
            val byteOffset: Int,
            val offsetKnown: Boolean,
        )

        private val occurrencesByLabel = mutableMapOf<String, MutableList<Occurrence>>()

        init {
            for ((fileId, text) in sources) {
                var consumerLabel: String? = null
                var byteOffset = 0
                var offsetKnown = true
                for (line in text.lineSequence()) {
                    val code = asmExecutableCode(line).trim()
                    val label = POINTER_GLOBAL_LABEL.matchEntire(code)
                    if (label != null) {
                        consumerLabel = label.groupValues[1]
                        byteOffset = 0
                        offsetKnown = true
                        continue
                    }
                    POINTER_IDENTIFIER.findAll(code)
                        .filter { !asmOffsetInsideString(code, it.range.first) }
                        .map { it.value.lowercase() }
                        .distinct()
                        .forEach { identifier ->
                            occurrencesByLabel.getOrPut(identifier) { mutableListOf() } += Occurrence(
                                fileId, code, consumerLabel, byteOffset, offsetKnown,
                            )
                        }
                    val width = directiveWidth(code)
                    if (width != null) byteOffset += width else if (code.isNotBlank()) offsetKnown = false
                }
            }
        }

        fun bindings(sourceLabels: Set<String>): Map<String, List<AsmManagedPointerBinding>?> =
            sourceLabels.associateWith { sourceLabel ->
                val occurrences = mutableListOf<AsmManagedPointerBinding>()
                for (occurrence in occurrencesByLabel[sourceLabel.lowercase()].orEmpty()) {
                    val kind = pointerKind(occurrence.code, sourceLabel) ?: return@associateWith null
                    if (kind == AsmManagedPointerKind.LONG24) {
                        if (!occurrence.offsetKnown || occurrence.consumerLabel == null) return@associateWith null
                        occurrences += AsmManagedPointerBinding(
                            occurrence.sourceFileId,
                            kind,
                            1,
                            occurrence.consumerLabel,
                            occurrence.byteOffset,
                        )
                    } else {
                        occurrences += AsmManagedPointerBinding(occurrence.sourceFileId, kind, 1)
                    }
                }
                occurrences.takeIf { it.isNotEmpty() }
                    ?.groupingBy { it.copy(occurrences = 0) }
                    ?.eachCount()
                    ?.map { (binding, count) -> binding.copy(occurrences = count) }
                    ?.sortedWith(compareBy(AsmManagedPointerBinding::sourceFileId, AsmManagedPointerBinding::kind,
                        { it.consumerLabel.orEmpty() }, AsmManagedPointerBinding::byteOffset))
            }
    }

    fun pointerKind(code: String, sourceLabel: String): AsmManagedPointerKind? {
        val label = Regex.escape(sourceLabel)
        return when {
            Regex("^dl\\s+$label$", RegexOption.IGNORE_CASE).matches(code) -> AsmManagedPointerKind.LONG24
            Regex("^LDA\\.W\\s+#$label$", RegexOption.IGNORE_CASE).matches(code) ->
                AsmManagedPointerKind.IMMEDIATE_LOW16
            Regex("^LDA\\.W\\s+#$label\\s*>>\\s*8\\s*&\\s*\\\$FF00$", RegexOption.IGNORE_CASE).matches(code) ->
                AsmManagedPointerKind.IMMEDIATE_BANK16
            else -> null
        }
    }

    fun validatePointerBindings(
        placements: List<AsmManagedAssetPlacement>,
        workingDirectory: File,
        compiledRom: ByteArray,
        symbols: AsmCompiledSymbols,
    ) {
        val sources = readAsmSourceTree(File(workingDirectory, "src"))
        placements.forEach { placement ->
            val record = placement.record
            val expectedAddress = placement.allocation.snesAddress
            require(pointerBindings(sources, record.sourceLabel) == record.pointerBindings) {
                "Managed ASM asset ${record.sourceLabel} pointer consumers changed shape. Restore the supported symbolic references."
            }
            record.pointerBindings.forEach bindingLoop@{ binding ->
                val source = File(workingDirectory, "src/${binding.sourceFileId}")
                require(source.isFile) {
                    "Managed ASM asset ${record.sourceLabel} lost pointer source ${binding.sourceFileId}"
                }
                if (binding.kind == AsmManagedPointerKind.LONG24) {
                    val anchor = requireNotNull(symbols.labels[binding.consumerLabel]) {
                        "Managed ASM pointer anchor ${binding.consumerLabel} is missing"
                    }
                    val pc = requireNotNull(snesLoRomToPc(anchor + binding.byteOffset))
                    require(pc + 3 <= compiledRom.size && readLittleEndian(compiledRom, pc, 3) == expectedAddress) {
                        "Managed ASM 24-bit pointer for ${record.sourceLabel} did not resolve to ${hexAddress(expectedAddress)}"
                    }
                    return@bindingLoop
                }
                val lines = source.readLines().mapIndexedNotNull { index, line ->
                    index.takeIf { pointerKind(asmExecutableCode(line).trim(), record.sourceLabel) == binding.kind }
                }
                lines.forEach { lineIndex ->
                    val consumerAddress = requireNotNull(symbols.addressAt(binding.sourceFileId, lineIndex)) {
                        "Managed ASM pointer for ${record.sourceLabel} at ${binding.sourceFileId}:${lineIndex + 1} emitted no mapped bytes"
                    }
                    val pc = requireNotNull(snesLoRomToPc(consumerAddress)) {
                        "Managed ASM pointer for ${record.sourceLabel} is outside LoROM"
                    }
                    when (binding.kind) {
                        AsmManagedPointerKind.LONG24 -> error("Long pointers use symbol anchors")
                        AsmManagedPointerKind.IMMEDIATE_LOW16 -> {
                            require(pc + 3 <= compiledRom.size && compiledRom[pc] == 0xA9.toByte() &&
                                readLittleEndian(compiledRom, pc + 1, 2) == (expectedAddress and 0xFFFF)
                            ) {
                                "Managed ASM low-word consumer for ${record.sourceLabel} did not resolve to ${hexAddress(expectedAddress)}"
                            }
                        }
                        AsmManagedPointerKind.IMMEDIATE_BANK16 -> {
                            val expected = expectedAddress ushr 8 and 0xFF00
                            require(pc + 3 <= compiledRom.size && compiledRom[pc] == 0xA9.toByte() &&
                                readLittleEndian(compiledRom, pc + 1, 2) == expected
                            ) {
                                "Managed ASM bank-word consumer for ${record.sourceLabel} did not resolve to ${hexAddress(expectedAddress)}"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun directiveWidth(code: String): Int? {
        val match = POINTER_DATA_DIRECTIVE.matchEntire(code) ?: return null
        val width = when (match.groupValues[1].lowercase()) {
            "db" -> 1
            "dw" -> 2
            "dl" -> 3
            else -> 4
        }
        val entries = match.groupValues[2].split(',').count { it.isNotBlank() }
        return width * entries
    }

    fun stableId(kind: String, sourceLabel: String, originalAssetPath: String): String {
        val stem = sourceLabel.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(56)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$kind\u0000$sourceLabel\u0000$originalAssetPath".toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
        return "${stem.ifBlank { "asset" }}-$digest"
    }

    private fun resolveInside(root: File, relative: String): File {
        require(managedAssetSafeRelative(relative)) { "Unsafe managed ASM asset path: $relative" }
        val normalizedRoot = root.canonicalFile.toPath()
        val target = File(root, relative).canonicalFile
        require(target.toPath().startsWith(normalizedRoot) && target.toPath() != normalizedRoot) {
            "Unsafe managed ASM asset path: $relative"
        }
        return target
    }

    private fun hexAddress(value: Int): String = "\$${value.toString(16).uppercase().padStart(6, '0')}"

    private fun readLittleEndian(bytes: ByteArray, offset: Int, length: Int): Int =
        (0 until length).fold(0) { value, index ->
            value or ((bytes[offset + index].toInt() and 0xFF) shl (index * 8))
        }

    private val POINTER_GLOBAL_LABEL = Regex("^([A-Za-z_][A-Za-z0-9_]*):$")
    private val POINTER_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val POINTER_DATA_DIRECTIVE = Regex("^(db|dw|dl|dd)\\s+(.+)$", RegexOption.IGNORE_CASE)
}

private fun managedAssetSafeRelative(path: String): Boolean = path.isNotBlank() && !path.contains('\\') &&
    !path.startsWith('/') && path.split('/').none { it.isBlank() || it == "." || it == ".." }

private fun managedAssetShortId(value: String): String =
    value.replace(Regex("[^A-Za-z0-9_]"), "_").take(72)

