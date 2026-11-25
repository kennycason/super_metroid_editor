package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.BabyMetroidPatch
import com.supermetroid.editor.rom.RomParser

@Composable
fun BabyMetroidEditor(
    behaviorPatch: SmPatch,
    statsPatch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
    onNavigateToAsm: ((Int) -> Unit)? = null,
) {
    @Suppress("UNUSED_VARIABLE") val revision = editorState.patchVersion
    val sourceMode = editorState.asmNavigationEnabled
    val tuning = editorState.enemyTuningBridge.takeIf { sourceMode }
    @Suppress("UNUSED_VARIABLE") val sourceRevision = tuning?.revision
    val hpKey = "metroid_hp"
    val damageKey = "metroid_dmg"
    val hpView = tuning?.value(statsPatch, hpKey)
    val damageView = tuning?.value(statsPatch, damageKey)
    val hpEditable = !sourceMode || tuning != null && hpView?.problem == null
    val damageEditable = !sourceMode || tuning != null && damageView?.problem == null
    val hp = hpView?.value
        ?: statsPatch.configData?.get(hpKey)
        ?: readBabyMetroidWord(romParser, 0xA0EEC3)
        ?: BabyMetroidPatch.DEFAULT_HP
    val contactDamage = damageView?.value
        ?: statsPatch.configData?.get(damageKey)
        ?: readBabyMetroidWord(romParser, 0xA0EEC5)
        ?: BabyMetroidPatch.DEFAULT_CONTACT_DAMAGE
    val config = behaviorPatch.configData.orEmpty()
    fun behaviorValue(key: String, default: Int): Int = config[key] ?: default
    fun setBehavior(key: String, value: Int) = editorState.setPatchConfigData(behaviorPatch.id, key, value)
    fun setStat(key: String, value: Int) = editorState.setPatchConfigData(statsPatch.id, key, value)

    val mortal = behaviorValue(BabyMetroidPatch.MORTAL_KEY, BabyMetroidPatch.DEFAULT_MORTAL) == 1
    val freezable = behaviorValue(BabyMetroidPatch.FREEZABLE_KEY, BabyMetroidPatch.DEFAULT_FREEZABLE) == 1
    val missileVulnerability = behaviorValue(
        BabyMetroidPatch.MISSILE_VULNERABILITY_KEY,
        BabyMetroidPatch.DEFAULT_MISSILE_VULNERABILITY,
    )
    val superVulnerability = behaviorValue(
        BabyMetroidPatch.SUPER_VULNERABILITY_KEY,
        BabyMetroidPatch.DEFAULT_SUPER_VULNERABILITY,
    )

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF26A69A).copy(alpha = 0.16f),
            border = BorderStroke(1.dp, Color(0xFF26A69A).copy(alpha = 0.45f)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "BABY METROID",
                            fontSize = LocalEditorTheme.current.fontSize.value.display,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4DD0E1),
                        )
                        Text(
                            "End-game AI & combat editor · enemy \$EEBF",
                            fontSize = LocalEditorTheme.current.fontSize.value.heading,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (sourceMode && onNavigateToAsm != null) {
                        Row {
                            TextButton(
                                onClick = {
                                    onNavigateToAsm(0xA0EEBF)
                                    editorState.openEnemyTuningSource(hpKey)
                                },
                                modifier = Modifier.testTag("baby-metroid-header-asm"),
                            ) { Text("HEADER ASM", fontSize = LocalEditorTheme.current.fontSize.value.detail) }
                            TextButton(
                                onClick = { onNavigateToAsm(0xA9EFC5) },
                                modifier = Modifier.testTag("baby-metroid-ai-asm"),
                            ) { Text("AI ASM", fontSize = LocalEditorTheme.current.fontSize.value.detail) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Makes the giant Tourian Baby damageable without exposing its scripted corpse sequence. " +
                        "Its normal knockback remains intact; a private vulnerability table isolates these settings from corpse enemies.",
                    fontSize = LocalEditorTheme.current.fontSize.value.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Giant Baby preset", fontWeight = FontWeight.SemiBold, fontSize = LocalEditorTheme.current.fontSize.value.heading)
                Text(
                    "5,000 HP, mortal, Ice-freezable, and normal missile/super damage: about 50 missiles or 17 supers with vanilla projectile damage.",
                    fontSize = LocalEditorTheme.current.fontSize.value.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        setStat(hpKey, BabyMetroidPatch.FIFTY_MISSILE_HP)
                        setBehavior(BabyMetroidPatch.MORTAL_KEY, 1)
                        setBehavior(BabyMetroidPatch.FREEZABLE_KEY, 1)
                        setBehavior(BabyMetroidPatch.MISSILE_VULNERABILITY_KEY, 2)
                        setBehavior(BabyMetroidPatch.SUPER_VULNERABILITY_KEY, 2)
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    enabled = hpEditable,
                    modifier = Modifier.testTag("baby-metroid-giant-preset"),
                ) {
                    Text("Apply 50-Missile Preset", fontSize = LocalEditorTheme.current.fontSize.value.body)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        BabySection("Combat", "HP and contact damage use the same enemy-header fields as Enemy Stats.") {
            if (sourceMode && tuning == null) {
                BabySourceProblem("Wait for enemy ASM source indexing before changing HP/contact damage.")
            }
            BabyNumberRow("HP", "Initial health once Mortal is enabled.", hp, 1..65535,
                enabled = hpEditable, onChange = { setStat(hpKey, it) })
            hpView?.problem?.let { BabySourceProblem(it) }
            BabyNumberRow("Contact Damage", "Damage dealt when touching Samus.", contactDamage, 0..65535,
                enabled = damageEditable, onChange = { setStat(damageKey, it) })
            damageView?.problem?.let { BabySourceProblem(it) }
            BabyToggleRow(
                label = "Mortal",
                detail = "Disable the vanilla per-frame \$7FFF health restore and route shots through guarded normal damage AI.",
                checked = mortal,
                onCheckedChange = { setBehavior(BabyMetroidPatch.MORTAL_KEY, if (it) 1 else 0) },
                testTag = "baby-metroid-mortal",
            )
            BabyToggleRow(
                label = "Freezable by Ice",
                detail = "In Mortal mode, all Ice Beam combinations freeze it using the existing spritemap and engine freeze palette; no extra sprite is required.",
                checked = freezable,
                onCheckedChange = { setBehavior(BabyMetroidPatch.FREEZABLE_KEY, if (it) 1 else 0) },
                testTag = "baby-metroid-freezable",
                enabled = mortal,
            )
            BabyNumberRow(
                "Missile Vulnerability",
                "Raw SM multiplier: 0 = immune, 1 = 0.5×, 2 = 1×, 4 = 2×.",
                missileVulnerability,
                0..15,
                enabled = mortal,
                onChange = { setBehavior(BabyMetroidPatch.MISSILE_VULNERABILITY_KEY, it) },
            )
            BabyNumberRow(
                "Super Vulnerability",
                "Raw SM multiplier: 2 = 1×; vanilla supers deal three times normal missile damage.",
                superVulnerability,
                0..15,
                enabled = mortal,
                onChange = { setBehavior(BabyMetroidPatch.SUPER_VULNERABILITY_KEY, it) },
            )
            Text(
                combatEstimate(mortal, hp, missileVulnerability, superVulnerability),
                fontSize = LocalEditorTheme.current.fontSize.value.detail,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        BabySection(
            "Movement",
            "These edit only the end-game Baby's movement call sites. Shared speed caps used by the Mother Brain cutscene are intentionally left untouched.",
        ) {
            for (field in BabyMetroidPatch.MOVEMENT_FIELDS) {
                val romValue = field.operandSnesAddresses.firstNotNullOfOrNull { readBabyMetroidWord(romParser, it) }
                    ?: field.defaultValue
                BabyNumberRow(
                    field.label,
                    field.description,
                    behaviorValue(field.key, romValue),
                    0..15,
                    onChange = { setBehavior(field.key, it) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                setStat(hpKey, readBabyMetroidWord(romParser, 0xA0EEC3) ?: BabyMetroidPatch.DEFAULT_HP)
                setStat(damageKey, readBabyMetroidWord(romParser, 0xA0EEC5) ?: BabyMetroidPatch.DEFAULT_CONTACT_DAMAGE)
                setBehavior(BabyMetroidPatch.MORTAL_KEY, 0)
                setBehavior(BabyMetroidPatch.FREEZABLE_KEY, 1)
                setBehavior(BabyMetroidPatch.MISSILE_VULNERABILITY_KEY, 2)
                setBehavior(BabyMetroidPatch.SUPER_VULNERABILITY_KEY, 2)
                BabyMetroidPatch.MOVEMENT_FIELDS.forEach { field ->
                    setBehavior(
                        field.key,
                        readBabyMetroidWord(romParser, field.operandSnesAddresses.first()) ?: field.defaultValue,
                    )
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("Reset to ROM Defaults", fontSize = LocalEditorTheme.current.fontSize.value.body)
        }
    }
}

@Composable
private fun BabySection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = LocalEditorTheme.current.fontSize.value.heading)
            Text(
                description,
                fontSize = LocalEditorTheme.current.fontSize.value.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun BabyNumberRow(
    label: String,
    detail: String,
    value: Int,
    range: IntRange,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium, fontSize = LocalEditorTheme.current.fontSize.value.body)
            Text(detail, fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BabyNumberInput(value, range, enabled, onChange, Modifier.width(88.dp))
    }
}

@Composable
private fun BabyToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium, fontSize = LocalEditorTheme.current.fontSize.value.body)
            Text(detail, fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange, enabled = enabled, modifier = Modifier.testTag(testTag))
    }
}

@Composable
private fun BabyNumberInput(
    value: Int,
    range: IntRange,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter(Char::isDigit).take(5)
            text = filtered
            filtered.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = LocalEditorTheme.current.fontSize.value.heading,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.height(30.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun BabySourceProblem(problem: String) {
    Text(
        "ASM field unavailable: $problem",
        color = MaterialTheme.colorScheme.error,
        fontSize = LocalEditorTheme.current.fontSize.value.detail,
    )
}

private fun readBabyMetroidWord(parser: RomParser?, snesAddress: Int): Int? = runCatching {
    val rom = requireNotNull(parser).getRomData()
    val pc = parser.snesToPc(snesAddress)
    (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
}.getOrNull()

private fun combatEstimate(
    mortal: Boolean,
    hp: Int,
    missileVulnerability: Int,
    superVulnerability: Int,
): String {
    if (!mortal) return "Invincible mode: projectiles do not reduce HP."
    fun shots(baseDamage: Int, vulnerability: Int): String {
        val damage = baseDamage * vulnerability / 2
        return if (damage <= 0) "immune" else ((hp + damage - 1) / damage).toString()
    }
    return "At vanilla projectile damage: ${shots(100, missileVulnerability)} missiles · " +
        "${shots(300, superVulnerability)} supers"
}
