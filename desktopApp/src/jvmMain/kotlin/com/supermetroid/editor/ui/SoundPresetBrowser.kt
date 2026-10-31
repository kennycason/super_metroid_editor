package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.*
import java.util.UUID

@Composable
internal fun SoundPresetBrowser(parser: RomParser, target: SampleBankPreview.Target, tempo: Int,
    onApply: (SoundPresets.Application) -> Unit, onClose: () -> Unit) {
    val library = remember { SoundPresetLibrary() }
    val audition = remember(parser, target) { SoundPresetAudition(parser, target, tempo) }
    val scope = rememberCoroutineScope()
    var presets by remember { mutableStateOf(emptyList<SoundPreset>()) }
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(target.soundName.take(80)) }
    var capture by remember { mutableStateOf<SoundPreset?>(null) }
    var application by remember { mutableStateOf<SoundPresets.Application?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var fitProblem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var remove by remember { mutableStateOf<SoundPreset?>(null) }
    DisposableEffect(audition) { onDispose { audition.stop() } }
    LaunchedEffect(Unit) {
        try {
            val loaded = withContext(Dispatchers.IO) { library.load() }
            presets = loaded.presets
            audition.select(presets.firstOrNull())
            if (loaded.problems.isNotEmpty()) problem = "${loaded.problems.size} preset(s) could not load: ${loaded.problems.first()}"
            capture = withContext(Dispatchers.Default) {
                SoundPresets.capture(parser, MusicBankResolver.sourceRam(parser, target.project, target.songSet), target.note.instrument, target.soundName.take(80))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { problem = error.message ?: "Cannot open the sound library" }
        finally { busy = false }
    }
    LaunchedEffect(audition.preset) {
        application = null; fitProblem = null
        val preset = audition.preset ?: return@LaunchedEffect
        try { application = withContext(Dispatchers.Default) { SoundPresets.plan(parser, target, preset) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fitProblem = error.message ?: "This preset cannot fit in this bank" }
    }
    LaunchedEffect(audition.refreshVersion) { if (audition.refreshVersion > 0) { delay(60); audition.refresh() } }
    fun save() {
        val source = capture ?: return
        val preset = source.copy(id = UUID.randomUUID().toString(), name = name.trim())
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { library.save(preset) }
                presets = (presets + preset).sortedBy { it.name.lowercase() }
                query = ""; audition.select(preset); problem = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { problem = error.message ?: "Cannot save this preset" }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 1120.dp).fillMaxWidth().heightIn(max = 790.dp), shape = SoundControlShape,
            color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sound presets", fontSize = LocalEditorTheme.current.fontSize.value.heading, fontWeight = FontWeight.Medium)
                        Text("${target.bankName} · ${target.soundName}", fontSize = LocalEditorTheme.current.fontSize.value.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = onClose, shape = SoundControlShape) { Text("Close") }
                }
                SoundPresetBrowserContent(presets, audition, query, { query = it }, name, { name = it },
                    busy, capture != null, application, fitProblem, problem,
                    onSave = ::save, onDelete = { remove = audition.preset },
                    onPlay = { scope.launch { audition.play() } },
                    onKeyDown = { value -> audition.changePitch(value); scope.launch(start = CoroutineStart.UNDISPATCHED) { audition.play(hold = true) } },
                    onApply = {
                        application?.takeIf { it.preset.id == audition.preset?.id }?.let { planned ->
                            try { onApply(planned); audition.stop(); onClose() }
                            catch (error: Exception) { problem = error.message ?: "Cannot apply this preset" }
                        }
                    }, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
    remove?.let { preset ->
        AlertDialog(onDismissRequest = { remove = null }, shape = SoundControlShape,
            title = { Text("Delete ${preset.name}?") },
            text = { Text("Sounds already applied to projects will stay in those projects.") },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Keep preset") } },
            confirmButton = { TextButton(onClick = {
                remove = null
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { library.delete(preset) }
                        presets = presets.filter { it.id != preset.id }; audition.stop(); audition.select(presets.firstOrNull())
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { problem = error.message ?: "Cannot delete this preset" }
                    finally { busy = false }
                }
            }) { Text("Delete preset") } })
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SoundPresetBrowserContent(
    presets: List<SoundPreset>, audition: SoundPresetAudition, query: String, onQuery: (String) -> Unit,
    name: String, onName: (String) -> Unit, busy: Boolean, canSave: Boolean,
    application: SoundPresets.Application?, fitProblem: String?, problem: String?,
    onSave: () -> Unit, onDelete: () -> Unit, onPlay: () -> Unit, onApply: () -> Unit, modifier: Modifier = Modifier,
    onKeyDown: ((Int) -> Unit)? = null,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val selected = audition.preset
    val readyApplication = application?.takeIf { it.preset.id == selected?.id }
    val visible = remember(presets, query) { presets.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    Column(modifier.fillMaxWidth().testTag("sound-preset-browser"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Saving remains visible alongside the piano, even when the details need to scroll.
        SynthModule(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { onName(it.take(80)) }, singleLine = true, label = { Text("Save current sound as", fontSize = fs.detail) },
                    textStyle = LocalTextStyle.current.copy(fontSize = fs.body), modifier = Modifier.weight(1f).testTag("preset-name"))
                OutlinedButton(onSave, enabled = !busy && canSave && name.isNotBlank(), shape = SoundControlShape,
                    modifier = Modifier.testTag("preset-save")) { Text("Save to library", fontSize = fs.body) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth >= 600.dp
            val list: @Composable (Modifier) -> Unit = { listModifier ->
                SynthModule(listModifier) {
                    Text("Your library · ${presets.size}", fontSize = fs.body, fontWeight = FontWeight.Medium)
                    OutlinedTextField(query, onQuery, singleLine = true, label = { Text("Find a preset", fontSize = fs.detail) },
                        textStyle = LocalTextStyle.current.copy(fontSize = fs.body), modifier = Modifier.fillMaxWidth().testTag("preset-search"))
                    val scroll = rememberLazyListState()
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (visible.isEmpty()) Text(if (presets.isEmpty()) "Save your current sound to start a library." else "No matching presets.",
                            fontSize = fs.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(visible, key = { it.id }) { preset ->
                                Surface(Modifier.fillMaxWidth().testTag("preset-${preset.id}").clickable(enabled = !busy) { audition.select(preset) },
                                    shape = SoundControlShape,
                                    color = if (preset.id == selected?.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, if (preset.id == selected?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(preset.name, fontSize = fs.body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(presetSource(preset), fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
            val details: @Composable (Modifier) -> Unit = { detailsModifier ->
                val scroll = rememberScrollState()
                Box(detailsModifier) {
                    Column(Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SynthModule(Modifier.fillMaxWidth()) {
                            Text(selected?.name ?: "Select a preset", fontSize = fs.body, fontWeight = FontWeight.Medium)
                            if (selected != null) {
                                Text(presetSource(selected), fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                EnvelopeGuide(selected.instrument(0), Modifier.fillMaxWidth().height(138.dp))
                                Text("${if (selected.adsr1 and 0x80 != 0) "ADSR envelope" else "Gain envelope"} · Native pitch ${selected.instrument(0).nativePitch}",
                                    fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else Text("Presets include the sample, envelope, and tuning.", fontSize = fs.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                list(Modifier.weight(0.43f).fillMaxHeight()); details(Modifier.weight(0.57f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list(Modifier.weight(1f).fillMaxWidth()); details(Modifier.weight(1f).fillMaxWidth())
            }
        }
        SoundAuditionKeyboard(audition.pitch, audition.notePitch, audition.current, canSave, !busy,
            editedAvailable = selected != null, onPitch = { audition.changePitch(it); onPlay() }, onOriginal = audition::compare,
            editedLabel = "Preset", originalLabel = "Current", hintText = if (onKeyDown != null) "Hold a key to sustain · compare Preset / Current" else "Compare at the same pitch · track notes stay unchanged",
            onKeyDown = onKeyDown, onKeyUp = audition::releaseKey)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onPlay, enabled = !busy && (if (audition.current) canSave else selected != null), shape = SoundControlShape,
                modifier = Modifier.testTag("preset-play")) { Text(if (audition.busy) "Preparing…" else "Play", fontSize = fs.body) }
            OutlinedButton(audition::stop, enabled = audition.active, shape = SoundControlShape, modifier = Modifier.testTag("preset-stop")) { Text("Stop", fontSize = fs.body) }
            FilterChip(audition.live, { audition.setLiveEnabled(!audition.live) }, label = { Text("Live", fontSize = fs.body) }, shape = SoundControlShape)
            OutlinedButton(onDelete, enabled = !busy && selected != null, shape = SoundControlShape, modifier = Modifier.testTag("preset-delete")) { Text("Delete preset", fontSize = fs.body) }
            Button(onApply, enabled = !busy && readyApplication?.changed == true, shape = SoundControlShape, modifier = Modifier.testTag("preset-apply")) { Text("Use preset in sound", fontSize = fs.body) }
        }
        val fit = readyApplication?.let { if (!it.changed) "This sound already matches the preset." else when {
            it.preset.noiseSource != null -> "Noise preset · no sample memory needed"
            it.reused -> "Reuses a bank sample · no extra sample memory"
            else -> "Copies ${it.addedBytes} B · ${it.remainingBytes} B sample memory available"
        } }
        val feedback = listOfNotNull(fit, audition.message.takeIf { it.isNotEmpty() }).joinToString(" · ")
        Text(fitProblem ?: problem ?: feedback.ifEmpty { if (busy) "Loading library…" else "Saved presets are available across projects." },
            fontSize = fs.detail, color = if (problem != null || fitProblem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().testTag("preset-status"))
    }
}

private fun presetSource(preset: SoundPreset): String = preset.noiseSource?.let { "Noise · rate ${it and 31}" }
    ?: "${if (preset.loopByteOffset != null) "Looped sample" else "One-shot sample"} · ${preset.brrBase64!!.let { it.length / 4 * 3 - it.takeLast(2).count { char -> char == '=' } }} B"
