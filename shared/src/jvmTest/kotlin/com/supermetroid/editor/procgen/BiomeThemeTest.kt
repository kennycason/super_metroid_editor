package com.supermetroid.editor.procgen

import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.TileGraphics
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BiomeThemeTest {

    @Test
    fun `concrete themes use distinct valid tilesets`() {
        val ids = BiomeTheme.CONCRETE.map { it.tilesetId }
        assertTrue(ids.all { it != null && it in 0 until TileGraphics.NUM_TILESETS })
        assertEquals(ids.size, ids.toSet().size, "each theme must own its tileset so recolors never collide")
    }

    @Test
    fun `palette effect ids resolve`() {
        for (t in BiomeTheme.CONCRETE) {
            t.paletteEffectId?.let { assertNotNull(PaletteEffects.findEffect(it), "unknown effect $it on ${t.id}") }
        }
        for (id in BiomeTheme.SURPRISE_RECOLORS) {
            assertNotNull(PaletteEffects.findEffect(id), "unknown surprise recolor $id")
        }
    }

    @Test
    fun `fx types are known and liquid fractions sane`() {
        val known = setOf(BiomeTheme.FX_LAVA, BiomeTheme.FX_ACID, BiomeTheme.FX_WATER, BiomeTheme.FX_SPORES)
        for (t in BiomeTheme.CONCRETE) {
            t.fxType?.let { assertTrue(it in known, "unexpected fxType on ${t.id}") }
            assertTrue(t.liquidFraction in 0.0..1.0)
        }
    }

    @Test
    fun `surprise resolves deterministically to a concrete theme`() {
        val a = BiomeTheme.SURPRISE.resolve(1234)
        val b = BiomeTheme.SURPRISE.resolve(1234)
        assertEquals(a, b)
        assertNotNull(a.tilesetId)
        assertTrue(BiomeTheme.CONCRETE.any { it.tilesetId == a.tilesetId })
        // Non-surprise themes resolve to themselves.
        for (t in BiomeTheme.THEMES.filter { it != BiomeTheme.SURPRISE }) {
            assertEquals(t, t.resolve(999))
        }
        // Different seeds eventually hit different themes.
        val distinct = (0L until 40L).map { BiomeTheme.SURPRISE.resolve(it).tilesetId }.toSet()
        assertTrue(distinct.size > 3, "surprise should vary across seeds")
    }
}
