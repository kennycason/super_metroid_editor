package com.supermetroid.editor.ui

import androidx.compose.runtime.*
import com.supermetroid.editor.data.SoundEffectByteEdit
import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*

internal class SoundEffectEditorState(private val player: SoundPlayer = SoundPlayer()) {
    var effects by mutableStateOf(emptyList<SoundEffects.Effect>()); private set
    var selectedKey by mutableStateOf("2:01"); private set
    var query by mutableStateOf("")
    var library by mutableStateOf(0)
    var bank by mutableStateOf(0); private set
    var loading by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var waveform by mutableStateOf<ShortArray?>(null); private set
    var duration by mutableStateOf(3)
    var live by mutableStateOf(true); private set
    var refreshRequested by mutableStateOf(false); private set
    var undoDepth by mutableStateOf(0); private set
    var redoDepth by mutableStateOf(0); private set
    val selected get() = effects.firstOrNull { it.identity.key == selectedKey }
    val visible get() = effects.filter { (library == 0 || it.identity.library == library) &&
        "${it.identity.name} ${it.identity.key}".contains(query.trim(), true) }
    private var version = 0
    private var projectKey = ""
    private val undo = ArrayDeque<Map<String, SoundEffectByteEdit>>()
    private val redo = ArrayDeque<Map<String, SoundEffectByteEdit>>()

    suspend fun load(parser: RomParser, editor: EditorState) {
        val key = "${editor.project.projectId}:${editor.project.romPath}"
        if (key != projectKey) { stop(); projectKey = key; undo.clear(); redo.clear(); history() }
        val token = ++version
        val project = MusicBankTransitions.snapshot(editor.project)
        val context = bank
        loading = true
        try {
            val result = withContext(Dispatchers.Default) {
                val ram = if (context == 0) SoundEffects.bootRam(parser, project) else MusicBankResolver.sourceRam(parser, project, context)
                SoundEffects.catalog(ram)
            }
            if (token == version) { effects = result; message = "${result.size} effects · edits apply to the shared effect program" }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (token == version) { effects = emptyList(); message = error.message ?: "Cannot read sound effects" } }
        finally { if (token == version) loading = false }
    }
    fun select(key: String) { if (key != selectedKey) { stop(); selectedKey = key; waveform = null } }
    fun selectBank(value: Int) { if (value != bank) { stop(); bank = value; waveform = null } }
    fun apply(parser: RomParser, editor: EditorState, key: String, operand: SoundEffects.Operand, value: Int) {
        if (key != selectedKey || loading || value == operand.value) return
        try {
            require(value in operand.field.range) { "${operand.field.label} must be ${operand.field.range}" }
            val at = operand.address.toString(16).uppercase().padStart(4, '0')
            val base = SpcData.buildInitialSpcRam(parser)[operand.address].toInt() and 255
            val next = editor.project.soundEffectEdits.toMutableMap()
            require((next[at]?.value ?: base) == operand.value) { "The effect changed. Select it again before editing." }
            if (value == base) next.remove(at) else next[at] = SoundEffectByteEdit(base, value)
            if (next == editor.project.soundEffectEdits) return
            SoundEffects.exportWrites(parser, editor.project.copy(soundEffectEdits = next))
            undo.addLast(editor.project.soundEffectEdits.toMap()); if (undo.size > 100) undo.removeFirst(); redo.clear()
            commit(editor, next); history()
            message = "Updated ${operand.field.label} · shared program · Undo available"
        } catch (error: Exception) { message = error.message ?: "Cannot edit this effect" }
    }
    fun undo(editor: EditorState) {
        if (undo.isEmpty()) return
        redo.addLast(editor.project.soundEffectEdits.toMap()); commit(editor, undo.removeLast()); history()
    }
    fun redo(editor: EditorState) {
        if (redo.isEmpty()) return
        undo.addLast(editor.project.soundEffectEdits.toMap()); commit(editor, redo.removeLast()); history()
    }
    fun reset(editor: EditorState) {
        val addresses = selected?.voices.orEmpty().flatMap { it.steps }.flatMap { it.operands }.map { it.address.toString(16).uppercase().padStart(4, '0') }.toSet()
        val next = editor.project.soundEffectEdits.filterKeys { it !in addresses }
        if (next == editor.project.soundEffectEdits) return
        undo.addLast(editor.project.soundEffectEdits.toMap()); redo.clear(); commit(editor, next); history()
    }
    private fun commit(editor: EditorState, edits: Map<String, SoundEffectByteEdit>) {
        val follow = playing && live
        if (!follow) stop() else version++
        editor.setSoundEffectEdits(edits); refreshRequested = follow
    }
    fun setLiveEnabled(value: Boolean) { if (value != live) { stop(); live = value } }
    private fun history() { undoDepth = undo.size; redoDepth = redo.size }
    suspend fun preview(parser: RomParser, editor: EditorState) {
        val effect = selected?.takeIf { it.problem == null } ?: return
        refreshRequested = false
        val token = ++version
        val context = bank
        val seconds = duration.coerceIn(1, 10)
        val project = MusicBankTransitions.snapshot(editor.project)
        loading = true
        try {
            val pcm = withContext(Dispatchers.Default) {
                val ram = if (context == 0) SoundEffects.bootRam(parser, project) else MusicBankResolver.sourceRam(parser, project, context)
                SoundEffects.requireEngine(ram)
                NativeSpcEmulator().use { engine ->
                    engine.loadFromRam(ram, emptyList(), 0, playbackWarmupSeconds = 0)
                    engine.playSoundEffect(effect.identity.library, effect.identity.id)
                    engine.renderMono(seconds)
                }
            }
            if (token != version) return
            waveform = pcm
            player.onComplete = { if (token == version) { playing = false; message = "Preview finished" } }
            playing = player.play(pcm, loop = live) == SoundPlayer.PlaybackStart.STARTED
            message = if (playing) "Playing ${effect.identity.name}" else "Cannot open audio output"
        } catch (cancelled: CancellationException) { if (token == version) stop(); throw cancelled }
        catch (error: Exception) { if (token == version) { stop(); message = error.message ?: "Cannot preview this effect" } }
        finally { if (token == version) loading = false }
    }
    fun stop() { version++; refreshRequested = false; player.onComplete = null; player.stop(); playing = false; loading = false }
}
