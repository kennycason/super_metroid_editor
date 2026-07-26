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
import com.supermetroid.editor.rom.RomParser
@Composable
fun BossBehaviorEditor(
    definition: BossBehaviorDefinition,
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    val fields = BOSS_BEHAVIOR_FIELDS_BY_CONFIG_TYPE.getValue(definition.configType)
    val values = remember(patch.id, editorState.patchVersion) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (field in fields) {
            val value = stored?.get(field.key)
                ?: readBossBehaviorFromRom(romParser, field)
                ?: field.defaultValue
            map[field.key] = coerceBossBehaviorValue(field, value)
        }
        map
    }

    fun apply(field: BossBehaviorField, value: Int) {
        val coerced = coerceBossBehaviorValue(field, value)
        values[field.key] = coerced
        editorState.setPatchConfigData(patch.id, field.key, coerced)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        BossBehaviorHeader(definition)
        Spacer(Modifier.height(20.dp))

        for ((idx, section) in definition.sections.withIndex()) {
            BossBehaviorSectionCard(section, values, ::apply)
            if (idx < definition.sections.lastIndex) Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                for (field in fields) {
                    val rom = readBossBehaviorFromRom(romParser, field) ?: field.defaultValue
                    apply(field, rom)
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("Reset All to ROM Defaults", fontSize = 12.sp)
        }
    }
}

@Composable
private fun BossBehaviorHeader(definition: BossBehaviorDefinition) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = definition.headerColor.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, definition.headerColor.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                definition.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = definition.headerColor.copy(red = (definition.headerColor.red + 0.35f).coerceAtMost(1f)),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                definition.subtitle,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                definition.description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun BossBehaviorSectionCard(
    section: BossBehaviorSection,
    values: Map<String, Int>,
    onApply: (BossBehaviorField, Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = section.color.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, section.color.copy(alpha = 0.20f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) "\u25BC " else "\u25B6 ",
                    fontSize = 11.sp,
                    color = section.color,
                    modifier = Modifier.width(16.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        section.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!expanded) {
                        Text(
                            "${section.fields.size} fields",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    section.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Field",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Value",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(80.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text("", modifier = Modifier.width(72.dp))
                }
                Divider(modifier = Modifier.padding(vertical = 4.dp))

                for (field in section.fields) {
                    BossBehaviorFieldRow(
                        field = field,
                        value = values[field.key] ?: field.defaultValue,
                        onChange = { onApply(field, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BossBehaviorFieldRow(
    field: BossBehaviorField,
    value: Int,
    onChange: (Int) -> Unit,
) {
    val isModified = value != field.defaultValue
    val displayValue = field.logicalValue(value)
    val minValue = field.logicalMinValue()
    val maxValue = field.logicalMaxValue()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                field.label,
                fontSize = 12.sp,
                fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal,
                color = if (isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                field.metadataText(),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (field.hex) {
            BossBehaviorHexInput(value, onChange, Modifier.width(80.dp), minValue, maxValue)
        } else if (field.signed) {
            BossBehaviorSignedInput(
                value = displayValue,
                onChange = { signed -> onChange(field.storedValue(signed)) },
                modifier = Modifier.width(80.dp),
                minValue = minValue,
                maxValue = maxValue,
            )
        } else {
            BossBehaviorIntInput(displayValue, { logical -> onChange(field.storedValue(logical)) }, Modifier.width(80.dp), minValue, maxValue)
        }

        val annotation = when {
            field.unit == "frames" -> "%.1fs".format(displayValue / 60.0)
            field.unit.isNotEmpty() -> field.unit
            else -> ""
        }
        Text(
            annotation,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp).padding(start = 8.dp),
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun BossBehaviorIntInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier,
    minValue: Int,
    maxValue: Int,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val maxChars = maxOf(1, maxValue.toString().length)
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(maxChars)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(minValue, maxValue)) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun BossBehaviorSignedInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier,
    minValue: Int,
    maxValue: Int,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val maxChars = maxOf(minValue.toString().length, maxValue.toString().length)
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val normalized = buildString {
                for (c in raw) {
                    when {
                        c.isDigit() -> append(c)
                        c == '-' && isEmpty() -> append(c)
                    }
                }
            }
            val filtered = normalized.take(maxChars)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(minValue, maxValue)) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun BossBehaviorHexInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier,
    minValue: Int,
    maxValue: Int,
) {
    var text by remember(value) { mutableStateOf(value.toString(16).uppercase().padStart(4, '0')) }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.take(4)
            text = filtered.uppercase()
            filtered.toIntOrNull(16)?.let { onChange(it.coerceIn(minValue, maxValue)) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Row(
                modifier = modifier
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                inner()
            }
        },
    )
}

internal fun readBossBehaviorFromRom(romParser: RomParser?, field: BossBehaviorField): Int? {
    if (romParser == null) return null
    return try {
        val pc = romParser.snesToPc(field.snesAddress)
        val rom = romParser.getRomData()
        if (pc + 1 < rom.size) {
            (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}
