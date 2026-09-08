package com.supermetroid.editor.emulator

/** Watch-speed steps for the emulator FF button / Equals hotkey. */
object EmulatorPlaybackSpeed {
    val STEPS: IntArray = intArrayOf(1, 2, 4, 8, 16)
    const val MAX = 16

    fun next(current: Int): Int {
        val index = STEPS.indexOf(current)
        if (index < 0) return STEPS[1]
        return STEPS[(index + 1) % STEPS.size]
    }

    fun clamp(speed: Int): Int = speed.coerceIn(1, MAX)

    fun label(speed: Int): String = "${clamp(speed)}×"
}
