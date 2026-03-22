package com.supermetroid.editor.rom

/**
 * Generic boss pose scanner.
 *
 * Many SM bosses (Ridley, Crocomire, Draygon, Torizo, Spore Spawn, etc.)
 * use large OAM spritemaps for their body rendering, but the standard
 * EnemySpritemap init tracing fails to find them because the instruction
 * lists are set up through complex multi-step AI code paths.
 *
 * This scanner finds ALL instruction list pointers in a boss's AI bank
 * by scanning for the `LDA #imm16; STA $0F92,x` assembly pattern, then
 * extracts all unique spritemaps with meaningful OAM entry counts.
 *
 * Works automatically for any boss — no per-species hardcoding needed.
 */
class BossPoseScanner(private val romParser: RomParser) {

    data class BossPose(
        val name: String,
        val spritemap: EnemySpritemap.Spritemap,
        val entryCount: Int
    )

    private val smap = EnemySpritemap(romParser)
    private val rom = romParser.getRomData()

    /**
     * Scan for all OAM poses for a species by searching its AI bank.
     *
     * @param speciesId    Enemy species ID
     * @param minEntries   Minimum OAM entries for a pose to be included (default: 3)
     * @param maxBboxSize  Maximum bounding box dimension to filter out scattered full-body maps
     * @return Sorted list of poses (highest entry count first), auto-cropped
     */
    fun scanPoses(
        speciesId: Int,
        minEntries: Int = 3,
        maxBboxSize: Int = 0
    ): List<BossPose> {
        val headerPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        if (headerPc < 0 || headerPc + 0x3A > rom.size) return emptyList()

        val aiBank = rom[headerPc + 0x0C].toInt() and 0xFF
        val rawTileSize = readU16(rom, headerPc)
        val tileCount = (rawTileSize and 0x7FFF) / 32

        // Find all instruction list pointers in the AI bank
        val instrListPtrs = scanBankForInstructionListPointers(aiBank)
        if (instrListPtrs.isEmpty()) return emptyList()

        // Parse all unique spritemaps from all instruction lists
        val allSpritemaps = mutableListOf<EnemySpritemap.Spritemap>()
        val seenAddrs = mutableSetOf<Int>()

        for (ptr in instrListPtrs) {
            val ilPc = romParser.snesToPc((aiBank shl 16) or ptr)
            if (ilPc < 0) continue

            var offset = 0
            for (f in 0 until 64) {
                if (ilPc + offset + 3 >= rom.size) break
                val word0 = readU16(rom, ilPc + offset)
                val word1 = readU16(rom, ilPc + offset + 2)
                offset += 4

                if (word0 == 0 && word1 == 0) break
                if (word0 == 0x8000) break
                if (word0 >= 0x8000) continue

                if (word1 in seenAddrs) continue
                seenAddrs.add(word1)

                val smapSnes = (aiBank shl 16) or word1
                val parsed = smap.parseSpritemap(smapSnes) ?: continue
                if (parsed.entries.size < minEntries) continue

                // Filter out spritemaps from OTHER enemies sharing this AI bank:
                // 1. All tile indices (8-bit) must be within this species' tile count
                val hasOutOfRange = parsed.entries.any { (it.tileNum and 0xFF) >= tileCount }
                if (hasOutOfRange) continue

                // 2. Palette consistency: real single-enemy poses use at most 2 palette rows.
                //    Cross-contaminated spritemaps mix entries from multiple enemies with
                //    different palettes (3+ distinct rows).
                val palRows = parsed.entries.map { it.palRow }.toSet()
                if (palRows.size > 2) continue

                // 3. Optional: filter by bounding box size
                if (maxBboxSize > 0) {
                    val minX = parsed.entries.minOf { it.xOffset }
                    val maxX = parsed.entries.maxOf { it.xOffset + if (it.is16x16) 16 else 8 }
                    val minY = parsed.entries.minOf { it.yOffset }
                    val maxY = parsed.entries.maxOf { it.yOffset + if (it.is16x16) 16 else 8 }
                    if (maxX - minX > maxBboxSize || maxY - minY > maxBboxSize) continue
                }

                allSpritemaps.add(parsed)
            }
        }

        // Deduplicate by OAM entry content
        val uniquePoses = allSpritemaps.distinctBy { sm ->
            sm.entries.map { e -> "${e.tileNum}_${e.xOffset}_${e.yOffset}" }.joinToString("|")
        }

        return uniquePoses
            .sortedByDescending { it.entries.size }
            .mapIndexed { idx, sm ->
                val name = when {
                    sm.entries.size >= 30 -> "Full Body ${idx + 1}"
                    sm.entries.size >= 15 -> "Body Pose ${idx + 1}"
                    sm.entries.size >= 8 -> "Pose ${idx + 1}"
                    else -> "Detail ${idx + 1}"
                }
                BossPose(name, sm, sm.entries.size)
            }
    }

    /**
     * Render a pose with auto-cropping to remove empty space.
     */
    fun renderPose(
        pose: BossPose,
        tileData: ByteArray,
        palette: IntArray
    ): EnemySpritemap.AssembledSprite? {
        val assembled = smap.renderSpritemap(pose.spritemap, tileData, palette) ?: return null
        return autoCrop(assembled, pose.spritemap)
    }

    /**
     * Auto-crop an assembled sprite to its non-transparent bounding box.
     */
    private fun autoCrop(
        assembled: EnemySpritemap.AssembledSprite,
        spritemap: EnemySpritemap.Spritemap
    ): EnemySpritemap.AssembledSprite? {
        var cropMinX = assembled.width
        var cropMaxX = 0
        var cropMinY = assembled.height
        var cropMaxY = 0

        for (y in 0 until assembled.height) {
            for (x in 0 until assembled.width) {
                if ((assembled.pixels[y * assembled.width + x] ushr 24) > 0) {
                    cropMinX = minOf(cropMinX, x)
                    cropMaxX = maxOf(cropMaxX, x)
                    cropMinY = minOf(cropMinY, y)
                    cropMaxY = maxOf(cropMaxY, y)
                }
            }
        }

        if (cropMaxX < cropMinX || cropMaxY < cropMinY) return null

        val pad = 2
        cropMinX = (cropMinX - pad).coerceAtLeast(0)
        cropMinY = (cropMinY - pad).coerceAtLeast(0)
        cropMaxX = (cropMaxX + pad).coerceAtMost(assembled.width - 1)
        cropMaxY = (cropMaxY + pad).coerceAtMost(assembled.height - 1)

        val cropW = cropMaxX - cropMinX + 1
        val cropH = cropMaxY - cropMinY + 1
        val cropped = IntArray(cropW * cropH)

        for (y in 0 until cropH) {
            for (x in 0 until cropW) {
                cropped[y * cropW + x] = assembled.pixels[(y + cropMinY) * assembled.width + (x + cropMinX)]
            }
        }

        return EnemySpritemap.AssembledSprite(
            cropW, cropH, cropped,
            assembled.originX - cropMinX,
            assembled.originY - cropMinY,
            spritemap
        )
    }

    /**
     * Scan an entire AI bank for LDA #imm16; STA $0F92,x patterns.
     */
    private fun scanBankForInstructionListPointers(aiBank: Int): Set<Int> {
        val ptrs = mutableSetOf<Int>()
        val scanStart = romParser.snesToPc((aiBank shl 16) or 0x8000)
        if (scanStart < 0) return ptrs
        val scanEnd = (scanStart + 0x8000).coerceAtMost(rom.size - 6)

        for (i in scanStart until scanEnd) {
            // A9 xx xx 9D 92 0F = LDA #imm16; STA $0F92,x
            if (rom[i].toInt() and 0xFF == 0xA9 &&
                rom[i + 3].toInt() and 0xFF == 0x9D &&
                rom[i + 4].toInt() and 0xFF == 0x92 &&
                rom[i + 5].toInt() and 0xFF == 0x0F) {
                ptrs.add(readU16(rom, i + 1))
            }
        }
        return ptrs
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
