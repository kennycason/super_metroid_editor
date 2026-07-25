package com.supermetroid.editor.ui

internal fun shotBlockCategory(bts: Int): ShotCategory = when (bts) {
    0x00, 0x01, 0x02, 0x03 -> ShotCategory.BEAM
    0x04, 0x05, 0x06, 0x07 -> ShotCategory.HIDDEN
    0x08, 0x09 -> ShotCategory.PB
    0x0A, 0x0B -> ShotCategory.SUPER
    in 0x40..0x4F -> ShotCategory.DOOR
    else -> ShotCategory.BEAM
}

internal enum class ShotCategory { BEAM, SUPER, PB, HIDDEN, DOOR }

/**
 * Named BTS options for block types that have well-known sub-types.
 * Values from SMILE RF documentation / Super Metroid Mod Manual.
 */
internal fun btsOptionsForBlockType(blockType: Int): List<Pair<Int, String>> = when (blockType) {
    0x1 -> listOf(
        // Square shapes (special collision handling, shapes 0–4) + common variants
        0x00 to "Half solid: bottom",
        0x01 to "Half solid: side",
        0x02 to "Three-quarter solid",
        0x03 to "Quarter solid",
        0x04 to "Fully solid",
        0x07 to "Flat half (alt)",
        0x13 to "Passthrough (air)",
        // 45° floor (2-tile standard pair)
        0x14 to "45° floor (tile 1/2)",
        0x15 to "45° floor (tile 2/2)",
        // 45° floor (2-tile smooth pair)
        0x16 to "45° floor smooth (tile 1/2)",
        0x17 to "45° floor smooth (tile 2/2)",
        // Gentle floor (3-tile)
        0x18 to "Gentle floor (tile 1/3)",
        0x19 to "Gentle floor (tile 2/3)",
        0x1A to "Gentle floor (tile 3/3)",
        // Steep floor
        0x12 to "Steep floor (1 tile)",
        0x1B to "Steep floor (tile 1/2)",
        0x1C to "Steep floor (tile 2/2)",
        0x1D to "Steep floor (tile 1/3)",
        0x1E to "Steep floor (tile 2/3)",
        0x1F to "Steep floor (tile 3/3)",
        // Square ceiling
        0x80 to "Half solid ceiling: top",
        0x82 to "Three-quarter ceiling",
        0x83 to "Quarter ceiling",
        0x87 to "Flat half ceiling (alt)",
        0x93 to "Passthrough ceiling (air)",
        // 45° ceiling (2-tile standard pair)
        0x94 to "45° ceiling (tile 1/2)",
        0x95 to "45° ceiling (tile 2/2)",
        // 45° ceiling (2-tile smooth pair)
        0x96 to "45° ceiling smooth (tile 1/2)",
        0x97 to "45° ceiling smooth (tile 2/2)",
        // Gentle ceiling (3-tile)
        0x98 to "Gentle ceiling (tile 1/3)",
        0x99 to "Gentle ceiling (tile 2/3)",
        0x9A to "Gentle ceiling (tile 3/3)",
        // Steep ceiling
        0x92 to "Steep ceiling (1 tile)",
        0x9B to "Steep ceiling (tile 1/2)",
        0x9C to "Steep ceiling (tile 2/2)",
        0x9D to "Steep ceiling (tile 1/3)",
        0x9E to "Steep ceiling (tile 2/3)",
        0x9F to "Steep ceiling (tile 3/3)",
        // Uncommon/special shapes
        0x05 to "Valley (shallow V-trough)",
        0x06 to "Valley (deep V-trough)",
        0x0E to "Staircase (4-step)",
        0x0F to "Smooth staircase (8-step)",
        0x10 to "Fully solid (table)",
        0x11 to "Plateau (overshoot)",
    )
    0x2 -> listOf(
        0x00 to "Air (X-Ray safe)",
        0x02 to "Spike (low damage, passthrough)",
        0x0C to "Morph Lock (custom patch)",
        0x0D to "Morph Unlock (custom patch)",
    )
    0x3 -> listOf(
        0x08 to "Conveyor Right",
        0x09 to "Conveyor Left",
        0x82 to "Quicksand (Maridia)",
        0x85 to "Sandfall (Maridia)",
    )
    0x4 -> listOf(
        0x00 to "Shootable Air (reform, 1x1)",
        0x01 to "Shootable Air (reform, 2x1)",
        0x02 to "Shootable Air (reform, 1x2)",
        0x03 to "Shootable Air (reform, 2x2)",
        0x04 to "Shootable Air (permanent, 1x1)",
        0x05 to "Shootable Air (permanent, 2x1)",
        0x06 to "Shootable Air (permanent, 1x2)",
        0x07 to "Shootable Air (permanent, 2x2)",
    )
    0x7 -> listOf(
        0x00 to "Bomb Air (reform, 1x1)",
        0x01 to "Bomb Air (reform, 2x1)",
        0x02 to "Bomb Air (reform, 1x2)",
        0x03 to "Bomb Air (reform, 2x2)",
        0x04 to "Bomb Air (permanent, 1x1)",
        0x05 to "Bomb Air (permanent, 2x1)",
        0x06 to "Bomb Air (permanent, 1x2)",
        0x07 to "Bomb Air (permanent, 2x2)",
    )
    0xA -> listOf(
        0x00 to "Spike (normal, \$003C dmg)",
        0x01 to "Spike (weak, \$0010 dmg)",
        0x03 to "Spike (weak variant, \$0010 dmg)",
        0x0E to "Invisible Bridge (solid, X-Ray reveals)",
        0x0F to "Enemy-break Block",
    )
    0xB -> listOf(
        0x00 to "Crumble (reform, 1x1)",
        0x01 to "Crumble (reform, 2x1)",
        0x02 to "Crumble (reform, 1x2)",
        0x03 to "Crumble (reform, 2x2)",
        0x04 to "Crumble (permanent, 1x1)",
        0x05 to "Crumble (permanent, 2x1)",
        0x06 to "Crumble (permanent, 1x2)",
        0x07 to "Crumble (permanent, 2x2)",
        0x0B to "Enemy-Solid (air for Samus)",
        0x0E to "Speed Booster (reform)",
        0x0F to "Speed Booster (permanent)",
        0x10 to "Enemy-Solid (no X-Ray)",
    )
    0xC -> listOf(
        0x00 to "Any Weapon (reform, 1x1)",
        0x01 to "Any Weapon (reform, 2x1)",
        0x02 to "Any Weapon (reform, 1x2)",
        0x03 to "Any Weapon (reform, 2x2)",
        0x04 to "Hidden (reform, 1x1)",
        0x05 to "Hidden (reform, 2x1)",
        0x06 to "Hidden (reform, 1x2)",
        0x07 to "Hidden (reform, 2x2)",
        0x08 to "Power Bomb (reform)",
        0x09 to "Power Bomb (permanent)",
        0x0A to "Super Missile (reform)",
        0x0B to "Super Missile (permanent)",
    )
    0xE -> listOf(
        0x00 to "Grapple",
        0x01 to "Crumble Grapple (reform)",
        0x02 to "Crumble Grapple (permanent)",
    )
    0xF -> listOf(
        0x00 to "Bomb Block (reform, 1x1)",
        0x01 to "Bomb Block (reform, 2x1)",
        0x02 to "Bomb Block (reform, 1x2)",
        0x03 to "Bomb Block (reform, 2x2)",
        0x04 to "Bomb Block (permanent, 1x1)",
        0x05 to "Bomb Block (permanent, 2x1)",
        0x06 to "Bomb Block (permanent, 1x2)",
        0x07 to "Bomb Block (permanent, 2x2)",
    )
    else -> emptyList()
}

internal val blockTypeNames = mapOf(
    0x0 to "Air", 0x1 to "Slope", 0x2 to "X-Ray Air", 0x3 to "Treadmill",
    0x4 to "Shootable Air", 0x5 to "H-Extend", 0x6 to "Unused", 0x7 to "Air (Bomb)",
    0x8 to "Solid", 0x9 to "Door", 0xA to "Spike", 0xB to "Crumble",
    0xC to "Shot Block", 0xD to "V-Extend", 0xE to "Grapple", 0xF to "Bomb Block"
)
internal fun blockTypeName(type: Int): String = blockTypeNames[type] ?: "0x${type.toString(16).uppercase()}"
