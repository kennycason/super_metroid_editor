package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpritePalettes
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class EnemySpriteEditorStateTest {
    @Test
    fun `catalog sprites resolve project palette region overrides`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val gfx = TilesetGfxData()
        val state = EnemySpriteEditorState({ gfx }, {})
        val nativeSprites = NativeSpriteEditorState({ gfx }, {})
        var covered = 0

        EnemySpriteGraphics.EDITOR_ENEMIES.forEach { entry ->
            val slice = paletteRegionSlice(parser, entry.speciesId) ?: return@forEach
            val raw = parser.getRomData().copyOfRange(
                slice.region.offset,
                slice.region.offset + slice.region.byteSize,
            )
            val pink = 0x7C1F
            raw[slice.byteOffset + 2] = pink.toByte()
            raw[slice.byteOffset + 3] = (pink shr 8).toByte()
            gfx.spritePalettes.clear()
            gfx.spritePalettes[slice.region.id] = Base64.getEncoder().encodeToString(raw)

            val actual = state.loadEnemyPalette(parser, entry.speciesId)
            assertEquals(
                EnemySpriteGraphics.snesColorToArgb(pink),
                actual?.get(1),
                "${entry.name} should use ${slice.region.id}",
            )
            val effective = nativeSprites.previewParser(parser)
            assertEquals(
                EnemySpriteGraphics.snesColorToArgb(pink),
                EnemySpriteGraphics.loadEnemyPreviewPaletteSource(effective, entry.speciesId)?.colors?.get(1),
                "${entry.name} effective preview should use ${slice.region.id}",
            )
            covered++
        }

        assertTrue(covered >= 20, "Expected broad registered-palette coverage, found $covered sprites")
    }

    @Test
    fun `sidehopper palette edits share the same region as AI edits`() {
        val parser = sidehopperParser()
        val gfx = TilesetGfxData()
        var dirtyCount = 0
        val state = EnemySpriteEditorState({ gfx }, { dirtyCount++ })
        val speciesId = 0xD93F
        val region = SpritePalettes.findRegion("enemy_sidehopper")!!
        val palette = requireNotNull(EnemySpriteGraphics.readEnemyPalette(parser, speciesId)).copyOf()
        palette[1] = EnemySpriteGraphics.snesColorToArgb(0x7C1F)

        state.applyEnemyPalette(parser, speciesId, palette)

        assertTrue(gfx.spritePalettes.containsKey(region.id))
        assertFalse(gfx.spritePalettes.containsKey("enemy_pal:D93F"))
        assertTrue(state.hasCustomEnemyPalette(parser, speciesId))
        assertEquals(palette[1], state.loadEnemyPalette(parser, speciesId)?.get(1))

        state.resetEnemyPalette(parser, speciesId)

        assertFalse(gfx.spritePalettes.containsKey(region.id))
        assertFalse(state.hasCustomEnemyPalette(parser, speciesId))
        assertEquals(2, dirtyCount)
    }

    private fun sidehopperParser(): RomParser {
        val data = ByteArray(0x300000)
        val parser = RomParser(data)
        val speciesId = 0xD93F
        val region = SpritePalettes.findRegion("enemy_sidehopper")!!
        val paletteAddress = parser.pcToSnes(region.offset)
        val header = parser.snesToPc(0xA00000 or speciesId)
        data[header + 2] = paletteAddress.toByte()
        data[header + 3] = (paletteAddress shr 8).toByte()
        data[header + 0x0C] = (paletteAddress shr 16).toByte()
        repeat(16) { index ->
            val color = index or (index shl 5) or (index shl 10)
            data[region.offset + index * 2] = color.toByte()
            data[region.offset + index * 2 + 1] = (color shr 8).toByte()
        }
        return RomParser(data)
    }

    private data class RegionSlice(
        val region: SpritePalettes.PaletteRegion,
        val byteOffset: Int,
    )

    private fun paletteRegionSlice(parser: RomParser, speciesId: Int): RegionSlice? {
        val source = EnemySpriteGraphics.loadEnemyPreviewPaletteSource(parser, speciesId) ?: return null
        val pc = parser.snesToPc(source.snesAddress)
        val region = SpritePalettes.REGIONS.firstOrNull {
            pc >= it.offset && pc + 32 <= it.offset + it.byteSize
        } ?: return null
        return RegionSlice(region, pc - region.offset)
    }
}
