package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser

// ─── Beam damage table: $93:8431, stride 22 bytes per projectile type ───
// Discovered by scanning the vanilla ROM for the known damage value pattern.
// Each entry is: [damage: u16] [10 pointers: u16×10] = 22 bytes total.

const val BEAM_DAMAGE_TABLE_SNES = 0x938431
const val BEAM_DAMAGE_ENTRY_STRIDE = 22

private val BEAM_COLOR_POWER  = Color(0xFFFFD700)
private val BEAM_COLOR_ICE    = Color(0xFF00BFFF)
private val BEAM_COLOR_SPAZER = Color(0xFFADFF2F)
private val BEAM_COLOR_WAVE   = Color(0xFF9370DB)
private val BEAM_COLOR_PLASMA = Color(0xFF00FF7F)

/**
 * Beam definition: maps a beam key to its ROM table entry indices,
 * default damage value, display color, and component beams (for combos).
 */
data class BeamDef(
    val key: String,
    val name: String,
    val abbrev: String,
    val color: Color,
    val defaultDamage: Int,
    val entryIndex: Int,
    val chargedEntryIndex: Int,
    val components: List<String> = emptyList()
) {
    val snesAddress: Int get() = BEAM_DAMAGE_TABLE_SNES + entryIndex * BEAM_DAMAGE_ENTRY_STRIDE
    val chargedSnesAddress: Int get() = BEAM_DAMAGE_TABLE_SNES + chargedEntryIndex * BEAM_DAMAGE_ENTRY_STRIDE
}

val BASE_BEAMS = listOf(
    BeamDef("power",  "Power Beam", "p", BEAM_COLOR_POWER,  20, entryIndex = 0,  chargedEntryIndex = 12),
    BeamDef("ice",    "Ice Beam",   "I", BEAM_COLOR_ICE,    30, entryIndex = 5,  chargedEntryIndex = 17),
    BeamDef("spazer", "Spazer",     "S", BEAM_COLOR_SPAZER, 40, entryIndex = 1,  chargedEntryIndex = 13),
    BeamDef("wave",   "Wave Beam",  "W", BEAM_COLOR_WAVE,   50, entryIndex = 6,  chargedEntryIndex = 19),
    BeamDef("plasma", "Plasma",     "P", BEAM_COLOR_PLASMA, 150, entryIndex = 7, chargedEntryIndex = 18),
)

val BEAM_COMBOS = listOf(
    BeamDef("is",  "Ice + Spazer",              "IS",  Color.Transparent, 60,  entryIndex = 2,  chargedEntryIndex = 14, listOf("ice", "spazer")),
    BeamDef("iw",  "Ice + Wave",                "IW",  Color.Transparent, 60,  entryIndex = 8,  chargedEntryIndex = 20, listOf("ice", "wave")),
    BeamDef("ws",  "Wave + Spazer",             "WS",  Color.Transparent, 70,  entryIndex = 9,  chargedEntryIndex = 21, listOf("wave", "spazer")),
    BeamDef("iws", "Ice + Wave + Spazer",       "IWS", Color.Transparent, 100, entryIndex = 3,  chargedEntryIndex = 15, listOf("ice", "wave", "spazer")),
    BeamDef("ip",  "Ice + Plasma",              "IP",  Color.Transparent, 200, entryIndex = 11, chargedEntryIndex = 22, listOf("ice", "plasma")),
    BeamDef("wp",  "Wave + Plasma",             "WP",  Color.Transparent, 250, entryIndex = 10, chargedEntryIndex = 23, listOf("wave", "plasma")),
    BeamDef("iwp", "Ice + Wave + Plasma",       "IWP", Color.Transparent, 300, entryIndex = 4,  chargedEntryIndex = 16, listOf("ice", "wave", "plasma")),
)

val ALL_BEAMS = BASE_BEAMS + BEAM_COMBOS

private val BEAM_COLORS = BASE_BEAMS.associate { it.key to it.color }

// ─── Main composable ────────────────────────────────────────────────────

@Composable
fun BeamDamageEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    val damages = remember(patch.id, editorState.patchVersion) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (beam in ALL_BEAMS) {
            map[beam.key] = stored?.get(beam.key) ?: readDamageFromRom(romParser, beam) ?: beam.defaultDamage
        }
        map
    }

    fun applyBeam(beam: BeamDef, value: Int) {
        damages[beam.key] = value
        editorState.setPatchConfigData(patch.id, beam.key, value)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Beam Damage Override", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Edit base and combined beam damages. Charged shots deal 3\u00D7 damage. Changes apply when patch is enabled.",
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // ── Left: Base Beams ──
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Base Beams")
                Spacer(Modifier.height(8.dp))
                HeaderRow()
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                for (beam in BASE_BEAMS) {
                    BaseBeamRow(
                        beam = beam,
                        damage = damages[beam.key] ?: beam.defaultDamage,
                        onDamageChange = { applyBeam(beam, it) }
                    )
                }
            }

            // ── Right: Combined Beams ──
            Column(modifier = Modifier.weight(1.3f)) {
                SectionLabel("Combined Beams")
                Spacer(Modifier.height(8.dp))
                ComboHeaderRow()
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                for (combo in BEAM_COMBOS) {
                    ComboBeamRow(
                        combo = combo,
                        damage = damages[combo.key] ?: combo.defaultDamage,
                        onDamageChange = { applyBeam(combo, it) }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    for (beam in ALL_BEAMS) applyBeam(beam, beam.defaultDamage)
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("Reset to Vanilla", fontSize = LocalEditorTheme.current.fontSize.value.body)
            }
        }
    }
}

// ─── Section label ──────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = LocalEditorTheme.current.fontSize.value.heading,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

// ─── Header rows ────────────────────────────────────────────────────────

@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(30.dp))
        Text("Beam", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text("Dmg", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
        Text("Charged", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ComboHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(56.dp))
        Text("Combination", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text("Dmg", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
        Text("Charged", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
    }
}

// ─── Base beam row ──────────────────────────────────────────────────────

@Composable
private fun BaseBeamRow(
    beam: BeamDef,
    damage: Int,
    onDamageChange: (Int) -> Unit
) {
    val isModified = damage != beam.defaultDamage
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(6.dp),
        color = if (isModified) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BeamIcon(beam.color, beam.abbrev)
            Spacer(Modifier.width(8.dp))
            Text(beam.name, fontSize = LocalEditorTheme.current.fontSize.value.body, modifier = Modifier.weight(1f),
                fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal)
            DamageInput(damage, onDamageChange, Modifier.width(60.dp))
            Spacer(Modifier.width(4.dp))
            ChargedLabel(damage * 3)
        }
    }
}

// ─── Combo beam row ─────────────────────────────────────────────────────

@Composable
private fun ComboBeamRow(
    combo: BeamDef,
    damage: Int,
    onDamageChange: (Int) -> Unit
) {
    val isModified = damage != combo.defaultDamage
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(6.dp),
        color = if (isModified) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ComboPips(combo.components)
            Spacer(Modifier.width(8.dp))
            Text(combo.name, fontSize = LocalEditorTheme.current.fontSize.value.body, modifier = Modifier.weight(1f),
                fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal)
            DamageInput(damage, onDamageChange, Modifier.width(60.dp))
            Spacer(Modifier.width(4.dp))
            ChargedLabel(damage * 3)
        }
    }
}

// ─── Beam icons ─────────────────────────────────────────────────────────

@Composable
private fun BeamIcon(color: Color, letter: String) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A2E),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ComboPips(componentKeys: List<String>) {
    Row(
        modifier = Modifier.width(48.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (key in componentKeys) {
            val color = BEAM_COLORS[key] ?: Color.Gray
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// ─── Damage input field ─────────────────────────────────────────────────

@Composable
private fun DamageInput(
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
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(0, 9999)) }
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

// ─── Charged damage label ───────────────────────────────────────────────

@Composable
private fun ChargedLabel(chargedDamage: Int) {
    Text(
        chargedDamage.toString(),
        fontSize = LocalEditorTheme.current.fontSize.value.body,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(56.dp)
    )
}

// ─── ROM access ─────────────────────────────────────────────────────────

private fun readDamageFromRom(romParser: RomParser?, beam: BeamDef): Int? {
    if (romParser == null) return null
    return try {
        val pc = romParser.snesToPc(beam.snesAddress)
        val rom = romParser.getRomData()
        if (pc + 1 < rom.size) {
            (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        } else null
    } catch (_: Exception) { null }
}
