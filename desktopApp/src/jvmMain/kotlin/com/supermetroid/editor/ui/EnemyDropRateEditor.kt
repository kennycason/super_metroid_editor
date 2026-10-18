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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser

// ─── Drop rate table layout ────────────────────────────────────────
// Species header offset +$3A points to a 6-byte table in bank $B4.
// Each byte is a probability weight (0-255). They should sum to ~0xFF.
// Order: Small Energy, Large Energy, Missile, Nothing, Super Missile, Power Bomb

data class DropSlot(
    val index: Int,
    val label: String,
    val shortLabel: String,
    val color: Color,
)

val DROP_SLOTS = listOf(
    DropSlot(0, "Small Energy", "Sm.E", Color(0xFF4CAF50)),
    DropSlot(1, "Large Energy", "Lg.E", Color(0xFF8BC34A)),
    DropSlot(2, "Missile", "Miss", Color(0xFFFF9800)),
    DropSlot(3, "Nothing", "None", Color(0xFF9E9E9E)),
    DropSlot(4, "Super Missile", "Supr", Color(0xFF4CAF50)),
    DropSlot(5, "Power Bomb", "PB", Color(0xFFF44336)),
)

// ─── ROM access: read drop table pointer and data ──────────────────

internal fun readDropTable(romParser: RomParser?, speciesId: Int): IntArray? {
    if (romParser == null) return null
    return try {
        val rom = romParser.getRomData()
        val headerPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        if (headerPc + 0x3C > rom.size) return null
        // Read 16-bit pointer at offset +$3A
        val ptr = (rom[headerPc + 0x3A].toInt() and 0xFF) or
                ((rom[headerPc + 0x3B].toInt() and 0xFF) shl 8)
        if (ptr == 0 || ptr == 0xFFFF) return null
        // Resolve to PC address in bank $B4
        val dropPc = romParser.snesToPc(0xB40000 or ptr)
        if (dropPc + 6 > rom.size) return null
        IntArray(6) { rom[dropPc + it].toInt() and 0xFF }
    } catch (_: Exception) { null }
}

private fun resolveDropPc(romParser: RomParser, speciesId: Int): Int? {
    val rom = romParser.getRomData()
    val headerPc = romParser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
    if (headerPc + 0x3C > rom.size) return null
    val ptr = (rom[headerPc + 0x3A].toInt() and 0xFF) or
            ((rom[headerPc + 0x3B].toInt() and 0xFF) shl 8)
    if (ptr == 0 || ptr == 0xFFFF) return null
    return romParser.snesToPc(0xB40000 or ptr)
}

// ─── Main composable ───────────────────────────────────────────────

@Composable
fun EnemyDropRateEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
    onNavigateToAsm: ((Int) -> Unit)? = null,
) {
    val tuning = editorState.enemyTuningBridge.takeIf { editorState.asmNavigationEnabled }
    val values = remember(editorState.project.projectId, patch.id, editorState.patchVersion, tuning, tuning?.revision) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (e in ENEMY_DEFS) {
            val romDrops = readDropTable(romParser, e.speciesId)
            for (slot in DROP_SLOTS) {
                val key = "${e.key}_drop${slot.index}"
                map[key] = tuning?.value(patch, key)?.value ?: stored?.get(key) ?: romDrops?.get(slot.index) ?: 0
            }
        }
        map
    }

    fun apply(key: String, value: Int) {
        editorState.setPatchConfigData(patch.id, key, value)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState())
            .widthIn(min = 540.dp)
            .padding(20.dp)
    ) {
        Text("Enemy Drop Rate Editor", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Edit item drop probabilities for each enemy. Values are weights (0-255) that should sum to ~255.",
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        if (editorState.asmNavigationEnabled) Text("Weights edit the actual ASM table. Shared-table edits affect every listed species; source Undo restores them. Patch toggles do not undo source edits.",
            fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val grouped = ENEMY_DEFS.groupBy { it.category }
        for (cat in listOf("Crawler", "Hopper", "Flyer", "Spawner", "Aquatic", "Pirate", "Special")) {
            val enemies = grouped[cat] ?: continue
            DropCategorySection(cat, enemies, values, ::apply, editorState, onNavigateToAsm)
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                for (e in ENEMY_DEFS) {
                    val romDrops = readDropTable(romParser, e.speciesId)
                    for (slot in DROP_SLOTS) {
                        apply("${e.key}_drop${slot.index}", romDrops?.get(slot.index) ?: 0)
                    }
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text("Reset All to ROM Defaults", fontSize = LocalEditorTheme.current.fontSize.value.body)
        }
    }
}

@Composable
private fun DropCategorySection(
    category: String,
    enemies: List<EnemyDef>,
    values: Map<String, Int>,
    onApply: (String, Int) -> Unit,
    editorState: EditorState,
    onNavigateToAsm: ((Int) -> Unit)?,
) {
    val catColor = when (category) {
        "Crawler" -> Color(0xFF8BC34A)
        "Hopper" -> Color(0xFF4CAF50)
        "Flyer" -> Color(0xFF2196F3)
        "Spawner" -> Color(0xFFFF9800)
        "Aquatic" -> Color(0xFF00BCD4)
        "Pirate" -> Color(0xFFF44336)
        "Special" -> Color(0xFF9C27B0)
        else -> Color.Gray
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = catColor.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, catColor.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(category, fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.SemiBold, color = catColor)
            Spacer(Modifier.height(6.dp))

            // Header row
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enemy", fontSize = LocalEditorTheme.current.fontSize.value.detail, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(220.dp))
                for (slot in DROP_SLOTS) {
                    Text(slot.shortLabel, fontSize = LocalEditorTheme.current.fontSize.value.statusBar, fontWeight = FontWeight.Medium,
                        color = slot.color,
                        modifier = Modifier.width(42.dp), textAlign = TextAlign.Center)
                }
                Text("Sum", fontSize = LocalEditorTheme.current.fontSize.value.statusBar, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))

            for (e in enemies) {
                DropRow(e, values, onApply, editorState, onNavigateToAsm)
            }
        }
    }
}

@Composable
private fun DropRow(
    enemy: EnemyDef,
    values: Map<String, Int>,
    onApply: (String, Int) -> Unit,
    editorState: EditorState,
    onNavigateToAsm: ((Int) -> Unit)?,
) {
    val tuning = editorState.enemyTuningBridge.takeIf { editorState.asmNavigationEnabled }
    val problems = DROP_SLOTS.mapNotNull { tuning?.view("${enemy.key}_drop${it.index}")?.problem }.distinct()
    val dropValues = DROP_SLOTS.map { slot -> values["${enemy.key}_drop${slot.index}"] ?: 0 }
    val sum = dropValues.sum()
    val sumOk = sum in 254..256 // allow +-1 rounding

    Row(
        modifier = enemyTuningFocus(enemy.speciesId).padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(220.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(enemy.name, fontSize = LocalEditorTheme.current.fontSize.value.body, modifier = Modifier.weight(1f))
                if (editorState.asmNavigationEnabled && onNavigateToAsm != null) TextButton(onClick = {
                    onNavigateToAsm(RomConstants.BANK_ENEMY_AI or enemy.speciesId)
                    editorState.openEnemyTuningSource("${enemy.key}_drop0")
                }) { Text("ASM", fontSize = LocalEditorTheme.current.fontSize.value.detail) }
            }
            val shared = tuning?.consumers("${enemy.key}_drop0").orEmpty()
            if (shared.size > 1) Text("Shared table: ${shared.joinToString { RomParser.enemyName(it) }}. Edits affect all.",
                fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.tertiary)
            problems.forEach { Text("Last-good / read-only: $it", fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.error) }
            if (problems.any { it.contains("patch override") }) TextButton(onClick = {
                runCatching { tuning?.useSourceValues(DROP_SLOTS.mapNotNull { slot ->
                    val key = "${enemy.key}_drop${slot.index}"
                    key.takeIf { tuning.view(key).problem?.contains("patch override") == true }
                }.toSet()) }.onFailure { editorState.postStatus(it.message ?: "Cannot use this source table.") }
            }) { Text("Use ASM weights", fontSize = LocalEditorTheme.current.fontSize.value.detail) }
        }
        for (slot in DROP_SLOTS) {
            val key = "${enemy.key}_drop${slot.index}"
            DropInput(values[key] ?: 0, { onApply(key, it) }, Modifier.width(42.dp), enabled = !editorState.asmNavigationEnabled || tuning != null && problems.isEmpty())
        }
        Text(
            sum.toString(),
            fontSize = LocalEditorTheme.current.fontSize.value.detail,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = if (sumOk) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFFF5722),
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DropInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var lastExternal by remember { mutableStateOf(value) }
    if (value != lastExternal) { text = value.toString(); lastExternal = value }
    BasicTextField(
        enabled = enabled,
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(3)
            text = filtered
            filtered.toIntOrNull()?.let { lastExternal = it.coerceIn(0, 255); onChange(lastExternal) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = LocalEditorTheme.current.fontSize.value.detail,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(22.dp)
            .padding(horizontal = 1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
            .padding(horizontal = 2.dp, vertical = 2.dp)
    )
}
