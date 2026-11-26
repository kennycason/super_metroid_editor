package com.supermetroid.editor.rom

/**
 * Managed end-game Baby Metroid (enemy $EEBF, commonly called the Shitroid)
 * behavior patch.
 *
 * Vanilla restores the enemy to $7FFF health every frame and its shot AI only
 * applies knockback. Mortal mode removes that health restore and points the
 * enemy header at a small guarded shot shim. The guard deliberately preserves
 * the native contact-reaction flag: shots cannot damage the Baby until its
 * scripted corpse sequence has removed the room's invisible walls. An opt-in
 * follow-up records its death in vanilla's unused event $14 and suppresses the
 * later Mother Brain rescue spawn, allowing the final beam to kill Samus.
 */
object BabyMetroidPatch {
    const val CONFIG_TYPE = "baby_metroid"
    const val PATCH_ID = "config_baby_metroid"

    const val MORTAL_KEY = "mortal"
    const val FREEZABLE_KEY = "freezable"
    const val MISSILE_VULNERABILITY_KEY = "missile_vulnerability"
    const val SUPER_VULNERABILITY_KEY = "super_vulnerability"
    const val DOES_NOT_SAVE_SAMUS_IF_DEAD_KEY = "does_not_save_samus_if_dead"

    const val DEFAULT_MORTAL = 0
    const val DEFAULT_FREEZABLE = 1
    const val DEFAULT_MISSILE_VULNERABILITY = 2
    const val DEFAULT_SUPER_VULNERABILITY = 2
    const val DEFAULT_DOES_NOT_SAVE_SAMUS_IF_DEAD = 0

    const val SPECIES_ID = 0xEEBF
    const val DEFAULT_HP = 3200
    const val DEFAULT_CONTACT_DAMAGE = 40
    const val FIFTY_MISSILE_HP = 5000

    private const val ENEMY_HEADER_SNES = 0xA0EEBF
    private const val SHOT_AI_POINTER_SNES = ENEMY_HEADER_SNES + 0x32
    private const val VULNERABILITY_POINTER_SNES = ENEMY_HEADER_SNES + 0x3C
    private const val MAIN_HEALTH_RESTORE_SNES = 0xA9EFC8
    private const val ORIGINAL_SHOT_AI_SNES = 0xA9F842
    private const val NORMAL_ENEMY_SHOT_AI_SNES = 0xA0A63D
    private const val ORIGINAL_VULNERABILITY_POINTER = 0xF12E
    private const val VULNERABILITY_SIZE = 22
    private const val BABY_DEFEATED_EVENT = 0x14 // Unused by the vanilla event table.
    private const val MARK_EVENT_SNES = 0x8081FA
    private const val CHECK_EVENT_SNES = 0x808233
    private const val CUTSCENE_BABY_SPAWN_CALL_SNES = 0xA9BDDD
    private const val CUTSCENE_BABY_SPAWN_ROUTINE = 0xBE1B
    private const val FINAL_BEAM_FUNCTION_POINTER_SNES = 0xA9BDE1
    private const val VANILLA_FINAL_BEAM_FUNCTION = 0xBDED
    private const val LETHAL_RAINBOW_BEAM_SETUP = 0xB951

    private val HEALTH_RESTORE_BYTES = listOf(0xA9, 0xFF, 0x7F, 0x9D, 0x8C, 0x0F)
    private val NOP_HEALTH_RESTORE_BYTES = List(HEALTH_RESTORE_BYTES.size) { 0xEA }
    private val CUTSCENE_BABY_SPAWN_CALL_BYTES = listOf(0x20, 0x1B, 0xBE)

    data class MovementField(
        val key: String,
        val label: String,
        val description: String,
        val defaultValue: Int,
        /** Addresses of the 16-bit immediate operands, one byte after each LDY opcode. */
        val operandSnesAddresses: List<Int>,
    )

    /**
     * These are call-site acceleration-table indexes, not the shared speed-cap
     * routine used later by the Mother Brain cutscene. Keeping the edits local
     * avoids destabilising that cutscene.
     */
    val MOVEMENT_FIELDS = listOf(
        MovementField(
            key = "opening_rush_agility",
            label = "Opening Rush Agility",
            description = "Acceleration toward the room centre and the Sidehopper (0 slowest, 15 fastest).",
            defaultValue = 15,
            operandSnesAddresses = listOf(0xA9F054, 0xA9F07C),
        ),
        MovementField(
            key = "samus_chase_agility",
            label = "Samus Chase Agility",
            description = "Acceleration while staring down and chasing Samus (0 slowest, 15 fastest).",
            defaultValue = 10,
            operandSnesAddresses = listOf(0xA9F1EB),
        ),
        MovementField(
            key = "latch_approach_agility",
            label = "Latch Approach Agility",
            description = "Acceleration during the final approach before latching onto Samus.",
            defaultValue = 15,
            operandSnesAddresses = listOf(0xA9F209),
        ),
        MovementField(
            key = "remorse_chase_agility",
            label = "Remorse Chase Agility",
            description = "Acceleration while returning toward Samus after the drain sequence.",
            defaultValue = 8,
            operandSnesAddresses = listOf(0xA9F437),
        ),
    )

    val CONFIG_KEYS: Set<String> = buildSet {
        add(MORTAL_KEY)
        add(FREEZABLE_KEY)
        add(MISSILE_VULNERABILITY_KEY)
        add(SUPER_VULNERABILITY_KEY)
        add(DOES_NOT_SAVE_SAMUS_IF_DEAD_KEY)
        addAll(MOVEMENT_FIELDS.map { it.key })
    }

    data class InstallResult(
        val mortalInstalled: Boolean,
        val movementWordsWritten: Int,
        val shotShimSnes: Int? = null,
        val vulnerabilityTableSnes: Int? = null,
        val noRescueIfDeadInstalled: Boolean = false,
        val rescueCheckSnes: Int? = null,
        val finalBeamDispatchSnes: Int? = null,
    )

    fun buildVulnerabilityTable(
        freezable: Boolean,
        missileVulnerability: Int,
        superVulnerability: Int,
    ): ByteArray {
        require(missileVulnerability in 0..0x0F)
        require(superVulnerability in 0..0x0F)
        return ByteArray(VULNERABILITY_SIZE).also { table ->
            if (freezable) {
                // Every base beam-combination slot containing Ice. The engine
                // checks $FF freeze before its charged-beam override.
                for (slot in listOf(2, 3, 6, 7, 10, 11)) table[slot] = 0xFF.toByte()
            }
            table[12] = missileVulnerability.toByte()
            table[13] = superVulnerability.toByte()
        }
    }

    fun install(
        config: Map<String, Int>,
        parser: RomParser,
        writePlan: RomWritePlan,
        freeSpaceAllocator: RomFreeSpaceAllocator,
        owner: String = "patch:$PATCH_ID",
        label: String = "Baby Metroid",
    ): InstallResult {
        val unknown = config.keys - CONFIG_KEYS
        if (unknown.isNotEmpty()) {
            throw RomWritePlanException("$label has unsupported setting(s): ${unknown.sorted().joinToString()}")
        }

        val movementValues = MOVEMENT_FIELDS.mapNotNull { field ->
            val value = config[field.key] ?: return@mapNotNull null
            require(value in 0..15) { "${field.label} must be between 0 and 15" }
            for (address in field.operandSnesAddresses) {
                requireByte(parser, address - 1, 0xA0, "${field.label} LDY immediate opcode")
            }
            field to value
        }

        val mortal = (config[MORTAL_KEY] ?: DEFAULT_MORTAL).also {
            require(it in 0..1) { "Mortal must be 0 or 1" }
        } == 1
        val freezable = (config[FREEZABLE_KEY] ?: DEFAULT_FREEZABLE).also {
            require(it in 0..1) { "Freezable must be 0 or 1" }
        } == 1
        val missileVulnerability = (config[MISSILE_VULNERABILITY_KEY]
            ?: DEFAULT_MISSILE_VULNERABILITY).also {
            require(it in 0..0x0F) { "Missile vulnerability must be between 0 and 15" }
        }
        val superVulnerability = (config[SUPER_VULNERABILITY_KEY]
            ?: DEFAULT_SUPER_VULNERABILITY).also {
            require(it in 0..0x0F) { "Super Missile vulnerability must be between 0 and 15" }
        }
        val doesNotSaveSamusIfDead = (config[DOES_NOT_SAVE_SAMUS_IF_DEAD_KEY]
            ?: DEFAULT_DOES_NOT_SAVE_SAMUS_IF_DEAD).also {
            require(it in 0..1) { "Baby Metroid Doesn't Save Samus If Dead must be 0 or 1" }
        } == 1
        val installNoRescueIfDead = mortal && doesNotSaveSamusIfDead

        if (mortal) {
            requireBytes(parser, MAIN_HEALTH_RESTORE_SNES, HEALTH_RESTORE_BYTES, "native per-frame health restore")
            requireWord(parser, SHOT_AI_POINTER_SNES, ORIGINAL_SHOT_AI_SNES and 0xFFFF, "native shot AI pointer")
            requireWord(parser, VULNERABILITY_POINTER_SNES, ORIGINAL_VULNERABILITY_POINTER, "native vulnerability pointer")
        }
        if (installNoRescueIfDead) {
            requireBytes(
                parser,
                CUTSCENE_BABY_SPAWN_CALL_SNES,
                CUTSCENE_BABY_SPAWN_CALL_BYTES,
                "native Mother Brain cutscene Baby spawn call",
            )
            requireWord(
                parser,
                FINAL_BEAM_FUNCTION_POINTER_SNES,
                VANILLA_FINAL_BEAM_FUNCTION,
                "native Mother Brain final-beam function pointer",
            )
        }

        var movementWrites = 0
        for ((field, value) in movementValues) {
            for (address in field.operandSnesAddresses) {
                addWord(
                    writePlan,
                    owner,
                    "$label · ${field.label}",
                    parser.snesToPc(address),
                    value,
                )
                movementWrites++
            }
        }
        if (!mortal) return InstallResult(false, movementWrites)

        val codeAllocator = freeSpaceAllocator.scoped(
            owner = owner,
            kind = RomSegmentKind.HOOK_HELPER,
            idPrefix = "$owner/shot-shim",
            constraint = "The enemy header stores a 16-bit shot-AI pointer, so the shim must remain in bank \$A9",
        )
        val tableAllocator = freeSpaceAllocator.scoped(
            owner = owner,
            kind = RomSegmentKind.SOURCE_ASSET,
            idPrefix = "$owner/vulnerability",
            constraint = "Normal enemy shot AI resolves vulnerability pointers in bank \$B4",
        )

        val shim = buildShotShim(markDefeatedEvent = installNoRescueIfDead)
        val shimAllocation = codeAllocator.reserveOrThrow(
            size = shim.size,
            banks = listOf(0xA9),
            label = "$label guarded shot AI",
            alignment = 2,
        )
        val rescueCheck = if (installNoRescueIfDead) buildRescueCheck() else null
        val rescueCheckAllocation = rescueCheck?.let { helper ->
            codeAllocator.reserveOrThrow(
                size = helper.size,
                banks = listOf(0xA9),
                label = "$label dead-Baby rescue check",
                alignment = 2,
            )
        }
        val finalBeamDispatch = if (installNoRescueIfDead) buildFinalBeamDispatch() else null
        val finalBeamDispatchAllocation = finalBeamDispatch?.let { helper ->
            codeAllocator.reserveOrThrow(
                size = helper.size,
                banks = listOf(0xA9),
                label = "$label dead-Baby final-beam dispatch",
                alignment = 2,
            )
        }
        val table = buildVulnerabilityTable(freezable, missileVulnerability, superVulnerability)
        val tableAllocation = tableAllocator.reserveOrThrow(
            size = table.size,
            banks = listOf(0xB4),
            label = "$label private vulnerability table",
            alignment = 2,
        )
        require(shimAllocation.snesAddress ushr 16 == 0xA9)
        require(rescueCheckAllocation == null || rescueCheckAllocation.snesAddress ushr 16 == 0xA9)
        require(finalBeamDispatchAllocation == null || finalBeamDispatchAllocation.snesAddress ushr 16 == 0xA9)
        require(tableAllocation.snesAddress ushr 16 == 0xB4)

        writePlan.addAllocation(shimAllocation, shim, RomWriteKind.HOOK)
        if (rescueCheckAllocation != null) {
            writePlan.addAllocation(rescueCheckAllocation, rescueCheck, RomWriteKind.HOOK)
        }
        if (finalBeamDispatchAllocation != null) {
            writePlan.addAllocation(finalBeamDispatchAllocation, finalBeamDispatch, RomWriteKind.HOOK)
        }
        writePlan.addAllocation(tableAllocation, table, RomWriteKind.CONFIG)
        addBytes(
            writePlan,
            owner,
            "$label · disable invincibility health restore",
            parser.snesToPc(MAIN_HEALTH_RESTORE_SNES),
            NOP_HEALTH_RESTORE_BYTES,
            HEALTH_RESTORE_BYTES,
            RomWriteKind.HOOK,
        )
        addWord(
            writePlan,
            owner,
            "$label · guarded shot AI pointer",
            parser.snesToPc(SHOT_AI_POINTER_SNES),
            shimAllocation.snesAddress and 0xFFFF,
        )
        addWord(
            writePlan,
            owner,
            "$label · private vulnerability pointer",
            parser.snesToPc(VULNERABILITY_POINTER_SNES),
            tableAllocation.snesAddress and 0xFFFF,
        )
        if (rescueCheckAllocation != null) {
            addBytes(
                writePlan,
                owner,
                "$label · skip rescue when Baby is dead",
                parser.snesToPc(CUTSCENE_BABY_SPAWN_CALL_SNES),
                listOf(
                    0x20,
                    rescueCheckAllocation.snesAddress and 0xFF,
                    (rescueCheckAllocation.snesAddress ushr 8) and 0xFF,
                ),
                CUTSCENE_BABY_SPAWN_CALL_BYTES,
                RomWriteKind.HOOK,
            )
        }
        if (finalBeamDispatchAllocation != null) {
            addBytes(
                writePlan,
                owner,
                "$label · route dead Baby to lethal rainbow beam",
                parser.snesToPc(FINAL_BEAM_FUNCTION_POINTER_SNES),
                listOf(
                    finalBeamDispatchAllocation.snesAddress and 0xFF,
                    (finalBeamDispatchAllocation.snesAddress ushr 8) and 0xFF,
                ),
                listOf(
                    VANILLA_FINAL_BEAM_FUNCTION and 0xFF,
                    (VANILLA_FINAL_BEAM_FUNCTION ushr 8) and 0xFF,
                ),
                RomWriteKind.HOOK,
            )
        }

        return InstallResult(
            mortalInstalled = true,
            movementWordsWritten = movementWrites,
            shotShimSnes = shimAllocation.snesAddress,
            vulnerabilityTableSnes = tableAllocation.snesAddress,
            noRescueIfDeadInstalled = installNoRescueIfDead,
            rescueCheckSnes = rescueCheckAllocation?.snesAddress,
            finalBeamDispatchSnes = finalBeamDispatchAllocation?.snesAddress,
        )
    }

    private fun buildShotShim(markDefeatedEvent: Boolean): ByteArray = if (!markDefeatedEvent) {
        byteArrayOf(
            0xAE.toByte(), 0x54, 0x0E,                         // LDX $0E54 (enemy index)
            0xBD.toByte(), 0xB6.toByte(), 0x0F,               // LDA $0FB6,x (combat/contact flag)
            0xF0.toByte(), 0x08,                               // BEQ return
            0x22, ORIGINAL_SHOT_AI_SNES.toByte(),
            (ORIGINAL_SHOT_AI_SNES ushr 8).toByte(),
            (ORIGINAL_SHOT_AI_SNES ushr 16).toByte(),          // JSL original knockback/AI reaction
            0x22, NORMAL_ENEMY_SHOT_AI_SNES.toByte(),
            (NORMAL_ENEMY_SHOT_AI_SNES ushr 8).toByte(),
            (NORMAL_ENEMY_SHOT_AI_SNES ushr 16).toByte(),      // JSL normal damage/freeze/death
            0x6B,                                               // RTL / return
        )
    } else {
        byteArrayOf(
            0xAE.toByte(), 0x54, 0x0E,                         // LDX $0E54 (enemy index)
            0xBD.toByte(), 0xB6.toByte(), 0x0F,               // LDA $0FB6,x (combat/contact flag)
            0xF0.toByte(), 0x14,                               // BEQ return
            0x22, ORIGINAL_SHOT_AI_SNES.toByte(),
            (ORIGINAL_SHOT_AI_SNES ushr 8).toByte(),
            (ORIGINAL_SHOT_AI_SNES ushr 16).toByte(),          // JSL original knockback/AI reaction
            0x22, NORMAL_ENEMY_SHOT_AI_SNES.toByte(),
            (NORMAL_ENEMY_SHOT_AI_SNES ushr 8).toByte(),
            (NORMAL_ENEMY_SHOT_AI_SNES ushr 16).toByte(),      // JSL normal damage/freeze/death
            0xBD.toByte(), 0x8C.toByte(), 0x0F,               // LDA $0F8C,x (health)
            0xD0.toByte(), 0x07,                               // BNE return
            0xA9.toByte(), BABY_DEFEATED_EVENT.toByte(), 0x00, // LDA #$0014
            0x22, MARK_EVENT_SNES.toByte(),
            (MARK_EVENT_SNES ushr 8).toByte(),
            (MARK_EVENT_SNES ushr 16).toByte(),                // JSL mark event
            0x6B,                                               // RTL / return
        )
    }

    private fun buildRescueCheck(): ByteArray = byteArrayOf(
        0xA9.toByte(), BABY_DEFEATED_EVENT.toByte(), 0x00,     // LDA #$0014
        0x22, CHECK_EVENT_SNES.toByte(),
        (CHECK_EVENT_SNES ushr 8).toByte(),
        (CHECK_EVENT_SNES ushr 16).toByte(),                   // JSL check event
        0xB0.toByte(), 0x04,                                   // BCS dead
        0x20, CUTSCENE_BABY_SPAWN_ROUTINE.toByte(),
        (CUTSCENE_BABY_SPAWN_ROUTINE ushr 8).toByte(),         // JSR $BE1B
        0x60,                                                   // RTS
        0x60,                                                   // dead: RTS
    )

    /**
     * Vanilla's final-beam function only changes the head instruction and then
     * parks; the cutscene Baby normally advances the fight from there. Route a
     * dead Baby into the real phase-2 rainbow-beam setup so the beam HDMA and
     * damage loop run. An alive Baby retains the exact vanilla function.
     */
    private fun buildFinalBeamDispatch(): ByteArray = byteArrayOf(
        0xA9.toByte(), BABY_DEFEATED_EVENT.toByte(), 0x00,      // LDA #$0014
        0x22, CHECK_EVENT_SNES.toByte(),
        (CHECK_EVENT_SNES ushr 8).toByte(),
        (CHECK_EVENT_SNES ushr 16).toByte(),                   // JSL check event
        0xB0.toByte(), 0x03,                                   // BCS dead
        0x4C, VANILLA_FINAL_BEAM_FUNCTION.toByte(),
        (VANILLA_FINAL_BEAM_FUNCTION ushr 8).toByte(),         // JMP $BDED
        0x4C, LETHAL_RAINBOW_BEAM_SETUP.toByte(),
        (LETHAL_RAINBOW_BEAM_SETUP ushr 8).toByte(),           // dead: JMP $B951
    )

    private fun addWord(
        writePlan: RomWritePlan,
        owner: String,
        label: String,
        fileOffset: Int,
        value: Int,
    ) {
        require(value in 0..0xFFFF)
        val expected = listOf(
            writePlan.romData[fileOffset].toInt() and 0xFF,
            writePlan.romData[fileOffset + 1].toInt() and 0xFF,
        )
        writePlan.add(
            owner = owner,
            label = label,
            offset = fileOffset - writePlan.headerSize,
            bytes = listOf(value and 0xFF, value ushr 8 and 0xFF),
            kind = RomWriteKind.CONFIG,
            expectedBefore = expected,
            overlapPolicy = RomOverlapPolicy.ALLOW_SAME_OWNER,
        )
    }

    private fun addBytes(
        writePlan: RomWritePlan,
        owner: String,
        label: String,
        fileOffset: Int,
        bytes: List<Int>,
        expected: List<Int>,
        kind: RomWriteKind,
    ) {
        writePlan.add(
            owner = owner,
            label = label,
            offset = fileOffset - writePlan.headerSize,
            bytes = bytes,
            kind = kind,
            expectedBefore = expected,
            overlapPolicy = RomOverlapPolicy.ALLOW_SAME_OWNER,
        )
    }

    private fun requireByte(parser: RomParser, snesAddress: Int, expected: Int, label: String) {
        val rom = parser.getRomData()
        val pc = parser.snesToPc(snesAddress)
        val actual = rom.getOrNull(pc)?.toInt()?.and(0xFF)
        if (actual != expected) {
            throw RomWritePlanException(
                "$label changed at ${formatSnes(snesAddress)}: expected \$${expected.hex(2)}, got " +
                    (actual?.let { "\$${it.hex(2)}" } ?: "end of ROM")
            )
        }
    }

    private fun requireWord(parser: RomParser, snesAddress: Int, expected: Int, label: String) {
        val rom = parser.getRomData()
        val pc = parser.snesToPc(snesAddress)
        val actual = if (pc + 1 < rom.size) {
            (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        } else null
        if (actual != expected) {
            throw RomWritePlanException(
                "$label changed at ${formatSnes(snesAddress)}: expected \$${expected.hex(4)}, got " +
                    (actual?.let { "\$${it.hex(4)}" } ?: "end of ROM")
            )
        }
    }

    private fun requireBytes(parser: RomParser, snesAddress: Int, expected: List<Int>, label: String) {
        val rom = parser.getRomData()
        val pc = parser.snesToPc(snesAddress)
        val actual = if (pc >= 0 && pc + expected.size <= rom.size) {
            (pc until pc + expected.size).map { rom[it].toInt() and 0xFF }
        } else emptyList()
        if (actual != expected) {
            throw RomWritePlanException(
                "$label changed at ${formatSnes(snesAddress)}; refusing to install an unchecked hook"
            )
        }
    }

    private fun formatSnes(address: Int): String =
        "\$${(address ushr 16).hex(2)}:${(address and 0xFFFF).hex(4)}"

    private fun Int.hex(width: Int): String = toString(16).uppercase().padStart(width, '0')
}
