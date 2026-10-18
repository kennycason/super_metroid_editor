package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.EnvironmentalDamagePatch
import com.supermetroid.editor.rom.RomParser
import java.io.File

internal const val ENVIRONMENT_HEAT_FILE = "bank_8D.asm"
internal const val ENVIRONMENT_LIQUID_FILE = "bank_90.asm"
internal val ENVIRONMENT_SOURCE_FILES = setOf(ENVIRONMENT_HEAT_FILE, ENVIRONMENT_LIQUID_FILE)
internal fun environmentalSourceKey(key: String) = "${EnvironmentalDamagePatch.CONFIG_TYPE}:$key"
private fun environmentalOpcodes(heat: Boolean) = mapOf(0 to 0xAD, 3 to 0x18, 4 to if (heat) 0x69 else 0x6D,
    7 to 0x8D, 10 to 0xAD, 13 to if (heat) 0x69 else 0x6D, 16 to 0x8D)

/** Conservative ROM/reference navigation. Live source navigation uses declarations instead. */
internal fun environmentalRuntimeWords(parser: RomParser, key: String): List<Int> = runCatching {
    val heat = key == EnvironmentalDamagePatch.HEAT_KEY
    val core = when (key) {
        EnvironmentalDamagePatch.HEAT_KEY -> 0x8DE381
        EnvironmentalDamagePatch.LAVA_KEY -> 0x9081F7
        EnvironmentalDamagePatch.ACID_KEY -> 0x908239
        else -> return emptyList()
    }
    val pc = parser.snesToPc(core)
    if (!environmentalOpcodes(heat).all { (at, op) -> parser.getRomData()[pc + at].toInt() and 255 == op } ||
        listOf(1 to 0xA4E, 8 to 0xA4E, 11 to 0xA50, 17 to 0xA50).any { (at, ram) -> parser.readUInt16At(pc + at) != ram }) return emptyList()
    if (heat) listOf(core + 5, core + 14) else listOf(5, 14).map { at ->
        val pointer = parser.readUInt16At(pc + at)
        if (pointer !in 0x8000..0xFFFE) return emptyList()
        0x900000 or pointer
    }
}.getOrDefault(emptyList())
internal data class AsmPatchWordLocations(val lowPc: Int, val highPc: Int)
internal data class AsmEnvironmentalWord(val span: IntRange, val value: Int, val symbol: String, val offset: Int = 0)
internal data class AsmEnvironmentalField(
    val key: String, val file: String, val words: List<AsmEnvironmentalWord>,
    val coreSymbol: String, val coreOffset: Int,
) {
    val fixed get() = EnvironmentalDamagePatch.FixedRate(words[0].value, words[1].value)
    val raw get() = fixed.raw.toLong() and 0xFFFFFFFFL
    val rate get() = EnvironmentalDamagePatch.decodeEnergyPerSecond(words[0].value, words[1].value)
    val editableRate get() = rate.takeIf { EnvironmentalDamagePatch.encodeEnergyPerSecond(it) == fixed }
}

/** Narrow, source-shaped lenses. We never evaluate arbitrary Asar expressions or trust address comments. */
internal class AsmEnvironmentalDamageSource(val originals: Map<String, String>) {
    val keys = EnvironmentalDamagePatch.FIELDS.map { it.key }.toSet()
    fun file(key: String) = if (key == EnvironmentalDamagePatch.HEAT_KEY) ENVIRONMENT_HEAT_FILE else ENVIRONMENT_LIQUID_FILE
    private data class Code(val text: String, val start: Int)
    private data class Label(val name: String, val start: Int, val end: Int)
    private class Document(val text: String) {
        private val lineStarts = (listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }).toIntArray()
        fun lineAt(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        val labels = buildList {
            var scope = ""
            Regex("(?m)^[ \\t]*([A-Za-z_][A-Za-z0-9_]*|\\.[A-Za-z_][A-Za-z0-9_]*):[^\\r\\n]*(?:\\r?\\n|$)").findAll(text).forEach { match ->
                val name = match.groupValues[1]
                if (!name.startsWith('.')) scope = name
                add(Label(if (name.startsWith('.')) scope + "_" + name.drop(1) else name, match.range.first, match.range.last + 1))
            }
        }
        val blocks = mutableMapOf<String, List<Code>>()
        fun block(name: String): List<Code> = blocks.getOrPut(name) {
            var index = labels.indices.singleOrNull { labels[it].name == name }
                ?: error("Expected exactly one $name declaration.")
            var start = labels[index].end
            while (index + 1 < labels.size && text.substring(start, labels[index + 1].start).lineSequence().all { asmCodeBeforeComment(it).isBlank() }) {
                index++; start = labels[index].end
            }
            val end = labels.getOrNull(index + 1)?.start ?: text.length
            buildList {
                var offset = start
                for (line in text.substring(start, end).split('\n')) {
                    val code = asmCodeBeforeComment(line).trim()
                    if (code.isNotEmpty()) {
                        val instruction = code.replace(Regex("^[+-]+[ \\t]+"), "")
                        add(Code(instruction, offset + line.indexOf(instruction)))
                    }
                    offset += line.length + 1
                }
            }
        }
    }
    private val documents = mutableListOf<Document>()
    private fun document(text: String): Document = documents.firstOrNull { it.text === text } ?: Document(text).also {
        if (documents.size == 6) documents.removeAt(0)
        documents.add(it)
    }
    private fun normalized(code: String): String {
        val value = code.trim().replace(Regex("[ \\t]+"), " ")
        return value.substringBefore(' ').uppercase() + value.substringAfter(' ', "").let { if (it.isEmpty()) "" else " $it" }
    }
    private fun width(code: String): Int {
        val op = code.substringBefore(' ').uppercase()
        return when {
            op in setOf("CLC", "NOP", "SEC") -> 1
            op in setOf("BNE", "BEQ", "BMI", "BPL", "BCC", "BCS", "BVC", "BVS", "BRA") -> 2
            op in setOf("LDA.B", "CMP.B", "BIT.B") -> 2
            op in setOf("LDA.W", "STA.W", "CMP.W", "BIT.W", "AND.W", "ADC.W") -> 3
            op == "JSL.L" -> 4
            else -> error("Unrecognized instruction before environmental damage: $code. This control is managed in ASM.")
        }
    }
    private fun core(key: String, text: String): Triple<String, Int, List<Code>> {
        val heat = key == EnvironmentalDamagePatch.HEAT_KEY
        val root = when (key) {
            EnvironmentalDamagePatch.HEAT_KEY -> "PreInstruction_PaletteFXObject_SamusInHeat"
            EnvironmentalDamagePatch.LAVA_KEY -> "AnimateSamus_FX_Lava_periodicDamage"
            EnvironmentalDamagePatch.ACID_KEY -> "AnimateSamus_FX_Acid"
            else -> error("Unknown environmental damage field $key.")
        }
        val codes = document(text).block(root)
        val stem = if (key == EnvironmentalDamagePatch.LAVA_KEY) "Lava" else "Acid"
        fun matches(at: Int): Boolean {
            val lines = codes.drop(at).take(7).map { normalized(it.text) }
            return lines.size == 7 && lines[0] == "LDA.W PeriodicSubDamage" && lines[1] == "CLC" &&
                (if (heat) lines[2].startsWith("ADC.W #") else lines[2] == "ADC.W SamusPhysicsConstants_${stem}SubDamagePerFrame") &&
                lines[3] == "STA.W PeriodicSubDamage" && lines[4] == "LDA.W PeriodicDamage" &&
                (if (heat) lines[5].startsWith("ADC.W #") else lines[5] == "ADC.W SamusPhysicsConstants_${stem}DamagePerFrame") &&
                lines[6] == "STA.W PeriodicDamage"
        }
        val at = codes.indices.singleOrNull(::matches) ?: error("$root: damage accumulator instructions changed. This control is managed in ASM.")
        return Triple(root, codes.take(at).sumOf { width(normalized(it.text)) }, codes.drop(at).take(7))
    }
    private fun word(code: Code, symbol: String, offset: Int, nativeExpression: String? = null): AsmEnvironmentalWord {
        val token = if (code.text.substringBefore(' ').equals("ADC.W", ignoreCase = true)) code.text.substringAfter('#').trim() else {
            require(Regex("dw[ \\t]+.+", RegexOption.IGNORE_CASE).matches(code.text)) { "$symbol: expected one dw word." }
            code.text.substringAfter(Regex("[ \\t]+").find(code.text)!!.value).trim()
        }
        val value = AsmPlmDocument.literal(token) ?: if (token == nativeExpression) 0x8000 else null
        require(value != null && value in 0..65535) { "$symbol: expected a 16-bit literal (or its exact native SPF expression). Repair this ASM word before editing/building its owned rate." }
        val start = code.start + code.text.lastIndexOf(token)
        return AsmEnvironmentalWord(start until start + token.length, value, symbol, offset)
    }
    fun field(key: String, texts: Map<String, String>, checkFrameDefines: Boolean = true): AsmEnvironmentalField {
        require(key in keys) { "Unknown environmental damage field $key." }
        val file = file(key)
        val text = texts.getValue(file)
        val (root, offset, codes) = core(key, text)
        val words = if (key == EnvironmentalDamagePatch.HEAT_KEY) listOf(
            word(codes[2], root, offset + 5), word(codes[5], root, offset + 14),
        ) else {
            val stem = if (key == EnvironmentalDamagePatch.LAVA_KEY) "Lava" else "Acid"
            listOf("SubDamage" to if (stem == "Lava") "\$80*!SPF*\$100" else "\$180*!SPF*\$100", "Damage" to null).map { (suffix, native) ->
                val symbol = "SamusPhysicsConstants_${stem}${suffix}PerFrame"
                val lines = document(text).block(symbol).takeWhile { !it.text.startsWith("org ", ignoreCase = true) }
                require(lines.size == 1) { "$symbol: expected exactly one literal dw word." }
                if (checkFrameDefines && "!SPF" in lines.single().text) {
                    fun defines(main: String): List<String> = buildList {
                        val conditions = mutableListOf<String>()
                        for (line in main.lineSequence()) {
                            val code = asmCodeBeforeComment(line).trim().replace(Regex("[ \\t]+"), " ")
                            when {
                                code.startsWith("if ") -> conditions.add(code.replace(Regex("[ \\t]+"), ""))
                                code == "endif" -> if (conditions.isNotEmpty()) conditions.removeAt(conditions.lastIndex)
                                code == "else" || code.startsWith("elseif ") -> if (conditions.isNotEmpty()) conditions[conditions.lastIndex] += ";" + code.replace(Regex("[ \\t]+"), "")
                                Regex("!(?:PAL|SPF)[ \\t]*=.*").matches(code) -> add(conditions.joinToString("/") + ":" + code.replace(Regex("[ \\t]+"), ""))
                            }
                        }
                    }
                    require(defines(texts["main.asm"].orEmpty()) == defines(originals["main.asm"].orEmpty())) {
                        "$key: frame-rate defines changed; this SPF expression is managed in ASM. Use explicit literal words for two-way rate editing."
                    }
                }
                word(lines.single(), symbol, 0, native)
            }
        }
        require(words[0].span != words[1].span) { "$key: fraction and whole words must be separate." }
        return AsmEnvironmentalField(key, file, words, root, offset)
    }
    fun original(key: String) = field(key, originals)
    private fun fingerprint(key: String, texts: Map<String, String>): Any = runCatching { field(key, texts, checkFrameDefines = false).words.map { it.value } }
        .getOrElse { error -> error.message ?: "Unreadable environmental rate" }
    fun changed(key: String, texts: Map<String, String>) = fingerprint(key, texts) != fingerprint(key, originals)
    fun changedKeys(before: Map<String, String>, after: Map<String, String>) = keys.filter { fingerprint(it, before) != fingerprint(it, after) }.toSet()
    fun line(key: String, texts: Map<String, String>): Pair<String, Int> {
        val file = file(key); val text = texts.getValue(file)
        val span = runCatching { field(key, texts).words.first().span.first }.getOrNull()
        val label = if (key == EnvironmentalDamagePatch.HEAT_KEY) "PreInstruction_PaletteFXObject_SamusInHeat"
            else "SamusPhysicsConstants_${if (key == EnvironmentalDamagePatch.LAVA_KEY) "Lava" else "Acid"}SubDamagePerFrame"
        val offset = span ?: document(text).labels.singleOrNull { it.name == label }?.start ?: error("Cannot locate $label.")
        return file to document(text).lineAt(offset)
    }
    fun navigationKeysAt(file: String, line: Int, texts: Map<String, String>): List<String> {
        if (file !in ENVIRONMENT_SOURCE_FILES) return emptyList()
        val text = texts.getValue(file)
        val doc = document(text)
        val selected = doc.labels.lastOrNull { doc.lineAt(it.start) <= line } ?: return emptyList()
        return keys.filter { file(it) == file }.filter { key ->
            val stem = if (key == EnvironmentalDamagePatch.LAVA_KEY) "Lava" else "Acid"
            if (key == EnvironmentalDamagePatch.HEAT_KEY) selected.name == "PreInstruction_PaletteFXObject_SamusInHeat"
            else (selected.name in setOf("SamusPhysicsConstants_${stem}SubDamagePerFrame", "SamusPhysicsConstants_${stem}DamagePerFrame") && doc.lineAt(selected.start) == line) ||
                runCatching { field(key, texts).words.any { doc.lineAt(it.span.first) == line } }.getOrDefault(false)
        }
    }
    companion object {
        fun load(original: File): AsmEnvironmentalDamageSource? {
            val files = ENVIRONMENT_SOURCE_FILES.associateWith { File(original, "src/$it") }
            return if (files.values.all { it.isFile }) AsmEnvironmentalDamageSource(files.mapValues { it.value.readText() } +
                File(original, "src/main.asm").takeIf { it.isFile }?.let { mapOf("main.asm" to it.readText()) }.orEmpty()) else null
        }
    }
}

/** Check live table bindings/instruction widths, then protect whole pairs and the code consuming them. */
internal fun protectEnvironmentalDamageSource(catalog: AsmEnvironmentalDamageSource?, texts: Map<String, String>,
    project: SmEditProject, compiled: AsmCompiledBuildBase): AsmCompiledBuildBase {
    val registered = EnvironmentalDamagePatch.FIELDS.map { environmentalSourceKey(it.key) }.toSet() + DATA_PATCH_DEFINITIONS.map { it.id } + AsmFastDoorsModule.SOURCE_KEY
    require(project.asmWorkspace.patchSourceKeys.all { it in registered }) { "Unknown authored patch-data identities." }
    val owned = project.asmWorkspace.patchSourceKeys.filter { it.startsWith(EnvironmentalDamagePatch.CONFIG_TYPE + ":") }.toSet()
    val configs = project.patches.filter { it.enabled && it.configType == EnvironmentalDamagePatch.CONFIG_TYPE }
        .flatMap { it.configData.orEmpty().entries }.groupBy { it.key }
    require(catalog != null || owned.isEmpty() && configs.isEmpty()) { "Environmental damage requires its native ASM sources." }
    if (catalog == null) return compiled
    require(owned.all { it in catalog.keys.map(::environmentalSourceKey) } && configs.keys.all { it in catalog.keys }) { "Unknown authored patch-data identities." }
    require(configs.values.all { group -> group.map { it.value }.distinct().size == 1 && group.all { it.value in 0..9999 } }) { "Environmental patch rates must agree and fit 0–9999 energy/sec." }
    val labels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
    val parser = RomParser(compiled.romBytes)
    val header = if (compiled.romBytes.size % 0x8000 == 512) 512 else 0
    val claims = linkedMapOf<Pair<Int, Int>, String>()
    val typed = mutableSetOf<Int>()
    val locations = mutableMapOf<String, AsmPatchWordLocations>()
    for (key in catalog.keys) {
        val authored = environmentalSourceKey(key) in owned || catalog.changed(key, texts)
        val pending = key in configs
        val input = runCatching { catalog.field(key, texts) }
        val field = input.getOrNull()
        if (!authored && !pending && field == null) continue
        require(field != null) { "$key: ${input.exceptionOrNull()?.message}" }
        if (!authored && !pending && (field.coreSymbol !in labels || field.words.any { it.symbol !in labels })) continue
        val bank = if (field.file == ENVIRONMENT_HEAT_FILE) 0x8D0000 else 0x900000
        fun address(symbol: String, offset: Int, size: Int): Int {
            val value = requireNotNull(labels[symbol]) { "$key: missing compiled symbol $symbol." } + offset
            require(value in (bank + 0x8000)..(bank + 0x10000 - size)) { "$key: $symbol must remain inside its native bank." }
            return value
        }
        val pcs = field.words.map { parser.snesToPc(address(it.symbol, it.offset, 2)) }
        val native = EnvironmentalDamagePatch.FIELDS.single { it.key == key }
        val relocated = pcs.map { it - header } != listOf(native.lowWordPc, native.highWordPc)
        if (!authored && !pending && !relocated) continue
        require(!authored || !pending) { "$key has both authored ASM and a patch override. Use ASM values or remove the override." }
        val corePc = parser.snesToPc(address(field.coreSymbol, field.coreOffset, 19))
        val heat = key == EnvironmentalDamagePatch.HEAT_KEY
        val opcodes = environmentalOpcodes(heat)
        require(opcodes.all { (offset, op) -> compiled.romBytes[corePc + offset].toInt() and 255 == op }) { "$key: compiled damage instruction shape changed; no guessed patch destination was used." }
        for ((offset, name) in listOf(1 to "PeriodicSubDamage", 8 to "PeriodicSubDamage", 11 to "PeriodicDamage", 17 to "PeriodicDamage")) {
            require(parser.readUInt16At(corePc + offset) == labels[name]?.and(65535)) { "$key: compiled accumulator binding $name changed." }
        }
        for (index in pcs.indices) {
            require(parser.readUInt16At(pcs[index]) == field.words[index].value) { "$key: compiled environmental word differs from typed source." }
            if (!heat) require(parser.readUInt16At(corePc + if (index == 0) 5 else 14) == (address(field.words[index].symbol, 0, 2) and 65535)) { "$key: damage table is no longer bound to its consumer." }
        }
        locations[environmentalSourceKey(key)] = AsmPatchWordLocations(pcs[0] - header, pcs[1] - header)
        typed.addAll(pcs.map { it - header })
        if (authored || relocated && !pending) pcs.forEach { claims[(it - header) to 2] = field.file }
        // Do not claim the heat suit check: its independent no-heat-protection patch remains compatible.
        if (authored || relocated || pending) {
            if (heat) listOf(0 to 5, 7 to 7, 16 to 3).forEach { (offset, size) -> claims[(corePc + offset - header) to size] = field.file }
            else claims[(corePc - header) to 19] = field.file
        }
    }
    val writes = configs.flatMap { (key, group) ->
        val loc = locations.getValue(environmentalSourceKey(key)); val rate = EnvironmentalDamagePatch.encodeEnergyPerSecond(group.first().value)
        listOf(loc.lowPc to rate.lowWord, loc.highPc to rate.highWord)
    }.groupBy { it.first }
    require(writes.all { (pc, values) -> values.map { it.second }.distinct().size == 1 && claims.keys.none { pc < it.first + it.second && pc + 2 > it.first } }) { "An environmental override competes with ASM-owned/shared words. Use ASM values or remove it." }
    val retained = compiled.sourceOwnedRanges.flatMap { range ->
        var spans = listOf(range.pcOffset until range.pcOffset + range.length)
        for ((pc, size) in (typed.map { it to 2 } + claims.keys).distinct()) spans = spans.flatMap { span -> if (pc > span.last || pc + size <= span.first) listOf(span) else buildList {
            if (span.first < pc) add(span.first until pc)
            if (pc + size <= span.last) add(pc + size..span.last)
        } }
        spans.map { range.copy(pcOffset = it.first, length = it.count()) }
    }
    return compiled.copy(patchDataLocations = compiled.patchDataLocations + locations,
        sourceOwnedRanges = retained + claims.map { (where, file) -> AsmSourceOwnedRange(where.first, where.second, file) })
}
