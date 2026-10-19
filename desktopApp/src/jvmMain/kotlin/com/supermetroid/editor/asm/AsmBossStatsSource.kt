package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.ALL_BOSS_FIELDS
import com.supermetroid.editor.ui.BossStatField
import java.io.File

internal data class AsmBossStatsWord(
    val speciesId: Int, val header: String, val offset: Int, val span: IntRange, val value: Int,
)
internal data class AsmBossStatsField(val key: String, val words: List<AsmBossStatsWord>) {
    val value get() = words.first().value
    val span get() = words.first().span
    val values get() = words.map { it.value }
    val sharedValue get() = values.distinct().singleOrNull()
}

/** Strict lenses into boss EnemyHeader %health/%damage arguments in bank A0 source. */
internal class AsmBossStatsSource(val originalText: String, val bindings: AsmSourceBindings = AsmSourceBindings.current()) {
    private data class Document(
        val text: String, val labels: List<MatchResult>, val byName: Map<String, List<Int>>,
        val arguments: MutableMap<Pair<String, String>, Pair<IntRange, String>> = mutableMapOf(),
    ) {
        val sections by lazy { AsmSourceSections(text) }
    }

    private val documents = mutableListOf<Document>()
    private fun document(text: String): Document {
        documents.firstOrNull { it.text === text }?.let { return it }
        val labels = Regex("(?m)^([A-Za-z_][A-Za-z0-9_]*):[^\\r\\n]*(?:\\r?\\n|$)").findAll(text).toList()
        return Document(text, labels, labels.indices.groupBy { labels[it].groupValues[1] }).also {
            if (documents.size == 4) documents.removeAt(0)
            documents.add(it)
        }
    }

    val headers: Map<Int, String> = bindings.enemyHeaders(originalText)

    private val bossFieldsByKey: Map<String, BossStatField> = ALL_BOSS_FIELDS.associateBy { it.key }

    val keys: Set<String> = bossFieldsByKey.keys

    private val originalFields = mutableMapOf<String, Result<AsmBossStatsField>>()

    private fun block(text: String, label: String): Pair<Int, String> {
        val document = document(text)
        val indices = document.byName[label].orEmpty()
        require(indices.size == 1) { "Expected exactly one $label declaration." }
        var index = indices.single()
        var start = document.labels[index].range.last + 1
        while (index + 1 < document.labels.size && text.substring(start, document.labels[index + 1].range.first)
                .lineSequence().all { asmCodeBeforeComment(it).isBlank() }) {
            index++; start = document.labels[index].range.last + 1
        }
        val end = document.labels.getOrNull(index + 1)?.range?.first ?: text.length
        return start to text.substring(start, end)
    }

    private fun argument(text: String, label: String, macro: String): Pair<IntRange, String> {
        val document = document(text)
        document.arguments[label to macro]?.let { return it }
        val (start, body) = block(text, label)
        val matches = Regex("(?m)^[ \\t]*%${macro}\\(([^\\r\\n]*)\\)[ \\t]*,?[ \\t]*(?:;[^\\r\\n]*)?\\r?$").findAll(body).toList()
        require(matches.size == 1) { "$label: expected one %$macro argument." }
        val group = matches.single().groups[1]!!
        val token = group.value.trim()
        val offset = group.value.indexOf(token)
        return ((start + group.range.first + offset until start + group.range.first + offset + token.length) to token)
            .also { document.arguments[label to macro] = it }
    }

    fun field(key: String, text: String): AsmBossStatsField {
        require(key in keys) { "Unknown boss stat field $key." }
        val bossField = bossFieldsByKey.getValue(key)
        val macro = if (bossField.offset == 4) "health" else "damage"
        return AsmBossStatsField(key, bossField.writeSpeciesIds.map { species ->
            val label = requireNotNull(headers[species]) { "$key: missing native boss header for ${species.toString(16)}." }
            val (span, token) = argument(text, label, macro)
            val value = AsmPlmDocument.literal(token)
            require(value != null && value in 0..65535) {
                "$label: %$macro expects a 16-bit literal. This field is read-only until its ASM is repaired."
            }
            AsmBossStatsWord(species, label, bossField.offset, span, value)
        })
    }

    fun original(key: String) = originalFields.getOrPut(key) { runCatching { field(key, originalText) } }.getOrThrow()

    /** Include every sub-part, even if one argument becomes an unsupported expression or disappears. */
    private fun fingerprint(key: String, text: String): List<String?> {
        val field = bossFieldsByKey.getValue(key)
        return field.writeSpeciesIds.map { species -> runCatching {
            val token = argument(text, headers.getValue(species), if (field.offset == 4) "health" else "damage").second
            AsmPlmDocument.literal(token)?.toString() ?: token
        }.getOrNull() }
    }
    fun changed(key: String, text: String) = fingerprint(key, text) != fingerprint(key, originalText)
    fun changedKeys(before: String, after: String) = keys.filter { fingerprint(it, before) != fingerprint(it, after) }.toSet()

    fun navigationKeysAt(text: String, line: Int): List<String> {
        val sections = document(text).sections
        val section = sections.at(line) ?: return emptyList()
        val code = asmCodeBeforeComment(text.lineSequence().elementAt(line)).trimStart()
        return keys.filter { key ->
            runCatching {
                val field = bossFieldsByKey.getValue(key)
                field.writeSpeciesIds.any { species -> headers[species]?.let { sections.unique(it) == section } == true } &&
                    // On a health/damage argument, offer only that field. Header labels still offer both.
                    (when {
                        code.startsWith("%health(") -> field.offset == 4
                        code.startsWith("%damage(") -> field.offset == 6
                        else -> true
                    })
            }.getOrDefault(false)
        }
    }

    fun sectionLine(text: String): Int {
        val first = ALL_BOSS_FIELDS.firstNotNullOfOrNull { headers[it.speciesId] } ?: error("No boss headers found.")
        val label = document(text).labels.filter { it.groupValues[1] == first }
        require(label.size == 1) { "Expected exactly one $first declaration." }
        return text.take(label.single().range.first).count { it == '\n' }
    }

    fun line(key: String, text: String): Int {
        require(key in keys) { "Unknown boss stat field $key." }
        val bossField = bossFieldsByKey.getValue(key)
        val macro = if (bossField.offset == 4) "health" else "damage"
        val arguments = bossField.writeSpeciesIds.map { species -> runCatching { argument(text, headers.getValue(species), macro) }.getOrNull() }
        val problem = arguments.indexOfFirst { it == null || AsmPlmDocument.literal(it.second) !in 0..65535 }
        if (problem >= 0) {
            arguments[problem]?.let { return text.take(it.first.first).count { c -> c == '\n' } }
            headers[bossField.writeSpeciesIds[problem]]?.let { label -> document(text).labels.singleOrNull { it.groupValues[1] == label }
                ?.let { return text.take(it.range.first).count { c -> c == '\n' } } }
        }
        runCatching { field(key, text) }.getOrNull()?.let { field ->
            val word = field.words.firstOrNull { it.value != field.value } ?: field.words.first()
            return text.take(word.span.first).count { c -> c == '\n' }
        }
        val label = headers[bossField.speciesId] ?: error("Boss species header not found in source.")
        val decl = document(text).labels.filter { it.groupValues[1] == label }
        require(decl.size == 1) { "Expected exactly one $label declaration." }
        return text.take(decl.single().range.first).count { it == '\n' }
    }

    companion object {
        fun load(original: File): AsmBossStatsSource? = File(original, "src/$ENEMY_HEADER_SOURCE_FILE")
            .takeIf { it.isFile }?.let { AsmBossStatsSource(it.readText(), AsmSourceBindings.load(original)) }
    }
}

/** Validate compiled boss stat fields and claim ownership of their ROM bytes. */
internal fun protectBossStatsSource(
    catalog: AsmBossStatsSource?, text: String, project: SmEditProject,
    compiled: AsmCompiledBuildBase,
): AsmCompiledBuildBase {
    val owned = project.asmWorkspace.bossStatsSourceKeys
    val configs = project.patches.filter { it.enabled && it.configType == "boss_stats" }
        .flatMap { it.configData.orEmpty().keys }.toSet()
    require(catalog != null || owned.isEmpty() && configs.isEmpty()) { "Boss stats require native symbolic source in this ASM workspace." }
    if (catalog == null) return compiled
    require(catalog.keys.containsAll(owned + configs)) { "Unknown boss stat identities." }
    require(project.patches.filter { it.enabled && it.configType == "boss_stats" }
        .all { it.configData.orEmpty().values.all { value -> value in 0..65535 } }) { "Boss stats must fit a 16-bit word (0–65535)." }
    val labels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
    val parser = RomParser(compiled.romBytes)
    val headerSize = if (compiled.romBytes.size % 0x8000 == 512) 512 else 0
    val claims = linkedMapOf<Pair<Int, Int>, String>()
    for (key in catalog.keys) {
        val authored = key in owned || catalog.changed(key, text)
        if (!authored && key !in configs) continue
        val input = runCatching { catalog.field(key, text) }
        val field = input.getOrNull()
        require(field != null) { "$key: ${input.exceptionOrNull()?.message}" }
        require(!authored || key !in configs) {
            "$key has both authored ASM and a GUI patch value. Use ASM values or remove the patch override before building."
        }
        for (word in field.words) {
            require(labels[word.header] == (0xA00000 or word.speciesId)) {
                "${word.header} moved or was removed. Boss headers must retain their native identities."
            }
            val pc = parser.snesToPc(0xA00000 or word.speciesId) + word.offset
            require(parser.readUInt16At(pc) == word.value) { "$key: compiled boss stat differs from its typed source (${word.header})." }
            if (authored) claims[(pc - headerSize) to 2] = ENEMY_HEADER_SOURCE_FILE
        }
    }
    val retained = compiled.sourceOwnedRanges.flatMap { range ->
        var spans = listOf(range.pcOffset until range.pcOffset + range.length)
        for ((location, _) in claims) {
            val (pc, size) = location
            spans = spans.flatMap { span ->
                if (pc > span.last || pc + size <= span.first) listOf(span) else buildList {
                    if (span.first < pc) add(span.first until pc)
                    if (pc + size <= span.last) add(pc + size..span.last)
                }
            }
        }
        spans.map { range.copy(pcOffset = it.first, length = it.count()) }
    }
    return compiled.copy(
        sourceOwnedRanges = retained + claims.map { (location, file) ->
            AsmSourceOwnedRange(location.first, location.second, file)
        },
    )
}
