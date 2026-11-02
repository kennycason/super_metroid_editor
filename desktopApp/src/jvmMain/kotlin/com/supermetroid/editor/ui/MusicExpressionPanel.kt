package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.NspcSequence
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MusicExpressionPanel(song: NspcSequence.Song, channel: Int, onEdit: (NspcSequence.Song, String) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val commands = song.channels[channel].commands
    var selected by remember(channel) { mutableStateOf<Int?>(null) }
    var original by remember(channel) { mutableStateOf<NspcSequence.ControlCommand?>(null) }
    var kind by remember(channel) { mutableStateOf(MusicExpression.kinds.first()) }
    var tickText by remember(channel) { mutableStateOf("0") }
    var params by remember(channel) { mutableStateOf(kind.parameters.map { it.initial and 255 }.toIntArray()) }
    var query by remember(channel) { mutableStateOf("") }
    var menu by remember(channel) { mutableStateOf(false) }
    var problem by remember(channel) { mutableStateOf<String?>(null) }
    val existing = selected?.let { commands.getOrNull(it) }
    val fresh = selected == null || original != null && original == existing
    val editable = selected == null || original?.let { event -> MusicExpression.kinds.any { it.opcode == event.command } } == true
    val tick = tickText.toIntOrNull()?.takeIf { it in 0..65535 }
    val extent = maxOf(192, song.totalTicks, tick ?: 0, commands.maxOfOrNull { it.tick } ?: 0)
    val events = commands.mapIndexed { index, event -> IndexedValue(index, event.copy(params = event.params.copyOf())) }
        .sortedWith(compareBy<IndexedValue<NspcSequence.ControlCommand>> { it.value.tick }.thenBy { it.index })
    val selectEvent: (Int) -> Unit = select@ { index ->
        val event = commands.getOrNull(index) ?: return@select
        selected = index; original = event.copy(params = event.params.copyOf()); tickText = event.tick.toString()
        MusicExpression.kinds.firstOrNull { it.opcode == event.command }?.let { kind = it }
        params = event.params.copyOf(); problem = null
    }
    val newEvent: (Int) -> Unit = { at ->
        selected = null; original = null; tickText = at.toString()
        params = kind.parameters.map { it.initial and 255 }.toIntArray(); problem = null
    }
    val save: () -> Unit = save@ {
        try {
            require(fresh) { "Events changed. Select the event again before saving." }
            val position = requireNotNull(tick) { "Event position must be 0–65535 ticks" }
            val event = NspcSequence.ControlCommand(position, kind.opcode, params.copyOf(), existing?.sourceAddress ?: -1)
            if (event == existing) return@save
            val index = selected ?: commands.size
            val updated = MusicExpression.edit(song, channel, selected, event)
            onEdit(updated, if (selected == null) "Add ${kind.name}" else "Edit ${kind.name}")
            selected = index; original = event.copy(params = event.params.copyOf()); query = ""; problem = null
        } catch (error: Exception) { problem = error.message ?: "Cannot update expression" }
    }
    SynthModule(Modifier.fillMaxWidth().testTag("music-expression")) {
        SoundToolbar {
            Text("Expression · Channel ${channel + 1}", fontSize = fs.body, fontWeight = FontWeight.Medium,
                modifier = Modifier.height(SoundToolbarHeight).wrapContentHeight(Alignment.CenterVertically))
            SoundToolbarButton("New event", { newEvent(tick ?: 0) }, Modifier.testTag("expression-new"))
            SoundToolbarButton(if (selected == null) "Add event" else "Save changes", save,
                Modifier.testTag("expression-apply"), enabled = editable && fresh && tick != null, primary = true)
            SoundToolbarButton("Delete event", {
                try {
                    require(fresh) { "Events changed. Select the event again before deleting." }
                    selected?.let { onEdit(MusicExpression.edit(song, channel, it, null), "Delete expression"); newEvent(tick ?: 0) }
                } catch (error: Exception) { problem = error.message ?: "Cannot delete expression" }
            }, Modifier.testTag("expression-delete"), enabled = selected != null && editable && fresh)
        }
        Text("Click a marker or list entry to edit; empty timeline space starts a new event.", fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExpressionTimeline(events, selected.takeIf { fresh }, tick ?: original?.tick ?: 0, extent, selectEvent, newEvent)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            val browser: @Composable (Modifier) -> Unit = { modifier ->
                ExpressionEventBrowser(events, selected.takeIf { fresh }, query, { query = it }, selectEvent,
                    modifier.height(if (wide) 236.dp else 160.dp))
            }
            val inspector: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (selected == null) "New ${kind.name.lowercase()} event" else "${MusicExpression.name(original?.command ?: kind.opcode)} · ${if (editable) "editing" else "source / read only"}",
                        fontSize = fs.body, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("expression-selection"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(tickText, { tickText = it }, label = { Text("At tick", fontSize = fs.detail) },
                            textStyle = LocalTextStyle.current.copy(fontSize = fs.body), isError = tick == null,
                            readOnly = !editable, singleLine = true, modifier = Modifier.width(124.dp).testTag("expression-tick"))
                        Box {
                            OutlinedButton({ menu = true }, enabled = editable && fresh, shape = SoundControlShape,
                                modifier = Modifier.height(40.dp).testTag("expression-type")) {
                                Text("Type · ${if (editable) kind.name else MusicExpression.name(original?.command ?: kind.opcode)}", fontSize = fs.detail)
                                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                            }
                            DropdownMenu(menu, { menu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                                for (value in MusicExpression.kinds) DropdownMenuItem({ Text(value.name, fontSize = fs.body) }, {
                                    if (kind != value) { kind = value; params = value.parameters.map { it.initial and 255 }.toIntArray() }
                                    menu = false
                                }, modifier = Modifier.testTag("expression-kind-${value.opcode.toString(16)}"))
                            }
                        }
                    }
                    Text(MusicExpression.description(if (editable) kind.opcode else original?.command ?: kind.opcode),
                        fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("expression-description"))
                    if (editable && params.size == kind.parameters.size) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((index, parameter) in kind.parameters.withIndex()) key(kind.opcode, index) {
                            SynthKnob(parameter.label, parameter.value(params[index]).coerceIn(parameter.range), parameter.range,
                                MusicExpression.readout(kind, parameter, params[index]), "expression-value-$index",
                                { value -> params = params.copyOf().also { it[index] = parameter.store(it[index], value) } }, {}, enabled = fresh)
                        }
                    }
                    if (!editable) original?.let { Text(MusicExpression.summary(it), fontSize = fs.body) }
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                browser(Modifier.weight(1f)); inspector(Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                inspector(Modifier.fillMaxWidth()); browser(Modifier.fillMaxWidth())
            }
        }
        val status = problem ?: if (!fresh) "Events changed. Select an event again before saving." else null
        status?.let { Text(it, fontSize = fs.detail, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("expression-problem")) }
    }
}

@Composable
private fun ExpressionEventBrowser(events: List<IndexedValue<NspcSequence.ControlCommand>>, selected: Int?, query: String,
    onQuery: (String) -> Unit, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val filtered = events.filter { event ->
        val text = "${event.value.tick} ${MusicExpression.name(event.value.command)} ${MusicExpression.summary(event.value)}"
        terms.all { text.contains(it, true) }
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(query) { scroll.scrollToItem(0) }
    LaunchedEffect(selected, filtered.map { it.index }) {
        val position = filtered.indexOfFirst { it.index == selected }
        if (position >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == position }) scroll.scrollToItem(position)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Existing events · ${filtered.size}${if (query.isNotBlank()) " / ${events.size}" else ""}", fontSize = fs.body, fontWeight = FontWeight.Medium)
        if (events.size > 8 || query.isNotEmpty()) OutlinedTextField(query, onQuery, singleLine = true,
            label = { Text("Find type, value or tick", fontSize = fs.detail) }, textStyle = LocalTextStyle.current.copy(fontSize = fs.body),
            modifier = Modifier.fillMaxWidth().testTag("expression-search"))
        if (filtered.isEmpty()) Text(if (events.isEmpty()) "No events on this channel yet." else "No matching events.", fontSize = fs.detail)
        Box(Modifier.fillMaxWidth().weight(1f).testTag("expression-event-list")) {
            LazyColumn(state = scroll, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                items(filtered, key = { it.index }) { (index, event) ->
                    Surface(Modifier.fillMaxWidth().testTag("expression-event-$index").selectable(selected == index, onClick = { onSelect(index) }),
                        shape = SoundControlShape, color = if (selected == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text("Tick ${event.tick} · ${MusicExpression.name(event.command)}", fontSize = fs.detail, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(MusicExpression.summary(event), fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun ExpressionTimeline(events: List<IndexedValue<NspcSequence.ControlCommand>>, selected: Int?, tick: Int, extent: Int,
    onSelect: (Int) -> Unit, onNew: (Int) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val ink = MaterialTheme.colorScheme.primary
    val currentSelect by rememberUpdatedState(onSelect)
    val currentNew by rememberUpdatedState(onNew)
    Column {
        Row {
            Column(Modifier.width(52.dp)) {
                for (label in listOf("Volume", "Pan", "Pitch", "Other")) Text(label, fontSize = fs.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(18.dp))
            }
            Canvas(Modifier.weight(1f).height(72.dp).testTag("expression-timeline")
                .semantics { contentDescription = "Event timeline: click a marker to select an event; click empty space to start a new event." }
                .pointerInput(extent, events, selected) {
                    detectTapGestures { at ->
                        val distances = events.map { event ->
                            val x = 8.dp.toPx() + event.value.tick.toFloat() / extent * (size.width - 16.dp.toPx())
                            val y = (MusicExpression.lane(event.value.command) + 0.5f) * size.height / 4
                            event.index to ((at.x - x) * (at.x - x) + (at.y - y) * (at.y - y))
                        }
                        val closest = distances.minOfOrNull { it.second }
                        if (closest != null && closest <= 12.dp.toPx() * 12.dp.toPx()) {
                            val samePosition = distances.filter { abs(it.second - closest) < 0.1f }.map { it.first }
                            val next = (samePosition.indexOf(selected) + 1) % samePosition.size
                            currentSelect(samePosition[next])
                        } else currentNew((((at.x - 8.dp.toPx()) / (size.width - 16.dp.toPx()) * extent / 6).roundToInt() * 6).coerceIn(0, 65535))
                    }
                }) {
                for (lane in 0..3) {
                    val y = (lane + 0.5f) * size.height / 4
                    drawLine(ink.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y))
                }
                for ((index, event) in events) {
                    val center = Offset(8.dp.toPx() + event.tick.toFloat() / extent * (size.width - 16.dp.toPx()), (MusicExpression.lane(event.command) + 0.5f) * size.height / 4)
                    drawCircle(ink.copy(alpha = if (index == selected) 1f else 0.55f), if (index == selected) 5.dp.toPx() else 3.dp.toPx(), center)
                    if (index == selected) drawCircle(ink, 7.dp.toPx(), center, style = Stroke(1.dp.toPx()))
                }
                val x = 8.dp.toPx() + tick.toFloat() / extent * (size.width - 16.dp.toPx())
                drawLine(ink, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 52.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$extent ticks", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
