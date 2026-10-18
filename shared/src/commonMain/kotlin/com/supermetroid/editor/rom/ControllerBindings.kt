package com.supermetroid.editor.rom

/** The game's action defaults, not Controller_Input_Bitmasks (the options menu's button decoder). */
object ControllerBindings {
    const val CONFIG_TYPE = "controller_config"
    data class Slot(val key: String, val label: String, val defaultMask: Int, val ramSymbol: String,
                    val ramAddress: Int, val newSaveOperand: Int, val resetOperand: Int)
    val slots = listOf(
        Slot("shot", "Shot", 0x0040, "ShotBinding", 0x09B2, 0x81B331, 0x82F22D),
        Slot("jump", "Jump", 0x0080, "JumpBinding", 0x09B4, 0x81B325, 0x82F233),
        Slot("dash", "Dash (Run)", 0x8000, "DashBinding", 0x09B6, 0x81B32B, 0x82F239),
        Slot("item_select", "Item Select", 0x2000, "ItemSelectBinding", 0x09BA, 0x81B33D, 0x82F245),
        Slot("item_cancel", "Item Cancel", 0x4000, "ItemCancelBinding", 0x09B8, 0x81B337, 0x82F23F),
        Slot("angle_up", "Aim Up", 0x0010, "AimUpBinding", 0x09BE, 0x81B343, 0x82F24B),
        Slot("angle_down", "Aim Down", 0x0020, "AimDownBinding", 0x09BC, 0x81B349, 0x82F251),
    )
    val defaults = slots.associate { it.key to it.defaultMask }
    val masks = defaults.values.toSet()
    val menuMasks = listOf(0x40, 0x80, 0x8000, 0x2000, 0x4000, 0x20, 0x10, 0x200, 0x100)
    fun requireMapping(values: Map<String, Int>) {
        require(values.keys == defaults.keys && values.values.toSet() == masks && values.size == masks.size) {
            "Controller settings must assign X, A, B, Select, Y, L and R exactly once, as in the game's options menu."
        }
    }

    /** Sparse overrides displace occupied buttons, matching the in-game single-binding swap. */
    fun resolve(base: Map<String, Int>, overrides: Map<String, Int>): Map<String, Int> {
        requireMapping(base)
        require(defaults.keys.containsAll(overrides.keys) && overrides.values.all { it in masks } &&
            overrides.values.toSet().size == overrides.size) { "Controller overrides contain an unknown action, unsupported button, or duplicate assignment." }
        val result = overrides.toMutableMap()
        for (slot in slots) if (slot.key !in result && base.getValue(slot.key) !in result.values) result[slot.key] = base.getValue(slot.key)
        val free = base.values.filter { it !in result.values }.iterator()
        for (slot in slots) if (slot.key !in result) result[slot.key] = free.next()
        requireMapping(result)
        return slots.associate { it.key to result.getValue(it.key) }
    }
    fun swap(base: Map<String, Int>, key: String, mask: Int) = resolve(base, mapOf(key to mask))
    fun pc(snes: Int) = ((snes ushr 16) and 0x7F) * 0x8000 + (snes and 0x7FFF)
    private fun header(rom: ByteArray) = if (rom.size % 0x8000 == 512) 512 else 0
    private fun word(rom: ByteArray, at: Int) = (rom[at].toInt() and 255) or ((rom[at + 1].toInt() and 255) shl 8)
    fun read(rom: ByteArray, reset: Boolean = false): Map<String, Int> {
        val offset = header(rom)
        require(0x17587 + offset <= rom.size && menuMasks.withIndex().all { (index, mask) ->
            word(rom, offset + 0x17575 + index * 2) == mask
        } && slots.withIndex().all { (index, slot) -> word(rom, offset + 0x1754A + index * 2) == slot.ramAddress }) {
            "Controller options-menu button/action decoder changed; restore it before editing defaults."
        }
        val values = slots.associate { slot ->
            val at = pc(if (reset) slot.resetOperand else slot.newSaveOperand) + offset
            require(at > 0 && at + 4 < rom.size && rom[at - 1].toInt() and 255 == 0xA9 &&
                rom[at + 2].toInt() and 255 == 0x8D && word(rom, at + 3) == slot.ramAddress) {
                "${slot.label}: controller initialization changed; no fixed-address controller patch was applied."
            }
            slot.key to word(rom, at)
        }
        requireMapping(values)
        return values
    }
    data class Write(val pc: Int, val value: Int)
    fun writes(rom: ByteArray?, overrides: Map<String, Int>): List<Write> {
        if (overrides.isEmpty()) return emptyList()
        val fresh = rom?.let { read(it) } ?: defaults
        val reset = rom?.let { read(it, reset = true) } ?: defaults
        val target = resolve(fresh, overrides)
        // Include displaced actions, and repair the same requested mapping in Reset to default.
        return slots.filter { it.key in overrides || target[it.key] != fresh[it.key] || target[it.key] != reset[it.key] }
            .flatMap { slot -> listOf(Write(pc(slot.newSaveOperand), target.getValue(slot.key)), Write(pc(slot.resetOperand), target.getValue(slot.key))) }
    }
}
