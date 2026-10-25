package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer.InstrumentEntry
import com.supermetroid.editor.rom.SpcData
import java.security.MessageDigest
import kotlin.math.abs

/** A loaded recording (or DSP noise source), not an instrument or sound-effect program. */
data class MusicSample(
    val srcn: Int,
    val resourceId: String,
    val frames: Int,
    val brrBytes: Int,
    val loopFrame: Int?,
    val waveform: List<MusicSamplePeak>,
    val origin: String,
    val importedFrom: List<String> = emptyList(),
) {
    val isNoise get() = srcn >= 0x80
    val title get() = when {
        isNoise -> "Noise rate ${srcn and 31}"
        loopFrame != null -> "Looped recording"
        frames < 6400 -> "Short one-shot"
        else -> "One-shot recording"
    }
}

data class MusicSamplePeak(val low: Float, val high: Float)

/** Reads the engine's actual $6D00 directory, never a heuristic scan of arbitrary RAM. */
internal object MusicSampleCatalog {
    private data class Brr(val start: Int, val end: Int, val loops: Boolean)

    fun read(ram: ByteArray, bankBlocks: List<SpcData.TransferBlock> = emptyList(),
        imports: List<Pair<String, List<SpcData.TransferBlock>>> = emptyList()): List<MusicSample> {
        require(ram.size == 65536)
        fun word(at: Int) = (ram[at].toInt() and 255) or ((ram[at + 1].toInt() and 255) shl 8)
        fun brr(start: Int): Brr? {
            if (start < 0x5800) return null // Never offer engine bytes as a recording.
            var at = start
            repeat(4096) {
                if (at + 9 > ram.size || (at < 0x6DA0 && at + 9 > 0x6C00)) return null
                val header = ram[at].toInt() and 255
                at += 9
                if (header and 1 != 0) return Brr(start, at, header and 2 != 0)
            }
            return null
        }
        fun touches(block: SpcData.TransferBlock, start: Int, end: Int) = block.destAddr < end && block.destAddr + block.data.size > start
        return buildList {
            for (srcn in 0 until 40) {
                val dir = 0x6D00 + srcn * 4
                val data = brr(word(dir)) ?: continue
                val loopAddr = word(dir + 2)
                val inlineLoop = data.loops && loopAddr in data.start until data.end && (loopAddr - data.start) % 9 == 0
                val linked = if (data.loops && !inlineLoop) brr(loopAddr) ?: continue else null
                val introBytes = data.end - data.start
                val bytes = ram.copyOfRange(data.start, data.end) + (linked?.let { ram.copyOfRange(it.start, it.end) } ?: byteArrayOf())
                val loopFrame = when {
                    inlineLoop -> (loopAddr - data.start) / 9 * 16
                    linked?.loops == true -> introBytes / 9 * 16
                    else -> null // A linked tail with END but no LOOP plays once, then stops.
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val loopOffset = loopFrame ?: -1
                repeat(4) { digest.update((loopOffset ushr (it * 8)).toByte()) }
                val resource = "brr:" + digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                fun affects(block: SpcData.TransferBlock) = touches(block, dir, dir + 4) || touches(block, data.start, data.end) ||
                    (linked != null && touches(block, linked.start, linked.end))
                val imported = imports.filter { (_, blocks) -> blocks.any(::affects) }.map { it.first }.distinct()
                val origin = when {
                    imported.isNotEmpty() -> "Import"
                    bankBlocks.any(::affects) -> "Bank ROM"
                    else -> "Shared ROM"
                }
                // Assemble only the display decode. Removing the intro END marker lets the
                // existing decoder carry predictor history into a linked tail/loop correctly.
                // Native audition always uses the untouched bank and real DIR pointers.
                val decode = bytes.copyOf()
                if (linked != null) decode[introBytes - 9] = (decode[introBytes - 9].toInt() and 0xFE).toByte()
                val pcm = SpcData.decodeBrrWithLoop(decode, SpcData.SampleDirEntry(srcn, 0, 0), bytes.size / 9).first
                val peak = pcm.maxOfOrNull { abs(it.toInt()) }?.coerceAtLeast(1) ?: 1
                val waveform = (0 until minOf(64, pcm.size)).map { bin ->
                    val bins = minOf(64, pcm.size)
                    val segment = pcm.sliceArray(bin * pcm.size / bins until (bin + 1) * pcm.size / bins)
                    MusicSamplePeak(segment.min().toFloat() / peak, segment.max().toFloat() / peak)
                }
                add(MusicSample(srcn, resource, pcm.size, bytes.size, loopFrame, waveform, origin, imported))
            }
            for (rate in 0..31) add(MusicSample(0x80 + rate, "noise:$rate", 0, 0, null, emptyList(), "Generated"))
        }
    }

    fun apply(entry: InstrumentEntry, sample: MusicSample) = entry.copy(srcn = sample.srcn)

    fun nameKey(songSet: Int, resourceId: String): String {
        require(songSet >= 0 && songSet % 3 == 0)
        require(Regex("brr:[0-9a-f]{64}|noise:([0-9]|[12][0-9]|3[01])").matches(resourceId))
        return "${MusicInstrumentBanks.key(songSet)}:$resourceId"
    }
}
