package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
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
// Native layout; ASM builds resolve the live symbolic pointer bindings instead.
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
    val chargedKey: String get() = "${key}_charged"
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

/** Old normal-only configs retain their established 3× behavior; explicit charged words are independent. */
internal fun beamDamageConfigValues(data: Map<String, Int>): Map<String, Int> {
    val allowed = ALL_BEAMS.flatMap { listOf(it.key, it.chargedKey) }.toSet()
    require(data.keys.all { it in allowed }) { "Unknown beam damage config keys: ${data.keys - allowed}." }
    val result = data.toMutableMap()
    for (beam in ALL_BEAMS) data[beam.key]?.let { normal -> result.putIfAbsent(beam.chargedKey, normal * 3) }
    require(result.values.all { it in 0..65535 }) { "Beam damage must fit a 16-bit word (0–65535); specify charged damage explicitly if 3× would overflow." }
    return result
}

private var selectedBeamDamageKey by mutableStateOf<String?>(null)
internal fun selectBeamDamage(key: String?) { selectedBeamDamageKey = key }

private val BEAM_COLORS = BASE_BEAMS.associate { it.key to it.color }
private val BEAM_ASM_LINK_WIDTH = 64.dp

// ─── Main composable ────────────────────────────────────────────────────

@Composable
fun BeamDamageEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
    onNavigateToAsm: (() -> Unit)? = null,
) {
    val sourceMode = editorState.asmNavigationEnabled
    val bridge = editorState.beamDamageBridge.takeIf { sourceMode }
    @Suppress("UNUSED_VARIABLE") val revision = bridge?.revision
    @Suppress("UNUSED_VARIABLE") val patchRevision = editorState.patchVersion
    val config = runCatching { beamDamageConfigValues(patch.configData.orEmpty()) }
    fun value(beam: BeamDef, charged: Boolean): Int {
        val key = if (charged) beam.chargedKey else beam.key
        if (sourceMode) return bridge?.value(patch, key)?.value ?: beam.defaultDamage * if (charged) 3 else 1
        return config.getOrNull()?.get(key) ?: readDamageFromRom(romParser, beam, charged) ?: beam.defaultDamage * if (charged) 3 else 1
    }
    fun applyBeam(beam: BeamDef, charged: Boolean, damage: Int) {
        if (!sourceMode && !charged) {
            // Freeze the currently displayed charged value before changing a normal-only legacy config.
            editorState.setPatchConfigData(patch.id, beam.chargedKey, value(beam, true))
        }
        editorState.setPatchConfigData(patch.id, if (charged) beam.chargedKey else beam.key, damage)
    }
    fun openSource(key: String? = null) {
        if (editorState.openBeamDamageSource(key)) onNavigateToAsm?.invoke()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Beam Damage", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (sourceMode) TextButton(onClick = { openSource() }, modifier = Modifier.testTag("beam-damage-asm")) { Text("ASM") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Normal and charged damage are independent. " + if (sourceMode) "ASM edits use source Undo, not patch toggles. Shared records edit together." else "Changes apply when this patch is enabled.",
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        config.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        @Composable fun Group(title: String, beams: List<BeamDef>, groupModifier: Modifier = Modifier) {
            Column(groupModifier) {
                SectionLabel(title)
                Spacer(Modifier.height(8.dp)); HeaderRow(sourceMode)
                Divider(Modifier.padding(vertical = 4.dp))
                for (beam in beams) {
                    val problems = listOf(beam.key, beam.chargedKey).associateWith { key ->
                        if (sourceMode) bridge?.value(patch, key)?.problem ?: if (bridge == null) "Wait for beam source indexing." else null
                        else config.exceptionOrNull()?.message
                    }
                    BeamRow(beam, value(beam, false), value(beam, true), problems,
                        onDamageChange = { charged, damage -> applyBeam(beam, charged, damage) },
                        onAsm = if (sourceMode) ({ key -> openSource(key) }) else null)
                    problems.values.filterNotNull().distinct().forEach { problem ->
                        Text("Last-good / read-only: $problem", color = MaterialTheme.colorScheme.error, fontSize = LocalEditorTheme.current.fontSize.value.detail)
                    }
                    if (problems.values.any { it?.contains("patch override") == true }) TextButton(onClick = {
                        runCatching { bridge?.useSourceValues(beam.key) }.onFailure { editorState.postStatus(it.message ?: "Cannot use source values.") }
                    }) { Text("Use ASM values") }
                }
            }
        }
        BoxWithConstraints {
            if (maxWidth < 1000.dp) Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Group("Base Beams", BASE_BEAMS); Group("Combined Beams", BEAM_COMBOS)
            } else Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Group("Base Beams", BASE_BEAMS, Modifier.weight(1f))
                Group("Combined Beams", BEAM_COMBOS, Modifier.weight(1.3f))
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    if (sourceMode) runCatching { requireNotNull(bridge).resetToOriginal() }
                        .onFailure { editorState.postStatus(it.message ?: "Cannot reset beam source.") }
                    else for (beam in ALL_BEAMS) {
                        editorState.setPatchConfigData(patch.id, beam.key, beam.defaultDamage)
                        editorState.setPatchConfigData(patch.id, beam.chargedKey, beam.defaultDamage * 3)
                    }
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
private fun HeaderRow(sourceMode: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(30.dp))
        Text("Beam", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        if (sourceMode) Spacer(Modifier.width(BEAM_ASM_LINK_WIDTH))
        Text("Normal", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp), textAlign = TextAlign.Center)
        Text("Charged", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp), textAlign = TextAlign.Center)
    }
}

// ─── Base beam row ──────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BeamRow(
    beam: BeamDef,
    damage: Int,
    charged: Int,
    problems: Map<String, String?>,
    onDamageChange: (Boolean, Int) -> Unit,
    onAsm: ((String) -> Unit)?,
) {
    val isModified = damage != beam.defaultDamage || charged != beam.defaultDamage * 3
    val selected = selectedBeamDamageKey?.removeSuffix("_charged") == beam.key
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selectedBeamDamageKey) { if (selected) requester.bringIntoView() }
    Surface(
        modifier = Modifier.bringIntoViewRequester(requester).fillMaxWidth().padding(vertical = 2.dp).testTag("beam-row-${beam.key}"),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else if (isModified) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (beam.components.isEmpty()) BeamIcon(beam.color, beam.abbrev) else ComboPips(beam.components)
            Spacer(Modifier.width(8.dp))
            Text(beam.name, fontSize = LocalEditorTheme.current.fontSize.value.body, modifier = Modifier.weight(1f),
                fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal)
            if (onAsm != null) TextButton(onClick = {
                onAsm(when {
                    problems[beam.key] != null -> beam.key
                    problems[beam.chargedKey] != null -> beam.chargedKey
                    selectedBeamDamageKey == beam.chargedKey -> beam.chargedKey
                    else -> beam.key
                })
            },
                modifier = Modifier.width(BEAM_ASM_LINK_WIDTH).testTag("beam-asm-${beam.key}"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) { Text("ASM", fontSize = LocalEditorTheme.current.fontSize.value.detail, maxLines = 1, softWrap = false) }
            DamageInput(damage, { onDamageChange(false, it) }, Modifier.width(72.dp).testTag("beam-damage-${beam.key}")
                .onFocusChanged { if (it.isFocused) selectBeamDamage(beam.key) }, problems[beam.key] == null)
            Spacer(Modifier.width(4.dp))
            DamageInput(charged, { onDamageChange(true, it) }, Modifier.width(72.dp).testTag("beam-damage-${beam.chargedKey}")
                .onFocusChanged { if (it.isFocused) selectBeamDamage(beam.chargedKey) }, problems[beam.chargedKey] == null)
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
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(5)
            val number = filtered.toIntOrNull()
            if (filtered.isEmpty() || number != null && number in 0..65535) {
                text = filtered
                number?.let(onChange)
            }
        },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = LocalEditorTheme.current.fontSize.value.heading,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(32.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

// ─── ROM access ─────────────────────────────────────────────────────────

private fun readDamageFromRom(romParser: RomParser?, beam: BeamDef, charged: Boolean): Int? {
    if (romParser == null) return null
    return try {
        val pc = romParser.snesToPc(if (charged) beam.chargedSnesAddress else beam.snesAddress)
        val rom = romParser.getRomData()
        if (pc + 1 < rom.size) {
            (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        } else null
    } catch (_: Exception) { null }
}
