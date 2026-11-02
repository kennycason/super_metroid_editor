package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.NspcRenderer.InstrumentEntry
import java.util.Locale
import kotlin.math.roundToInt

/** Gesture-local drafts drive the graph. Only a finished gesture changes the shared bank/undo history. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun NativeInstrumentPanel(entry: InstrumentEntry, sourcePitch: Int?, onCommit: (InstrumentEntry) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    // All subcompositions/gesture callbacks retain the same state cells. Replacing
    // those cells on each bank commit leaves an otherwise unchanged tuning module
    // pointing at the previous envelope draft.
    var draft by remember { mutableStateOf(entry) }
    var submitted by remember { mutableStateOf(entry) }
    val currentCommit by rememberUpdatedState(onCommit)
    remember(entry) { draft = entry; submitted = entry }
    fun commit(updated: InstrumentEntry = draft) {
        draft = updated
        if (updated != submitted) { submitted = updated; currentCommit(updated) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("native-sound-controls")) {
        val wide = maxWidth >= 600.dp
        val valuesBeside = maxWidth >= 1000.dp
        val fourAcross = (if (wide) maxWidth - 162.dp - (if (valuesBeside) 322.dp else 0.dp) else maxWidth - 20.dp) >= 424.dp
        val envelopeContent: @Composable ColumnScope.() -> Unit = {
            val envelope = NativeInstrumentSound.usesEnvelope(draft)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(envelope, { commit(NativeInstrumentSound.envelope(draft, true)) }, shape = SoundControlShape,
                    label = { Text("Envelope", fontSize = fs.body) }, modifier = Modifier.testTag("sound-mode-envelope"))
                FilterChip(!envelope, { commit(NativeInstrumentSound.envelope(draft, false)) }, shape = SoundControlShape,
                    label = { Text("Gain", fontSize = fs.body) }, modifier = Modifier.testTag("sound-mode-gain"))
                if (envelope) EnvelopeShapes(draft) { commit(it) }
            }
            EnvelopeGuide(draft, if (wide) Modifier.weight(1f) else Modifier)
            if (envelope) {
                // Time knobs turn clockwise for longer times; raw native rates run backwards.
                Box(Modifier.fillMaxWidth()) {
                    val controls: List<@Composable () -> Unit> = listOf(
                        { SoundParameter("Attack", 15 - NativeInstrumentSound.attack(draft), 0..15, timeLabel(NativeInstrumentSound.attackSeconds(draft)),
                            "sound-attack", { draft = NativeInstrumentSound.attack(draft, 15 - it) }, { commit() }) },
                        { SoundParameter("Decay", 7 - NativeInstrumentSound.decay(draft), 0..7,
                            if (NativeInstrumentSound.sustain(draft) == 7) "No drop" else timeLabel(NativeInstrumentSound.decaySeconds(draft)),
                            "sound-decay", { draft = NativeInstrumentSound.decay(draft, 7 - it) }, { commit() }, enabled = NativeInstrumentSound.sustain(draft) != 7) },
                        { SoundParameter("Sustain", NativeInstrumentSound.sustain(draft), 0..7,
                            String.format(Locale.ROOT, "%.1f%%", (NativeInstrumentSound.sustain(draft) + 1) * 12.5),
                            "sound-sustain", { draft = NativeInstrumentSound.sustain(draft, it) }, { commit() }) },
                        { SoundParameter("Held fade", 31 - NativeInstrumentSound.fade(draft), 0..31,
                            NativeInstrumentSound.fadeSeconds(draft)?.let(::timeLabel) ?: "Hold",
                            "sound-fade", { draft = NativeInstrumentSound.fade(draft, 31 - it) }, { commit() }) },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in controls.chunked(if (fourAcross) 4 else 2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { it() }
                        }
                    }
                }
            } else GainControls(draft, { draft = it }, { commit(it) }, { commit() })
        }
        val tuningContent: @Composable ColumnScope.() -> Unit = {
            TuningControls(draft, entry, sourcePitch, wide, onDraft = { draft = it }, onCommit = { commit(it) }, onFinish = { commit() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (wide) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SynthModule(Modifier.weight(1f).fillMaxHeight().testTag("sound-envelope-module"), envelopeContent)
                    SynthModule(Modifier.width(132.dp).fillMaxHeight().testTag("sound-tuning-module"), tuningContent)
                    if (valuesBeside) key(draft.index) {
                        NativeSoundValues(draft, Modifier.width(312.dp).fillMaxHeight()) { commit(it) }
                    }
                }
            } else {
                SynthModule(Modifier.fillMaxWidth(), envelopeContent)
                SynthModule(Modifier.fillMaxWidth(), tuningContent)
            }
            Text("Drag up/down · Shift: fine · click, then scroll or use arrows", fontSize = fs.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!valuesBeside) key(draft.index) { NativeSoundValues(draft, Modifier.fillMaxWidth()) { commit(it) } }
        }
    }
}

@Composable
private fun EnvelopeShapes(entry: InstrumentEntry, onCommit: (InstrumentEntry) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(shape = SoundControlShape, onClick = { expanded = true }, modifier = Modifier.testTag("sound-shape-menu")) {
            Text("${NativeInstrumentSound.matchingShape(entry)?.label ?: "Shape"} ▾", fontSize = fs.body)
        }
        DropdownMenu(expanded, { expanded = false }) {
            Text("Envelope shape", fontSize = fs.detail, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            NativeInstrumentSound.EnvelopeShape.entries.forEach { shape ->
                DropdownMenuItem(text = { Text(shape.label, fontSize = fs.body) },
                    onClick = { expanded = false; onCommit(NativeInstrumentSound.shape(entry, shape)) }, modifier = Modifier.testTag("sound-shape-${shape.name}"))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GainControls(entry: InstrumentEntry, onDraft: (InstrumentEntry) -> Unit, onCommit: (InstrumentEntry) -> Unit, onFinish: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val mode = NativeInstrumentSound.gainMode(entry)
    var expanded by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            OutlinedButton(shape = SoundControlShape, onClick = { expanded = true }, modifier = Modifier.testTag("sound-gain-mode")) { Text("${mode.label} ▾", fontSize = fs.body) }
            DropdownMenu(expanded, { expanded = false }) {
                NativeInstrumentSound.GainMode.entries.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.label, fontSize = fs.body) },
                        onClick = { expanded = false; onCommit(NativeInstrumentSound.gainMode(entry, choice)) }, modifier = Modifier.testTag("sound-gain-${choice.name}"))
                }
            }
        }
        SoundParameter(if (mode == NativeInstrumentSound.GainMode.FIXED) "Level" else "Rate", NativeInstrumentSound.gainValue(entry),
            0..if (mode == NativeInstrumentSound.GainMode.FIXED) 127 else 31,
            if (mode == NativeInstrumentSound.GainMode.FIXED) "${(NativeInstrumentSound.gainValue(entry) * 100.0 / 127).roundToInt()}%"
            else if (NativeInstrumentSound.gainValue(entry) == 0) "Hold" else "${NativeInstrumentSound.gainValue(entry)} / 31",
            "sound-gain-value", { onDraft(NativeInstrumentSound.gainValue(entry, it)) }, onFinish)
    }
    if (mode == NativeInstrumentSound.GainMode.FALL || mode == NativeInstrumentSound.GainMode.EXPONENTIAL_FALL) {
        Text("Falling gain starts silent on a new note", fontSize = fs.detail, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("sound-gain-silent"))
    } else if (mode != NativeInstrumentSound.GainMode.FIXED && NativeInstrumentSound.gainValue(entry) == 0) {
        Text("Rate 0 holds silence on a new note", fontSize = fs.detail, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun SoundParameter(label: String, value: Int, range: IntRange, readout: String, tag: String,
    onDraft: (Int) -> Unit, onCommit: () -> Unit, enabled: Boolean = true) {
    SynthKnob(label, value, range, readout, tag, onDraft, onCommit, enabled)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuningControls(entry: InstrumentEntry, committed: InstrumentEntry, sourcePitch: Int?, vertical: Boolean, onDraft: (InstrumentEntry) -> Unit,
    onCommit: (InstrumentEntry) -> Unit, onFinish: () -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    Text(if (entry.srcn >= 0x80) "Noise" else "Tuning", fontSize = fs.body)
    if (entry.srcn >= 0x80 || sourcePitch == null || sourcePitch == 0) {
        if (entry.srcn >= 0x80) SoundParameter("Rate", entry.srcn and 31, 0..31, "${entry.srcn and 31} / 31", "sound-noise-rate",
            { onDraft(NativeInstrumentSound.noiseRate(entry, it)) }, onFinish)
        Text(if (entry.srcn >= 0x80) "Shared noise clock" else "Source tuning unavailable · set Pitch in Sound values",
            fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sound-tuning-unavailable"))
        return
    }
    val cents = NativeInstrumentSound.tuningCents(entry, sourcePitch)
    val baseCents = NativeInstrumentSound.tuningCents(committed, sourcePitch) ?: 0.0
    // Keep the two axes stable during a gesture: native rounding near 50 cents
    // must not flip a fine-tune drag into another semitone while the pointer is down.
    var semitones by remember(sourcePitch, committed.nativePitch) { mutableStateOf((baseCents / 100).roundToInt()) }
    var fine by remember(sourcePitch, committed.nativePitch) { mutableStateOf((baseCents - (baseCents / 100).roundToInt() * 100).roundToInt()) }
    fun tuned(semitones: Int, fine: Int): InstrumentEntry {
        val baseSemitones = (baseCents / 100).roundToInt()
        val baseFine = (baseCents - baseSemitones * 100).roundToInt()
        // Returning/cancelling a gesture restores the exact native word, not a rounded-cent approximation.
        return if (semitones == baseSemitones && fine == baseFine) entry.copy(pitchAdj = committed.pitchAdj)
        else NativeInstrumentSound.tuning(entry, sourcePitch, semitones * 100.0 + fine)
    }
    fun finish() {
        onFinish()
        val actual = cents ?: 0.0
        semitones = (actual / 100).roundToInt()
        fine = (actual - semitones * 100).roundToInt()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Source-relative", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (cents == null) "Zero / silent" else String.format(Locale.ROOT, "%+.0f cents", cents),
            fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sound-tuning-readout"))
        TextButton(shape = SoundControlShape, onClick = { onCommit(NativeInstrumentSound.tuning(entry, sourcePitch, 0.0)) },
            enabled = entry.nativePitch != sourcePitch, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp).testTag("sound-tuning-reset")) { Text("Reset tuning", fontSize = fs.detail) }
    }
    val knobs: @Composable () -> Unit = {
        val baseSemitones = (baseCents / 100).roundToInt()
        SoundParameter("Transpose", semitones, minOf(-24, baseSemitones)..maxOf(24, baseSemitones), "$semitones semitones", "sound-transpose",
            { semitones = it; onDraft(tuned(it, fine)) }, ::finish)
        SoundParameter("Fine", fine, -50..50, "$fine cents", "sound-fine",
            { fine = it; onDraft(tuned(semitones, it)) }, ::finish)
    }
    if (vertical) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { knobs() }
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { knobs() }
}

@Composable
internal fun EnvelopeGuide(entry: InstrumentEntry, modifier: Modifier = Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val levels = remember(entry.adsr1, entry.adsr2, entry.gain) { NativeInstrumentSound.envelopeGuide(entry) }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.heightIn(min = 108.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("2 s hold", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Release · fixed", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).heightIn(min = 88.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .testTag("sound-envelope-guide").semantics { contentDescription = "Envelope level over two seconds, then fixed key-off release; sample playback may end sooner" }) {
            val left = 4f; val width = (size.width - 8).coerceAtLeast(1f); val height = (size.height - 8).coerceAtLeast(1f)
            for (fraction in listOf(0f, 0.5f, 1f)) {
                drawLine(grid.copy(alpha = 0.5f), Offset(left, 4 + height * fraction), Offset(left + width, 4 + height * fraction))
                drawLine(grid.copy(alpha = 0.5f), Offset(left + width * fraction, 4f), Offset(left + width * fraction, 4 + height))
            }
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
