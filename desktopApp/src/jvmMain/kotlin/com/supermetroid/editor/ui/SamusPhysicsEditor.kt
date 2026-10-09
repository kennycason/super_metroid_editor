package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser

// ─── Samus Physics data definitions ─────────────────────────────────
// All values are single bytes at verified PC offsets.
// Addresses confirmed from hex_edits.txt (begrimed.com) and direct ROM inspection.
// DO NOT treat as 16-bit: these are 1-byte values in jump/gravity/speed tables.

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
        val pc = field.pcOffset
        if (pc < rom.size) (rom[pc].toInt() and 0xFF) else null
    } catch (_: Exception) { null }
}

// ─── Main composable ───────────────────────────────────────────────

@Composable
fun SamusPhysicsEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    val values = remember(patch.id, editorState.patchVersion) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (field in ALL_PHYSICS_FIELDS) {
            map[field.key] = stored?.get(field.key)
                ?: readPhysicsValue(romParser, field)
                ?: field.defaultValue
        }
        map
    }

    fun apply(field: PhysicsField, value: Int) {
        values[field.key] = value
        editorState.setPatchConfigData(patch.id, field.key, value)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Samus Physics Editor", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Edit Samus movement physics — jump heights, gravity, run speed, air control, and more. " +
                "Values are single-byte table entries. Changes apply when the patch is enabled.",
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        for (cat in PHYSICS_CATEGORIES) {
            PhysicsCategoryCard(cat, values, ::apply)
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                for (field in ALL_PHYSICS_FIELDS) {
                    apply(field, readPhysicsValue(romParser, field) ?: field.defaultValue)
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text("Reset All to ROM Defaults", fontSize = LocalEditorTheme.current.fontSize.value.body)
        }
    }
}

@Composable
private fun PhysicsCategoryCard(
    category: PhysicsCategory,
    values: Map<String, Int>,
    onApply: (PhysicsField, Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = category.color.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, category.color.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(category.name, fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.SemiBold, color = category.color)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Property", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text("Value", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(80.dp), textAlign = TextAlign.Center)
                Text("Hex", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))

            for (field in category.fields) {
                PhysicsRow(field, values[field.key] ?: field.defaultValue, { onApply(field, it) })
            }
        }
    }
}

@Composable
private fun PhysicsRow(
    field: PhysicsField,
    value: Int,
    onChange: (Int) -> Unit
) {
    val isModified = value != field.defaultValue

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                field.label,
                fontSize = LocalEditorTheme.current.fontSize.value.body,
                fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal,
                color = if (isModified) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            if (field.description.isNotEmpty()) {
                Text(
                    field.description,
                    fontSize = LocalEditorTheme.current.fontSize.value.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        PhysicsInput(value, onChange, Modifier.width(80.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            String.format("%04X", value),
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PhysicsInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(5)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(0, 65535)) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = LocalEditorTheme.current.fontSize.value.heading,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}
