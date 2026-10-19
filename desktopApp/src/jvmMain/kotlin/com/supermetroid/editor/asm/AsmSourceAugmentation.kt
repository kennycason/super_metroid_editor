package com.supermetroid.editor.asm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val augmentationNamespace = Regex("^\\s*namespace\\b", RegexOption.IGNORE_CASE)
private val documentationLocalLabel = Regex("\\.[A-Za-z_][A-Za-z0-9_]*")
private val documentationDeclaration = Regex("\\s*(?:[A-Za-z_][A-Za-z0-9_]*:|\\.+[A-Za-z0-9_]+:?)\\s*")

@Serializable
internal data class AsmSourceDocumentation(
    val id: String,
    val fileId: String,
    val symbol: String,
    val expectedCode: String,
    val paragraphs: List<String>,
    /** A first-level local declaration owned by [symbol], never a line/address lookup. */
    val localLabel: String? = null,
)

@Serializable
internal data class AsmSourceRename(val symbol: String, val replacement: String)

/** An engineering-owned recipe. Projects retain its result, never replay a newer recipe on open. */
@Serializable
internal data class AsmSourceAugmentation(
    val revision: String,
    val upstreamRevision: String = AsmReferenceContract.COMMIT,
    val upstreamSourceSha256: String = "",
    val documentation: List<AsmSourceDocumentation> = emptyList(),
    val renames: List<AsmSourceRename> = emptyList(),
) {
    fun apply(sourceRoot: File, sourceIndex: AsmReferenceIndex? = null,
        nativeSymbols: List<AsmNativeSymbol> = AsmNativeSymbolManifest.load(upstreamRevision)): AsmAugmentedSource =
        apply(AsmSourceImage.capture(sourceRoot, sourceIndex), nativeSymbols)

    fun apply(image: AsmSourceImage,
        nativeSymbols: List<AsmNativeSymbol> = AsmNativeSymbolManifest.load(upstreamRevision)): AsmAugmentedSource {
        val texts = image.texts
        val upstreamHashes = texts.mapValues { AsmVerifiedCache.hash(it.value) }
        require(upstreamSourceSha256.isEmpty() || AsmVerifiedCache.fingerprint(texts) == upstreamSourceSha256) {
            "ASM augmentation $revision does not match the pinned upstream source bytes"
        }
        if (documentation.isEmpty() && renames.isEmpty()) return AsmAugmentedSource(texts, emptyMap(), emptyMap(), upstreamHashes)
        val index = image.index
        val lineStarts = mutableMapOf<String, List<Int>>()
        fun offset(fileId: String, line: Int) = lineStarts.getOrPut(fileId) {
            buildList { add(0); texts.getValue(fileId).forEachIndexed { at, c -> if (c == '\n') add(at + 1) } }
        }[line]
        if (renames.isNotEmpty()) require(texts.values.none { text -> text.lineSequence().any {
            augmentationNamespace.containsMatchIn(asmExecutableCode(it))
        } }) { "Global renaming of namespaced source requires an explicit scoped transformation" }
        require(documentation.map { it.id }.distinct().size == documentation.size) { "Duplicate ASM documentation identity" }
        require(documentation.map { Triple(it.fileId, it.symbol, it.localLabel) }.distinct().size == documentation.size) { "Duplicate ASM documentation target" }
        require(renames.map { it.symbol }.distinct().size == renames.size) { "Duplicate ASM rename identity" }
        val changes = mutableMapOf<String, MutableList<SourceChange>>()
        val symbolNames = linkedMapOf<String, String>()
        val generatedSymbols = mutableSetOf<String>()
        val globals = index.labels.filter { it.localScope == null }.groupBy { it.name }
        val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val boundParameters = if (renames.isEmpty()) emptySet() else texts.values.asSequence()
            .flatMap { it.lineSequence() }.flatMap { asmBoundParameterNames(it).asSequence() }.toSet()
        renames.forEach { rename ->
            require(identifier.matches(rename.symbol) && identifier.matches(rename.replacement) && rename.symbol != rename.replacement) {
                "ASM renames require two different ordinary global identifiers"
            }
            val root = globals[rename.symbol]?.singleOrNull()
                ?: error("Expected one declaration of ${rename.symbol}")
            require(index.macros.none { rename.symbol in it.parameters }) { "${rename.symbol} is also a macro parameter" }
            require(rename.symbol !in boundParameters) {
                "${rename.symbol} is also a bound function or macro parameter"
            }
            index.labels.filter { label ->
                label.localScope == null && label.fileId == root.fileId &&
                    (label.symbolId == root.symbolId ||
                        index.file(label.fileId)?.scopePathByLine?.get(label.lineIndex)?.firstOrNull() == rename.symbol)
            }.forEach { label ->
                val replacement = rename.replacement + label.name.removePrefix(rename.symbol)
                require(label.name == rename.symbol || label.name.startsWith(rename.symbol + "_")) {
                    "Unsupported derived ASM symbol ${label.name}"
                }
                require(symbolNames.put(label.name, replacement) == null) { "Overlapping ASM renames at ${label.name}" }
            }
            nativeSymbols.singleOrNull { it.fileId == root.fileId && it.name == root.name }?.generatedMembers.orEmpty().forEach { member ->
                val name = "${root.name}_$member"
                require(name !in globals && name !in symbolNames) { "Generated ASM symbol collides with $name" }
                symbolNames[name] = "${rename.replacement}_$member"
                generatedSymbols += name
            }
            val line = index.file(root.fileId)!!.lines[root.lineIndex]
            val declaration = Regex("^[ \\t]*(${Regex.escape(rename.symbol)}):").find(line)?.groups?.get(1)
                ?: error("${rename.symbol} is not an ordinary global label declaration")
            val start = offset(root.fileId, root.lineIndex) + declaration.range.first
            changes.getOrPut(root.fileId, ::mutableListOf) += SourceChange(start, start + rename.symbol.length, rename.replacement)
        }
        require(symbolNames.values.distinct().size == symbolNames.size) { "ASM renames collide with one another" }
        symbolNames.forEach { (old, new) ->
            require(new !in globals || new in symbolNames) { "ASM rename collides with $new" }
            // These are verified compiler identities, not parsed source declarations. A source
            // use still fails the unclassified-occurrence check until its binding is supported.
            if (old in generatedSymbols) return@forEach
            require(globals[old]?.size == 1) { "Ambiguous ASM symbol $old" }
            index.usagesFor(globals.getValue(old).single()).filter { it.token == old }.forEach { use ->
                require(index.resolveUniqueLabel(use.fileId, use.lineIndex, use.token)?.symbolId == globals.getValue(old).single().symbolId) {
                    "${use.fileId}: ambiguous reference to $old"
                }
                val start = offset(use.fileId, use.lineIndex) + use.column
                changes.getOrPut(use.fileId, ::mutableListOf) += SourceChange(start, start + old.length, new)
            }
        }
        // Navigation's permissive resolver must not turn an unclassified occurrence into a partial rename.
        texts.forEach { (fileId, text) ->
            var offset = 0
            text.splitToSequence('\n').forEach { line ->
                asmIdentifierTokens(asmExecutableCode(line)).forEach { (range, token) ->
                    if (token in symbolNames) require(changes[fileId].orEmpty().any {
                        it.start == offset + range.first && it.end == offset + range.last + 1
                    }) { "$fileId: unsupported reference to $token" }
                }
                offset += line.length + 1
            }
        }
        val documentedLocations = mutableSetOf<Pair<String, Int>>()
        val labelsByFile = index.labels.groupBy { it.fileId }
        documentation.forEach { note ->
            require(note.id.isNotBlank() && note.paragraphs.isNotEmpty()) { "Empty ASM documentation record" }
            val root = globals[note.symbol]?.singleOrNull()?.takeIf { it.fileId == note.fileId }
                ?: error("${note.id}: expected one ${note.symbol} declaration in ${note.fileId}")
            val source = index.file(root.fileId)!!
            val fileLabels = labelsByFile.getValue(root.fileId)
            val label = note.localLabel?.let { local ->
                require(documentationLocalLabel.matches(local)) {
                    "${note.id}: documentation requires a first-level local label"
                }
                require(Regex("^\\s*${Regex.escape(root.name)}:").containsMatchIn(asmExecutableCode(source.lines[root.lineIndex]))) {
                    "${note.id}: a local documentation anchor requires an ordinary global parent"
                }
                fileLabels.singleOrNull { it.name == local && it.localScope == root.name }
                    ?: error("${note.id}: expected one $local declaration under ${root.name}")
            } ?: root
            require(documentedLocations.add(label.fileId to label.lineIndex)) { "${note.id}: duplicate ASM documentation location" }
            require(documentationDeclaration.matches(asmExecutableCode(source.lines[label.lineIndex]))) {
                "${note.id}: documentation requires a standalone label declaration"
            }
            // An empty block must not accidentally borrow the next label's instruction as its guard.
            val end = fileLabels.asSequence().map { it.lineIndex }.filter { it > label.lineIndex }.minOrNull() ?: source.lines.size
            val firstCode = source.lines.subList(label.lineIndex + 1, end).asSequence()
                .map(::asmExecutableCode).map(String::trim)
                .firstOrNull { it.isNotEmpty() && !it.endsWith(':') }
            require(note.expectedCode.isNotBlank() && firstCode != null &&
                asmStatementSignature(firstCode) == asmStatementSignature(note.expectedCode)) {
                "${note.id}: ${note.symbol}${note.localLabel.orEmpty()} source shape changed"
            }
            val text = texts.getValue(label.fileId)
            val newline = if ("\r\n" in text) "\r\n" else "\n"
            val indent = source.lines[label.lineIndex].takeWhile { it == ' ' || it == '\t' }
            val comments = note.paragraphs.joinToString(newline, postfix = newline) { paragraph ->
                require('\n' !in paragraph && '\r' !in paragraph) { "Documentation paragraphs must be single lines" }
                indent + if (paragraph.isEmpty()) ";" else "; $paragraph"
            }
            val start = offset(label.fileId, label.lineIndex)
            changes.getOrPut(label.fileId, ::mutableListOf) += SourceChange(start, start, comments)
        }
        val origins = linkedMapOf<String, List<Int>>()
        val augmented = texts.mapValues { (fileId, text) ->
            val edits = changes[fileId].orEmpty().distinct().sortedWith(compareBy(SourceChange::start, SourceChange::end))
            require(edits.zipWithNext().all { (a, b) -> a.end <= b.start }) { "Overlapping ASM transformations in $fileId" }
            var cursor = 0
            val output = buildString {
                edits.forEach { edit -> append(text, cursor, edit.start); append(edit.replacement); cursor = edit.end }
                append(text, cursor, text.length)
            }
            if (edits.any { it.start == it.end }) {
                val inserted = edits.filter { it.start == it.end }.groupBy { text.take(it.start).count { c -> c == '\n' } }
                origins[fileId] = buildList {
                    text.split('\n').indices.forEach { line ->
                        inserted[line].orEmpty().forEach { edit -> repeat(edit.replacement.count { it == '\n' }) { add(-1) } }
                        add(line)
                    }
                }
            }
            output
        }
        return AsmAugmentedSource(augmented, symbolNames, origins, upstreamHashes)
    }

    companion object {
        val NONE = AsmSourceAugmentation("unmodified")
        fun bundled(): AsmSourceAugmentation = checkNotNull(AsmSourceAugmentation::class.java.getResourceAsStream("/asm-reference/augmentation.json"))
            .bufferedReader().use { Json.decodeFromString(serializer(), it.readText()) }
    }
}

internal data class AsmAugmentedSource(
    val texts: Map<String, String>,
    val symbolNames: Map<String, String>,
    /** Original zero-based line for each augmented line; inserted documentation has no upstream line. */
    val upstreamLines: Map<String, List<Int>>,
    /** Fingerprints of the same input image used for every edit, never a later filesystem read. */
    val upstreamSourceHashes: Map<String, String>,
)

private data class SourceChange(val start: Int, val end: Int, val replacement: String)

internal fun readAsmSourceTree(root: File): Map<String, String> = root.walkTopDown()
    .filter { it.isFile && it.extension.equals("asm", true) }
    .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }.toSortedMap()

/** Asar's ;@ compatibility lines are executable; ordinary quoted semicolons remain part of strings. */
internal fun asmExecutableCode(line: String): String {
    val prefix = line.indexOfFirst { !it.isWhitespace() }
    return if (prefix >= 0 && line.startsWith(";@", prefix)) {
        " ".repeat(prefix + 2) + asmCodeBeforeComment(line.substring(prefix + 2))
    } else asmCodeBeforeComment(line)
}
