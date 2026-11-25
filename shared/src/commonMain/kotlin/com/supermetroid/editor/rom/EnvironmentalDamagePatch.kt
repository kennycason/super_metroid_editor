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
    const val PER_AREA_RATES_KEY = "per_area_rates"
    const val TOURIAN_NO_HEAT_BABY_TO_MB_KEY = "tourian_no_heat_baby_to_mb"
    const val TOURIAN_NO_HEAT_ESCAPE_KEY = "tourian_no_heat_during_escape"
    const val LEGACY_ALL_ROOMS_HEATED_KEY = "all_rooms_heated"

    const val DEFAULT_TOURIAN_NO_HEAT_BABY_TO_MB = true
    const val DEFAULT_TOURIAN_NO_HEAT_ESCAPE = false

    const val MIN_ENERGY_PER_SECOND = 0
    const val MAX_ENERGY_PER_SECOND = 9999
    const val FRAMES_PER_SECOND = 60
    const val TOURIAN_AREA_INDEX = 5
    const val NORFAIR_AREA_INDEX = 2
    const val LOWER_NORFAIR_REGION_INDEX = 8
    const val LOWER_NORFAIR_ELEVATOR_ROOM_INDEX = 0x26
    const val LOWER_NORFAIR_MAIN_ROOM_INDEX = 0x34
    const val TOURIAN_UNSAFE_HEAT_FIRST_ROOM_INDEX = 0x07
    const val TOURIAN_UNSAFE_HEAT_END_ROOM_INDEX = 0x0D
    const val TOURIAN_ESCAPE_FIRST_ROOM_INDEX = 0x0E
    const val TOURIAN_ESCAPE_END_ROOM_INDEX = 0x12
    private const val FIXED_ONE = 0x10000L

    data class CheckedWrite(
        val pcOffset: Int,
        val bytes: List<Int>,
        val expectedBytes: List<Int>,
    )

    data class RoomLoadHook(
        val pcOffset: Int,
        val expectedBytes: List<Int>,
    )

    val NATIVE_ROOM_LOAD_HOOKS = listOf(
        RoomLoadHook(0x0020E5, listOf(0x22, 0x82, 0xAB, 0x89)), // JSL $89:AB82
        RoomLoadHook(0x0164C5, listOf(0x22, 0x82, 0xAB, 0x89)), // JSL $89:AB82
    )

    const val PER_AREA_HEAT_HELPER_PC = 0x2FFF00
    const val PER_AREA_HEAT_HELPER_SNES = 0xDFFF00
    const val REGION_INDEX_ROUTINE_SNES = 0xDFFE5A
    const val NATIVE_FX_LOADER_SNES = 0x89AB82
    const val NATIVE_PALETTE_FX_SPAWNER_SNES = 0x8DC4E9
    const val NATIVE_HEAT_OBJECT_ID = 0xF761

    private class BranchBuilder {
        val bytes = mutableListOf<Int>()
        private val labels = mutableMapOf<String, Int>()
        private val branches = mutableListOf<Pair<Int, String>>()

        fun emit(vararg values: Int) {
            bytes += values.toList()
        }

        fun label(name: String) {
            require(labels.put(name, bytes.size) == null) { "Duplicate branch label $name." }
        }

        fun branch(opcode: Int, target: String) {
            emit(opcode, 0)
            branches += bytes.lastIndex to target
        }

        fun finish(): List<Int> {
            for ((operandIndex, target) in branches) {
                val displacement = requireNotNull(labels[target]) { "Missing branch label $target." } - (operandIndex + 1)
                require(displacement in -128..127) { "Branch to $target is out of range." }
                bytes[operandIndex] = displacement and 0xFF
            }
            return bytes.toList()
        }
    }

    /**
     * In per-area mode, every region with a positive heat rate receives the native
     * heat object after its room FX load. Tourian additionally applies its safety
     * exclusions. Existing heat objects are detected before spawning a new one.
     */
    fun perAreaHeatCheckedWrites(
        heatedRegionIndexes: Set<Int>,
        noHeatBabyToMotherBrain: Boolean = DEFAULT_TOURIAN_NO_HEAT_BABY_TO_MB,
        noHeatDuringEscape: Boolean = DEFAULT_TOURIAN_NO_HEAT_ESCAPE,
        hooks: List<RoomLoadHook> = NATIVE_ROOM_LOAD_HOOKS,
        nativeFxLoaderSnes: Int = NATIVE_FX_LOADER_SNES,
        paletteFxSpawnerSnes: Int = NATIVE_PALETTE_FX_SPAWNER_SNES,
        heatObjectId: Int = NATIVE_HEAT_OBJECT_ID,
    ): List<CheckedWrite> {
        require(heatedRegionIndexes.isNotEmpty() && heatedRegionIndexes.all { index ->
            RATE_REGIONS.any { it.index == index }
        }) { "Per-area heat requires at least one valid heated region." }
        require(hooks.size == 2 && hooks.all { it.expectedBytes.size == 4 }) {
            "Per-area heat requires two checked four-byte room-load hooks."
        }
        require(nativeFxLoaderSnes in 0x808000..0xFFFFFF && paletteFxSpawnerSnes in 0x808000..0xFFFFFF) {
            "Per-area heat runtime targets must be mapped SNES ROM addresses."
        }
        require(heatObjectId in 0x8000..0xFFFF) { "Per-area heat object must stay in bank-local ROM." }

        val helper = BranchBuilder().apply {
            emit(0x22, *longAddress(nativeFxLoaderSnes).toIntArray()) // JSL native FX loader
            emit(0x08)                                               // PHP
            emit(0xC2, 0x30)                                         // REP #$30
            emit(0x48, 0xDA, 0x5A)                                   // PHA / PHX / PHY
            emit(
                0x20,
                REGION_INDEX_ROUTINE_SNES and 0xFF,
                REGION_INDEX_ROUTINE_SNES ushr 8 and 0xFF,
            ) // JSR region index

            val tourianNeedsSafety = TOURIAN_AREA_INDEX in heatedRegionIndexes &&
                (noHeatBabyToMotherBrain || noHeatDuringEscape)
            heatedRegionIndexes.sorted().forEach { regionIndex ->
                emit(0xC9, regionIndex, 0x00)                          // CMP #region
                branch(0xF0, if (regionIndex == TOURIAN_AREA_INDEX && tourianNeedsSafety) {
                    "tourianSafety"
                } else {
                    "scan"
                })
            }
            branch(0x80, "done")                                    // Unheated region

            if (tourianNeedsSafety) {
                label("tourianSafety")
                emit(0xAD, 0x9D, 0x07)                               // LDA $079D (RoomIndex)
            }
            if (TOURIAN_AREA_INDEX in heatedRegionIndexes && noHeatBabyToMotherBrain) {
                emit(0xC9, TOURIAN_UNSAFE_HEAT_FIRST_ROOM_INDEX, 0x00)
                branch(0x90, "scan")                                // Before Big Boy / Baby Metroid
                emit(0xC9, TOURIAN_UNSAFE_HEAT_END_ROOM_INDEX, 0x00)
                branch(0x90, "done")                                // Big Boy through Rinka Shaft
            }
            if (TOURIAN_AREA_INDEX in heatedRegionIndexes && noHeatDuringEscape) {
                emit(0xC9, TOURIAN_ESCAPE_FIRST_ROOM_INDEX, 0x00)
                branch(0x90, "scan")                                // Lower save / earlier rooms
                emit(0xC9, TOURIAN_ESCAPE_END_ROOM_INDEX, 0x00)
                branch(0x90, "done")                                // Four escape rooms
            }

            label("scan")
            emit(0xA2, 0x0E, 0x00)                                   // LDX #$000E
            label("scanLoop")
            emit(0xBD, 0x7D, 0x1E)                                   // LDA $1E7D,X (object IDs)
            emit(0xC9, heatObjectId and 0xFF, heatObjectId ushr 8)     // CMP #heat object
            branch(0xF0, "done")                                    // Already present
            emit(0xCA, 0xCA)                                         // DEX / DEX
            branch(0x10, "scanLoop")                                // BPL scanLoop
            emit(0xA0, heatObjectId and 0xFF, heatObjectId ushr 8)     // LDY #heat object
            emit(0x22, *longAddress(paletteFxSpawnerSnes).toIntArray()) // JSL spawn palette FX

            label("done")
            emit(0x7A, 0xFA, 0x68, 0x28, 0x6B)                        // PLY / PLX / PLA / PLP / RTL
        }.finish()

        return hooks.map { hook ->
            CheckedWrite(
                pcOffset = hook.pcOffset,
                bytes = listOf(0x22) + longAddress(PER_AREA_HEAT_HELPER_SNES),
                expectedBytes = hook.expectedBytes,
            )
        } + CheckedWrite(
            pcOffset = PER_AREA_HEAT_HELPER_PC,
            bytes = helper,
            expectedBytes = List(helper.size) { 0xFF },
        )
    }

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

    data class RateRegion(
        val index: Int,
        val key: String,
        val label: String,
    )

    /**
     * Damage tables use the eight native areas plus a logical Lower Norfair row.
     * Norfair's native room indexes are not contiguous: Lava Dive is $25, the
     * Lower Norfair elevator is $26, Upper Norfair continues at $27-$33, and the
     * remaining Lower Norfair rooms begin at $34.
     */
    val RATE_REGIONS: List<RateRegion> = AREAS.map { area ->
        RateRegion(
            index = area.index,
            key = area.key,
            label = if (area.index == NORFAIR_AREA_INDEX) "Upper Norfair" else area.label,
        )
    } + RateRegion(
        index = LOWER_NORFAIR_REGION_INDEX,
        key = "lower_norfair",
        label = "Lower Norfair",
    )

    /** Human-facing order keeps the two Norfair regions adjacent. */
    val DISPLAY_RATE_REGIONS: List<RateRegion> = listOf(0, 1, 2, 8, 3, 4, 5, 6, 7).map { index ->
        RATE_REGIONS.single { it.index == index }
    }

    data class AreaRateField(
        val area: RateRegion,
        val hazard: Field,
        val key: String = areaRateKey(area, hazard),
    )

    val AREA_RATE_FIELDS: List<AreaRateField> = DISPLAY_RATE_REGIONS.flatMap { area ->
        FIELDS.map { hazard -> AreaRateField(area, hazard) }
    }

    val CONFIG_KEYS: Set<String> = buildSet {
        addAll(FIELDS.map { it.key })
        add(PER_AREA_RATES_KEY)
        add(TOURIAN_NO_HEAT_BABY_TO_MB_KEY)
        add(TOURIAN_NO_HEAT_ESCAPE_KEY)
        addAll(AREA_RATE_FIELDS.map { it.key })
    }

    /** Accepted only so projects saved before the Tourian model do not fail validation. */
    val ACCEPTED_CONFIG_KEYS: Set<String> = CONFIG_KEYS + LEGACY_ALL_ROOMS_HEATED_KEY

    fun areaRateKey(area: RateRegion, hazard: Field): String =
        "${hazard.key.removeSuffix("_per_second")}_${area.key}_per_second"

    fun areaRateKey(areaKey: String, hazardKey: String): String {
        val area = RATE_REGIONS.single { it.key == areaKey }
        val hazard = FIELDS.single { it.key == hazardKey }
        return areaRateKey(area, hazard)
    }

    fun perAreaRatesEnabled(config: Map<String, Int>): Boolean = config[PER_AREA_RATES_KEY] == 1

    fun resolvedAreaRate(
        config: Map<String, Int>,
        area: RateRegion,
        hazard: Field,
        fallbackRates: Map<String, Int> = FIELDS.associate { it.key to it.defaultEnergyPerSecond },
    ): Int = config[areaRateKey(area, hazard)]
        ?: config[hazard.key]
        ?: fallbackRates[hazard.key]
        ?: hazard.defaultEnergyPerSecond

    fun heatedRateRegionIndexes(
        config: Map<String, Int>,
        fallbackRates: Map<String, Int> = FIELDS.associate { it.key to it.defaultEnergyPerSecond },
    ): Set<Int> {
        val heat = FIELDS.single { it.key == HEAT_KEY }
        return RATE_REGIONS.filter { area ->
            resolvedAreaRate(config, area, heat, fallbackRates) > 0
        }.mapTo(linkedSetOf()) { it.index }
    }

    fun rateRegionIndex(areaIndex: Int, roomIndex: Int): Int = when {
        areaIndex !in AREAS.indices -> 0
        areaIndex == NORFAIR_AREA_INDEX &&
            (roomIndex == LOWER_NORFAIR_ELEVATOR_ROOM_INDEX || roomIndex >= LOWER_NORFAIR_MAIN_ROOM_INDEX) ->
            LOWER_NORFAIR_REGION_INDEX
        else -> areaIndex
    }

    fun isTourianHeatExcluded(
        areaIndex: Int,
        roomIndex: Int,
        noHeatBabyToMotherBrain: Boolean = DEFAULT_TOURIAN_NO_HEAT_BABY_TO_MB,
        noHeatDuringEscape: Boolean = DEFAULT_TOURIAN_NO_HEAT_ESCAPE,
    ): Boolean = areaIndex == TOURIAN_AREA_INDEX && (
        noHeatBabyToMotherBrain &&
            roomIndex in TOURIAN_UNSAFE_HEAT_FIRST_ROOM_INDEX until TOURIAN_UNSAFE_HEAT_END_ROOM_INDEX ||
            noHeatDuringEscape && roomIndex in TOURIAN_ESCAPE_FIRST_ROOM_INDEX until TOURIAN_ESCAPE_END_ROOM_INDEX
        )

    fun tourianNoHeatBabyToMotherBrain(config: Map<String, Int>): Boolean =
        config[TOURIAN_NO_HEAT_BABY_TO_MB_KEY]?.let { it != 0 }
            ?: DEFAULT_TOURIAN_NO_HEAT_BABY_TO_MB

    fun tourianNoHeatDuringEscape(config: Map<String, Int>): Boolean =
        config[TOURIAN_NO_HEAT_ESCAPE_KEY]?.let { it != 0 }
            ?: DEFAULT_TOURIAN_NO_HEAT_ESCAPE

    /**
     * Per-area mode redirects only the three native 19-byte accumulator blocks.
     * Hazard detection, liquid movement, sound, and suit checks remain native.
     * The generated routines live immediately before the per-area heat
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

    private fun areaRoutine(tableSnes: Int, regionRoutineSnes: Int): List<Int> = buildList {
        add(0xDA)                         // PHX
        add(0x20)                         // JSR regionIndexRoutine
        addAll(listOf(regionRoutineSnes and 0xFF, regionRoutineSnes ushr 8 and 0xFF))
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

    private fun regionIndexRoutine(): List<Int> = listOf(
        0xAD, 0x9F, 0x07,       // LDA $079F (AreaIndex)
        0xC9, 0x02, 0x00,       // CMP #$0002 (Norfair)
        0xD0, 0x15,             // BNE nativeArea
        0xAD, 0x9D, 0x07,       // LDA $079D (RoomIndex)
        0xC9, 0x26, 0x00,       // CMP #$0026 (Lower Norfair elevator)
        0xF0, 0x09,             // BEQ lowerNorfair
        0xC9, 0x34, 0x00,       // CMP #$0034 (remaining Lower Norfair rooms)
        0xB0, 0x04,             // BCS lowerNorfair
        0xA9, 0x02, 0x00,       // LDA #$0002 (Upper Norfair)
        0x60,                   // RTS
        0xA9, 0x08, 0x00,       // lowerNorfair: LDA #$0008
        0x60,                   // RTS
        0xC9, 0x08, 0x00,       // nativeArea: CMP #$0008
        0x90, 0x03,             // BCC valid
        0xA9, 0x00, 0x00,       // Invalid index safely uses Crateria
        0x60,                   // valid: RTS
    )

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

        val placeholderRoutine = areaRoutine(0, 0)
        val routineAddresses = FIELDS.mapIndexed { index, field ->
            field.key to (PER_AREA_CODE_SNES + index * placeholderRoutine.size)
        }.toMap()
        val regionRoutine = regionIndexRoutine()
        val regionRoutineAddress = PER_AREA_CODE_SNES + placeholderRoutine.size * FIELDS.size
        require(regionRoutineAddress == REGION_INDEX_ROUTINE_SNES) {
            "Per-area region classifier moved without updating its room-load caller."
        }
        val tableStart = regionRoutineAddress + regionRoutine.size
        val tableAddresses = FIELDS.mapIndexed { index, field ->
            field.key to (tableStart + index * RATE_REGIONS.size * 4)
        }.toMap()
        val payload = buildList {
            FIELDS.forEach { field ->
                addAll(areaRoutine(tableAddresses.getValue(field.key), regionRoutineAddress))
            }
            addAll(regionRoutine)
            FIELDS.forEach { field ->
                RATE_REGIONS.forEach { area ->
                    val configured = config[areaRateKey(area, field)]
                        ?: config[field.key]
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
        require(PER_AREA_CODE_PC + payload.size <= PER_AREA_HEAT_HELPER_PC) {
            "Per-area environmental code overlaps the heat helper."
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
