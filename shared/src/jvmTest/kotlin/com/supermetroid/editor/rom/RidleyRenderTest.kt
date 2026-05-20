package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Render Ridley's large OAM spritemaps to verify body assembly.
 */
class RidleyRenderTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `render Ridley poses via RidleySpritemap`() {
        val rp = loadTestRom() ?: return
        val ridley = RidleySpritemap(rp)
        val speciesId = RidleySpritemap.RIDLEY_SPECIES_ID

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return

        val poses = ridley.loadPoses()
        println("Found ${poses.size} Ridley poses:")

        val outDir = File("/tmp/ridley_poses")
        outDir.mkdirs()

        for ((i, pose) in poses.withIndex()) {
            val assembled = ridley.renderPose(pose, tileData, palette)
            if (assembled == null) {
                println("  ${pose.name}: FAILED to render")
                continue
            }

            val filled = assembled.pixels.count { (it ushr 24) > 0 }
            val fillPct = (filled * 100) / (assembled.width * assembled.height)
            println("  ${pose.name}: ${pose.entryCount} OAM, ${assembled.width}x${assembled.height}, fill=$fillPct%")

            val bi = BufferedImage(assembled.width, assembled.height, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, assembled.width, assembled.height, assembled.pixels, 0, assembled.width)
            val fileName = "ridley_pose${i}_${pose.name.replace(' ', '_').lowercase()}_${assembled.width}x${assembled.height}.png"
            ImageIO.write(bi, "png", File(outDir, fileName))
        }

        assert(poses.isNotEmpty()) { "Should find at least one Ridley pose" }
    }

    @Test
    fun `render Ridley large spritemaps raw`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val speciesId = 0xE17F

        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: return
        val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: return

        // AI bank from species header
        val rom = rp.getRomData()
        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF

        println("Ridley AI bank: \$${aiBank.toString(16).uppercase()}")
        println("Tile data: ${tileData.size} bytes (${tileData.size / 32} tiles)")

        // Known large spritemap addresses from diagnostic
        val largeSpritemapAddrs = listOf(
            0x03D0 to "full_body_37entries",
            0xA932 to "body_pose2_32entries",
            0x9F00 to "wings_20entries",
        )

        val outDir = File("/tmp/ridley_renders")
        outDir.mkdirs()

        for ((addr, label) in largeSpritemapAddrs) {
            val snesAddr = (aiBank shl 16) or addr
            val parsed = smap.parseSpritemap(snesAddr)
            if (parsed == null) {
                println("  $label at \$${snesAddr.toString(16).uppercase()}: FAILED to parse")
                continue
            }

            println("  $label at \$${snesAddr.toString(16).uppercase()}: ${parsed.entries.size} OAM entries")

            val assembled = smap.renderSpritemap(parsed, tileData, palette)
            if (assembled == null) {
                println("    FAILED to render")
                continue
            }

            println("    Size: ${assembled.width}x${assembled.height}")
            val filled = assembled.pixels.count { (it ushr 24) > 0 }
            val fillPct = (filled * 100) / (assembled.width * assembled.height)
            println("    Fill: $fillPct%")

            val bi = BufferedImage(assembled.width, assembled.height, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, assembled.width, assembled.height, assembled.pixels, 0, assembled.width)
            ImageIO.write(bi, "png", File(outDir, "ridley_$label.png"))
            println("    Saved to /tmp/ridley_renders/ridley_$label.png")
        }

        // Also scan for ALL spritemaps with 10+ entries in the AI bank
        println("\nScanning AI bank \$${aiBank.toString(16).uppercase()} for spritemaps with 10+ entries...")
        val bankBase = aiBank shl 16
        val bankPcStart = rp.snesToPc(bankBase or 0x8000)
        val bankPcEnd = rp.snesToPc(bankBase or 0xFFFF)

        val foundSmaps = mutableListOf<Triple<Int, Int, EnemySpritemap.Spritemap>>()

        for (offset in 0x8000..0xFFF0 step 2) {
            val pc = rp.snesToPc(bankBase or offset)
            if (pc < 0 || pc + 2 >= rom.size) continue

            val count = (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
            if (count !in 10..64) continue
            if (pc + 2 + count * 5 > rom.size) continue

            val parsed = smap.parseSpritemap(bankBase or offset)
            if (parsed != null && parsed.entries.size >= 10) {
                // Validate tile indices are within Ridley's 256-tile range
                val maxTile = parsed.entries.maxOf { it.tileNum and 0xFF }
                if (maxTile < 256) {
                    // Check bounding box is reasonable
                    val minX = parsed.entries.minOf { it.xOffset }
                    val maxX = parsed.entries.maxOf { it.xOffset + if (it.is16x16) 16 else 8 }
                    val minY = parsed.entries.minOf { it.yOffset }
                    val maxY = parsed.entries.maxOf { it.yOffset + if (it.is16x16) 16 else 8 }
                    val w = maxX - minX
                    val h = maxY - minY
                    if (w in 16..600 && h in 16..600) {
                        foundSmaps.add(Triple(offset, parsed.entries.size, parsed))
                    }
                }
            }
        }

        println("Found ${foundSmaps.size} spritemaps with 10+ entries:")
        val uniqueBySize = foundSmaps.distinctBy { "${it.second}_${it.third.entries.map { e -> e.tileNum }.hashCode()}" }

        for ((offset, entryCount, parsed) in uniqueBySize.sortedByDescending { it.second }.take(15)) {
            val assembled = smap.renderSpritemap(parsed, tileData, palette) ?: continue
            val filled = assembled.pixels.count { (it ushr 24) > 0 }
            val fillPct = (filled * 100) / (assembled.width * assembled.height)
            val hexAddr = "\$${aiBank.toString(16).uppercase()}:${offset.toString(16).uppercase().padStart(4, '0')}"
            println("  $hexAddr: $entryCount entries, ${assembled.width}x${assembled.height}, fill=$fillPct%")

            if (entryCount >= 15 && fillPct >= 5) {
                val bi = BufferedImage(assembled.width, assembled.height, BufferedImage.TYPE_INT_ARGB)
                bi.setRGB(0, 0, assembled.width, assembled.height, assembled.pixels, 0, assembled.width)
                ImageIO.write(bi, "png", File(outDir, "ridley_scan_${offset.toString(16)}_${entryCount}e.png"))
            }
        }
    }
}
