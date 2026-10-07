package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.EnvironmentalDamagePatch
import com.supermetroid.editor.rom.RomParser

fun readEnvironmentalDamageRomRates(romParser: RomParser?): Map<String, Int> {
    val rom = try {
        romParser?.getRomData()
    } catch (_: Exception) {
        null
    }
    return EnvironmentalDamagePatch.FIELDS.associate { field ->
        val low = rom?.readEnvironmentalWord(field.lowWordPc)
        val high = rom?.readEnvironmentalWord(field.highWordPc)
        val value = if (low != null && high != null) {
            EnvironmentalDamagePatch.decodeEnergyPerSecond(low, high)
        } else {
            field.defaultEnergyPerSecond
        }
        field.key to value
    }
}

private fun ByteArray.readEnvironmentalWord(offset: Int): Int? =
    if (offset >= 0 && offset + 1 < size) {
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    } else {
        null
    }

@Composable
fun EnvironmentalDamageEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    val romRates = remember(romParser) { readEnvironmentalDamageRomRates(romParser) }
    @Suppress("UNUSED_VARIABLE") val patchVersion = editorState.patchVersion

    fun currentValue(key: String): Int =
        patch.configData?.get(key) ?: romRates.getValue(key)

    fun apply(key: String, value: Int) {
        editorState.setPatchConfigData(
            patch.id,
            key,
            value.coerceIn(
                EnvironmentalDamagePatch.MIN_ENERGY_PER_SECOND,
                EnvironmentalDamagePatch.MAX_ENERGY_PER_SECOND,
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Environmental Damage", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Base energy drained each second. Set a rate to 0 to keep the effect but remove its damage.",
                    fontSize = LocalEditorTheme.current.fontSize.value.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(
                onClick = {
                    EnvironmentalDamagePatch.FIELDS.forEach { field ->
                        apply(field.key, field.defaultEnergyPerSecond)
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text("Vanilla", fontSize = LocalEditorTheme.current.fontSize.value.body)
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                EnvironmentalDamagePatch.FIELDS.forEachIndexed { index, field ->
                    EnvironmentalDamageRateRow(
                        label = field.label,
                        suitBehavior = field.suitBehavior,
                        value = currentValue(field.key),
                        vanillaValue = field.defaultEnergyPerSecond,
                        onChange = { apply(field.key, it) },
                    )
                    if (index != EnvironmentalDamagePatch.FIELDS.lastIndex) {
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Rates are accumulated every frame, so low values still drain smoothly.",
            fontSize = LocalEditorTheme.current.fontSize.value.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun EnvironmentalDamageRateRow(
    label: String,
    suitBehavior: String,
    value: Int,
    vanillaValue: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.SemiBold)
                if (value == vanillaValue) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "VANILLA",
                        fontSize = LocalEditorTheme.current.fontSize.value.statusBar,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                suitBehavior,
                fontSize = LocalEditorTheme.current.fontSize.value.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DamageStepButton("−", enabled = value > EnvironmentalDamagePatch.MIN_ENERGY_PER_SECOND) {
            onChange(value - 1)
        }
        DamageRateInput(value = value, onChange = onChange)
        DamageStepButton("+", enabled = value < EnvironmentalDamagePatch.MAX_ENERGY_PER_SECOND) {
            onChange(value + 1)
        }
        Text(
            "energy/sec",
            fontSize = LocalEditorTheme.current.fontSize.value.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
        )
    }
}

@Composable
private fun DamageRateInput(value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { candidate ->
            val filtered = candidate.filter(Char::isDigit).take(4)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = LocalEditorTheme.current.fontSize.value.heading,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.width(62.dp),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                inner()
            }
        },
    )
}

@Composable
private fun DamageStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(28.dp).height(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(label, fontSize = LocalEditorTheme.current.fontSize.value.heading)
    }
}
