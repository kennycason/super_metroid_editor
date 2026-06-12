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

    enum class TileDataVariant {
        DEFAULT,
        CROCOMIRE_SKELETON
    }

    data class BossPose(
        val name: String,
        val spritemap: EnemySpritemap.Spritemap,
        val entryCount: Int,
        val frame: EnemySpritemap.RenderableFrame = EnemySpritemap.RenderableFrame.Oam(spritemap),
        val childCount: Int = 1,
        val tilemapCount: Int = 0,
        val renderOptions: EnemySpritemap.RenderOptions = EnemySpritemap.RenderOptions(),
        val durationTicks: Int = 1,
        val tileDataVariant: TileDataVariant = TileDataVariant.DEFAULT,
        val usesCrocomireBgTileData: Boolean = false,
        val usesDraygonBgTileData: Boolean = false,
        val usesMotherBrainBgTileData: Boolean = false,
        val compositeParts: List<CompositePart> = emptyList()
    )

    data class CompositePart(
        val frame: EnemySpritemap.RenderableFrame,
        val renderOptions: EnemySpritemap.RenderOptions = EnemySpritemap.RenderOptions()
    )

    private val smap = EnemySpritemap(romParser)
    private val rom = romParser.getRomData()
    private var crocomireRoomTileDataCache: ByteArray? = null
    private var draygonRoomTileDataCache: ByteArray? = null
    private var motherBrainRoomTileDataCache: ByteArray? = null

    companion object {
        /** Check if a species has known instruction lists from the decompilation. */
        fun hasKnownPoses(speciesId: Int): Boolean =
            speciesId == SPECIES_DRAYGON_BODY || KNOWN_INSTR_LISTS.containsKey(speciesId)

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
        private const val SPECIES_MOTHER_BRAIN_BODY = 0xEC7F
        private const val SPECIES_DRAYGON_BODY = 0xDE3F
        private const val SPECIES_DRAYGON_EYE = 0xDE7F
        private const val SPECIES_DRAYGON_TAIL = 0xDEBF
        private const val SPECIES_DRAYGON_ARMS = 0xDEFF
        private val DRAYGON_SPECIES_IDS = setOf(
            SPECIES_DRAYGON_BODY,
            SPECIES_DRAYGON_EYE,
            SPECIES_DRAYGON_TAIL,
            SPECIES_DRAYGON_ARMS
        )
        private const val CROCOMIRE_BG2_ORIGIN_X = -0x33
        private const val CROCOMIRE_BG2_ORIGIN_Y = -0x43
        private const val DRAYGON_BG2_ORIGIN_X = -0x3E
        private const val DRAYGON_BG2_ORIGIN_Y = -0x40
        private const val INSTRUCTION_COMMON_GOTO_Y = 0x80ED
        private const val INSTRUCTION_COMMON_SLEEP = 0x812F
        private val CROCOMIRE_SKELETON_INSTR_LISTS = setOf(0xE14A, 0xE158, 0xE1C6, 0xE1CC, 0xE1D2)

        private val CROCOMIRE_BG2_Y_ADJUST_FRAMES = setOf(
            0xBFC4, 0xBFF6, 0xC028, 0xC05A,
            0xC08C, 0xC0BE, 0xC0F0, 0xC122,
            0xC154, 0xC186, 0xC1B8, 0xC1EA,
            0xC47A, 0xC4AC, 0xC4DE, 0xC510,
            0xC542
        )

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
                KnownInstrList(0xA4, 0xE14A, "Skeleton Falling"),
                KnownInstrList(0xA4, 0xE158, "Skeleton Collapse"),
                KnownInstrList(0xA4, 0xE1C6, "Skeleton Apart"),
                KnownInstrList(0xA4, 0xE1CC, "Skeleton Dead"),
                KnownInstrList(0xA4, 0xE1D2, "Skeleton River"),
            ),
            // Ridley / Ceres Ridley — extended spritemaps composed from legs,
            // hand, torso, and head/neck OAM spritemaps.
            // Source: bank_A6.asm InstList_Ridley_*.
            0xE17F to ridleyInstructionLists(),
            0xE13F to ridleyInstructionLists(),
            // Draygon sub-entities. The body is handled by a composite scanner
            // because vanilla sets body/eye/tail/arms instruction lists together.
            SPECIES_DRAYGON_EYE to draygonEyeInstructionLists(),
            SPECIES_DRAYGON_TAIL to draygonTailInstructionLists(),
            SPECIES_DRAYGON_ARMS to draygonArmsInstructionLists(),
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

        private fun draygonEyeInstructionLists(): List<KnownInstrList> = listOf(
            KnownInstrList(0xA5, 0x9944, "Eye Left Idle"),
            KnownInstrList(0xA5, 0x997A, "Eye Left Dying"),
            KnownInstrList(0xA5, 0x999C, "Eye Left Dead"),
            KnownInstrList(0xA5, 0x99AE, "Eye Left Looking Left"),
            KnownInstrList(0xA5, 0x99B4, "Eye Left Looking Right"),
            KnownInstrList(0xA5, 0x99BA, "Eye Left Looking Up"),
            KnownInstrList(0xA5, 0x99C0, "Eye Left Looking Down"),
            KnownInstrList(0xA5, 0x9CD6, "Eye Right Idle"),
            KnownInstrList(0xA5, 0x9D1C, "Eye Right Dying"),
            KnownInstrList(0xA5, 0x9D3E, "Eye Right Dead"),
            KnownInstrList(0xA5, 0x9D50, "Eye Right Looking Right"),
            KnownInstrList(0xA5, 0x9D56, "Eye Right Looking Left"),
            KnownInstrList(0xA5, 0x9D5C, "Eye Right Looking Up"),
            KnownInstrList(0xA5, 0x9D62, "Eye Right Looking Down"),
        )

        private fun draygonTailInstructionLists(): List<KnownInstrList> = listOf(
            KnownInstrList(0xA5, 0x99C6, "Tail Left Idle"),
            KnownInstrList(0xA5, 0x99FC, "Tail Left Fake Whip"),
            KnownInstrList(0xA5, 0x9A68, "Tail Left Final Whips"),
            KnownInstrList(0xA5, 0x9AE8, "Tail Left Whip"),
            KnownInstrList(0xA5, 0x9B5A, "Tail Left Flail"),
            KnownInstrList(0xA5, 0x9D68, "Tail Right Idle"),
            KnownInstrList(0xA5, 0x9D9E, "Tail Right Fake Whip"),
            KnownInstrList(0xA5, 0x9E21, "Tail Right Final Whips"),
            KnownInstrList(0xA5, 0x9EA1, "Tail Right Whip"),
            KnownInstrList(0xA5, 0x9F15, "Tail Right Flail"),
        )

        private fun draygonArmsInstructionLists(): List<KnownInstrList> = listOf(
            KnownInstrList(0xA5, 0x97E7, "Arms Left Idle"),
            KnownInstrList(0xA5, 0x9813, "Arms Left Near Apex"),
            KnownInstrList(0xA5, 0x9825, "Arms Left Fake Grab"),
            KnownInstrList(0xA5, 0x9845, "Arms Left Grab"),
            KnownInstrList(0xA5, 0x9867, "Arms Left Dying"),
            KnownInstrList(0xA5, 0x9BDA, "Arms Right Idle"),
            KnownInstrList(0xA5, 0x9C06, "Arms Right Near Apex"),
            KnownInstrList(0xA5, 0x9C18, "Arms Right Fake Grab"),
            KnownInstrList(0xA5, 0x9C38, "Arms Right Grab"),
            KnownInstrList(0xA5, 0x9C5A, "Arms Right Dying"),
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

        if (speciesId == SPECIES_DRAYGON_BODY) {
            return scanDraygonCompositePoses(minEntries)
        }

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
            val renderOptions: EnemySpritemap.RenderOptions,
            val durationTicks: Int,
            val tileDataVariant: TileDataVariant,
            val usesCrocomireBgTileData: Boolean,
            val usesDraygonBgTileData: Boolean,
            val usesMotherBrainBgTileData: Boolean
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
                if (word0 == INSTRUCTION_COMMON_GOTO_Y || word0 == INSTRUCTION_COMMON_SLEEP) break
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
                        renderOptions = renderOptionsForKnownFrame(speciesId, frame),
                        durationTicks = word0,
                        tileDataVariant = tileDataVariantForKnownFrame(speciesId, instrList),
                        usesCrocomireBgTileData = speciesId == SPECIES_CROCOMIRE &&
                            frame is EnemySpritemap.RenderableFrame.Extended,
                        usesDraygonBgTileData = speciesId in DRAYGON_SPECIES_IDS &&
                            frame is EnemySpritemap.RenderableFrame.Extended,
                        usesMotherBrainBgTileData = speciesId == SPECIES_MOTHER_BRAIN_BODY &&
                            frame is EnemySpritemap.RenderableFrame.Extended
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
                renderOptions = candidate.renderOptions,
                durationTicks = candidate.durationTicks,
                tileDataVariant = candidate.tileDataVariant,
                usesCrocomireBgTileData = candidate.usesCrocomireBgTileData,
                usesDraygonBgTileData = candidate.usesDraygonBgTileData,
                usesMotherBrainBgTileData = candidate.usesMotherBrainBgTileData
            )
        }
    }

    private fun scanDraygonCompositePoses(minEntries: Int): List<BossPose> {
        val poses = mutableListOf<BossPose>()

        val leftEyeIdle = intArrayOf(
            0xA5A36B, 0xA5A375, 0xA5A37F, 0xA5A375,
            0xA5A393, 0xA5A393, 0xA5A3A7, 0xA5A3A7,
            0xA5A3B1, 0xA5A37F, 0xA5A375, 0xA5A36B
        )
        val leftTailIdle = intArrayOf(
            0xA5A40B, 0xA5A41D, 0xA5A42F, 0xA5A441,
            0xA5A453, 0xA5A465, 0xA5A477, 0xA5A465,
            0xA5A453, 0xA5A441, 0xA5A42F, 0xA5A41D
        )
        val leftArmsIdle = intArrayOf(
            0xA5A2DF, 0xA5A2E9, 0xA5A2F3, 0xA5A2FD, 0xA5A307, 0xA5A311
        )

        val rightEyeIdle = intArrayOf(
            0xA5A693, 0xA5A69D, 0xA5A6A7, 0xA5A69D,
            0xA5A6BB, 0xA5A6BB, 0xA5A6CF, 0xA5A6CF,
            0xA5A6D9, 0xA5A6A7, 0xA5A69D, 0xA5A693
        )
        val rightTailIdle = intArrayOf(
            0xA5A779, 0xA5A78B, 0xA5A79D, 0xA5A7AF,
            0xA5A7C1, 0xA5A7D3, 0xA5A7E5, 0xA5A7D3,
            0xA5A7C1, 0xA5A7AF, 0xA5A79D, 0xA5A78B
        )
        val rightArmsIdle = intArrayOf(
            0xA5A607, 0xA5A611, 0xA5A61B, 0xA5A625, 0xA5A62F, 0xA5A639
        )

        for (i in leftEyeIdle.indices) {
            createDraygonCompositePose(
                label = "Idle Left ${i + 1}",
                durationTicks = if (i == 0) 8 else 6,
                frameAddresses = listOf(
                    0xA5A3BB,
                    leftEyeIdle[i],
                    leftTailIdle[i],
                    leftArmsIdle[i % leftArmsIdle.size]
                ),
                minEntries = minEntries
            )?.let { poses.add(it) }
        }

        for (i in rightEyeIdle.indices) {
            createDraygonCompositePose(
                label = "Idle Right ${i + 1}",
                durationTicks = if (i == 0) 8 else 6,
                frameAddresses = listOf(
                    0xA5A6E3,
                    rightEyeIdle[i],
                    rightTailIdle[i],
                    rightArmsIdle[i % rightArmsIdle.size]
                ),
                minEntries = minEntries
            )?.let { poses.add(it) }
        }

        val leftMouth = intArrayOf(0xA5A343, 0xA5A34D, 0xA5A357, 0xA5A361, 0xA5A357, 0xA5A34D, 0xA5A343)
        for (i in leftMouth.indices) {
            createDraygonCompositePose(
                label = "Roar Left ${i + 1}",
                durationTicks = 6,
                frameAddresses = listOf(
                    0xA5A3BB,
                    leftMouth[i],
                    leftEyeIdle[0],
                    leftTailIdle[0],
                    leftArmsIdle[0]
                ),
                minEntries = minEntries
            )?.let { poses.add(it) }
        }

        val rightMouth = intArrayOf(0xA5A66B, 0xA5A675, 0xA5A67F, 0xA5A689, 0xA5A67F, 0xA5A675, 0xA5A66B)
        for (i in rightMouth.indices) {
            createDraygonCompositePose(
                label = "Roar Right ${i + 1}",
                durationTicks = 6,
                frameAddresses = listOf(
                    0xA5A6E3,
                    rightMouth[i],
                    rightEyeIdle[0],
                    rightTailIdle[0],
                    rightArmsIdle[0]
                ),
                minEntries = minEntries
            )?.let { poses.add(it) }
        }

        val leftTailWhip = intArrayOf(
            0xA5A42F, 0xA5A489, 0xA5A4A3, 0xA5A4C5,
            0xA5A4EF, 0xA5A521, 0xA5A55B, 0xA5A59D,
            0xA5A55B, 0xA5A521, 0xA5A4EF, 0xA5A4C5, 0xA5A4A3, 0xA5A489
        )
        for (i in leftTailWhip.indices) {
            createDraygonCompositePose(
                label = "Tail Whip Left ${i + 1}",
                durationTicks = if (i < 7) maxOf(1, 7 - i) else minOf(6, i - 6),
                frameAddresses = listOf(
                    0xA5A3BB,
                    leftEyeIdle[0],
                    leftTailWhip[i],
                    leftArmsIdle[0]
                ),
                minEntries = minEntries
            )?.let { poses.add(it) }
        }

        val rightTailWhip = intArrayOf(
            0xA5A79D, 0xA5A7F7, 0xA5A811, 0xA5A833,
            0xA5A85D, 0xA5A88F, 0xA5A8C9, 0xA5A90B,
            0xA5A8C9, 0xA5A88F, 0xA5A85D, 0xA5A833, 0xA5A811, 0xA5A7F7
        )
        for (i in rightTailWhip.indices) {
            createDraygonCompositePose(
                label = "Tail Whip Right ${i + 1}",
                durationTicks = if (i < 7) maxOf(1, 7 - i) else minOf(6, i - 6),
                frameAddresses = listOf(
                    0xA5A6E3,
                    rightEyeIdle[0],
                    rightTailWhip[i],
                    rightArmsIdle[0]
                ),
                minEntries = minEntries
            )?.let { poses.add(it) }
        }

        return poses.distinctBy { pose ->
            pose.compositeParts.joinToString("|") { it.frame.snesAddress.toString(16) }
        }
    }

    private fun createDraygonCompositePose(
        label: String,
        durationTicks: Int,
        frameAddresses: List<Int>,
        minEntries: Int
    ): BossPose? {
        val parts = frameAddresses.map { addr ->
            val frame = smap.parseRenderableFrame(addr) ?: return null
            CompositePart(frame, draygonRenderOptions(frame))
        }
        val flattenedEntries = parts.flatMap { smap.flattenRenderableFrame(it.frame).entries }
        val combinedSpritemap = EnemySpritemap.Spritemap(flattenedEntries, parts.first().frame.snesAddress)
        val entryCount = parts.sumOf { part ->
            val flattened = smap.flattenRenderableFrame(part.frame)
            renderableEntryCount(part.frame, flattened)
        }
        if (entryCount < minEntries) return null

        return BossPose(
            name = label,
            spritemap = combinedSpritemap,
            entryCount = entryCount,
            frame = parts.first().frame,
            childCount = parts.sumOf { renderableChildCount(it.frame) },
            tilemapCount = parts.sumOf { renderableTilemapCount(it.frame) },
            renderOptions = draygonRenderOptions(parts.first().frame),
            durationTicks = durationTicks,
            usesDraygonBgTileData = true,
            compositeParts = parts
        )
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
        val renderTileData = when (pose.tileDataVariant) {
            TileDataVariant.DEFAULT -> tileData
            TileDataVariant.CROCOMIRE_SKELETON ->
                EnemySpriteGraphics.applyCrocomireSkeletonTileData(romParser, tileData) ?: tileData
        }
        val bgTileData = when {
            pose.usesCrocomireBgTileData -> crocomireRoomTileData()
            pose.usesDraygonBgTileData -> draygonRoomTileData()
            pose.usesMotherBrainBgTileData -> motherBrainRoomTileData()
            else -> null
        }
        if (pose.compositeParts.isNotEmpty()) {
            val sprites = pose.compositeParts.mapNotNull { part ->
                smap.renderRenderableFrame(
                    part.frame,
                    renderTileData,
                    palette,
                    part.renderOptions,
                    extendedTilemapTileData = bgTileData
                )
            }
            return composeAssembledSprites(sprites, pose.spritemap)
        }
        val assembled = smap.renderRenderableFrame(
            pose.frame,
            renderTileData,
            palette,
            pose.renderOptions,
            extendedTilemapTileData = bgTileData
        ) ?: return null
        return autoCrop(assembled, pose.spritemap)
    }

    private fun crocomireRoomTileData(): ByteArray? {
        val cached = crocomireRoomTileDataCache
        if (cached != null) return cached
        val loaded = EnemySpriteGraphics.loadCrocomireRoomTileData(romParser)
        crocomireRoomTileDataCache = loaded
        return loaded
    }

    private fun draygonRoomTileData(): ByteArray? {
        val cached = draygonRoomTileDataCache
        if (cached != null) return cached
        val loaded = EnemySpriteGraphics.loadDraygonRoomTileData(romParser)
        draygonRoomTileDataCache = loaded
        return loaded
    }

    private fun motherBrainRoomTileData(): ByteArray? {
        val cached = motherBrainRoomTileDataCache
        if (cached != null) return cached
        val loaded = EnemySpriteGraphics.loadMotherBrainRoomTileData(romParser)
        motherBrainRoomTileDataCache = loaded
        return loaded
    }

    private fun tileDataVariantForKnownFrame(
        speciesId: Int,
        instrList: KnownInstrList
    ): TileDataVariant {
        return if (speciesId == SPECIES_CROCOMIRE && instrList.ptr in CROCOMIRE_SKELETON_INSTR_LISTS) {
            TileDataVariant.CROCOMIRE_SKELETON
        } else {
            TileDataVariant.DEFAULT
        }
    }

    private fun renderOptionsForKnownFrame(
        speciesId: Int,
        frame: EnemySpritemap.RenderableFrame
    ): EnemySpritemap.RenderOptions {
        if (speciesId in setOf(SPECIES_DRAYGON_EYE, SPECIES_DRAYGON_TAIL, SPECIES_DRAYGON_ARMS)) {
            return draygonRenderOptions(frame)
        }
        if (speciesId == SPECIES_MOTHER_BRAIN_BODY && frame is EnemySpritemap.RenderableFrame.Extended) {
            return motherBrainBodyRenderOptions()
        }
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
            extendedTilemapBlankTiles = setOf(0x0338, 0x0147, 0x02FF)
        )
    }

    private fun draygonRenderOptions(
        frame: EnemySpritemap.RenderableFrame
    ): EnemySpritemap.RenderOptions {
        val hasTilemap = frame is EnemySpritemap.RenderableFrame.Extended &&
            frame.spritemap.children.any { it is EnemySpritemap.ExtendedChild.Tilemap }
        return EnemySpritemap.RenderOptions(
            normalizeExtendedTilemaps = !hasTilemap,
            extendedTilemapOriginX = DRAYGON_BG2_ORIGIN_X,
            extendedTilemapOriginY = DRAYGON_BG2_ORIGIN_Y,
            wrapExtendedTilemapTilePage = false,
            extendedTilemapsBehindOam = true,
            oamTileNumberMode = EnemySpritemap.OamTileNumberMode.LOW_9
        )
    }

    private fun motherBrainBodyRenderOptions(): EnemySpritemap.RenderOptions =
        EnemySpritemap.RenderOptions(
            normalizeExtendedTilemaps = true,
            extendedTilemapsBehindOam = true,
            oamTileNumberMode = EnemySpritemap.OamTileNumberMode.LOW_9,
            extendedOamOriginY = 0x28,
            extendedTilemapBlankTiles = setOf(0x0338)
        )

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

    private fun composeAssembledSprites(
        sprites: List<EnemySpritemap.AssembledSprite>,
        spritemap: EnemySpritemap.Spritemap
    ): EnemySpritemap.AssembledSprite? {
        if (sprites.isEmpty()) return null

        val minX = sprites.minOf { -it.originX }
        val minY = sprites.minOf { -it.originY }
        val maxX = sprites.maxOf { it.width - it.originX }
        val maxY = sprites.maxOf { it.height - it.originY }
        val w = maxX - minX
        val h = maxY - minY
        if (w <= 0 || h <= 0) return null

        val pixels = IntArray(w * h)
        for (sprite in sprites) {
            for (y in 0 until sprite.height) {
                val globalY = y - sprite.originY
                val dy = globalY - minY
                if (dy !in 0 until h) continue
                for (x in 0 until sprite.width) {
                    val argb = sprite.pixels[y * sprite.width + x]
                    if ((argb ushr 24) == 0) continue
                    val globalX = x - sprite.originX
                    val dx = globalX - minX
                    if (dx in 0 until w) {
                        pixels[dy * w + dx] = argb
                    }
                }
            }
        }

        return autoCrop(
            EnemySpritemap.AssembledSprite(
                width = w,
                height = h,
                pixels = pixels,
                originX = -minX,
                originY = -minY,
                spritemap = spritemap
            ),
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
