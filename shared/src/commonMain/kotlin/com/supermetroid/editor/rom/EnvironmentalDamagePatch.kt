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

    const val MIN_ENERGY_PER_SECOND = 0
    const val MAX_ENERGY_PER_SECOND = 9999
    const val FRAMES_PER_SECOND = 60
    private const val FIXED_ONE = 0x10000L

    data class CheckedWrite(
        val pcOffset: Int,
        val bytes: List<Int>,
        val expectedBytes: List<Int>,
    )

    /**
     * Makes heat a global semantic effect without repurposing the area-specific
     * palette-FX bits. Both room-load paths call a checked helper in vanilla
     * bank $DF. The helper runs the native FX loader first, then adds the native
     * heat object only when the room did not already spawn it.
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
            expectedBytes = List(37) { 0xFF },
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
