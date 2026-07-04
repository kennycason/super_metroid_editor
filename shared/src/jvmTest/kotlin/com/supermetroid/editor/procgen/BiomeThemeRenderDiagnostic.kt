package com.supermetroid.editor.procgen

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.PaletteEffects
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.TileGraphics
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Visual diagnostic: generates one cavern room per [BiomeTheme] — dressed with
 * the theme tileset's learned vocabulary and its palette recolor — and renders
 * each to test-resources/biome_theme_<id>.png.
 * Skips silently when the ROM is unavailable.
 */
class BiomeThemeRenderDiagnostic {

    @Test
    fun `render one generated room per theme`() {
        val romParser = TestRomHelper.loadRomParser() ?: return
        val headers = RoomRepository().getAllRooms().mapNotNull { romParser.readRoomHeader(it.getRoomIdAsInt()) }
        val room = headers.first { it.roomId == 0xADAD }  // Double Chamber, 4x2 screens

        val w = room.width * 16
        val h = room.height * 16
        val grid = LevelGrid.parse(romParser.decompressLZ2(room.levelDataPtr), w, h)!!
        val words = IntArray(w * h)
        val bts = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            words[y * w + x] = grid.word(x, y)
            bts[y * w + x] = grid.bts(x, y)
        }
        val rules = BiomeRules.roll(BiomeStyle.CAVERN, 2026)

        for (theme in BiomeTheme.CONCRETE) {
            val tilesetId = theme.tilesetId!!
            val profile = TilesetProfile.learn(romParser, headers, tilesetId)
            if (profile.roomsSampled == 0) {
                println("theme ${theme.id}: tileset $tilesetId has no learnable rooms!")
                continue
            }
            val gen = BiomeGenerator(rules, profile, 2026).generate(w, h, words, bts)
            val n = w * h
            val levelData = ByteArray(2 + n * 2 + n)
            levelData[0] = ((n * 2) and 0xFF).toByte()
            levelData[1] = (((n * 2) shr 8) and 0xFF).toByte()
            for (i in 0 until n) {
                levelData[2 + i * 2] = (gen.words[i] and 0xFF).toByte()
                levelData[2 + i * 2 + 1] = ((gen.words[i] shr 8) and 0xFF).toByte()
                levelData[2 + n * 2 + i] = gen.bts[i].toByte()
            }

            val tg = TileGraphics(romParser)
            if (!tg.loadTileset(tilesetId)) continue
            theme.paletteEffectId?.let { effectId ->
                val raw = tg.getRawPaletteData() ?: return@let
                val colors = IntArray(128) {
                    (raw[it * 2].toInt() and 0xFF) or ((raw[it * 2 + 1].toInt() and 0xFF) shl 8)
                }
                PaletteEffects.findEffect(effectId)?.apply?.invoke(colors)
                for (row in 0..7) for (col in 0..15) {
                    tg.setPaletteEntry(row, col, colors[row * 16 + col])
                }
            }
            val render = MapRenderer(romParser, tg)
                .renderRoomFromLevelData(room.copy(tileset = tilesetId), levelData) ?: continue
            val img = BufferedImage(render.width, render.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, render.width, render.height, render.pixels, 0, render.width)
            ImageIO.write(img, "png", File(TestRomHelper.outputDir(), "biome_theme_${theme.id}.png"))
            println("theme ${theme.id}: tileset $tilesetId, ${profile.roomsSampled} rooms sampled")
        }
    }
}
