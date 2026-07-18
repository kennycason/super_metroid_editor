package com.supermetroid.editor.emulator

enum class SnesButton(val bit: Int) {
    B(0),
    Y(1),
    SELECT(2),
    START(3),
    UP(4),
    DOWN(5),
    LEFT(6),
    RIGHT(7),
    A(8),
    X(9),
    L(10),
    R(11),
}

object SnesInputBits {
    const val BUTTON_COUNT = 12
    const val VALID_MASK = (1 shl BUTTON_COUNT) - 1

    fun normalize(inputBits: Int): Int = inputBits and VALID_MASK

    fun fromPressed(vararg buttons: SnesButton): Int {
        var bits = 0
        for (button in buttons) {
            bits = bits or (1 shl button.bit)
        }
        return bits
    }

    fun fromButtonList(buttons: List<Int>): Int {
        var bits = 0
        val count = minOf(buttons.size, BUTTON_COUNT)
        for (index in 0 until count) {
            if (buttons[index] != 0) {
                bits = bits or (1 shl index)
            }
        }
        return bits
    }

    fun toButtonList(inputBits: Int): List<Int> {
        val normalized = normalize(inputBits)
        return List(BUTTON_COUNT) { index ->
            if ((normalized and (1 shl index)) != 0) 1 else 0
        }
    }
}
