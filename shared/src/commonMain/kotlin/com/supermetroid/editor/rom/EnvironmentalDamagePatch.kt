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

    const val MIN_ENERGY_PER_SECOND = 0
    const val MAX_ENERGY_PER_SECOND = 9999
    const val FRAMES_PER_SECOND = 60
    private const val FIXED_ONE = 0x10000L

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
