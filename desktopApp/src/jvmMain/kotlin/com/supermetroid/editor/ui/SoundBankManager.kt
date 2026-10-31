package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SoundBankManager(parser: RomParser, target: SampleBankPreview.Target,
    onApply: (MusicInstrumentBankEdit, String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf<SoundBankManagement.Overview?>(null) }
    var message by remember { mutableStateOf("Checking bank resources…") }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(parser, target) {
        try { overview = withContext(Dispatchers.Default) { SoundBankManagement.inspect(parser, target) }; message = "Resources are shared across this bank's tracks and effects." }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message = error.message ?: "Cannot inspect this bank" }
        finally { busy = false }
    }
    fun action(label: String, plan: () -> MusicInstrumentBankEdit) {
        scope.launch {
            busy = true
            try { val bank = withContext(Dispatchers.Default) { plan() }; onApply(bank, label); onClose() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Cannot update this bank" }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SoundBankManagerContent(target.bankName, target.note.instrument, overview, busy, message,
            onCreate = { action("Create sound") { SoundBankManagement.create(parser, target).second } },
            onCopy = { from, into -> action("Copy sound #$from to #$into") { SoundBankManagement.duplicate(parser, target, from, into) } },
            onRemove = { srcn -> action("Remove unused project sample") { SoundBankManagement.removeSample(parser, target, srcn) } }, onClose = onClose)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SoundBankManagerContent(bankName: String, initialSource: Int, overview: SoundBankManagement.Overview?, busy: Boolean, message: String,
    onCreate: () -> Unit, onCopy: (Int, Int) -> Unit, onRemove: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    var source by remember { mutableStateOf(initialSource) }
    var destination by remember { mutableStateOf<Int?>(null) }
    var choosingSource by remember { mutableStateOf(false) }
    var choosingDestination by remember { mutableStateOf(false) }
    Surface(modifier.widthIn(max = 1040.dp).fillMaxWidth().heightIn(max = 760.dp), shape = SoundControlShape,
            color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${bankName} · Sound bank", fontSize = fs.heading, modifier = Modifier.weight(1f))
                    OutlinedButton(onClose, shape = SoundControlShape) { Text("Close", fontSize = fs.body) }
                }
                overview?.let { info ->
                    SynthModule(Modifier.fillMaxWidth()) {
                        Text("${info.freeBytes} B free · largest gap ${info.largestGap} B", fontSize = fs.body)
                        Text("${info.samples.sumOf { it.bytes }} B in project samples · ${info.emptySounds.size} empty sound slots", fontSize = fs.detail)
                        Text(info.creationProblem ?: "Native banks have 42 sound slots. Existing nonzero sounds stay assigned until explicitly replaced.", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SynthModule(Modifier.fillMaxWidth()) {
                        Text("Create / copy a sound", fontSize = fs.body)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onCreate, enabled = !busy && info.emptySounds.isNotEmpty(), shape = SoundControlShape,
                                modifier = Modifier.testTag("bank-new-sound")) { Text(info.emptySounds.firstOrNull()?.let { "New sound · #$it" } ?: "No empty sound slots", fontSize = fs.body) }
                            Box {
                                OutlinedButton({ choosingSource = true }, enabled = !busy, shape = SoundControlShape) { Text("Copy from #$source", fontSize = fs.body) }
                                DropdownMenu(choosingSource, { choosingSource = false }) {
                                    for (entry in info.instruments.filter(InstrumentDescriptions::playable)) DropdownMenuItem({ Text(InstrumentDescriptions.title(entry, emptyMap())) },
                                        { source = entry.index; choosingSource = false })
                                }
                            }
                            Box {
                                OutlinedButton({ choosingDestination = true }, enabled = !busy, shape = SoundControlShape) { Text(destination?.let { "Replace #$it" } ?: "Choose destination", fontSize = fs.body) }
                                DropdownMenu(choosingDestination, { choosingDestination = false }) {
                                    for (entry in info.instruments.filter { it.index != source }) DropdownMenuItem({ Text("#${entry.index} · ${if (entry.index in info.emptySounds) "Empty slot" else InstrumentDescriptions.title(entry, emptyMap())}") },
                                        { destination = entry.index; choosingDestination = false })
                                }
                            }
                            Button({ destination?.let { onCopy(source, it) } },
                                enabled = !busy && destination != null && source != destination, shape = SoundControlShape, modifier = Modifier.testTag("bank-copy-sound")) { Text("Copy into slot", fontSize = fs.body) }
                        }
                        Text("New creates a sine in a verified empty slot. Copy replaces the chosen slot's sound settings. Each action has one Undo step.", fontSize = fs.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val scroll = rememberScrollState()
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (info.samples.isEmpty()) Text("No project samples in this bank yet. Use New sample from the sound editor to add one.", fontSize = fs.body)
                            for (sample in info.samples) SynthModule(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${sample.name} · Sample #${sample.srcn}", fontSize = fs.body)
                                        Text("${sample.bytes} B · ${if (sample.users.isEmpty()) "Unused by bank sounds" else "Used by sounds ${sample.users.joinToString { "#$it" }}"}", fontSize = fs.detail)
                                    }
                                    OutlinedButton({ onRemove(sample.srcn) },
                                        enabled = !busy && sample.users.isEmpty(), shape = SoundControlShape, modifier = Modifier.testTag("bank-remove-sample-${sample.srcn}")) { Text("Remove", fontSize = fs.detail) }
                                }
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
                Text(message, fontSize = fs.detail, modifier = Modifier.fillMaxWidth())
            }
        }
}
