package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

// ─── Common enemy definitions ────────────────────────────────────
// Species ID (bank $A0 pointer), HP at offset 4, damage at offset 6.

data class EnemyDef(
    val key: String,
    val name: String,
    val speciesId: Int,
    val defaultHp: Int,
    val defaultDamage: Int,
    val category: String = "Common"
)

// Defaults are fallbacks; actual values are always read from ROM at runtime.
// Species IDs must be on the 0x40-byte grid (address ≡ 0x3F or 0x13 mod 0x40).
// "Zoomer grey" is not a separate species — grey Zoomers are palette variants of 0xDCFF.
val ENEMY_DEFS = listOf(
    // ── Crawlers & Hoppers ──
    EnemyDef("zoomer", "Zoomer", 0xDCFF, 15, 5, "Crawler"),
    EnemyDef("geemer_horiz", "Geemer (horizontal)", 0xDC3F, 15, 5, "Crawler"),
    EnemyDef("sidehopper", "Sidehopper", 0xD93F, 60, 20, "Hopper"),
    EnemyDef("sidehopper_large", "Sidehopper (large)", 0xD97F, 120, 80, "Hopper"),
    EnemyDef("dessgeega", "Dessgeega", 0xD9BF, 320, 80, "Hopper"),

    // ── Flyers ──
    EnemyDef("tripper", "Tripper", 0xD7FF, 20, 40, "Flyer"),
    EnemyDef("reo", "Reo", 0xD87F, 20, 40, "Flyer"),
    EnemyDef("waver", "Waver", 0xD63F, 100, 16, "Flyer"),
    EnemyDef("ripper", "Ripper", 0xD47F, 200, 5, "Flyer"),
    EnemyDef("ripper2", "Ripper II", 0xD3FF, 400, 20, "Flyer"),
    EnemyDef("kihunter", "Kihunter", 0xDFBF, 20, 40, "Flyer"),
    EnemyDef("kihunter_green", "Kihunter (green)", 0xE03F, 400, 30, "Flyer"),

    // ── Wall & Ceiling ──
    EnemyDef("sciser", "Sciser", 0xD77F, 100, 12, "Crawler"),
    EnemyDef("zeela", "Zeela", 0xDC7F, 100, 16, "Crawler"),
    EnemyDef("sova", "Sova", 0xDD3F, 100, 16, "Crawler"),
    EnemyDef("beetom", "Beetom", 0xDCBF, 50, 8, "Crawler"),

    // ── Spawners & Pipes ──
    EnemyDef("rinka", "Rinka", 0xD23F, 10, 40, "Spawner"),
    EnemyDef("zeb", "Zeb", 0xF193, 20, 8, "Spawner"),
    EnemyDef("zebbo", "Zebbo", 0xF1D3, 20, 8, "Spawner"),
    EnemyDef("gamet", "Gamet", 0xF213, 20, 12, "Spawner"),

    // ── Aquatic (Maridia) ──
    EnemyDef("oum", "Oum", 0xD7BF, 200, 20, "Aquatic"),
    EnemyDef("skultera", "Skultera", 0xD6FF, 100, 12, "Aquatic"),
    EnemyDef("yard", "Yard", 0xDBBF, 100, 16, "Aquatic"),

    // ── Space Pirates ──
    EnemyDef("pirate_basic", "Space Pirate", 0xF353, 20, 15, "Pirate"),
    EnemyDef("pirate_norfair", "Space Pirate (Norfair)", 0xF413, 600, 40, "Pirate"),
    EnemyDef("pirate_maridia", "Space Pirate (Maridia)", 0xF453, 600, 40, "Pirate"),
    EnemyDef("pirate_tourian", "Space Pirate (Tourian)", 0xF493, 800, 50, "Pirate"),
    EnemyDef("pirate_mk2_norfair", "Space Pirate Mk.II (Norfair)", 0xF593, 1000, 60, "Pirate"),
    EnemyDef("pirate_mk2_tourian", "Space Pirate Mk.II (Tourian)", 0xF613, 1200, 70, "Pirate"),

    // ── Special ──
    EnemyDef("metroid", "Big Metroid", 0xEEBF, 1, 0, "Special"),
    EnemyDef("fireflea", "Fireflea", 0xD6BF, 1, 0, "Special"),
    EnemyDef("cacatac", "Cacatac", 0xCFFF, 200, 20, "Special"),
    EnemyDef("magdollite", "Magdollite", 0xD4BF, 200, 30, "Special"),
    EnemyDef("boyon", "Boyon", 0xCEBF, 100, 16, "Special"),
)

private val CATEGORY_ORDER = listOf("Crawler", "Hopper", "Flyer", "Spawner", "Aquatic", "Pirate", "Special")

// ─── Main composable ────────────────────────────────────────────

@Composable
fun EnemyStatsEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    // All enemy fields (HP, DMG, AI pointers, GFX) share this map.
    // Keys follow the pattern: "${enemyKey}_fieldName" (e.g., "zoomer_hp", "zoomer_touchAi")
    val values = remember(patch.id, editorState.patchVersion) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (e in ENEMY_DEFS) {
            map["${e.key}_hp"] = stored?.get("${e.key}_hp") ?: readEnemyStat(romParser, e.speciesId, 4) ?: e.defaultHp
            map["${e.key}_dmg"] = stored?.get("${e.key}_dmg") ?: readEnemyStat(romParser, e.speciesId, 6) ?: e.defaultDamage
            // Restore any stored AI/GFX field overrides
            if (stored != null) {
                for (suffix in listOf("initAi", "mainAi", "touchAi", "shotAi", "hurtAi", "frozenAi",
                    "grappleAi", "deathAnim", "extraGfx", "pbVuln")) {
                    val key = "${e.key}_$suffix"
                    stored[key]?.let { map[key] = it }
                }
            }
        }
        map
    }

    fun apply(key: String, value: Int) {
        values[key] = value
        editorState.setPatchConfigData(patch.id, key, value)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            "Edit HP, damage, AI routines, and graphics. Click an enemy name to expand details.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        val grouped = ENEMY_DEFS.groupBy { it.category }
        for (cat in CATEGORY_ORDER) {
            val enemies = grouped[cat] ?: continue
            EnemyCategorySection(cat, enemies, values, ::apply, romParser, editorState)
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                for (e in ENEMY_DEFS) {
                    apply("${e.key}_hp", readEnemyStat(romParser, e.speciesId, 4) ?: e.defaultHp)
                    apply("${e.key}_dmg", readEnemyStat(romParser, e.speciesId, 6) ?: e.defaultDamage)
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text("Reset All to ROM Defaults", fontSize = 12.sp)
        }
    }
}

@Composable
private fun EnemyCategorySection(
    category: String,
    enemies: List<EnemyDef>,
    values: Map<String, Int>,
    onApply: (String, Int) -> Unit,
    romParser: RomParser?,
    editorState: EditorState? = null,
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
            Text(category, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = catColor)
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enemy", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text("HP", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp), textAlign = TextAlign.Center)
                Text("DMG", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp), textAlign = TextAlign.Center)
            }
            Text("Click enemy name to expand AI pointers and graphics info",
                fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp))
            Divider(modifier = Modifier.padding(vertical = 4.dp))

            for (e in enemies) {
                EnemyRow(e, values, onApply, romParser, editorState)
            }
        }
    }
}

@Composable
private fun EnemyRow(
    enemy: EnemyDef,
    values: Map<String, Int>,
    onApply: (String, Int) -> Unit,
    romParser: RomParser? = null,
    editorState: EditorState? = null,
) {
    val hp = values["${enemy.key}_hp"] ?: enemy.defaultHp
    val dmg = values["${enemy.key}_dmg"] ?: enemy.defaultDamage
    val hpModified = hp != enemy.defaultHp
    val dmgModified = dmg != enemy.defaultDamage
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                enemy.name,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f).clickable { expanded = !expanded },
                fontWeight = if (hpModified || dmgModified) FontWeight.Medium else FontWeight.Normal,
                color = if (hpModified || dmgModified) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            EnemyStatInput(hp, { onApply("${enemy.key}_hp", it) }, Modifier.width(72.dp))
            Spacer(Modifier.width(4.dp))
            EnemyStatInput(dmg, { onApply("${enemy.key}_dmg", it) }, Modifier.width(72.dp))
        }
        // Expandable AI/GFX detail section
        if (expanded && romParser != null) {
            EnemyDetailSection(enemy, values, onApply, romParser, editorState)
        }
    }
}

/** Expandable section showing AI pointers, tile data, and layer control for an enemy species. */
@Composable
private fun EnemyDetailSection(
    enemy: EnemyDef,
    values: Map<String, Int>,
    onApply: (String, Int) -> Unit,
    romParser: RomParser,
    editorState: EditorState? = null,
) {
    val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
    val aiFields = listOf(
        EnemyField("Init AI",    0x12, "${enemy.key}_initAi",    "Runs once when enemy spawns"),
        EnemyField("Main AI",    0x16, "${enemy.key}_mainAi",    "Runs every frame (movement, behavior)"),
        EnemyField("Touch AI",   0x30, "${enemy.key}_touchAi",   "Runs when Samus touches enemy"),
        EnemyField("Shot AI",    0x32, "${enemy.key}_shotAi",    "Runs when projectile hits enemy"),
        EnemyField("Hurt AI",    0x1C, "${enemy.key}_hurtAi",    "Runs when enemy takes damage"),
        EnemyField("Frozen AI",  0x1E, "${enemy.key}_frozenAi",  "Runs while enemy is frozen"),
        EnemyField("Grapple",    0x1A, "${enemy.key}_grappleAi", "Reaction to Grapple Beam"),
        EnemyField("Death Anim", 0x22, "${enemy.key}_deathAnim", "Death explosion/effect type"),
    )
    val gfxFields = listOf(
        EnemyField("Extra GFX",  0x18, "${enemy.key}_extraGfx",  "Pointer to additional graphics"),
        EnemyField("PB Vuln",    0x28, "${enemy.key}_pbVuln",    "0000=vulnerable, xx80=immune"),
    )

    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Species ID + AI bank
            val aiBank = readEnemyStat(romParser, enemy.speciesId, 0x10)
            Row {
                Text("Species", fontSize = 9.sp, color = detailColor, modifier = Modifier.width(72.dp))
                Text("\$${enemy.speciesId.toString(16).uppercase()}", fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(12.dp))
                Text("AI Bank", fontSize = 9.sp, color = detailColor)
                Spacer(Modifier.width(4.dp))
                Text("\$${(aiBank ?: 0).toString(16).uppercase().padStart(4, '0')}", fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(4.dp))

            // AI pointers (2-column layout)
            Text("AI Routines", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = detailColor)
            Spacer(Modifier.height(2.dp))
            for (row in aiFields.chunked(2)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (field in row) {
                        val romVal = readEnemyStat(romParser, enemy.speciesId, field.offset) ?: 0
                        val curVal = values[field.key] ?: romVal
                        val isModified = values.containsKey(field.key) && curVal != romVal
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(field.label, fontSize = 10.sp,
                                    color = if (isModified) Color(0xFFFFCC00) else detailColor,
                                    fontWeight = if (isModified) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.width(68.dp))
                                EnemyHexInput(curVal, { onApply(field.key, it) }, Modifier.width(56.dp))
                                val routineDesc = aiRoutineLabel(curVal)
                                if (routineDesc.isNotEmpty()) {
                                    Text(routineDesc, fontSize = 9.sp, color = Color(0xFF80C0FF),
                                        modifier = Modifier.padding(start = 4.dp))
                                }
                                if (isModified) {
                                    Text("↩", fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 4.dp).clickable { onApply(field.key, romVal) })
                                }
                            }
                            if (field.tooltip.isNotEmpty()) {
                                Text(field.tooltip, fontSize = 9.sp, color = detailColor.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // GFX fields
            Text("Graphics", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = detailColor)
            Spacer(Modifier.height(2.dp))

            // Tile data pointer (3 bytes)
            val tileSize = readEnemyStat(romParser, enemy.speciesId, 0x00)
            val tilePtrLo = readEnemyStat(romParser, enemy.speciesId, 0x36)
            val tilePtrBank = readEnemyStatByte(romParser, enemy.speciesId, 0x38)
            val layerCtrl = readEnemyStatByte(romParser, enemy.speciesId, 0x39)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Tile Size", fontSize = 9.sp, color = detailColor, modifier = Modifier.width(64.dp))
                Text("${tileSize ?: 0} bytes", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(12.dp))
                Text("Tile Ptr", fontSize = 9.sp, color = detailColor)
                Spacer(Modifier.width(4.dp))
                Text("\$${(tilePtrBank ?: 0).toString(16).uppercase().padStart(2, '0')}:${(tilePtrLo ?: 0).toString(16).uppercase().padStart(4, '0')}",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Layer", fontSize = 9.sp, color = detailColor, modifier = Modifier.width(64.dp))
                val layerStr = when ((layerCtrl ?: 0) and 0x03) {
                    0 -> "BG3 (behind BG)"
                    1 -> "BG2 (behind enemies)"
                    2 -> "Sprites (normal)"
                    3 -> "Sprites (foreground)"
                    else -> "Unknown"
                }
                Text("\$${(layerCtrl ?: 0).toString(16).uppercase().padStart(2, '0')} — $layerStr",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            // Extra GFX + PB vulnerability
            Row(modifier = Modifier.fillMaxWidth()) {
                for (field in gfxFields) {
                    val romVal = readEnemyStat(romParser, enemy.speciesId, field.offset) ?: 0
                    val curVal = values[field.key] ?: romVal
                    val isModified = values.containsKey(field.key) && curVal != romVal
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(field.label, fontSize = 10.sp,
                            color = if (isModified) Color(0xFFFFCC00) else detailColor,
                            fontWeight = if (isModified) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(64.dp))
                        EnemyHexInput(curVal, { onApply(field.key, it) }, Modifier.width(56.dp))
                        if (isModified) {
                            Text("↩", fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp).clickable { onApply(field.key, romVal) })
                        }
                    }
                }
            }

            // Custom ASM embedding
            if (editorState != null) {
                Spacer(Modifier.height(6.dp))
                Text("Custom Code", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = detailColor)
                Text("Paste assembled hex bytes. On export, code is written to free space and the pointer is auto-linked.",
                    fontSize = 8.sp, color = detailColor.copy(alpha = 0.5f))
                Spacer(Modifier.height(2.dp))

                val speciesHex = enemy.speciesId.toString(16).uppercase()
                val asmFieldDefs = listOf("initAi" to "Init AI", "shotAi" to "Shot AI", "touchAi" to "Touch AI",
                    "hurtAi" to "Hurt AI", "frozenAi" to "Frozen AI", "grappleAi" to "Grapple")
                for ((fieldName, label) in asmFieldDefs) {
                    val asmKey = "$speciesHex:$fieldName"
                    val existing = editorState.project.customAsm[asmKey]
                    var showEditor by remember(asmKey) { mutableStateOf(existing != null) }

                    if (!showEditor) {
                        Text("+ $label", fontSize = 9.sp, color = Color(0xFF80C0FF),
                            modifier = Modifier.clickable { showEditor = true }.padding(vertical = 1.dp))
                    } else {
                        var hexText by remember(asmKey) { mutableStateOf(existing?.hexBytes ?: "") }
                        var labelText by remember(asmKey) { mutableStateOf(existing?.label ?: "") }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(4.dp),
                        ) {
                            Column(Modifier.padding(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF80C0FF))
                                    Spacer(Modifier.weight(1f))
                                    Text("✕", fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.clickable {
                                            editorState.project.customAsm.remove(asmKey)
                                            editorState.markDirty()
                                            showEditor = false
                                        })
                                }
                                BasicTextField(
                                    value = labelText,
                                    onValueChange = { labelText = it },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                                        .padding(4.dp),
                                    decorationBox = { inner ->
                                        if (labelText.isEmpty()) Text("Description (optional)", fontSize = 9.sp, color = detailColor.copy(alpha = 0.4f))
                                        inner()
                                    }
                                )
                                BasicTextField(
                                    value = hexText,
                                    onValueChange = { raw ->
                                        hexText = raw.uppercase().filter { it in '0'..'9' || it in 'A'..'F' || it == ' ' || it == '\n' }
                                    },
                                    singleLine = false,
                                    maxLines = 8,
                                    textStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                        .height(64.dp)
                                        .background(Color(0xFF1A1A2E), RoundedCornerShape(3.dp))
                                        .padding(4.dp),
                                    decorationBox = { inner ->
                                        if (hexText.isEmpty()) Text("Paste assembled hex bytes, e.g.:\n22 77 A4 A0 6B\n22 97 A4 A0 6B 60",
                                            fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = detailColor.copy(alpha = 0.3f))
                                        inner()
                                    }
                                )
                                val byteCount = hexText.trim().split("\\s+".toRegex()).count { it.length == 2 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$byteCount bytes", fontSize = 8.sp, color = detailColor)
                                    Spacer(Modifier.weight(1f))
                                    Text("Save", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        color = if (hexText.isNotBlank()) Color(0xFF00CC66) else detailColor,
                                        modifier = Modifier.clickable {
                                            if (hexText.isNotBlank()) {
                                                editorState.project.customAsm[asmKey] = com.supermetroid.editor.data.CustomAsmEntry(
                                                    hexBytes = hexText.trim(),
                                                    label = labelText.trim(),
                                                )
                                                editorState.markDirty()
                                            }
                                        }.padding(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class EnemyField(val label: String, val offset: Int, val key: String, val tooltip: String = "")

/** Known AI routine addresses and their descriptions (from SM disassembly). */
private val KNOWN_AI_ROUTINES = mapOf(
    0x0000 to "None",
    0x0001 to "Null (no-op)",
    0x800A to "Default grapple reaction",
    0x8023 to "Standard touch (damage Samus)",
    0x802D to "Standard shot (take damage, flash)",
    0x8041 to "Standard freeze (become frozen)",
    0x804C to "Standard hurt (flash + recoil)",
)

/** Look up a human-readable label for an AI routine pointer, or format as hex. */
private fun aiRoutineLabel(value: Int): String = KNOWN_AI_ROUTINES[value] ?: ""

@Composable
private fun EnemyStatInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(value.toString()) }
    var lastExternalValue by remember { mutableStateOf(value) }
    if (value != lastExternalValue) {
        text = value.toString()
        lastExternalValue = value
    }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(5)
            text = filtered
            val parsed = filtered.toIntOrNull()?.coerceIn(0, 65535)
            if (parsed != null) {
                lastExternalValue = parsed
                onChange(parsed)
            }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(26.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp)
    )
}

/** Hex input for AI pointers and other 16-bit hex values. */
@Composable
private fun EnemyHexInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(value.toString(16).uppercase().padStart(4, '0')) }
    // Sync from external value changes (e.g., reset button) without fighting the user's typing
    var lastExternalValue by remember { mutableStateOf(value) }
    if (value != lastExternalValue) {
        text = value.toString(16).uppercase().padStart(4, '0')
        lastExternalValue = value
    }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(4)
            text = filtered
            val parsed = filtered.toIntOrNull(16)?.coerceIn(0, 0xFFFF)
            if (parsed != null) {
                lastExternalValue = parsed
                onChange(parsed)
            }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(22.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
            .padding(horizontal = 2.dp, vertical = 2.dp)
    )
}

// ─── ROM access ─────────────────────────────────────────────────

private fun readEnemyStat(romParser: RomParser?, speciesId: Int, offset: Int): Int? {
    if (romParser == null) return null
    return try {
        val snesAddr = RomConstants.BANK_ENEMY_AI or speciesId
        val pc = romParser.snesToPc(snesAddr) + offset
        val rom = romParser.getRomData()
        if (pc + 1 < rom.size) {
            (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        } else null
    } catch (_: Exception) { null }
}

private fun readEnemyStatByte(romParser: RomParser?, speciesId: Int, offset: Int): Int? {
    if (romParser == null) return null
    return try {
        val snesAddr = RomConstants.BANK_ENEMY_AI or speciesId
        val pc = romParser.snesToPc(snesAddr) + offset
        val rom = romParser.getRomData()
        if (pc < rom.size) rom[pc].toInt() and 0xFF else null
    } catch (_: Exception) { null }
}
