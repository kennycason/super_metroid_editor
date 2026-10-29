package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.NspcRenderer.InstrumentEntry
import java.util.Locale

data class MusicSampleBrowserState(
    val samples: List<MusicSample> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val problem: String? = null,
    val previewSource: Int? = null,
    val previewBusy: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InstrumentRecordingEditor(entry: InstrumentEntry, sourcePitch: Int?, browser: MusicSampleBrowserState,
    instruments: List<InstrumentEntry>, instrumentNames: Map<Int, String>, usedIds: Set<Int>, noteName: String,
    previewAvailable: Boolean, onPreview: (Int, Boolean) -> Unit, onChoose: (Int) -> Unit,
    onRename: (String, String) -> Unit, onControlCommit: (InstrumentEntry) -> Unit, onTrySample: (() -> Unit)? = null) {
    val fs = LocalEditorTheme.current.fontSize.value
    var choosing by remember(entry.index, entry.srcn, browser.samples) { mutableStateOf(false) }
    val current = browser.samples.firstOrNull { it.srcn == entry.srcn }
    Column(Modifier.fillMaxWidth().testTag("instrument-recording-editor"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (entry.srcn >= 0x80) "Noise source" else "Recording", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current?.let { browser.names[it.resourceId] ?: it.title } ?: if (browser.loading) "Loading samples…" else "Unlisted source #${entry.srcn}",
                    fontSize = fs.body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(shape = SoundControlShape, onClick = { choosing = !choosing }, enabled = !browser.loading && browser.samples.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp).testTag("sample-choose")) {
                    Text(if (choosing) "Close samples" else "Change source", fontSize = fs.body)
                }
                if (onTrySample != null) OutlinedButton(shape = SoundControlShape, onClick = onTrySample,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp).testTag("sample-try-draft")) { Text("New recording", fontSize = fs.body) }
            }
        }
        Text(browser.problem ?: current?.let(::sampleDetail) ?: if (browser.loading) "Reading this bank" else "Check raw SRCN in Advanced",
            fontSize = fs.detail, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (choosing) {
            MusicSamplePicker(entry.srcn, browser, instruments, instrumentNames, usedIds, noteName, previewAvailable,
                onPreview, onChoose = { onChoose(it); choosing = false }, onCancel = { choosing = false }, onRename)
        } else {
            SampleWaveform(current, Modifier.fillMaxWidth().height(32.dp))
            NativeInstrumentPanel(entry, sourcePitch, onControlCommit)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MusicSamplePicker(currentSrcn: Int, browser: MusicSampleBrowserState, instruments: List<InstrumentEntry>,
    instrumentNames: Map<Int, String>, usedIds: Set<Int>, noteName: String, previewAvailable: Boolean,
    onPreview: (Int, Boolean) -> Unit, onChoose: (Int) -> Unit, onCancel: () -> Unit, onRename: (String, String) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    var query by remember { mutableStateOf("") }
    var noise by remember { mutableStateOf(currentSrcn >= 0x80) }
    var usedOnly by remember { mutableStateOf(false) }
    var reference by remember { mutableStateOf(false) }
    var candidate by remember(currentSrcn, browser.samples) { mutableStateOf(currentSrcn) }
    var renaming by remember(candidate) { mutableStateOf(false) }
    val chosen = browser.samples.firstOrNull { it.srcn == candidate }
    var name by remember(chosen?.resourceId, browser.names) { mutableStateOf(chosen?.let { browser.names[it.resourceId] }.orEmpty()) }
    val usedSources = instruments.filter { it.index in usedIds }.mapTo(mutableSetOf()) { it.srcn }
    fun usage(sample: MusicSample) = instruments.filter { it.srcn == sample.srcn && !InstrumentDescriptions.empty(it) }
        .joinToString { instrumentNames[it.index] ?: "Sound #${it.index}" }
    fun title(sample: MusicSample) = browser.names[sample.resourceId] ?: sample.title
    val available = browser.samples.filter { it.isNoise == noise }
    val visible = available.filter { sample ->
        (!usedOnly || sample.srcn in usedSources) && query.trim().split(Regex("\\s+")).all { term ->
            "${title(sample)} ${sampleDetail(sample)} ${usage(sample)} ${sample.srcn} 0x${sample.srcn.toString(16).padStart(2, '0')} ${if (sample.isNoise) "noise rate ${sample.srcn and 31}" else "sample"}".contains(term, ignoreCase = true)
        }
    }.sortedWith(compareByDescending<MusicSample> { it.srcn == currentSrcn }.thenByDescending { it.srcn in usedSources }.thenBy { it.srcn })
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().testTag("sample-browser"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${visible.size} of ${available.size} ${if (noise) "noise sources" else "recordings"} · current bank", fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sample-count"))
        OutlinedTextField(query, { query = it }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = fs.body),
            placeholder = { Text("Search names, sources, or IDs", fontSize = fs.body) }, modifier = Modifier.fillMaxWidth().testTag("sample-search"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!noise, { noise = false; candidate = if (currentSrcn < 0x80) currentSrcn else -1 }, shape = SoundControlShape,
                label = { Text("Recordings", fontSize = fs.detail) }, modifier = Modifier.testTag("sample-recordings"))
            FilterChip(noise, { noise = true; candidate = if (currentSrcn >= 0x80) currentSrcn else -1 }, shape = SoundControlShape,
                label = { Text("Noise", fontSize = fs.detail) }, modifier = Modifier.testTag("sample-noise"))
            FilterChip(usedOnly, { usedOnly = !usedOnly }, shape = SoundControlShape, label = { Text("Used in track", fontSize = fs.detail) }, modifier = Modifier.testTag("sample-used"))
        }
        Text(if (noise) "Preview uses this envelope · noise rate sets pitch" else "Preview with this sound's envelope + tuning",
            fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!reference, { reference = false }, enabled = !noise, shape = SoundControlShape,
                label = { Text("This note ($noteName)", fontSize = fs.detail) }, modifier = Modifier.testTag("sample-note-pitch"))
            FilterChip(reference, { reference = true }, enabled = !noise, shape = SoundControlShape,
                label = { Text("Middle C (C4)", fontSize = fs.detail) }, modifier = Modifier.testTag("sample-reference-pitch"))
        }
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            if (visible.isEmpty()) Text("No matching sources", fontSize = fs.body, modifier = Modifier.align(Alignment.Center))
            Column(Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scroll)) {
                visible.forEach { sample -> key(sample.srcn) {
                    Surface(shape = SoundControlShape,
                        color = if (sample.srcn == candidate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp).testTag("sample-row-${sample.srcn}")
                            .semantics { selected = sample.srcn == candidate }.clickable(role = Role.RadioButton) { candidate = sample.srcn }) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!sample.isNoise) SampleWaveform(sample, Modifier.width(64.dp).height(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title(sample), fontSize = fs.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(sampleDetail(sample), fontSize = fs.detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (sample.isNoise) "${sample.srcn and 31}" else "#${sample.srcn}", fontSize = fs.detail)
                            IconButton(onClick = { candidate = sample.srcn; onPreview(sample.srcn, reference) }, enabled = previewAvailable,
                                modifier = Modifier.size(32.dp).testTag("sample-preview-${sample.srcn}")) {
                                if (browser.previewBusy && browser.previewSource == sample.srcn) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.PlayArrow, "Preview ${title(sample)}", Modifier.size(18.dp))
                            }
                        }
                    }
                } }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        SampleWaveform(chosen, Modifier.fillMaxWidth().height(36.dp))
        // Keep the selected-source detail slot stable while rows/preview states change.
        Text(chosen?.let { "Bank sounds: ${usage(it).ifEmpty { "none listed" }}" }.orEmpty(), fontSize = fs.detail,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().height(22.dp).testTag("sample-usage"))
        if (renaming && chosen != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it.take(64) }, singleLine = true, label = { Text("Sample name", fontSize = fs.detail) },
                textStyle = LocalTextStyle.current.copy(fontSize = fs.body), modifier = Modifier.weight(1f).testTag("sample-name"))
            Button(shape = SoundControlShape, onClick = { onRename(chosen.resourceId, name); renaming = false }, modifier = Modifier.testTag("sample-name-save")) { Text("Save", fontSize = fs.body) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Button(shape = SoundControlShape, onClick = { chosen?.let { onChoose(it.srcn) } }, enabled = chosen != null && chosen in visible,
                modifier = Modifier.testTag("sample-apply")) { Text(if (chosen?.isNoise == true) "Use noise" else "Use sample", fontSize = fs.body) }
            OutlinedButton(shape = SoundControlShape, onClick = onCancel, modifier = Modifier.testTag("sample-cancel")) { Text("Cancel", fontSize = fs.body) }
            TextButton(shape = SoundControlShape, onClick = { renaming = !renaming }, enabled = chosen != null,
                modifier = Modifier.testTag("sample-rename")) { Text(if (chosen?.isNoise == true) "Rename noise" else "Rename sample", fontSize = fs.body) }
        }
        Text("Shared sound · keeps envelope + tuning", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sampleDetail(sample: MusicSample): String = if (sample.isNoise) "Generated · no recording" else {
    val length = when {
        sample.frames < 32 -> "<1 ms"
        sample.frames < 32000 -> "${sample.frames * 1000 / 32000} ms"
        else -> String.format(Locale.ROOT, "%.2f s", sample.frames / 32000.0)
    }
    "$length recorded · ${if (sample.loopFrame != null) "loop" else "one-shot"} · ${sample.origin}" +
        if (sample.importedFrom.isNotEmpty()) " · ${sample.importedFrom.joinToString()}" else ""
}

@Composable
private fun SampleWaveform(sample: MusicSample?, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val color = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outlineVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        if (sample == null || sample.isNoise) {
            Text(if (sample?.isNoise == true) "Generated noise · rate ${sample.srcn and 31}" else "No recording to display",
                fontSize = fs.detail, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Recording waveform${if (sample.loopFrame != null) " with loop marker" else ""}" }) {
            val center = size.height / 2
            drawLine(guide, Offset(0f, center), Offset(size.width, center))
            sample.loopFrame?.let { loop ->
                val x = size.width * loop / sample.frames.coerceAtLeast(1)
                drawRect(color.copy(alpha = 0.08f), Offset(x, 0f), Size(size.width - x, size.height))
                drawLine(color.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height))
            }
            sample.waveform.forEachIndexed { i, peak ->
                val x = size.width * (i + 0.5f) / sample.waveform.size
                drawLine(color, Offset(x, center - peak.high * (center - 2)), Offset(x, center - peak.low * (center - 2)), strokeWidth = 1.5f)
            }
        }
    }
}
