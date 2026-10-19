package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.ENEMY_DEFS
import java.io.File

internal const val ENEMY_HEADER_SOURCE_FILE = "bank_A0.asm"
internal const val ENEMY_DROP_SOURCE_FILE = "bank_B4.asm"
internal enum class AsmEnemyTuningSection(val fileId: String, val labelPrefix: String) {
    STATS(ENEMY_HEADER_SOURCE_FILE, "EnemyHeaders_"),
    DROPS(ENEMY_DROP_SOURCE_FILE, "EnemyDropChances_"),
    VULNERABILITIES(ENEMY_DROP_SOURCE_FILE, "EnemyVulnerabilities_"),
    ;

    val tableSize: Int get() = when (this) { STATS -> 0; DROPS -> 6; VULNERABILITIES -> 22 }
    val pointerOffset: Int get() = when (this) { STATS -> error("Stats are inline."); DROPS -> 0x3A; VULNERABILITIES -> 0x3C }
    val macro: String get() = when (this) { STATS -> error("Stats have individual arguments."); DROPS -> "drops"; VULNERABILITIES -> "vulnerabilities" }
    val title: String get() = when (this) { STATS -> "stats"; DROPS -> "drops"; VULNERABILITIES -> "vulnerabilities" }
}
internal val ENEMY_TUNING_CONFIG_TYPES = setOf("enemy_stats", "enemy_drops", "enemy_vuln")
internal data class AsmEnemyTuningField(val key: String, val speciesId: Int, val header: String, val offset: Int,
    val fileId: String, val span: IntRange, val value: Int, val table: String? = null, val bindingSpan: IntRange? = null,
    val section: AsmEnemyTuningSection = AsmEnemyTuningSection.STATS)

/** Strict lenses into native EnemyHeader arguments and drop/vulnerability tables, not an Asar evaluator. */
internal class AsmEnemyTuningSource(val originalHeaders: String, val originalDrops: String,
    val bindings: AsmSourceBindings = AsmSourceBindings.current()) {
    private data class Document(val text: String, val labels: List<MatchResult>, val byName: Map<String, List<Int>>,
        val blocks: MutableMap<String, Pair<Int, String>> = mutableMapOf(),
        val arguments: MutableMap<Pair<String, String>, Pair<IntRange, String>> = mutableMapOf(),
        val tables: MutableMap<Pair<String, AsmEnemyTuningSection>, List<Pair<IntRange, Int>>> = mutableMapOf()) {
        val sections by lazy { AsmSourceSections(text) }
    }
    private val documents = mutableListOf<Document>()
    private fun document(text: String): Document {
        documents.firstOrNull { it.text === text }?.let { return it }
        val labels = Regex("(?m)^([A-Za-z_][A-Za-z0-9_]*):[^\\r\\n]*(?:\\r?\\n|$)").findAll(text).toList()
        return Document(text, labels, labels.indices.groupBy { labels[it].groupValues[1] }).also {
            if (documents.size == 8) documents.removeAt(0)
            documents.add(it)
        }
    }
    /** Category shortcuts inspect live declarations, without decoding/adopting editable fields. */
    fun sectionLine(section: AsmEnemyTuningSection, text: String): Int {
        val declaration = requireNotNull(document(text).labels.firstOrNull {
            bindings.names.canonical(it.groupValues[1]).startsWith(section.labelPrefix)
        }) { "No ${section.labelPrefix} declarations remain in ${section.fileId}." }
        return text.take(declaration.range.first).count { it == '\n' }
    }
    val headers: Map<Int, String> = bindings.enemyHeaders(originalHeaders)
    private val enemiesByKey = ENEMY_DEFS.filter { it.speciesId in headers }.associateBy { it.key }
    private val originalFields = mutableMapOf<String, Result<AsmEnemyTuningField>>()
    val keys = enemiesByKey.values.flatMap { enemy ->
        listOf("${enemy.key}_hp", "${enemy.key}_dmg") + (0..5).map { "${enemy.key}_drop$it" } +
            (0..21).map { "${enemy.key}_vuln$it" }
    }.toSet()
    private fun block(text: String, label: String): Pair<Int, String> {
        val document = document(text)
        document.blocks[label]?.let { return it }
        val indices = document.byName[label].orEmpty()
        require(indices.size == 1) { "Expected exactly one $label declaration." }
        var index = indices.single()
        var start = document.labels[index].range.last + 1
        // Adjacent labels are aliases of the same table, not zero-length resources.
        while (index + 1 < document.labels.size && text.substring(start, document.labels[index + 1].range.first)
                .lineSequence().all { asmCodeBeforeComment(it).isBlank() }) {
            index++
            start = document.labels[index].range.last + 1
        }
        val end = document.labels.getOrNull(index + 1)?.range?.first ?: text.length
        return (start to text.substring(start, end)).also { document.blocks[label] = it }
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
    fun field(key: String, headerText: String, dropText: String): AsmEnemyTuningField {
        require(key in keys) { "Unsupported enemy tuning field $key." }
        val enemy = enemiesByKey.getValue(key.substringBeforeLast('_'))
        val label = headers.getValue(enemy.speciesId)
        val suffix = key.removePrefix(enemy.key + "_")
        if (suffix == "hp" || suffix == "dmg") {
            val (span, token) = argument(headerText, label, if (suffix == "hp") "health" else "damage")
            val value = AsmPlmDocument.literal(token)
            require(value != null && value in 0..65535) { "$label: %${if (suffix == "hp") "health" else "damage"} expects a 16-bit literal. This field is read-only until its ASM is repaired." }
            return AsmEnemyTuningField(key, enemy.speciesId, label, if (suffix == "hp") 4 else 6, ENEMY_HEADER_SOURCE_FILE, span, value)
        }
        val section = if (suffix.startsWith("drop")) AsmEnemyTuningSection.DROPS else AsmEnemyTuningSection.VULNERABILITIES
        val (binding, table) = argument(headerText, label, section.macro)
        require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "$label: ${section.macro} must name an exact bank-B4 table label." }
        val fields = tableBytes(dropText, table, section)
        val slot = suffix.removePrefix(if (section == AsmEnemyTuningSection.DROPS) "drop" else "vuln").toInt()
        return AsmEnemyTuningField(key, enemy.speciesId, label, slot, ENEMY_DROP_SOURCE_FILE, fields[slot].first, fields[slot].second, table, binding, section)
    }
    private fun tableBytes(text: String, table: String, section: AsmEnemyTuningSection): List<Pair<IntRange, Int>> {
        val document = document(text)
        document.tables[table to section]?.let { return it }
        val (start, body) = block(text, table)
        val fields = mutableListOf<Pair<IntRange, Int>>()
        var offset = start
        for (line in body.split('\n')) {
            val code = asmCodeBeforeComment(line).trimEnd('\r')
            if (code.isNotBlank()) {
                // Native feature guards and explicit orgs are section boundaries,
                // not bytes belonging to this fixed-size table.
                if (fields.size == section.tableSize && (code.trim() in setOf("if !FEATURE_KEEP_UNREFERENCED", "endif") ||
                    Regex("org[ \\t]+\\$[0-9A-Fa-f]{6}", RegexOption.IGNORE_CASE).matches(code.trim()))) break
                val directive = requireNotNull(Regex("^[ \\t]*db[ \\t]+", RegexOption.IGNORE_CASE).find(code)) { "$table: expected exactly ${section.tableSize} literal db bytes." }
                var local = directive.range.last + 1
                for (part in code.substring(local).split(',')) {
                    val token = part.trim()
                    val at = local + part.indexOf(token)
                    val value = AsmPlmDocument.literal(token)
                    require(value != null && value in 0..255) { "$table: ${section.title} must be byte literals (0–255)." }
                    fields += (offset + at until offset + at + token.length) to value
                    local += part.length + 1
                }
            }
            offset += line.length + 1
        }
        require(fields.size == section.tableSize) { "$table: expected ${section.tableSize} ${section.title} bytes, found ${fields.size}." }
        return fields.also { document.tables[table to section] = it }
    }
    fun original(key: String) = originalFields.getOrPut(key) { runCatching { field(key, originalHeaders, originalDrops) } }.getOrThrow()
    fun navigationTargetsAt(headerText: String, tableText: String, fileId: String, line: Int): List<AsmEditorTarget.EnemyTuning> {
        val doc = document(if (fileId == ENEMY_HEADER_SOURCE_FILE) headerText else tableText)
        val selected = doc.sections.at(line) ?: return emptyList()
        return enemiesByKey.values.flatMap { enemy ->
            val header = headers.getValue(enemy.speciesId)
            if (fileId == ENEMY_HEADER_SOURCE_FILE) {
                if (doc.sections.unique(header) != selected) emptyList() else {
                    val sections = listOf(AsmEnemyTuningSection.DROPS, AsmEnemyTuningSection.VULNERABILITIES).filter { section ->
                        runCatching { doc.sections.lineAt(argument(headerText, header, section.macro).first.first) == line }.getOrDefault(false)
                    }.ifEmpty { listOf(AsmEnemyTuningSection.STATS) }
                    sections.map { AsmEditorTarget.EnemyTuning(enemy.speciesId, it) }
                }
            } else listOf(AsmEnemyTuningSection.DROPS, AsmEnemyTuningSection.VULNERABILITIES).mapNotNull { section ->
                val table = runCatching { argument(headerText, header, section.macro).second }.getOrNull()
                if (table != null && doc.sections.unique(table) == selected) AsmEditorTarget.EnemyTuning(enemy.speciesId, section) else null
            }
        }
    }
    /** A broken literal/table still has a useful repair destination; never guess among duplicate labels. */
    fun line(key: String, headers: String, tables: String): Pair<String, Int> {
        val field = runCatching { field(key, headers, tables) }.getOrNull()
        if (field != null) {
            val text = if (field.fileId == ENEMY_HEADER_SOURCE_FILE) headers else tables
            return field.fileId to text.take(field.span.first).count { it == '\n' }
        }
        require(key in keys) { "Unsupported enemy tuning field $key." }
        val enemy = enemiesByKey.getValue(key.substringBeforeLast('_'))
        val suffix = key.removePrefix(enemy.key + "_")
        val macro = when {
            suffix == "hp" -> "health"
            suffix == "dmg" -> "damage"
            suffix.startsWith("drop") -> "drops"
            else -> "vulnerabilities"
        }
        val (binding, token) = argument(headers, this.headers.getValue(enemy.speciesId), macro)
        val table = if (suffix in setOf("hp", "dmg")) null else runCatching { block(tables, token).first }.getOrNull()
        return if (table != null) ENEMY_DROP_SOURCE_FILE to tables.take(table).count { it == '\n' }
            else ENEMY_HEADER_SOURCE_FILE to headers.take(binding.first).count { it == '\n' }
    }
    fun tableConsumers(key: String, headers: String, drops: String): List<Int> {
        val selected = field(key, headers, drops)
        val start = block(drops, requireNotNull(selected.table)).first
        return this.headers.filter { (_, label) -> runCatching {
            block(drops, argument(headers, label, selected.section.macro).second).first == start
        }.getOrDefault(false) }.keys.toList()
    }
    fun edit(key: String, value: Int, headers: String, drops: String): Pair<String, String> {
        val field = field(key, headers, drops)
        require(value in 0..if (field.table == null) 65535 else 255) { "Enemy tuning value is outside its field width." }
        val literal = "$" + value.toString(16).uppercase().padStart(if (field.table == null) 4 else 2, '0')
        return if (field.fileId == ENEMY_HEADER_SOURCE_FILE) headers.replaceRange(field.span, literal) to drops
            else headers to drops.replaceRange(field.span, literal)
    }
    companion object {
        fun load(original: File): AsmEnemyTuningSource? {
            val headers = File(original, "src/$ENEMY_HEADER_SOURCE_FILE")
            val drops = File(original, "src/$ENEMY_DROP_SOURCE_FILE")
            return if (headers.isFile && drops.isFile) AsmEnemyTuningSource(headers.readText(), drops.readText(), AsmSourceBindings.load(original)) else null
        }
    }
}

/** Validate real compiler locations and claim complete typed fields, including unchanged bytes. */
internal fun protectEnemyTuningSource(catalog: AsmEnemyTuningSource?, headers: String, drops: String,
                                     project: SmEditProject, compiled: AsmCompiledBuildBase): AsmCompiledBuildBase {
    val owned = project.asmWorkspace.enemyTuningSourceKeys
    require(catalog != null || owned.isEmpty()) { "Enemy tuning source is missing from this ASM workspace." }
    if (catalog == null) return compiled
    require(catalog.keys.containsAll(owned)) { "Unknown authored enemy tuning identities." }
    val advanced = project.patches.filter { it.enabled && it.configType == "enemy_stats" }.flatMap { it.configData.orEmpty().keys }.filter { it !in catalog.keys }
    require(advanced.isEmpty()) { "Advanced enemy AI/GFX patch fields are ROM-only (${advanced.joinToString()}). In ASM mode, edit the symbolic EnemyHeader arguments or remove those overrides." }
    val labels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
    val parser = RomParser(compiled.romBytes)
    val headerSize = if (compiled.romBytes.size % 0x8000 == 512) 512 else 0
    val originalParser = RomParser(compiled.referenceRomBody)
    val claims = linkedMapOf<Pair<Int, Int>, String>()
    val boundTables = mutableSetOf<Pair<AsmEnemyTuningSection, Int>>()
    for (key in catalog.keys) {
        val original = runCatching { catalog.original(key) }.getOrNull()
        val input = runCatching { catalog.field(key, headers, drops) }
        val config = project.patches.filter { it.enabled && it.configType in ENEMY_TUNING_CONFIG_TYPES }.mapNotNull { it.configData?.get(key) }
        val field = input.getOrNull()
        val authored = key in owned || field != null && (original == null || field.value != original.value || field.table != original.table)
        val tableAddress = field?.table?.let { labels[it] }
        val relocated = field != null && tableAddress != null && originalParser.readUInt16At(originalParser.snesToPc(0xA00000 or field.speciesId) + field.section.pointerOffset) != (tableAddress and 65535)
        if (!authored && !relocated && config.isEmpty()) continue
        require(field != null) { "$key: ${input.exceptionOrNull()?.message}" }
        require(!authored || config.isEmpty()) { "$key has both authored ASM and a GUI patch value. Undo/remove the patch override before building." }
        require(labels[field.header] == (0xA00000 or field.speciesId)) { "${field.header} moved or was removed. Species headers must retain their native identities; no fixed-address tuning patch was applied." }
        val headerPc = parser.snesToPc(0xA00000 or field.speciesId)
        if (field.table == null) {
            val pc = headerPc + field.offset
            require(parser.readUInt16At(pc) == field.value) { "$key: EnemyHeader layout differs from the supported HP/contact-damage format." }
            if (authored) claims[(pc - headerSize) to 2] = ENEMY_HEADER_SOURCE_FILE
        } else {
            val address = requireNotNull(labels[field.table]) { "Missing enemy ${field.section.title} table ${field.table}." }
            require(address in 0xB48000..(0xB50000 - field.section.tableSize) &&
                parser.readUInt16At(headerPc + field.section.pointerOffset) == (address and 65535)) {
                "$key: the ${field.section.title} pointer does not resolve to its ${field.section.tableSize}-byte bank-B4 table."
            }
            val pc = parser.snesToPc(address) + field.offset
            require((compiled.romBytes[pc].toInt() and 255) == field.value) { "$key: assembled drop data differs from its typed source." }
            if (authored || relocated && config.isEmpty()) claims[(pc - headerSize) to 1] = ENEMY_DROP_SOURCE_FILE
            if ((authored || relocated) && boundTables.add(field.section to address)) {
                // A table may also serve species outside the tuning picker. Protect every
                // active pointer binding, not only the first visible consumer's word.
                for (species in catalog.tableConsumers(key, headers, drops)) {
                    val label = catalog.headers.getValue(species)
                    if (label !in labels) continue // Conditional/index-only, not an emitted header.
                    require(labels[label] == (0xA00000 or species)) { "$label moved or was removed; its shared ${field.section.title} binding cannot be guessed." }
                    val bindingPc = parser.snesToPc(0xA00000 or species) + field.section.pointerOffset
                    require(parser.readUInt16At(bindingPc) == (address and 65535)) { "$label has a competing ${field.section.title} table binding." }
                    claims[(bindingPc - headerSize) to 2] = ENEMY_HEADER_SOURCE_FILE
                }
            }
        }
    }
    // Preserve existing claims outside the typed ranges. Shared tables claim their bytes once.
    val retained = compiled.sourceOwnedRanges.flatMap { range ->
        var spans = listOf(range.pcOffset until range.pcOffset + range.length)
        for ((location, _) in claims) {
            val (pc, size) = location
            spans = spans.flatMap { span -> if (pc > span.last || pc + size <= span.first) listOf(span) else buildList {
                if (span.first < pc) add(span.first until pc)
                if (pc + size <= span.last) add(pc + size..span.last)
            } }
        }
        spans.map { range.copy(pcOffset = it.first, length = it.count()) }
    }
    return compiled.copy(sourceOwnedRanges = retained + claims.map { (location, file) -> AsmSourceOwnedRange(location.first, location.second, file) })
}
