package com.supermetroid.editor.libretro

/** Pure joypad input resolution for libretro input_state callbacks. */
internal object LibretroJoypadInput {
    private const val SNES_BUTTON_COUNT = 12

    fun applyButtonList(target: IntArray, buttons: List<Int>) {
        target.fill(0)
        for (index in buttons.indices) {
            if (index < SNES_BUTTON_COUNT) {
                target[index] = if (buttons[index] != 0) 1 else 0
            }
        }
    }

    fun resolveState(inputState: IntArray, device: Int, id: Int): Short {
        if (device != LibretroConstants.RETRO_DEVICE_JOYPAD) return 0
        if (id == LibretroConstants.RETRO_DEVICE_ID_JOYPAD_MASK) {
            return buildBitmask(inputState).toShort()
        }
        if (id !in 0 until SNES_BUTTON_COUNT) return 0
        return if (inputState[id] != 0) 1 else 0
    }

    fun buildBitmask(inputState: IntArray): Int {
        var mask = 0
        for (index in 0 until SNES_BUTTON_COUNT) {
            if (inputState[index] != 0) {
                mask = mask or (1 shl index)
            }
        }
        return mask
    }
}
