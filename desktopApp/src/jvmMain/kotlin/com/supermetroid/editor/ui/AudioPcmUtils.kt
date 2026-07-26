package com.supermetroid.editor.ui

import kotlin.math.abs
import kotlin.math.sqrt

private const val PREVIEW_TARGET_PEAK = 32000.0
private const val PREVIEW_TARGET_RMS = 9500.0
private const val PREVIEW_MAX_GAIN = 5.0

internal fun resampleLinear(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray {
    if (fromRate == toRate || pcm.isEmpty()) return pcm
    val ratio = fromRate.toDouble() / toRate
    val outLen = (pcm.size / ratio).toInt()
    val out = ShortArray(outLen)
    for (i in 0 until outLen) {
        val srcPos = i * ratio
        val idx = srcPos.toInt()
        val frac = srcPos - idx
        val s = if (idx + 1 < pcm.size) {
            (pcm[idx] * (1.0 - frac) + pcm[idx + 1] * frac).toInt()
        } else if (idx < pcm.size) {
            pcm[idx].toInt()
        } else break
        out[i] = s.coerceIn(-32768, 32767).toShort()
    }
    return out
}

internal fun extendWithLoop(
    pcm: ShortArray,
    loopStart: Int,
    sampleRate: Int,
    targetSeconds: Double
): ShortArray {
    val targetLen = (sampleRate * targetSeconds).toInt()
    if (pcm.size >= targetLen) return pcm

    if (loopStart in 0 until pcm.size) {
        val attack = pcm.copyOfRange(0, loopStart)
        val loopRegion = pcm.copyOfRange(loopStart, pcm.size)
        if (loopRegion.isEmpty()) return pcm

        val out = ShortArray(targetLen)
        attack.copyInto(out)
        var pos = attack.size
        while (pos < targetLen) {
            val remaining = targetLen - pos
            val copyLen = minOf(loopRegion.size, remaining)
            loopRegion.copyInto(out, pos, 0, copyLen)
            pos += copyLen
        }
        val fadeLen = minOf(out.size, (sampleRate * 0.3).toInt())
        for (i in 0 until fadeLen) {
            val idx = out.size - fadeLen + i
            out[idx] = (out[idx] * (fadeLen - i) / fadeLen).toShort()
        }
        return out
    }

    val fadeLen = minOf(pcm.size, (sampleRate * 0.2).toInt())
    val out = pcm.copyOf()
    for (i in 0 until fadeLen) {
        val idx = out.size - fadeLen + i
        out[idx] = (out[idx] * (fadeLen - i) / fadeLen).toShort()
    }
    return out
}

internal fun resamplePitch(
    pcm: ShortArray,
    loopStart: Int,
    pitchFactor: Double,
    sampleRate: Int,
    targetSeconds: Double
): ShortArray {
    if (pcm.isEmpty()) return pcm
    val extended = extendWithLoop(pcm, loopStart, sampleRate, targetSeconds * pitchFactor + 0.1)
    val outLen = (extended.size / pitchFactor).toInt().coerceAtMost((sampleRate * targetSeconds).toInt())
    val out = ShortArray(outLen)
    for (i in 0 until outLen) {
        val srcPos = i * pitchFactor
        val idx = srcPos.toInt()
        if (idx + 1 >= extended.size) break
        val frac = (srcPos - idx).toFloat()
        val v = (extended[idx] * (1f - frac) + extended[idx + 1] * frac).toInt()
        out[i] = v.coerceIn(-32768, 32767).toShort()
    }
    return out
}

/** Fade the last 5 seconds then trim trailing silence. */
internal fun trimNativeTrackPreview(mono: ShortArray, nativeSr: Int): ShortArray {
    var result = mono
    val fadeSamples = nativeSr * 5
    if (result.size > fadeSamples) {
        val fadeStart = result.size - fadeSamples
        for (i in 0 until fadeSamples) {
            val gain = 1.0f - (i.toFloat() / fadeSamples)
            result[fadeStart + i] = (result[fadeStart + i] * gain).toInt().toShort()
        }
    }
    val silenceThreshold = 64
    val silenceWindow = nativeSr / 2
    var trimEnd = result.size
    while (trimEnd > silenceWindow) {
        val windowStart = trimEnd - silenceWindow
        val windowPeak = (windowStart until trimEnd).maxOf { abs(result[it].toInt()) }
        if (windowPeak > silenceThreshold) break
        trimEnd = windowStart
    }
    if (trimEnd < result.size) result = result.copyOf(trimEnd)
    return result
}

internal fun normalizePreviewPcm(pcm: ShortArray, extraGain: Double = 1.0): ShortArray {
    if (pcm.isEmpty()) return pcm
    val peak = pcm.maxOf { abs(it.toInt()) }
    if (peak < 64) return pcm

    var sumSquares = 0.0
    for (sample in pcm) {
        val value = sample.toDouble()
        sumSquares += value * value
    }
    val rms = sqrt(sumSquares / pcm.size).coerceAtLeast(1.0)

    val peakLimitedGain = PREVIEW_TARGET_PEAK / peak
    val rmsGain = PREVIEW_TARGET_RMS / rms
    val baseGain = minOf(PREVIEW_MAX_GAIN, peakLimitedGain, rmsGain).coerceAtLeast(1.0)
    val gain = (baseGain * extraGain).coerceAtMost(PREVIEW_MAX_GAIN * extraGain)
    if (gain <= 1.02) return pcm
    return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
}
