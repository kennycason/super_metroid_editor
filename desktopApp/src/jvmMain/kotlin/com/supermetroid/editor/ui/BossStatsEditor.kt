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
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser

// ─── Enemy stats structure: bank $A0, 64 bytes per species ───
// Offset 4-5: HP (u16 LE), Offset 6-7: Contact Damage (u16 LE)

/**
 * A single editable stat field for a boss or sub-enemy.
 * [speciesId] is the 16-bit pointer into bank $A0.
 * [offset] is the byte offset within the 64-byte stat block (4 = HP, 6 = damage).
 */
data class BossStatField(
    val key: String,
    val label: String,
    val speciesId: Int,
    val offset: Int,
    val defaultValue: Int,
    val additionalSpeciesIds: List<Int> = emptyList(),
) {
    val snesAddress: Int get() = RomConstants.BANK_ENEMY_AI or speciesId
    val writeSpeciesIds: List<Int> get() = (listOf(speciesId) + additionalSpeciesIds).distinct()
}

data class BossDef(
    val name: String,
    val color: Color,
    val abbrev: String,
    val fields: List<BossStatField>
)

private fun hpField(key: String, label: String, id: Int, default: Int, vararg additionalIds: Int) =
    BossStatField(key, label, id, 4, default, additionalIds.toList())

private fun dmgField(key: String, label: String, id: Int, default: Int, vararg additionalIds: Int) =
    BossStatField(key, label, id, 6, default, additionalIds.toList())

// ─── Boss definitions with vanilla defaults ──────────────────────
// Species IDs verified against Kraid's room enemy set ($A1:9EB5) and ROM binary.
// Kraid uses bank $A0 IDs in the E2xx range; D2BF is Squeept (Norfair enemy), not Kraid.
// HP/damage defaults are vanilla values (read from ROM at export; these are fallbacks).

// Vanilla defaults verified against test ROM ($A0 bank enemy stat blocks).
val BOSS_DEFS = listOf(
    BossDef("Kraid", Color(0xFF8BC34A), "K", listOf(
        hpField("kraid_hp", "Kraid HP", 0xE2BF, 1000, 0xE2FF),
        dmgField("kraid_contact", "Contact Damage", 0xE2BF, 20, 0xE2FF),
        dmgField("kraid_belly_spike", "Belly Spike Damage", 0xE33F, 10, 0xE37F, 0xE3BF),
        dmgField("kraid_claw", "Flying Claw Damage", 0xE3FF, 20),
    )),
    BossDef("Phantoon", Color(0xFF9C27B0), "Ph", listOf(
        hpField("phantoon_hp", "Phantoon HP", 0xE4BF, 2500),
        dmgField("phantoon_contact", "Contact Damage", 0xE4BF, 40),
        dmgField("phantoon_flame1", "Flame (small)", 0xE4FF, 40),
        dmgField("phantoon_flame2", "Flame (medium)", 0xE53F, 40),
        dmgField("phantoon_flame3", "Flame (large)", 0xE57F, 40),
    )),
    BossDef("Ridley", Color(0xFFFF5722), "R", listOf(
        hpField("ridley_hp", "Ridley HP", 0xE17F, 18000),
        dmgField("ridley_contact", "Contact Damage", 0xE17F, 160),
    )),
    BossDef("Draygon", Color(0xFF00BCD4), "D", listOf(
        hpField("draygon_hp", "Draygon HP", 0xDE3F, 6000),
        dmgField("draygon_body", "Body Contact", 0xDE3F, 160),
        dmgField("draygon_eye", "Eye Contact", 0xDE7F, 160),
        dmgField("draygon_tail", "Tail Swipe", 0xDEBF, 160),
        dmgField("draygon_arms", "Arm Grab", 0xDEFF, 160),
    )),
    BossDef("Mother Brain", Color(0xFFE91E63), "MB", listOf(
        hpField("mb_phase1_hp", "Phase 1 HP (in glass)", 0xEC3F, 18000),
        hpField("mb_phase2_hp", "Phase 2 HP (walking)", 0xEC7F, 18000),
        dmgField("mb_phase1_contact", "Phase 1 Contact", 0xEC3F, 120),
        dmgField("mb_phase2_contact", "Phase 2 Contact", 0xEC7F, 120),
    )),
    BossDef("Spore Spawn", Color(0xFF4CAF50), "SS", listOf(
        hpField("sporespawn_hp", "Spore Spawn HP", 0xDF3F, 960),
        dmgField("sporespawn_contact", "Contact Damage", 0xDF3F, 12),
    )),
    BossDef("Crocomire", Color(0xFFFF9800), "Cr", listOf(
        hpField("crocomire_hp", "Crocomire HP", 0xDDBF, 32767),
        dmgField("crocomire_contact", "Contact Damage", 0xDDBF, 40),
    )),
    BossDef("Botwoon", Color(0xFF3F51B5), "Bw", listOf(
        hpField("botwoon_hp", "Botwoon HP", 0xF293, 3000),
        dmgField("botwoon_contact", "Contact Damage", 0xF293, 120),
    )),
    BossDef("Golden Torizo", Color(0xFFFFD700), "GT", listOf(
        hpField("golden_torizo_hp", "Golden Torizo HP", 0xEF7F, 13500),
        dmgField("golden_torizo_contact", "Contact Damage", 0xEF7F, 160),
    )),
    BossDef("Torizo", Color(0xFFA1887F), "T", listOf(
        hpField("torizo_hp", "Bomb Torizo HP", 0xEEFF, 800),
        dmgField("torizo_contact", "Contact Damage", 0xEEFF, 8),
    )),
)

val ALL_BOSS_FIELDS: List<BossStatField> = BOSS_DEFS.flatMap { it.fields }

// ─── Main composable ────────────────────────────────────────────

@Composable
fun BossStatsEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    val values = remember(patch.id, editorState.patchVersion) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (field in ALL_BOSS_FIELDS) {
            map[field.key] = stored?.get(field.key) ?: readStatFromRom(romParser, field) ?: field.defaultValue
        }
        map
    }

    fun apply(field: BossStatField, value: Int) {
        values[field.key] = value
        editorState.setPatchConfigData(patch.id, field.key, value)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Boss Stats Editor", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Edit HP and damage values for all major and mini-bosses. Changes apply when patch is enabled.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        for (boss in BOSS_DEFS) {
            BossCard(boss, values, ::apply)
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                for (field in ALL_BOSS_FIELDS) {
                    val rom = readStatFromRom(romParser, field) ?: field.defaultValue
                    apply(field, rom)
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text("Reset All to ROM Defaults", fontSize = 12.sp)
        }
    }
}

@Composable
private fun BossCard(
    boss: BossDef,
    values: Map<String, Int>,
    onApply: (BossStatField, Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = boss.color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, boss.color.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = boss.color.copy(alpha = 0.8f),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        boss.abbrev,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Text(boss.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Stat", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text("Value", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp), textAlign = TextAlign.Center)
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))

            for (field in boss.fields) {
                StatRow(
                    field = field,
                    value = values[field.key] ?: field.defaultValue,
                    onChange = { onApply(field, it) }
                )
            }
        }
    }
}

@Composable
private fun StatRow(
    field: BossStatField,
    value: Int,
    onChange: (Int) -> Unit
) {
    val isModified = value != field.defaultValue
    val isHp = field.offset == 4
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (isHp) "\u2764 " else "\u2694 ",
            fontSize = 12.sp,
            modifier = Modifier.width(20.dp)
        )
        Text(
            field.label,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal,
            color = if (isModified) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
        )
        StatInput(value, onChange, Modifier.width(72.dp))
    }
}

@Composable
private fun StatInput(
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
            fontSize = 13.sp,
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

// ─── ROM access ─────────────────────────────────────────────────

internal fun readStatFromRom(romParser: RomParser?, field: BossStatField): Int? {
    if (romParser == null) return null
    return try {
        val pc = romParser.snesToPc(field.snesAddress) + field.offset
        val rom = romParser.getRomData()
        if (pc + 1 < rom.size) {
            (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        } else null
    } catch (_: Exception) { null }
}
