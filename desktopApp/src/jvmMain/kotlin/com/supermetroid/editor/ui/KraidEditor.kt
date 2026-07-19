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
    val key: String,
    val label: String,
    val snesAddress: Int,
    val defaultValue: Int,
    val unit: String = "",
    val signed: Boolean = false,
    val hex: Boolean = false,
    val writeSnesAddresses: List<Int> = listOf(snesAddress),
)

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
        writeSnesAddresses = (listOf(snesAddress) + additionalWriteAddresses.toList()).distinct(),
    )

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
            map[field.key] = stored?.get(field.key)
                ?: readKraidFromRom(romParser, field)
                ?: field.defaultValue
        }
        map
    }

    fun apply(field: KraidField, value: Int) {
        values[field.key] = value
        editorState.setPatchConfigData(patch.id, field.key, value)
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
    val displayValue = if (field.signed && value > 32767) value - 65536 else value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            field.label,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal,
            color = if (isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )

        if (field.hex) {
            KraidHexInput(value, onChange, Modifier.width(80.dp))
        } else if (field.signed) {
            KraidSignedInput(
                value = displayValue,
                onChange = { signed ->
                    val stored = if (signed < 0) signed + 65536 else signed
                    onChange(stored.coerceIn(0, 65535))
                },
                modifier = Modifier.width(80.dp),
            )
        } else {
            KraidIntInput(value, onChange, Modifier.width(80.dp))
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
private fun KraidIntInput(value: Int, onChange: (Int) -> Unit, modifier: Modifier) {
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
private fun KraidSignedInput(value: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '-') }.take(6)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(-32768, 32767)) }
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
private fun KraidHexInput(value: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    var text by remember(value) { mutableStateOf(value.toString(16).uppercase().padStart(4, '0')) }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.take(4)
            text = filtered.uppercase()
            filtered.toIntOrNull(16)?.let { onChange(it.coerceIn(0, 65535)) }
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
