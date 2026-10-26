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
    var message by mutableStateOf("")
    var problem by mutableStateOf<String?>(null)
        private set
    private var revision = 0
    private var audioVersion = 0

    fun update(value: SampleDraft) {
        stop()
        revision++
        draft = value
        encoded = null
        preparing = false
        problem = null
        message = ""
    }

    suspend fun prepare() {
        val expected = revision
        val input = draft
        preparing = true
        try {
            val result = withContext(Dispatchers.Default) { SampleAuthoring.encode(input) }
            if (expected == revision) { encoded = result; problem = null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (expected == revision) problem = error.message ?: "Cannot encode sample" }
        finally { if (expected == revision) preparing = false }
    }

    suspend fun preview(ram: ByteArray) {
        val sample = encoded ?: return
        stop()
        val token = audioVersion
        val expected = revision
        previewing = true
        try {
            val pcm = withContext(Dispatchers.Default) { InstrumentAudition.render(SampleAuthoring.preview(ram, sample)) }
            if (expected != revision || token != audioVersion) return
            player.onComplete = {
                if (token == audioVersion && expected == revision) { playing = false; message = "Preview finished" }
            }
            when (player.play(pcm)) {
                SoundPlayer.PlaybackStart.STARTED -> { playing = true; message = "Playing encoded BRR" }
                SoundPlayer.PlaybackStart.EMPTY -> message = "Preview contains no frames"
                SoundPlayer.PlaybackStart.OUTPUT_UNAVAILABLE -> message = "Cannot open audio output"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (expected == revision && token == audioVersion) message = "Native preview failed: ${error.message}" }
        finally { if (expected == revision && token == audioVersion) previewing = false }
    }

    fun stop() {
        audioVersion++
        player.onComplete = null
        player.stop()
        previewing = false
        playing = false
    }
}
