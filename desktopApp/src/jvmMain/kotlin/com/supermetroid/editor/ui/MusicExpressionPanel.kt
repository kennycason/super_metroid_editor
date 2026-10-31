package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.NspcSequence
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MusicExpressionPanel(song: NspcSequence.Song, channel: Int, onEdit: (NspcSequence.Song, String) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val commands = song.channels[channel].commands
    var selected by remember(channel) { mutableStateOf<Int?>(null) }
    var kind by remember(channel) { mutableStateOf(MusicExpression.kinds.first()) }
    var tick by remember(channel) { mutableStateOf(0) }
    var tickText by remember(channel, tick) { mutableStateOf(tick.toString()) }
    val validTick = tickText.toIntOrNull()?.let { it in 0..65535 } == true
    var menu by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val existing = selected?.let { commands.getOrNull(it) }
    var params by remember(channel, existing, kind) { mutableStateOf(existing?.params?.copyOf() ?: kind.parameters.map { it.initial and 255 }.toIntArray()) }
    val activeKind = existing?.let { e -> MusicExpression.kinds.firstOrNull { it.opcode == e.command } } ?: kind
    val editable = existing == null || MusicExpression.kinds.any { it.opcode == existing.command }
    val extent = maxOf(192, song.totalTicks, tick, commands.maxOfOrNull { it.tick } ?: 0)
    val ink = MaterialTheme.colorScheme.primary
    fun save() {
        try {
            val event = NspcSequence.ControlCommand(tick, activeKind.opcode, params.copyOf())
            if (event == existing) return
            val updated = MusicExpression.edit(song, channel, selected, event)
            onEdit(updated, "Edit ${activeKind.name}"); problem = null
            selected = selected ?: updated.channels[channel].commands.lastIndex
        } catch (error: Exception) { problem = error.message ?: "Cannot update expression" }
    }
    SynthModule(Modifier.fillMaxWidth().testTag("music-expression")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Expression · Channel ${channel + 1}", fontSize = fs.body)
            Box {
                OutlinedButton({ menu = true }, shape = SoundControlShape) { Text(if (editable) activeKind.name else "Native ${existing!!.command.toString(16).uppercase()}", fontSize = fs.detail) }
                DropdownMenu(menu, { menu = false }) { for (value in MusicExpression.kinds) DropdownMenuItem({ Text(value.name) }, {
                    selected = null; kind = value; params = value.parameters.map { it.initial and 255 }.toIntArray(); menu = false
                }) }
            }
            OutlinedButton({ selected = null; params = kind.parameters.map { it.initial and 255 }.toIntArray() }, shape = SoundControlShape) { Text("New event", fontSize = fs.detail) }
            Button(::save, enabled = editable && validTick, shape = SoundControlShape, modifier = Modifier.testTag("expression-apply")) { Text(if (existing == null) "Add event" else "Apply event", fontSize = fs.detail) }
            OutlinedButton({ selected?.let { onEdit(MusicExpression.edit(song, channel, it, null), "Delete expression"); selected = null } },
                enabled = existing != null && editable, shape = SoundControlShape) { Text("Delete event", fontSize = fs.detail) }
        }
        Canvas(Modifier.fillMaxWidth().height(42.dp).testTag("expression-timeline").pointerInput(extent) {
            detectTapGestures { at -> tick = ((at.x / size.width * extent / 6).roundToInt() * 6).coerceIn(0, 65535) }
        }) {
            drawLine(ink.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
            for (event in commands) drawCircle(ink.copy(alpha = 0.65f), 5f, Offset(event.tick.toFloat() / extent * size.width, size.height / 2))
            val x = tick.toFloat() / extent * size.width
            drawLine(ink, Offset(x, 0f), Offset(x, size.height), 2f)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(tickText, { tickText = it; it.toIntOrNull()?.takeIf { value -> value in 0..65535 }?.let { value -> tick = value } },
                label = { Text("Tick", fontSize = fs.detail) }, isError = !validTick, singleLine = true, modifier = Modifier.width(100.dp).testTag("expression-tick"))
            if (editable && params.size == activeKind.parameters.size) for ((index, parameter) in activeKind.parameters.withIndex()) {
                SynthKnob(parameter.label, parameter.value(params[index]).coerceIn(parameter.range), parameter.range,
                    parameter.value(params[index]).toString(), "expression-value-$index", { value -> params = params.copyOf().also { it[index] = parameter.store(it[index], value) } }, {}, enabled = true)
            }
            if (!editable) Text("Native source command 0x${existing!!.command.toString(16)} · retained unchanged", fontSize = fs.detail)
        }
        val scroll = rememberScrollState()
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((index, event) in commands.withIndex()) FilterChip(selected == index, {
                selected = index; tick = event.tick
                MusicExpression.kinds.firstOrNull { it.opcode == event.command }?.let { kind = it }
                params = event.params.copyOf()
            }, shape = SoundControlShape, modifier = Modifier.testTag("expression-event-$index"), label = {
                Text("${event.tick} · ${MusicExpression.kinds.firstOrNull { it.opcode == event.command }?.name ?: "Native ${event.command.toString(16)}"}", fontSize = fs.detail)
            })
        }
        Text(problem ?: "Values follow the native engine · pan runs right to left · echo uses its reserved buffer", fontSize = fs.detail,
            color = if (problem == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
}
