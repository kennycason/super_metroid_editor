package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.NspcRenderer.InstrumentEntry
import java.util.Locale

/** Exact native values alongside the musical controls, with one edit per finished input. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeSoundValues(entry: InstrumentEntry, modifier: Modifier = Modifier, onCommit: (InstrumentEntry) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val envelope = NativeInstrumentSound.usesEnvelope(entry)
    SynthModule(modifier.testTag("sound-values")) {
        Text("Sound values", fontSize = fs.body, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SoundValueInput("ADSR1", "Attack / decay", entry.adsr1, 255, "sound-value-adsr1",
                if (envelope) "Active" else "Stored") { onCommit(entry.copy(adsr1 = it)) }
            SoundValueInput("ADSR2", "Sustain / held fade", entry.adsr2, 255, "sound-value-adsr2",
                if (envelope) "Active" else "Stored") { onCommit(entry.copy(adsr2 = it)) }
            SoundValueInput("GAIN", "Gain mode / value", entry.gain, 255, "sound-value-gain",
                if (envelope) "Stored" else "Active") { onCommit(entry.copy(gain = it)) }
            SoundValueInput("Pitch", if (entry.srcn >= 0x80) "Stored tuning" else "Sample tuning", entry.nativePitch, 65535, "sound-value-pitch",
                String.format(Locale.ROOT, "%.3f×", entry.tuningMultiplier)) {
                onCommit(entry.copy(pitchAdj = ((it and 255) shl 8) or (it ushr 8)))
            }
        }
        Text("Type decimal or 0x hex · Enter or leave the field to apply · Esc to cancel", fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SoundValueInput(label: String, description: String, value: Int, maxValue: Int, tag: String,
    hint: String, onCommit: (Int) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val focus = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value.toString()) }
    var submitted by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    val commit by rememberUpdatedState(onCommit)
    val candidate = parseSoundValue(text, maxValue)
    fun apply() {
        val next = parseSoundValue(text, maxValue) ?: return
        text = next.toString()
        if (next != submitted) { submitted = next; commit(next) }
    }
    Column(Modifier.width(138.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(description, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(text, { text = it }, singleLine = true, shape = SoundControlShape,
            label = { Text(label, fontSize = fs.detail) }, isError = candidate == null,
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = fs.body, fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().testTag(tag)
                .onFocusChanged {
                    if (focused && !it.isFocused) apply()
                    focused = it.isFocused
                }
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            if (event.type == KeyEventType.KeyDown) { apply(); focus.clearFocus() }
                            true
                        }
                        Key.Escape -> {
                            if (event.type == KeyEventType.KeyDown) { text = currentValue.toString(); focus.clearFocus() }
                            true
                        }
                        else -> false
                    }
                })
        Text(if (candidate == null) "0–$maxValue or 0x hex" else
            "0x${candidate.toString(16).uppercase().padStart(if (maxValue > 255) 4 else 2, '0')} · $hint",
            fontSize = fs.detail, color = if (candidate == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun parseSoundValue(text: String, maxValue: Int): Int? {
    val input = text.trim()
    return (if (input.startsWith("0x", ignoreCase = true)) input.drop(2).toIntOrNull(16) else input.toIntOrNull())
        ?.takeIf { it in 0..maxValue }
}
