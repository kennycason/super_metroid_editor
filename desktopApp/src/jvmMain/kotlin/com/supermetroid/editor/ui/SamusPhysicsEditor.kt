package com.supermetroid.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser

// ─── Samus Physics data definitions ─────────────────────────────────
// Legacy GUI controls select bytes within native words, swapped dwords or operands.
// ASM lenses own the complete storage unit and preserve the other bytes.

data class PhysicsField(
    val key: String,
    val label: String,
    val pcOffset: Int,      // PC file offset (no SMC header)
    val defaultValue: Int,  // Vanilla ROM value (single byte)
    val description: String = "",
)

data class PhysicsCategory(
    val name: String,
    val color: Color,
    val fields: List<PhysicsField>,
)

// ─── Physics value addresses ────────────────────────────────────────
// All addresses verified against vanilla ROM bytes. Each is a 1-byte value.
// Source: hex_edits.txt (begrimed.com community reference)

val PHYSICS_CATEGORIES = listOf(
    PhysicsCategory("Jump", Color(0xFF2196F3), listOf(
        // Jump heights: scale is additive — higher byte = higher jump
        PhysicsField("jump_height",       "Jump Height",              0x081EB9, 0x04, "Standing/springball height (03=lower, 04=normal, 05=higher)"),
        PhysicsField("hijump_height",     "Jump Height (Hi-Jump)",    0x081EC5, 0x06, "Height with Hi-Jump Boots (05=lower, 06=normal, 07=higher)"),
        PhysicsField("walljump",          "Walljump Height",          0x081ED1, 0x04, "Walljump height (03=lower, 04=normal, 05=higher)"),
        PhysicsField("walljump_hijump",   "Walljump (Hi-Jump)",       0x081EDD, 0x05, "Walljump height with Hi-Jump Boots (04=lower, 05=normal)"),
        PhysicsField("jump_water",        "Jump Height (Water)",      0x081EBB, 0x01, "Jump height underwater without Gravity Suit (00=lower, 01=normal)"),
        PhysicsField("hijump_water",      "Jump (Hi-Jump, Water)",    0x081EC7, 0x02, "Hi-Jump height underwater (01=lower, 02=normal)"),
        PhysicsField("walljump_water",    "Walljump (Water)",         0x081ED3, 0x00, "Walljump height underwater (00=normal, 01=higher)"),
        PhysicsField("jump_lava",         "Jump Height (Lava)",       0x081EBD, 0x02, "Jump height in lava/acid (01=lower, 02=normal)"),
        PhysicsField("hijump_lava",       "Jump (Hi-Jump, Lava)",     0x081EC9, 0x03, "Hi-Jump height in lava (02=lower, 03=normal)"),
        PhysicsField("walljump_lava",     "Walljump (Lava)",          0x081ED5, 0x02, "Walljump height in lava (01=lower, 02=normal)"),
    )),
    PhysicsCategory("Gravity & Falling", Color(0xFF9C27B0), listOf(
        PhysicsField("gravity",     "Gravity",          0x081EA2, 0x1C, "Downward acceleration per frame (0C=lower, 1C=normal, 2C=higher)"),
        PhysicsField("max_fall",    "Max Fall Speed",   0x081110, 0x05, "Terminal fall velocity (04=slower, 05=normal, 06=faster)"),
    )),
    PhysicsCategory("Running", Color(0xFF4CAF50), listOf(
        PhysicsField("run_accel",   "Run Acceleration", 0x081F64, 0x30, "Ground acceleration per frame"),
        PhysicsField("run_max",     "Run Max Speed",    0x081F65, 0x02, "Max run speed (>05 crashes intro, >07 glitches speed booster)"),
    )),
    PhysicsCategory("Air Control", Color(0xFF00BCD4), listOf(
        PhysicsField("air_spin",    "Air Speed (Spin Jump)",    0x081F7D, 0x01, "Horizontal speed mid-air during spin jump (01=normal)"),
        PhysicsField("air_normal",  "Air Speed (Normal Jump)",  0x081F71, 0x01, "Horizontal speed mid-air during normal jump/fall (01=normal)"),
        PhysicsField("air_physics", "Air Physics Mode",         0x081B2F, 0x02, "02=normal mid-air control, 04=no mid-air direction change"),
    )),
)

val ALL_PHYSICS_FIELDS: List<PhysicsField> = PHYSICS_CATEGORIES.flatMap { it.fields }

// ─── ROM access ────────────────────────────────────────────────────

internal fun readPhysicsValue(romParser: RomParser?, field: PhysicsField): Int? {
    if (romParser == null) return null
    return try {
        val rom = romParser.getRomData()
        val pc = field.pcOffset + if (rom.size % 0x8000 == 512) 512 else 0
        if (pc < rom.size) (rom[pc].toInt() and 0xFF) else null
    } catch (_: Exception) { null }
}

// ─── Main composable ───────────────────────────────────────────────

@Composable
fun SamusPhysicsEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
    onNavigateToAsm: (() -> Unit)? = null,
) {
    NativeDataPatchEditor(patch, editorState, romParser, modifier, onNavigateToAsm)
}
