package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CeresDiagTest {
    private var romParser: RomParser? = null

    @BeforeAll
    fun setUp() {
        romParser = TestRomHelper.loadRomParser()
    }

    @Test
    fun `render Ceres room and tileset grid to images`() {
        val rp = romParser ?: return

        // Render tileset 18 grid
        val tg18 = TileGraphics(rp)
        tg18.loadTileset(18)
        val grid18 = tg18.renderTilesetGrid()!!
        savePixels("/tmp/ceres_tileset18_grid.png", grid18.pixels, grid18.width, grid18.height)
        println("Saved tileset 18 grid to /tmp/ceres_tileset18_grid.png")

        // Render tileset 0 grid for comparison
        val tg0 = TileGraphics(rp)
        tg0.loadTileset(0)
        val grid0 = tg0.renderTilesetGrid()!!
        savePixels("/tmp/normal_tileset0_grid.png", grid0.pixels, grid0.width, grid0.height)
        println("Saved tileset 0 grid to /tmp/normal_tileset0_grid.png")

        // Render Ceres elevator room (0xdf45)
        val room = rp.readRoomHeader(0xdf45)!!
        println("Room 0xdf45: tileset=${room.tileset} ${room.width}x${room.height}")
        val renderer = MapRenderer(rp)
        val roomData = renderer.renderRoom(room)!!
        savePixels("/tmp/ceres_elevator_room.png", roomData.pixels, roomData.width, roomData.height)
        println("Saved room to /tmp/ceres_elevator_room.png")

        // Render a few metatiles at full detail for tileset 18
        val tg = TileGraphics(rp)
        tg.loadTileset(18)
        // Find non-empty metatiles
        var found = 0
        for (m in 0..1023) {
            val px = tg.renderMetatile(m) ?: continue
            val hasNonTransparent = px.any { it != 0 && it != 0xFF0C0C18.toInt() }
            if (hasNonTransparent && found < 3) {
                println("Metatile $m (tileset 18):")
                for (row in 0..15) {
                    val rowStr = (0..15).joinToString(" ") { x ->
                        val argb = px[row * 16 + x]
                        if (argb == 0) ".." else "%02x".format(argb and 0xFF)
                    }
                    println("  $rowStr")
                }
                found++
            }
        }

        // Dump raw GFX data for tiles referenced by metatile 0 of tileset 18
        val romData = rp.getRomData()
        val tablePC = rp.snesToPc(0x8FE6A2)
        val off18 = tablePC + 18 * 9
        val gfxPtr = readU24(romData, off18 + 3)
        val gfxData = rp.decompressLZ2(gfxPtr)

        // Tileset 18 metatile 0: tiles=3,47,3,47
        for (tileIdx in listOf(3, 47, 131)) {
            val tileOff = tileIdx * 32
            println("\nTile $tileIdx raw 4bpp (32 bytes):")
            println("  BP0/1 (rows 0-7):")
            for (row in 0..7) {
                val bp0 = gfxData[tileOff + row * 2].toInt() and 0xFF
                val bp1 = gfxData[tileOff + row * 2 + 1].toInt() and 0xFF
                println("    row $row: bp0=%02x bp1=%02x => ${decodeBp01Row(bp0, bp1)}".format(bp0, bp1))
            }
            println("  BP2/3 (rows 0-7):")
            for (row in 0..7) {
                val bp2 = gfxData[tileOff + row * 2 + 16].toInt() and 0xFF
                val bp3 = gfxData[tileOff + row * 2 + 17].toInt() and 0xFF
                println("    row $row: bp2=%02x bp3=%02x => ${decodeBp23Row(bp2, bp3)}".format(bp2, bp3))
            }
        }
    }

    private fun decodeBp01Row(bp0: Int, bp1: Int): String {
        return (7 downTo 0).joinToString("") { bit ->
            val v = ((bp0 shr bit) and 1) or (((bp1 shr bit) and 1) shl 1)
            v.toString(16)
        }
    }

    private fun decodeBp23Row(bp2: Int, bp3: Int): String {
        return (7 downTo 0).joinToString("") { bit ->
            val v = (((bp2 shr bit) and 1) shl 2) or (((bp3 shr bit) and 1) shl 3)
            v.toString(16)
        }
    }

    private fun savePixels(path: String, pixels: IntArray, width: Int, height: Int) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, width, height, pixels, 0, width)
        ImageIO.write(img, "PNG", File(path))
    }

    private fun readU24(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
    }
}
