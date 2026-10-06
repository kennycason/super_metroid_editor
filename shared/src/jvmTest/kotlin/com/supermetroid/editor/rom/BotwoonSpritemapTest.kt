package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotwoonSpritemapTest {

    private fun load(): BotwoonSpritemap? {
        val parser = TestRomHelper.loadRomParser() ?: return null
        val tiles = EnemySpriteGraphics.loadEnemyTileData(parser, BotwoonSpritemap.SPECIES_ID) ?: return null
        return BotwoonSpritemap(parser).also { assertTrue(it.load(tiles)) }
    }

    @Test
    fun `runtime health palette zero matches the enemy header palette`() {
        val renderer = load() ?: return

        assertContentEquals(renderer.readHeaderPalette(), renderer.readPalette())
        assertEquals(8, BotwoonSpritemap.PALETTE_STAGES.size)
        assertEquals(8, BotwoonSpritemap.PALETTE_STAGES.mapNotNull(renderer::readPalette).distinctBy(IntArray::contentHashCode).size)
    }

    @Test
    fun `every speed stage samples the history at twelve pixel segment intervals`() {
        assertEquals(listOf(6, 4, 3), BotwoonSpritemap.SPEED_STAGES.map { it.historyFrames })
        assertEquals(listOf(12, 12, 12), BotwoonSpritemap.SPEED_STAGES.map { it.segmentDistance })
    }

    @Test
    fun `straight trails contain the head plus thirteen runtime projectile positions`() {
        val renderer = load() ?: return
        val right = renderer.straightTrail(BotwoonSpritemap.DIRECTIONS.single { it.key == "right" })
        val upRight = renderer.straightTrail(BotwoonSpritemap.DIRECTIONS.single { it.key == "up-right" })

        assertEquals(14, right.size)
        assertEquals(BotwoonSpritemap.Point(-156, 0), right.last())
        assertEquals(BotwoonSpritemap.Point(-110, 110), upRight.last())
    }

    @Test
    fun `full compositions preserve per-link direction and all visible segments`() {
        val renderer = load() ?: return

        BotwoonSpritemap.COMPOSITIONS.forEach { definition ->
            val rendered = assertNotNull(renderer.renderComposition(definition), definition.name)
            val expectedEntries = 2 + definition.visibleProjectileCount
            assertEquals(expectedEntries, rendered.spritemap.entries.size, definition.name)
            assertTrue(rendered.pixels.any { it ushr 24 != 0 }, definition.name)
        }

        val turning = BotwoonSpritemap.COMPOSITIONS.single { it.key == "turning" }.points!!
        val linkDirections = turning.zipWithNext().map { (previous, current) ->
            renderer.directionForVector(previous.x - current.x, previous.y - current.y).key
        }
        assertTrue(linkDirections.distinct().size >= 3, "Curved history should select multiple body directions")
    }

    @Test
    fun `swim spit and projectile animations preserve independent source timings`() {
        val renderer = load() ?: return

        BotwoonSpritemap.DIRECTIONS.forEach { direction ->
            val swim = assertNotNull(renderer.renderSwimAnimation(direction), direction.name)
            assertEquals(4, swim.frames.size, direction.name)
            assertEquals(32, swim.totalTicks, direction.name)

            val spit = assertNotNull(renderer.renderSpitAnimation(direction), direction.name)
            assertEquals(32 + direction.spitOpenTicks, spit.totalTicks, direction.name)
            assertEquals(if (direction.key == "left") 8 else 6, spit.frames.size, direction.name)
        }
        val projectile = assertNotNull(renderer.renderSpitProjectileAnimation())
        assertEquals(5, projectile.frames.size)
        assertEquals(15, projectile.totalTicks)
    }

    @Test
    fun `all independently addressable components render`() {
        val renderer = load() ?: return

        assertEquals(37, BotwoonSpritemap.COMPONENTS.size)
        BotwoonSpritemap.COMPONENTS.forEach { definition ->
            val rendered = assertNotNull(renderer.renderComponent(definition), definition.name)
            assertTrue(rendered.pixels.any { it ushr 24 != 0 }, definition.name)
        }
    }
}
