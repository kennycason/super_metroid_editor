package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser
import javax.imageio.ImageIO

// ─── Phantoon AI data model ─────────────────────────────────────

data class PhantoonField(
    val key: String,
    val label: String,
    val snesAddress: Int,
    val defaultValue: Int,
    val unit: String = "",
    val signed: Boolean = false,
    val hex: Boolean = false
)

data class PhantoonSection(
    val title: String,
    val description: String,
    val color: Color,
    val fields: List<PhantoonField>
)

// ─── Field definitions ──────────────────────────────────────────

private fun timerFields(prefix: String, baseSnes: Int, defaults: List<Int>): List<PhantoonField> =
    defaults.mapIndexed { i, d ->
        PhantoonField("${prefix}_$i", "Round $i", baseSnes + i * 2, d, unit = "frames")
    }

val PHANTOON_SECTIONS: List<PhantoonSection> = listOf(
    PhantoonSection(
        "Vulnerable Window — Eye Open Duration",
        "How long Phantoon's eye stays open (damageable) during figure-8 movement. 60 frames = 1 second.",
        Color(0xFF9C27B0),
        timerFields("vuln", 0xA7CD41, listOf(60, 30, 15, 30, 60, 30, 15, 60))
    ),
    PhantoonSection(
        "Eye Closed Duration — Between Patterns",
        "How long the eye stays closed before the next vulnerability window. Higher = longer wait between damage opportunities.",
        Color(0xFF7B1FA2),
        timerFields("closed", 0xA7CD53, listOf(720, 60, 360, 720, 360, 60, 360, 720))
    ),
    PhantoonSection(
        "Flame Rain — Hiding Duration",
        "How long Phantoon hides (invisible, invulnerable) before reappearing during flame rain phases.",
        Color(0xFFE65100),
        timerFields("hide", 0xA7CD63, listOf(60, 120, 30, 60, 30, 60, 30, 30))
    ),
    PhantoonSection(
        "Figure-8 Movement — Forward",
        "Subacceleration/acceleration words and speed caps for forward figure-8 movement.",
        Color(0xFF1565C0),
        listOf(
            PhantoonField("fwd_accel_0", "Acceleration 0", 0xA7CD73, 0x0600, hex = true),
            PhantoonField("fwd_accel_1", "Acceleration 1", 0xA7CD75, 0x0000, hex = true),
            PhantoonField("fwd_accel_2", "Acceleration 2", 0xA7CD77, 0x1000, hex = true),
            PhantoonField("fwd_accel_3", "Acceleration 3", 0xA7CD79, 0x0000, hex = true),
            PhantoonField("fwd_cap_0", "Speed Cap 0", 0xA7CD7B, 2, unit = "steps/frame"),
            PhantoonField("fwd_cap_1", "Speed Cap 1", 0xA7CD7D, 7, unit = "steps/frame"),
            PhantoonField("fwd_cap_2", "Speed Cap 2", 0xA7CD7F, 0, unit = "steps/frame"),
        )
    ),
    PhantoonSection(
        "Figure-8 Movement — Reverse",
        "Subacceleration/acceleration words and speed caps for reverse figure-8 movement. Negative speed = reverse traversal.",
        Color(0xFF0D47A1),
        listOf(
            PhantoonField("rev_accel_0", "Acceleration 0", 0xA7CD81, 0x0600, hex = true),
            PhantoonField("rev_accel_1", "Acceleration 1", 0xA7CD83, 0x0000, hex = true),
            PhantoonField("rev_accel_2", "Acceleration 2", 0xA7CD85, 0x1000, hex = true),
            PhantoonField("rev_accel_3", "Acceleration 3", 0xA7CD87, 0x0000, hex = true),
            PhantoonField("rev_cap_0", "Speed Cap 0", 0xA7CD89, 0xFFFE, unit = "steps/frame", signed = true),
            PhantoonField("rev_cap_1", "Speed Cap 1", 0xA7CD8B, 0xFFF9, unit = "steps/frame", signed = true),
            PhantoonField("rev_cap_2", "Speed Cap 2", 0xA7CD8D, 0x0000, unit = "steps/frame", signed = true),
        )
    ),
    PhantoonSection(
        "Wavy Effect — Intro / Death",
        "Amplitude deltas, max amplitudes, and phase delta for Phantoon's intro/death wave effect.",
        Color(0xFF00695C),
        listOf(
            PhantoonField("wave_amp", "Intro Amplitude Delta", 0xA7CD9B, 0x0040),
            PhantoonField("wave_freq", "Intro Max Amplitude", 0xA7CD9D, 0x0C00),
            PhantoonField("wave_growth", "Death Amplitude Delta", 0xA7CD9F, 0x0100),
            PhantoonField("wave_decay", "Death Max Amplitude", 0xA7CDA1, 0xF000, signed = true),
            PhantoonField("wave_speed", "Wave Phase Delta", 0xA7CDA3, 0x0008),
        )
    ),
    PhantoonSection(
        "Flame Rain Positions",
        "Where Phantoon materializes during flame rain. X/Y in room-local pixels (256x224 room).",
        Color(0xFFBF360C),
        listOf(
            PhantoonField("pos0_x", "Position 0 X", 0xA7CDAF, 128, unit = "px"),
            PhantoonField("pos0_y", "Position 0 Y", 0xA7CDB1, 96, unit = "px"),
            PhantoonField("pos1_x", "Position 1 X", 0xA7CDB7, 168, unit = "px"),
            PhantoonField("pos1_y", "Position 1 Y", 0xA7CDB9, 64, unit = "px"),
            PhantoonField("pos2_x", "Position 2 X", 0xA7CDBF, 208, unit = "px"),
            PhantoonField("pos2_y", "Position 2 Y", 0xA7CDC1, 96, unit = "px"),
            PhantoonField("pos3_x", "Position 3 X", 0xA7CDC7, 168, unit = "px"),
            PhantoonField("pos3_y", "Position 3 Y", 0xA7CDC9, 128, unit = "px"),
        )
    ),
)

val ALL_PHANTOON_FIELDS: List<PhantoonField> = PHANTOON_SECTIONS.flatMap { it.fields }

// ─── Sprite loader ──────────────────────────────────────────────

private object PhantoonSprites {
    fun load(id: String) = try {
        javaClass.getResourceAsStream("/enemies/$id.png")?.use {
            ImageIO.read(it)?.toComposeImageBitmap()
        }
    } catch (_: Exception) { null }
}

// ─── Main composable ────────────────────────────────────────────

@Composable
fun PhantoonEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    val values = remember(patch.id, editorState.patchVersion) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (field in ALL_PHANTOON_FIELDS) {
            map[field.key] = stored?.get(field.key)
                ?: readPhantoonFromRom(romParser, field)
                ?: field.defaultValue
        }
        map
    }

    fun apply(field: PhantoonField, value: Int) {
        values[field.key] = value
        editorState.setPatchConfigData(patch.id, field.key, value)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        PhantoonHeader()
        Spacer(Modifier.height(20.dp))

        for ((idx, section) in PHANTOON_SECTIONS.withIndex()) {
            PhantoonSectionCard(section, values, ::apply)
            if (idx < PHANTOON_SECTIONS.lastIndex) Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                for (field in ALL_PHANTOON_FIELDS) {
                    val rom = readPhantoonFromRom(romParser, field) ?: field.defaultValue
                    apply(field, rom)
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text("Reset All to ROM Defaults", fontSize = 12.sp)
        }
    }
}

// ─── Header with sprites ────────────────────────────────────────

@Composable
private fun PhantoonHeader() {
    val bodySprite = remember { PhantoonSprites.load("E4BF") }
    val flameSmall = remember { PhantoonSprites.load("E4FF") }
    val flameMed = remember { PhantoonSprites.load("E53F") }
    val flameLrg = remember { PhantoonSprites.load("E57F") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1A1025),
        border = BorderStroke(1.dp, Color(0xFF9C27B0).copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sprites column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 16.dp)
            ) {
                if (bodySprite != null) {
                    Image(
                        bitmap = bodySprite,
                        contentDescription = "Phantoon",
                        modifier = Modifier.size(72.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((sprite, desc) in listOf(
                        flameSmall to "Small flame",
                        flameMed to "Medium flame",
                        flameLrg to "Large flame"
                    )) {
                        if (sprite != null) {
                            Image(
                                bitmap = sprite,
                                contentDescription = desc,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            Column {
                Text(
                    "PHANTOON",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFCE93D8),
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Advanced Behavior Editor",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFB39DDB)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Edit AI timers, movement parameters, and flame rain behavior. " +
                    "All values are data-table writes — no ASM patches required. " +
                    "HP and damage are in the Boss Stats patch.",
                    fontSize = 11.sp,
                    color = Color(0xFF9E9E9E),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// ─── Section card ───────────────────────────────────────────────

@Composable
private fun PhantoonSectionCard(
    section: PhantoonSection,
    values: Map<String, Int>,
    onApply: (PhantoonField, Int) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = section.color.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, section.color.copy(alpha = 0.20f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (expanded) "\u25BC " else "\u25B6 ",
                    fontSize = 11.sp,
                    color = section.color,
                    modifier = Modifier.width(16.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        section.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!expanded) {
                        Text(
                            "${section.fields.size} fields",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(8.dp))

                // Column headers
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Field", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    Text("Value", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(80.dp), textAlign = TextAlign.Center)
                    Text("", modifier = Modifier.width(72.dp))
                }
                Divider(modifier = Modifier.padding(vertical = 4.dp))

                for (field in section.fields) {
                    PhantoonFieldRow(
                        field = field,
                        value = values[field.key] ?: field.defaultValue,
                        onChange = { onApply(field, it) }
                    )
                }
            }
        }
    }
}

// ─── Single field row ───────────────────────────────────────────

@Composable
private fun PhantoonFieldRow(
    field: PhantoonField,
    value: Int,
    onChange: (Int) -> Unit
) {
    val isModified = value != field.defaultValue
    val displayValue = if (field.signed && value > 32767) value - 65536 else value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            field.label,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal,
            color = if (isModified) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
        )

        if (field.hex) {
            PhantoonHexInput(value, onChange, Modifier.width(80.dp))
        } else if (field.signed) {
            PhantoonSignedInput(displayValue, { signed ->
                val stored = if (signed < 0) signed + 65536 else signed
                onChange(stored.coerceIn(0, 65535))
            }, Modifier.width(80.dp))
        } else {
            PhantoonIntInput(value, onChange, Modifier.width(80.dp))
        }

        // Annotation column
        val annotation = when {
            field.unit == "frames" -> "%.1fs".format(displayValue / 60.0)
            field.unit.isNotEmpty() -> field.unit
            field.hex -> ""
            else -> ""
        }
        Text(
            annotation,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp).padding(start = 8.dp),
            textAlign = TextAlign.Start
        )
    }
}

// ─── Input widgets ──────────────────────────────────────────────

@Composable
private fun PhantoonIntInput(value: Int, onChange: (Int) -> Unit, modifier: Modifier) {
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
            fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun PhantoonSignedInput(value: Int, onChange: (Int) -> Unit, modifier: Modifier) {
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
            fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun PhantoonHexInput(value: Int, onChange: (Int) -> Unit, modifier: Modifier) {
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
            fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Row(
                modifier = modifier
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace)
                inner()
            }
        }
    )
}

// ─── ROM access ─────────────────────────────────────────────────

internal fun readPhantoonFromRom(romParser: RomParser?, field: PhantoonField): Int? {
    if (romParser == null) return null
    return try {
        val pc = romParser.snesToPc(field.snesAddress)
        val rom = romParser.getRomData()
        if (pc + 1 < rom.size) {
            (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        } else null
    } catch (_: Exception) { null }
}
