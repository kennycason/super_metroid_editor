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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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

const val FANFARE_CONFIG_TYPE = "fanfares"

const val FANFARE_FRAMES_KEY = "item_fanfare_frames"

const val FANFARE_MESSAGE_BOX_WAIT_PC = 0x028491
val FANFARE_MUSIC_RESUME_DELAY_PCS = listOf(
    0x0208DF, // Beam pickups
    0x020906, // Equipment pickups
    0x020931, // Grapple beam
    0x020958, // X-ray scope
    0x020976, // Energy tank
    0x020999, // Reserve tank
    0x0209C2, // Missile tank
    0x0209EB, // Super missile tank
    0x020A14, // Power bomb tank
)

const val FANFARE_DEFAULT_FRAMES = 0x0168
const val FANFARE_TRIMMED_FRAMES = 0x00F0
const val FANFARE_QUICK_FRAMES = 0x0010
const val FANFARE_MIN_FRAMES = 1
const val FANFARE_MAX_FRAMES = 9999

data class FanfareRomDefaults(
    val itemFanfareFrames: Int,
    val roomMusicResumeFrames: Int,
)

fun readFanfareRomDefaults(romParser: RomParser?): FanfareRomDefaults {
    val rom = try {
        romParser?.getRomData()
    } catch (_: Exception) {
        null
    }
    val messageFrames = rom?.readWordOrNull(FANFARE_MESSAGE_BOX_WAIT_PC) ?: FANFARE_DEFAULT_FRAMES
    val musicFrames = rom?.readWordOrNull(FANFARE_MUSIC_RESUME_DELAY_PCS.first()) ?: FANFARE_DEFAULT_FRAMES
    return FanfareRomDefaults(
        itemFanfareFrames = messageFrames,
        roomMusicResumeFrames = musicFrames,
    )
}

private fun ByteArray.readWordOrNull(offset: Int): Int? =
    if (offset >= 0 && offset + 1 < size) {
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    } else {
        null
    }

@Composable
fun FanfareEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    val romDefaults = remember(romParser) { readFanfareRomDefaults(romParser) }
    val values = remember(patch.id, editorState.patchVersion, romDefaults) {
        val stored = patch.configData
        mutableStateMapOf(
            FANFARE_FRAMES_KEY to (stored?.get(FANFARE_FRAMES_KEY) ?: romDefaults.itemFanfareFrames),
        )
    }

    fun apply(value: Int) {
        val clamped = value.coerceIn(FANFARE_MIN_FRAMES, FANFARE_MAX_FRAMES)
        values[FANFARE_FRAMES_KEY] = clamped
        editorState.setPatchConfigData(patch.id, FANFARE_FRAMES_KEY, clamped)
    }

    val frames = (values[FANFARE_FRAMES_KEY] ?: romDefaults.itemFanfareFrames)
        .coerceIn(FANFARE_MIN_FRAMES, FANFARE_MAX_FRAMES)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Fanfares", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Controls item fanfare lockout timing. Export writes the same frame count to the item message-box wait and the room-music resume delay.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF7CB342).copy(alpha = 0.07f),
            border = BorderStroke(1.dp, Color(0xFF7CB342).copy(alpha = 0.22f)),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Item Fanfares", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF558B2F))
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fanfare Frames", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Frames before the item box can close and room music resumes. Vanilla is 360.",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FanfareNumberInput(frames, ::apply, Modifier.width(88.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "frames",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp),
                        textAlign = TextAlign.Left,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row {
                    FanfarePresetButton("Vanilla", FANFARE_DEFAULT_FRAMES, frames, ::apply)
                    Spacer(Modifier.width(8.dp))
                    FanfarePresetButton("Trimmed", FANFARE_TRIMMED_FRAMES, frames, ::apply)
                    Spacer(Modifier.width(8.dp))
                    FanfarePresetButton("Quick", FANFARE_QUICK_FRAMES, frames, ::apply)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "ROM defaults: ${romDefaults.itemFanfareFrames} item-box frames, " +
                "${romDefaults.roomMusicResumeFrames} room-music resume frames.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "MapRandomizer uses 240 for Trimmed and 16 for its item-sound mode. This patch keeps vanilla fanfare behavior and changes the timing.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { apply(romDefaults.itemFanfareFrames) },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("Reset to ROM Default", fontSize = 12.sp)
        }
    }
}

@Composable
private fun FanfarePresetButton(
    label: String,
    value: Int,
    currentValue: Int,
    onClick: (Int) -> Unit,
) {
    OutlinedButton(
        onClick = { onClick(value) },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            "$label $value",
            fontSize = 11.sp,
            fontWeight = if (value == currentValue) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun FanfareNumberInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(5)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(FANFARE_MIN_FRAMES, FANFARE_MAX_FRAMES)) }
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
