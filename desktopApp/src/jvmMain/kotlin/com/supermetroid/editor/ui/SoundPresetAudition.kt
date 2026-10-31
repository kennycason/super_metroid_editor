package com.supermetroid.editor.ui

import androidx.compose.runtime.*
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.RomParser
import kotlinx.coroutines.*

/** A disposable comparison session. Neither the library nor the destination bank is edited. */
internal class SoundPresetAudition(
    private val parser: RomParser,
    private val target: SampleBankPreview.Target,
    private val tempo: Int,
    private val player: SoundPlayer = SoundPlayer(),
    private val heldPlayer: HeldSoundPlayer = HeldSoundPlayer(),
) {
    val notePitch = target.note.noteValue
    var preset by mutableStateOf<SoundPreset?>(null); private set
    var current by mutableStateOf(false); private set
    var pitch by mutableStateOf(target.note.noteValue); private set
    var live by mutableStateOf(true); private set
    var active by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var refreshVersion by mutableStateOf(0); private set
    private var version = 0
    private var held = false

    fun select(value: SoundPreset?) {
        if (preset == value && !current) return
        preset = value; current = false
        if (value == null) stop() else invalidate()
    }
    fun compare(value: Boolean) { if (current != value) { current = value; invalidate() } }
    fun changePitch(value: Int) {
        require(value in com.supermetroid.editor.rom.NspcSequence.NOTE_MIN..com.supermetroid.editor.rom.NspcSequence.NOTE_MAX)
        pitch = value
    }
    fun setLiveEnabled(value: Boolean) { if (value != live) { stop(); live = value } }
    private fun invalidate() { version++; if (active) { busy = true; refreshVersion++ } }

    suspend fun play(hold: Boolean = false) { held = hold; if (hold) player.stop(); if (!current && preset == null) return; active = true; refresh() }
    suspend fun refresh() {
        if (!active) return
        val selected = preset
        val compare = current
        if (!compare && selected == null) { stop(); return }
        val token = ++version
        val note = target.note.copy(noteValue = pitch)
        val loop = live
        val holding = held
        val label = "${if (compare) "Current sound" else selected!!.name} · ${note.name}"
        busy = true
        try {
            val preview = withContext(Dispatchers.Default) {
                val preview = if (compare) {
                    val ram = MusicBankResolver.sourceRam(parser, target.project, target.songSet)
                    val instrument = NspcRenderer.readInstrumentTable(ram).first { it.index == note.instrument }
                    InstrumentAudition.build(ram, instrument, note, tempo, holding)
                } else SoundPresets.preview(parser, selected!!, note.instrument, note, tempo,
                    noiseRam = if (selected.noiseSource != null) MusicBankResolver.sourceRam(parser, target.project, target.songSet) else null, held = holding)
                preview
            }
            if (token != version) return
            if (holding) {
                player.stop()
                heldPlayer.play(preview) { if (token == version) { busy = false; message = "Holding · $label" } }
                if (token == version) stop()
                return
            }
            val pcm = withContext(Dispatchers.Default) { InstrumentAudition.render(preview) }
            if (token != version) return
            heldPlayer.stop()
            player.onComplete = { if (token == version) { active = false; message = "Preview finished · $label" } }
            when (player.play(pcm, loop = loop)) {
                SoundPlayer.PlaybackStart.STARTED -> message = "Playing · $label"
                SoundPlayer.PlaybackStart.EMPTY -> { stop(); message = "Preview contains no frames" }
                SoundPlayer.PlaybackStart.OUTPUT_UNAVAILABLE -> { stop(); message = "Cannot open audio output" }
            }
        } catch (cancelled: CancellationException) { if (token == version) stop(); throw cancelled }
        catch (error: Exception) { if (token == version) { stop(); message = "Cannot preview: ${error.message}" } }
        finally { if (token == version) busy = false }
    }
    fun releaseKey(value: Int) {
        if (!held || value != pitch) return
        version++; held = false; active = false; busy = false; message = "Key released"
        heldPlayer.release()
    }
    fun stop() {
        held = false; heldPlayer.stop()
        version++; player.onComplete = null; player.stop()
        active = false; busy = false; message = ""
    }
}
