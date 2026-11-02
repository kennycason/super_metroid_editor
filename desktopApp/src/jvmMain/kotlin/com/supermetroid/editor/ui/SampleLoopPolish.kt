package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.SpcData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/** Reversible seam blending and bounded inspection of the actual encoded BRR stream. */
internal object SampleLoopPolish {
    const val MAX_CROSSFADE = 256

    fun maxCrossfade(draft: SampleDraft): Int = draft.loopStart?.let {
        minOf(MAX_CROSSFADE, (draft.trimEnd - it).coerceAtLeast(0) / 32 * 16)
    } ?: 0

    fun crossfadeFrames(draft: SampleDraft) = draft.crossfadeFrames.coerceAtMost(maxCrossfade(draft))

    /** Blend both sides toward a shared seam; never shorten the loop or change its pitch. */
    fun pcm(draft: SampleDraft): ShortArray {
        SampleAuthoring.validate(draft)
        val result = ShortArray(draft.trimEnd - draft.trimStart) { draft.pcm[draft.trimStart + it].toShort() }
        val frames = crossfadeFrames(draft)
        val start = (draft.loopStart ?: draft.trimStart) - draft.trimStart
        for (distance in 0 until frames) {
            val tail = result.lastIndex - distance
            val head = start + distance
            val a = result[tail].toInt(); val b = result[head].toInt()
            // Raised-cosine taper: 50/50 at the join, unchanged at the outer edges.
            val mix = (1 + cos(PI * distance / (frames - 1))) / 4
            result[tail] = (a * (1 - mix) + b * mix).roundToInt().toShort()
            result[head] = (b * (1 - mix) + a * mix).roundToInt().toShort()
        }
        return result
    }

    data class Join(val pcm: ShortArray, val jump: Int)
    data class Inspection(val joins: List<Join>, val maxRepeatDifference: Int)

    /** Concatenation retains predictor history at each native END+LOOP transition. */
    fun inspect(sample: SampleAuthoring.Encoded): Inspection? {
        val loop = sample.loopFrame ?: return null
        require(sample.pcm.size <= SampleAuthoring.MAX_FRAMES) { "Loop inspection exceeds the sample workbench limit" }
        MusicSamplePlacement.validate(sample)
        val at = requireNotNull(sample.loopByteOffset)
        val loopBytes = sample.brr.size - at
        val loopFrames = sample.pcm.size - loop
        val frames = sample.pcm.size + loopFrames * 3
        val brr = ByteArray(sample.brr.size + loopBytes * 3)
        sample.brr.copyInto(brr)
        brr[sample.brr.size - 9] = (brr[sample.brr.size - 9].toInt() and 0xFE).toByte()
        repeat(3) { pass ->
            val start = sample.brr.size + loopBytes * pass
            sample.brr.copyInto(brr, start, at)
            if (pass < 2) brr[start + loopBytes - 9] = (brr[start + loopBytes - 9].toInt() and 0xFE).toByte()
        }
        val decoded = SpcData.decodeBrr(brr, 0, frames).map { (it.toInt() * 2).toShort() }.toShortArray()
        check(decoded.size == frames) { "Incomplete encoded loop inspection" }
        val window = minOf(32, loopFrames)
        val joins = (0 until 3).map { pass ->
            val seam = sample.pcm.size + loopFrames * pass
            Join(decoded.copyOfRange(seam - window, seam + window), abs(decoded[seam - 1].toInt() - decoded[seam].toInt()))
        }
        var difference = 0
        for (pass in 1..3) for (frame in 0 until loopFrames) {
            difference = maxOf(difference, abs(decoded[loop + frame].toInt() - decoded[loop + loopFrames * pass + frame].toInt()))
        }
        return Inspection(joins, difference)
    }
}
