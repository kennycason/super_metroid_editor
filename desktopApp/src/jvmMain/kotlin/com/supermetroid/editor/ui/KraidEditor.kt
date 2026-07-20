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

const val KRAID_CONFIG_TYPE = "kraid"

data class KraidField(
    override val key: String,
    override val label: String,
    override val snesAddress: Int,
    override val defaultValue: Int,
    override val unit: String = "",
    override val signed: Boolean = false,
    override val hex: Boolean = false,
    override val minValue: Int? = null,
    override val maxValue: Int? = null,
    override val writeSnesAddresses: List<Int> = listOf(snesAddress),
) : BossTuningField

data class KraidSection(
    val title: String,
    val description: String,
    val color: Color,
    val fields: List<KraidField>,
)

private fun kraidField(
    key: String,
    label: String,
    snesAddress: Int,
    defaultValue: Int,
    unit: String = "",
    signed: Boolean = false,
    hex: Boolean = false,
    minValue: Int? = null,
    maxValue: Int? = null,
    vararg additionalWriteAddresses: Int,
): KraidField =
    KraidField(
        key = key,
        label = label,
        snesAddress = snesAddress,
        defaultValue = defaultValue,
        unit = unit,
        signed = signed,
        hex = hex,
        minValue = minValue,
        maxValue = maxValue,
        writeSnesAddresses = (listOf(snesAddress) + additionalWriteAddresses.toList()).distinct(),
    )

internal fun coerceKraidValue(field: KraidField, storedValue: Int): Int =
    coerceBossTuningValue(field, storedValue)

val KRAID_SECTIONS: List<KraidSection> = listOf(
    KraidSection(
        "Kraid Constants",
        "Data table values at \$A7:A916 for lint timers, walking speed, and lint X speed.",
        Color(0xFF689F38),
        listOf(
            kraidField("lint_top_timer", "Top Lint Timer", 0xA7A916, 0x0120, unit = "frames"),
            kraidField("lint_middle_timer", "Middle Lint Timer", 0xA7A918, 0x00A0, unit = "frames"),
            kraidField("lint_bottom_timer", "Bottom Lint Timer", 0xA7A91A, 0x0040, unit = "frames"),
            kraidField("walk_forward_speed", "Forward Walk Speed", 0xA7A91C, 0x0003, unit = "px/frame"),
            kraidField("walk_back_speed", "Backward Walk Speed", 0xA7A920, 0x0003, unit = "px/frame"),
            kraidField("lint_x_subspeed", "Lint X Subspeed", 0xA7A926, 0x8000, hex = true),
            kraidField("lint_x_speed", "Lint X Speed", 0xA7A928, 0x0003, unit = "px/frame"),
        ),
    ),
    KraidSection(
        "Intro And Phase Timers",
        "Immediate operands used by Kraid's intro, floor break, rising, and mouth-open loops.",
        Color(0xFF8D6E63),
        listOf(
            kraidField("intro_delay", "Intro Delay", 0xA7AA6A, 0x012C, unit = "frames"),
            kraidField("initial_instruction_timer", "Initial Instruction Timer", 0xA7AA77, 0x0040, unit = "frames"),
            kraidField("get_big_pause", "Get-Big Pause", 0xA7C016, 0x00B4, unit = "frames"),
            kraidField("ceiling_rocks_slow_timer", "Slow Rock Phase", 0xA7C8A4, 0x0078, unit = "frames"),
            kraidField("ceiling_rocks_fast_timer", "Fast Rock Phase", 0xA7C8F3, 0x0060, unit = "frames"),
            kraidField("post_rise_foot_timer", "Post-Rise Foot Timer", 0xA7C973, 0x012C, unit = "frames"),
            kraidField("mouth_close_timer", "After Mouth Closes", 0xA7AEF3, 0x005A, unit = "frames"),
            kraidField("mouth_reopen_timer", "Mouth Reopen Delay", 0xA7AF15, 0x0040, unit = "frames"),
        ),
    ),
    KraidSection(
        "Projectile Spawns",
        "Earthquake projectile cadence masks and the falling-rock X-position table used while Kraid breaks the ceiling.",
        Color(0xFF546E7A),
        listOf(
            kraidField("earthquake_ceiling_mask", "Ceiling Quake Mask", 0xA7AC51, 0x0007, hex = true, maxValue = 0x00FF),
            kraidField("earthquake_rise_slow_mask", "Rise Slow Quake Mask", 0xA7C8FA, 0x000F, hex = true, maxValue = 0x00FF),
            kraidField("earthquake_rise_fast_mask", "Rise Fast Quake Mask", 0xA7C91C, 0x0007, hex = true, maxValue = 0x00FF),
            kraidField("earthquake_shake_bit_mask", "Screen-Shake Spawn Mask", 0xA7C92B, 0x0005, hex = true, maxValue = 0x00FF),
            kraidField("falling_rock_x_0", "Falling Rock X 0", 0xA7ACB3, 0x0068, unit = "px"),
            kraidField("falling_rock_x_1", "Falling Rock X 1", 0xA7ACB5, 0x00D8, unit = "px"),
            kraidField("falling_rock_x_2", "Falling Rock X 2", 0xA7ACB7, 0x0028, unit = "px"),
            kraidField("falling_rock_x_3", "Falling Rock X 3", 0xA7ACB9, 0x00A8, unit = "px"),
            kraidField("falling_rock_x_4", "Falling Rock X 4", 0xA7ACBB, 0x0058, unit = "px"),
            kraidField("falling_rock_x_5", "Falling Rock X 5", 0xA7ACBD, 0x00C8, unit = "px"),
            kraidField("falling_rock_x_6", "Falling Rock X 6", 0xA7ACBF, 0x0038, unit = "px"),
            kraidField("falling_rock_x_7", "Falling Rock X 7", 0xA7ACC1, 0x00B8, unit = "px"),
            kraidField("falling_rock_x_8", "Falling Rock X 8", 0xA7ACC3, 0x0048, unit = "px"),
        ),
    ),
    KraidSection(
        "Fingernails",
        "Spawn timers, horizontal fingernail launch values, and shared diagonal velocity table words.",
        Color(0xFF5D4037),
        listOf(
            kraidField("good_fingernail_timer", "Good Fingernail Timer", 0xA7AE5F, 0x0040, unit = "frames"),
            kraidField("bad_fingernail_timer", "Bad Fingernail Timer", 0xA7AE65, 0x0080, unit = "frames"),
            kraidField("horizontal_nail_x", "Horizontal Nail X", 0xA7BE04, 0x0032, unit = "px"),
            kraidField("horizontal_nail_y", "Horizontal Nail Y", 0xA7BE0A, 0x00F0, unit = "px"),
            kraidField("horizontal_nail_x_speed", "Horizontal Nail X Speed", 0xA7BE16, 0x0001, unit = "px/frame"),
            kraidField(
                "horizontal_nail_y_speed",
                "Horizontal Nail Y Speed",
                0xA7BE22,
                0x0000,
                unit = "px/frame",
                signed = true,
            ),
            kraidField(
                "diagonal_up_x_speed",
                "Diagonal Up X Speed",
                0xA7BE50,
                0xFFFF,
                unit = "px/frame",
                signed = true,
                additionalWriteAddresses = intArrayOf(0xA7BE60, 0xA7BE70, 0xA7BE80),
            ),
            kraidField(
                "diagonal_up_y_speed",
                "Diagonal Up Y Speed",
                0xA7BE54,
                0x0001,
                unit = "px/frame",
                signed = true,
                additionalWriteAddresses = intArrayOf(0xA7BE64, 0xA7BE74, 0xA7BE84),
            ),
            kraidField(
                "diagonal_down_x_speed",
                "Diagonal Down X Speed",
                0xA7BE58,
                0xFFFF,
                unit = "px/frame",
                signed = true,
                additionalWriteAddresses = intArrayOf(0xA7BE68, 0xA7BE78, 0xA7BE88),
            ),
            kraidField(
                "diagonal_down_y_speed",
                "Diagonal Down Y Speed",
                0xA7BE5C,
                0xFFFF,
                unit = "px/frame",
                signed = true,
                additionalWriteAddresses = intArrayOf(0xA7BE6C, 0xA7BE7C, 0xA7BE8C),
            ),
        ),
    ),
)

val ALL_KRAID_FIELDS: List<KraidField> = KRAID_SECTIONS.flatMap { it.fields }

@Composable
fun KraidEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    val values = remember(patch.id, editorState.patchVersion) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (field in ALL_KRAID_FIELDS) {
            val value = stored?.get(field.key)
                ?: readKraidFromRom(romParser, field)
                ?: field.defaultValue
            map[field.key] = coerceKraidValue(field, value)
        }
        map
    }

    fun apply(field: KraidField, value: Int) {
        val coerced = coerceKraidValue(field, value)
        values[field.key] = coerced
        editorState.setPatchConfigData(patch.id, field.key, coerced)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        KraidHeader()
        Spacer(Modifier.height(20.dp))

        for ((idx, section) in KRAID_SECTIONS.withIndex()) {
            KraidSectionCard(section, values, ::apply)
            if (idx < KRAID_SECTIONS.lastIndex) Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                for (field in ALL_KRAID_FIELDS) {
                    val rom = readKraidFromRom(romParser, field) ?: field.defaultValue
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
private fun KraidHeader() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF16220F),
        border = BorderStroke(1.dp, Color(0xFF8BC34A).copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "KRAID",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC5E1A5),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Advanced Behavior Editor",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFAED581),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Edit Kraid's ROM data constants and selected immediate operands. HP and contact damage are in the Boss Stats patch.",
                fontSize = 11.sp,
                color = Color(0xFFB0B0B0),
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun KraidSectionCard(
    section: KraidSection,
    values: Map<String, Int>,
    onApply: (KraidField, Int) -> Unit,
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
                    KraidFieldRow(
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
private fun KraidFieldRow(
    field: KraidField,
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
            KraidHexInput(value, onChange, Modifier.width(80.dp), minValue, maxValue)
        } else if (field.signed) {
            KraidSignedInput(
                value = displayValue,
                onChange = { signed -> onChange(field.storedValue(signed)) },
                modifier = Modifier.width(80.dp),
                minValue = minValue,
                maxValue = maxValue,
            )
        } else {
            KraidIntInput(displayValue, { logical -> onChange(field.storedValue(logical)) }, Modifier.width(80.dp), minValue, maxValue)
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
private fun KraidIntInput(
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
private fun KraidSignedInput(
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
private fun KraidHexInput(
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

internal fun readKraidFromRom(romParser: RomParser?, field: KraidField): Int? {
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
