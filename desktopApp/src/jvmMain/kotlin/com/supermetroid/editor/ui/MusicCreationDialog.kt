package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*

internal class MusicCreationDraft(palette: Int) {
    var name by mutableStateOf("New track")
    var duplicate by mutableStateOf(false)
    var palette by mutableStateOf(palette)
    var newBank by mutableStateOf(true)
    var bankName by mutableStateOf("New sound bank")
    fun signature(version: Int, source: SpcData.TrackInfo?) = listOf(name, duplicate, palette, newBank, bankName, version, source?.id)
}

@Composable
internal fun MusicCreationDialog(parser: RomParser, editor: EditorState, source: SpcData.TrackInfo?, onCreated: (SpcData.TrackInfo) -> Unit, onClose: () -> Unit) {
    val draft = remember { MusicCreationDraft(source?.songSet ?: 3) }
    var plan by remember { mutableStateOf<MusicCreation.Plan?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(true) }
    var generation by remember { mutableStateOf(0) }
    var settled by remember { mutableStateOf<List<Any?>?>(null) }
    val version = editor.musicEditVersion
    val signature = draft.signature(version, source)
    val choices = remember(version) { MusicCatalog.banks(editor.project).map { it to MusicCatalog.bankName(editor.project, it) } }
    LaunchedEffect(draft.name, draft.duplicate, draft.palette, draft.newBank, draft.bankName, version) {
        plan = null; checking = true; problem = null
        val token = ++generation
        val frozen = MusicBankTransitions.snapshot(editor.project)
        val name = draft.name; val bankName = draft.bankName.takeIf { draft.newBank }; val palette = draft.palette
        val original = source.takeIf { draft.duplicate }
        try {
            delay(150)
            plan = withContext(Dispatchers.Default) { MusicCreation.plan(parser, frozen, name, palette, bankName, original) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { problem = error.message ?: "Cannot create this track" }
        finally { if (token == generation) { checking = false; settled = signature } }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MusicCreationForm(draft, source, choices, plan.takeIf { settled == signature }, checking || settled != signature,
            problem.takeIf { settled == signature }, onCreate = {
            plan?.let { ready ->
                if (settled != draft.signature(editor.musicEditVersion, source)) return@let
                try { editor.applyMusicCreation(ready); onCreated(ready.track) }
                catch (error: Exception) { problem = error.message ?: "Cannot create this track" }
            }
        }, onClose = onClose)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MusicCreationForm(draft: MusicCreationDraft, source: SpcData.TrackInfo?, banks: List<Pair<Int, String>>, plan: MusicCreation.Plan?,
    checking: Boolean, problem: String?, onCreate: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    var choosingBank by remember { mutableStateOf(false) }
    Surface(modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(max = 720.dp), shape = SoundControlShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New track", fontSize = fs.heading)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SynthModule(Modifier.fillMaxWidth()) {
                    OutlinedTextField(draft.name, { draft.name = it }, label = { Text("Track name", fontSize = fs.detail) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new-track-name"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!draft.duplicate, { draft.duplicate = false }, label = { Text("Start blank", fontSize = fs.body) }, shape = SoundControlShape)
                        FilterChip(draft.duplicate, {
                            draft.duplicate = true
                            if (draft.name == "New track") draft.name = "${source?.name} copy".take(80)
                        }, enabled = source != null, label = { Text("Duplicate selected", fontSize = fs.body) }, shape = SoundControlShape,
                            modifier = Modifier.testTag("new-track-duplicate"))
                    }
                    if (draft.duplicate && source != null) Text(source.name, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SynthModule(Modifier.fillMaxWidth()) {
                    Text("Sound palette", fontSize = fs.body)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(draft.newBank, { draft.newBank = true }, label = { Text("New bank", fontSize = fs.body) }, shape = SoundControlShape,
                            modifier = Modifier.testTag("new-track-new-bank"))
                        FilterChip(!draft.newBank, { draft.newBank = false }, label = { Text("Existing bank", fontSize = fs.body) }, shape = SoundControlShape,
                            modifier = Modifier.testTag("new-track-existing-bank"))
                    }
                    ExposedDropdownMenuBox(choosingBank, { choosingBank = it }) {
                        OutlinedTextField(
                            value = banks.firstOrNull { it.first == draft.palette }?.second ?: "Choose a sound palette",
                            onValueChange = {}, readOnly = true, singleLine = true,
                            label = { Text(if (draft.newBank) "Copy palette from" else "Use sound bank", fontSize = fs.detail) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(choosingBank) },
                            textStyle = LocalTextStyle.current.copy(fontSize = fs.body),
                            modifier = Modifier.fillMaxWidth().menuAnchor().testTag("new-track-palette"))
                        ExposedDropdownMenu(choosingBank, { choosingBank = false }) {
                            for ((id, name) in banks) DropdownMenuItem({ Text(name, fontSize = fs.body) }, { draft.palette = id; choosingBank = false })
                        }
                    }
                    if (draft.newBank) {
                        OutlinedTextField(draft.bankName, { draft.bankName = it }, label = { Text("Bank name", fontSize = fs.detail) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("new-track-bank-name"))
                        Text("An independent copy for your own sounds. Changes here leave the source bank unchanged.", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text("Reuses this palette. Notes stay separate; editing a sound changes it for every track using this bank.", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(if (checking) "Checking track slots and memory…" else problem ?: plan?.let {
                "${MusicCatalog.bankName(it.after, it.track.songSet)} · track ${it.track.playIndex} · ${it.sequenceBytes} B packed · ${it.gapBytes} B largest remaining gap"
            } ?: "Choose a track name and palette", fontSize = fs.detail,
                color = if (problem == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.heightIn(min = 44.dp).testTag("new-track-status"))
            SoundToolbar {
                SoundToolbarButton("Create track", onCreate, Modifier.testTag("new-track-create"), enabled = !checking && problem == null && plan != null, primary = true)
                SoundToolbarButton("Cancel", onClose)
            }
        }
    }
}
