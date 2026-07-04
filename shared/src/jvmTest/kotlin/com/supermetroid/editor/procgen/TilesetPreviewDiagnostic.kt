package com.supermetroid.editor.procgen

import com.supermetroid.editor.data.RoomRepository
import com.supermetroid.editor.rom.MapRenderer
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.TileGraphics
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Visual diagnostic: generates one small cavern room and renders it once per
 * tileset (dressed with that tileset's learned vocabulary) to
 * test-resources/tileset_preview_<id>.png, printing rooms sampled per tileset.
 * Used to ground-truth the tileset id → visual biome mapping for [BiomeTheme].
 * Skips silently when the ROM is unavailable.
 */
class TilesetPreviewDiagnostic {

    @Test
    fun `render one generated room per tileset`() {
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

        for (tilesetId in 0 until TileGraphics.NUM_TILESETS) {
            val profile = TilesetProfile.learn(romParser, headers, tilesetId)
            println("tileset $tilesetId: ${profile.roomsSampled} rooms sampled")
            if (profile.roomsSampled == 0) continue

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
            val render = MapRenderer(romParser, tg)
                .renderRoomFromLevelData(room.copy(tileset = tilesetId), levelData) ?: continue
            val img = BufferedImage(render.width, render.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, render.width, render.height, render.pixels, 0, render.width)
            ImageIO.write(img, "png", File(TestRomHelper.outputDir(), "tileset_preview_$tilesetId.png"))
        }
    }
}
