package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TorizoSpritemapTest {

    private fun load(): TorizoSpritemap? {
        val parser = TestRomHelper.loadRomParser() ?: return null
        val tiles = EnemySpriteGraphics.loadEnemyTileData(parser, TorizoSpritemap.BOMB_SPECIES_ID) ?: return null
        return TorizoSpritemap(parser).also { assertTrue(it.load(tiles)) }
    }

    @Test
    fun `shared body compositions render both encounters and runtime overlays`() {
        val renderer = load() ?: return

        TorizoSpritemap.COMPOSITIONS.forEach { definition ->
            val palette = TorizoSpritemap.PALETTE_STAGES.first {
                if (definition.encounter == TorizoSpritemap.Encounter.BOMB) it.key == "bomb-normal" else it.key == "gold-active"
            }
            val rendered = assertNotNull(renderer.renderComposition(definition, palette), definition.name)
            assertTrue(rendered.pixels.any { it ushr 24 != 0 }, definition.name)
            assertTrue(rendered.width in 24..160, definition.name)
            assertTrue(rendered.height in 24..160, definition.name)
        }

        val normal = assertNotNull(renderer.renderComposition(
            TorizoSpritemap.COMPOSITIONS.first { it.key == "bomb-facing" },
            TorizoSpritemap.PALETTE_STAGES.first { it.key == "bomb-normal" },
        ))
        val damaged = assertNotNull(renderer.renderComposition(
            TorizoSpritemap.COMPOSITIONS.first { it.key == "bomb-face" },
            TorizoSpritemap.PALETTE_STAGES.first { it.key == "bomb-normal" },
        ))
        assertNotEquals(normal.pixels.contentHashCode(), damaged.pixels.contentHashCode())
    }

    @Test
    fun `all body and projectile animations render from their source owners`() {
        val renderer = load() ?: return
        TorizoSpritemap.ANIMATIONS.forEach { definition ->
            val palette = TorizoSpritemap.PALETTE_STAGES.first {
                if (definition.encounter == TorizoSpritemap.Encounter.BOMB) it.key == "bomb-normal" else it.key == "gold-active"
            }
            val animation = assertNotNull(renderer.renderAnimation(definition, palette), definition.key)
            assertEquals(definition.frames.size, animation.frames.size, definition.key)
            assertTrue(animation.frames.all { frame -> frame.pixels.any { it ushr 24 != 0 } }, definition.key)
        }

        val golden = TorizoSpritemap.PALETTE_STAGES.first { it.key == "gold-active" }
        TorizoSpritemap.PROJECTILE_ANIMATIONS.forEach { definition ->
            val animation = assertNotNull(renderer.renderProjectileAnimation(definition, golden), definition.key)
            assertEquals(definition.addresses.size, animation.frames.size, definition.key)
            assertTrue(animation.frames.all { frame -> frame.pixels.any { it ushr 24 != 0 } }, definition.key)
        }
    }

    @Test
    fun `palette and pixel owners remain distinct`() {
        val renderer = load() ?: return

        assertEquals(13, TorizoSpritemap.PALETTE_STAGES.size)
        assertEquals(4, TorizoSpritemap.PIXEL_SOURCES.size)
        TorizoSpritemap.PIXEL_SOURCES.forEach { source ->
            assertEquals(source.byteCount, assertNotNull(renderer.readPixelSource(source)).size, source.key)
        }
        val goldenHashes = TorizoSpritemap.PALETTE_STAGES.filter { it.encounter == TorizoSpritemap.Encounter.GOLDEN }
            .map { assertNotNull(renderer.readPalette(it)).contentHashCode() }
        assertEquals(10, goldenHashes.size)
        assertEquals(9, goldenHashes.distinct().size)
        assertEquals(
            assertNotNull(renderer.readPalette(TorizoSpritemap.PALETTE_STAGES.first { it.key == "gold-active" })).contentHashCode(),
            assertNotNull(renderer.readPalette(TorizoSpritemap.PALETTE_STAGES.first { it.key == "gold-hp-overflow" })).contentHashCode(),
            "The named post-awakening palette intentionally aliases the handler's top saturation row",
        )
    }

    @Test
    fun `all independently addressable components render`() {
        val renderer = load() ?: return
        val palette = TorizoSpritemap.PALETTE_STAGES.first { it.key == "gold-active" }

        TorizoSpritemap.COMPONENTS.forEach { definition ->
            val rendered = assertNotNull(renderer.renderComponent(definition, palette), definition.name)
            assertTrue(rendered.pixels.any { it ushr 24 != 0 }, definition.name)
        }
    }

    @Test
    fun `extended poses preserve lower OAM index foreground priority`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(parser, TorizoSpritemap.BOMB_SPECIES_ID) ?: return
        val renderer = TorizoSpritemap(parser).also { assertTrue(it.load(tileData)) }
        val palette = TorizoSpritemap.PALETTE_STAGES.first { it.key == "bomb-normal" }

        listOf("bomb-awake-left", "jump-left").forEach { key ->
            val definition = TorizoSpritemap.COMPOSITIONS.firstOrNull { it.key == key }
                ?: TorizoSpritemap.COMPONENTS.first { it.key == key }
            val actual = when (definition) {
                is TorizoSpritemap.CompositionDef -> renderer.renderComposition(definition, palette)
                is TorizoSpritemap.ComponentDef -> renderer.renderComponent(definition, palette)
                else -> null
            }
            val rendered = assertNotNull(actual, key)

            // Reversing an extended pose must materially affect overlapping
            // anatomy. This catches regressions where the torso covers the head
            // or foreground arm even though every individual tile still parses.
            val frameAddress = when (definition) {
                is TorizoSpritemap.CompositionDef -> definition.snesAddress
                is TorizoSpritemap.ComponentDef -> definition.snesAddress
                else -> error("Unexpected definition")
            }
            val frame = assertNotNull(EnemySpritemap(parser).parseRenderableFrame(frameAddress))
            val physicalTiles = ByteArray(0x4000).also { tileData.copyInto(it, 0x2000) }
            val firstPalette = readPalette(parser, palette.palette1Snes)
            val secondPalette = readPalette(parser, palette.palette2Snes)
            val wrongOrder = assertNotNull(EnemySpritemap(parser).renderRenderableFrame(
                frame,
                physicalTiles,
                firstPalette,
                EnemySpritemap.RenderOptions(
                    oamPaletteRows = mapOf(1 to firstPalette, 2 to secondPalette),
                    oamTileNumberMode = EnemySpritemap.OamTileNumberMode.LOW_9,
                    reverseExtendedOamDrawOrder = false,
                ),
            ))
            assertFalse(rendered.pixels.contentEquals(wrongOrder.pixels), "$key must use SNES OAM overlap order")
        }
    }

    private fun readPalette(parser: RomParser, snesAddress: Int): IntArray {
        val start = parser.snesToPc(snesAddress)
        return IntArray(16) { index ->
            if (index == 0) 0 else {
                val lo = parser.romData[start + index * 2].toInt() and 0xFF
                val hi = parser.romData[start + index * 2 + 1].toInt() and 0xFF
                EnemySpriteGraphics.snesColorToArgb(lo or (hi shl 8))
            }
        }
    }

}
