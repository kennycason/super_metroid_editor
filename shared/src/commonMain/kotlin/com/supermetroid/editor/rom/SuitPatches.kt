package com.supermetroid.editor.rom

import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.SmPatch

/** Checked, composable suit-behavior patches shared by the GUI and headless API. */
object SuitPatches {
    const val GRAVITY_NO_HEAT_ID = "hex_gravity_no_heat_protect"
    const val GRAVITY_NO_DAMAGE_REDUCTION_ID = "hex_gravity_no_damage_reduction"
    const val VARIA_NO_DAMAGE_REDUCTION_ID = "hex_varia_no_damage_reduction"

    private const val COMPLEMENTARY_SUITS_ID = "bundled_complementary_suits"

    private const val HEAT_IMMUNITY_OPERAND_PC = 0x06E37D
    private const val HEAT_PALETTE_HOOK_PC = 0x06E3AB
    private const val HEAT_PALETTE_HELPER_PC = 0x2FFD80

    private fun hexBytes(source: String): List<Int> =
        source.trim().split(Regex("\\s+")).map { it.toInt(16) }

    /**
     * Vanilla only reselects the animated heat palette when its animation index changes.
     * This replacement also includes the equipped Varia/Gravity bits in that signature,
     * so changing or collecting a suit cannot leave the previous suit's palette active.
     */
    private val heatPaletteOriginal = hexBytes(
        """
        AD ED 1E CD EF 1E F0 2C 8D EF 1E 0A A8 A9 01 00
        9D CD 1E AD A2 09 89 20 00 F0 05 A9 E0 E3 80 0D
        89 01 00 F0 05 A9 00 E4 80 03 A9 20 E4 85 12 B1
        12 9D BD 1E 60
        """
    )

    private val heatPaletteHook =
        listOf(0x22, 0x80, 0xFD, 0xDF, 0x60) + // JSL $DF:FD80; RTS
            List(heatPaletteOriginal.size - 5) { 0xEA }

    private val heatPaletteHelper = hexBytes(
        """
        AD A2 09 29 21 00 EB 0D ED 1E CD EF 1E F0 2F 8D
        EF 1E AD ED 1E 0A A8 A9 01 00 9D CD 1E AD A2 09
        89 20 00 F0 05 A9 E0 E3 80 0D 89 01 00 F0 05 A9
        00 E4 80 03 A9 20 E4 85 12 B1 12 9D BD 1E 6B
        """
    )

    fun definitions(): List<SmPatch> = listOf(
        SmPatch(
            id = GRAVITY_NO_HEAT_ID,
            name = "Gravity Suit No Heat Protection",
            description = "Gravity keeps unrestricted liquid movement but no longer prevents heated-room damage; Varia still protects. Heat palettes stay synchronized when suits change.",
            enabled = false,
            writes = mutableListOf(
                PatchWrite(
                    offset = HEAT_IMMUNITY_OPERAND_PC.toLong(),
                    bytes = listOf(0x01),
                    expectedBytes = listOf(0x21),
                ),
                PatchWrite(
                    offset = HEAT_PALETTE_HOOK_PC.toLong(),
                    bytes = heatPaletteHook,
                    expectedBytes = heatPaletteOriginal,
                ),
                PatchWrite(
                    offset = HEAT_PALETTE_HELPER_PC.toLong(),
                    bytes = heatPaletteHelper,
                    expectedBytes = List(heatPaletteHelper.size) { 0xFF },
                ),
            ),
            incompatiblePatchIds = mutableListOf(COMPLEMENTARY_SUITS_ID),
        ),
        SmPatch(
            id = GRAVITY_NO_DAMAGE_REDUCTION_ID,
            name = "Gravity Suit No Damage Reduction",
            description = "Removes Gravity's direct and periodic damage reduction, including lava immunity, while retaining unrestricted movement in water, lava, and acid. Heated-room immunity is controlled separately.",
            enabled = false,
            writes = mutableListOf(
                // Lava handler: do not skip accumulation when Gravity is equipped.
                PatchWrite(0x0801DC, listOf(0x00), expectedBytes = listOf(0x20)),
                // Periodic damage handler: bypass Gravity's quarter-damage branch.
                PatchWrite(0x0869DD, listOf(0x00), expectedBytes = listOf(0x20)),
                // Enemy/projectile damage: bypass Gravity's quarter-damage branch.
                PatchWrite(0x102464, listOf(0x00), expectedBytes = listOf(0x20)),
            ),
            incompatiblePatchIds = mutableListOf(COMPLEMENTARY_SUITS_ID),
        ),
        SmPatch(
            id = VARIA_NO_DAMAGE_REDUCTION_ID,
            name = "Varia Suit No Damage Reduction",
            description = "Removes Varia's direct and periodic half-damage effect while retaining its heated-room immunity.",
            enabled = false,
            writes = mutableListOf(
                // Periodic damage handler: make the Varia test always false.
                PatchWrite(0x0869E2, listOf(0x00), expectedBytes = listOf(0x01)),
                // Enemy/projectile damage: CLC makes the following BCC always take full damage.
                PatchWrite(0x102468, listOf(0x18), expectedBytes = listOf(0x4A)),
            ),
            incompatiblePatchIds = mutableListOf(COMPLEMENTARY_SUITS_ID),
        ),
    )

    /** Upgrade the one-write version saved by SMEDIT before palette synchronization. */
    fun upgradeLegacyWrites(target: SmPatch, catalogPatch: SmPatch): Boolean {
        if (target.id != GRAVITY_NO_HEAT_ID || catalogPatch.id != GRAVITY_NO_HEAT_ID) return false
        val legacy = target.writes.singleOrNull() ?: return false
        if (legacy.offset != HEAT_IMMUNITY_OPERAND_PC.toLong() ||
            legacy.bytes != listOf(0x01) ||
            legacy.expectedBytes !in listOf(null, listOf(0x21))) {
            return false
        }
        target.writes.clear()
        target.writes.addAll(catalogPatch.writes.map { write ->
            write.copy(bytes = write.bytes.toList(), expectedBytes = write.expectedBytes?.toList())
        })
        return true
    }
}
