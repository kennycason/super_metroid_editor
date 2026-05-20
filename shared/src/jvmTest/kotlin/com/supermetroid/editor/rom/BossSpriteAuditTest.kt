package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Deep audit of boss sprite tile mapping to understand scrambling.
 * Uses SM decompilation findings to verify VRAM mapping.
 */
class BossSpriteAuditTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `audit which species share AI banks`() {
        val rp = loadTestRom() ?: return
        val rom = rp.getRomData()

        val bankToSpecies = mutableMapOf<Int, MutableList<String>>()

        val speciesToCheck = listOf(
            0xE17F to "Ridley", 0xE13F to "Ceres Ridley",
            0xDDBF to "Crocomire", 0xDE3F to "Draygon",
            0xEC3F to "MB P1", 0xEC7F to "MB P2",
            0xEEBF to "Big Metroid", 0xDF3F to "Spore Spawn",
            0xF293 to "Botwoon", 0xE0FF to "Mini Kraid",
            0xEEFF to "Torizo", 0xEF7F to "Golden Torizo",
            0xE2BF to "Kraid", 0xE4BF to "Phantoon",
        )

        for ((speciesId, name) in speciesToCheck) {
            val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            if (headerPc < 0 || headerPc + 0x3A > rom.size) continue
            val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
            val rawTileSize = readU16(rom, headerPc)
            val tileCount = (rawTileSize and 0x7FFF) / 32
            bankToSpecies.getOrPut(aiBank) { mutableListOf() }
                .add("$name(\$${speciesId.toString(16).uppercase()}, ${tileCount}tiles)")
        }

        println("AI Bank sharing (explains cross-contamination of spritemaps):")
        for ((bank, species) in bankToSpecies.toSortedMap()) {
            println("  Bank \$${bank.toString(16).uppercase()}: ${species.joinToString(", ")}")
        }
    }

    @Test
    fun `audit all bosses tile numbers vs tile count and name table bits`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val rom = rp.getRomData()

        val bosses = listOf(
            0xE17F to "Ridley",
            0xDDBF to "Crocomire",
            0xEC3F to "Mother Brain P1",
            0xEEBF to "Big Metroid",
            0xEEFF to "Torizo",
            0xEF7F to "Golden Torizo",
            0xF293 to "Botwoon",
            0xE0FF to "Mini Kraid",
            0xDE3F to "Draygon",
            0xDF3F to "Spore Spawn",
        )

        println("%-18s %5s %5s %5s %5s %5s %s".format(
            "Boss", "Tiles", "Poses", "NT0", "NT1", "OOR", "Notes"))
        println("-".repeat(80))

        for ((speciesId, name) in bosses) {
            val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
            if (headerPc < 0) continue
            val rawTileSize = readU16(rom, headerPc)
            val tileCount = (rawTileSize and 0x7FFF) / 32
            val bit15 = (rawTileSize and 0x8000) != 0

            val poses = scanner.scanPoses(speciesId, minEntries = 3)

            var nt0Count = 0
            var nt1Count = 0
            var oorCount = 0

            for (pose in poses) {
                for (entry in pose.spritemap.entries) {
                    val localTile = entry.tileNum and 0xFF
                    val nameTable = (entry.tileNum shr 8) and 1

                    if (nameTable == 1) nt1Count++
                    else nt0Count++

                    if (localTile >= tileCount) oorCount++
                }
            }

            val notes = mutableListOf<String>()
            if (bit15) notes.add("bit15_set")
            if (nt1Count > 0) notes.add("USES_NAME_TABLE_1")
            if (oorCount > 0) notes.add("OUT_OF_RANGE_TILES")

            println("%-18s %5d %5d %5d %5d %5d %s".format(
                name, tileCount, poses.size, nt0Count, nt1Count, oorCount,
                notes.joinToString(", ")))
        }
    }

    @Test
    fun `detailed Ridley pose tile analysis`() {
        val rp = loadTestRom() ?: return
        val scanner = BossPoseScanner(rp)
        val rom = rp.getRomData()
        val speciesId = 0xE17F

        val headerPc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
        val rawTileSize = readU16(rom, headerPc)
        val tileCount = (rawTileSize and 0x7FFF) / 32

        println("Ridley: bank=\$${aiBank.toString(16).uppercase()}, tiles=$tileCount, bit15=${(rawTileSize and 0x8000) != 0}")

        val poses = scanner.scanPoses(speciesId, minEntries = 3)

        for ((i, pose) in poses.withIndex()) {
            val entries = pose.spritemap.entries
            val tileNums = entries.map { it.tileNum and 0xFF }
            val nt1Entries = entries.count { (it.tileNum shr 8) and 1 == 1 }
            val palRows = entries.map { it.palRow }.toSet()
            val maxTile = tileNums.max()
            val minTile = tileNums.min()

            // Calculate bounding box
            val minX = entries.minOf { it.xOffset }
            val maxX = entries.maxOf { it.xOffset + if (it.is16x16) 16 else 8 }
            val minY = entries.minOf { it.yOffset }
            val maxY = entries.maxOf { it.yOffset + if (it.is16x16) 16 else 8 }
            val bboxW = maxX - minX
            val bboxH = maxY - minY

            val isCompact = bboxW <= 128 && bboxH <= 128

            println("  [%2d] %-15s %3d entries, tiles=%3d..%3d, nt1=%d, pal=%s, bbox=%dx%d %s".format(
                i, pose.name, entries.size, minTile, maxTile, nt1Entries,
                palRows, bboxW, bboxH, if (isCompact) "COMPACT" else "SCATTERED"
            ))
        }
    }

    @Test
    fun `render boss audit with name table awareness`() {
        val rp = loadTestRom() ?: return
        val smap = EnemySpritemap(rp)
        val scanner = BossPoseScanner(rp)
        val outDir = File("/tmp/boss_audit_v2")
        outDir.mkdirs()

        val bosses = listOf(
            0xE17F to "Ridley",
            0xDDBF to "Crocomire",
            0xEC3F to "MotherBrain_P1",
            0xEEBF to "BigMetroid",
            0xEEFF to "Torizo",
            0xEF7F to "GoldenTorizo",
            0xF293 to "Botwoon",
            0xDE3F to "Draygon",
        )

        for ((speciesId, name) in bosses) {
            val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId) ?: continue
            val tileData = EnemySpriteGraphics.loadEnemyTileData(rp, speciesId) ?: continue

            val poses = scanner.scanPoses(speciesId, minEntries = 3)

            for ((i, pose) in poses.take(5).withIndex()) {
                val rendered = scanner.renderPose(pose, tileData, palette) ?: continue
                val bi = BufferedImage(rendered.width, rendered.height, BufferedImage.TYPE_INT_ARGB)
                bi.setRGB(0, 0, rendered.width, rendered.height, rendered.pixels, 0, rendered.width)
                ImageIO.write(bi, "png",
                    File(outDir, "${name}_pose${i}_${pose.entryCount}oam_${rendered.width}x${rendered.height}.png"))
            }
        }
        println("Rendered poses to $outDir")
    }
}
