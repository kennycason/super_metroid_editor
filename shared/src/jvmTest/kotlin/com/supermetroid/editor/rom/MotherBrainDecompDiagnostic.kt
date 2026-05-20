package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Use the SM decompilation knowledge to find the correct Mother Brain P1 spritemap.
 *
 * From decompilation:
 * - MB brain's custom instruction list starts at $A9:5000
 * - MotherBrain_DrawBrain reads mbn_var_21 as instruction list pointer
 * - MotherBrain_AddSpritemapToOam reads spritemaps from bank $A9
 * - Format: [duration(2), spritemap_ptr(2)] entries, with control opcodes >= 0x8000
 */
class MotherBrainDecompDiagnostic {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `find MB brain spritemaps from decompilation-derived instruction list`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)

        // From decompilation: MB brain Phase 1 instruction list at $A9:9C21
        // (set via MotherBrain_SetBrainInstrs(addr_stru_A99C21) in MotherBrainsBrain_Init)
        val instrListAddr = 0xA99C21
        val ilPc = rp.snesToPc(instrListAddr)
        println("MB brain instruction list at \$A9:5000, PC=$ilPc")

        val outDir = File("/tmp/mb1_decomp")
        outDir.mkdirs()

        // Parse instruction list entries (same format as standard enemy)
        val spritemapPtrs = mutableListOf<Int>()
        var offset = 0
        for (f in 0 until 64) {
            if (ilPc + offset + 3 >= rom.size) break
            val word0 = readU16(rom, ilPc + offset)
            val word1 = readU16(rom, ilPc + offset + 2)
            offset += 4

            if (word0 == 0 && word1 == 0) { println("  [END]"); break }

            if (word0 >= 0x8000) {
                println("  [$f] CTRL \$${word0.toString(16)} arg=\$${word1.toString(16)}")
                if (word0 == 0x8000) break // GOTO
                continue
            }

            // word1 is spritemap pointer in bank $A9
            val smapSnes = 0xA90000 or word1
            val parsed = smap.parseSpritemap(smapSnes)
            val entries = parsed?.entries?.size ?: 0
            println("  [$f] duration=$word0 spritemap=\$A9:${word1.toString(16).uppercase().padStart(4, '0')} entries=$entries")

            if (parsed != null && entries > 0) {
                spritemapPtrs.add(word1)

                // Dump OAM entries for first few
                if (spritemapPtrs.size <= 5) {
                    for ((i, e) in parsed.entries.withIndex()) {
                        val lt = e.tileNum and 0xFF
                        val nt = (e.tileNum shr 8) and 1
                        val size = if (e.is16x16) "16x16" else "8x8"
                        println("       [$i] tile=$lt NT=$nt $size x=${e.xOffset} y=${e.yOffset} hf=${e.hFlip} vf=${e.vFlip}")
                    }
                }
            }
        }

        // Now render the spritemaps using MB brain's tile data ($EC3F)
        val speciesId = 0xEC3F
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return

        println("\nRendering ${spritemapPtrs.size} spritemaps with species $speciesId tiles")
        println("Tile data: ${tileData.size} bytes = ${tileData.size / 32} tiles")

        for ((i, ptr) in spritemapPtrs.take(10).withIndex()) {
            val smapSnes = 0xA90000 or ptr
            val parsed = smap.parseSpritemap(smapSnes) ?: continue
            val rendered = smap.renderSpritemap(parsed, tileData, palette) ?: continue

            val bi = BufferedImage(rendered.width, rendered.height, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, rendered.width, rendered.height, rendered.pixels, 0, rendered.width)
            ImageIO.write(bi, "png", File(outDir, "mb_brain_smap${i}_${rendered.width}x${rendered.height}.png"))
            println("  Saved smap$i: ${rendered.width}x${rendered.height}, ${parsed.entries.size} entries")
        }
    }

    @Test
    fun `also try MB brain species E7BF`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)

        // Try the $E7BF species (Brain entity from docs)
        val speciesId = 0xE7BF
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId)
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId)

        if (palette == null || tileData == null) {
            println("Species \$E7BF has no loadable GFX data")
            return
        }

        println("Species \$E7BF tile data: ${tileData.size} bytes = ${tileData.size / 32} tiles")

        // Also try species $E73F (Phase 1 from docs)
        val p1Palette = EnemySpriteGraphics.readEnemyPalette(rp, 0xE73F)
        val p1TileData = EnemySpriteGraphics.loadEnemyTileData(rp, 0xE73F)
        if (p1TileData != null) {
            println("Species \$E73F tile data: ${p1TileData.size} bytes = ${p1TileData.size / 32} tiles")
        } else {
            println("Species \$E73F has no loadable GFX data")
        }
    }
}
