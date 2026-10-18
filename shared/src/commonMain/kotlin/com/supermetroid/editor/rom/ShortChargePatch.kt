package com.supermetroid.editor.rom

/** The native counter/timer table, shared by new runs, stage transitions and equipment changes. */
object ShortChargePatch {
    const val CONFIG_TYPE = "short_charge_stages"
    const val STAGES_KEY = "stages"
    const val LEGACY_KEY = "taps"
    const val TABLE_SNES = 0x91B61F
    val defaults = listOf(1, 1, 1, 1, 2)
    val keys = setOf(STAGES_KEY, LEGACY_KEY)

    fun requestedStages(scalar: Int?, data: Map<String, Int>?): Int? {
        require(keys.containsAll(data.orEmpty().keys)) { "Unknown Short Charge settings." }
        val values = listOfNotNull(scalar, data?.get(STAGES_KEY), data?.get(LEGACY_KEY))
        require(values.distinct().size <= 1) { "Conflicting Short Charge stages/taps/scalar overrides; choose one value." }
        return values.firstOrNull()?.also { require(it in 0..4) { "Short Charge stages must fit 0–4." } }
    }

    fun decodeInitial(word: Int): Int? = (4 - (word ushr 8)).takeIf {
        word in 0..65535 && word and 255 != 0 && it in 0..4
    }
    fun decode(words: List<Int>): Int? = words.takeIf { it.size == 5 && it.drop(1).all { word -> word in 1..255 } }
        ?.first()?.let(::decodeInitial)
    fun encodeInitial(stages: Int, previous: Int): Int {
        require(stages in 0..4) { "Short Charge stages must fit 0–4." }
        return ((4 - stages) shl 8) or (previous and 255)
    }
    fun encode(stages: Int, words: List<Int>): List<Int> {
        require(decode(words) != null) { "Custom Short Charge counter/timer values are managed in ASM; restore supported timing before using the stage control." }
        return words.toMutableList().also { it[0] = encodeInitial(stages, it[0]) }
    }

    fun pc(snes: Int) = ((snes ushr 16) and 127) * 0x8000 + (snes and 0x7FFF)
    fun read(rom: ByteArray): List<Int> {
        val header = if (rom.size % 0x8000 == 512) 512 else 0
        fun byte(snes: Int): Int {
            val at = header + pc(snes)
            require(at in rom.indices) { "Short Charge ROM data is unavailable." }
            return rom[at].toInt() and 255
        }
        fun word(snes: Int) = byte(snes) or (byte(snes + 1) shl 8)
        for ((address, opcode) in listOf(0x9097A2 to 0xAF, 0x9085B6 to 0x1F, 0x91E67F to 0xAD)) {
            val size = if (opcode == 0xAD) 3 else 4
            require(byte(address) == opcode && word(address + 1) == (TABLE_SNES and 65535) &&
                (size == 3 || byte(address + 3) == 0x91) && byte(address + size) == 0x8D && word(address + size + 1) == 0x0B3E) {
                "Short Charge timer-table consumers changed; no fixed-address patch was applied."
            }
        }
        for ((address, opcode, operand) in listOf(
            Triple(0x908584, 0x89, 0x00FF), Triple(0x90858C, 0x89, 0x0400),
            Triple(0x908592, 0x69, 0x0100), Triple(0x908598, 0x89, 0x0400),
            Triple(0x9085AB, 0x29, 0x00FF), Triple(0x9085B3, 0x29, 0xFF00),
        )) require(byte(address) == opcode && word(address + 1) == operand) {
            "Short Charge counter/transition logic changed; no fixed-address patch was applied."
        }
        return (0..4).map { word(TABLE_SNES + it * 2) }
    }
}
