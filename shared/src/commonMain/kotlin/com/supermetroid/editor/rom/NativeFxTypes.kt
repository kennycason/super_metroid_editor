package com.supermetroid.editor.rom

/** Verified against FXType_Function_Pointers at $83:AC18–AC45, not legacy SMILE labels. */
object NativeFxTypes {
    private val handlers = mapOf(
        0x00 to "None", 0x02 to "Lava", 0x04 to "Acid", 0x06 to "Water",
        0x08 to "Spores", 0x0A to "Rain", 0x0C to "Fog",
        0x20 to "Scrolling Sky", 0x22 to "Scrolling Sky (unused)",
        0x24 to "Fireflea", 0x26 to "Tourian Statues",
        0x28 to "Ceres Ridley", 0x2A to "Ceres Elevator", 0x2C to "Ceres Haze",
    )
    val names: Map<Int, String> = (0..0x2C step 2).associateWith { handlers[it] ?: "Unused 0x${it.toString(16).uppercase().padStart(2, '0')}" }
    fun commentName(type: Int): String = handlers[type] ?: if (type in names) "Unused (no native FX handler)" else "Unknown"
}

/**
 * Native FX-header byte +12 (`fxBitC`) as implemented by bank $88.
 *
 * These masks deliberately do not use the historical SMILE labels. The two
 * BG2 options select different per-scanline displacement tables; the actual
 * tide-amplitude flags are bits 6 and 7.
 */
object NativeLiquidOptions {
    const val FLOW_LEFT = 0x01
    const val BG_ANIMATE = 0x02
    const val BG_LIQUID = 0x04
    const val LARGE_TIDE = 0x40
    const val SMALL_TIDE = 0x80

    val labels: Map<Int, String> = linkedMapOf(
        FLOW_LEFT to "Flow Left",
        BG_ANIMATE to "BG Animate",
        BG_LIQUID to "BG Liquid",
        LARGE_TIDE to "Large Tide",
        SMALL_TIDE to "Small Tide",
    )
    val semanticNames: Map<String, Int> = linkedMapOf(
        "flow_left" to FLOW_LEFT,
        "bg_animate" to BG_ANIMATE,
        "bg_liquid" to BG_LIQUID,
        "large_tide" to LARGE_TIDE,
        "small_tide" to SMALL_TIDE,
    )
    val knownMask: Int = labels.keys.fold(0, Int::or)

    fun decodedNames(value: Int): List<String> = semanticNames.mapNotNull { (name, mask) ->
        name.takeIf { value and mask != 0 }
    }
}
