package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.ControllerBindings
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.ui.*
import java.io.File

internal const val PHYSICS_CONFIG_TYPE = "samus_physics"
internal val DATA_PATCH_TYPES = setOf("ceres_escape_seconds", ZEBES_ESCAPE_CONFIG_TYPE, BOMB_CONFIG_TYPE, FANFARE_CONFIG_TYPE, ControllerBindings.CONFIG_TYPE, PHYSICS_CONFIG_TYPE)
internal fun dataPatchKey(type: String, key: String) = "$type:$key"
internal data class DataPatchSite(val file: String, val symbol: String, val native: Int, val op: String = "LDA.W",
    val consumer: String? = null, val index: Int = 0, val size: Int = 2, val before: List<String> = emptyList(),
    val nativeExpression: String? = null, val nativeValue: Int? = null, val immediate: Boolean = true)
internal data class DataPatchDefinition(val type: String, val key: String, val label: String, val range: IntRange,
    val sites: List<DataPatchSite>, val bcd: Boolean = false, val mask: Int = -1, val shift: Int = 0,
    val unrepresentableMask: Int = 0) {
    val id get() = dataPatchKey(type, key)
    fun decode(word: Int): Int? {
        if (!bcd) return if (word and unrepresentableMask != 0) null else (word and mask) ushr shift
        val digits = listOf(word and 15, (word ushr 4) and 15, (word ushr 8) and 15, (word ushr 12) and 15)
        if (digits.any { it > 9 } || digits[1] > 5) return null
        return (digits[3] * 10 + digits[2]) * 60 + digits[1] * 10 + digits[0]
    }
    fun encode(value: Int, previous: Int = 0): Int {
        require(value in range) { "$label must fit ${range.first}–${range.last}." }
        if (!bcd) return (previous and mask.inv()) or ((value shl shift) and mask)
        val minutes = value / 60; val seconds = value % 60
        return ((minutes / 10) shl 12) or ((minutes % 10) shl 8) or ((seconds / 10) shl 4) or (seconds % 10)
    }
}
internal val DATA_PATCH_DEFINITIONS: List<DataPatchDefinition> = buildList {
    add(DataPatchDefinition("ceres_escape_seconds", "seconds", "Ceres escape time", 15..600,
        listOf(DataPatchSite("bank_80.asm", "ProcessTimer_CeresStart", 0x809E0E, consumer = "JSL.L SetTimer")), bcd = true))
    add(DataPatchDefinition(ZEBES_ESCAPE_CONFIG_TYPE, "seconds", "Zebes escape time", ZEBES_ESCAPE_MIN_SECONDS..ZEBES_ESCAPE_MAX_SECONDS,
        listOf(DataPatchSite("bank_80.asm", "ProcessTimer_MotherBrainStart", 0x809E21, consumer = "JSL.L SetTimer")), bcd = true))
    add(DataPatchDefinition(BOMB_CONFIG_TYPE, BOMB_MAX_ACTIVE_KEY, "Bomb slot limit", 1..5,
        listOf(DataPatchSite("bank_90.asm", "FireBomb", 0x90C0F4, op = "CMP.W", consumer = "BPL .noFire",
            before = listOf("LDA.W SamusProjectile_BombCounter", "BEQ +")))))
    add(DataPatchDefinition(BOMB_CONFIG_TYPE, BOMB_FUSE_FRAMES_KEY, "Bomb fuse (frames)", 1..9999,
        listOf(DataPatchSite("bank_90.asm", "BombTimerResetValue", 0x90BF9B, op = "dw"))))
    add(DataPatchDefinition(BOMB_CONFIG_TYPE, BOMB_COOLDOWN_FRAMES_KEY, "Bomb cooldown (frames)", 0..255,
        listOf(DataPatchSite("bank_90.asm", "ProjectileCooldowns_NonBeamProjectiles", 0x90C27F, op = "db", index = 5, size = 1))))
    add(DataPatchDefinition(BOMB_CONFIG_TYPE, BOMB_EXPLOSION_FRAME_DELAY_KEY, "Bomb explosion frame delay", 1..255,
        listOf(DataPatchSite("bank_93.asm", "Initialize_Bomb_Explosion", 0x93815B, consumer = "STA.W SamusProjectile_InstructionTimers,X"))))
    val music = listOf("Instruction_PLM_PickUpBeam_DisplayMessageBox", "Instruction_PLM_PickUpEquipment_DisplayMessageBox",
        "Inst_PLM_PickUpEquipment_AddGrappleHUD_DisplayMessageBox", "Inst_PLM_PickUpEquipment_AddXrayToHUD_DisplayMessageBox",
        "Instruction_PLM_CollectHealth_EnergyTank", "Instruction_PLM_CollectHealth_ReserveTank", "Instruction_PLM_CollectAmmo_MissileTank",
        "Instruction_PLM_CollectAmmo_SuperMissileTank", "Instruction_PLM_CollectAmmo_PowerBombTank")
    add(DataPatchDefinition(FANFARE_CONFIG_TYPE, FANFARE_FRAMES_KEY, "Item fanfare duration (frames)", 1..9999,
        listOf(DataPatchSite("bank_85.asm", "Handle_MessageBox_Interaction", 0x858491, op = "LDX.W", consumer = "JSR.W Wait_for_Lag_Frame")) +
            music.zip(FANFARE_MUSIC_RESUME_DELAY_PCS).map { (symbol, pc) -> DataPatchSite("bank_84.asm", symbol, 0x848000 + (pc - 0x20000),
                consumer = "JSL.L Play_Room_Music_Track_After_A_Frames") }))
    for (slot in ControllerBindings.slots) add(DataPatchDefinition(ControllerBindings.CONFIG_TYPE, slot.key, slot.label, 0..65535,
        listOf(DataPatchSite("bank_81.asm", "NewSaveFile", slot.newSaveOperand, consumer = "STA.W ${slot.ramSymbol}"),
            DataPatchSite("bank_82.asm", "GameOptions_ControllerSettings_ResetToDefault_reset", slot.resetOperand, consumer = "STA.W ${slot.ramSymbol}"))))
    addAll(PHYSICS_PATCH_DEFINITIONS)
}
internal data class DataPatchWord(val site: DataPatchSite, val span: IntRange, val value: Int, val offset: Int)
internal data class DataPatchField(val definition: DataPatchDefinition, val words: List<DataPatchWord>) {
    val values get() = words.map { it.value }
    val value get() = values.distinct().singleOrNull()?.let(definition::decode)?.takeIf { it in definition.range }
}
internal data class DataPatchGuard(val file: String, val symbol: String, val offset: Int, val instructions: List<String>)
internal val PATCH_DATA_OPS = setOf("db", "dw", "dd")
internal fun patchHex(value: Int, size: Int) = "$" + value.toUInt().toString(16).uppercase().padStart(size * 2, '0')
internal fun patchValueFits(value: Int, size: Int) = size == 4 || value in 0..if (size == 1) 255 else 65535

private val patchWhitespace = Regex("[ \\t]+")
private val patchInstructionWidths = buildMap<String, Int> {
    for (op in "REP SEP BEQ BNE BPL BMI BCC BCS BVC BVS BRA".split(' ')) put(op, 2)
    put("BRL", 3)
    for (op in "PHP PLP PHB PLB PHK PHA PLA PHX PLX PHY PLY CLC SEC NOP RTS RTL TAX TXA TAY TYA TXY TYX INX DEX INY DEY INC DEC ASL LSR XBA".split(' ')) put(op, 1)
    for (op in "LDA LDX LDY STA STX STY STZ CMP CPX CPY BIT TRB TSB AND ORA EOR ADC SBC INC DEC ASL LSR ROL ROR JSR JMP".split(' ')) put("$op.W", 3)
    for (op in "LDA LDX LDY STA STX STY STZ CMP CPX CPY BIT AND ORA EOR ADC SBC".split(' ')) put("$op.B", 2)
    for (op in "LDA STA CMP AND ORA EOR ADC SBC JSL JML".split(' ')) put("$op.L", 4)
}

/** Finite codecs for explicit native operands/data rows. No address-comment parsing or general Asar evaluation. */
internal class AsmDataPatchSource(val originals: Map<String, String>) {
    val definitions = DATA_PATCH_DEFINITIONS.filter { def -> def.sites.all { it.file in originals } }.associateBy { it.id }
    val types = definitions.values.map { it.type }.toSet()
    private data class Code(val text: String, val at: Int)
    private data class Label(val name: String, val start: Int, val end: Int, val local: Boolean)
    private class Document(val text: String) {
        val labels = buildList {
            var scope = ""
            for (match in Regex("(?m)^[ \\t]*([A-Za-z_][A-Za-z0-9_]*|\\.[A-Za-z_][A-Za-z0-9_]*):[^\\r\\n]*(?:\\r?\\n|$)").findAll(text)) {
                val name = match.groupValues[1]
                if (!name.startsWith('.')) scope = name
                add(Label(if (name.startsWith('.')) scope + "_" + name.drop(1) else name, match.range.first, match.range.last + 1, name.startsWith('.')))
            }
        }
        private val blocks = mutableMapOf<String, List<Code>>()
        fun block(symbol: String): List<Code> = blocks.getOrPut(symbol) {
            val label = labels.singleOrNull { it.name == symbol } ?: error("Expected exactly one $symbol declaration.")
            val end = labels.firstOrNull { it.start >= label.end && (!it.local || label.local) }?.start ?: text.length
            val nested = labels.filter { it.start >= label.end && it.start < end }.map { it.start }.toSet()
            buildList {
                var at = label.end
                for (line in text.substring(label.end, end).split('\n')) {
                    val code = asmCodeBeforeComment(line).trim().replace(Regex("^[+-]+[ \\t]+"), "")
                    if (at !in nested && code.isNotBlank() && !code.matches(Regex("[+-]+"))) add(Code(code, at + line.indexOf(code)))
                    at += line.length + 1
                }
            }
        }
    }
    private val documents = mutableListOf<Document>()
    private fun document(text: String) = documents.firstOrNull { it.text === text } ?: Document(text).also {
        if (documents.size == 12) documents.removeAt(0)
        documents.add(it)
    }
    private fun normalize(code: String) = code.trim().replace(patchWhitespace, " ").let {
        it.substringBefore(' ').uppercase() + it.substringAfter(' ', "").let { tail -> if (tail.isEmpty()) "" else " $tail" }
    }
    private fun width(code: String): Int {
        val op = code.takeWhile { !it.isWhitespace() }.uppercase()
        return patchInstructionWidths[op] ?: error("Unrecognized instruction before this field: $code. Edit its ASM directly.")
    }
    private fun tokens(code: Code): List<Pair<IntRange, String>> {
        val start = code.text.indexOfFirst { it.isWhitespace() } + 1
        require(start > 0) { "Expected a db/dw argument." }
        val result = mutableListOf<Pair<IntRange, String>>()
        var depth = 0; var at = start
        for (i in start..code.text.length) {
            val char = code.text.getOrNull(i)
            if (char == '(') depth++
            if (char == ')') depth--
            if ((char == ',' && depth == 0) || char == null) {
                val raw = code.text.substring(at, i); val token = raw.trim(); val local = at + raw.indexOf(token)
                require(token.isNotEmpty() && depth == 0) { "Unsupported data expression." }
                result += (code.at + local until code.at + local + token.length) to token
                at = i + 1
            }
        }
        return result
    }
    private fun literal(token: String, texts: Map<String, String>, site: DataPatchSite): Int {
        if (token == site.nativeExpression) {
            requirePhysicsExpressionDefinitions(originals, texts)
            return requireNotNull(site.nativeValue)
        }
        val size = site.size
        val regional = Regex("regional\\(\\s*(\\$[0-9A-Fa-f]+|[0-9]+)\\s*,\\s*(\\$[0-9A-Fa-f]+|[0-9]+)\\s*\\)").matchEntire(token)
        val value = if (regional == null) {
            if (size == 4) token.removePrefix("$").takeIf { token.startsWith('$') }?.toLongOrNull(16)
                ?.takeIf { it in 0..0xFFFFFFFFL }?.toInt() ?: token.toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL }?.toInt()
            else AsmPlmDocument.literal(token)
        } else {
            fun signature(file: String, pattern: Regex, source: Map<String, String>) = source[file].orEmpty().lineSequence()
                .map { asmCodeBeforeComment(it).replace(Regex("[ \\t]+"), "").trim() }.filter { pattern.containsMatchIn(it) }.toList()
            require(signature("main.asm", Regex("!PAL|^if|^else|^endif"), texts) == signature("main.asm", Regex("!PAL|^if|^else|^endif"), originals) &&
                signature("macros.asm", Regex("^functionregional\\("), texts) == signature("macros.asm", Regex("^functionregional\\("), originals)) {
                "Regional definitions changed. Use explicit literal values for two-way editing."
            }
            AsmPlmDocument.literal(regional.groupValues[1])
        }
        require(value != null && patchValueFits(value, size)) { "Expected a ${size * 8}-bit literal; this field is managed in ASM until repaired." }
        return value
    }
    fun field(id: String, texts: Map<String, String>): DataPatchField {
        val def = requireNotNull(definitions[id]) { "Unavailable patch data $id." }
        return DataPatchField(def, def.sites.map { site ->
            val codes = document(texts.getValue(site.file)).block(site.symbol)
            if (site.op in PATCH_DATA_OPS) {
                val args = codes.takeWhile { !it.text.startsWith("org ", true) }.flatMap { code ->
                    require(code.text.takeWhile { !it.isWhitespace() }.equals(site.op, true)) { "${site.symbol}: expected ${site.op} data only." }
                    tokens(code)
                }
                val (span, token) = args.getOrNull(site.index) ?: error("${site.symbol}: missing data slot ${site.index}.")
                DataPatchWord(site, span, literal(token, texts, site), site.index * site.size)
            } else {
                val immediate = Regex("^${Regex.escape(site.op)}[ \\t]+${if (site.immediate) "#" else ""}", RegexOption.IGNORE_CASE)
                val candidates = codes.indices.filter { index ->
                    immediate.containsMatchIn(codes[index].text) &&
                        (site.consumer == null || normalize(codes.getOrNull(index + 1)?.text.orEmpty()) == site.consumer) &&
                        codes.take(index).takeLast(site.before.size).map { normalize(it.text) } == site.before
                }
                val selected = if (site.index == 0) candidates.singleOrNull() else candidates.getOrNull(site.index)
                val index = selected ?: error("${site.symbol}: expected one checked ${site.op} operand for ${def.label}.")
                val code = codes[index]; val start = if (site.immediate) code.text.indexOf('#') + 1 else code.text.indexOfFirst { it.isWhitespace() } + 1
                val token = code.text.substring(start).trim().removeSuffix(",X")
                val at = code.at + start + code.text.substring(start).indexOf(token)
                DataPatchWord(site, at until at + token.length, literal(token, texts, site), codes.take(index).sumOf { width(it.text) } + 1)
            }
        })
    }
    fun original(id: String) = field(id, originals)
    /** The menu decodes buttons and swaps bindings independently of the two defaults routines. */
    fun controllerInterface(texts: Map<String, String>): List<DataPatchGuard> {
        val file = "bank_82.asm"
        val current = document(texts.getValue(file)); val original = document(originals.getValue(file))
        val roots = listOf("GameOptionsMenu_ControllerBindings", "Save_GameOptionsMenu_ControllerBindings")
        val guards = roots.map { root ->
            val code = current.block(root).map { normalize(it.text) }
            require(code == original.block(root).map { normalize(it.text) }) {
                "Controller options-menu logic changed; its defaults are managed in ASM."
            }
            DataPatchGuard(file, root, 0, code)
        }
        require(current.block("Configurable_Controller_Binding_RAM_Addresses").map { normalize(it.text) } ==
            ControllerBindings.slots.map { "DW ${it.ramSymbol}" }) { "Controller menu action bindings changed; managed in ASM." }
        val masks = current.block("Controller_Input_Bitmasks").flatMap {
            require(it.text.takeWhile { char -> !char.isWhitespace() }.equals("dw", true)) { "Expected word-sized controller button masks." }
            tokens(it).map { (_, token) -> literal(token, texts, DataPatchSite(file, "Controller_Input_Bitmasks", 0)) }
        }
        require(masks == ControllerBindings.menuMasks) { "Controller button decoder changed; restore it before using the visual controller editor." }
        return guards
    }
    fun guards(field: DataPatchField, texts: Map<String, String>): List<DataPatchGuard> = buildList {
        for (word in field.words) if (word.site.op !in PATCH_DATA_OPS) add(DataPatchGuard(word.site.file, word.site.symbol,
            word.offset - 1 - word.site.before.sumOf(::width), word.site.before +
                listOf("${word.site.op} ${if (word.site.immediate) "#" else ""}${patchHex(word.value, word.site.size)}${if (word.site.immediate) "" else ",X"}") + listOfNotNull(word.site.consumer)))
        if (field.definition.type == PHYSICS_CONFIG_TYPE) {
            for (binding in physicsBindings(field.definition)) {
                val codes = document(texts.getValue(binding.file)).block(binding.symbol)
                val matches = codes.indices.filter { index -> codes.drop(index).take(binding.instructions.size).map { normalize(it.text) } == binding.instructions }
                require(matches.size == 1) { "${field.definition.label}: ${binding.symbol} consumer changed; managed in ASM." }
                val index = matches.single()
                add(DataPatchGuard(binding.file, binding.symbol, codes.take(index).sumOf { width(it.text) }, binding.instructions))
            }
            if (field.words.single().site.op == "dd") {
                val file = "bank_94.asm"
                val native = document(originals.getValue(file))
                val roots = native.labels.filter { !it.local && native.block(it.name).any { code -> normalize(code.text) == "LDA.W #SamusXSpeedTable_Normal" } }
                require(roots.isNotEmpty()) { "Normal movement table bindings are unavailable." }
                for (root in roots) {
                    val sequence = listOf("LDA.W #SamusXSpeedTable_Normal", "STA.W XSpeedTablePointer")
                    val codes = document(texts.getValue(file)).block(root.name)
                    val matches = codes.indices.filter { codes.drop(it).take(2).map { code -> normalize(code.text) } == sequence }
                    require(matches.size == native.block(root.name).count { normalize(it.text) == sequence.first() }) { "${root.name}: normal movement table binding changed; managed in ASM." }
                    matches.forEach { add(DataPatchGuard(file, root.name, codes.take(it).sumOf { code -> width(code.text) }, sequence)) }
                }
            }
        }
        if (field.definition.type == BOMB_CONFIG_TYPE && field.definition.key in setOf(BOMB_FUSE_FRAMES_KEY, BOMB_COOLDOWN_FRAMES_KEY)) {
            val root = "HUDSelectionHandler_MorphBall"
            val codes = document(texts.getValue("bank_90.asm")).block(root)
            val sequence = if (field.definition.key == BOMB_FUSE_FRAMES_KEY)
                listOf("LDA.W BombTimerResetValue", "STA.W SamusProjectile_BombTimers-${'$'}A,X") else
                listOf("LDA.W ProjectileCooldowns_NonBeamProjectiles,Y", "AND.W #${'$'}00FF", "STA.W SamusProjectile_CooldownTimer")
            val matches = codes.indices.filter { codes.drop(it).take(sequence.size).map { code -> normalize(code.text) } == sequence }
            require(matches.size == 2) { "Bomb timer/cooldown consumers changed; edit this logic in ASM." }
            for (index in matches) add(DataPatchGuard("bank_90.asm", root, codes.take(index).sumOf { width(it.text) }, sequence))
        }
    }
    private fun fingerprint(id: String, texts: Map<String, String>): Any = runCatching { field(id, texts).values }.getOrElse { it.message.orEmpty() }
    fun changed(id: String, texts: Map<String, String>) = fingerprint(id, texts) != fingerprint(id, originals)
    fun changedKeys(before: Map<String, String>, after: Map<String, String>, type: String? = null) = definitions.values
        .filter { type == null || it.type == type }.map { it.id }.filter { fingerprint(it, before) != fingerprint(it, after) }.toSet()
    fun line(id: String, texts: Map<String, String>): Pair<String, Int> {
        val word = runCatching { field(id, texts).words.first() }.getOrNull()
        val site = word?.site ?: definitions.getValue(id).sites.first()
        val text = texts.getValue(site.file)
        val offset = word?.span?.first ?: document(text).labels.singleOrNull { it.name == site.symbol }?.start ?: 0
        return site.file to text.take(offset).count { it == '\n' }
    }
    fun editorLinks(file: String, line: Int, texts: Map<String, String>): List<AsmEditorLink> {
        val text = texts[file] ?: return emptyList()
        val labels = document(text).labels
        val offset = text.splitToSequence('\n').take(line).sumOf { it.length + 1 }
        val end = text.indexOf('\n', offset).takeIf { it >= 0 } ?: text.length
        fun link(def: DataPatchDefinition) = AsmEditorLink("Edit ${def.label}", "Patch data", AsmEditorTarget.PatchData(def.type, def.key))
        val exact = definitions.values.filter { def -> runCatching { field(def.id, texts).words.any {
            it.site.file == file && it.span.first in offset..end
        } }.getOrDefault(false) }
        if (exact.isNotEmpty()) return exact.map(::link)
        val label = labels.lastOrNull { it.start <= offset } ?: return emptyList()
        // A shared routine's heading can offer a chooser, but unrelated instructions must not
        // pretend to belong to every controller action in NewSaveFile.
        return definitions.values.filter { def -> def.sites.any { site -> site.file == file &&
            (site.symbol == label.name || label.local && label.name.startsWith(site.symbol + "_") ||
                !label.local && site.symbol.startsWith(label.name + "_")) } }
            .let { candidates -> if (candidates.size > 1 && offset !in label.start until label.end) emptyList() else candidates.map(::link) }
    }
    companion object {
        fun load(original: File): AsmDataPatchSource? {
            val files = DATA_PATCH_DEFINITIONS.flatMap { it.sites }.map { it.file }.toSet() + setOf("main.asm", "macros.asm", "bank_94.asm")
            val texts = files.mapNotNull { file -> File(original, "src/$file").takeIf { it.isFile }?.let { file to it.readText() } }.toMap()
            return texts.takeIf { map -> DATA_PATCH_DEFINITIONS.any { def -> def.sites.all { it.file in map } } }?.let(::AsmDataPatchSource)
        }
    }
}

/** Conservative ROM/reference links; live project-source links use parsed spans instead. */
internal fun nativeDataPatchRuntimeAddresses(definition: DataPatchDefinition, parser: RomParser): List<Int> = runCatching {
    val rom = parser.getRomData()
    fun byte(snes: Int) = rom[parser.snesToPc(snes)].toInt() and 255
    fun word(snes: Int) = parser.readUInt16At(parser.snesToPc(snes))
    if (definition.type == ControllerBindings.CONFIG_TYPE) { ControllerBindings.read(rom); ControllerBindings.read(rom, reset = true) }
    if (definition.type == PHYSICS_CONFIG_TYPE) return definition.sites.flatMap { site ->
        if (site.op !in PATCH_DATA_OPS) {
            require(byte(site.native - 1) == if (site.immediate) 0xC9 else 0x7D)
            require(byte(site.native + 2) == if (site.immediate) 0xF0 else 0x8D)
            listOf(site.native - 1, site.native)
        } else {
            val load = when (site.symbol) {
                "SamusPhysicsConstants_InitialYSpeeds_Jumping" -> 0x909905
                "SamusPhysicsConstants_InitialYSpeeds_HiJumpJumping" -> 0x909913
                "SamusPhysicsConstants_InitialYSpeeds_WallJumping" -> 0x909992
                "SamusPhysicsConstants_InitialYSpeeds_HiJumpWallJumping" -> 0x9099A0
                "SamusPhysicsConstants_YSubAccelerationInAir" -> 0x909C7B
                else -> 0x9497D0
            }
            require(byte(load) == if (site.op == "dd") 0xA9 else if (definition.key == "gravity") 0xAD else 0xBD)
            require(byte(load + 3) == 0x8D)
            val target = word(load + 1) + site.index * site.size
            require(target in 0x8000..0x10000 - site.size)
            listOf(0x900000 or target)
        }
    }
    definition.sites.flatMap { site ->
        if (site.op in setOf("db", "dw")) {
            val loads = if (site.op == "dw") listOf(0x90BFFB, 0x90C06D) else listOf(0x90C011, 0x90C083)
            val opcode = if (site.op == "dw") 0xAD else 0xB9
            require(loads.all { byte(it) == opcode })
            val target = loads.map { word(it + 1) }.distinct().single()
            require(target in 0x8000..0xFFF6)
            listOf(0x900000 or (target + site.index * site.size))
        } else {
            val opcode = when (site.op) { "LDX.W" -> 0xA2; "CMP.W" -> 0xC9; else -> 0xA9 }
            val consumer = when { site.consumer?.startsWith("JSL.L") == true -> 0x22; site.consumer?.startsWith("JSR.W") == true -> 0x20
                site.consumer?.startsWith("BPL") == true -> 0x10; site.consumer?.endsWith(",X") == true -> 0x9D; else -> 0x8D }
            if (byte(site.native - 1) == opcode && byte(site.native + 2) == consumer) listOf(site.native - 1, site.native) else emptyList()
        }
    }
}.getOrDefault(emptyList())
