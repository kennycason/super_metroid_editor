package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
        dmgField("phantoon_flame1", "Eye Contact", 0xE4FF, 40),
        dmgField("phantoon_flame2", "Tentacles Contact", 0xE53F, 40),
        dmgField("phantoon_flame3", "Mouth Contact", 0xE57F, 40),
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

private var selectedBossStatsKey by mutableStateOf<String?>(null)
internal fun selectBossStats(key: String?) { selectedBossStatsKey = key }

// ─── Main composable ────────────────────────────────────────────

@Composable
fun BossStatsEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
    onNavigateToAsm: (() -> Unit)? = null,
) {
    val sourceMode = editorState.asmNavigationEnabled
    val bridge = editorState.bossStatsBridge.takeIf { sourceMode }
    @Suppress("UNUSED_VARIABLE") val revision = bridge?.revision
    @Suppress("UNUSED_VARIABLE") val patchRevision = editorState.patchVersion
    val views = ALL_BOSS_FIELDS.associate { field -> field.key to if (sourceMode) bridge?.value(patch, field.key)
        ?: com.supermetroid.editor.asm.AsmBossStatsView(field.defaultValue, "Wait for boss stats source indexing.") else null }
    val values = ALL_BOSS_FIELDS.associate { field -> field.key to if (sourceMode) views[field.key]?.value ?: field.defaultValue
        else patch.configData?.get(field.key) ?: readStatFromRom(romParser, field) ?: field.defaultValue }

    fun apply(field: BossStatField, value: Int) {
        editorState.setPatchConfigData(patch.id, field.key, value)
    }
    fun openSource(key: String? = null) {
        if (editorState.openBossStatsSource(key)) onNavigateToAsm?.invoke()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Boss Stats Editor", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (sourceMode) {
                TextButton(
                    onClick = { openSource() },
                    modifier = Modifier.testTag("boss-stats-asm"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("ASM", fontSize = LocalEditorTheme.current.fontSize.value.body, maxLines = 1, softWrap = false) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (sourceMode) "ASM edits use source Undo, not patch toggles. Matching sub-species edit together; independent sub-species values are managed in ASM."
            else "Edit HP and damage values for all major and mini-bosses. Changes apply on ROM export.",
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        for (boss in BOSS_DEFS) {
            BossCard(boss, values, ::apply, sourceMode, views, bridge, editorState, ::openSource)
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                if (sourceMode) {
                    runCatching { requireNotNull(bridge).resetToOriginal() }
                        .onFailure { editorState.postStatus(it.message ?: "Cannot reset boss stats source.") }
                } else {
                    for (field in ALL_BOSS_FIELDS) {
                        val rom = readStatFromRom(romParser, field) ?: field.defaultValue
                        apply(field, rom)
                    }
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(if (sourceMode) "Reset All to Original ASM" else "Reset All to ROM Defaults", fontSize = LocalEditorTheme.current.fontSize.value.body)
        }
    }
}

@Composable
private fun BossCard(
    boss: BossDef,
    values: Map<String, Int>,
    onApply: (BossStatField, Int) -> Unit,
    sourceMode: Boolean = false,
    views: Map<String, com.supermetroid.editor.asm.AsmBossStatsView?> = emptyMap(),
    bridge: com.supermetroid.editor.asm.AsmBossStatsBridge? = null,
    editorState: EditorState? = null,
    onOpenSource: ((String?) -> Unit)? = null,
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
                        fontSize = LocalEditorTheme.current.fontSize.value.body,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Text(boss.name, fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Stat", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text("Value", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp), textAlign = TextAlign.Center)
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))

            for (field in boss.fields) {
                val view = views[field.key]
                val problem = view?.problem
                StatRow(
                    field = field,
                    value = values[field.key] ?: field.defaultValue,
                    onChange = { onApply(field, it) },
                    enabled = problem == null,
                    onAsm = if (sourceMode && onOpenSource != null) ({ onOpenSource(field.key) }) else null,
                )
                if (problem != null) {
                    Text(
                        "Last-good / read-only: $problem",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = LocalEditorTheme.current.fontSize.value.detail,
                        modifier = Modifier.padding(start = 24.dp),
                    )
                    if (view.conflict && bridge != null && editorState != null) {
                        OutlinedButton(
                            onClick = {
                                runCatching { bridge.useSourceValues(field.key) }
                                    .onFailure { editorState.postStatus(it.message ?: "Cannot use source values.") }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.padding(start = 24.dp),
                        ) { Text("Use ASM values", fontSize = LocalEditorTheme.current.fontSize.value.detail) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatRow(
    field: BossStatField,
    value: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    onAsm: (() -> Unit)? = null,
) {
    val isModified = value != field.defaultValue
    val isHp = field.offset == 4
    val selected = selectedBossStatsKey == field.key
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selectedBossStatsKey) { if (selected) requester.bringIntoView() }
    Row(
        modifier = Modifier
            .bringIntoViewRequester(requester)
            .testTag("boss-row-${field.key}")
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(4.dp))
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (isHp) "\u2764 " else "\u2694 ",
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            modifier = Modifier.width(20.dp)
        )
        Text(
            field.label,
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            modifier = Modifier.weight(1f),
            fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal,
            color = if (isModified) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
        )
        if (onAsm != null) TextButton(onClick = onAsm, modifier = Modifier.width(64.dp).testTag("boss-asm-${field.key}"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text("ASM", fontSize = LocalEditorTheme.current.fontSize.value.detail, maxLines = 1, softWrap = false)
        }
        StatInput(value, onChange, Modifier.width(72.dp).testTag("boss-stat-${field.key}")
            .onFocusChanged { if (it.isFocused) selectBossStats(field.key) }, enabled)
    }
}

@Composable
private fun StatInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by remember(value, enabled) { mutableStateOf(value.toString()) }

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
