package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

class KraidSpriteInvestigateTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun rd16(rom: ByteArray, pc: Int): Int =
        (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `decompress Kraid tile graphics at B9 FA38`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }
        val pcAddr = parser.snesToPc(0xB9FA38)
        println("Kraid tile GFX: SNES=\$B9:FA38  PC=0x${pcAddr.toString(16)}")

        val raw = parser.decompressLZ5AtPc(pcAddr)
        val tileCount = raw.size / 32
        println("Decompressed: ${raw.size} bytes = $tileCount tiles (8x8, 4bpp)")
        assertTrue(tileCount > 0, "Expected tiles from Kraid tile data")

        val nonZero = raw.count { it.toInt() != 0 }
        println("Non-zero bytes: $nonZero / ${raw.size} (${nonZero * 100 / raw.size}%)")
    }

    @Test
    fun `decompress Kraid BG2 nametable at B9 FE3E`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }
        val pcAddr = parser.snesToPc(0xB9FE3E)
        println("Kraid BG2 nametable: SNES=\$B9:FE3E  PC=0x${pcAddr.toString(16)}")

        val raw = parser.decompressLZ5AtPc(pcAddr)
        val wordCount = raw.size / 2
        println("Decompressed: ${raw.size} bytes = $wordCount tilemap words")

        val entries = (0 until wordCount).map { i -> rd16(raw, i * 2) }
        val nonEmpty = entries.count { it and 0x03FF != 0 }
        println("Non-empty tile entries: $nonEmpty / $wordCount")

        val uniqueTiles = entries.map { it and 0x03FF }.filter { it != 0 }.toSet()
        println("Unique tile indices used: ${uniqueTiles.size} — range ${uniqueTiles.minOrNull()?.let { "0x${it.toString(16)}" }}..${uniqueTiles.maxOrNull()?.let { "0x${it.toString(16)}" }}")

        val palRows = entries.map { (it shr 10) and 7 }.toSet()
        println("Palette rows used: $palRows")
    }

    @Test
    fun `read kKraidTilemaps 0-3 at A7 97C8`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }
        val rom = parser.getRomData()

        val tilemapAddrs = listOf(0xA797C8, 0xA79AC8, 0xA79DC8, 0xA7A0C8)
        for ((idx, snes) in tilemapAddrs.withIndex()) {
            val pc = parser.snesToPc(snes)
            println("\nkKraidTilemaps_$idx: SNES=\$${snes.toString(16).uppercase()}  PC=0x${pc.toString(16)}")

            val size = 32 * 12 * 2
            val entries = (0 until size / 2).map { i -> rd16(rom, pc + i * 2) }
            val nonEmpty = entries.count { it and 0x03FF != 0 }
            val uniqueTiles = entries.map { it and 0x03FF }.filter { it != 0 }.toSet()
            val palRows = entries.map { (it shr 10) and 7 }.toSet()
            println("  Non-empty entries: $nonEmpty / ${size / 2}")
            if (uniqueTiles.isNotEmpty()) {
                println("  Tile index range: 0x${uniqueTiles.min().toString(16)}..0x${uniqueTiles.max().toString(16)}")
                val roomTiles = uniqueTiles.filter { it < 0x100 }.size
                val kraidTiles = uniqueTiles.filter { it in 0x100..0x17F }.size
                println("  Room tileset tiles (< 0x100): $roomTiles, Kraid tiles (0x100-0x17F): $kraidTiles")
            }
            println("  Palette rows used: $palRows")
        }
    }

    @Test
    fun `analyze BigSprmap entries at A7 E27E onwards`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }
        val rom = parser.getRomData()

        val bigSprmapAddrs = listOf(
            0xA7E27E, 0xA7E292, 0xA7E2A6, 0xA7E2BA, 0xA7E2CE,
            0xA7E2E2, 0xA7E2F6, 0xA7E30A, 0xA7E39A, 0xA7E3B6
        )

        for (snes in bigSprmapAddrs) {
            val pc = parser.snesToPc(snes)
            val firstWord = rd16(rom, pc)
            print("BigSprmap \$${snes.toString(16).uppercase()}: header=0x${firstWord.toString(16)}")

            if (firstWord == 0xFFFE) {
                var offset = pc + 2
                var rows = 0
                var totalTiles = 0
                val tileNums = mutableSetOf<Int>()
                val palRows = mutableSetOf<Int>()
                while (true) {
                    val dest = rd16(rom, offset)
                    if (dest == 0xFFFF) break
                    val count = rd16(rom, offset + 2)
                    totalTiles += count
                    for (i in 0 until count) {
                        val tw = rd16(rom, offset + 4 + i * 2)
                        tileNums.add(tw and 0x03FF)
                        palRows.add((tw shr 10) and 7)
                    }
                    offset += 4 + count * 2
                    rows++
                }
                println(" — FFFE format, $rows rows, $totalTiles tiles, palettes=$palRows, tile range 0x${tileNums.minOrNull()?.toString(16)}..0x${tileNums.maxOrNull()?.toString(16)}")
            } else {
                println(" — NOT FFFE format")
            }
        }
    }

    @Test
    fun `read Kraid palette at A7 86C7`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }
        val rom = parser.getRomData()

        val palPc = parser.snesToPc(0xA786C7)
        println("Kraid palette (kKraid_Palette2): SNES=\$A7:86C7  PC=0x${palPc.toString(16)}")
        println("Loaded to BG palette row 6 during fight")

        for (i in 0 until 16) {
            val bgr = rd16(rom, palPc + i * 2)
            val argb = EnemySpriteGraphics.snesColorToArgb(bgr)
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            println("  [$i] BGR555=0x${bgr.toString(16).padStart(4, '0')}  ARGB=#${r.toString(16).padStart(2,'0')}${g.toString(16).padStart(2,'0')}${b.toString(16).padStart(2,'0')}")
        }
    }

    @Test
    fun `render Kraid tile sheet to PNG`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }

        val sm = KraidSpritemap(parser)
        assertTrue(sm.load(), "KraidSpritemap.load() failed")

        val tiles = sm.getTileData() ?: fail("No tile data")
        val palette = sm.getPalette() ?: fail("No palette")
        println("Kraid tiles: ${tiles.size / 32} tiles from \$B9:FA38")

        val gfx = EnemySpriteGraphics(parser)
        gfx.loadFromRaw(listOf(tiles))
        val result = gfx.renderSheet(palette, cols = 16) ?: fail("renderSheet failed")
        val (pixels, w, h) = result
        println("Sheet: ${w}x${h}")

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        val outDir = File("build/test-output")
        outDir.mkdirs()
        val outFile = File(outDir, "kraid_tile_sheet.png")
        ImageIO.write(img, "PNG", outFile)
        println("Wrote tile sheet: ${outFile.absolutePath}")
    }

    @Test
    fun `render Kraid full body with room tileset`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }

        val sm = KraidSpritemap(parser)
        assertTrue(sm.load(), "KraidSpritemap.load() failed")
        println("Loaded tileset ${sm.getTilesetId()} for Kraid room")

        val body = sm.renderFullBody() ?: fail("renderFullBody failed")
        println("Full body: ${body.width}x${body.height}")

        val nonTransparent = body.pixels.count { (it ushr 24) and 0xFF > 0 }
        println("Non-transparent pixels: $nonTransparent / ${body.pixels.size}")
        assertTrue(nonTransparent > 1000, "Expected significant rendered content in full body")

        val img = BufferedImage(body.width, body.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, body.width, body.height, body.pixels, 0, body.width)
        val outDir = File("build/test-output")
        outDir.mkdirs()
        val outFile = File(outDir, "kraid_body_bg2.png")
        ImageIO.write(img, "PNG", outFile)
        println("Wrote BG2 body render: ${outFile.absolutePath}")
    }

    @Test
    fun `render Kraid body tilemaps with room tileset`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }

        val sm = KraidSpritemap(parser)
        assertTrue(sm.load(), "KraidSpritemap.load() failed")

        val outDir = File("build/test-output")
        outDir.mkdirs()

        for (def in KraidSpritemap.BODY_TILEMAPS) {
            val sprite = sm.renderBodyTilemap(def)
            if (sprite == null) { println("WARN: ${def.name} render failed"); continue }
            val nonTransparent = sprite.pixels.count { (it ushr 24) and 0xFF > 0 }
            println("${def.name}: ${sprite.width}x${sprite.height}, ${nonTransparent} non-transparent pixels")
            assertTrue(nonTransparent > 100, "Expected visible content in ${def.name}")

            val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
            val safeName = def.name.replace(Regex("[^a-zA-Z0-9]"), "_")
            ImageIO.write(img, "PNG", File(outDir, "kraid_${safeName}.png"))
        }
    }

    @Test
    fun `render Kraid BigSprmap components`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }

        val sm = KraidSpritemap(parser)
        assertTrue(sm.load(), "KraidSpritemap.load() failed")

        val outDir = File("build/test-output")
        outDir.mkdirs()

        for (def in KraidSpritemap.BIGSPRMAP_COMPONENTS) {
            val sprite = sm.renderBigSprmap(def)
            if (sprite == null) { println("WARN: ${def.name} render failed"); continue }
            val nonTransparent = sprite.pixels.count { (it ushr 24) and 0xFF > 0 }
            println("${def.name}: ${sprite.width}x${sprite.height}, ${nonTransparent} non-transparent pixels")

            val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
            val safeName = def.name.replace(Regex("[^a-zA-Z0-9]"), "_")
            ImageIO.write(img, "PNG", File(outDir, "kraid_${safeName}.png"))
        }
    }
}
