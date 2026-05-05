package com.supermetroid.editor.rom

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Palette color effects engine for Super Metroid.
 * Operates on arrays of BGR555 colors (SNES native format: 0BBBBBGGGGGRRRRR).
 *
 * Ported from super_metroid_colors effects.ts — each effect transforms colors
 * in-place, skipping transparent (0x0000) entries.
 */
object PaletteEffects {

    // ─── BGR555 ↔ RGB (5-bit channels) ─────────────────────────────

    fun bgr555ToRgb5(color: Int): Triple<Int, Int, Int> {
        val r = color and 0x1F
        val g = (color shr 5) and 0x1F
        val b = (color shr 10) and 0x1F
        return Triple(r, g, b)
    }

    fun rgb5ToBgr555(r: Int, g: Int, b: Int): Int {
        return (r.coerceIn(0, 31)) or
                (g.coerceIn(0, 31) shl 5) or
                (b.coerceIn(0, 31) shl 10)
    }

    // ─── RGB (0-31) ↔ HSV ──────────────────────────────────────────

    data class HSV(var h: Float, var s: Float, var v: Float)

    fun rgbToHsv(r: Int, g: Int, b: Int): HSV {
        val rf = r / 31f; val gf = g / 31f; val bf = b / 31f
        val mx = maxOf(rf, gf, bf)
        val mn = minOf(rf, gf, bf)
        val delta = mx - mn

        var h = 0f
        if (delta != 0f) {
            h = when (mx) {
                rf -> 60f * (((gf - bf) / delta) % 6f)
                gf -> 60f * ((bf - rf) / delta + 2f)
                else -> 60f * ((rf - gf) / delta + 4f)
            }
        }
        if (h < 0f) h += 360f

        val s = if (mx == 0f) 0f else delta / mx
        return HSV(h, s, mx)
    }

    fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c

        val (rr, gg, bb) = when {
            h < 60f  -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else     -> Triple(c, 0f, x)
        }
        return Triple(
            round((rr + m) * 31f).toInt().coerceIn(0, 31),
            round((gg + m) * 31f).toInt().coerceIn(0, 31),
            round((bb + m) * 31f).toInt().coerceIn(0, 31)
        )
    }

    // ─── Core transform helper ─────────────────────────────────────

    private inline fun transformColors(
        colors: IntArray,
        fn: (r: Int, g: Int, b: Int, index: Int) -> Triple<Int, Int, Int>
    ) {
        for (i in colors.indices) {
            if (colors[i] == 0) continue // skip transparent
            val (r, g, b) = bgr555ToRgb5(colors[i])
            val (nr, ng, nb) = fn(r, g, b, i)
            colors[i] = rgb5ToBgr555(nr, ng, nb)
        }
    }

    private fun luminance(r: Int, g: Int, b: Int): Int =
        ((r * 77 + g * 150 + b * 29) / 256).coerceIn(0, 31)

    // ─── Effect definitions ────────────────────────────────────────

    fun grayscale(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val l = luminance(r, g, b)
        Triple(l, l, l)
    }

    fun sepia(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val l = luminance(r, g, b)
        Triple(min(31, round(l * 31f / 24f).toInt()), min(31, round(l * 22f / 24f).toInt()), min(31, round(l * 16f / 24f).toInt()))
    }

    fun invert(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(31 - r, 31 - g, 31 - b)
    }

    fun dark(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(r / 2, g / 2, b / 2)
    }

    fun tintRed(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(min(r + 12, 31), g / 2, b / 2)
    }

    fun tintBlue(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(r / 2, g / 2, min(b + 12, 31))
    }

    fun tintGreen(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(r / 2, min(g + 12, 31), b / 2)
    }

    fun neon(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(if (r > 15) 31 else 0, if (g > 15) 31 else 0, if (b > 15) 31 else 0)
    }

    fun pastel(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(min(31, r / 2 + 12), min(31, g / 2 + 12), min(31, b / 2 + 12))
    }

    fun vaporwave(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(min(r + 8, 31), (g * 2 / 3), min(b + 10, 31))
    }

    fun gameboy(colors: IntArray) {
        val shades = arrayOf(Triple(1, 4, 1), Triple(5, 12, 5), Triple(12, 22, 10), Triple(20, 30, 16))
        transformColors(colors) { r, g, b, _ ->
            val l = luminance(r, g, b)
            shades[when { l < 8 -> 0; l < 16 -> 1; l < 24 -> 2; else -> 3 }]
        }
    }

    fun neonPink(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val l = luminance(r, g, b)
        Triple(min(31, l + 12), l / 3, min(31, l + 8))
    }

    fun iceCold(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(min(31, r / 3 + 8), min(31, g / 2 + 12), min(31, b + 6))
    }

    fun lava(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val l = luminance(r, g, b)
        Triple(min(31, l + 10), min(31, l / 2), l / 4)
    }

    fun midnight(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(r / 4, g / 4, min(31, b / 2 + 4))
    }

    fun golden(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val l = luminance(r, g, b)
        Triple(min(31, l + 8), min(31, round(l * 0.85f).toInt()), l / 4)
    }

    fun underwater(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(r / 3, min(31, round(g * 0.7f).toInt() + 6), min(31, round(b * 0.8f).toInt() + 10))
    }

    fun hueShift(colors: IntArray, degrees: Float) = transformColors(colors) { r, g, b, _ ->
        val hsv = rgbToHsv(r, g, b)
        hsv.h = (hsv.h + degrees) % 360f
        if (hsv.h < 0f) hsv.h += 360f
        hsvToRgb(hsv.h, hsv.s, hsv.v)
    }

    fun saturate(colors: IntArray, amount: Float) = transformColors(colors) { r, g, b, _ ->
        val hsv = rgbToHsv(r, g, b)
        hsv.s = (hsv.s * amount).coerceIn(0f, 1f)
        hsvToRgb(hsv.h, hsv.s, hsv.v)
    }

    fun psychedelic(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val hsv = rgbToHsv(r, g, b)
        hsv.h = (hsv.h * 3f) % 360f
        hsv.s = min(1f, hsv.s * 1.5f)
        hsvToRgb(hsv.h, hsv.s, hsv.v)
    }

    fun rainbow(colors: IntArray) = transformColors(colors) { r, g, b, i ->
        val hsv = rgbToHsv(r, g, b)
        hsv.h = (i * 360f / max(colors.size, 1).toFloat()) % 360f
        hsv.s = max(0.7f, hsv.s)
        hsv.v = max(0.4f, hsv.v)
        hsvToRgb(hsv.h, hsv.s, hsv.v)
    }

    fun cyberpunk(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val l = luminance(r, g, b)
        when {
            l > 20 -> Triple(31, l / 4, min(31, l + 6))
            l > 10 -> Triple(min(31, l + 8), 0, min(31, l + 14))
            else   -> Triple(l / 2, min(31, l + 6), min(31, l + 10))
        }
    }

    fun complementary(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val hsv = rgbToHsv(r, g, b)
        hsv.h = (hsv.h + 180f) % 360f
        hsvToRgb(hsv.h, hsv.s, hsv.v)
    }

    fun triadic(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val hsv = rgbToHsv(r, g, b)
        hsv.h = (hsv.h + 120f) % 360f
        hsvToRgb(hsv.h, hsv.s, hsv.v)
    }

    fun acid(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val hsv = rgbToHsv(r, g, b)
        hsv.h = (hsv.h * 5f + 60f) % 360f
        hsv.s = 1f
        hsv.v = max(0.5f, hsv.v)
        hsvToRgb(hsv.h, hsv.s, hsv.v)
    }

    fun thermal(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        val l = luminance(r, g, b)
        val t = l / 31f
        when {
            t < 0.25f -> Triple(0, 0, round(t * 4f * 31f).toInt())
            t < 0.5f  -> Triple(round((t - 0.25f) * 4f * 31f).toInt(), 0, round((0.5f - t) * 4f * 31f).toInt())
            t < 0.75f -> Triple(31, round((t - 0.5f) * 4f * 31f).toInt(), 0)
            else      -> Triple(31, 31, round((t - 0.75f) * 4f * 31f).toInt())
        }
    }

    fun hologram(colors: IntArray) = transformColors(colors) { r, g, b, i ->
        val l = luminance(r, g, b)
        val phase = (i * 40f) % 360f
        hsvToRgb(phase, 0.6f, max(0.3f, l / 31f))
    }

    fun randomize(colors: IntArray) = transformColors(colors) { _, _, _, _ ->
        Triple((0..31).random(), (0..31).random(), (0..31).random())
    }

    /** Small random mutations: nudge each channel by +-5, preserving overall feel */
    fun mutate(colors: IntArray) = transformColors(colors) { r, g, b, _ ->
        Triple(
            (r + (-5..5).random()).coerceIn(0, 31),
            (g + (-5..5).random()).coerceIn(0, 31),
            (b + (-5..5).random()).coerceIn(0, 31)
        )
    }

    /** Pick a random effect from the registry and apply it */
    fun randomEffect(colors: IntArray) {
        val effect = EFFECTS.random()
        effect.apply(colors)
    }

    // ─── Effect registry ───────────────────────────────────────────

    data class EffectDef(val id: String, val name: String, val apply: (IntArray) -> Unit)

    val EFFECTS: List<EffectDef> = listOf(
        EffectDef("grayscale", "Grayscale") { grayscale(it) },
        EffectDef("sepia", "Sepia") { sepia(it) },
        EffectDef("invert", "Invert") { invert(it) },
        EffectDef("dark", "Dark") { dark(it) },
        EffectDef("gameboy", "Game Boy") { gameboy(it) },
        EffectDef("red", "Red Tint") { tintRed(it) },
        EffectDef("blue", "Blue Tint") { tintBlue(it) },
        EffectDef("green", "Green Tint") { tintGreen(it) },
        EffectDef("golden", "Golden") { golden(it) },
        EffectDef("icecold", "Ice Cold") { iceCold(it) },
        EffectDef("lava", "Lava") { lava(it) },
        EffectDef("underwater", "Underwater") { underwater(it) },
        EffectDef("midnight", "Midnight") { midnight(it) },
        EffectDef("neon", "Neon") { neon(it) },
        EffectDef("neonpink", "Neon Pink") { neonPink(it) },
        EffectDef("psychedelic", "Psychedelic") { psychedelic(it) },
        EffectDef("randomize", "Random Chaos") { randomize(it) },
        EffectDef("vaporwave", "Vaporwave") { vaporwave(it) },
        EffectDef("pastel", "Pastel") { pastel(it) },
        EffectDef("hue90", "Hue +90") { hueShift(it, 90f) },
        EffectDef("hue180", "Hue +180") { hueShift(it, 180f) },
        EffectDef("hue270", "Hue +270") { hueShift(it, 270f) },
        EffectDef("saturated", "Hypersaturated") { saturate(it, 2.0f) },
        EffectDef("desaturated", "Desaturated") { saturate(it, 0.3f) },
        EffectDef("rainbow", "Rainbow") { rainbow(it) },
        EffectDef("cyberpunk", "Cyberpunk") { cyberpunk(it) },
        EffectDef("complementary", "Complementary") { complementary(it) },
        EffectDef("triadic", "Triadic") { triadic(it) },
        EffectDef("acid", "Acid Trip") { acid(it) },
        EffectDef("thermal", "Thermal") { thermal(it) },
        EffectDef("hologram", "Hologram") { hologram(it) },
    )

    fun findEffect(id: String): EffectDef? = EFFECTS.find { it.id == id }
}
