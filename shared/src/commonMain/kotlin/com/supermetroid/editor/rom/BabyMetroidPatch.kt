package com.supermetroid.editor.rom

/**
 * Managed end-game Baby Metroid (enemy $EEBF, commonly called the Shitroid)
 * behavior patch.
 *
 * Vanilla restores the enemy to $7FFF health every frame and its shot AI only
 * applies knockback. Mortal mode removes that health restore and points the
 * enemy header at a small guarded shot shim. The guard deliberately preserves
 * the native contact-reaction flag: shots cannot damage the Baby until its
 * scripted corpse sequence has removed the room's invisible walls.
 */
object BabyMetroidPatch {
    const val CONFIG_TYPE = "baby_metroid"
    const val PATCH_ID = "config_baby_metroid"

    const val MORTAL_KEY = "mortal"
    const val FREEZABLE_KEY = "freezable"
    const val MISSILE_VULNERABILITY_KEY = "missile_vulnerability"
    const val SUPER_VULNERABILITY_KEY = "super_vulnerability"

    const val DEFAULT_MORTAL = 0
    const val DEFAULT_FREEZABLE = 1
    const val DEFAULT_MISSILE_VULNERABILITY = 2
    const val DEFAULT_SUPER_VULNERABILITY = 2

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

    private val HEALTH_RESTORE_BYTES = listOf(0xA9, 0xFF, 0x7F, 0x9D, 0x8C, 0x0F)
    private val NOP_HEALTH_RESTORE_BYTES = List(HEALTH_RESTORE_BYTES.size) { 0xEA }

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
        addAll(MOVEMENT_FIELDS.map { it.key })
    }

    data class InstallResult(
        val mortalInstalled: Boolean,
        val movementWordsWritten: Int,
        val shotShimSnes: Int? = null,
        val vulnerabilityTableSnes: Int? = null,
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

        if (mortal) {
            requireBytes(parser, MAIN_HEALTH_RESTORE_SNES, HEALTH_RESTORE_BYTES, "native per-frame health restore")
            requireWord(parser, SHOT_AI_POINTER_SNES, ORIGINAL_SHOT_AI_SNES and 0xFFFF, "native shot AI pointer")
            requireWord(parser, VULNERABILITY_POINTER_SNES, ORIGINAL_VULNERABILITY_POINTER, "native vulnerability pointer")
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

        val shim = buildShotShim()
        val shimAllocation = codeAllocator.reserveOrThrow(
            size = shim.size,
            banks = listOf(0xA9),
            label = "$label guarded shot AI",
            alignment = 2,
        )
        val table = buildVulnerabilityTable(freezable, missileVulnerability, superVulnerability)
        val tableAllocation = tableAllocator.reserveOrThrow(
            size = table.size,
            banks = listOf(0xB4),
            label = "$label private vulnerability table",
            alignment = 2,
        )
        require(shimAllocation.snesAddress ushr 16 == 0xA9)
        require(tableAllocation.snesAddress ushr 16 == 0xB4)

        writePlan.addAllocation(shimAllocation, shim, RomWriteKind.HOOK)
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

        return InstallResult(
            mortalInstalled = true,
            movementWordsWritten = movementWrites,
            shotShimSnes = shimAllocation.snesAddress,
            vulnerabilityTableSnes = tableAllocation.snesAddress,
        )
    }

    private fun buildShotShim(): ByteArray = byteArrayOf(
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
