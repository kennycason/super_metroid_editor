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
        val entryCount: Int,
        val frame: EnemySpritemap.RenderableFrame = EnemySpritemap.RenderableFrame.Oam(spritemap),
        val childCount: Int = 1,
        val tilemapCount: Int = 0,
        val renderOptions: EnemySpritemap.RenderOptions = EnemySpritemap.RenderOptions()
    )

    private val smap = EnemySpritemap(romParser)
    private val rom = romParser.getRomData()

    companion object {
        /** Check if a species has known instruction lists from the decompilation. */
        fun hasKnownPoses(speciesId: Int): Boolean = KNOWN_INSTR_LISTS.containsKey(speciesId)

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

        private const val SPECIES_CROCOMIRE = 0xDDBF
        private const val CROCOMIRE_BG2_ORIGIN_X = -0x33
        private const val CROCOMIRE_BG2_ORIGIN_Y = -0x43
        private const val CROCOMIRE_BG2_ENEMY_TILE_BASE = 0xD0

        private val CROCOMIRE_BG2_Y_ADJUST_FRAMES = setOf(
            0xBFC4, 0xBFF6, 0xC028, 0xC05A,
            0xC08C, 0xC0BE, 0xC0F0, 0xC122,
            0xC154, 0xC186, 0xC1B8, 0xC1EA,
            0xC47A, 0xC4AC, 0xC4DE, 0xC510,
            0xC542
        )

        private val CROCOMIRE_TAIL_TILE_OVERRIDES: Map<Pair<Int, Int>, Int> = crocomireTailTileOverrides()

        private fun crocomireTailTileOverrides(): Map<Pair<Int, Int>, Int> {
            val overrides = mutableMapOf<Pair<Int, Int>, Int>()

            fun putRun(tilemapSnesAddress: Int, tileStart: Int, rawTileStart: Int, count: Int) {
                for (i in 0 until count) {
                    overrides[tilemapSnesAddress to (tileStart + i)] =
                        CROCOMIRE_BG2_ENEMY_TILE_BASE + rawTileStart + i
                }
            }

            // Crocomire's animated tail tilemaps use raw tail strips near $20/$26/$2C,
            // while the rest of the BG2 body uses the normal physical $D0 VRAM layout.
            putRun(0xA4D852, 0x140, 0x20, 6)
            putRun(0xA4D852, 0x150, 0x30, 6)

            putRun(0xA4D876, 0x126, 0x26, 6)
            putRun(0xA4D876, 0x136, 0x36, 6)

            putRun(0xA4D89A, 0x12C, 0x2C, 4)
            overrides[0xA4D89A to 0x0C8] = CROCOMIRE_BG2_ENEMY_TILE_BASE + 0x30
            overrides[0xA4D89A to 0x0C9] = CROCOMIRE_BG2_ENEMY_TILE_BASE + 0x31
            putRun(0xA4D89A, 0x13C, 0x3C, 4)
            overrides[0xA4D89A to 0x0E7] = CROCOMIRE_BG2_ENEMY_TILE_BASE + 0x40
            overrides[0xA4D89A to 0x0E8] = CROCOMIRE_BG2_ENEMY_TILE_BASE + 0x41

            return overrides
        }

        private val KNOWN_INSTR_LISTS: Map<Int, List<KnownInstrList>> = mapOf(
            // Mother Brain brain (P1) — custom drawing via MotherBrain_DrawBrain
            // Bank $A9, instruction lists set via MotherBrain_SetBrainInstrs
            0xEC3F to listOf(
                // Brain idle/hit/damage states (Phase 1)
                KnownInstrList(0xA9, 0x9C21, "Brain Idle"),
                KnownInstrList(0xA9, 0x9C29, "Brain Hit"),
                KnownInstrList(0xA9, 0x9C39, "Brain Damaged"),
                // Brain Phase 2 states (after body rises)
                KnownInstrList(0xA9, 0x9B7F, "Brain P2 Shake"),
                KnownInstrList(0xA9, 0x9BB3, "Brain Revive"),
                KnownInstrList(0xA9, 0x9BE7, "Brain Disrupt"),
                KnownInstrList(0xA9, 0x9D7F, "Brain P3 Idle"),
                KnownInstrList(0xA9, 0x9DB1, "Brain Shitroid"),
                KnownInstrList(0xA9, 0x9DBB, "Brain Neck"),
                // Brain attack states
                KnownInstrList(0xA9, 0x9C77, "Brain Rainbow Charge"),
                KnownInstrList(0xA9, 0x9C87, "Brain Death Beam"),
                KnownInstrList(0xA9, 0x9ECC, "Brain Attack 1"),
                KnownInstrList(0xA9, 0x9F00, "Brain Attack 2"),
                KnownInstrList(0xA9, 0x9F34, "Brain Laser"),
                // Brain death/end states
                KnownInstrList(0xA9, 0x9D25, "Brain Death"),
                KnownInstrList(0xA9, 0x9D3D, "Brain Decide"),
            ),
            // Mother Brain body (P2) — walking mech body instruction lists
            0xEC7F to listOf(
                KnownInstrList(0xA9, 0x9C13, "Body Init"),
                KnownInstrList(0xA9, 0x99AA, "Body Stand"),
                KnownInstrList(0xA9, 0x99C6, "Body Stand Up"),
                KnownInstrList(0xA9, 0x99F2, "Body Crouch"),
                KnownInstrList(0xA9, 0x9A02, "Body Ascent"),
                KnownInstrList(0xA9, 0x9A0A, "Body Rise"),
                KnownInstrList(0xA9, 0x9A26, "Body Drained"),
                KnownInstrList(0xA9, 0x9A42, "Body Death Beam"),
                // Walking cycle instruction lists (forward stride variants)
                KnownInstrList(0xA9, 0x9730, "Walk Fwd 1"),
                KnownInstrList(0xA9, 0x976A, "Walk Fwd 2"),
                KnownInstrList(0xA9, 0x97A4, "Walk Fwd 3"),
                KnownInstrList(0xA9, 0x97DE, "Walk Fwd 4"),
                KnownInstrList(0xA9, 0x9818, "Walk Fwd 5"),
                KnownInstrList(0xA9, 0x9852, "Walk Bwd 1"),
                KnownInstrList(0xA9, 0x988C, "Walk Bwd 2"),
                KnownInstrList(0xA9, 0x98C6, "Walk Bwd 3"),
                KnownInstrList(0xA9, 0x9900, "Walk Bwd 4"),
                KnownInstrList(0xA9, 0x993A, "Walk Bwd 5"),
            ),
            // Crocomire — multibox extended spritemaps with BG2 tilemap children.
            // Source: bank_A4.asm InstList_Crocomire_*.
            0xDDBF to listOf(
                KnownInstrList(0xA4, 0xBADE, "Initial"),
                KnownInstrList(0xA4, 0xBBCE, "Step Forward"),
                KnownInstrList(0xA4, 0xBC30, "Step Back"),
                KnownInstrList(0xA4, 0xBC34, "Stepping Back"),
                KnownInstrList(0xA4, 0xBC56, "Wait Damage"),
                KnownInstrList(0xA4, 0xBCD8, "Moving Claws"),
                KnownInstrList(0xA4, 0xBD2A, "Roar"),
                KnownInstrList(0xA4, 0xBD8E, "Close Mouth"),
                KnownInstrList(0xA4, 0xBDAE, "Power Bomb Open"),
                KnownInstrList(0xA4, 0xBDB2, "Power Bomb Half"),
                KnownInstrList(0xA4, 0xBDB6, "Power Bomb Closed"),
            ),
            // Ridley / Ceres Ridley — extended spritemaps composed from legs,
            // hand, torso, and head/neck OAM spritemaps.
            // Source: bank_A6.asm InstList_Ridley_*.
            0xE17F to ridleyInstructionLists(),
            0xE13F to ridleyInstructionLists(),
        )

        private fun ridleyInstructionLists(): List<KnownInstrList> = listOf(
            KnownInstrList(0xA6, 0xE538, "Initial Left"),
            KnownInstrList(0xA6, 0xE542, "Initial Right"),
            KnownInstrList(0xA6, 0xE548, "Ceres Lunge Left"),
            KnownInstrList(0xA6, 0xE576, "Ceres Lunge Right"),
            KnownInstrList(0xA6, 0xE658, "Retrieve Baby Left"),
            KnownInstrList(0xA6, 0xE676, "Retrieve Baby Right"),
            KnownInstrList(0xA6, 0xE690, "Opening Roar Left"),
            KnownInstrList(0xA6, 0xE6AE, "Opening Roar Right"),
            KnownInstrList(0xA6, 0xE6C8, "Death Roar Left"),
            KnownInstrList(0xA6, 0xE6DE, "Death Roar Right"),
            KnownInstrList(0xA6, 0xE6F0, "Turn Right"),
            KnownInstrList(0xA6, 0xE706, "Turn Left"),
            KnownInstrList(0xA6, 0xE73A, "Fireball Left"),
            KnownInstrList(0xA6, 0xE7B4, "Fireball Right"),
            KnownInstrList(0xA6, 0xE7AC, "Fireball End Left"),
            KnownInstrList(0xA6, 0xE820, "Fireball End Right"),
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
            return scanKnownInstructionLists(speciesId, knownLists, minEntries)
        }

        // Strategy 2: Fall back to AI bank scan
        return scanAiBank(aiBank, tileCount, minEntries, maxBboxSize)
    }

    /**
     * Parse spritemaps from known instruction list addresses.
     */
    private fun scanKnownInstructionLists(
        speciesId: Int,
        lists: List<KnownInstrList>,
        minEntries: Int
    ): List<BossPose> {
        data class Candidate(
            val label: String,
            val spritemap: EnemySpritemap.Spritemap,
            val frame: EnemySpritemap.RenderableFrame,
            val entryCount: Int,
            val childCount: Int,
            val tilemapCount: Int,
            val renderOptions: EnemySpritemap.RenderOptions
        )

        val allFrames = mutableListOf<Candidate>()
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
                val frame = smap.parseRenderableFrame(smapSnes) ?: continue
                val parsed = smap.flattenRenderableFrame(frame)
                val entryCount = renderableEntryCount(frame, parsed)
                if (entryCount < minEntries) continue

                // For known instruction lists, use relaxed tile validation (256 max)
                // since multi-entity bosses share VRAM space (e.g., MB body uses tiles 128+
                // because brain tiles occupy 0-127)
                val maxTile = 256
                val hasOutOfRange = parsed.entries.any { (it.tileNum and 0xFF) >= maxTile }
                if (hasOutOfRange) continue

                allFrames.add(
                    Candidate(
                        label = instrList.label,
                        spritemap = parsed,
                        frame = frame,
                        entryCount = entryCount,
                        childCount = renderableChildCount(frame),
                        tilemapCount = renderableTilemapCount(frame),
                        renderOptions = renderOptionsForKnownFrame(speciesId, frame)
                    )
                )
            }
        }

        // Deduplicate by source frame address first. Extended bosses often reuse
        // the exact same frame from several instruction lists.
        val uniquePoses = allFrames.distinctBy { it.frame.snesAddress }

        return uniquePoses.mapIndexed { idx, candidate ->
            val name = when {
                candidate.entryCount >= 15 -> "${candidate.label} ${idx + 1}"
                candidate.entryCount >= 8 -> "${candidate.label} ${idx + 1}"
                else -> "${candidate.label} ${idx + 1}"
            }
            BossPose(
                name = name,
                spritemap = candidate.spritemap,
                entryCount = candidate.entryCount,
                frame = candidate.frame,
                childCount = candidate.childCount,
                tilemapCount = candidate.tilemapCount,
                renderOptions = candidate.renderOptions
            )
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
        val assembled = smap.renderRenderableFrame(pose.frame, tileData, palette, pose.renderOptions) ?: return null
        return autoCrop(assembled, pose.spritemap)
    }

    private fun renderOptionsForKnownFrame(
        speciesId: Int,
        frame: EnemySpritemap.RenderableFrame
    ): EnemySpritemap.RenderOptions {
        if (speciesId != SPECIES_CROCOMIRE || frame !is EnemySpritemap.RenderableFrame.Extended) {
            return EnemySpritemap.RenderOptions()
        }

        val framePtr = frame.spritemap.snesAddress and 0xFFFF
        val yAdjust = if (framePtr in CROCOMIRE_BG2_Y_ADJUST_FRAMES) {
            readS16OrZero(frame.spritemap.snesAddress + 0x1C)
        } else {
            0
        }

        return EnemySpritemap.RenderOptions(
            normalizeExtendedTilemaps = false,
            extendedTilemapOriginX = CROCOMIRE_BG2_ORIGIN_X,
            extendedTilemapOriginY = CROCOMIRE_BG2_ORIGIN_Y - yAdjust,
            wrapExtendedTilemapTilePage = false,
            extendedTilemapsBehindOam = true,
            oamTileNumberMode = EnemySpritemap.OamTileNumberMode.LOW_9,
            extendedTilemapTileNumberOverrides = CROCOMIRE_TAIL_TILE_OVERRIDES
        )
    }

    private fun renderableEntryCount(
        frame: EnemySpritemap.RenderableFrame,
        flattened: EnemySpritemap.Spritemap
    ): Int {
        return when (frame) {
            is EnemySpritemap.RenderableFrame.Oam -> flattened.entries.size
            is EnemySpritemap.RenderableFrame.Extended ->
                flattened.entries.size + frame.spritemap.children
                    .filterIsInstance<EnemySpritemap.ExtendedChild.Tilemap>()
                    .sumOf { it.tilemap.tileCount }
        }
    }

    private fun renderableChildCount(frame: EnemySpritemap.RenderableFrame): Int {
        return when (frame) {
            is EnemySpritemap.RenderableFrame.Oam -> 1
            is EnemySpritemap.RenderableFrame.Extended -> frame.spritemap.children.size
        }
    }

    private fun renderableTilemapCount(frame: EnemySpritemap.RenderableFrame): Int {
        return when (frame) {
            is EnemySpritemap.RenderableFrame.Oam -> 0
            is EnemySpritemap.RenderableFrame.Extended ->
                frame.spritemap.children.count { it is EnemySpritemap.ExtendedChild.Tilemap }
        }
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

    private fun readS16OrZero(snesAddr: Int): Int {
        val pc = romParser.snesToPc(snesAddr)
        if (pc < 0 || pc + 2 > rom.size) return 0
        val word = readU16(rom, pc)
        return if (word >= 0x8000) word - 0x10000 else word
    }
}
