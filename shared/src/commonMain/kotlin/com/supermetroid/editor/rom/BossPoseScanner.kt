package com.supermetroid.editor.rom

/**
 * Generic boss pose scanner.
 *
 * Many SM bosses use large OAM spritemaps for body rendering, but the standard
 * EnemySpritemap init tracing fails because instruction lists are set up through
 * complex multi-step AI code paths or custom drawing hooks.
 *
 * Two discovery strategies:
 * 1. **Known instruction lists** (from SM decompilation) — exact addresses for bosses
 *    with custom rendering systems (Mother Brain, Torizo, Ridley, etc.)
 * 2. **AI bank scan** — fallback: scans the entire AI bank for `LDA #imm16; STA $0F92,x`
 *    patterns, with cross-contamination filtering.
 */
class BossPoseScanner(private val romParser: RomParser) {

    data class BossPose(
        val name: String,
        val spritemap: EnemySpritemap.Spritemap,
        val entryCount: Int
    )

    private val smap = EnemySpritemap(romParser)
    private val rom = romParser.getRomData()

    companion object {
        /**
         * Known instruction list addresses from the SM decompilation.
         *
         * Key: species ID
         * Value: list of (bank, pointer, label) for each known instruction list.
         * These are the SNES bank-local addresses set via SetInstrs functions.
         *
         * Sources: ~/code/super_metroid/sm/src/sm_a9.c, sm_aa.c, sm_a6.c
         */
        private data class KnownInstrList(val bank: Int, val ptr: Int, val label: String)

        private val KNOWN_INSTR_LISTS: Map<Int, List<KnownInstrList>> = mapOf(
            // Mother Brain brain (P1) — custom drawing via MotherBrain_DrawBrain
            // Bank $A9, instruction lists set via MotherBrain_SetBrainInstrs
            0xEC3F to listOf(
                KnownInstrList(0xA9, 0x9C21, "Brain Idle"),
                KnownInstrList(0xA9, 0x9C29, "Brain Hit"),
                KnownInstrList(0xA9, 0x9C39, "Brain Damaged"),
                KnownInstrList(0xA9, 0x9B7F, "Brain Phase2"),
                KnownInstrList(0xA9, 0x9C87, "Brain Death"),
                KnownInstrList(0xA9, 0x9D25, "Brain Rainbow"),
                KnownInstrList(0xA9, 0x9D3D, "Brain Revive"),
                KnownInstrList(0xA9, 0x9ECC, "Brain Walk 1"),
                KnownInstrList(0xA9, 0x9F00, "Brain Walk 2"),
                KnownInstrList(0xA9, 0x9F34, "Brain Walk 3"),
            ),
            // Mother Brain body (P2) — body instruction lists
            0xEC7F to listOf(
                KnownInstrList(0xA9, 0x9C13, "Body Init"),
                KnownInstrList(0xA9, 0x9A02, "Body Ascent"),
                KnownInstrList(0xA9, 0x99AA, "Body Stand"),
                KnownInstrList(0xA9, 0x9A42, "Body Death"),
            ),
        )
    }

    /**
     * Scan for all OAM poses for a species.
     * Uses known instruction lists if available, falls back to AI bank scanning.
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

        // Strategy 1: Use known instruction lists from decompilation
        val knownLists = KNOWN_INSTR_LISTS[speciesId]
        if (knownLists != null) {
            return scanKnownInstructionLists(knownLists, tileCount, minEntries)
        }

        // Strategy 2: Fall back to AI bank scan
        return scanAiBank(aiBank, tileCount, minEntries, maxBboxSize)
    }

    /**
     * Parse spritemaps from known instruction list addresses.
     */
    private fun scanKnownInstructionLists(
        lists: List<KnownInstrList>,
        tileCount: Int,
        minEntries: Int
    ): List<BossPose> {
        val allSpritemaps = mutableListOf<Pair<String, EnemySpritemap.Spritemap>>()
        val seenAddrs = mutableSetOf<Int>()

        for (instrList in lists) {
            val snesAddr = (instrList.bank shl 16) or instrList.ptr
            val ilPc = romParser.snesToPc(snesAddr)
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

                val smapSnes = (instrList.bank shl 16) or word1
                val parsed = smap.parseSpritemap(smapSnes) ?: continue
                if (parsed.entries.size < minEntries) continue

                // Validate tile indices within range
                val hasOutOfRange = parsed.entries.any { (it.tileNum and 0xFF) >= tileCount }
                if (hasOutOfRange) continue

                allSpritemaps.add(instrList.label to parsed)
            }
        }

        // Deduplicate by OAM entry content
        val uniquePoses = allSpritemaps.distinctBy { (_, sm) ->
            sm.entries.map { e -> "${e.tileNum}_${e.xOffset}_${e.yOffset}" }.joinToString("|")
        }

        return uniquePoses.mapIndexed { idx, (label, sm) ->
            val name = when {
                sm.entries.size >= 15 -> "$label ${idx + 1}"
                sm.entries.size >= 8 -> "$label ${idx + 1}"
                else -> "$label ${idx + 1}"
            }
            BossPose(name, sm, sm.entries.size)
        }
    }

    /**
     * Fallback: scan AI bank for STA $0F92,x patterns.
     */
    private fun scanAiBank(
        aiBank: Int,
        tileCount: Int,
        minEntries: Int,
        maxBboxSize: Int
    ): List<BossPose> {
        val instrListPtrs = scanBankForInstructionListPointers(aiBank)
        if (instrListPtrs.isEmpty()) return emptyList()

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

                // Filter: tile indices within species' range
                val hasOutOfRange = parsed.entries.any { (it.tileNum and 0xFF) >= tileCount }
                if (hasOutOfRange) continue

                // Filter: palette consistency (max 2 rows)
                val palRows = parsed.entries.map { it.palRow }.toSet()
                if (palRows.size > 2) continue

                // Optional: bounding box size filter
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

    private fun scanBankForInstructionListPointers(aiBank: Int): Set<Int> {
        val ptrs = mutableSetOf<Int>()
        val scanStart = romParser.snesToPc((aiBank shl 16) or 0x8000)
        if (scanStart < 0) return ptrs
        val scanEnd = (scanStart + 0x8000).coerceAtMost(rom.size - 6)

        for (i in scanStart until scanEnd) {
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
