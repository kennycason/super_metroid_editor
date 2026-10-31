package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
internal fun SampleWorkbench(state: SampleWorkbenchState, parser: RomParser?, bankTarget: SampleBankPreview.Target? = null,
    onUseSample: ((MusicSampleBanks.Application) -> Unit)? = null, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val fs = LocalEditorTheme.current.fontSize.value
    var fileBusy by remember { mutableStateOf(false) }
    var bank by remember(parser, bankTarget) { mutableStateOf<SampleBankPreview?>(null) }
    var bankProblem by remember(parser, bankTarget) { mutableStateOf<String?>(null) }
    var application by remember(parser, bankTarget) { mutableStateOf<MusicSampleBanks.Application?>(null) }
    var useProblem by remember(parser, bankTarget) { mutableStateOf<String?>(null) }
    DisposableEffect(state) { onDispose { state.stop() } }
    LaunchedEffect(state.draft) { delay(140); state.prepare() }
    LaunchedEffect(parser, bankTarget) {
        state.stop()
        if (parser != null && bankTarget != null) {
            try { bank = withContext(Dispatchers.Default) { SampleBankPreview.load(parser, bankTarget) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { bankProblem = error.message ?: "Cannot check this bank" }
        }
    }
    LaunchedEffect(parser, bankTarget, state.encoded, state.draft.name) {
        application = null; useProblem = null
        val sample = state.encoded
        val name = state.draft.name
        if (parser != null && bankTarget != null && sample != null && onUseSample != null) {
            delay(80)
            try { application = withContext(Dispatchers.Default) { MusicSampleBanks.plan(parser, bankTarget, sample, name) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { useProblem = error.message ?: "Cannot use this sample in this bank" }
        }
    }
    fun fileAction(title: String, extension: String, save: Boolean, action: suspend (File) -> Unit) {
        state.stop()
        val file = chooseSampleAssetFile(title, extension, save, state.draft.name) ?: return
        scope.launch {
            fileBusy = true
            state.stop()
            try { action(file) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state.message = error.message ?: "File operation failed" }
            finally { fileBusy = false }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 1120.dp).fillMaxWidth().heightIn(max = 800.dp),
            shape = SoundControlShape, color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("New sample", fontSize = fs.heading, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    OutlinedButton(shape = SoundControlShape, onClick = onClose) { Text("Close", fontSize = fs.body) }
                }
                SampleWorkbenchContent(state, parser != null, !fileBusy,
                    onPlay = { parser?.let { scope.launch { state.preview(SpcData.buildInitialSpcRam(it)) } } },
                    onImport = { fileAction("Import WAV (up to 1.024 seconds)", "wav", false) { file ->
                        val draft = withContext(Dispatchers.IO) { SampleAuthoring.readWav(file) }
                        state.update(draft)
                    } },
                    onOpen = { fileAction("Load sample (.smedit-sample)", "smedit-sample", false) { file ->
                        val draft = withContext(Dispatchers.IO) { SampleAuthoring.open(file) }
                        state.update(draft)
                    } },
                    onSave = { fileAction("Save sample (.smedit-sample)", "smedit-sample", true) { file ->
                        val draft = state.draft
                        withContext(Dispatchers.IO) { SampleAuthoring.save(draft, file) }
                        state.message = "Saved sample · ${file.name}"
                    } },
                    onExport = { fileAction("Export raw BRR data", "brr", true) { file ->
                        val encoded = state.encoded ?: return@fileAction
                        withContext(Dispatchers.IO) { SampleAuthoring.exportBrr(encoded, file) }
                        state.message = "Saved raw BRR · loop offset ${encoded.loopByteOffset?.let { "$it bytes" } ?: "none"}"
                    } },
                    bankTarget = bankTarget?.let { "${it.bankName} · ${it.soundName} · ${it.note.name}" }, bank = bank, bankProblem = bankProblem,
                    onPlayInBank = { bank?.let { scope.launch { state.previewInBank(it) } } },
                    application = application, useProblem = useProblem,
                    onUseSample = onUseSample?.let { use -> {
                        application?.let { planned ->
                            try { use(planned); state.stop(); onClose() }
                            catch (error: Exception) { state.message = error.message ?: "Cannot apply this sample" }
                        }
                    } },
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SampleWorkbenchContent(
    state: SampleWorkbenchState,
    canPreview: Boolean,
    enabled: Boolean = true,
    onPlay: () -> Unit,
    onImport: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    bankTarget: String? = null,
    bank: SampleBankPreview? = null,
    bankProblem: String? = null,
    onPlayInBank: () -> Unit = {},
    application: MusicSampleBanks.Application? = null,
    useProblem: String? = null,
    onUseSample: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    val draft = state.draft
    val encoded = state.encoded
    val shape = draft.generator?.let { SampleAuthoring.Shape.valueOf(it) }
    val period = draft.periodFrames
    var invalidFields by remember { mutableStateOf(emptySet<String>()) }
    var details by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val bankFit = remember(bank, encoded) { if (bank != null && encoded != null) runCatching { bank.allocation(encoded) } else null }
    fun fieldValidity(label: String, valid: Boolean) { invalidFields = if (valid) invalidFields - label else invalidFields + label }
    val ready = enabled && encoded != null && invalidFields.isEmpty()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        BoxWithConstraints(modifier.fillMaxWidth().testTag("sample-workbench")) {
            val wide = maxWidth >= 860.dp
            val sourceControls: @Composable () -> Unit = {
                SynthModule(Modifier.fillMaxWidth().testTag("sample-source-module")) {
                    Text("Source", fontSize = fs.body, fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(shape = SoundControlShape, enabled = enabled, onClick = onImport,
                            modifier = Modifier.testTag("sample-import-wav")) { Text("Import WAV", fontSize = fs.detail) }
                        OutlinedButton(shape = SoundControlShape, enabled = enabled, onClick = onOpen,
                            modifier = Modifier.testTag("sample-load-recording")) { Text("Load sample", fontSize = fs.detail) }
                    }
                    OutlinedTextField(draft.name, { state.update(draft.copy(name = it)) }, enabled = enabled, singleLine = true,
                        label = { Text("Name", fontSize = fs.detail) }, textStyle = LocalTextStyle.current.copy(fontSize = fs.body),
                        modifier = Modifier.fillMaxWidth().testTag("sample-draft-name"))
                    Text("Waveform", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (value in SampleAuthoring.Shape.entries) FilterChip(shape == value, enabled = enabled,
                            shape = SoundControlShape,
                            onClick = { state.update(SampleAuthoring.generate(value, period).copy(gainPercent = draft.gainPercent)) },
                            modifier = Modifier.testTag("sample-generate-${value.name}"),
                            label = { Text(value.name.lowercase().replaceFirstChar(Char::uppercase), fontSize = fs.detail) })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        SynthKnob("Period", period / 16, 2..32, "$period frames", "sample-period", { value ->
                            if (value * 16 != period && shape != null) state.update(SampleAuthoring.generate(shape, value * 16)
                                .copy(name = draft.name, gainPercent = draft.gainPercent))
                        }, {}, enabled && shape != null && shape != SampleAuthoring.Shape.NOISE)
                        SynthKnob("Level", draft.gainPercent, 0..100, "${draft.gainPercent}%", "sample-level",
                            { state.update(draft.copy(gainPercent = it)) }, {}, enabled)
                    }
                    Text(when (shape) {
                        null -> "Imported recording · ${draft.pcm.size} frames"
                        SampleAuthoring.Shape.NOISE -> "Noise · 4096 frames"
                        else -> "${SampleAuthoring.RATE / period} Hz · 32 kHz recording"
                    }, fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Drag up/down · Shift: fine · click, then scroll or use arrows", fontSize = fs.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SampleLoopJoin(draft, encoded, enabled && invalidFields.isEmpty() && SampleLoopEditing.validBounds(draft), state::update)
                }
            }
            val sampleControls: @Composable () -> Unit = {
                SynthModule(Modifier.fillMaxWidth().testTag("sample-preview-module")) {
                    Text("Sample", fontSize = fs.body, fontWeight = FontWeight.Medium)
                    SampleLoopEditor(draft, enabled && invalidFields.isEmpty() && SampleLoopEditing.validBounds(draft), encoded, state::update)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(encoded?.let { "${"%.1f".format(it.pcm.size / 32.0)} ms · ${if (it.loopFrame == null) "one-shot" else "looped"} · ${it.brr.size} B" }
                            ?: "Encoding sample…", fontSize = fs.detail, modifier = Modifier.weight(1f))
                        TextButton(shape = SoundControlShape, onClick = { details = !details }) {
                            Text(if (details) "Details ▴" else "Details ▾", fontSize = fs.detail)
                        }
                    }
                    if (details) Text(encoded?.let { "32 kHz · ${it.pcm.size} frames · loop offset ${it.loopByteOffset?.let { at -> "$at bytes" } ?: "none"}" }
                        ?: "Native BRR · 16 frames per block", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(state.live, { state.setLiveEnabled(!state.live) }, shape = SoundControlShape,
                            label = { Text("Live", fontSize = fs.detail) }, modifier = Modifier.testTag("sample-live"))
                        if (bankTarget != null) Button(shape = SoundControlShape, onClick = onPlayInBank,
                            enabled = ready && canPreview && bank != null && bankFit?.isSuccess == true && !state.previewing,
                            modifier = Modifier.testTag("sample-play-in-bank")) { Text("Play as sound", fontSize = fs.body) }
                        OutlinedButton(shape = SoundControlShape, onClick = onPlay, enabled = ready && canPreview && !state.previewing,
                            modifier = Modifier.testTag("sample-play-brr")) { Text("Play sample", fontSize = fs.body) }
                        OutlinedButton(shape = SoundControlShape, onClick = state::stop, enabled = state.auditionActive,
                            modifier = Modifier.testTag("sample-stop")) { Text("Stop", fontSize = fs.body) }
                    }
                    if (!canPreview) Text("Load a ROM for native preview", fontSize = fs.detail)
                    if (bankTarget != null) {
                        Text(bankTarget, fontSize = fs.detail, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sample-bank-target"))
                        Text(if (onUseSample == null) "Tryout only · uses this sound's envelope + tuning" else "Keeps envelope + tuning · shared bank sound", fontSize = fs.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when {
                            bankProblem != null -> bankProblem
                            useProblem != null -> useProblem
                            bank == null -> "Checking bank space…"
                            encoded == null -> "Encoding sample…"
                            application != null -> "${application.bytes} B sample · ${application.remainingBytes} B left\nLargest gap · ${application.largestGapBytes} B"
                            onUseSample != null -> "Checking sample placement…"
                            bankFit?.isSuccess == true -> "${encoded.brr.size} B sample fits · ${bank.layout.totalBytes} B available"
                            else -> bankFit?.exceptionOrNull()?.message ?: "Cannot place this sample"
                        }, fontSize = fs.detail, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (bankProblem != null || useProblem != null || bankFit?.isFailure == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().height(44.dp).testTag("sample-bank-fit"))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Trim / loop", fontSize = fs.body, modifier = Modifier.weight(1f))
                        Checkbox(draft.loopStart != null, { state.update(draft.copy(loopStart = if (it) draft.trimStart else null)) }, enabled = enabled,
                            modifier = Modifier.testTag("sample-loop-enabled"))
                        Text("Loop", fontSize = fs.detail)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SampleFrameField("Start", draft.trimStart, enabled, draft.pcm, { fieldValidity("Start", it) }) { state.update(draft.copy(trimStart = it)) }
                        SampleFrameField("End", draft.trimEnd, enabled, draft.pcm, { fieldValidity("End", it) }) { state.update(draft.copy(trimEnd = it)) }
                        SampleFrameField("Loop start", draft.loopStart ?: draft.trimStart, enabled && draft.loopStart != null, draft.pcm,
                            { fieldValidity("Loop start", it) }) { state.update(draft.copy(loopStart = it)) }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 14.dp)) {
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(0.44f)) { sourceControls() }
                            Column(Modifier.weight(0.56f)) { sampleControls() }
                        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            sourceControls()
                            sampleControls()
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                Box(Modifier.fillMaxWidth().height(44.dp).testTag("sample-workbench-status")) {
                    Text(when { invalidFields.isNotEmpty() -> "Use whole-frame positions on 16-frame boundaries"; state.preparing -> "Encoding BRR…";
                        state.previewing -> "Rendering native preview…"; state.problem != null -> state.problem!!;
                        state.message.isNotEmpty() -> state.message; state.live -> "Live · repeats and follows edits until Stop"; else -> "Ready to preview" },
                        fontSize = fs.detail, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (state.problem != null || invalidFields.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onUseSample != null) Button(shape = SoundControlShape, onClick = onUseSample,
                        enabled = ready && application?.changed == true && useProblem == null,
                        modifier = Modifier.testTag("sample-use-in-sound")) { Text(if (application?.changed == false) "Already in sound" else "Use sample in sound", fontSize = fs.body) }
                    if (onUseSample == null) Button(shape = SoundControlShape, onClick = onSave, enabled = ready,
                        modifier = Modifier.testTag("sample-save-draft")) { Text("Save sample", fontSize = fs.body) }
                    else OutlinedButton(shape = SoundControlShape, onClick = onSave, enabled = ready,
                        modifier = Modifier.testTag("sample-save-draft")) { Text("Save sample", fontSize = fs.body) }
                    OutlinedButton(shape = SoundControlShape, onClick = onExport, enabled = ready,
                        modifier = Modifier.testTag("sample-export-brr")) { Text("Export BRR", fontSize = fs.body) }
                }
                Text(if (onUseSample == null) "Save as a sample file · not added to a music bank" else
                    "Use updates this shared sound · Save sample creates a separate file", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SampleFrameField(label: String, value: Int, enabled: Boolean, source: List<Int>, onValidity: (Boolean) -> Unit, onChange: (Int) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    var text by remember(value, source) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it >= 0 && it % 16 == 0 } == true
    val currentValidity by rememberUpdatedState(onValidity)
    LaunchedEffect(text, enabled) { currentValidity(!enabled || valid) }
    OutlinedTextField(text, { next ->
        text = next
        val frame = next.toIntOrNull()?.takeIf { it >= 0 && it % 16 == 0 }
        currentValidity(!enabled || frame != null)
        frame?.let(onChange)
    },
        enabled = enabled, isError = enabled && !valid, singleLine = true,
        label = { Text(label, fontSize = fs.detail) }, textStyle = LocalTextStyle.current.copy(fontSize = fs.body),
        modifier = Modifier.width(142.dp).testTag("sample-frame-$label"))
}

private fun chooseSampleAssetFile(title: String, extension: String, save: Boolean, suggestedName: String): File? {
    val dialog = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    try {
        val stem = suggestedName.map { if (it.isLetterOrDigit() || it in " _.-") it else '_' }.joinToString("").trim().take(80).ifBlank { "sample" }
        if (save) dialog.file = "$stem.$extension" else dialog.setFilenameFilter { _, name -> name.endsWith(".$extension", true) }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val directory = dialog.directory ?: return null
        return File(directory, if (save && !name.endsWith(".$extension", true)) "$name.$extension" else name)
    } finally { dialog.dispose() }
}
