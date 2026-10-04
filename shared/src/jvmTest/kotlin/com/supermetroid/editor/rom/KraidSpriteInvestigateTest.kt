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
    fun `decompress Kraid upper BG2 tilemap at B9 FA38`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }
        val pcAddr = parser.snesToPc(0xB9FA38)
        println("Kraid upper BG2 tilemap: SNES=\$B9:FA38  PC=0x${pcAddr.toString(16)}")

        val raw = parser.decompressLZ5AtPc(pcAddr)
        val wordCount = raw.size / 2
        println("Decompressed: ${raw.size} bytes = $wordCount BG2 words")
        assertEquals(0x1000, raw.size, "Upper map is two 32x32 screen blocks")

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
    fun `read Kraid head tilemaps 0-3 at A7 97C8`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }
        val rom = parser.getRomData()

        val tilemapAddrs = listOf(0xA797C8, 0xA79AC8, 0xA79DC8, 0xA7A0C8)
        for ((idx, snes) in tilemapAddrs.withIndex()) {
            val pc = parser.snesToPc(snes)
            println("\nTilemap_KraidHead_$idx: SNES=\$${snes.toString(16).uppercase()}  PC=0x${pc.toString(16)}")

            val size = 32 * 12 * 2
            val entries = (0 until size / 2).map { i -> rd16(rom, pc + i * 2) }
            val nonEmpty = entries.count { it and 0x03FF != 0 }
            val uniqueTiles = entries.map { it and 0x03FF }.filter { it != 0 }.toSet()
            val palRows = entries.map { (it shr 10) and 7 }.toSet()
            println("  Non-empty entries: $nonEmpty / ${size / 2}")
            if (uniqueTiles.isNotEmpty()) {
                println("  Tile index range: 0x${uniqueTiles.min().toString(16)}..0x${uniqueTiles.max().toString(16)}")
                println("  Tileset \$1A references span the full 10-bit BG tile index domain")
            }
            println("  Palette rows used: $palRows")
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
        println("Kraid room tiles: ${tiles.size / 32} tiles from Tiles_1A_Kraid")

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
    fun `render Kraid head tilemaps with room tileset`() {
        val parser = loadTestRom() ?: run { println("ROM not found, skipping"); return }

        val sm = KraidSpritemap(parser)
        assertTrue(sm.load(), "KraidSpritemap.load() failed")

        val outDir = File("build/test-output")
        outDir.mkdirs()

        for (def in KraidSpritemap.HEAD_TILEMAPS) {
            val sprite = sm.renderHeadTilemap(def)
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
    fun `render source-backed Kraid animation samples`() {
        val parser = loadTestRom() ?: return
        val outDir = File("build/test-output")
        outDir.mkdirs()

        val kraid = KraidSpritemap(parser)
        assertTrue(kraid.load())
        val oamSamples = listOf("arm-normal", "foot-walk-forward", "nail")
        for (key in oamSamples) {
            val def = KraidSpritemap.OAM_SEQUENCES.first { it.key == key }
            val tiles = EnemySpriteGraphics.loadEnemyTileData(parser, def.speciesId) ?: fail("No ${def.name} tiles")
            val animation = kraid.renderOamAnimation(def, tiles) ?: fail("No ${def.name} animation")
            writeFramePng(animation.frames[animation.frames.size / 2], File(outDir, "kraid_${key}.png"))
        }

        val miniTiles = EnemySpriteGraphics.loadEnemyTileData(parser, MiniKraidSpritemap.SPECIES_ID)
            ?: fail("No Mini Kraid tiles")
        val miniPalette = EnemySpriteGraphics.readEnemyPalette(parser, MiniKraidSpritemap.SPECIES_ID)
            ?: fail("No Mini Kraid palette")
        val mini = MiniKraidSpritemap(parser)
        for (key in listOf("step-forward-left", "fire-right")) {
            val def = MiniKraidSpritemap.SEQUENCES.first { it.key == key }
            val animation = mini.renderAnimation(def, miniTiles, miniPalette) ?: fail("No ${def.name}")
            writeFramePng(animation.frames[1], File(outDir, "mini_kraid_${key}.png"))
        }

    }

    private fun writeFramePng(frame: SpriteAnimationFrame, file: File) {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        ImageIO.write(image, "PNG", file)
    }

}
