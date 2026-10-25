package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.NspcRenderer.InstrumentEntry
import com.supermetroid.editor.rom.NspcSequence

/** Describes what we know from the current bank, without inventing a timbre from an instrument ID. */
internal object InstrumentDescriptions {
    fun empty(entry: InstrumentEntry): Boolean =
        entry.srcn == 0 && entry.adsr1 == 0 && entry.adsr2 == 0 && entry.gain == 0 && entry.pitchAdj == 0

    fun title(entry: InstrumentEntry, names: Map<Int, String>): String = names[entry.index] ?: when {
        empty(entry) -> "Empty slot"
        entry.srcn >= 0x80 -> "Noise"
        entry.sampleStartAddr < 0 || entry.brrByteLength < 9 -> "Unavailable sample"
        entry.brrLoops -> "Looped sound"
        entry.brrByteLength / 9 * 16 < 6400 -> "Short one-shot"
        else -> "One-shot sound"
    }

    fun detail(entry: InstrumentEntry): String {
        val source = if (entry.srcn >= 0x80) "Noise rate ${entry.srcn and 31}" else "Sample ${entry.srcn}"
        val envelope = if (entry.adsr1 and 0x80 == 0) "Gain envelope" else when (entry.adsr1 and 15) {
            in 0..5 -> "Slow attack"
            in 6..11 -> "Soft attack"
            else -> "Fast attack"
        }
        return "$source · $envelope"
    }

    fun playable(entry: InstrumentEntry): Boolean =
        !empty(entry) && (entry.srcn >= 0x80 || (entry.sampleStartAddr >= 0 && entry.brrByteLength >= 9))

    fun matches(entry: InstrumentEntry, names: Map<Int, String>, query: String): Boolean {
        val id = entry.index.toString(16).uppercase().padStart(2, '0')
        return query.trim().split(Regex("\\s+")).all { term ->
            "${title(entry, names)} ${detail(entry)} ${entry.index} 0x$id".contains(term, ignoreCase = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteInstrumentSelector(
    note: NspcSequence.Note,
    instruments: List<InstrumentEntry>,
    names: Map<Int, String>,
    usedIds: Set<Int>,
    bankLabel: String,
    nativePreviewAvailable: Boolean,
    previewInstrument: Int?,
    previewBusy: Boolean,
    previewMessage: String,
    onPreview: (Int, Boolean) -> Unit,
    onStopPreview: () -> Unit,
    onRename: (Int, String) -> Unit,
    onChoose: (Int) -> Unit,
    advancedContent: @Composable () -> Unit,
    sharedTracks: List<String> = emptyList(),
    sharedInstrumentEdited: Boolean = false,
    onResetSharedInstrument: () -> Unit = {},
    instrumentEditingEnabled: Boolean = true,
    sourceLabel: String = "ROM bank",
    soundContent: @Composable () -> Unit = {},
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val info = instruments.firstOrNull { it.index == note.instrument }
    var choosing by remember(bankLabel, note.instrument) { mutableStateOf(false) }
    var advanced by remember(bankLabel) { mutableStateOf(false) }
    var editing by remember(bankLabel) { mutableStateOf(false) }
    var renaming by remember(bankLabel, note.instrument) { mutableStateOf(false) }
    var name by remember(bankLabel, note.instrument, names) { mutableStateOf(names[note.instrument].orEmpty()) }

    Surface(
        modifier = Modifier.testTag("note-sound-selector"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info?.let { InstrumentDescriptions.title(it, names) } ?: "Unknown instrument",
                        fontSize = fs.heading, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(info?.let(InstrumentDescriptions::detail) ?: "Unknown sound",
                        fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Sound #${note.instrument}", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(shape = SoundControlShape, onClick = { onPreview(note.instrument, false) },
                    enabled = nativePreviewAvailable && info?.let(InstrumentDescriptions::playable) == true,
                    modifier = Modifier.testTag("instrument-preview-current")) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                    Text("Play ${note.name}", fontSize = fs.body)
                }
                Button(shape = SoundControlShape, onClick = { choosing = !choosing; if (choosing) editing = false }, enabled = instruments.isNotEmpty(),
                    modifier = Modifier.testTag("instrument-choose")) {
                    Text(if (choosing) "Close sounds" else "Choose sound", fontSize = fs.body)
                }
                OutlinedButton(shape = SoundControlShape, onClick = { renaming = !renaming }, enabled = info != null) {
                    Text("Rename", fontSize = fs.body)
                }
                OutlinedButton(shape = SoundControlShape, onClick = { editing = !editing; if (editing) choosing = false },
                    enabled = instrumentEditingEnabled && info != null, modifier = Modifier.testTag("instrument-edit-sound")) {
                    Text(if (editing) "Close editor" else "Edit sound", fontSize = fs.body)
                }
            }
            if (sharedTracks.isNotEmpty()) SharedInstrumentScope(sharedTracks, sharedInstrumentEdited, onResetSharedInstrument, instrumentEditingEnabled, note.instrument < 24)
            if (renaming) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(64) }, label = { Text("Instrument name") }, singleLine = true,
                        modifier = Modifier.weight(1f).testTag("instrument-name"))
                    Button(shape = SoundControlShape, onClick = { onRename(note.instrument, name); renaming = false }) { Text("Save", fontSize = fs.body) }
                }
            }
            // A permanent status slot and Stop button keep a bottom-anchored inspector
            // stationary through preparing/playing/completed/error transitions.
            Row(Modifier.fillMaxWidth().height(36.dp).testTag("instrument-preview-status"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    if (previewBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(when {
                    !nativePreviewAvailable -> "Native preview unavailable"
                    previewMessage.isNotEmpty() -> previewMessage
                    else -> "Ready to preview"
                }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                OutlinedButton(shape = SoundControlShape, onClick = onStopPreview, enabled = previewInstrument != null,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.width(64.dp).height(32.dp).testTag("instrument-preview-stop")) {
                    Text("Stop", fontSize = fs.body)
                }
            }
            if (choosing) {
                InstrumentPicker(instruments, names, usedIds, note.instrument, bankLabel, note.name,
                    nativePreviewAvailable, previewInstrument, previewBusy, onPreview,
                    onApply = { onChoose(it); choosing = false }, onCancel = { choosing = false }, sourceLabel = sourceLabel)
            }
            if (editing && instrumentEditingEnabled) {
                soundContent()
                TextButton(shape = SoundControlShape, onClick = { advanced = !advanced }, modifier = Modifier.testTag("instrument-advanced")) {
                    Text(if (advanced) "Hide advanced" else "Advanced", fontSize = fs.body)
                }
                if (advanced) advancedContent()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SharedInstrumentScope(tracks: List<String>, edited: Boolean, onReset: () -> Unit, enabled: Boolean = true, commonSlot: Boolean = false) {
    val fs = LocalEditorTheme.current.fontSize.value
    var expanded by remember(tracks) { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose: this note · Edit: shared bank", fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        Box {
            TextButton(shape = SoundControlShape, onClick = { expanded = true }, modifier = Modifier.testTag("instrument-bank-scope")) {
                Text("${tracks.size} ${if (tracks.size == 1) "track" else "tracks"} ▾", fontSize = fs.detail)
            }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.testTag("instrument-bank-tracks")) {
                Column(Modifier.padding(horizontal = 12.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Bank loaded by", fontSize = fs.detail, fontWeight = FontWeight.Medium)
                    tracks.forEach { Text(it, fontSize = fs.body) }
                    Text("Effects may share these sounds", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (edited) TextButton(shape = SoundControlShape, onClick = onReset, enabled = enabled, modifier = Modifier.testTag("instrument-bank-reset")) {
            Text("Reset sound", fontSize = fs.detail)
        }
        if (commonSlot) Text("Common sound · edits can block ROM export", fontSize = fs.detail,
            color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("instrument-common-warning").padding(top = 12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun InstrumentPicker(
    instruments: List<InstrumentEntry>,
    names: Map<Int, String>,
    usedIds: Set<Int>,
    currentId: Int,
    bankLabel: String,
    noteName: String,
    nativePreviewAvailable: Boolean,
    previewInstrument: Int?,
    previewBusy: Boolean,
    onPreview: (Int, Boolean) -> Unit,
    onApply: (Int) -> Unit,
    onCancel: () -> Unit,
    sourceLabel: String = "ROM bank",
) {
    val fs = LocalEditorTheme.current.fontSize.value
    var query by remember { mutableStateOf("") }
    var usedOnly by remember { mutableStateOf(false) }
    var referencePitch by remember { mutableStateOf(false) }
    var candidate by remember(currentId) { mutableStateOf(currentId) }
    val available = instruments.filter(InstrumentDescriptions::playable)
    val visible = available.filter { (!usedOnly || it.index in usedIds) && InstrumentDescriptions.matches(it, names, query) }
        .sortedWith(compareByDescending<InstrumentEntry> { it.index == currentId }.thenByDescending { it.index in usedIds }.thenBy { it.index })
    val listState = rememberScrollState()
    Column(Modifier.fillMaxWidth().testTag("instrument-browser"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(bankLabel, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${visible.size} of ${available.size} sounds · ${available.count { it.index in usedIds }} used · $sourceLabel",
            fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("instrument-bank-count"))
        OutlinedTextField(query, { query = it }, singleLine = true,
            placeholder = { Text("Search names, samples, or IDs", fontSize = fs.body) },
            modifier = Modifier.fillMaxWidth().testTag("instrument-search"))
        Text("Preview pitch", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(usedOnly, { usedOnly = !usedOnly }, label = { Text("Used in track", fontSize = fs.detail) },
                modifier = Modifier.testTag("instrument-used-filter"))
            FilterChip(!referencePitch, { referencePitch = false }, label = { Text("This note ($noteName)", fontSize = fs.detail) },
                modifier = Modifier.testTag("instrument-note-pitch"))
            FilterChip(referencePitch, { referencePitch = true }, label = { Text("Middle C (C4)", fontSize = fs.detail) },
                modifier = Modifier.testTag("instrument-reference-pitch"))
        }
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            if (visible.isEmpty()) Text("No matching instruments", fontSize = fs.body, modifier = Modifier.align(Alignment.Center))
            Column(modifier = Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(listState)) {
                for (entry in visible) key(entry.index) {
                    val selected = candidate == entry.index
                    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)
                            .semantics { this.selected = selected }.testTag("instrument-row-${entry.index}")
                            .clickable { candidate = entry.index }) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(InstrumentDescriptions.title(entry, names), fontSize = fs.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(InstrumentDescriptions.detail(entry), fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("#${entry.index}", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { onPreview(entry.index, referencePitch) },
                                enabled = nativePreviewAvailable && InstrumentDescriptions.playable(entry),
                                modifier = Modifier.size(32.dp).testTag("instrument-preview-${entry.index}")) {
                                if (previewBusy && previewInstrument == entry.index) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.PlayArrow, "Preview ${InstrumentDescriptions.title(entry, names)}", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(shape = SoundControlShape, onClick = { onApply(candidate) }, enabled = visible.any { it.index == candidate && InstrumentDescriptions.playable(it) },
                modifier = Modifier.testTag("instrument-apply")) { Text("Use sound", fontSize = fs.body) }
            OutlinedButton(shape = SoundControlShape, onClick = onCancel, modifier = Modifier.testTag("instrument-cancel")) { Text("Cancel", fontSize = fs.body) }
            Text("This note only", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
