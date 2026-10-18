package com.supermetroid.editor.asm

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.ControllerBindings
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.*

internal data class AsmDataPatchLocation(val pc: Int, val size: Int)
internal data class AsmDataPatchWrite(val pc: Int, val size: Int, val value: Int)

/** Normalize only real pending overrides. An enabled, untouched panel never invents a customization. */
internal fun dataPatchOverrides(patch: SmPatch, fields: Map<String, DataPatchField>): Map<String, List<Int>> {
    val type = patch.configType ?: return emptyMap()
    val definitions = DATA_PATCH_DEFINITIONS.filter { it.type == type }.associateBy { it.key }
    require(definitions.keys.containsAll(patch.configData.orEmpty().keys)) { "Unknown $type settings." }
    val values = patch.configData.orEmpty().toMutableMap()
    if (type in setOf("ceres_escape_seconds", ZEBES_ESCAPE_CONFIG_TYPE)) patch.configValue?.let {
        require(values["seconds"] == null || values["seconds"] == it) { "Conflicting timer overrides; choose one seconds value before building." }
        values["seconds"] = it
    }
    fun field(key: String) = fields.getValue(dataPatchKey(type, key))
    if (type == BOMB_CONFIG_TYPE && BOMB_MAX_ACTIVE_KEY in values && BOMB_COOLDOWN_FRAMES_KEY !in values) {
        val fuse = values[BOMB_FUSE_FRAMES_KEY] ?: field(BOMB_FUSE_FRAMES_KEY).words.single().value
        val cooldown = field(BOMB_COOLDOWN_FRAMES_KEY).words.single().value
        val adjusted = calculateBombCooldownForConfig(values.getValue(BOMB_MAX_ACTIVE_KEY), fuse, cooldown)
        if (adjusted != cooldown) values[BOMB_COOLDOWN_FRAMES_KEY] = adjusted
    }
    if (type == ControllerBindings.CONFIG_TYPE && values.isNotEmpty()) {
        val base = ControllerBindings.slots.associate { it.key to field(it.key).words.first().value }
        val resolved = ControllerBindings.resolve(base, values)
        resolved.filter { (key, value) -> key in values || field(key).words.any { it.value != value } }.forEach { (key, value) -> values[key] = value }
    }
    return values.map { (key, value) ->
        val def = definitions.getValue(key)
        val encoded = def.encode(value)
        def.id to List(field(key).words.size) { encoded }
    }.toMap()
}

internal fun protectDataPatchSource(catalog: AsmDataPatchSource?, texts: Map<String, String>, project: SmEditProject,
                                    compiled: AsmCompiledBuildBase): AsmCompiledBuildBase {
    val allIds = DATA_PATCH_DEFINITIONS.map { it.id }.toSet()
    val owned = project.asmWorkspace.patchSourceKeys.filter { it.substringBefore(':') in DATA_PATCH_TYPES }.toSet()
    require(owned.all { it in allIds }) { "Unknown authored numerical patch fields." }
    val patches = project.patches.filter { it.enabled && it.configType in DATA_PATCH_TYPES }
    require(catalog != null || owned.isEmpty() && patches.none { it.configValue != null || it.configData.orEmpty().isNotEmpty() }) {
        "Numerical patches require their native source in ASM mode."
    }
    if (catalog == null) return compiled
    require(catalog.definitions.keys.containsAll(owned)) { "An authored numerical patch source is missing." }
    val inputs = catalog.definitions.keys.associateWith { runCatching { catalog.field(it, texts) } }
    val fields = inputs.mapNotNull { (id, field) -> field.getOrNull()?.let { id to it } }.toMap()
    val pending = patches.associate { patch -> patch.id to dataPatchOverrides(patch, fields) }
    val pendingIds = pending.values.flatMap { it.keys }.toSet()
    val labels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
    val parser = RomParser(compiled.romBytes)
    val header = if (compiled.romBytes.size % 0x8000 == 512) 512 else 0
    val typed = mutableMapOf<Int, String>()
    val claims = mutableMapOf<Int, String>()
    val locations = mutableMapOf<String, List<AsmDataPatchLocation>>()
    fun address(file: String, symbol: String, offset: Int, size: Int): Int {
        val bank = file.removePrefix("bank_").removeSuffix(".asm").toInt(16) shl 16
        val value = requireNotNull(labels[symbol]) { "Missing compiled symbol $symbol." } + offset
        require(value in bank + 0x8000..bank + 0x10000 - size) { "$symbol must remain in its native bank without crossing its end." }
        return parser.snesToPc(value) - header
    }
    fun compiledByte(pc: Int) = compiled.romBytes[pc + header].toInt() and 255
    fun compiledWord(pc: Int) = parser.readUInt16At(pc + header)
    fun symbolValue(expression: String): Int {
        AsmPlmDocument.literal(expression)?.let { return it }
        val parts = Regex("([A-Za-z_][A-Za-z0-9_]*)(?:([+-])(\\$[0-9A-Fa-f]+|[0-9]+))?").matchEntire(expression)
            ?: error("Unsupported binding $expression.")
        val base = requireNotNull(labels[parts.groupValues[1]]) { "Missing compiled binding $expression." }
        val delta = parts.groupValues[3].takeIf { it.isNotEmpty() }?.let { requireNotNull(AsmPlmDocument.literal(it)) } ?: 0
        return base + if (parts.groupValues[2] == "-") -delta else delta
    }
    fun checkGuard(guard: DataPatchGuard): List<Int> {
        val start = address(guard.file, guard.symbol, guard.offset, 1)
        var pc = start
        for (code in guard.instructions) {
            val op = code.substringBefore(' '); val arg = code.substringAfter(' ', "")
            val immediate = arg.startsWith('#')
            val indexed = arg.endsWith(",X") || arg.endsWith(",Y")
            val opcode = when (op) {
                "LDA.W" -> if (immediate) 0xA9 else if (arg.endsWith(",Y")) 0xB9 else if (arg.endsWith(",X")) 0xBD else 0xAD
                "LDX.W" -> 0xA2
                "STA.W" -> if (arg.endsWith(",Y")) 0x99 else if (indexed) 0x9D else 0x8D
                "CMP.W" -> 0xC9
                "CPX.W" -> 0xE0
                "BIT.W" -> 0x89
                "AND.W" -> 0x29
                "JSL.L" -> 0x22
                "JSR.W" -> 0x20
                "JMP.W" -> 0x4C
                "BPL" -> 0x10
                "BMI" -> 0x30
                "BNE" -> 0xD0
                "BEQ" -> 0xF0
                "BRA" -> 0x80
                "TAY" -> 0xA8
                "TAX" -> 0xAA
                "PHX" -> 0xDA
                "PLX" -> 0xFA
                "ASL" -> 0x0A
                "INX" -> 0xE8
                "CLC" -> 0x18
                "RTS" -> 0x60
                else -> error("Unsupported guarded instruction $code.")
            }
            require(compiledByte(pc) == opcode) { "${guard.symbol}: compiled instruction shape changed ($code)." }
            if (op in setOf("BPL", "BMI", "BNE", "BEQ", "BRA")) {
                val scope = guard.symbol
                val displacement = compiledByte(pc + 1).let { if (it >= 128) it - 256 else it }
                if (arg == "+") require(displacement >= 0) { "${guard.symbol}: expected a forward anonymous branch." }
                else {
                    val target = requireNotNull(labels[scope + "_" + arg.removePrefix(".")]) { "Missing branch target $arg." }
                    require(parser.pcToSnes(pc + header + 2 + displacement) == target) { "${guard.symbol}: branch binding changed." }
                }
                pc += 2
            } else if (arg.isEmpty()) pc++ else {
                val expression = arg.removeSuffix(",X").removeSuffix(",Y")
                val value = if (immediate) requireNotNull(AsmPlmDocument.literal(arg.drop(1))) else
                    symbolValue(if (expression.startsWith('.')) guard.symbol + "_" + expression.drop(1) else expression)
                require(compiledWord(pc + 1) == value and 65535) { "${guard.symbol}: compiled operand/binding differs from source ($code)." }
                if (op == "JSL.L") require(compiledByte(pc + 3) == value ushr 16) { "${guard.symbol}: long-call bank changed." }
                pc += if (op == "JSL.L") 4 else 3
            }
        }
        address(guard.file, guard.symbol, guard.offset, pc - start)
        return (start until pc).toList()
    }
    for ((id, def) in catalog.definitions) {
        val authored = id in owned || catalog.changed(id, texts)
        val input = inputs.getValue(id); val field = input.getOrNull()
        if (field == null && !authored && id !in pendingIds) continue
        require(field != null) { "$id: ${input.exceptionOrNull()?.message}" }
        if (!authored && id !in pendingIds && field.words.any { it.site.symbol !in labels }) continue
        val pcs = field.words.map { address(it.site.file, it.site.symbol, it.offset, it.site.size) }
        val relocated = pcs.zip(field.words).any { (pc, word) -> pc != ControllerBindings.pc(word.site.native) }
        if (!authored && !relocated && id !in pendingIds) continue
        require(!authored || id !in pendingIds) { "$id has both ASM and a patch override. Use ASM values before building." }
        require(!def.bcd || field.values.all { def.decode(it) != null }) { "$id: invalid BCD timer. Seconds must be 00–59." }
        for ((pc, word) in pcs.zip(field.words)) {
            val actual = if (word.site.size == 1) compiledByte(pc) else compiledWord(pc)
            require(actual == word.value) { "$id: compiled data differs from the checked source word." }
            for (at in pc until pc + word.site.size) {
                require(typed[at] == null || typed[at] == id) { "$id shares compiled bytes with ${typed[at]}; split the bindings before patching." }
                typed[at] = id
                if (authored || relocated && id !in pendingIds) claims[at] = word.site.file
            }
        }
        for (guard in catalog.guards(field, texts)) checkGuard(guard).forEach { claims[it] = guard.file }
        locations[id] = pcs.zip(field.words).map { (pc, word) -> AsmDataPatchLocation(pc, word.site.size) }
    }
    // Pending numeric writes own their values; code/binding guards remain protected.
    pendingIds.flatMap { locations.getValue(it) }.forEach { location -> repeat(location.size) { claims.remove(location.pc + it) } }
    if (catalog.definitions.keys.any { it.startsWith(ControllerBindings.CONFIG_TYPE + ":") } &&
        (catalog.definitions.keys.any { it.startsWith(ControllerBindings.CONFIG_TYPE + ":") && (it in owned || catalog.changed(it, texts)) } ||
            pendingIds.any { it.startsWith(ControllerBindings.CONFIG_TYPE + ":") })) {
        for (part in 0..1) ControllerBindings.requireMapping(ControllerBindings.slots.associate { slot ->
            slot.key to fields.getValue(dataPatchKey(ControllerBindings.CONFIG_TYPE, slot.key)).words[part].value
        })
        // Editing defaults must never rewrite the options menu's button decoder.
        val maskSymbol = requireNotNull(labels["Controller_Input_Bitmasks"]) { "Missing controller button decoder." }
        val expected = ControllerBindings.menuMasks
        expected.forEachIndexed { index, mask ->
            val pc = parser.snesToPc(maskSymbol) - header + index * 2
            require(compiledWord(pc) == mask) { "Controller button decoder changed; restore it before using the visual controller editor." }
            claims[pc] = "bank_82.asm"; claims[pc + 1] = "bank_82.asm"
        }
        catalog.controllerInterface(texts).forEach { guard -> checkGuard(guard).forEach { claims[it] = guard.file } }
        val table = address("bank_82.asm", "Configurable_Controller_Binding_RAM_Addresses", 0, 14)
        ControllerBindings.slots.forEachIndexed { index, slot ->
            require(labels[slot.ramSymbol] == slot.ramAddress && compiledWord(table + index * 2) == slot.ramAddress) {
                "${slot.label}: controller menu RAM binding changed."
            }
            claims[table + index * 2] = "bank_82.asm"; claims[table + index * 2 + 1] = "bank_82.asm"
        }
    }
    val foreign = compiled.patchDataLocations.values.flatMap { listOf(it.lowPc, it.highPc) } + compiled.beamDamageLocations.values
    require(foreign.none { pc -> pc in typed || pc + 1 in typed }) { "Numerical patch data overlaps another managed source resource." }
    val writes = pending.mapValues { (_, fields) -> fields.flatMap { (id, values) ->
        locations.getValue(id).zip(values).map { (location, value) -> AsmDataPatchWrite(location.pc, location.size, value) }
    } }
    val bytes = mutableMapOf<Int, Int>()
    for (write in writes.values.flatten()) repeat(write.size) { index ->
        val at = write.pc + index; val value = write.value ushr (index * 8) and 255
        require(at !in claims && (bytes[at] == null || bytes[at] == value)) { "Competing numerical patch overrides or ASM-owned data." }
        bytes[at] = value
    }
    val cut = typed.keys + claims.keys
    val retained = compiled.sourceOwnedRanges.flatMap { range ->
        if (range.sourceFileId?.startsWith(ASM_PROJECT_MODULE_PREFIX) == true) listOf(range) else {
            val result = mutableListOf<AsmSourceOwnedRange>(); var start: Int? = null
            for (pc in range.pcOffset..range.pcOffset + range.length) {
                if (pc < range.pcOffset + range.length && pc !in cut) { if (start == null) start = pc }
                else if (start != null) { result += range.copy(pcOffset = start, length = pc - start); start = null }
            }
            result
        }
    }
    val grouped = mutableListOf<AsmSourceOwnedRange>()
    for ((pc, file) in claims.toSortedMap()) {
        val last = grouped.lastOrNull()
        if (last != null && last.pcOffset + last.length == pc && last.sourceFileId == file) grouped[grouped.lastIndex] = last.copy(length = last.length + 1)
        else grouped += AsmSourceOwnedRange(pc, 1, file)
    }
    return compiled.copy(nativePatchLocations = locations, nativePatchWrites = writes, sourceOwnedRanges = retained + grouped)
}
