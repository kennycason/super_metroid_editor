package com.supermetroid.editor.ui

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*

/** Native program storage shared by every bank. No engine opcodes other than verified dispatch calls change. */
internal object SoundEffectPrograms {
    val arenas = listOf(0x56E2 until 0x5800, 0x6B27 until 0x6C00)
    private val magic = "SMEDFX01".encodeToByteArray()
    private const val START = 0x56E2
    private val calls = mapOf(1 to 0x1F4A, 2 to 0x31AE, 3 to 0x4773)
    private val pointerVariables = mapOf(1 to 0x22, 2 to 0xD4, 3 to 0xE0)
    private val tables = mapOf(1 to 0x2AED, 2 to 0x39B3, 3 to 0x4E8F)
    private val dataEnds = mapOf(1 to 0x3154, 2 to 0x4703, 3 to 0x5310)
    data class Entry(val library: Int, val id: Int, val routingId: Int, val voices: Int, val pointers: Int, val original: Int) {
        val key get() = key(library, id)
    }
    data class Installed(val entries: List<Entry>, val stubs: Map<Int, Int>, val headerBytes: Int)
    data class Compiled(val ram: ByteArray, val writes: Map<Int, ByteArray>, val used: Int) {
        val capacity get() = arenas.sumOf { it.count() }
    }
    fun key(library: Int, id: Int) = "$library:${id.toString(16).uppercase().padStart(2, '0')}"
    fun count(library: Int) = SoundEffects.identities.count { it.library == library }
    fun copyVoices(voices: List<SoundEffectVoiceProgram>) = voices.map { voice -> voice.copy(steps = voice.steps.map { it.copy(params = it.params.toMutableList()) }.toMutableList()) }.toMutableList()
    fun copy(program: SoundEffectProgram) = program.copy(voices = copyVoices(program.voices), baseline = copyVoices(program.baseline))
    fun copyMap(programs: Map<String, SoundEffectProgram>) = programs.mapValues { copy(it.value) }.toMutableMap()
    fun occupied(ram: ByteArray): List<MusicSequenceBudget.SpcRange> = if (installed(ram) == null) emptyList()
        else arenas.map { MusicSequenceBudget.SpcRange(it.first, it.last + 1) }
    private fun word(ram: ByteArray, at: Int) = (ram[at].toInt() and 255) or ((ram[at + 1].toInt() and 255) shl 8)
    private fun inside(at: Int, size: Int) = size > 0 && arenas.any { at >= it.first && size <= it.last + 1 - at }
    fun installed(ram: ByteArray): Installed? {
        if (!ram.copyOfRange(START, START + 8).contentEquals(magic)) return null
        val effectCount = ram[START + 8].toInt() and 255
        require(effectCount in 1..32 && START + 15 + effectCount * 8 <= 0x5800) { "Invalid effect program directory" }
        val stubs = (1..3).associateWith { word(ram, START + 9 + (it - 1) * 2) }
        val entries = (0 until effectCount).map { index ->
            val at = START + 15 + index * 8
            Entry(ram[at].toInt() and 255, ram[at + 1].toInt() and 255, ram[at + 2].toInt() and 255,
                ram[at + 3].toInt() and 255, word(ram, at + 4), word(ram, at + 6)).also {
                val routing = SoundEffects.identities.firstOrNull { identity -> identity.library == it.library && identity.id == it.routingId }
                require(it.id in 1..128 && routing?.voices == it.voices && inside(it.pointers, it.voices * 2) &&
                    (it.id > count(it.library) || it.routingId == it.id)) { "Invalid effect routing or voice directory" }
                require(if (it.id > count(it.library)) it.original == 0 else
                    it.original >= tables.getValue(it.library) && it.original + it.voices * 2 <= dataEnds.getValue(it.library)) { "Invalid original effect directory" }
            }
        }
        require(entries.map { it.key }.distinct().size == entries.size) { "Duplicate native effect ID" }
        return Installed(entries, stubs, 15 + effectCount * 8)
    }
    private fun stub(entries: List<Entry>, library: Int): ByteArray {
        val out = mutableListOf<Int>()
        val variable = pointerVariables.getValue(library)
        for (entry in entries.filter { it.library == library && it.id > count(library) }.sortedBy { it.id }) {
            out += listOf(0x68, entry.id, 0xD0, 11, 0x8F, entry.pointers and 255, variable,
                0x8F, entry.pointers ushr 8, variable + 1, 0xE8, entry.routingId, 0x5F, 0x75, 0x28)
        }
        out += listOf(0x5F, 0x75, 0x28)
        return out.map(Int::toByte).toByteArray()
    }
    /** Validate every installed instruction/pointer before normalizing the three dispatch operands for ABI hashing. */
    fun normalized(ram: ByteArray): ByteArray {
        val info = installed(ram) ?: return ram
        val result = ram.copyOf()
        val claimed = mutableListOf<IntRange>(START until START + info.headerBytes)
        fun claim(at: Int, size: Int, shared: Boolean = false) {
            require(inside(at, size)) { "Effect data exceeds owned memory" }
            val range = at until at + size
            require(claimed.none { it.first <= range.last && range.first <= it.last && !(shared && it == range) }) { "Effect program resources overlap" }
            if (range !in claimed) claimed += range
        }
        for (library in 1..3) {
            val address = info.stubs.getValue(library)
            val code = stub(info.entries, library)
            if (info.entries.any { it.library == library && it.id > count(library) }) {
                claim(address, code.size)
                require(ram.copyOfRange(address, address + code.size).contentEquals(code)) { "Effect dispatch code differs" }
                require(word(ram, calls.getValue(library) + 1) == address) { "Effect dispatch hook differs" }
                result[calls.getValue(library) + 1] = 0x75; result[calls.getValue(library) + 2] = 0x28
            } else require(address == 0) { "Unexpected effect dispatch hook" }
        }
        for (entry in info.entries) {
            claim(entry.pointers, entry.voices * 2)
            if (entry.id <= count(entry.library)) require(word(ram, tables.getValue(entry.library) + (entry.id - 1) * 2) == entry.pointers) { "Effect pointer hook differs" }
            for (voice in 0 until entry.voices) {
                val at = word(ram, entry.pointers + voice * 2)
                val steps = decode(ram, at)
                val data = encode(SoundEffectVoiceProgram(steps))
                claim(at, data.size, shared = true)
            }
        }
        return result
    }
    fun decode(ram: ByteArray, start: Int): MutableList<SoundEffectInstruction> {
        val result = mutableListOf<SoundEffectInstruction>()
        var at = start
        while (at - start < 256) {
            require(inside(at, 1)) { "Effect voice points outside owned memory" }
            val opcode = ram[at++].toInt() and 255
            val size = parameterCount(opcode)
            require(inside(at - 1, size + 1) && at + size - start <= 256) { "Truncated effect voice" }
            val params = MutableList(size) { ram[at++].toInt() and 255 }
            result += SoundEffectInstruction(opcode, params)
            if (opcode == 0xFF) { encode(SoundEffectVoiceProgram(result)); return result }
        }
        error("Effect voice exceeds its 256-byte index")
    }
    fun parameterCount(opcode: Int) = when (opcode) {
        in 0..41 -> 4
        0xF5, 0xF8, 0xF9 -> 2
        0xFE -> 1
        0xFB, 0xFC, 0xFD, 0xFF -> 0
        else -> error("Unsupported effect instruction")
    }
    fun encode(voice: SoundEffectVoiceProgram): ByteArray {
        require(voice.steps.isNotEmpty() && voice.steps.last().opcode == 0xFF && voice.steps.dropLast(1).none { it.opcode == 0xFF }) { "Every voice needs one final End step" }
        val values = voice.steps.flatMap { step ->
            require(step.params.size == parameterCount(step.opcode) && step.params.all { it in 0..255 }) { "Invalid effect operands" }
            when (step.opcode) {
                in 0..41 -> require(step.params[1] in 0..20 && (step.params[2] in 0x80..0xC7 || step.params[2] == 0xF6)) { "Invalid note pan or pitch" }
                0xF5, 0xF8 -> require(step.params[1] in 0x80..0xC7) { "Invalid slide pitch" }
            }
            listOf(step.opcode) + step.params
        }
        require(values.size <= 256) { "One effect voice can contain at most 256 bytes" }
        validateExecution(values)
        return values.map(Int::toByte).toByteArray()
    }
    /** Follow the native prefix/repeat phases, rejecting operand-as-opcode and zero-time cycles. */
    private fun validateExecution(bytes: List<Int>) {
        var pc = 0; var repeatPoint = -1; var counter = 0; var notes = 0
        val seen = mutableMapOf<Triple<Int, Int, Int>, Int>()
        fun next(): Int { require(pc in bytes.indices) { "Effect program runs past End" }; return bytes[pc++] }
        repeat(70_000) {
            val state = Triple(pc, repeatPoint, counter)
            seen[state]?.let { before -> require(notes > before) { "Repeat needs a note with a duration" }; return }
            seen[state] = notes
            var op = next()
            if (op == 0xF9) { next(); next(); return@repeat }
            if (op == 0xF5 || op == 0xF8) { next(); next(); op = next() }
            if (op == 0xFF) return
            if (op == 0xFE) { counter = next(); repeatPoint = pc; op = next() }
            if (op == 0xFD) {
                require(repeatPoint >= 0) { "Counted repeat needs a Repeat point" }
                counter = (counter - 1) and 255
                if (counter == 0) return@repeat
                pc = repeatPoint; op = next()
            }
            if (op == 0xFB) {
                require(repeatPoint >= 0) { "Repeat needs a Repeat point" }
                pc = repeatPoint; op = next()
                require(op != 0xFB) { "Repeat needs a note with a duration" }
            }
            if (op == 0xFC) return@repeat
            require(op in 0..41) { "Slide/repeat prefixes must lead to a note or Noise on" }
            next(); next(); next(); next(); notes++
        }
        error("Effect control flow exceeds validation limits")
    }
    fun capture(effect: SoundEffects.Effect): MutableList<SoundEffectVoiceProgram> = effect.voices.map { voice ->
        SoundEffectVoiceProgram(voice.steps.map { step ->
            val opcode = when (step.label) { "Note" -> step.operands[0].value; "Legato slide" -> 0xF5; "Pitch slide" -> 0xF8; "Envelope" -> 0xF9;
                "Repeat point" -> 0xFE; "Repeat" -> 0xFB; "Noise on" -> 0xFC; "Counted repeat" -> 0xFD; "End" -> 0xFF; else -> error("Unknown step") }
            SoundEffectInstruction(opcode, step.operands.drop(if (opcode in 0..41) 1 else 0).map { it.value }.toMutableList())
        }.toMutableList())
    }.toMutableList()
    fun requireStorage(parser: RomParser, project: SmEditProject) {
        fun overlaps(at: Int, size: Int) = arenas.any { at <= it.last && at + size > it.first }
        val initial = SpcData.buildInitialSpcRam(parser)
        val installed = installed(initial) != null
        for (set in (SpcData.TRANSFER_SONG_SETS + SpcMusicBankTable.songSets(parser)).distinct().filter { it > 0 }) {
            val pointer = SpcData.readSongSetPointer(parser, set)
            for (block in SpcTransferStream.read(parser, pointer).blocks) require(!overlaps(block.destAddr, block.data.size) ||
                installed && arenas.any { range -> block.destAddr == range.first && block.data.size == range.count() && block.data.contentEquals(initial.copyOfRange(range.first, range.last + 1)) }) {
                "Bank ${MusicInstrumentBanks.key(set)} uploads data into reserved effect memory"
            }
        }
        for (edit in project.musicEdits.values) for (block in edit.nativePayload?.blocks.orEmpty()) require(!overlaps(block.destAddr, java.util.Base64.getDecoder().decode(block.dataBase64).size)) {
            "An imported music source owns effect memory; choose a different source before authoring effects"
        }
        for (bank in project.musicBanks.values) for (block in bank.sourceBlocks) require(!overlaps(block.destAddr, java.util.Base64.getDecoder().decode(block.dataBase64).size)) { "A bank palette owns effect memory" }
        for (bank in project.musicInstrumentBanks.values) for (sample in bank.samples.values) require(!overlaps(sample.startAddr, MusicSampleBanks.decode(sample).size)) {
            "A project sample owns effect memory; move that sample before authoring effects"
        }
    }
    fun definitions(ram: ByteArray): Map<String, SoundEffectProgram> = installed(ram)?.entries.orEmpty().associate { entry ->
        val voices = (0 until entry.voices).map { SoundEffectVoiceProgram(decode(ram, word(ram, entry.pointers + it * 2))) }.toMutableList()
        val name = SoundEffects.identities.firstOrNull { it.key == entry.key }?.name ?: "Custom effect ${entry.key}"
        entry.key to SoundEffectProgram(entry.library, entry.id, name, entry.routingId, voices, copyVoices(voices))
    }
    fun compile(base: ByteArray, programs: Map<String, SoundEffectProgram>): Compiled {
        if (programs.isEmpty()) return Compiled(base, emptyMap(), 0)
        val old = installed(base)
        val merged = definitions(base).toMutableMap().also { it.putAll(copyMap(programs)) }
        require(merged.size <= 32) { "Too many effect programs" }
        val clean = normalized(base).copyOf()
        for (entry in old?.entries.orEmpty()) if (entry.id <= count(entry.library)) {
            val at = tables.getValue(entry.library) + (entry.id - 1) * 2
            clean[at] = entry.original.toByte(); clean[at + 1] = (entry.original ushr 8).toByte()
        }
        for (range in arenas) clean.fill(0, range.first, range.last + 1)
        val headerSize = 15 + merged.size * 8
        require(headerSize <= arenas.first().count()) { "Effect directory exceeds memory" }
        val free = mutableListOf(START + headerSize until 0x5800, arenas[1])
        val objects = linkedMapOf<String, ByteArray>()
        val voiceKeys = mutableMapOf<Pair<String, Int>, String>()
        val unique = mutableMapOf<List<Byte>, String>()
        for ((key, program) in merged.toSortedMap()) {
            val route = SoundEffects.identities.firstOrNull { it.library == program.library && it.id == program.routingId }
            require(key == SoundEffectPrograms.key(program.library, program.id) && program.id in 1..128 && program.name.isNotBlank() && program.name.length <= 100 &&
                route?.voices == program.voices.size && (program.id > count(program.library) || program.routingId == program.id)) { "Invalid effect identity or routing" }
            require(program.baseline.size == program.voices.size) { "Effect baseline has a different voice count" }
            program.baseline.forEach { encode(it) }
            for ((index, voice) in program.voices.withIndex()) {
                val bytes = encode(voice)
                val resource = unique.getOrPut(bytes.asList()) { "voice:${unique.size}".also { objects[it] = bytes } }
                voiceKeys[key to index] = resource
            }
            objects["pointers:$key"] = ByteArray(program.voices.size * 2)
        }
        for (library in 1..3) {
            val n = merged.values.count { it.library == library && it.id > count(library) }
            if (n > 0) objects["stub:$library"] = ByteArray(n * 15 + 3)
        }
        val addresses = mutableMapOf<String, Int>()
        for ((key, data) in objects.entries.sortedByDescending { it.value.size }) {
            val index = free.indexOfFirst { it.count() >= data.size }
            require(index >= 0) { "Effect memory is full or fragmented: ${headerSize + objects.values.sumOf { it.size }} B requested in ${arenas.sumOf { it.count() }} B; shorten a voice or remove a project effect" }
            val gap = free[index]; addresses[key] = gap.first; free[index] = gap.first + data.size..gap.last
        }
        val entries = merged.toSortedMap().map { (key, p) ->
            for (v in p.voices.indices) {
                val pointer = addresses.getValue(voiceKeys.getValue(key to v))
                objects.getValue("pointers:$key")[v * 2] = pointer.toByte(); objects.getValue("pointers:$key")[v * 2 + 1] = (pointer ushr 8).toByte()
            }
            Entry(p.library, p.id, p.routingId, p.voices.size, addresses.getValue("pointers:$key"),
                if (p.id <= count(p.library)) word(clean, tables.getValue(p.library) + (p.id - 1) * 2) else 0)
        }
        val writes = linkedMapOf<Int, ByteArray>()
        for (range in arenas) writes[range.first] = ByteArray(range.count())
        val header = ByteArray(headerSize).also { magic.copyInto(it); it[8] = entries.size.toByte() }
        for (library in 1..3) addresses["stub:$library"]?.let { at ->
            val bytes = stub(entries, library); objects["stub:$library"] = bytes
            header[9 + (library - 1) * 2] = at.toByte(); header[10 + (library - 1) * 2] = (at ushr 8).toByte()
            writes[calls.getValue(library)] = byteArrayOf(0x3F, at.toByte(), (at ushr 8).toByte())
        }
        entries.forEachIndexed { i, entry ->
            val at = 15 + i * 8
            listOf(entry.library, entry.id, entry.routingId, entry.voices, entry.pointers and 255, entry.pointers ushr 8, entry.original and 255, entry.original ushr 8)
                .forEachIndexed { j, value -> header[at + j] = value.toByte() }
            if (entry.id <= count(entry.library)) writes[tables.getValue(entry.library) + (entry.id - 1) * 2] = byteArrayOf(entry.pointers.toByte(), (entry.pointers ushr 8).toByte())
        }
        for ((key, bytes) in objects) bytes.copyInto(clean, addresses.getValue(key))
        header.copyInto(clean, START)
        for ((at, bytes) in writes) if (at !in arenas.map { it.first }) bytes.copyInto(clean, at)
        for (range in arenas) writes[range.first] = clean.copyOfRange(range.first, range.last + 1)
        normalized(clean)
        return Compiled(clean, writes, headerSize + objects.values.sumOf { it.size })
    }
}
