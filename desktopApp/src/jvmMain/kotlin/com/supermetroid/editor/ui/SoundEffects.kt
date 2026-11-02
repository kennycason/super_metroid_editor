package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.SoundEffectByteEdit
import com.supermetroid.editor.rom.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Stock SPC effect ABI verified against PJBoy/SM-SPC 17b0c1ee46eed2f97909fbb6141a5ee6a693c45a. */
internal object SoundEffects {
    @Serializable data class Identity(val library: Int, val id: Int, val name: String, val voices: Int) {
        val key get() = "$library:${id.toString(16).uppercase().padStart(2, '0')}"
    }
    enum class Field(val label: String, val range: IntRange) {
        SOUND("Sound", 0..41), LEVEL("Level", 0..255), PAN("Pan", 0..20), PITCH("Pitch", 0x80..0xC7), LENGTH("Length", 0..255),
        RATE("Slide rate", 0..255), TARGET("Target pitch", 0x80..0xC7), ADSR1("ADSR1", 0..255), ADSR2("ADSR2", 0..255), REPEATS("Repeats", 0..255)
    }
    data class Operand(val address: Int, val field: Field, val value: Int)
    data class Step(val address: Int, val label: String, val operands: List<Operand>)
    data class Voice(val index: Int, val address: Int, val steps: List<Step>)
    data class Effect(val identity: Identity, val voices: List<Voice>, val problem: String? = null)
    val identities: List<Identity> by lazy {
        Json.decodeFromString(requireNotNull(javaClass.getResource("/sound/effects.json")).readText())
    }
    private val tables = mapOf(1 to 0x2AED, 2 to 0x39B3, 3 to 0x4E8F)
    private val ends = mapOf(1 to 0x3154, 2 to 0x4703, 3 to 0x5310)
    private val code = listOf(Triple(0x1500, 0x1E1D, "42a2551cd84ebb33bb9f03ab2cb335b35b1a4ba6919a39deb2d4a494995e9f8d"),
        Triple(0x1EE4, 0x2AED, "ae4e686547579c403e0d86e5e166055f2489e832d290aa5adcb0243859be0823"),
        Triple(0x3154, 0x39B3, "507c71641309ef14bdfd1cf19b98f06f2774c8725d50e7c00142c3fc8e850bbd"),
        Triple(0x4703, 0x4E8F, "1710c43fbaf56d2ae63b03116a00a332eec7d26c6b683ab7f6bc7989fcaea526"))
    fun requireEngine(ram: ByteArray) {
        val normalized = SoundEffectPrograms.normalized(ram)
        require(ram.size == 65536 && code.all { (start, end, hash) ->
            MessageDigest.getInstance("SHA-256").digest(normalized.copyOfRange(start, end)).joinToString("") { "%02x".format(it) } == hash
        }) { "Effects editing requires the supported Super Metroid SPC engine" }
    }
    fun catalog(ram: ByteArray, project: SmEditProject? = null): List<Effect> {
        requireEngine(ram)
        val extra = SoundEffectPrograms.installed(ram)?.entries.orEmpty().filter { it.id > SoundEffectPrograms.count(it.library) }
            .map { Identity(it.library, it.id, "Custom effect ${it.key}", it.voices) }
        return (identities + extra).map { original ->
            val identity = original.copy(name = project?.soundEffectPrograms?.get(original.key)?.name ?: original.name)
            try { read(ram, identity) } catch (error: Exception) { Effect(identity, emptyList(), error.message ?: "Invalid effect program") }
        }
    }
    private fun read(ram: ByteArray, identity: Identity): Effect {
        val table = tables.getValue(identity.library)
        val end = ends.getValue(identity.library)
        fun byte(at: Int) = ram[at].toInt() and 255
        fun word(at: Int): Int { require(at >= table && at + 1 < end); return byte(at) or (byte(at + 1) shl 8) }
        val owned = SoundEffectPrograms.installed(ram)?.entries?.firstOrNull { it.key == identity.key }
        val set = owned?.pointers ?: word(table + (identity.id - 1) * 2)
        val voices = (0 until identity.voices).map { voice ->
            val pointer = set + voice * 2
            val start = if (owned == null) word(pointer) else byte(pointer) or (byte(pointer + 1) shl 8)
            // Dachora's library-2 effects deliberately share library-3 voice programs.
            val voiceEnd = (listOf(0x2B71 until 0x3154, 0x3AB1 until 0x4703, 0x4EED until 0x5310) + if (owned != null) SoundEffectPrograms.arenas else emptyList())
                .firstOrNull { start in it }?.let { it.last + 1 } ?: error("Effect voice points outside program data")
            var at = start
            var ended = false
            val steps = mutableListOf<Step>()
            while (!ended && at - start < 256) {
                require(at >= start && at < voiceEnd) { "Effect program exceeds its data region" }
                val opcode = byte(at)
                val fields: List<Field>
                val label: String
                val skip: Int
                when (opcode) {
                    0xF5, 0xF8 -> { label = if (opcode == 0xF5) "Legato slide" else "Pitch slide"; fields = listOf(Field.RATE, Field.TARGET); skip = 1 }
                    0xF9 -> { label = "Envelope"; fields = listOf(Field.ADSR1, Field.ADSR2); skip = 1 }
                    0xFE -> { label = "Repeat point"; fields = listOf(Field.REPEATS); skip = 1 }
                    0xFB, 0xFC, 0xFD, 0xFF -> {
                        label = when (opcode) { 0xFB -> "Repeat"; 0xFC -> "Noise on"; 0xFD -> "Counted repeat"; else -> "End" }
                        fields = emptyList(); skip = 1; ended = opcode == 0xFF
                    }
                    else -> {
                        require(opcode in 0..41) { "Unsupported effect instruction at ${at.toString(16)}" }
                        label = "Note"; fields = listOf(Field.SOUND, Field.LEVEL, Field.PAN, Field.PITCH, Field.LENGTH); skip = 0
                    }
                }
                require(at + skip + fields.size <= voiceEnd && at + skip + fields.size - start <= 256) { "Truncated effect instruction" }
                val operands = fields.mapIndexed { offset, field -> Operand(at + skip + offset, field, byte(at + skip + offset)) }
                steps += Step(at, label, operands)
                at += skip + fields.size
            }
            require(ended) { "Effect program has no ending instruction within its byte-indexed voice" }
            Voice(voice, start, steps)
        }
        return Effect(identity, voices)
    }

    fun changes(parser: RomParser, project: SmEditProject): Map<Int, SoundEffectByteEdit> {
        if (project.soundEffectEdits.isEmpty()) return emptyMap()
        val ram = SpcData.buildInitialSpcRam(parser)
        val fields = catalog(ram).flatMap { it.voices }.flatMap { it.steps }.flatMap { it.operands }.associateBy { it.address }
        return project.soundEffectEdits.map { (key, edit) ->
            val at = key.toIntOrNull(16) ?: error("Invalid sound-effect address")
            require(key == at.toString(16).uppercase().padStart(4, '0')) { "Sound-effect addresses must have one canonical identity" }
            val field = requireNotNull(fields[at]) { "Sound-effect edit targets code or an unsupported program" }
            require(edit.value in field.field.range && edit.expected in 0..255) { "Invalid ${field.field.label} value" }
            require(field.value == edit.expected || field.value == edit.value) { "Sound-effect source changed at ${at.toString(16)}" }
            at to edit
        }.toMap()
    }
    fun bootRam(parser: RomParser, project: SmEditProject): ByteArray {
        val ram = SpcData.buildInitialSpcRam(parser)
        for ((at, edit) in changes(parser, project)) ram[at] = edit.value.toByte()
        if (project.soundEffectPrograms.isNotEmpty() || SoundEffectPrograms.installed(ram) != null) SoundEffectPrograms.requireStorage(parser, project)
        return SoundEffectPrograms.compile(ram, project.soundEffectPrograms).ram
    }

    fun exportWrites(parser: RomParser, project: SmEditProject): Map<Int, Byte> {
        val edits = changes(parser, project)
        if (edits.isEmpty()) return emptyMap()
        val writes = mutableMapOf<Int, Byte>()
        for (pointer in (listOf(0xCF8000) + (SpcData.TRANSFER_SONG_SETS + SpcMusicBankTable.songSets(parser)).distinct().map { SpcData.readSongSetPointer(parser, it) }).distinct()) {
            val chain = SpcTransferStream.read(parser, pointer)
            for ((at, edit) in edits) {
                val source = chain.sourceBlocks.lastOrNull { at in it.destAddr until it.destAddr + it.data.size } ?: continue
                val value = source.data[at - source.destAddr].toInt() and 255
                require(value == edit.expected || value == edit.value) { "A bank replaces the edited effect program at SPC ${at.toString(16)}" }
                writes[source.dataStartPc + at - source.destAddr] = edit.value.toByte()
            }
        }
        for (edit in project.musicEdits.values) for (block in edit.nativePayload?.blocks.orEmpty()) {
            val bytes = java.util.Base64.getDecoder().decode(block.dataBase64)
            require(edits.keys.none { it in block.destAddr until block.destAddr + bytes.size }) { "An imported bank owns edited effect data" }
        }
        return writes
    }
}
