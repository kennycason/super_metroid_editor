package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.NspcRenderer.InstrumentEntry
import java.util.Locale
import kotlin.math.roundToInt

/** Gesture-local drafts drive the graph. Only a finished gesture changes the shared bank/undo history. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun NativeInstrumentPanel(entry: InstrumentEntry, sourcePitch: Int?, onCommit: (InstrumentEntry) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    var draft by remember(entry) { mutableStateOf(entry) }
    fun commit(updated: InstrumentEntry = draft) {
        draft = updated
        if (updated != entry) onCommit(updated)
    }
    val envelope = NativeInstrumentSound.usesEnvelope(draft)
    Column(Modifier.fillMaxWidth().testTag("native-sound-controls"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FilterChip(envelope, { commit(NativeInstrumentSound.envelope(draft, true)) },
                shape = SoundControlShape, label = { Text("Envelope", fontSize = fs.body) }, modifier = Modifier.testTag("sound-mode-envelope"))
            FilterChip(!envelope, { commit(NativeInstrumentSound.envelope(draft, false)) },
                shape = SoundControlShape, label = { Text("Gain", fontSize = fs.body) }, modifier = Modifier.testTag("sound-mode-gain"))
        }
        EnvelopeGuide(draft)
        if (envelope) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SoundParameter("Attack", NativeInstrumentSound.attack(draft), 0..15, timeLabel(NativeInstrumentSound.attackSeconds(draft)),
                    "sound-attack", { draft = NativeInstrumentSound.attack(draft, it) }, { commit() })
                SoundParameter("Decay", NativeInstrumentSound.decay(draft), 0..7,
                    if (NativeInstrumentSound.sustain(draft) == 7) "No level drop" else timeLabel(NativeInstrumentSound.decaySeconds(draft)),
                    "sound-decay", { draft = NativeInstrumentSound.decay(draft, it) }, { commit() }, enabled = NativeInstrumentSound.sustain(draft) != 7)
                SoundParameter("Sustain level", NativeInstrumentSound.sustain(draft), 0..7,
                    String.format(Locale.ROOT, "%.1f%%", (NativeInstrumentSound.sustain(draft) + 1) * 12.5),
                    "sound-sustain", { draft = NativeInstrumentSound.sustain(draft, it) }, { commit() })
                SoundParameter("Sustain fade", NativeInstrumentSound.fade(draft), 0..31,
                    NativeInstrumentSound.fadeSeconds(draft)?.let(::timeLabel) ?: "Hold level",
                    "sound-fade", { draft = NativeInstrumentSound.fade(draft, it) }, { commit() })
            }
        } else {
            val mode = NativeInstrumentSound.gainMode(draft)
            var expanded by remember { mutableStateOf(false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.padding(top = 4.dp)) {
                    OutlinedButton(shape = SoundControlShape, onClick = { expanded = true }, modifier = Modifier.testTag("sound-gain-mode")) {
                        Text("${mode.label} ▾", fontSize = fs.body)
                    }
                    DropdownMenu(expanded, { expanded = false }) {
                        NativeInstrumentSound.GainMode.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.label, fontSize = fs.body) },
                                onClick = { expanded = false; commit(NativeInstrumentSound.gainMode(draft, choice)) },
                                modifier = Modifier.testTag("sound-gain-${choice.name}"))
                        }
                    }
                }
                SoundParameter(if (mode == NativeInstrumentSound.GainMode.FIXED) "Level" else "Rate",
                    NativeInstrumentSound.gainValue(draft), 0..if (mode == NativeInstrumentSound.GainMode.FIXED) 127 else 31,
                    if (mode == NativeInstrumentSound.GainMode.FIXED) "${(NativeInstrumentSound.gainValue(draft) * 100.0 / 127).roundToInt()}%"
                    else if (NativeInstrumentSound.gainValue(draft) == 0) "Hold" else "${NativeInstrumentSound.gainValue(draft)} / 31 · faster →",
                    "sound-gain-value", { draft = NativeInstrumentSound.gainValue(draft, it) }, { commit() })
            }
            if (mode == NativeInstrumentSound.GainMode.FALL || mode == NativeInstrumentSound.GainMode.EXPONENTIAL_FALL) {
                Text("Falling gain starts silent on a new note", fontSize = fs.detail, color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag("sound-gain-silent"))
            } else if (mode != NativeInstrumentSound.GainMode.FIXED && NativeInstrumentSound.gainValue(draft) == 0) {
                Text("Rate 0 holds silence on a new note", fontSize = fs.detail, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Text("Key-off release: fixed · use Play to hear changes", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TuningControls(draft, entry, sourcePitch, onDraft = { draft = it }, onCommit = { commit(it) }, onFinish = { commit() })
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun SoundParameter(label: String, value: Int, range: IntRange, readout: String, tag: String,
    onDraft: (Int) -> Unit, onCommit: () -> Unit, enabled: Boolean = true) {
    val fs = LocalEditorTheme.current.fontSize.value
    var keyboardDraft by remember { mutableStateOf(false) }
    Column(Modifier.width(148.dp)) {
        Text(label, fontSize = fs.body, color = MaterialTheme.colorScheme.onSurface)
        Text(readout, fontSize = fs.detail, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value.coerceIn(range).toFloat(), onValueChange = { onDraft(it.roundToInt()) },
            onValueChangeFinished = onCommit, valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0), enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(30.dp).testTag(tag).semantics { contentDescription = label }
                // This project's Compose Material slider exposes accessibility progress
                // but has no desktop arrow-key handler. Preserve native one-step editing.
                .onFocusChanged { if (!it.isFocused && keyboardDraft) { keyboardDraft = false; onCommit() } }
                .onPreviewKeyEvent { event ->
                    if (!enabled) return@onPreviewKeyEvent false
                    val next = when (event.key) {
                        Key.DirectionLeft, Key.DirectionDown -> value - 1
                        Key.DirectionRight, Key.DirectionUp -> value + 1
                        Key.MoveHome -> range.first
                        Key.MoveEnd -> range.last
                        else -> return@onPreviewKeyEvent false
                    }
                    when (event.type) {
                        KeyEventType.KeyDown -> { keyboardDraft = true; onDraft(next.coerceIn(range)); true }
                        KeyEventType.KeyUp -> { keyboardDraft = false; onCommit(); true }
                        else -> false
                    }
                })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuningControls(entry: InstrumentEntry, committed: InstrumentEntry, sourcePitch: Int?, onDraft: (InstrumentEntry) -> Unit,
    onCommit: (InstrumentEntry) -> Unit, onFinish: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    if (entry.srcn >= 0x80 || sourcePitch == null || sourcePitch == 0) {
        Text(if (entry.srcn >= 0x80) "Noise pitch uses its source rate" else "Source tuning unavailable · use Advanced",
            fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sound-tuning-unavailable"))
        return
    }
    val cents = NativeInstrumentSound.tuningCents(entry, sourcePitch)
    val baseCents = NativeInstrumentSound.tuningCents(committed, sourcePitch) ?: 0.0
    // Keep the two axes stable during a gesture: native rounding near 50 cents
    // must not flip a fine-tune drag into another semitone while the pointer is down.
    var semitones by remember(sourcePitch, committed.nativePitch) { mutableStateOf((baseCents / 100).roundToInt()) }
    var fine by remember(sourcePitch, committed.nativePitch) { mutableStateOf((baseCents - (baseCents / 100).roundToInt() * 100).roundToInt()) }
    fun finish() {
        onFinish()
        val actual = cents ?: 0.0
        semitones = (actual / 100).roundToInt()
        fine = (actual - semitones * 100).roundToInt()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tuning", fontSize = fs.body)
        Text(if (cents == null) "Zero / silent" else String.format(Locale.ROOT, "%+.0f cents · source-relative", cents),
            fontSize = fs.detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).testTag("sound-tuning-readout"))
        TextButton(shape = SoundControlShape, onClick = { onCommit(NativeInstrumentSound.tuning(entry, sourcePitch, 0.0)) },
            enabled = entry.nativePitch != sourcePitch, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp).testTag("sound-tuning-reset")) { Text("Reset tuning", fontSize = fs.detail) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SoundParameter("Transpose", semitones, minOf(-24, semitones)..maxOf(24, semitones), "$semitones semitones", "sound-transpose",
            { semitones = it; onDraft(NativeInstrumentSound.tuning(entry, sourcePitch, it * 100.0 + fine)) }, ::finish)
        SoundParameter("Fine", fine, -50..50, "$fine cents", "sound-fine",
            { fine = it; onDraft(NativeInstrumentSound.tuning(entry, sourcePitch, semitones * 100.0 + it)) }, ::finish)
    }
}

@Composable
private fun EnvelopeGuide(entry: InstrumentEntry) {
    val fs = LocalEditorTheme.current.fontSize.value
    val levels = remember(entry.adsr1, entry.adsr2, entry.gain) { NativeInstrumentSound.envelopeGuide(entry) }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Text("Envelope guide · 2 s hold", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .testTag("sound-envelope-guide").semantics { contentDescription = "Envelope level over two seconds, then fixed key-off release; sample playback may end sooner" }) {
            val left = 4f; val width = (size.width - 8).coerceAtLeast(1f); val height = (size.height - 8).coerceAtLeast(1f)
            for (fraction in listOf(0f, 0.5f, 1f)) drawLine(grid, Offset(left, 4 + height * fraction), Offset(left + width, 4 + height * fraction))
            val path = Path()
            val points = width.roundToInt().coerceAtLeast(2)
            for (i in 0..points) {
                val index = (i.toLong() * levels.lastIndex / points).toInt()
                val x = left + width * i / points
                val y = 4 + height * (1 - levels[index] / 2047f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(2f))
            val keyOff = left + width * NativeInstrumentSound.HOLD_FRAMES / levels.size
            drawLine(grid, Offset(keyOff, 4f), Offset(keyOff, 4 + height), strokeWidth = 1f)
        }
    }
}

private fun timeLabel(seconds: Double): String = when {
    seconds < 0.001 -> "<1 ms"
    seconds < 1 -> "~${(seconds * 1000).roundToInt()} ms"
    else -> String.format(Locale.ROOT, "~%.2f s", seconds)
}
