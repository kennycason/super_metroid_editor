package com.supermetroid.editor.rom

/**
 * Configurable base damage rates for Super Metroid's three environmental hazards.
 *
 * The engine accumulates periodic damage every frame as an unsigned 16.16 fixed-point
 * value. SMEDIT presents that value as whole energy per second, using the game's nominal
 * 60 Hz rate, and writes both halves of the accumulator increment.
 */
object EnvironmentalDamagePatch {
    const val CONFIG_TYPE = "environmental_damage"

    const val HEAT_KEY = "heat_per_second"
    const val LAVA_KEY = "lava_per_second"
    const val ACID_KEY = "acid_per_second"
    const val ALL_ROOMS_HEATED_KEY = "all_rooms_heated"
    const val PER_AREA_RATES_KEY = "per_area_rates"

    const val MIN_ENERGY_PER_SECOND = 0
    const val MAX_ENERGY_PER_SECOND = 9999
    const val FRAMES_PER_SECOND = 60
    const val TOURIAN_AREA_INDEX = 5
    private const val FIXED_ONE = 0x10000L

    data class CheckedWrite(
        val pcOffset: Int,
        val bytes: List<Int>,
        val expectedBytes: List<Int>,
    )

    /**
     * Makes heat a global semantic effect outside Tourian without repurposing
     * the area-specific palette-FX bits. Both room-load paths call a checked
     * helper in vanilla bank $DF. The helper always runs the native FX loader
     * first, then leaves Tourian untouched because Mother Brain and the escape
     * use special palette-FX state. Elsewhere it adds the native heat object
     * only when the room did not already spawn it.
     */
    val ALL_ROOMS_HEATED_WRITES: List<CheckedWrite> = listOf(
        CheckedWrite(
            pcOffset = 0x0020E5,
            bytes = listOf(0x22, 0x00, 0xFF, 0xDF), // JSL $DF:FF00
            expectedBytes = listOf(0x22, 0x82, 0xAB, 0x89), // JSL $89:AB82
        ),
        CheckedWrite(
            pcOffset = 0x0164C5,
            bytes = listOf(0x22, 0x00, 0xFF, 0xDF), // JSL $DF:FF00
            expectedBytes = listOf(0x22, 0x82, 0xAB, 0x89), // JSL $89:AB82
        ),
        CheckedWrite(
            pcOffset = 0x2FFF00,
            bytes = listOf(
                0x22, 0x82, 0xAB, 0x89, // JSL $89:AB82 (native FX loader)
                0x08,                   // PHP
                0xC2, 0x30,             // REP #$30
                0x48,                   // PHA
                0xDA,                   // PHX
                0x5A,                   // PHY
                0xAD, 0x9F, 0x07,       // LDA $079F (AreaIndex)
                0xC9, 0x05, 0x00,       // CMP #$0005 (Tourian)
                0xF0, 0x16,             // BEQ done (keep native Tourian FX only)
                0xA2, 0x0E, 0x00,       // LDX #$000E
                0xBD, 0x7D, 0x1E,       // LDA $1E7D,X (palette FX object IDs)
                0xC9, 0x61, 0xF7,       // CMP #$F761 (native heat object)
                0xF0, 0x0B,             // BEQ done
                0xCA,                   // DEX
                0xCA,                   // DEX
                0x10, 0xF4,             // BPL loop
                0xA0, 0x61, 0xF7,       // LDY #$F761
                0x22, 0xE9, 0xC4, 0x8D, // JSL $8D:C4E9 (spawn palette FX)
                0x7A,                   // PLY
                0xFA,                   // PLX
                0x68,                   // PLA
                0x28,                   // PLP
                0x6B,                   // RTL
            ),
            expectedBytes = List(45) { 0xFF },
        ),
    )

    data class Field(
        val key: String,
        val label: String,
        val lowWordPc: Int,
        val highWordPc: Int,
        val defaultEnergyPerSecond: Int,
        val suitBehavior: String,
    )

    val FIELDS = listOf(
        Field(
            key = HEAT_KEY,
            label = "Heated Rooms",
            lowWordPc = 0x06E386,
            highWordPc = 0x06E38F,
            defaultEnergyPerSecond = 15,
            suitBehavior = "Varia and Gravity protect",
        ),
        Field(
            key = LAVA_KEY,
            label = "Lava",
            lowWordPc = 0x081E8B,
            highWordPc = 0x081E8D,
            defaultEnergyPerSecond = 30,
            suitBehavior = "Varia halves damage; Gravity protects",
        ),
        Field(
            key = ACID_KEY,
            label = "Acid",
            lowWordPc = 0x081E8F,
            highWordPc = 0x081E91,
            defaultEnergyPerSecond = 90,
            suitBehavior = "Varia halves damage; Gravity quarters it",
        ),
    )

    data class Area(
        val index: Int,
        val key: String,
        val label: String,
    )

    /** The engine stores the current area as a word at $7E:079F. */
    val AREAS = listOf(
        Area(0, "crateria", "Crateria"),
        Area(1, "brinstar", "Brinstar"),
        Area(2, "norfair", "Norfair"),
        Area(3, "wrecked_ship", "Wrecked Ship"),
        Area(4, "maridia", "Maridia"),
        Area(5, "tourian", "Tourian"),
        Area(6, "ceres", "Ceres"),
        Area(7, "debug", "Debug / Unused"),
    )

    data class AreaRateField(
        val area: Area,
        val hazard: Field,
        val key: String = areaRateKey(area, hazard),
    )

    val AREA_RATE_FIELDS: List<AreaRateField> = AREAS.flatMap { area ->
        FIELDS.map { hazard -> AreaRateField(area, hazard) }
    }

    val CONFIG_KEYS: Set<String> = buildSet {
        addAll(FIELDS.map { it.key })
        add(ALL_ROOMS_HEATED_KEY)
        add(PER_AREA_RATES_KEY)
        addAll(AREA_RATE_FIELDS.map { it.key })
    }

    fun areaRateKey(area: Area, hazard: Field): String =
        "${hazard.key.removeSuffix("_per_second")}_${area.key}_per_second"

    fun areaRateKey(areaKey: String, hazardKey: String): String {
        val area = AREAS.single { it.key == areaKey }
        val hazard = FIELDS.single { it.key == hazardKey }
        return areaRateKey(area, hazard)
    }

    fun perAreaRatesEnabled(config: Map<String, Int>): Boolean = config[PER_AREA_RATES_KEY] == 1

    fun resolvedAreaRate(
        config: Map<String, Int>,
        area: Area,
        hazard: Field,
        fallbackRates: Map<String, Int> = FIELDS.associate { it.key to it.defaultEnergyPerSecond },
    ): Int = config[areaRateKey(area, hazard)]
        ?: config[hazard.key]
        ?: fallbackRates[hazard.key]
        ?: hazard.defaultEnergyPerSecond

    /**
     * Per-area mode redirects only the three native 19-byte accumulator blocks.
     * Hazard detection, liquid movement, sound, and suit checks remain native.
     * The generated routines live immediately before the existing all-rooms-heat
     * helper in checked vanilla bank $DF free space.
     */
    const val PER_AREA_CODE_PC = 0x2FFE00
    const val PER_AREA_CODE_SNES = 0xDFFE00
    private const val DAMAGE_BLOCK_SIZE = 19

    data class DamageHook(
        val key: String,
        val pcOffset: Int,
        val expectedBytes: List<Int>,
    )

    val NATIVE_DAMAGE_HOOKS = listOf(
        DamageHook(
            HEAT_KEY,
            0x06E381,
            listOf(0xAD, 0x4E, 0x0A, 0x18, 0x69, 0x00, 0x40, 0x8D, 0x4E, 0x0A,
                0xAD, 0x50, 0x0A, 0x69, 0x00, 0x00, 0x8D, 0x50, 0x0A),
        ),
        DamageHook(
            LAVA_KEY,
            0x0801F7,
            listOf(0xAD, 0x4E, 0x0A, 0x18, 0x6D, 0x8B, 0x9E, 0x8D, 0x4E, 0x0A,
                0xAD, 0x50, 0x0A, 0x6D, 0x8D, 0x9E, 0x8D, 0x50, 0x0A),
        ),
        DamageHook(
            ACID_KEY,
            0x080239,
            listOf(0xAD, 0x4E, 0x0A, 0x18, 0x6D, 0x8F, 0x9E, 0x8D, 0x4E, 0x0A,
                0xAD, 0x50, 0x0A, 0x6D, 0x91, 0x9E, 0x8D, 0x50, 0x0A),
        ),
    )

    fun isNativeDamageHookCompatible(key: String, bytes: List<Int>): Boolean {
        val native = NATIVE_DAMAGE_HOOKS.singleOrNull { it.key == key } ?: return false
        if (bytes.size != DAMAGE_BLOCK_SIZE) return false
        val configurableHeatOperands = if (key == HEAT_KEY) setOf(5, 6, 14, 15) else emptySet()
        return bytes.indices.all { index -> index in configurableHeatOperands || bytes[index] == native.expectedBytes[index] }
    }

    private fun longAddress(address: Int): List<Int> =
        listOf(address and 0xFF, (address ushr 8) and 0xFF, (address ushr 16) and 0xFF)

    private fun areaRoutine(tableSnes: Int): List<Int> = buildList {
        add(0xDA)                         // PHX
        addAll(listOf(0xAD, 0x9F, 0x07)) // LDA $079F (AreaIndex)
        addAll(listOf(0xC9, 0x08, 0x00)) // CMP #$0008
        addAll(listOf(0x90, 0x03))       // BCC valid
        addAll(listOf(0xA9, 0x00, 0x00)) // Invalid index safely uses Crateria
        add(0x0A)                        // ASL (four bytes per table entry)
        add(0x0A)                        // ASL
        add(0xAA)                        // TAX
        addAll(listOf(0xAD, 0x4E, 0x0A)) // LDA PeriodicSubDamage
        add(0x18)                        // CLC
        add(0x7F)                        // ADC.l table,X
        addAll(longAddress(tableSnes))
        addAll(listOf(0x8D, 0x4E, 0x0A)) // STA PeriodicSubDamage
        addAll(listOf(0xAD, 0x50, 0x0A)) // LDA PeriodicDamage
        add(0x7F)                        // ADC.l table+2,X (carry is retained)
        addAll(longAddress(tableSnes + 2))
        addAll(listOf(0x8D, 0x50, 0x0A)) // STA PeriodicDamage
        add(0xFA)                        // PLX
        add(0x6B)                        // RTL
    }

    private fun fixedRateBytes(fixed: FixedRate): List<Int> = listOf(
        fixed.lowWord and 0xFF,
        fixed.lowWord ushr 8 and 0xFF,
        fixed.highWord and 0xFF,
        fixed.highWord ushr 8 and 0xFF,
    )

    private fun rateBytes(rate: Int): List<Int> {
        require(rate in MIN_ENERGY_PER_SECOND..MAX_ENERGY_PER_SECOND) {
            "Environmental damage must fit $MIN_ENERGY_PER_SECOND-$MAX_ENERGY_PER_SECOND energy/sec."
        }
        return fixedRateBytes(encodeEnergyPerSecond(rate))
    }

    fun perAreaCheckedWrites(
        config: Map<String, Int>,
        fallbackRates: Map<String, Int> = FIELDS.associate { it.key to it.defaultEnergyPerSecond },
        hooks: List<DamageHook> = NATIVE_DAMAGE_HOOKS,
        fallbackFixedRates: Map<String, FixedRate> = emptyMap(),
    ): List<CheckedWrite> {
        require(perAreaRatesEnabled(config)) { "Per-area environmental rates are not enabled." }
        require(hooks.map { it.key }.toSet() == FIELDS.map { it.key }.toSet() &&
            hooks.all { it.expectedBytes.size == DAMAGE_BLOCK_SIZE }) {
            "Per-area environmental damage requires one checked 19-byte hook per hazard."
        }

        val placeholderRoutine = areaRoutine(0)
        val routineAddresses = FIELDS.mapIndexed { index, field ->
            field.key to (PER_AREA_CODE_SNES + index * placeholderRoutine.size)
        }.toMap()
        val tableStart = PER_AREA_CODE_SNES + placeholderRoutine.size * FIELDS.size
        val tableAddresses = FIELDS.mapIndexed { index, field ->
            field.key to (tableStart + index * AREAS.size * 4)
        }.toMap()
        val payload = buildList {
            FIELDS.forEach { field -> addAll(areaRoutine(tableAddresses.getValue(field.key))) }
            FIELDS.forEach { field ->
                AREAS.forEach { area ->
                    val configured = config[areaRateKey(area, field)] ?: config[field.key]
                    addAll(if (configured != null) {
                        rateBytes(configured)
                    } else {
                        fixedRateBytes(
                            fallbackFixedRates[field.key]
                                ?: encodeEnergyPerSecond(fallbackRates[field.key] ?: field.defaultEnergyPerSecond)
                        )
                    })
                }
            }
        }
        require(PER_AREA_CODE_PC + payload.size <= ALL_ROOMS_HEATED_WRITES.last().pcOffset) {
            "Per-area environmental code overlaps the non-Tourian heat helper."
        }

        return hooks.map { hook ->
            val routine = routineAddresses.getValue(hook.key)
            CheckedWrite(
                pcOffset = hook.pcOffset,
                bytes = listOf(0x22) + longAddress(routine) + List(DAMAGE_BLOCK_SIZE - 4) { 0xEA },
                expectedBytes = hook.expectedBytes,
            )
        } + CheckedWrite(
            pcOffset = PER_AREA_CODE_PC,
            bytes = payload,
            expectedBytes = List(payload.size) { 0xFF },
        )
    }

    data class FixedRate(val lowWord: Int, val highWord: Int) {
        val raw: Int get() = lowWord or (highWord shl 16)
    }

    fun encodeEnergyPerSecond(energyPerSecond: Int): FixedRate {
        val safeRate = energyPerSecond.coerceIn(MIN_ENERGY_PER_SECOND, MAX_ENERGY_PER_SECOND)
        val raw = (safeRate.toLong() * FIXED_ONE + FRAMES_PER_SECOND / 2) / FRAMES_PER_SECOND
        return FixedRate(
            lowWord = (raw and 0xFFFF).toInt(),
            highWord = ((raw ushr 16) and 0xFFFF).toInt(),
        )
    }

    fun decodeEnergyPerSecond(lowWord: Int, highWord: Int): Int {
        val raw = (lowWord.toLong() and 0xFFFF) or ((highWord.toLong() and 0xFFFF) shl 16)
        return ((raw * FRAMES_PER_SECOND + FIXED_ONE / 2) / FIXED_ONE)
            .toInt()
            .coerceIn(MIN_ENERGY_PER_SECOND, MAX_ENERGY_PER_SECOND)
    }
}
