package com.supermetroid.editor.procgen

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Visual diagnostic: generates one room per biome style over Landing Site and
 * renders each to test-resources/biome_<style>.png with the real tileset.
 * Skips silently when the ROM is unavailable.
 */
class BiomeRenderDiagnostic {

    @Test
    fun `render generated biomes to files`() {
        val romParser = TestRomHelper.loadRomParser() ?: return
        val headers = RoomRepository().getAllRooms().mapNotNull { romParser.readRoomHeader(it.getRoomIdAsInt()) }
        val room = headers.first { it.roomId == 0x91F8 }  // Landing Site, 9x5 screens

        val w = room.width * 16
        val h = room.height * 16
        val grid = LevelGrid.parse(romParser.decompressLZ2(room.levelDataPtr), w, h)!!
        val words = IntArray(w * h)
        val bts = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            words[y * w + x] = grid.word(x, y)
            bts[y * w + x] = grid.bts(x, y)
        }
        val profile = TilesetProfile.learn(romParser, headers, room.tileset)
        val renderer = MapRenderer(romParser)

        for (style in BiomeStyle.values()) {
            if (style == BiomeStyle.SURPRISE) continue
            val rules = BiomeRules.roll(style, 2026)
            val gen = BiomeGenerator(rules, profile, 2026).generate(w, h, words, bts)

            // Pack generated grid back into raw level-data bytes for the renderer.
            val n = w * h
            val levelData = ByteArray(2 + n * 2 + n)
            levelData[0] = ((n * 2) and 0xFF).toByte()
            levelData[1] = (((n * 2) shr 8) and 0xFF).toByte()
            for (i in 0 until n) {
                levelData[2 + i * 2] = (gen.words[i] and 0xFF).toByte()
                levelData[2 + i * 2 + 1] = ((gen.words[i] shr 8) and 0xFF).toByte()
                levelData[2 + n * 2 + i] = gen.bts[i].toByte()
            }
            val render = renderer.renderRoomFromLevelData(room, levelData) ?: continue
            val img = BufferedImage(render.width, render.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, render.width, render.height, render.pixels, 0, render.width)
            val name = "biome_${style.name.lowercase()}.png"
            ImageIO.write(img, "png", File(TestRomHelper.outputDir(), name))
        }
    }
}
