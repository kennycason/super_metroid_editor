package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import kotlin.random.Random

class PaletteEffectsTest {

    @Test
    fun `bgr555 round-trip conversion`() {
        for (r in 0..31 step 7) for (g in 0..31 step 7) for (b in 0..31 step 7) {
            val bgr = PaletteEffects.rgb5ToBgr555(r, g, b)
            val (r2, g2, b2) = PaletteEffects.bgr555ToRgb5(bgr)
            assertEquals(r, r2, "Red mismatch for ($r,$g,$b)")
            assertEquals(g, g2, "Green mismatch for ($r,$g,$b)")
            assertEquals(b, b2, "Blue mismatch for ($r,$g,$b)")
        }
    }

    @Test
    fun `hsv round-trip conversion`() {
        // Pure red
        val (r, g, b) = PaletteEffects.hsvToRgb(0f, 1f, 1f)
        assertEquals(31, r)
        assertEquals(0, g)
        assertEquals(0, b)

        // Pure green
        val (r2, g2, b2) = PaletteEffects.hsvToRgb(120f, 1f, 1f)
        assertEquals(0, r2)
        assertEquals(31, g2)
        assertEquals(0, b2)
    }

    @Test
    fun `grayscale converts colors to luminance`() {
        val colors = intArrayOf(
            PaletteEffects.rgb5ToBgr555(31, 0, 0),  // pure red
            PaletteEffects.rgb5ToBgr555(0, 31, 0),   // pure green
            PaletteEffects.rgb5ToBgr555(0, 0, 31),   // pure blue
        )
        PaletteEffects.grayscale(colors)
        for (c in colors) {
            val (r, g, b) = PaletteEffects.bgr555ToRgb5(c)
            assertEquals(r, g, "Grayscale R should equal G")
            assertEquals(g, b, "Grayscale G should equal B")
        }
    }

    @Test
    fun `transparent colors are preserved`() {
        val colors = intArrayOf(0x0000, PaletteEffects.rgb5ToBgr555(15, 15, 15))
        PaletteEffects.invert(colors)
        assertEquals(0, colors[0], "Transparent should remain 0")
        assertNotEquals(0, colors[1], "Non-transparent should be transformed")
    }

    @Test
    fun `invert produces complement`() {
        val colors = intArrayOf(PaletteEffects.rgb5ToBgr555(10, 20, 5))
        PaletteEffects.invert(colors)
        val (r, g, b) = PaletteEffects.bgr555ToRgb5(colors[0])
        assertEquals(21, r)
        assertEquals(11, g)
        assertEquals(26, b)
    }

    @Test
    fun `hue shift 180 is same as complementary`() {
        val original = intArrayOf(
            PaletteEffects.rgb5ToBgr555(20, 5, 10),
            PaletteEffects.rgb5ToBgr555(8, 25, 3),
        )
        val a = original.copyOf()
        val b = original.copyOf()
        PaletteEffects.hueShift(a, 180f)
        PaletteEffects.complementary(b)
        for (i in a.indices) {
            assertEquals(a[i], b[i], "Hue+180 should match complementary at index $i")
        }
    }

    @Test
    fun `effect registry has all effects`() {
        assert(PaletteEffects.EFFECTS.size >= 30) { "Expected at least 30 effects, got ${PaletteEffects.EFFECTS.size}" }
        val ids = PaletteEffects.EFFECTS.map { it.id }.toSet()
        assert("grayscale" in ids)
        assert("sepia" in ids)
        assert("invert" in ids)
        assert("cyberpunk" in ids)
        assert("thermal" in ids)
        assert("psychedelic-randomize" in ids)
        assert("mathematical-randomize" in ids)
        assert("ghost25" in ids)
    }

    @Test
    fun `all effects run without exception`() {
        val colors = IntArray(16) { PaletteEffects.rgb5ToBgr555(it * 2, it, 31 - it) }
        for (effect in PaletteEffects.EFFECTS) {
            val copy = colors.copyOf()
            effect.apply(copy) // should not throw
        }
    }

    @Test
    fun `psychedelic and mathematical randomize preserve transparent entries`() {
        val original = IntArray(32) { i ->
            if (i == 0 || i == 16) 0
            else PaletteEffects.rgb5ToBgr555((i * 3) % 32, (i * 5) % 32, (i * 7) % 32)
        }

        val psychedelic = original.copyOf()
        PaletteEffects.psychedelicRandomize(psychedelic)
        assertEquals(0, psychedelic[0])
        assertEquals(0, psychedelic[16])

        val mathematical = original.copyOf()
        PaletteEffects.mathematicalRandomize(mathematical)
        assertEquals(0, mathematical[0])
        assertEquals(0, mathematical[16])
    }

    @Test
    fun `seeded random effects are reproducible`() {
        val original = IntArray(32) { i ->
            if (i == 0 || i == 16) 0
            else PaletteEffects.rgb5ToBgr555((i * 3) % 32, (i * 5) % 32, (i * 7) % 32)
        }
        val effect = PaletteEffects.findEffect("psychedelic-randomize")!!

        val first = original.copyOf()
        val second = original.copyOf()
        val third = original.copyOf()
        PaletteEffects.applyEffect(effect, first, Random(12345L))
        PaletteEffects.applyEffect(effect, second, Random(12345L))
        PaletteEffects.applyEffect(effect, third, Random(54321L))

        assertArrayEquals(first, second)
        assertNotEquals(first.toList(), third.toList())
    }
}
