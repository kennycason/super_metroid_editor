package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.ALL_BEAMS
import com.supermetroid.editor.ui.beamDamageConfigValues
import java.io.File

internal const val BEAM_DAMAGE_SOURCE_FILE = "bank_93.asm"
internal val BEAM_POINTER_KEYS = listOf("power", "wave", "ice", "iw", "spazer", "ws", "is", "iws", "plasma", "wp", "ip", "iwp")
internal fun beamPointerLabel(charged: Boolean) = "SamusProjectileDataPointers_${if (charged) "Charged" else "Uncharged"}Beams"
internal data class AsmBeamDamageField(val key: String, val table: String, val span: IntRange, val value: Int,
    val pointerLabel: String, val slot: Int, val bindingSpan: IntRange)

/** Literal damage words reached through the live native projectile pointer lists. Not an Asar evaluator. */
internal class AsmBeamDamageSource(val originalText: String) {
    val keys = ALL_BEAMS.flatMap { listOf(it.key, it.chargedKey) }.toSet()
    private data class Document(val text: String, val labels: List<MatchResult>,
        val words: MutableMap<Pair<String, Int>, List<Pair<IntRange, String>>> = mutableMapOf())
    private val documents = mutableListOf<Document>()
    private fun document(text: String): Document {
        documents.firstOrNull { it.text === text }?.let { return it }
        return Document(text, Regex("(?m)^([A-Za-z_][A-Za-z0-9_]*):[^\\r\\n]*(?:\\r?\\n|$)").findAll(text).toList()).also {
            if (documents.size == 4) documents.removeAt(0)
            documents.add(it)
        }
    }
    private fun words(text: String, label: String, size: Int): List<Pair<IntRange, String>> {
        val doc = document(text)
        doc.words[label to size]?.let { return it }
        val indices = doc.labels.indices.filter { doc.labels[it].groupValues[1] == label }
        require(indices.size == 1) { "Expected exactly one $label declaration." }
        var index = indices.single()
        var start = doc.labels[index].range.last + 1
        while (index + 1 < doc.labels.size && text.substring(start, doc.labels[index + 1].range.first)
                .lineSequence().all { asmCodeBeforeComment(it).isBlank() }) {
            index++; start = doc.labels[index].range.last + 1
        }
        val end = doc.labels.getOrNull(index + 1)?.range?.first ?: text.length
        val result = mutableListOf<Pair<IntRange, String>>()
        var offset = start
        for (line in text.substring(start, end).split('\n')) {
            val code = asmCodeBeforeComment(line).trimEnd('\r')
            if (code.isNotBlank()) {
                if (result.size == size && Regex("org[ \\t]+\\$[0-9A-Fa-f]{6}", RegexOption.IGNORE_CASE).matches(code.trim())) break
                val directive = requireNotNull(Regex("^[ \\t]*dw[ \\t]+", RegexOption.IGNORE_CASE).find(code)) {
                    "$label: expected exactly $size dw words; this section is managed in ASM until repaired."
                }
                var local = directive.range.last + 1
                for (part in code.substring(local).split(',')) {
                    val token = part.trim()
                    val at = local + part.indexOf(token)
                    result += (offset + at until offset + at + token.length) to token
                    local += part.length + 1
                }
            }
            offset += line.length + 1
        }
        require(result.size == size) { "$label: expected $size dw words, found ${result.size}." }
        return result.also { doc.words[label to size] = it }
    }
    fun field(key: String, text: String): AsmBeamDamageField {
        require(key in keys) { "Unknown beam damage field $key." }
        val charged = key.endsWith("_charged")
        val slot = BEAM_POINTER_KEYS.indexOf(key.removeSuffix("_charged"))
        val pointer = beamPointerLabel(charged)
        val binding = words(text, pointer, 12)[slot]
        val table = binding.second
        require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "$pointer: beam damage must reference an exact bank-93 table label." }
        val word = words(text, table, 11).first()
        val value = AsmPlmDocument.literal(word.second)
        require(value != null && value in 0..65535) { "$table: damage must be a 16-bit literal. This field is managed in ASM until repaired." }
        return AsmBeamDamageField(key, table, word.first, value, pointer, slot, binding.first)
    }
    fun original(key: String) = field(key, originalText)
    fun sectionLine(text: String): Int = labelLine(text, beamPointerLabel(false))
    private fun labelLine(text: String, name: String): Int {
        val label = document(text).labels.filter { it.groupValues[1] == name }
        require(label.size == 1) { "Expected exactly one $name declaration." }
        return text.take(label.single().range.first).count { it == '\n' }
    }
    fun line(key: String, text: String): Int {
        runCatching { field(key, text) }.getOrNull()?.let { return text.take(it.span.first).count { c -> c == '\n' } }
        require(key in keys) { "Unknown beam damage field $key." }
        val pointer = beamPointerLabel(key.endsWith("_charged"))
        val binding = runCatching { words(text, pointer, 12)[BEAM_POINTER_KEYS.indexOf(key.removeSuffix("_charged"))] }.getOrNull()
        return binding?.let { runCatching { labelLine(text, it.second) }.getOrNull() }
            ?: labelLine(text, pointer)
    }
    companion object {
        fun load(original: File): AsmBeamDamageSource? = File(original, "src/$BEAM_DAMAGE_SOURCE_FILE")
            .takeIf { it.isFile }?.let { AsmBeamDamageSource(it.readText()) }
    }
}

/** Resolve patch destinations from compiler symbols and protect full authored words and their bindings. */
internal fun protectBeamDamageSource(catalog: AsmBeamDamageSource?, text: String, project: SmEditProject,
    compiled: AsmCompiledBuildBase): AsmCompiledBuildBase {
    val owned = project.asmWorkspace.beamDamageSourceKeys
    require(catalog != null || owned.isEmpty()) { "Beam damage source is missing from this ASM workspace." }
    val configs = project.patches.filter { it.enabled && it.configType == "beam_damage" }
        .flatMap { beamDamageConfigValues(it.configData.orEmpty()).keys }.toSet()
    require(catalog != null || configs.isEmpty()) { "Beam damage patches require native symbolic source in ASM mode." }
    if (catalog == null) return compiled
    require(catalog.keys.containsAll(owned)) { "Unknown authored beam damage identities." }
    val labels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
    val parser = RomParser(compiled.romBytes)
    val headerSize = if (compiled.romBytes.size % 0x8000 == 512) 512 else 0
    val claims = linkedMapOf<Int, String>()
    val locations = mutableMapOf<String, Int>()
    for (key in catalog.keys) {
        val input = runCatching { catalog.field(key, text) }
        val field = input.getOrNull()
        val original = runCatching { catalog.original(key) }.getOrNull()
        val authored = key in owned || field != null && (original == null || field.value != original.value || field.table != original.table)
        val table = field?.let { labels[it.table] }
        val pointer = field?.let { labels[it.pointerLabel] }
        val beam = ALL_BEAMS.single { it.key == key.removeSuffix("_charged") }
        val charged = key.endsWith("_charged")
        val relocated = field != null && (table != (if (charged) beam.chargedSnesAddress else beam.snesAddress) ||
            pointer != (if (charged) 0x9383D9 else 0x9383C1))
        if (!authored && !relocated && key !in configs) continue
        require(field != null) { "$key: ${input.exceptionOrNull()?.message}" }
        require(!authored || key !in configs) { "$key has both authored ASM and a GUI patch value. Use ASM values or remove the patch override before building." }
        require(table != null && table in 0x938000..0x93FFEA && pointer != null && pointer in 0x938000..0x93FFE8) {
            "$key: projectile tables and pointers must resolve within bank 93 without crossing its end."
        }
        val pc = parser.snesToPc(table)
        val bindingPc = parser.snesToPc(pointer) + field.slot * 2
        require(parser.readUInt16At(bindingPc) == (table and 65535) && parser.readUInt16At(pc) == field.value) {
            "$key: compiled projectile binding or damage differs from its typed source. No fixed-address patch was applied."
        }
        locations[key] = pc - headerSize
        if (authored || relocated && key !in configs) claims[pc - headerSize] = BEAM_DAMAGE_SOURCE_FILE
        if (authored || relocated) claims[bindingPc - headerSize] = BEAM_DAMAGE_SOURCE_FILE
    }
    // Aliased records cannot have competing patch values, even if both keys look independent in the GUI.
    val writes = configs.groupBy { locations.getValue(it) }
    val values = project.patches.filter { it.enabled && it.configType == "beam_damage" }
        .flatMap { beamDamageConfigValues(it.configData.orEmpty()).entries }.groupBy { locations.getValue(it.key) }
    require(configs.none { locations.getValue(it) in claims }) { "A beam patch competes with an ASM-owned shared damage record. Use ASM values or remove the override." }
    require(writes.keys.all { pc -> values.getValue(pc).map { it.value }.distinct().size == 1 }) {
        "Shared beam damage tables have competing patch values. Use ASM values or make the tables independent."
    }
    val typedWords = claims.keys + configs.map { locations.getValue(it) }
    val retained = compiled.sourceOwnedRanges.flatMap { range ->
        var spans = listOf(range.pcOffset until range.pcOffset + range.length)
        for (pc in typedWords) spans = spans.flatMap { span ->
            if (pc > span.last || pc + 2 <= span.first) listOf(span) else buildList {
                if (span.first < pc) add(span.first until pc)
                if (pc + 2 <= span.last) add(pc + 2..span.last)
            }
        }
        spans.map { range.copy(pcOffset = it.first, length = it.count()) }
    }
    return compiled.copy(beamDamageLocations = locations, sourceOwnedRanges = retained + claims.map { (pc, file) -> AsmSourceOwnedRange(pc, 2, file) })
}
