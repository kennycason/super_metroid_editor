package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Focused diagnostic on Torizo tile mapping.
 * Torizo poses render with correct SHAPE but wrong TILES —
 * this means OAM positions are right but tile indices are off.
 */
class TorizoTileDiagnostic {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `dump Torizo pose OAM entries and render each tile individually`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val speciesId = 0xEEFF

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return
        val rom = rp.getRomData()
        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val rawTileSize = readU16(rom, headerPc)
        val tileCount = (rawTileSize and 0x7FFF) / 32

        println("Torizo: tileCount=$tileCount, tileDataSize=${tileData.size} bytes")
        println("  bit15 = ${(rawTileSize and 0x8000) != 0}")

        val poses = scanner.scanPoses(speciesId, minEntries = 15)
        if (poses.isEmpty()) { println("No poses found!"); return }

        // Take the best compact pose
        val pose = poses.first()
        println("\nPose '${pose.name}': ${pose.entryCount} entries")

        val outDir = File("/tmp/torizo_debug")
        outDir.mkdirs()

        // Dump each OAM entry
        for ((i, entry) in pose.spritemap.entries.withIndex()) {
            val fullTileNum = entry.tileNum  // full 9-bit
            val localTile = fullTileNum and 0xFF
            val nameTable = (fullTileNum shr 8) and 1
            val size = if (entry.is16x16) "16x16" else "8x8"

            println("  [$i] tile=$localTile(0x${localTile.toString(16)}) NT=$nameTable " +
                "x=${entry.xOffset} y=${entry.yOffset} $size " +
                "hFlip=${entry.hFlip} vFlip=${entry.vFlip} pal=${entry.palRow}")

            // For 16x16 tiles, show the sub-tile indices
            if (entry.is16x16) {
                val sub0 = localTile
                val sub1 = localTile + 1
                val sub2 = localTile + 16
                val sub3 = localTile + 17
                val valid = listOf(sub0, sub1, sub2, sub3).map {
                    if (it * 32 + 32 <= tileData.size) "ok" else "OOB"
                }
                println("       sub-tiles: $sub0(${valid[0]}), $sub1(${valid[1]}), " +
                    "$sub2(${valid[2]}), $sub3(${valid[3]})")
            }
        }

        // Render the full pose with our current code
        val smap = EnemySpritemap(rp)
        val assembled = smap.renderSpritemap(pose.spritemap, tileData, palette)
        if (assembled != null) {
            val bi = BufferedImage(assembled.width, assembled.height, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, assembled.width, assembled.height, assembled.pixels, 0, assembled.width)
            ImageIO.write(bi, "png", File(outDir, "torizo_pose_current.png"))
            println("\nRendered pose: ${assembled.width}x${assembled.height}")
        }

        // Now render the tile sheet to see what's at each tile index
        val gfx = EnemySpriteGraphics(rp)
        gfx.loadFromRaw(listOf(tileData))
        val sheet = gfx.renderSheet(palette, 16)
        if (sheet != null) {
            val (px, w, h) = sheet
            val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, w, h, px, 0, w)
            ImageIO.write(bi, "png", File(outDir, "torizo_tilesheet.png"))
            println("Tile sheet: ${w}x${h}")
        }
    }

    @Test
    fun `check if Torizo VRAM loading splits across name tables`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val speciesId = 0xEEFF

        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
        val rawTileSize = readU16(rom, headerPc)
        val tileCount = (rawTileSize and 0x7FFF) / 32

        // Check vram_dst from enemy GFX set data in bank $B4
        // The enemy's GRAPHADR offset+bank tells us where tiles come from
        val graphOffset = readU16(rom, headerPc + 0x36)
        val graphBank = rom[headerPc + 0x38].toInt() and 0xFF

        println("Torizo species header:")
        println("  AI bank: \$${aiBank.toString(16).uppercase()}")
        println("  tileDataSize: $rawTileSize (0x${rawTileSize.toString(16)}), ${tileCount} tiles, bit15=${(rawTileSize and 0x8000) != 0}")
        println("  GRAPHADR: \$${graphBank.toString(16).uppercase()}:${graphOffset.toString(16).uppercase().padStart(4, '0')}")

        // Check palette info
        val palPtr = readU16(rom, headerPc + 0x02)
        println("  Palette ptr: \$${palPtr.toString(16).uppercase()}")

        // For comparison, check a working enemy (Mini Kraid)
        val mkHeaderPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or 0xE0FF)
        val mkAiBank = rom[mkHeaderPc + 0x0C].toInt() and 0xFF
        val mkTileSize = readU16(rom, mkHeaderPc)
        val mkGraphOffset = readU16(rom, mkHeaderPc + 0x36)
        val mkGraphBank = rom[mkHeaderPc + 0x38].toInt() and 0xFF

        println("\nMini Kraid (for comparison):")
        println("  AI bank: \$${mkAiBank.toString(16).uppercase()}")
        println("  tileDataSize: $mkTileSize (0x${mkTileSize.toString(16)}), ${(mkTileSize and 0x7FFF) / 32} tiles")
        println("  GRAPHADR: \$${mkGraphBank.toString(16).uppercase()}:${mkGraphOffset.toString(16).uppercase().padStart(4, '0')}")
    }

    @Test
    fun `test rendering with name table offset applied`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val smap = EnemySpritemap(rp)
        val speciesId = 0xEEFF

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return
        val rom = rp.getRomData()
        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val rawTileSize = readU16(rom, headerPc)
        val tileCount = (rawTileSize and 0x7FFF) / 32

        val poses = scanner.scanPoses(speciesId, minEntries = 15)
        if (poses.isEmpty()) return

        val pose = poses.first()
        val outDir = File("/tmp/torizo_debug")
        outDir.mkdirs()

        // Theory: if tile data is split 50/50 between NT0 and NT1,
        // NT1 tile N actually maps to GRAPHADR tile (tileCount/2 + N)
        // Try several offsets and see which produces the best result
        for (offset in listOf(0, 128, -128, 64, -64, 32, -32, 16, -16)) {
            // Create a modified spritemap with adjusted tile numbers
            val adjustedEntries = pose.spritemap.entries.map { entry ->
                val nt = (entry.tileNum shr 8) and 1
                val localTile = entry.tileNum and 0xFF
                val adjustedTile = if (nt == 1) {
                    ((localTile + offset) and 0xFF) // Apply offset for NT1 tiles
                } else {
                    localTile
                }
                EnemySpritemap.OamEntry(
                    entry.xOffset, entry.yOffset, adjustedTile,
                    entry.palRow, entry.hFlip, entry.vFlip, entry.is16x16
                )
            }
            val adjustedSmap = EnemySpritemap.Spritemap(adjustedEntries, pose.spritemap.snesAddress)
            val rendered = smap.renderSpritemap(adjustedSmap, tileData, palette) ?: continue

            // Calculate fill quality
            val filled = rendered.pixels.count { (it ushr 24) > 0 }
            val fillPct = filled * 100 / (rendered.width * rendered.height)

            val bi = BufferedImage(rendered.width, rendered.height, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, rendered.width, rendered.height, rendered.pixels, 0, rendered.width)
            ImageIO.write(bi, "png", File(outDir, "torizo_offset_${if (offset >= 0) "p" else "m"}${kotlin.math.abs(offset)}.png"))

            println("Offset $offset: ${rendered.width}x${rendered.height}, fill=$fillPct%")
        }
    }
}
