package com.supermetroid.editor.ui

import androidx.compose.runtime.*
import com.supermetroid.editor.data.*
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
    private data class Frame(val edits: Map<String, SoundEffectByteEdit>, val programs: Map<String, SoundEffectProgram>)
    private val undo = ArrayDeque<Frame>()
    private val redo = ArrayDeque<Frame>()
    private var sourcePrograms = emptyMap<String, SoundEffectProgram>()
    private var loaded: SmEditProject? = null
    private var historyCurrent: Frame? = null
    var programs by mutableStateOf(emptyMap<String, SoundEffectProgram>()); private set
    var memoryUsed by mutableStateOf(0); private set
    val memoryCapacity get() = SoundEffectPrograms.arenas.sumOf { it.count() }
    val owned get() = programs[selectedKey]
    val canDelete get() = owned?.let { it.id > SoundEffectPrograms.count(it.library) && selectedKey !in sourcePrograms } == true
    private fun frame(editor: EditorState) = Frame(editor.project.soundEffectEdits.toMap(), SoundEffectPrograms.copyMap(editor.project.soundEffectPrograms))
    private fun current(editor: EditorState) {
        require(!loading && loaded?.let { MusicCreation.sameMusic(it, editor.project) } == true) { "The sound bank changed. Wait for it to reload before editing." }
    }
    private inline fun attempt(action: () -> Unit): Boolean = try { action(); true }
        catch (error: Exception) { message = error.message ?: "Cannot edit this effect"; false }
    private fun update(parser: RomParser, editor: EditorState, next: Frame) {
        current(editor)
        if (next == frame(editor)) return
        val project = MusicBankTransitions.snapshot(editor.project).copy(soundEffectEdits = next.edits.toMutableMap(), soundEffectPrograms = SoundEffectPrograms.copyMap(next.programs))
        // The same full transaction used by Save/Build checks SPC space, banks and ROM allocations.
        MusicRomExporter(project, {}).applyMusicEditsToRom(parser.copyRomData())
        undo.addLast(frame(editor)); if (undo.size > 100) undo.removeFirst(); redo.clear()
        commit(editor, next); history()
    }

    suspend fun load(parser: RomParser, editor: EditorState) {
        val key = "${editor.project.projectId}:${editor.project.romPath}"
        if (key != projectKey) { stop(); projectKey = key; undo.clear(); redo.clear(); historyCurrent = null; history() }
        if (historyCurrent != null && historyCurrent != frame(editor)) { undo.clear(); redo.clear(); history() }
        historyCurrent = frame(editor)
        val token = ++version
        val project = MusicBankTransitions.snapshot(editor.project)
        val context = bank
        loading = true
        try {
            val result = withContext(Dispatchers.Default) {
                val ram = if (context == 0) SoundEffects.bootRam(parser, project) else MusicBankResolver.sourceRam(parser, project, context)
                val definitions = SoundEffectPrograms.definitions(ram).toMutableMap().also { it.putAll(SoundEffectPrograms.copyMap(project.soundEffectPrograms)) }
                Triple(SoundEffects.catalog(ram, project), definitions, SoundEffectPrograms.compile(ram, definitions).used)
            }
            if (token == version) {
                effects = result.first; programs = result.second; memoryUsed = result.third; loaded = project
                if (effects.none { it.identity.key == selectedKey }) { stop(); selectedKey = "2:01" }
                sourcePrograms = SoundEffectPrograms.definitions(SpcData.buildInitialSpcRam(parser))
                message = "${effects.size} effects · ${if (programs.isEmpty()) "duplicate an effect to create your own" else "$memoryUsed / $memoryCapacity B custom program memory"}"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (token == version) { effects = emptyList(); message = error.message ?: "Cannot read sound effects" } }
        finally { if (token == version) loading = false }
    }
    fun select(key: String) { if (key != selectedKey) { stop(); selectedKey = key; waveform = null } }
    fun selectBank(value: Int) { if (value != bank) { stop(); bank = value; waveform = null } }
    fun apply(parser: RomParser, editor: EditorState, key: String, operand: SoundEffects.Operand, value: Int, voiceIndex: Int = 0): Boolean = attempt {
        require(key == selectedKey) { "Select this effect before editing" }
        if (value == operand.value) return@attempt
        require(value in operand.field.range || operand.field == SoundEffects.Field.PITCH && value == 0xF6) { "${operand.field.label} must be ${operand.field.range}" }
        if (owned != null) {
            val voice = selected!!.voices.getOrNull(voiceIndex) ?: error("Select a voice")
            val stepIndex = voice.steps.indexOfFirst { operand in it.operands }
            require(stepIndex >= 0) { "This step changed. Select it again." }
            val offset = voice.steps[stepIndex].operands.indexOf(operand)
            changeProgram(parser, editor) { p ->
                val step = p.voices[voiceIndex].steps[stepIndex]
                if (step.opcode in 0..41 && offset == 0) p.voices[voiceIndex].steps[stepIndex] = step.copy(opcode = value)
                else step.params[offset - if (step.opcode in 0..41) 1 else 0] = value
            }
        } else {
            current(editor)
            val at = operand.address.toString(16).uppercase().padStart(4, '0')
            val base = SpcData.buildInitialSpcRam(parser)[operand.address].toInt() and 255
            val next = editor.project.soundEffectEdits.toMutableMap()
            require((next[at]?.value ?: base) == operand.value) { "The effect changed. Select it again before editing." }
            // Raw stock editing retains its existing bounded operand schema.
            require(value in operand.field.range) { "Duplicate the effect to add a Hold pitch note" }
            if (value == base) next.remove(at) else next[at] = SoundEffectByteEdit(base, value)
            update(parser, editor, frame(editor).copy(edits = next))
        }
        message = "Updated ${operand.field.label} · ${if (owned == null) "shared program" else "independent program"} · Undo available"
    }
    private fun changeProgram(parser: RomParser, editor: EditorState, edit: (SoundEffectProgram) -> Unit) {
        current(editor)
        val program = SoundEffectPrograms.copy(owned ?: error("Choose Edit independently or Duplicate first"))
        require(program.voices == SoundEffectPrograms.capture(selected!!)) { "This program changed. Select it again." }
        edit(program)
        val next = SoundEffectPrograms.copyMap(editor.project.soundEffectPrograms).also { it[selectedKey] = program }
        update(parser, editor, frame(editor).copy(programs = next))
    }
    fun duplicate(parser: RomParser, editor: EditorState, name: String, library: Int): Boolean = attempt {
        current(editor)
        val source = selected?.takeIf { it.problem == null } ?: error("Select a playable effect")
        val route = SoundEffects.identities.firstOrNull { it.library == library && it.id == (owned?.routingId ?: source.identity.id) && it.voices == source.voices.size }
            ?: SoundEffects.identities.firstOrNull { it.library == library && it.voices == source.voices.size }
            ?: error("Library $library cannot play ${source.voices.size} voices. Choose another library.")
        val id = (SoundEffectPrograms.count(library) + 1..128).firstOrNull { SoundEffectPrograms.key(library, it) !in effects.map { e -> e.identity.key } }
            ?: error("Library $library has no unused effect IDs. Choose another library.")
        val voices = SoundEffectPrograms.capture(source)
        val p = SoundEffectProgram(library, id, name.trim(), route.id, voices, SoundEffectPrograms.copyVoices(voices))
        val key = SoundEffectPrograms.key(library, id)
        update(parser, editor, frame(editor).copy(programs = frame(editor).programs + (key to p)))
        select(key); this.library = 0; query = ""
        message = "Created ${p.name} · $key · use for a game cue when ready"
    }
    fun makeIndependent(parser: RomParser, editor: EditorState): Boolean = attempt {
        current(editor)
        val source = selected ?: error("Select an effect")
        require(owned == null) { "This effect is already independent" }
        val voices = SoundEffectPrograms.capture(source)
        val p = SoundEffectProgram(source.identity.library, source.identity.id, source.identity.name, source.identity.id, voices, SoundEffectPrograms.copyVoices(voices))
        update(parser, editor, frame(editor).copy(programs = frame(editor).programs + (selectedKey to p)))
        message = "Independent program · game calls to ${source.identity.name} use this version"
    }
    fun assign(parser: RomParser, editor: EditorState, targetKey: String): Boolean = attempt {
        current(editor)
        val source = owned ?: error("Duplicate or detach an effect first")
        val target = SoundEffects.identities.firstOrNull { it.key == targetKey && it.voices == source.voices.size } ?: error("Choose a game cue with ${source.voices.size} voices")
        require(targetKey != selectedKey) { "This effect already uses that cue" }
        val original = effects.first { it.identity.key == targetKey }
        val p = SoundEffectProgram(target.library, target.id, "${source.name} · ${target.name}".take(100), target.id,
            SoundEffectPrograms.copyVoices(source.voices), SoundEffectPrograms.capture(original))
        update(parser, editor, frame(editor).copy(programs = frame(editor).programs + (targetKey to p)))
        select(targetKey); library = 0; query = ""
        message = "Game calls to ${target.name} now use this independent program · Undo available"
    }
    fun changeStep(parser: RomParser, editor: EditorState, voice: Int, index: Int, move: Int = 0, remove: Boolean = false): Boolean = attempt {
        changeProgram(parser, editor) { p ->
            val steps = p.voices[voice].steps
            require(index in 0 until steps.lastIndex) { "Keep the final End step" }
            if (remove) steps.removeAt(index) else {
                require(index + move in 0 until steps.lastIndex) { "Keep End last" }
                val step = steps.removeAt(index); steps.add(index + move, step)
            }
        }
        message = "Updated program steps · Undo available"
    }
    fun addSteps(parser: RomParser, editor: EditorState, voice: Int, after: Int, steps: List<SoundEffectInstruction>): Boolean = attempt {
        changeProgram(parser, editor) { p ->
            val list = p.voices[voice].steps
            list.addAll((after + 1).coerceIn(0, list.lastIndex), SoundEffectPrograms.copyVoices(listOf(SoundEffectVoiceProgram(steps.toMutableList())))[0].steps)
        }
        message = "Added program steps · Undo available"
    }
    fun delete(parser: RomParser, editor: EditorState): Boolean = attempt {
        current(editor); require(canDelete) { "Effects in the source ROM cannot be deleted" }
        update(parser, editor, frame(editor).copy(programs = frame(editor).programs - selectedKey))
        select("2:01"); message = "Deleted project effect · Undo available"
    }
    fun undo(editor: EditorState) {
        if (undo.isEmpty() || !historyValid(editor)) return
        redo.addLast(frame(editor)); commit(editor, undo.removeLast()); history()
    }
    fun redo(editor: EditorState) {
        if (redo.isEmpty() || !historyValid(editor)) return
        undo.addLast(frame(editor)); commit(editor, redo.removeLast()); history()
    }
    private fun historyValid(editor: EditorState): Boolean {
        if (historyCurrent == frame(editor) && projectKey == "${editor.project.projectId}:${editor.project.romPath}") return true
        undo.clear(); redo.clear(); history(); message = "The project changed. Reload this effect before editing."; return false
    }
    fun reset(editor: EditorState) {
        if (!historyValid(editor) || loading) return
        val current = frame(editor)
        val next = if (owned != null) {
            val program = current.programs[selectedKey]
            val restored = if (selectedKey in sourcePrograms) current.programs - selectedKey
                else if (program != null && program.id > SoundEffectPrograms.count(program.library)) current.programs + (selectedKey to program.copy(voices = SoundEffectPrograms.copyVoices(program.baseline)))
                else current.programs - selectedKey
            current.copy(programs = restored)
        } else {
            val addresses = selected?.voices.orEmpty().flatMap { it.steps }.flatMap { it.operands }.map { it.address.toString(16).uppercase().padStart(4, '0') }.toSet()
            current.copy(edits = current.edits.filterKeys { it !in addresses })
        }
        if (next == current) return
        undo.addLast(current); redo.clear(); commit(editor, next); history()
    }
    private fun commit(editor: EditorState, frame: Frame) {
        val follow = playing && live
        if (!follow) stop() else version++
        editor.setSoundEffectState(frame.edits, frame.programs); historyCurrent = this.frame(editor); refreshRequested = follow
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
