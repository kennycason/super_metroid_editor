package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** All pointer, keyboard and nearby-join operations use the same native 16-frame boundaries. */
internal object SampleLoopEditing {
    enum class Marker(val label: String) { START("Start"), LOOP("Loop start"), END("End") }
    fun validBounds(draft: SampleDraft) = draft.trimStart >= 0 && draft.trimStart < draft.trimEnd && draft.trimEnd <= draft.pcm.size &&
        draft.trimStart % 16 == 0 && draft.trimEnd % 16 == 0 &&
        (draft.loopStart == null || draft.loopStart in draft.trimStart until draft.trimEnd && draft.loopStart % 16 == 0)
    fun position(draft: SampleDraft, marker: Marker) = when (marker) {
        Marker.START -> draft.trimStart; Marker.LOOP -> draft.loopStart ?: draft.trimStart; Marker.END -> draft.trimEnd
    }
    fun move(draft: SampleDraft, marker: Marker, frame: Int): SampleDraft {
        val snapped = ((frame.coerceIn(0, draft.pcm.size) / 16.0).roundToInt() * 16)
        return when (marker) {
            Marker.START -> {
                val start = snapped.coerceIn(0, draft.trimEnd - 16)
                draft.copy(trimStart = start, loopStart = draft.loopStart?.coerceAtLeast(start))
            }
            Marker.END -> {
                val end = snapped.coerceIn(draft.trimStart + 16, draft.pcm.size)
                draft.copy(trimEnd = end, loopStart = draft.loopStart?.coerceAtMost(end - 16))
            }
            Marker.LOOP -> draft.copy(loopStart = snapped.coerceIn(draft.trimStart, draft.trimEnd - 16))
        }
    }
    /** A local PCM continuity aid, not a guarantee of a click-free encoded loop. */
    fun nearbyJoin(draft: SampleDraft): SampleDraft {
        val current = draft.loopStart ?: return draft
        val pcm = draft.pcm
        val end = draft.trimEnd
        fun score(at: Int): Long {
            val jump = abs(pcm[end - 1].toLong() - pcm[at])
            val incoming = pcm[end - 1].toLong() - pcm[end - 2]
            val outgoing = pcm[at + 1].toLong() - pcm[at]
            return jump * 4 + abs(incoming - outgoing)
        }
        val candidates = (maxOf(draft.trimStart, current - 128)..minOf(end - 16, current + 128) step 16)
        val best = candidates.minWithOrNull(compareBy<Int> { score(it) }.thenBy { abs(it - current) }) ?: current
        return draft.copy(loopStart = best)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SampleLoopEditor(draft: SampleDraft, enabled: Boolean, encoded: SampleAuthoring.Encoded? = null, onChange: (SampleDraft) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val focus = remember { FocusRequester() }
    var marker by remember(draft.pcm) { mutableStateOf(SampleLoopEditing.Marker.LOOP) }
    val active = if (draft.loopStart == null && marker == SampleLoopEditing.Marker.LOOP) SampleLoopEditing.Marker.START else marker
    val latestDraft by rememberUpdatedState(draft)
    val latestMarker by rememberUpdatedState(active)
    val update by rememberUpdatedState(onChange)
    val move by rememberUpdatedState({ selected: SampleLoopEditing.Marker, frame: Int -> update(SampleLoopEditing.move(latestDraft, selected, frame)) })
    val wave = MaterialTheme.colorScheme.primary
    val loopColor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val trimmed = MaterialTheme.colorScheme.onSurfaceVariant
    val markers = SampleLoopEditing.Marker.entries.filter { it != SampleLoopEditing.Marker.LOOP || draft.loopStart != null }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (value in markers) FilterChip(active == value, { marker = value }, enabled = enabled, shape = SoundControlShape,
            label = { Text(value.label, fontSize = fs.detail) }, modifier = Modifier.testTag("sample-marker-${value.name}"))
        OutlinedButton(onClick = { move(active, SampleLoopEditing.position(draft, active) - 16) }, enabled = enabled, shape = SoundControlShape,
            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("sample-marker-back")) { Text("−16", fontSize = fs.detail) }
        OutlinedButton(onClick = { move(active, SampleLoopEditing.position(draft, active) + 16) }, enabled = enabled, shape = SoundControlShape,
            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("sample-marker-forward")) { Text("+16", fontSize = fs.detail) }
    }
    Canvas(Modifier.fillMaxWidth().height(100.dp).background(MaterialTheme.colorScheme.surfaceVariant, SoundControlShape)
        .testTag("sample-encoded-waveform").semantics {
            contentDescription = "Trim and loop waveform"
            stateDescription = "Start ${draft.trimStart}, end ${draft.trimEnd}, loop ${draft.loopStart ?: "off"}"
            if (!enabled) disabled()
            progressBarRangeInfo = ProgressBarRangeInfo(SampleLoopEditing.position(draft, active).toFloat(), 0f..draft.pcm.size.toFloat())
            setProgress { frame -> if (enabled) { move(active, frame.roundToInt()); true } else false }
        }.onPreviewKeyEvent { event ->
            val direction = when (event.key) { Key.DirectionLeft, Key.DirectionDown -> -1; Key.DirectionRight, Key.DirectionUp -> 1; else -> 0 }
            if (enabled && direction != 0 && event.type == KeyEventType.KeyDown) {
                move(active, SampleLoopEditing.position(draft, active) + direction * if (event.isShiftPressed) 128 else 16); true
            } else false
        }.focusRequester(focus).focusable(enabled)
        .pointerInput(enabled, draft.pcm) {
            fun frame(x: Float) = (x / size.width.coerceAtLeast(1) * latestDraft.pcm.size).roundToInt()
            fun closest(x: Float): SampleLoopEditing.Marker {
                val current = latestDraft
                val available = SampleLoopEditing.Marker.entries.filter { it != SampleLoopEditing.Marker.LOOP || current.loopStart != null }
                val nearest = available.sortedBy { if (it == latestMarker) 0 else 1 }.minBy { abs(SampleLoopEditing.position(current, it) - frame(x)) }
                return if (abs(SampleLoopEditing.position(current, nearest) - frame(x)) <= current.pcm.size * 18f / size.width.coerceAtLeast(1)) nearest else latestMarker
            }
            var dragging = latestMarker
            if (enabled) detectDragGestures(onDragStart = { position ->
                focus.requestFocus(); dragging = closest(position.x); marker = dragging; move(dragging, frame(position.x))
            }, onDrag = { change, _ -> change.consume(); move(dragging, frame(change.position.x)) })
        }.pointerInput(enabled, draft.pcm) {
            if (enabled) detectTapGestures { position -> focus.requestFocus(); move(latestMarker, (position.x / size.width.coerceAtLeast(1) * latestDraft.pcm.size).roundToInt()) }
        }) {
        val count = draft.pcm.size
        val startX = draft.trimStart.toFloat() / count * size.width
        val endX = draft.trimEnd.toFloat() / count * size.width
        val loopX = draft.loopStart?.let { it.toFloat() / count * size.width }
        if (loopX != null) drawRect(loopColor.copy(alpha = 0.10f), Offset(loopX, 0f), androidx.compose.ui.geometry.Size((endX - loopX).coerceAtLeast(0f), size.height))
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        val bins = minOf(size.width.toInt().coerceAtLeast(1), count, 512)
        for (bin in 0 until bins) {
            val from = bin * count / bins; val to = (bin + 1) * count / bins
            var low = 0; var high = 0
            for (at in from until to) {
                val value = encoded?.pcm?.getOrNull(at - draft.trimStart)?.toInt() ?: (draft.pcm[at] * draft.gainPercent / 100)
                low = minOf(low, value); high = maxOf(high, value)
            }
            val x = (bin + 0.5f) * size.width / bins
            val ink = if (x < startX || x > endX) trimmed.copy(alpha = 0.25f) else wave
            drawLine(ink, Offset(x, size.height * (0.5f - high / 65536f)), Offset(x, size.height * (0.5f - low / 65536f)), 1.5f)
        }
        for (value in markers) {
            val x = (SampleLoopEditing.position(draft, value).toFloat() / count * size.width).coerceIn(4f, (size.width - 4).coerceAtLeast(4f))
            val color = if (value == SampleLoopEditing.Marker.LOOP) loopColor else wave
            drawLine(color, Offset(x, 6f), Offset(x, size.height), if (active == value) 3f else 1f)
            drawCircle(color, if (active == value) 6f else 4f, Offset(x, 6f))
        }
    }
    Text("Drag a marker · arrows: 16 frames · Shift: 128", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SampleLoopJoin(draft: SampleDraft, encoded: SampleAuthoring.Encoded?, enabled: Boolean, onChange: (SampleDraft) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    val wave = MaterialTheme.colorScheme.primary
    val loopColor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    if (draft.loopStart != null) {
        val pcm = encoded?.pcm
        val loop = encoded?.loopFrame
        val jump = if (pcm != null && loop != null && pcm.isNotEmpty()) abs(pcm.last().toInt() - pcm[loop].toInt()) / 655.36 else null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Loop join · end → start", fontSize = fs.detail)
            TextButton(onClick = { onChange(SampleLoopEditing.nearbyJoin(draft)) }, enabled = enabled, shape = SoundControlShape,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(24.dp).testTag("sample-nearby-join")) { Text("Find nearby join", fontSize = fs.detail) }
        }
        Canvas(Modifier.fillMaxWidth().height(52.dp).background(MaterialTheme.colorScheme.surfaceVariant, SoundControlShape).testTag("sample-loop-join")) {
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
            drawLine(loopColor.copy(alpha = 0.5f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
            if (pcm != null && loop != null) {
                val frames = minOf(32, pcm.size, pcm.size - loop)
                val joined = pcm.takeLast(frames) + pcm.drop(loop).take(frames)
                for (at in 1 until joined.size) drawLine(if (at < frames) wave else loopColor,
                    Offset((at - 1f) / (joined.size - 1) * size.width, size.height * (0.5f - joined[at - 1] / 65536f)),
                    Offset(at.toFloat() / (joined.size - 1) * size.width, size.height * (0.5f - joined[at] / 65536f)), 2f)
            }
        }
        Text(jump?.let { "Encoded join · ${"%.1f".format(it)}% full-scale difference" } ?: "Encoding loop join…", fontSize = fs.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
