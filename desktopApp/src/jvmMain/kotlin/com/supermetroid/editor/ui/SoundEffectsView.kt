package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SoundEffectsList(state: SoundEffectEditorState, modifier: Modifier = Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(state.query, { state.query = it }, singleLine = true, label = { Text("Find an effect", fontSize = fs.detail) },
            textStyle = LocalTextStyle.current.copy(fontSize = fs.body), modifier = Modifier.fillMaxWidth().testTag("effect-search"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (library in 0..3) FilterChip(state.library == library, { state.library = library }, shape = SoundControlShape,
                label = { Text(if (library == 0) "All" else "$library", fontSize = fs.detail) })
        }
        Text("${state.visible.size} effects", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val scroll = rememberLazyListState()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.visible, key = { it.identity.key }) { effect ->
                    Surface(Modifier.fillMaxWidth().testTag("effect-${effect.identity.key}").clickable { state.select(effect.identity.key) }, shape = SoundControlShape,
                        color = if (state.selectedKey == effect.identity.key) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(8.dp)) {
                            Text(effect.identity.name, fontSize = fs.body, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${effect.identity.key} · ${effect.identity.voices} voices", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SoundEffectsToolbar(parser: RomParser?, editor: EditorState, state: SoundEffectEditorState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var banks by remember { mutableStateOf(false) }
    val effect = state.selected
    SoundToolbar(modifier.testTag("effect-toolbar")) {
        SoundToolbarButton("Play", { parser?.let { scope.launch { state.preview(it, editor) } } }, Modifier.testTag("effect-play"),
            enabled = parser != null && effect != null && effect.problem == null && !state.loading, primary = true, icon = Icons.Default.PlayArrow)
        SoundToolbarButton("Stop", state::stop, Modifier.testTag("effect-stop"), enabled = state.playing || state.loading, icon = Icons.Default.Stop)
        SoundToolbarToggle("Live", state.live, { state.setLiveEnabled(!state.live) }, Modifier.testTag("effect-live"))
        SoundToolbarButton("Undo", { state.undo(editor) }, Modifier.testTag("effect-undo"), enabled = state.undoDepth > 0)
        SoundToolbarButton("Redo", { state.redo(editor) }, Modifier.testTag("effect-redo"), enabled = state.redoDepth > 0)
        SoundToolbarButton("Reset effect", { state.reset(editor) }, Modifier.testTag("effect-reset"), enabled = effect != null)
        Box {
            SoundToolbarButton(if (state.bank == 0) "Samples · Common" else "Samples · Bank ${state.bank.toString(16).uppercase()}",
                { banks = true }, Modifier.testTag("effect-bank"))
            DropdownMenu(banks, { banks = false }) {
                DropdownMenuItem({ Text("Common samples") }, { state.selectBank(0); banks = false })
                for (set in MusicCatalog.banks(editor.project)) DropdownMenuItem({ Text(MusicCatalog.bankName(editor.project, set)) }, { state.selectBank(set); banks = false })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (seconds in listOf(1, 3, 6, 10)) SoundToolbarToggle("${seconds}s", state.duration == seconds, { state.duration = seconds },
                Modifier.testTag("effect-duration-$seconds"), minWidth = 48.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SoundEffectsView(parser: RomParser?, editor: EditorState, state: SoundEffectEditorState, modifier: Modifier = Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val editVersion = editor.musicEditVersion
    LaunchedEffect(parser, editor.project.projectId, editVersion, state.bank) { if (parser != null) { state.load(parser, editor); if (state.refreshRequested) state.preview(parser, editor) } }
    DisposableEffect(state) { onDispose { state.stop() } }
    val effect = state.selected
    var voiceIndex by remember(state.selectedKey) { mutableStateOf(0) }
    var stepIndex by remember(state.selectedKey, voiceIndex) { mutableStateOf(0) }
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(effect?.identity?.name ?: "Sound effects", fontSize = fs.heading, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        SoundEffectsToolbar(parser, editor, state)
        SynthModule(Modifier.fillMaxWidth()) {
            Text("Native effect audition", fontSize = fs.body, fontWeight = FontWeight.Medium)
            val wave = state.waveform
            val ink = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(90.dp).testTag("effect-waveform")) {
                drawLine(ink.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
                if (wave != null && wave.isNotEmpty()) for (bin in 0 until minOf(512, wave.size)) {
                    val bins = minOf(512, wave.size); var low = 0; var high = 0
                    for (at in bin * wave.size / bins until (bin + 1) * wave.size / bins) { low = minOf(low, wave[at].toInt()); high = maxOf(high, wave[at].toInt()) }
                    val x = (bin + 0.5f) * size.width / bins
                    drawLine(ink, Offset(x, size.height * (0.5f - high / 65536f)), Offset(x, size.height * (0.5f - low / 65536f)), 2f)
                }
            }
        }
        Text(effect?.problem ?: if (parser == null) "Load a ROM to browse effects" else state.message, fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 32.dp).testTag("effect-status"))
        if (effect != null && effect.problem == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (voice in effect.voices) FilterChip(voice.index == voiceIndex, { voiceIndex = voice.index }, shape = SoundControlShape,
                    label = { Text("Voice ${voice.index + 1}", fontSize = fs.body) })
            }
            val voice = effect.voices.getOrNull(voiceIndex)
            val steps = voice?.steps.orEmpty()
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SynthModule(Modifier.fillMaxWidth()) {
                        Text("Program steps · shared by effects using the same program", fontSize = fs.body, fontWeight = FontWeight.Medium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((index, step) in steps.withIndex()) FilterChip(stepIndex == index, { stepIndex = index }, shape = SoundControlShape,
                                modifier = Modifier.testTag("effect-step-$index"), label = { Text("${index + 1} · ${step.label}", fontSize = fs.detail) })
                        }
                    }
                    steps.getOrNull(stepIndex)?.let { step ->
                        SynthModule(Modifier.fillMaxWidth()) {
                            Text(step.label, fontSize = fs.body, fontWeight = FontWeight.Medium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (operand in step.operands) key(operand.address) {
                                    var value by remember(operand.value) { mutableStateOf(operand.value) }
                                    val readout = when (operand.field) {
                                        SoundEffects.Field.PITCH, SoundEffects.Field.TARGET -> if (value == 0xF6) "Tie" else NspcSequence.noteToName(value)
                                        SoundEffects.Field.LEVEL -> "${value * 100 / 255}%"
                                        SoundEffects.Field.PAN -> if (value == 10) "Center" else if (value < 10) "R ${(10 - value) * 10}%" else "L ${(value - 10) * 10}%"
                                        else -> value.toString()
                                    }
                                    SynthKnob(operand.field.label, value.coerceIn(operand.field.range), operand.field.range, readout,
                                        "effect-field-${operand.field.name}", { value = it }, { parser?.let { state.apply(it, editor, effect.identity.key, operand, value) } },
                                        enabled = !state.loading && operand.value in operand.field.range)
                                }
                            }
                            if (step.operands.isEmpty()) Text("Native control instruction", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}
