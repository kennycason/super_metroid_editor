package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.NspcSequence

/** Preview pitches are local to this instrument editor and never change a track note. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SoundAuditionKeyboard(
    pitch: Int,
    notePitch: Int,
    original: Boolean,
    originalAvailable: Boolean,
    enabled: Boolean,
    editedAvailable: Boolean = true,
    onPitch: (Int) -> Unit,
    onOriginal: (Boolean) -> Unit,
) {
    val fs = LocalEditorTheme.current.fontSize.value
    var octave by remember(notePitch) { mutableStateOf((pitch - NspcSequence.NOTE_MIN) / 12 + 1) }
    val canPlay = enabled && if (original) originalAvailable else editedAvailable
    val compare: @Composable () -> Unit = {
        FilterChip(!original, { onOriginal(false) }, enabled = enabled && editedAvailable, shape = SoundControlShape,
            label = { Text("Edited", fontSize = fs.detail) }, modifier = Modifier.testTag("audition-edited"))
        FilterChip(original, { onOriginal(true) }, enabled = enabled && originalAvailable, shape = SoundControlShape,
            label = { Text("Original", fontSize = fs.detail) }, modifier = Modifier.testTag("audition-original"))
    }
    val reset: @Composable () -> Unit = {
        OutlinedButton(onClick = { octave = (notePitch - NspcSequence.NOTE_MIN) / 12 + 1; onPitch(notePitch) },
            enabled = canPlay, shape = SoundControlShape, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp).testTag("audition-note-pitch")) { Text("This note · ${NspcSequence.noteToName(notePitch)}", fontSize = fs.detail) }
    }
    val hint: @Composable () -> Unit = {
        Text(if (original) "Original bank sound · controls edit Edited" else "Click a key to preview · track notes stay unchanged",
            fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 960.dp
        SynthModule(Modifier.fillMaxWidth().testTag("sound-audition")) {
            if (wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Audition", fontSize = fs.body, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { compare() }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PianoKeys(octave, pitch, canPlay, onPitch)
                    hint()
                }
                Column(Modifier.width(152.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AuditionOctave(octave) { octave = it }
                    reset()
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Audition", fontSize = fs.body, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 9.dp))
                    compare()
                    reset()
                    AuditionOctave(octave) { octave = it }
                }
                PianoKeys(octave, pitch, canPlay, onPitch)
                hint()
            }
        }
    }
}

@Composable
private fun AuditionOctave(octave: Int, onChange: (Int) -> Unit) {
    val fs = LocalEditorTheme.current.fontSize.value
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { onChange(octave - 1) }, enabled = octave > 1, shape = SoundControlShape,
            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp).testTag("audition-octave-down")
                .semantics { contentDescription = "Lower audition octave" }) { Text("−", fontSize = fs.body) }
        Text("Octave $octave", fontSize = fs.detail, modifier = Modifier.testTag("audition-octave"))
        OutlinedButton(onClick = { onChange(octave + 1) }, enabled = octave < 6, shape = SoundControlShape,
            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp).testTag("audition-octave-up")
                .semantics { contentDescription = "Raise audition octave" }) { Text("+", fontSize = fs.body) }
    }
}

@Composable
private fun PianoKeys(octave: Int, pitch: Int, enabled: Boolean, onPitch: (Int) -> Unit) {
    BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxWidth().height(64.dp)) {
        val whiteWidth = maxWidth / 8
        val blackWidth = whiteWidth * 0.58f
        val base = NspcSequence.NOTE_MIN + (octave - 1) * 12
        val whites = listOf(0, 2, 4, 5, 7, 9, 11, 12)
        Row(Modifier.fillMaxSize()) {
            for (semitone in whites) AuditionKey(base + semitone, pitch, false, enabled, onPitch,
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp))
        }
        for ((semitone, boundary) in listOf(1 to 1, 3 to 2, 6 to 4, 8 to 5, 10 to 6)) {
            AuditionKey(base + semitone, pitch, true, enabled, onPitch,
                Modifier.offset(x = whiteWidth * boundary - blackWidth / 2).width(blackWidth).height(41.dp))
        }
    }
}

@Composable
private fun AuditionKey(value: Int, pitch: Int, black: Boolean, enabled: Boolean, onPitch: (Int) -> Unit, modifier: Modifier) {
    val fs = LocalEditorTheme.current.fontSize.value
    val available = enabled && value in NspcSequence.NOTE_MIN..NspcSequence.NOTE_MAX
    val chosen = value == pitch
    val name = if (value > NspcSequence.NOTE_MAX) "C7" else NspcSequence.noteToName(value)
    // Piano key contrast stays familiar in both editor themes; the selection follows the theme accent.
    val face = if (chosen) MaterialTheme.colorScheme.primary else if (black) Color(0xFF292D35) else Color(0xFFE5E9EF)
    val ink = if (chosen) MaterialTheme.colorScheme.onPrimary else if (black) Color(0xFFE5E9EF) else Color(0xFF292D35)
    val shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
    Surface(modifier.testTag("audition-key-$value").semantics {
        contentDescription = "Audition $name"
        selected = chosen
    }.clickable(enabled = available, role = Role.Button, onClickLabel = "Play $name") { onPitch(value) },
        color = face.copy(alpha = if (available) 1f else 0.4f), shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.fillMaxSize().padding(bottom = 5.dp), contentAlignment = Alignment.BottomCenter) {
            Text(if (black) name.dropLast(1) else name, fontSize = fs.detail, color = ink.copy(alpha = if (available) 1f else 0.4f),
                maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}
