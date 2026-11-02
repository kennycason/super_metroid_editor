package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.supermetroid.editor.data.SoundEffectInstruction
import com.supermetroid.editor.rom.RomParser

internal object EffectStepTemplates {
    private fun step(op: Int, vararg params: Int) = SoundEffectInstruction(op, params.toMutableList())
    private fun note() = step(0, 160, 10, 0xA4, 12)
    fun choices() = linkedMapOf(
        "Note" to listOf(note()),
        "Envelope + note" to listOf(step(0xF9, 0x8F, 0xE0), note()),
        "Pitch slide + note" to listOf(step(0xF8, 8, 0xB0), note()),
        "Legato slide + note" to listOf(step(0xF5, 8, 0xB0), note()),
        "Noise + note" to listOf(step(0xFC), note()),
        "Counted repeat block" to listOf(step(0xFE, 2), note(), step(0xFD)),
        "Repeat forever block" to listOf(step(0xFE, 0), note(), step(0xFB)),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EffectProgramActions(parser: RomParser?, editor: EditorState, state: SoundEffectEditorState) {
    val fs = LocalEditorTheme.current.fontSize.value
    val effect = state.selected ?: return
    var dialog by remember { mutableStateOf("") }
    Text(if (state.owned == null) "Shared program" else "Independent program · ${state.memoryUsed} / ${state.memoryCapacity} B", fontSize = fs.body)
    SoundToolbar {
        SoundToolbarButton("Duplicate", { dialog = "duplicate" }, Modifier.testTag("effect-duplicate"), enabled = parser != null && !state.loading)
        if (state.owned == null) SoundToolbarButton("Edit independently", { parser?.let { state.makeIndependent(it, editor) } },
            Modifier.testTag("effect-independent"), enabled = parser != null && !state.loading)
        else {
            SoundToolbarButton("Use for game cue…", { dialog = "assign" }, Modifier.testTag("effect-assign"), enabled = parser != null && !state.loading && SoundEffects.identities.any { it.voices == effect.voices.size && it.key != effect.identity.key })
            if (state.canDelete) SoundToolbarButton("Delete", { parser?.let { state.delete(it, editor) } }, Modifier.testTag("effect-delete"), enabled = !state.loading)
        }
    }
    Text(if (state.owned == null) "Shared edits can affect other cues. Make this effect independent to edit its steps."
        else "Preview uses the chosen bank. In-game effects use the bank already loaded.", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (dialog.isNotEmpty() && parser != null) {
        var name by remember(dialog, effect.identity.key) { mutableStateOf("${effect.identity.name} copy".take(100)) }
        val available = (1..3).associateWith { lib ->
            (SoundEffectPrograms.count(lib) + 1..128).count { id -> SoundEffectPrograms.key(lib, id) !in state.effects.map { it.identity.key } }
        }
        var library by remember(dialog, effect.identity.key) { mutableStateOf(
            (listOf(effect.identity.library) + (1..3)).firstOrNull { lib -> available.getValue(lib) > 0 && SoundEffects.identities.any { it.library == lib && it.voices == effect.voices.size } } ?: effect.identity.library) }
        var target by remember(dialog, effect.identity.key) { mutableStateOf(SoundEffects.identities.firstOrNull { it.voices == effect.voices.size && it.key != effect.identity.key }?.key.orEmpty()) }
        var problem by remember(dialog) { mutableStateOf<String?>(null) }
        Dialog(onDismissRequest = { dialog = "" }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            EffectAuthoringForm(dialog == "duplicate", name, { name = it }, library, { library = it }, target, { target = it }, effect,
                problem, onApply = {
                    val success = if (dialog == "duplicate") state.duplicate(parser, editor, name, library) else state.assign(parser, editor, target)
                    if (success) dialog = "" else problem = state.message
                }, onClose = { dialog = "" }, availableIds = available)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EffectAuthoringForm(duplicate: Boolean, name: String, onName: (String) -> Unit, library: Int, onLibrary: (Int) -> Unit,
    target: String, onTarget: (String) -> Unit, effect: SoundEffects.Effect, problem: String?, onApply: () -> Unit, onClose: () -> Unit,
    availableIds: Map<Int, Int> = (1..3).associateWith { 128 - SoundEffectPrograms.count(it) }) {
    val fs = LocalEditorTheme.current.fontSize.value
    var choosing by remember { mutableStateOf(false) }
    val targets = SoundEffects.identities.filter { it.voices == effect.voices.size && it.key != effect.identity.key }
    Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 680.dp), shape = SoundControlShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (duplicate) "Duplicate effect" else "Use for game cue", fontSize = fs.heading)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SynthModule(Modifier.fillMaxWidth()) {
                    Text(effect.identity.name, fontSize = fs.body)
                    if (duplicate) OutlinedTextField(name, onName, singleLine = true, label = { Text("Effect name", fontSize = fs.detail) },
                        textStyle = LocalTextStyle.current.copy(fontSize = fs.body), modifier = Modifier.fillMaxWidth().testTag("effect-copy-name"))
                    ExposedDropdownMenuBox(choosing, { choosing = it }) {
                        OutlinedTextField(if (duplicate) "Library $library" else targets.firstOrNull { it.key == target }?.let { "${it.key} · ${it.name}" } ?: "No other cue uses this voice count", {},
                            readOnly = true, singleLine = true, label = { Text(if (duplicate) "Effect library" else "Replace game cue", fontSize = fs.detail) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(choosing) }, textStyle = LocalTextStyle.current.copy(fontSize = fs.body),
                            modifier = Modifier.fillMaxWidth().menuAnchor().testTag("effect-copy-target"))
                        ExposedDropdownMenu(choosing, { choosing = false }) {
                            if (duplicate) for (lib in 1..3) {
                                val compatible = availableIds.getValue(lib) > 0 && SoundEffects.identities.any { it.library == lib && it.voices == effect.voices.size }
                                DropdownMenuItem({ Text("Library $lib · ${availableIds.getValue(lib)} free IDs", fontSize = fs.body) },
                                    { onLibrary(lib); choosing = false }, enabled = compatible)
                            } else for (cue in targets) DropdownMenuItem({ Text("${cue.key} · ${cue.name}", fontSize = fs.body) }, { onTarget(cue.key); choosing = false })
                        }
                    }
                    Text(if (duplicate) "Creates an independent ${effect.voices.size}-voice copy with a new ID. Use for game cue connects it to an existing sound in the game."
                        else "Calls to this game cue will play a copy of your program using the bank loaded in-game. The preview bank is not saved with the cue. Undo or Reset effect restores it.",
                        fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            problem?.let { Text(it, fontSize = fs.detail, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("effect-copy-problem")) }
            SoundToolbar {
                SoundToolbarButton(if (duplicate) "Create effect" else "Use this cue", onApply, Modifier.testTag("effect-copy-apply"), enabled = if (duplicate) name.isNotBlank() && availableIds.getValue(library) > 0 else targets.any { it.key == target }, primary = true)
                SoundToolbarButton("Cancel", onClose)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EffectStepActions(parser: RomParser?, editor: EditorState, state: SoundEffectEditorState, voice: Int, index: Int, count: Int,
    onSelect: (Int) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    SoundToolbar {
        Box {
            SoundToolbarButton("Add step", { adding = true }, Modifier.testTag("effect-step-add"), enabled = !state.loading, icon = Icons.Default.ArrowDropDown)
            DropdownMenu(adding, { adding = false }) {
                for ((name, steps) in EffectStepTemplates.choices()) DropdownMenuItem({ Text(name) }, {
                    adding = false
                    parser?.let { if (state.addSteps(it, editor, voice, index, steps)) onSelect((index + 1).coerceAtMost(count - 1)) }
                })
            }
        }
        SoundToolbarButton("Earlier", { parser?.let { if (state.changeStep(it, editor, voice, index, move = -1)) onSelect(index - 1) } },
            Modifier.testTag("effect-step-earlier"), enabled = !state.loading && index > 0 && index < count - 1)
        SoundToolbarButton("Later", { parser?.let { if (state.changeStep(it, editor, voice, index, move = 1)) onSelect(index + 1) } },
            Modifier.testTag("effect-step-later"), enabled = !state.loading && index < count - 2)
        SoundToolbarButton("Remove step", { parser?.let { if (state.changeStep(it, editor, voice, index, remove = true)) onSelect(index.coerceAtMost(count - 2)) } },
            Modifier.testTag("effect-step-remove"), enabled = !state.loading && index < count - 1)
    }
}
