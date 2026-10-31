package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

/** Portable native sound data. No source-bank addresses, slots or project references. */
@Serializable
internal data class SoundPreset(
    val id: String,
    val name: String,
    val adsr1: Int,
    val adsr2: Int,
    val gain: Int,
    val pitchAdj: Int,
    val brrBase64: String? = null,
    val loopByteOffset: Int? = null,
    val noiseSource: Int? = null,
    val version: Int = 1,
) {
    fun instrument(index: Int, srcn: Int = noiseSource ?: 0) = NspcRenderer.InstrumentEntry(srcn, adsr1, adsr2, gain, pitchAdj,
        index, 0x6C00 + index * 6)
}

internal object SoundPresets {
    const val SAMPLE_START = 0x6E00
    const val MAX_BYTES = (65536 - SAMPLE_START) / 9 * 9
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    data class Application(val target: SampleBankPreview.Target, val bank: MusicInstrumentBankEdit, val preset: SoundPreset,
        val addedBytes: Int, val remainingBytes: Int, val reused: Boolean, val changed: Boolean)

    fun bytes(preset: SoundPreset): ByteArray? {
        require(preset.version == 1) { "Unsupported sound preset version" }
        require(UUID.fromString(preset.id).toString() == preset.id) { "Invalid sound preset identity" }
        require(preset.name.isNotBlank() && preset.name.length <= 80) { "Preset name must contain 1–80 characters" }
        require(listOf(preset.adsr1, preset.adsr2, preset.gain).all { it in 0..255 } && preset.pitchAdj in 0..65535) { "Invalid sound preset settings" }
        val noise = preset.noiseSource
        if (noise != null) {
            require(noise in 0x80..255 && preset.brrBase64 == null && preset.loopByteOffset == null) { "Invalid noise preset" }
            return null
        }
        val data = requireNotNull(preset.brrBase64) { "Sound preset has no sample" }
        require(data.length <= (MAX_BYTES + 2) / 3 * 4) { "Sound preset sample is too large" }
        val decoded = Base64.getDecoder().decode(data)
        require(decoded.size <= MAX_BYTES) { "Sound preset sample is too large" }
        val loop = preset.loopByteOffset
        require(loop == null || loop >= 0 && loop % 9 == 0) { "Invalid preset loop point" }
        MusicSamplePlacement.validate(SampleAuthoring.Encoded(decoded, ShortArray(decoded.size / 9 * 16), loop?.let { it / 9 * 16 }))
        return decoded
    }

    fun encode(preset: SoundPreset): String { bytes(preset); return json.encodeToString(preset) }
    fun decode(text: String): SoundPreset = json.decodeFromString<SoundPreset>(text).also { bytes(it) }

    fun capture(parser: RomParser, ram: ByteArray, index: Int, name: String): SoundPreset {
        requireOriginalEngine(parser, ram)
        val entry = NspcRenderer.readInstrumentTable(ram).firstOrNull { it.index == index } ?: error("Choose an available sound")
        val sample = if (entry.srcn < 0x80) sample(ram, entry.srcn) else null
        return SoundPreset(UUID.randomUUID().toString(), name.trim(), entry.adsr1, entry.adsr2, entry.gain, entry.pitchAdj,
            sample?.first?.let { Base64.getEncoder().encodeToString(it) }, sample?.second, entry.srcn.takeIf { it >= 0x80 }).also { bytes(it) }
    }

    /** Flatten a linked intro/tail without re-encoding or resetting its BRR predictor history. */
    internal fun sample(ram: ByteArray, srcn: Int): Pair<ByteArray, Int?> {
        require(ram.size == 65536 && srcn in 0 until SpcData.SAMPLE_COUNT) { "Invalid preset sample source" }
        fun word(at: Int) = (ram[at].toInt() and 255) or ((ram[at + 1].toInt() and 255) shl 8)
        fun stream(start: Int): ByteArray {
            require(start >= 0x5800) { "Sample source is unavailable" }
            var at = start
            while (at + 9 <= ram.size) {
                require(!(at < SpcData.SAMPLE_DIRECTORY_END && at + 9 > 0x6C00)) { "Sample overlaps native sound tables" }
                val last = ram[at].toInt() and 1 != 0
                at += 9
                require(at - start <= MAX_BYTES) { "Sample is too large for a portable sound preset" }
                if (last) return ram.copyOfRange(start, at)
            }
            error("Sample has no complete ending BRR block")
        }
        val dir = SpcData.SAMPLE_DIRECTORY + srcn * 4
        val start = word(dir)
        val loop = word(dir + 2)
        val intro = stream(start)
        if (intro[intro.size - 9].toInt() and 2 == 0) return intro to null
        if (loop in start until start + intro.size) {
            require((loop - start) % 9 == 0) { "Sample loop is not aligned to a BRR block" }
            return intro to (loop - start)
        }
        val tail = stream(loop)
        require(intro.size + tail.size <= MAX_BYTES) { "Sample is too large for a portable sound preset" }
        intro[intro.size - 9] = (intro[intro.size - 9].toInt() and 0xFE).toByte()
        return (intro + tail) to if (tail[tail.size - 9].toInt() and 2 != 0) intro.size else null
    }

    fun preview(parser: RomParser, preset: SoundPreset, index: Int, note: NspcSequence.Note, tempo: Int,
        noiseRam: ByteArray? = null, held: Boolean = false): InstrumentAudition.Preview {
        val data = bytes(preset)
        // Noise still decodes a backing BRR stream on the DSP. Preserve the destination's
        // directory/sample setup so its end flags behave exactly as they will after Apply.
        val ram = if (data == null && noiseRam != null) noiseRam.copyOf() else SpcData.buildInitialSpcRam(parser)
        if (data != null) {
            // A disposable single-voice bank: clear references before replacing samples.
            ram.fill(0, 0x6C00, SpcData.SAMPLE_DIRECTORY_END)
            data.copyInto(ram, SAMPLE_START)
            val loop = SAMPLE_START + (preset.loopByteOffset ?: 0)
            byteArrayOf(SAMPLE_START.toByte(), (SAMPLE_START ushr 8).toByte(), loop.toByte(), (loop ushr 8).toByte())
                .copyInto(ram, SpcData.SAMPLE_DIRECTORY)
        }
        return InstrumentAudition.build(ram, preset.instrument(index), note.copy(instrument = index), tempo, held)
    }

    fun plan(parser: RomParser, input: SampleBankPreview.Target, preset: SoundPreset): Application {
        val data = bytes(preset)
        val target = input.copy(note = input.note.copy(), project = MusicBankTransitions.snapshot(input.project))
        val project = target.project
        val key = MusicInstrumentBanks.key(target.songSet)
        val context = SampleBankPreview.load(parser, target)
        val ram = MusicBankResolver.sourceRam(parser, project, target.songSet)
        val current = NspcRenderer.readInstrumentTable(ram).firstOrNull { it.index == target.note.instrument }
            ?: error("Choose an available sound")
        val bank = MusicInstrumentBanks.copy(project.musicInstrumentBanks[key]) ?: MusicInstrumentBankEdit()
        var added = 0
        var remaining = context.layout.totalBytes
        var reused = false
        val srcn = if (data == null) requireNotNull(preset.noiseSource) else {
            val match = MusicSampleCatalog.read(ram).sortedBy { if (it.srcn == current.srcn) 0 else 1 }.firstOrNull { candidate ->
                !candidate.isNoise && runCatching { sample(ram, candidate.srcn) }.getOrNull()?.let {
                    it.second == preset.loopByteOffset && it.first.contentEquals(data)
                } == true
            }
            if (match != null) { reused = true; match.srcn } else {
                val encoded = SampleAuthoring.Encoded(data, ShortArray(data.size / 9 * 16), preset.loopByteOffset?.let { it / 9 * 16 })
                val application = MusicSampleBanks.plan(parser, target, encoded, preset.name)
                bank.samples.putAll(application.bank.samples)
                added = data.size; remaining = application.remainingBytes
                application.srcn
            }
        }
        val updated = preset.instrument(target.note.instrument, srcn)
        val source = NspcRenderer.readInstrumentTable(MusicBankResolver.sourceRam(parser, project, target.songSet, includeOverrides = false))
            .firstOrNull { it.index == target.note.instrument } ?: error("Choose an available sound")
        if (PianoRollPreviewLogic.instrumentBytes(source).contentEquals(PianoRollPreviewLogic.instrumentBytes(updated))) bank.instruments.remove(updated.index)
        else bank.instruments[updated.index] = MusicInstrumentBanks.entry(updated)
        val staged = MusicBankTransitions.snapshot(project)
        if (bank.instruments.isEmpty() && bank.samples.isEmpty()) staged.musicInstrumentBanks.remove(key) else staged.musicInstrumentBanks[key] = bank
        MusicBankResolver.resolve(parser, staged, target.songSet)
        return Application(target, bank, preset, added, remaining, reused,
            project.musicInstrumentBanks[key] != bank.takeIf { it.instruments.isNotEmpty() || it.samples.isNotEmpty() })
    }

    private fun requireOriginalEngine(parser: RomParser, ram: ByteArray) {
        val boot = SpcData.buildInitialSpcRam(parser)
        require(ram.size == 65536 && (0x1500 until 0x5800).all { ram[it] == boot[it] }) { "Sound presets require the original SPC engine" }
    }
}

/** One atomic file per preset. Opening the library never creates or rewrites files. */
internal class SoundPresetLibrary(private val directory: File = File(System.getProperty("user.home"), ".smedit/sound-presets")) {
    data class Loaded(val presets: List<SoundPreset>, val problems: List<String>)
    fun load(): Loaded {
        if (!directory.exists()) return Loaded(emptyList(), emptyList())
        val files = requireNotNull(directory.listFiles()) { "Cannot read the sound preset library" }
            .filter { it.isFile && it.extension == "smedit-sound" }.sortedBy { it.name }
        val problems = mutableListOf<String>()
        val presets = files.mapNotNull { file ->
            try {
                require(file.length() <= 256 * 1024) { "Preset file is too large" }
                SoundPresets.decode(file.readText()).also { require(file.nameWithoutExtension == it.id) { "Preset file identity does not match its name" } }
            } catch (error: Exception) { problems += "${file.name}: ${error.message}"; null }
        }
        return Loaded(presets.sortedWith(compareBy<SoundPreset> { it.name.lowercase() }.thenBy { it.id }), problems)
    }
    fun save(preset: SoundPreset) {
        val text = SoundPresets.encode(preset)
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(directory.toPath(), ".sound-", ".tmp")
        try {
            Files.writeString(temporary, text)
            Files.move(temporary, File(directory, "${preset.id}.smedit-sound").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun delete(preset: SoundPreset) { SoundPresets.bytes(preset); Files.deleteIfExists(File(directory, "${preset.id}.smedit-sound").toPath()) }
}
