package com.supermetroid.editor.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Session-local asset editor. It has no EditorState/project/ROM-write access. */
internal class SampleWorkbenchState(private val player: SoundPlayer = SoundPlayer()) {
    var draft by mutableStateOf(SampleAuthoring.generate(SampleAuthoring.Shape.SINE))
        private set
    var encoded by mutableStateOf<SampleAuthoring.Encoded?>(null)
        private set
    var preparing by mutableStateOf(false)
        private set
    var previewing by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var live by mutableStateOf(true)
        private set
    var message by mutableStateOf("")
    var problem by mutableStateOf<String?>(null)
        private set
    private var revision = 0
    private var audioVersion = 0
    private data class Audition(val message: String, val build: (SampleAuthoring.Encoded) -> InstrumentAudition.Preview)
    private var audition by mutableStateOf<Audition?>(null)
    val auditionActive: Boolean get() = audition != null

    fun update(value: SampleDraft) {
        if (value == draft) return
        if (!live) stop() else { audioVersion++; previewing = false }
        revision++
        draft = value
        encoded = null
        preparing = false
        problem = null
        if (audition == null) message = ""
    }

    suspend fun prepare() {
        val expected = revision
        val input = draft
        preparing = true
        try {
            val result = withContext(Dispatchers.Default) { SampleAuthoring.encode(input) }
            if (expected == revision) {
                encoded = result; problem = null
                // Keep the last valid audio playing during encoding and rendering.
                if (live && audition != null) refreshPreview()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (expected == revision) problem = error.message ?: "Cannot encode sample" }
        finally { if (expected == revision) preparing = false }
    }

    suspend fun preview(ram: ByteArray) {
        if (encoded == null) return
        audition = Audition("Playing encoded BRR") { SampleAuthoring.preview(ram, it) }
        refreshPreview()
    }

    suspend fun previewInBank(bank: SampleBankPreview) {
        if (encoded == null) return
        audition = Audition("Playing as ${bank.soundName}", bank::build)
        refreshPreview()
    }

    private suspend fun refreshPreview() {
        val target = audition ?: return
        val sample = encoded ?: return
        val token = ++audioVersion
        val expected = revision
        val loop = live
        previewing = true
        try {
            val pcm = withContext(Dispatchers.Default) { InstrumentAudition.render(target.build(sample)) }
            if (expected != revision || token != audioVersion) return
            player.onComplete = {
                if (token == audioVersion && expected == revision) {
                    playing = false; audition = null; message = "Preview finished"
                }
            }
            when (player.play(pcm, loop = loop)) {
                SoundPlayer.PlaybackStart.STARTED -> { playing = true; message = target.message }
                SoundPlayer.PlaybackStart.EMPTY -> { stop(); message = "Preview contains no frames" }
                SoundPlayer.PlaybackStart.OUTPUT_UNAVAILABLE -> { stop(); message = "Cannot open audio output" }
            }
        } catch (cancelled: CancellationException) {
            // A newer edit owns the session. Stop/close already invalidated its token.
            if (token == audioVersion) stop()
            throw cancelled
        } catch (error: Exception) {
            if (expected == revision && token == audioVersion) { stop(); message = "Native preview failed: ${error.message}" }
        } finally { if (expected == revision && token == audioVersion) previewing = false }
    }

    fun setLiveEnabled(enabled: Boolean) {
        if (enabled == live) return
        stop()
        live = enabled
    }

    fun stop() {
        val wasActive = playing || previewing || audition != null
        audioVersion++
        audition = null
        player.onComplete = null
        player.stop()
        previewing = false
        playing = false
        if (wasActive) message = "Preview stopped"
    }
}
