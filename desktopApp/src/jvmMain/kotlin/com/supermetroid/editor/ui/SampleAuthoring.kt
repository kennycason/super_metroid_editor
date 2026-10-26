package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.*
import kotlin.random.Random

/** Standalone PCM authoring asset, not a project bank edit or a claim of spare native slots. */
@Serializable
internal data class SampleDraft(
    val name: String,
    val pcm: List<Int>,
    val trimStart: Int = 0,
    val trimEnd: Int = pcm.size,
    val loopStart: Int? = null,
    val gainPercent: Int = 80,
    val version: Int = 1,
    val generator: String? = null,
    val periodFrames: Int = 128,
)

internal object SampleAuthoring {
    const val RATE = 32000
    const val MAX_FRAMES = 32768
    enum class Shape { SINE, TRIANGLE, PULSE, SAW, NOISE }
    data class Encoded(val brr: ByteArray, val pcm: ShortArray, val loopFrame: Int?) {
        val loopByteOffset get() = loopFrame?.let { it / 16 * 9 }
    }
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun generate(shape: Shape, period: Int = 128): SampleDraft {
        require(period in 32..512 && period % 16 == 0) { "Period must be 32–512 frames, in 16-frame blocks" }
        val random = Random(0x534D)
        val frames = if (shape == Shape.NOISE) 4096 else period * 4
        val pcm = List(frames) { frame ->
            val phase = (frame % period).toDouble() / period
            val value = when (shape) {
                Shape.SINE -> sin(phase * 2 * PI)
                Shape.TRIANGLE -> 1 - 4 * abs(phase - 0.5)
                Shape.PULSE -> if (phase < 0.5) 1.0 else -1.0
                Shape.SAW -> 2 * phase - 1
                Shape.NOISE -> random.nextDouble(-1.0, 1.0)
            }
            (value * 24000).roundToInt()
        }
        return SampleDraft(shape.name.lowercase().replaceFirstChar(Char::uppercase), pcm,
            loopStart = if (shape == Shape.NOISE) null else 0, generator = shape.name, periodFrames = period)
    }

    fun validate(draft: SampleDraft) {
        require(draft.version == 1) { "Unsupported sample draft version" }
        require(draft.name.isNotBlank() && draft.name.length <= 120) { "Name must contain 1–120 characters" }
        require(draft.pcm.size in 16..MAX_FRAMES && draft.pcm.size % 16 == 0) { "Sample must contain 16–32768 frames in 16-frame blocks" }
        require(draft.pcm.all { it in Short.MIN_VALUE..Short.MAX_VALUE }) { "Sample contains invalid 16-bit PCM" }
        require(draft.trimStart >= 0 && draft.trimEnd <= draft.pcm.size && draft.trimStart < draft.trimEnd &&
            draft.trimStart % 16 == 0 && draft.trimEnd % 16 == 0) { "Trim must select complete 16-frame blocks" }
        require(draft.loopStart == null || draft.loopStart in draft.trimStart until draft.trimEnd && draft.loopStart % 16 == 0) {
            "Loop start must be a 16-frame boundary inside the trim"
        }
        require(draft.gainPercent in 0..100) { "Level must be 0–100%" }
        require(draft.generator == null || Shape.entries.any { it.name == draft.generator }) { "Unknown waveform generator" }
        require(draft.periodFrames in 32..512 && draft.periodFrames % 16 == 0) { "Invalid waveform period" }
    }

    fun encode(draft: SampleDraft): Encoded {
        validate(draft)
        // The existing BRR codec works in the DSP's signed 15-bit predictor domain.
        // Convert 16-bit WAV/generator PCM before encoding, then restore the scale
        // for the display decode. Feeding full-scale PCM here wraps/clips harmonics.
        val pcm = ShortArray(draft.trimEnd - draft.trimStart) { (draft.pcm[draft.trimStart + it] * draft.gainPercent / 200).toShort() }
        val loopFrame = draft.loopStart?.minus(draft.trimStart)
        val loopBlock = loopFrame?.div(16) ?: -1
        val brr = SpcData.encodeBrr(pcm, loopBlock, setOf(0, loopBlock))
        val decoded = SpcData.decodeBrr(brr, 0, pcm.size).map { (it.toInt() * 2).toShort() }.toShortArray()
        return Encoded(brr, decoded, loopFrame)
    }

    /** Bounded conversion; long files are refused, never silently shortened to the memory limit. */
    fun readWav(file: File): SampleDraft {
        require(file.length() <= 16 * 1024 * 1024) { "WAV is too large; trim it before importing" }
        val (pcm, rate) = AudioSystem.getAudioInputStream(file).use { source ->
            val rate = source.format.sampleRate
            require(rate.isFinite() && rate in 1000f..192000f) { "Unsupported WAV sample rate" }
            val format = AudioFormat(rate, 16, 1, true, false)
            AudioSystem.getAudioInputStream(format, source).use { converted ->
                val maxSourceFrames = (MAX_FRAMES.toDouble() * rate / RATE).toInt()
                val bytes = converted.readNBytes(maxSourceFrames * 2 + 2)
                require(bytes.size <= maxSourceFrames * 2) { "WAV exceeds 1.024 seconds; trim it before importing" }
                require(bytes.isNotEmpty() && bytes.size % 2 == 0) { "WAV contains no complete PCM frames" }
                ShortArray(bytes.size / 2) { ((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() } to rate.toInt()
            }
        }
        val resampled = SpcData.resamplePcm(pcm, rate, RATE)
        require(resampled.isNotEmpty() && resampled.size <= MAX_FRAMES) { "WAV does not fit the sample workbench" }
        val padded = resampled.copyOf((resampled.size + 15) / 16 * 16)
        return SampleDraft(file.nameWithoutExtension.take(120).ifBlank { "Imported sample" }, padded.map(Short::toInt))
    }

    private fun writeAsset(file: File, bytes: ByteArray) {
        val path = file.toPath().toAbsolutePath()
        val temporary = Files.createTempFile(path.parent, "smedit-sample-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun save(draft: SampleDraft, file: File) { validate(draft); writeAsset(file, json.encodeToString(draft).encodeToByteArray()) }
    fun exportBrr(sample: Encoded, file: File) = writeAsset(file, sample.brr)
    fun open(file: File): SampleDraft {
        require(file.length() <= 1024 * 1024) { "Sample draft is too large" }
        return json.decodeFromString<SampleDraft>(file.readText()).also(::validate)
    }

    /** Disposable audition RAM. Replacing DIR/BRR here grants no permission to patch a bank. */
    fun preview(ram: ByteArray, sample: Encoded): InstrumentAudition.Preview {
        require(ram.size == 65536 && sample.brr.size <= 0x8000)
        val privateRam = ram.copyOf()
        sample.brr.copyInto(privateRam, 0x8000)
        val loop = 0x8000 + (sample.loopByteOffset ?: 0)
        byteArrayOf(0, 0x80.toByte(), loop.toByte(), (loop ushr 8).toByte()).copyInto(privateRam, 0x6D00)
        val c6 = (ram[0x1E66].toInt() and 255) or ((ram[0x1E67].toInt() and 255) shl 8)
        require(c6 > 0) { "Native pitch table is unavailable" }
        val c4 = (c6 * 2) shr 3
        require(c4 > 0) { "Native pitch table cannot support recorded-rate preview" }
        val tuning = (4096.0 * 256 / c4).roundToInt()
        require(tuning in 1..65535) { "Native tuning cannot support recorded-rate preview" }
        val packedTuning = ((tuning and 255) shl 8) or (tuning ushr 8)
        val instrument = NspcRenderer.InstrumentEntry(0, 0, 0, 0x7F, packedTuning, 24, 0x6C90)
        return InstrumentAudition.build(privateRam, instrument, NspcSequence.Note(0, 120, 0xA4, instrument = 24))
    }
}
