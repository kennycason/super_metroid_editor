package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Diagnose Mother Brain's runtime tile DMA transfers.
 * These transfers replace mechanical frame tiles with brain tissue during phase transitions.
 */
class MBTileTransferDiagnostic {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        return null
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `dump all VramWriteEntry tables for Mother Brain phase transitions`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()

        // VramWriteEntry tables from sm_a9.c
        val tables = mapOf(
            0x8F8F to "Phase2 Leg/Brain Tiles",
            0x8FC7 to "Shitroid Cutscene",
            0x8FE5 to "Phase2 Cutscene",
            0x9003 to "Phase3 Death",
            0x902F to "Brain Death Extra",
        )

        for ((offset, label) in tables) {
            val snesAddr = 0xA90000 or offset
            val pc = rp.snesToPc(snesAddr)
            if (pc < 0) { println("$label: invalid address"); continue }

            println("\n=== $label (table at \$A9:${offset.toString(16).uppercase()}) ===")

            var entryOffset = 0
            var entryIdx = 0
            while (true) {
                val addr = pc + entryOffset
                if (addr + 7 > rom.size) break

                val size = readU16(rom, addr)
                if (size == 0) { println("  [end] terminator"); break }

                val srcAddr = readU16(rom, addr + 2)
                val srcBank = rom[addr + 4].toInt() and 0xFF
                val vramDst = readU16(rom, addr + 5)

                // Calculate which tile indices this overwrites
                // VRAM dest is in word address; tiles are 16 words (32 bytes) each
                // OBJ base with OBSEL=3: base = 3 * 0x4000 = 0xC000 words
                // Tile index = (vramDst - objBase) / 16
                val objBase = 3 * 0x4000 // = 0xC000 words
                val tileStartIdx = (vramDst - objBase) / 16
                val tileCount = size / 32

                val srcSnes = "\$${srcBank.toString(16).uppercase()}:${srcAddr.toString(16).uppercase().padStart(4, '0')}"
                println("  [$entryIdx] size=$size bytes ($tileCount tiles) src=$srcSnes " +
                    "vramDst=0x${vramDst.toString(16)} → tiles $tileStartIdx..${tileStartIdx + tileCount - 1}")

                entryOffset += 7
                entryIdx++
                if (entryIdx > 20) { println("  [truncated]"); break }
            }
        }
    }

    @Test
    fun `apply Phase2 tile transfers and render Brain Idle 2`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()
        val smap = EnemySpritemap(rp)
        val outDir = File("/tmp/mb_tile_transfer")
        outDir.mkdirs()

        // Load MB P1 base tile data
        val speciesId = 0xEC3F
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val baseTileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return

        println("Base tile data: ${baseTileData.size} bytes (${baseTileData.size / 32} tiles)")

        // Apply Phase2 tile transfers from table at $A9:8F8F
        val patchedTileData = baseTileData.copyOf()
        val tablePc = rp.snesToPc(0xA90000 or 0x8F8F)
        val objBase = 3 * 0x4000 // OBSEL=3 base

        var entryOffset = 0
        while (true) {
            val addr = tablePc + entryOffset
            if (addr + 7 > rom.size) break

            val size = readU16(rom, addr)
            if (size == 0) break

            val srcAddr = readU16(rom, addr + 2)
            val srcBank = rom[addr + 4].toInt() and 0xFF
            val vramDst = readU16(rom, addr + 5)

            val tileStartIdx = (vramDst - objBase) / 16
            val dstByteOffset = tileStartIdx * 32

            // Copy from ROM source to our patched tile data
            val srcPc = rp.snesToPc((srcBank shl 16) or srcAddr)
            if (srcPc >= 0 && dstByteOffset >= 0 && dstByteOffset + size <= patchedTileData.size &&
                srcPc + size <= rom.size) {
                System.arraycopy(rom, srcPc, patchedTileData, dstByteOffset, size)
                println("Applied: $size bytes from \$${srcBank.toString(16).uppercase()}:${srcAddr.toString(16).uppercase().padStart(4, '0')} " +
                    "→ tiles $tileStartIdx..${tileStartIdx + size / 32 - 1}")
            } else {
                println("SKIP: size=$size src=\$${srcBank.toString(16).uppercase()}:${srcAddr.toString(16).uppercase().padStart(4, '0')} " +
                    "tileIdx=$tileStartIdx dstOffset=$dstByteOffset (out of range)")
            }

            entryOffset += 7
        }

        // Render tile sheets for comparison
        val gfx = EnemySpriteGraphics(rp)

        gfx.loadFromRaw(listOf(baseTileData))
        val baseSheet = gfx.renderSheet(palette, 16)!!
        val (bsPx, bsW, bsH) = baseSheet
        val bsBi = BufferedImage(bsW, bsH, BufferedImage.TYPE_INT_ARGB)
        bsBi.setRGB(0, 0, bsW, bsH, bsPx, 0, bsW)
        ImageIO.write(bsBi, "png", File(outDir, "tilesheet_base.png"))

        gfx.loadFromRaw(listOf(patchedTileData))
        val patchedSheet = gfx.renderSheet(palette, 16)!!
        val (psPx, psW, psH) = patchedSheet
        val psBi = BufferedImage(psW, psH, BufferedImage.TYPE_INT_ARGB)
        psBi.setRGB(0, 0, psW, psH, psPx, 0, psW)
        ImageIO.write(psBi, "png", File(outDir, "tilesheet_phase2.png"))

        // Render Brain Idle 2 with patched tiles
        val brainIdle2 = smap.parseSpritemap(0xA90000 or 0xA717)
        if (brainIdle2 != null) {
            val baseRender = smap.renderSpritemap(brainIdle2, baseTileData, palette)
            val patchedRender = smap.renderSpritemap(brainIdle2, patchedTileData, palette)

            if (baseRender != null) {
                val bi = BufferedImage(baseRender.width, baseRender.height, BufferedImage.TYPE_INT_ARGB)
                bi.setRGB(0, 0, baseRender.width, baseRender.height, baseRender.pixels, 0, baseRender.width)
                ImageIO.write(bi, "png", File(outDir, "brain_idle2_base.png"))
                println("\nBrain Idle 2 (base tiles): ${baseRender.width}x${baseRender.height}")
            }
            if (patchedRender != null) {
                val bi = BufferedImage(patchedRender.width, patchedRender.height, BufferedImage.TYPE_INT_ARGB)
                bi.setRGB(0, 0, patchedRender.width, patchedRender.height, patchedRender.pixels, 0, patchedRender.width)
                ImageIO.write(bi, "png", File(outDir, "brain_idle2_patched.png"))
                println("Brain Idle 2 (Phase2 tiles): ${patchedRender.width}x${patchedRender.height}")
            }
        }

        // Also render Brain Idle 1 with both to verify it doesn't break
        val brainIdle1 = smap.parseSpritemap(0xA90000 or 0xA755)
        if (brainIdle1 != null) {
            val patchedRender = smap.renderSpritemap(brainIdle1, patchedTileData, palette)
            if (patchedRender != null) {
                val bi = BufferedImage(patchedRender.width, patchedRender.height, BufferedImage.TYPE_INT_ARGB)
                bi.setRGB(0, 0, patchedRender.width, patchedRender.height, patchedRender.pixels, 0, patchedRender.width)
                ImageIO.write(bi, "png", File(outDir, "brain_idle1_patched.png"))
                println("Brain Idle 1 (Phase2 tiles): ${patchedRender.width}x${patchedRender.height}")
            }
        }
    }
}
